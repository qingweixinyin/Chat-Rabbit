#!/bin/sh
# chatrabbit plugin installer (koolshare/koolcenter offline package entry).
# Called by softcenter without arguments; must exit 0.

[ -f /koolshare/scripts/base.sh ] && . /koolshare/scripts/base.sh

BASE=/koolshare/chatrabbit
SRC=$(cd "$(dirname "$0")" && pwd)
VER="1.7.3-fix"
TITLE="ChatRabbit"

echo "== chatrabbit plugin install v${VER} =="

# ---------- 1. dirs ----------
mkdir -p "$BASE/bin" "$BASE/scripts" "$BASE/media" "$BASE/logs" "$BASE/webs"

# JFFS 余量预检：内置铃声 6 个合计只有 ~550KB（不是 5.6MB），
# 之前用固定的 12MB 阈值 → 余量 12MB 以下的路由器只装 alarm_classic，
# 表现为"升级后合成铃声消失了"。现在按**实际体积**算需求，再留 512KB 余量。
RING_BYTES=$(du -sk "$SRC/media" 2>/dev/null | awk '{print $1*1024}')
[ -z "$RING_BYTES" ] && RING_BYTES=600000
NEED_KB=$(( (RING_BYTES + 1023) / 1024 + 512 ))
JFFS_FREE_KB=$(df -k 2>/dev/null | awk '$6=="/jffs"{print $4}' | head -1)
[ -z "$JFFS_FREE_KB" ] && JFFS_FREE_KB=0
echo "   /jffs free: ${JFFS_FREE_KB}KB (builtin ringtones need ~${NEED_KB}KB)"
if [ -d "$SRC/media" ]; then
    n=0
    kept=0
    LIMIT=99
    if [ "$JFFS_FREE_KB" -le 0 ] || [ "$JFFS_FREE_KB" -lt "$NEED_KB" ]; then
        LIMIT=1
        echo "   ⚠ /jffs 余量不足（剩 ${JFFS_FREE_KB}KB，需要 ${NEED_KB}KB）：只装 1 个内置铃声"
    else
        echo "   /jffs 余量充足：安装全部内置铃声"
    fi
    for f in "$SRC"/media/*.mp3; do
        [ -f "$f" ] || continue
        b=$(basename "$f")
        if [ ! -f "$BASE/media/$b" ]; then
            if [ "$kept" -ge "$LIMIT" ]; then
                continue
            fi
            # 空间紧张时优先保留 alarm_classic
            if [ "$LIMIT" -eq 1 ] && [ "$b" != "alarm_classic.mp3" ]; then
                continue
            fi
            cp -f "$f" "$BASE/media/$b" && { n=$((n+1)); kept=$((kept+1)); }
        fi
    done
    echo "   builtin ringtones added: $n"
fi

# ---------- 2. scripts + web page + icon ----------
if [ -f "$SRC/scripts/chatrabbit_ctl.sh" ]; then
    cp -f "$SRC/scripts/chatrabbit_ctl.sh" "$BASE/scripts/chatrabbit_ctl.sh"
    chmod 755 "$BASE/scripts/chatrabbit_ctl.sh"
fi
if [ -f "$SRC/webs/Module_chatrabbit.asp" ]; then
    mkdir -p /koolshare/webs
    cp -f "$SRC/webs/Module_chatrabbit.asp" /koolshare/webs/Module_chatrabbit.asp
    cp -f "$SRC/webs/Module_chatrabbit.asp" "$BASE/webs/Module_chatrabbit.asp"
fi
# softcenter list icon: must be /koolshare/res/icon-<module>.png
if [ -f "$SRC/res/icon-chatrabbit.png" ]; then
    mkdir -p /koolshare/res
    cp -f "$SRC/res/icon-chatrabbit.png" /koolshare/res/icon-chatrabbit.png
    echo "   icon ok"
fi

# ---------- 3. config (keep existing) ----------
if [ ! -f "$BASE/chatrabbit.conf" ]; then
cat > "$BASE/chatrabbit.conf" <<'EOF'
{
  "lan_ip": "",
  "http_port": 28081,
  "mqtt_port": 1883,
  "extra_mqtt_port": 24056,
  "api_port": 8890,
  "media_dir": "/koolshare/chatrabbit/media",
  "log_dir": "/koolshare/chatrabbit/logs",
  "gap_seconds": 6,
  "log_max_mb": 1,
  "time_mode": 10,
  "tz_hours": 8,
  "once_mode": "weekday",
  "ws_accept": true,
  "api_key": "69043ca347f34348b1e043c5429fef16",
  "device_id": "AI91144031DD06A2",
  "openid": "o0LocalOpenid0000000000000000",
  "kick_on_command": false
}
EOF
fi

# ---------- 3b. ensure ws_accept=true (needed by ringtone/mode switching) ----------
if [ -f "$BASE/chatrabbit.conf" ]; then
    if grep -q '"ws_accept"' "$BASE/chatrabbit.conf"; then
        sed -i 's/"ws_accept": *false/"ws_accept": true/' "$BASE/chatrabbit.conf"
    else
        awk 'NR==1 && $0 ~ /^[ ]*\{/ {print; print "  \"ws_accept\": true,"; next} {print}' \
            "$BASE/chatrabbit.conf" > "$BASE/chatrabbit.conf.tmp" && mv -f "$BASE/chatrabbit.conf.tmp" "$BASE/chatrabbit.conf"
    fi
    echo "   ws_accept -> true"
    # 校时格式固定为「纯文本秒」：设备只认这个（实测唯一正确格式）
    if grep -q '"time_mode"' "$BASE/chatrabbit.conf"; then
        sed -i 's/"time_mode": *[0-9]*/"time_mode": 10/' "$BASE/chatrabbit.conf"
    else
        awk 'NR==1 && $0 ~ /^[ ]*\{/ {print; print "  \"time_mode\": 10,"; next} {print}' \
            "$BASE/chatrabbit.conf" > "$BASE/chatrabbit.conf.tmp" && mv -f "$BASE/chatrabbit.conf.tmp" "$BASE/chatrabbit.conf"
    fi
    echo "   time_mode -> 10 (plain seconds)"
    # 设备本地时区（路由器系统时区可能是 UTC，影响"按时间匹配闹钟铃声"）
    if grep -q '"tz_hours"' "$BASE/chatrabbit.conf"; then
        sed -i 's/"tz_hours": *[0-9-]*/"tz_hours": 8/' "$BASE/chatrabbit.conf"
    else
        awk 'NR==1 && $0 ~ /^[ ]*\{/ {print; print "  \"tz_hours\": 8,"; next} {print}' \
            "$BASE/chatrabbit.conf" > "$BASE/chatrabbit.conf.tmp" && mv -f "$BASE/chatrabbit.conf.tmp" "$BASE/chatrabbit.conf"
    fi
    echo "   tz_hours -> 8"
    # API 端口迁移 8898 -> 8890（8898 与 kongke 劫持面板冲突；程序仍会尽力兼容监听 8898）
    if grep -q '"api_port"' "$BASE/chatrabbit.conf"; then
        OLD_API_PORT=$(sed -n 's/.*"api_port": *\([0-9]*\).*/\1/p' "$BASE/chatrabbit.conf" | head -n1)
        sed -i 's/"api_port": *[0-9]*/"api_port": 8890/' "$BASE/chatrabbit.conf"
        echo "   api_port ${OLD_API_PORT:-?} -> 8890"
    else
        awk 'NR==1 && $0 ~ /^[ ]*\{/ {print; print "  \"api_port\": 8890,"; next} {print}' \
            "$BASE/chatrabbit.conf" > "$BASE/chatrabbit.conf.tmp" && mv -f "$BASE/chatrabbit.conf.tmp" "$BASE/chatrabbit.conf"
        echo "   api_port -> 8890 (inserted)"
    fi
