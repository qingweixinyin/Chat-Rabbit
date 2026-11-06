"""把 chatrabbit 部署到 AC68U（SFTP 上传 + 执行 install.sh）。

用法：
  python deploy.py                 # 上传 dist/chatrabbit 并安装
  python deploy.py --restart       # 只重启服务
  python deploy.py --status        # 只看状态

环境变量：ROUTER_HOST / ROUTER_USER / ROUTER_PASS（默认 192.168.6.1 / asus / 需填）
"""
import argparse
import os
import sys

import paramiko

HERE = os.path.dirname(os.path.abspath(__file__))
HOST = os.environ.get("ROUTER_HOST", "192.168.6.1")
USER = os.environ.get("ROUTER_USER", "asus")
PWD = os.environ.get("ROUTER_PASS", "")
REMOTE = "/tmp/chatrabbit"

FILES = [
    ("pkg/install.sh", "install.sh"),
    ("pkg/scripts/chatrabbit_ctl.sh", "scripts/chatrabbit_ctl.sh"),
    ("pkg/webs/Module_chatrabbit.asp", "webs/Module_chatrabbit.asp"),
]


def pick_binary(cli):
    """按远端 CPU 架构选择 armv7 / arm64 二进制。

    优先级：环境变量 ROUTER_BIN > 远端 uname -m 探测 > 旧文件名 dist/chatrabbit
    """
    forced = os.environ.get("ROUTER_BIN", "").strip()
    if forced:
        return forced
    stdin, stdout, stderr = cli.exec_command("uname -m")
    arch = stdout.read().decode().strip()
    print(f"远端架构: {arch}")
    cand = []
    if arch in ("aarch64", "arm64"):
        cand = ["dist/chatrabbit_aarch64", "dist/chatrabbit"]
    elif arch in ("armv7l", "armv6l", "arm"):
        cand = ["dist/chatrabbit_armv7", "dist/chatrabbit"]
    else:
        cand = ["dist/chatrabbit"]
    for c in cand:
        if os.path.exists(os.path.join(HERE, c)):
            return c
    return "dist/chatrabbit"


def run(cli, cmd, timeout=60):
    stdin, stdout, stderr = cli.exec_command(cmd, timeout=timeout)
    out = stdout.read().decode("utf-8", "replace")
    err = stderr.read().decode("utf-8", "replace")
    rc = stdout.channel.recv_exit_status()
    print(out, end="")
    if err.strip():
        print("--- stderr ---")
        print(err, end="")
    print(f"[exit {rc}]")
    return rc


def put_file(cli, local_path, remote_path):
    """dropbear 默认没 sftp-server，用 `cat > file` 走 SSH 标准输入传二进制。"""
    size = os.path.getsize(local_path)
    print(f"upload {local_path} -> {remote_path}  ({size} bytes)")
    stdin, stdout, stderr = cli.exec_command(f"cat > {remote_path}", timeout=180)
    with open(local_path, "rb") as f:
        while True:
            chunk = f.read(32768)
            if not chunk:
                break
            stdin.write(chunk)
        stdin.flush()
        stdin.channel.shutdown_write()
    rc = stdout.channel.recv_exit_status()
    err = stderr.read().decode("utf-8", "replace")
    if rc != 0:
        raise RuntimeError(f"upload failed rc={rc}: {err[:300]}")
    return size


def md5(cli, remote_path):
    stdin, stdout, stderr = cli.exec_command(f"md5sum {remote_path}")
    out = stdout.read().decode().strip()
    return out.split()[0] if out else ""


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--restart", action="store_true")
    ap.add_argument("--status", action="store_true")
    a = ap.parse_args()
    if not PWD:
        sys.exit("请设置 ROUTER_PASS 环境变量")

    cli = paramiko.SSHClient()
    cli.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    cli.connect(HOST, 22, USER, PWD, timeout=15, look_for_keys=False, allow_agent=False)
    try:
        if a.status:
            run(cli, "/koolshare/chatrabbit/scripts/chatrabbit_ctl.sh status")
            return
        if a.restart:
            run(cli, "/koolshare/chatrabbit/scripts/chatrabbit_ctl.sh restart")
            run(cli, "/koolshare/chatrabbit/scripts/chatrabbit_ctl.sh status")
            return

        bin_rel = pick_binary(cli)
        files = [(bin_rel, "chatrabbit")] + FILES
        for local, rel in files:
            p = os.path.join(HERE, local)
            if not os.path.exists(p):
                sys.exit(f"缺少文件 {p}（先跑 build.ps1）")
        run(cli, f"rm -rf {REMOTE} && mkdir -p {REMOTE}/scripts {REMOTE}/webs")

        import hashlib
        for local, rel in files:
            p = os.path.join(HERE, local)
            remote = f"{REMOTE}/{rel}"
            put_file(cli, p, remote)
            local_md5 = hashlib.md5(open(p, "rb").read()).hexdigest()
            remote_md5 = md5(cli, remote)
            ok = "OK" if local_md5 == remote_md5 else "MISMATCH!"
            print(f"  md5 {ok} local={local_md5} remote={remote_md5}")

        run(cli, f"cd {REMOTE} && sh install.sh", timeout=180)

        # 运行态二进制是压缩态：把新二进制 gzip 后覆盖 bin/chatrabbit.gz，并删掉未压缩副本
        import gzip
        import tempfile

        bin_path = os.path.join(HERE, bin_rel)
        gz_local = os.path.join(tempfile.gettempdir(), "chatrabbit.deploy.gz")
        with open(bin_path, "rb") as f:
            raw = f.read()
        with gzip.GzipFile(gz_local, "wb", compresslevel=9, mtime=0) as f:
            f.write(raw)
        print(f"upload gz -> /koolshare/chatrabbit/bin/chatrabbit.gz ({len(raw)} -> {os.path.getsize(gz_local)})")
        put_file(cli, gz_local, "/koolshare/chatrabbit/bin/chatrabbit.gz")
        run(cli, "chmod 755 /koolshare/chatrabbit/bin/chatrabbit.gz; rm -f /koolshare/chatrabbit/bin/chatrabbit")
        run(cli, "/koolshare/chatrabbit/scripts/chatrabbit_ctl.sh restart")
        run(cli, "/koolshare/chatrabbit/scripts/chatrabbit_ctl.sh status")
    finally:
        cli.close()


if __name__ == "__main__":
    main()
