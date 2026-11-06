# chatrabbit —— AC68U 路由器端（koolshare armv7l 静态二进制）

> ⚠️ **本文是早期设计说明，端口等部分内容已过时**（当时用 8081/40506/8899）。
> **当前状态、端口、构建/部署/校验、已验证快照、坑位清单 → 看 [`HANDOVER.md`](HANDOVER.md)。**

目标：把"假图灵云"从电脑搬到路由器，让玩具不再依赖 PC。格式对齐本机已装的 `kongke` 插件
（`/koolshare/<name>/bin` + `/koolshare/init.d/V99`、`T99` + `/koolshare/webs/Module_<name>.asp`）。

## 为什么是 Go 静态二进制

| 依赖 | 路由器现状 |
|---|---|
| Python / Entware / opkg / mosquitto | **都没有**（AC68U Merlin-KoolShare 386.2） |
| busybox / iptables / nohup | ✅ 有 |
| 用户态 | ELF32 ARM（`armv7l`）→ 必须 `GOOS=linux GOARCH=arm GOARM=7 CGO_ENABLED=0` |

本机已有 Go 1.19：`E:\tools\go1.19\go\bin\go.exe`。

## 一个二进制提供三个服务

| 端口 | 作用 | 说明 |
|---|---|---|
| `:8081` | 假图灵 HTTP + `/media/` 音频 | 设备硬编码连 80，而路由器 80 被 ASUS httpd 占用，所以监听 8081，并用 iptables 按**设备 MAC** 把 80 重定向过来（不影响路由器管理页） |
| `:1883` / `:40506` | 假 MQTT broker | 设备登录、订阅 `iot/<apiKey>/<deviceId>`；下行按 `gap_seconds` 节流 |
| `:8899` | 控制 API | 给网页 / 脚本 / 手机用：`/api/status`、`/api/play`、`/api/stop`、`/api/cmd`、`/api/list`、`/api/upload` |

DNS 用路由器自带的 dnsmasq：`address=/turingapi.com/<路由器IP>`（`chatrabbit dns-fix` 自动写）。

## 构建

```powershell
cd 'E:\Claude official\chat rabbit\router'
powershell -ExecutionPolicy Bypass -File .\build.ps1 -GoExe 'E:\tools\go1.19\go\bin\go.exe'
# 产物 dist\chatrabbit  (linux/arm, ~5.2MB)
```

## 部署

```powershell
$env:ROUTER_HOST='192.168.6.1'; $env:ROUTER_USER='asus'; $env:ROUTER_PASS='<密码>'
python deploy.py
```

`deploy.py` 会：sftp 上传二进制与脚本 → 在路由器上执行 `pkg/install.sh`：
1. 安装到 `/koolshare/chatrabbit/{bin,scripts,media,logs,webs}`
2. 写默认 `chatrabbit.conf`
3. 装 `/koolshare/init.d/V99chatrabbit.sh`、`T99chatrabbit.sh`（开/关机钩子）
4. 复制 `Module_chatrabbit.asp` 到 `/koolshare/webs/`
5. `chatrabbit dns-fix` 把 `turingapi.com` 指向本机
6. 启动服务（含 iptables 重定向）

## 日常操作

```sh
# 路由器上
/koolshare/chatrabbit/scripts/chatrabbit_ctl.sh status|start|stop|restart
/koolshare/chatrabbit/bin/chatrabbit ctl status
/koolshare/chatrabbit/bin/chatrabbit ctl play track1.mp3
/koolshare/chatrabbit/bin/chatrabbit ctl op 3        # 3=呼吸灯开
tail -f /koolshare/chatrabbit/logs/serve.log
```

控制页：`http://192.168.6.1/Module_chatrabbit.asp`（调用 `:8899` API，带 CORS）

## 切换与回滚

- 切到路由器后，**设备必须重启一次**才会重新解析 `turingapi.com` 到路由器 IP（DNS 缓存）。
- 回滚到电脑方案：
  ```sh
  /koolshare/chatrabbit/scripts/chatrabbit_ctl.sh stop   # 会同时删 iptables 规则
  # 再把 /jffs/configs/dnsmasq.conf.add 里的 address=/turingapi.com/ 行改成电脑 IP 或删掉
  service restart_dnsmasq
  ```

## 已知限制 / 注意

1. **音量**：云控 `type=2 operate=0/1` 在该固件上无效，只能物理键调（vol 0=振动 1=静音 2/3/4=音量1/2/3）。
2. `:80` 重定向只针对设备 MAC（`14:40:31:dd:06:a2`），换设备需改 `chatrabbit_ctl.sh` 里的 `DEVMAC`。
3. 路由器 `/jffs` 只剩约 52MB，media 目录别塞太多无损音乐；大 WAV 建议先转 MP3。
4. 版本号 `AC791N_NJ_1V2_V2.0` 的 OTA 接口返回"无更新"，避免设备尝试升级。
5. 设备很脆：连续快速下发会让它的 MQTT 任务卡死，`gap_seconds` 不要低于 5。

---

## HND 平台（AX 系列 / ARM64）部署说明

### 架构对照
| 路由器 | CPU | 使用文件 |
|---|---|---|
| RT-AC68U / 老款 Merlin | armv7l | `dist\chatrabbit_armv7`（包：`chatrabbit-ac68u-armv7.tar.gz`） |
| RT-AX88U / AX89X 等 HND | aarch64 | `dist\chatrabbit_aarch64`（包：`chatrabbit-hnd-arm64.tar.gz`） |