fi

# ---------- 4. binary: gz on JFFS + wrapper running from /tmp ----------
# 架构选择以"用户态位数"为准，不能只看 uname -m：
#   华硕 HND（BE3600 / AX89X 等）= 64 位内核 + **32 位 armhf 用户态**，
#   uname -m 报 aarch64，但只跑 ELF32 → 装 arm64 会 exec 失败（黑屏/无监听）。
# 判断：读 /bin/busybox 的 ELF class 字节（1=ELF32, 2=ELF64）。
USR_CLASS=""
if [ -r /bin/busybox ]; then
    USR_CLASS=$(od -An -tu1 -N5 /bin/busybox 2>/dev/null | awk '{print $5}')
fi
ARCH=$(uname -m)
case "$USR_CLASS" in
    2) BIN_SRC="$SRC/bin/chatrabbit_arm64.gz"; PICK="arm64(ELF64 用户态)" ;;
    1) BIN_SRC="$SRC/bin/chatrabbit_armv7.gz"; PICK="armv7(ELF32 用户态)" ;;
    *)
        case "$ARCH" in
            aarch64|arm64) BIN_SRC="$SRC/bin/chatrabbit_arm64.gz"; PICK="arm64(uname 推测)" ;;
            *)             BIN_SRC="$SRC/bin/chatrabbit_armv7.gz"; PICK="armv7(uname 推测)" ;;
        esac
        ;;
esac
[ -f "$BIN_SRC" ] || BIN_SRC="$SRC/bin/chatrabbit_armv7.gz"
[ -f "$BIN_SRC" ] || BIN_SRC="$SRC/bin/chatrabbit_aarch64.gz"
if [ -f "$BIN_SRC" ]; then
    echo "   arch=$ARCH userland_class=${USR_CLASS:-?} selected=$(basename "$BIN_SRC") [$PICK]"
    rm -f "$BASE/bin/chatrabbit" "$BASE/bin/chatrabbit.real"
    cp -f "$BIN_SRC" "$BASE/bin/chatrabbit.gz"
    cat > "$BASE/bin/chatrabbit" <<'WRAP'
#!/bin/sh
# runtime wrapper: only chatrabbit.gz is stored on JFFS; decompress to /tmp and exec
GZ="$(dirname "$0")/chatrabbit.gz"
TMP=/tmp/chatrabbit.bin
STAMP=/tmp/chatrabbit.bin.stamp
[ -f "$GZ" ] || exit 127
SZ=$(ls -l "$GZ" | awk '{print $5}')
if [ ! -x "$TMP" ] || [ "$(cat "$STAMP" 2>/dev/null)" != "$SZ" ]; then
    NEW="/tmp/chatrabbit.bin.new.$$"
    gunzip -c "$GZ" > "$NEW" 2>/dev/null || { rm -f "$NEW"; exit 127; }
    chmod 755 "$NEW" 2>/dev/null
    mv -f "$NEW" "$TMP" 2>/dev/null || { rm -f "$NEW"; exit 127; }
    echo "$SZ" > "$STAMP" 2>/dev/null
fi
exec "$TMP" "$@"
WRAP
    chmod 755 "$BASE/bin/chatrabbit" "$BASE/bin/chatrabbit.gz"
    echo "   arch=$ARCH binary=$(du -k "$BASE/bin/chatrabbit.gz" | awk '{print $1}')KB"
fi

# ---------- 5. boot hooks ----------
mkdir -p /koolshare/init.d
cat > /koolshare/init.d/V99chatrabbit.sh <<'EOF'
#!/bin/sh
/koolshare/chatrabbit/scripts/chatrabbit_ctl.sh boot-start
EOF
cat > /koolshare/init.d/T99chatrabbit.sh <<'EOF'
#!/bin/sh
/koolshare/chatrabbit/scripts/chatrabbit_ctl.sh stop
EOF
chmod 755 /koolshare/init.d/V99chatrabbit.sh /koolshare/init.d/T99chatrabbit.sh