### 编译
```powershell
# armv7
powershell -ExecutionPolicy Bypass -File .\build.ps1 -GoExe <go.exe> -Out dist\chatrabbit_armv7 -GOARCH arm -GOARM 5
# arm64 (HND)
powershell -ExecutionPolicy Bypass -File .\build.ps1 -GoExe <go.exe> -Out dist\chatrabbit_aarch64 -GOARCH arm64
```

### 部署（两种方式）
**方式 A：deploy.py 自动识别架构**（推荐，需 SSH）
```powershell
$env:ROUTER_PASS='<密码>'
python deploy.py
```
会自动执行 `uname -m`，armv7l 用 `chatrabbit_armv7`，aarch64 用 `chatrabbit_aarch64`。

**方式 B：手动上传压缩包**
```sh
# 路由器上
cd /tmp && tar -xzf chatrabbit-hnd-arm64.tar.gz -C /tmp/chatrabbit-pkg
cd /tmp/chatrabbit-pkg && sh install.sh
```

### HND 注意事项
1. **必须开启 JFFS 自定义脚本**：路由器后台 → 系统管理 → 系统设置 → `Enable JFFS custom scripts and configs` = 是
   （否则 `/jffs/scripts/services-start` 不会执行，重启后服务不自启）
2. install.sh 已同时安装三种自启钩子：
   - `/koolshare/init.d/V99chatrabbit.sh`（koolshare 软件中心）
   - `/jffs/scripts/services-start`（Merlin/HND）
   - `/jffs/scripts/firewall-start`（防火墙重启后重新加 iptables 规则）
3. HND 上 iptables 为 xtables 兼容层，`-m mac --mac-source` 规则依然有效
4. 服务端口与 AC68U 完全相同：8081 / 8898 / 1883 / 40506
5. 若 `uname -m` 显示 `aarch64` 但运行报 `not found`，检查是否误传了 armv7 二进制

---

## 36.5 TUF-BE3600（HND）架构结论与部署

### 固件实测（TUF-BE3600_3.0.0.6_102_37307_koolcenter_nand_squashfs）
```
容器头        : FDT/ASUS HND 格式
内核          : 4.19.294 SMP ... aarch64      ← 64 位内核
工具链        : aarch64-linux-gcc (Buildroot 2021.02.4) 10.3.0
squashfs 位置 : 0x97EA04 (le)
```
但 **用户态为 armv7l（32 位）**（另一个项目固件分析结论），属于
"64 位内核 + 32 位用户态"（BCM HND 平台常见形态）。

### 部署选择
| 设备 | 用户态 | 使用二进制 |
|---|---|---|
| RT-AC68U | armv7l | chatrabbit_armv7 |
| **TUF-BE3600（HND）** | **armv7l** | **chatrabbit_armv7**（arm64 内核带 32 位兼容） |
| 真正 aarch64 用户态的 HND 机型 | aarch64 | chatrabbit_aarch64（备用） |

包：`dist\chatrabbit-hnd-be3600-armv7.tar.gz`

### 现场确认方法（BE3600 SSH 上执行）
```sh
uname -m                      # armv7l -> 用 armv7 包；aarch64 -> 用 arm64 包
head -c 20 /bin/busybox | hexdump -C   # ELF class: 01=32位, 02=64位
```

## 36.6 日志与存储管理（2026-10-27 晚）

### 日志
- 每次启动新建 `logs/<日期-时间>.log`，且 `serve.log` 为 stdout 重定向 → 会累积
- 新增 API：`GET /api/log/stat`（总大小/文件数/上限）、`POST /api/log/clear`（清空并删历史）
- 页面「日志」卡片：显示大小 + **清空日志** 按钮（每 30 秒刷新）
- 自动限容：单文件超过 `log_max_mb`（默认 **1 MB**，本次已按用户要求改 1）自动截断
- 本次清理：释放 81 个历史文件约 1.17MB

### 存储位置（重要）
```
/koolshare  ->  jffs/.koolshare        （JFFS 分区，62.8MB）
媒体目录    :  /koolshare/chatrabbit/media   → 实际落在 JFFS
清理前占用  :  11.8 MB（含 2×1.9MB OTA 测试包、1.5MB 音乐等）
清理后占用  :  7.9 MB（alarm_voice/custom_ring/tone/track1 + 2 首心咒 mp3）
JFFS 余量   :  约 40.6 MB
```
新增 API：
- `GET /api/storage` → media_dir、media_size、media_count、fs_total/fs_used/fs_free
- `POST /api/media/clean` → 删除 media 目录下所有非 `.mp3` 文件（页面按钮「清理非音频文件」）

### 迁到外挂硬盘（可选）
当前路由器未挂载任何 USB 存储。若有 U 盘/硬盘：
```sh
# 挂载点通常为 /tmp/mnt/sda1
mkdir -p /tmp/mnt/sda1/chatrabbit-media
cp -a /koolshare/chatrabbit/media/. /tmp/mnt/sda1/chatrabbit-media/
sed -i 's#"media_dir": *"[^"]*"#"media_dir": "/tmp/mnt/sda1/chatrabbit-media"#' /koolshare/chatrabbit/chatrabbit.conf
/koolshare/chatrabbit/scripts/chatrabbit_ctl.sh restart
```
注意：需在 `/jffs/scripts/services-start` 中加挂载等待（如 `sleep 5`），否则开机时盘还没挂载会导致媒体丢失。