mkdir -p /jffs/scripts
add_hook() {
    hookfile="$1"; cmd="$2"
    [ -f "$hookfile" ] || { echo "#!/bin/sh" > "$hookfile"; chmod +x "$hookfile"; }
    grep -q "chatrabbit_ctl.sh" "$hookfile" 2>/dev/null || echo "$cmd" >> "$hookfile"
    chmod +x "$hookfile"
}
add_hook /jffs/scripts/services-start "/koolshare/chatrabbit/scripts/chatrabbit_ctl.sh boot-start"
add_hook /jffs/scripts/firewall-start "/koolshare/chatrabbit/scripts/chatrabbit_ctl.sh start"
# U 盘在插件启动后才插上时，自动把 media 切到 U 盘（并把已有的歌迁过去）
add_hook /jffs/scripts/post-mount "/koolshare/chatrabbit/scripts/chatrabbit_ctl.sh media 2>/dev/null"

# ---------- 6. softcenter registration ----------
if [ -x /usr/bin/dbus ]; then
    dbus set softcenter_module_chatrabbit_name="chatrabbit"
    dbus set "softcenter_module_chatrabbit_title=${TITLE}"
    dbus set softcenter_module_chatrabbit_description="ChatRabbit local cloud: alarm/music/pairing"
    dbus set softcenter_module_chatrabbit_version="$VER"
    dbus set softcenter_module_chatrabbit_install="1"
    echo "   dbus registered $VER"
fi

# ---------- 7. start (force fresh runtime binary) ----------
"$BASE/scripts/chatrabbit_ctl.sh" stop 2>/dev/null || true
rm -f /tmp/chatrabbit.bin /tmp/chatrabbit.bin.stamp
"$BASE/scripts/chatrabbit_ctl.sh" restart || true
sleep 2

# 自检（关键）：必须查"正在跑的那个进程"报的版本，而不是磁盘上的二进制。
# 否则旧进程持有旧 inode 时，磁盘 version 显示新、页面/接口仍是旧版本。
API_PORT=$(grep -o '"api_port":[[:space:]]*[0-9]*' "$BASE/chatrabbit.conf" 2>/dev/null | grep -o '[0-9]*$')
[ -z "$API_PORT" ] && API_PORT=8890
RUNVER=""
for i in 1 2 3 4 5; do
    sleep 1
    RUNVER=$(curl -s --max-time 3 "http://127.0.0.1:$API_PORT/api/status" 2>/dev/null \
        | grep -o '"version":"[^"]*"' | head -1 | sed 's/.*:"//; s/"//')
    [ -n "$RUNVER" ] && break
done
if [ -z "$RUNVER" ]; then
    RUNVER=$("$BASE/bin/chatrabbit" version 2>/dev/null | awk '{print $2}')
fi
echo "   running service version: ${RUNVER:-unknown} (expect $VER)"
if [ -n "$RUNVER" ] && [ "$RUNVER" != "$VER" ]; then
    echo "   WARN: 运行中的服务还是旧版本，强制清理残留进程后重启一次"
    "$BASE/scripts/chatrabbit_ctl.sh" stop 2>/dev/null || true
    rm -f /tmp/chatrabbit.bin /tmp/chatrabbit.bin.stamp
    "$BASE/scripts/chatrabbit_ctl.sh" restart || true
    sleep 2
    RUNVER=$(curl -s --max-time 3 "http://127.0.0.1:$API_PORT/api/status" 2>/dev/null \
        | grep -o '"version":"[^"]*"' | head -1 | sed 's/.*:"//; s/"//')
    echo "   retry -> running service version: ${RUNVER:-unknown}"
fi

IP=$(nvram get lan_ipaddr 2>/dev/null)
[ -z "$IP" ] && IP=192.168.1.1
echo "== install done =="
echo "   page: http://$IP/Module_chatrabbit.asp"
echo "   direct: http://$IP:$API_PORT/"
exit 0
