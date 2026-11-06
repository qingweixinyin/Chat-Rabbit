#!/bin/sh
# chatrabbit service control (BusyBox sh)
BASE=/koolshare/chatrabbit
BIN=$BASE/bin/chatrabbit
BINGZ=$BASE/bin/chatrabbit.gz
RUNBIN=/tmp/chatrabbit.bin
CONF=$BASE/chatrabbit.conf
PID=/tmp/chatrabbit.pid
LOG=$BASE/logs/serve.log
DEVMAC=14:40:31:dd:06:a2
# 注意：iptables 把 MAC 统一显示成大写，删除规则时必须用同样的写法，
# 所以脚本内部一律用大写 MAC。
DEVMAC=$(echo "$DEVMAC" | tr 'a-f' 'A-F')
# 默认设备 MAC 单独存一份：nat_add/nat_del 的循环会改写 DEVMAC，
# 如果 dev_macs() 用可变的 DEVMAC 当默认值，第二次调用就会漏掉默认设备。
DEVMAC_DEFAULT=$DEVMAC
# 多玩具支持：追加的 MAC 写在这个文件里（一行一个，可用 # 注释）。
# 不放在 chatrabbit.conf（JSON）里是为了避免破坏 JSON 结构。
DEVMAC_FILE=$BASE/dev_macs.conf
dev_macs() {
    echo "$DEVMAC_DEFAULT"
    if [ -f "$DEVMAC_FILE" ]; then
        sed -e 's/#.*//' -e 's/[[:space:]]//g' "$DEVMAC_FILE" 2>/dev/null \
            | grep -Ei '^([0-9a-f]{2}:){5}[0-9a-f]{2}$' \
            | tr 'a-f' 'A-F' \
            | grep -vi "^$DEVMAC_DEFAULT$"
    fi
}
HTTPPORT=28081
MQTTPORT=1883
LANIP=$(nvram get lan_ipaddr 2>/dev/null)
# 兜底：部分固件 nvram 里是默认值，改从 br0 取真实地址
if [ -z "$LANIP" ] || [ "$LANIP" = "192.168.1.1" ]; then
    BR0IP=$(ifconfig br0 2>/dev/null | grep -o 'inet addr:[0-9.]*' | head -1 | cut -d: -f2)
    [ -n "$BR0IP" ] && LANIP=$BR0IP
fi
[ -z "$LANIP" ] && LANIP=192.168.6.1
# 传给二进制，避免它把 WAN 口 IP 当成 LAN IP
export CHATRABBIT_LANIP="$LANIP"

# JFFS 空间紧张：二进制以 .gz 存放，运行时解压到 /tmp（内存）执行
# 注意：覆盖正在运行的二进制会 Text file busy，必须先写临时文件再 rename
prepare_bin() {
    if [ -f "$BINGZ" ]; then
        if [ ! -s "$RUNBIN" ] || [ "$BINGZ" -nt "$RUNBIN" ]; then
            NEW="$RUNBIN.new.$$"
            gunzip -c "$BINGZ" > "$NEW" 2>/dev/null
            chmod +x "$NEW" 2>/dev/null
            mv -f "$NEW" "$RUNBIN" 2>/dev/null || rm -f "$NEW"
        fi
        [ -s "$RUNBIN" ] && BIN=$RUNBIN
    fi
}

get_json_int() {
    grep -o "\"$1\":[[:space:]]*[0-9]*" "$CONF" 2>/dev/null | grep -o '[0-9]*$'
}
[ -n "$(get_json_int http_port)" ] && HTTPPORT=$(get_json_int http_port)
[ -n "$(get_json_int mqtt_port)" ] && MQTTPORT=$(get_json_int mqtt_port)

# "假云端地址"：dnsmasq 把 turingapi.com 指到它，iptables 再把发往它的流量
# 重定向到本机。用独立地址而不是 LANIP，是为了能按"目的地址"转发，
# 这样任何玩具（不用预先登记 MAC）都能直接找到本地云。
TURINGIP=$(grep -o '"turing_ip"[[:space:]]*:[[:space:]]*"[^"]*"' "$CONF" 2>/dev/null | sed 's/.*"\([^"]*\)"$/\1/')
[ -z "$TURINGIP" ] && TURINGIP=198.18.0.1

# 全局（不区分来源 MAC）的重定向规则
nat_any_add() {
    iptables -t nat -C PREROUTING -d $TURINGIP -p tcp --dport 80 -j REDIRECT --to-ports $HTTPPORT 2>/dev/null \
      || iptables -t nat -A PREROUTING -d $TURINGIP -p tcp --dport 80 -j REDIRECT --to-ports $HTTPPORT
    iptables -t nat -C PREROUTING -d $TURINGIP -p tcp --dport 7890 -j REDIRECT --to-ports $HTTPPORT 2>/dev/null \
      || iptables -t nat -A PREROUTING -d $TURINGIP -p tcp --dport 7890 -j REDIRECT --to-ports $HTTPPORT
    for dp in 1883 8883 10883; do
        iptables -t nat -C PREROUTING -d $TURINGIP -p tcp --dport $dp -j REDIRECT --to-ports $MQTTPORT 2>/dev/null \
          || iptables -t nat -A PREROUTING -d $TURINGIP -p tcp --dport $dp -j REDIRECT --to-ports $MQTTPORT
    done
    iptables -t nat -C PREROUTING -d $TURINGIP -p udp --dport 123 -j DNAT --to-destination $LANIP:123 2>/dev/null \
      || iptables -t nat -A PREROUTING -d $TURINGIP -p udp --dport 123 -j DNAT --to-destination $LANIP:123
    echo "iptables: 通用规则 $TURINGIP -> :$HTTPPORT / :$MQTTPORT（无需按 MAC 登记）"
}

nat_any_del() {
    while iptables -t nat -D PREROUTING -d $TURINGIP -p tcp --dport 80 -j REDIRECT --to-ports $HTTPPORT 2>/dev/null; do :; done
    while iptables -t nat -D PREROUTING -d $TURINGIP -p tcp --dport 7890 -j REDIRECT --to-ports $HTTPPORT 2>/dev/null; do :; done
    for dp in 1883 8883 10883; do
        while iptables -t nat -D PREROUTING -d $TURINGIP -p tcp --dport $dp -j REDIRECT --to-ports $MQTTPORT 2>/dev/null; do :; done
    done
    while iptables -t nat -D PREROUTING -d $TURINGIP -p udp --dport 123 -j DNAT --to-destination $LANIP:123 2>/dev/null; do :; done
}

is_running() {
    [ -f "$PID" ] || return 1
    p=$(cat "$PID" 2>/dev/null)
    [ -n "$p" ] && [ -d "/proc/$p" ] || return 1
    return 0
}

nat_add() {
    nat_any_add
    n=0
    for m in $(dev_macs); do
        DEVMAC=$m
        nat_add_one
        n=$((n + 1))
    done
    DEVMAC=$DEVMAC_DEFAULT
    echo "iptables: 已为 $n 台设备(按 MAC) 补充重定向"
}

nat_add_one() {
    # 解析设备当前 IP（部分固件 arp 输出为 "? (192.168.3.106) at MAC"，需提取括号内 IP）
    DEVIP=$(arp -n 2>/dev/null | grep -i "$DEVMAC" | grep -o '([0-9][0-9.]*)' | tr -d '()' | head -1)
    if [ -z "$DEVIP" ]; then
        DEVIP=$(arp -n 2>/dev/null | grep -i "$DEVMAC" | awk '{print $2}' | tr -d '()' | head -1)
    fi
    case "$DEVIP" in
        *[0-9].[0-9]*.[0-9]*.[0-9]*) : ;;
        *) DEVIP="" ;;
    esac
    # 1) 设备到路由器本机 80
    iptables -t nat -C PREROUTING -d $LANIP -p tcp --dport 80 -m mac --mac-source $DEVMAC -j REDIRECT --to-ports $HTTPPORT 2>/dev/null \
      || iptables -t nat -A PREROUTING -d $LANIP -p tcp --dport 80 -m mac --mac-source $DEVMAC -j REDIRECT --to-ports $HTTPPORT
    # 2) 设备任意目标 80（公网 turing HTTP / 硬编码 IP）→ 本地
    iptables -t nat -C PREROUTING -p tcp -m mac --mac-source $DEVMAC --dport 80 -j REDIRECT --to-ports $HTTPPORT 2>/dev/null \
      || iptables -t nat -A PREROUTING -p tcp -m mac --mac-source $DEVMAC --dport 80 -j REDIRECT --to-ports $HTTPPORT
    # 2b) 兜底：按源 IP 再挂一组（MAC 规则在部分新固件上不生效时保证能拦到）
    if [ -n "$DEVIP" ]; then
        iptables -t nat -C PREROUTING -s $DEVIP -p tcp --dport 80 -j REDIRECT --to-ports $HTTPPORT 2>/dev/null \
          || iptables -t nat -A PREROUTING -s $DEVIP -p tcp --dport 80 -j REDIRECT --to-ports $HTTPPORT
        iptables -t nat -C PREROUTING -s $DEVIP -p tcp --dport 7890 -j REDIRECT --to-ports $HTTPPORT 2>/dev/null \
          || iptables -t nat -A PREROUTING -s $DEVIP -p tcp --dport 7890 -j REDIRECT --to-ports $HTTPPORT
        iptables -t nat -C PREROUTING -s $DEVIP -p udp --dport 123 -j DNAT --to-destination $LANIP:123 2>/dev/null \
          || iptables -t nat -A PREROUTING -s $DEVIP -p udp --dport 123 -j DNAT --to-destination $LANIP:123
        for dp in 1883 8883 10883; do
            iptables -t nat -C PREROUTING -s $DEVIP -p tcp --dport $dp -j REDIRECT --to-ports $MQTTPORT 2>/dev/null \
              || iptables -t nat -A PREROUTING -s $DEVIP -p tcp --dport $dp -j REDIRECT --to-ports $MQTTPORT
        done
        echo "iptables: fallback by IP $DEVIP"
    fi
    # 3) 老版 robot 端口 7890 → 本地 HTTP
    iptables -t nat -C PREROUTING -p tcp -m mac --mac-source $DEVMAC --dport 7890 -j REDIRECT --to-ports $HTTPPORT 2>/dev/null \
      || iptables -t nat -A PREROUTING -p tcp -m mac --mac-source $DEVMAC --dport 7890 -j REDIRECT --to-ports $HTTPPORT
    # 4) 设备 DNS（含硬编码 114.114.114.114）强制转到路由器
    iptables -t nat -C PREROUTING -p udp -m mac --mac-source $DEVMAC --dport 53 -j DNAT --to-destination $LANIP 2>/dev/null \
      || iptables -t nat -A PREROUTING -p udp -m mac --mac-source $DEVMAC --dport 53 -j DNAT --to-destination $LANIP
    # 4b) 设备 NTP 校时 → 路由器内建 SNTP（解决闹钟时间不准）
    iptables -t nat -C PREROUTING -p udp -m mac --mac-source $DEVMAC --dport 123 -j DNAT --to-destination $LANIP:123 2>/dev/null \
      || iptables -t nat -A PREROUTING -p udp -m mac --mac-source $DEVMAC --dport 123 -j DNAT --to-destination $LANIP:123
    iptables -t nat -C PREROUTING -p tcp -m mac --mac-source $DEVMAC --dport 53 -j DNAT --to-destination $LANIP 2>/dev/null \
      || iptables -t nat -A PREROUTING -p tcp -m mac --mac-source $DEVMAC --dport 53 -j DNAT --to-destination $LANIP
    # 5) 硬编码云端口 10883 / 8883 → 本地 MQTT
    for dp in 1883 8883 10883; do
        iptables -t nat -C PREROUTING -p tcp -m mac --mac-source $DEVMAC --dport $dp -j REDIRECT --to-ports $MQTTPORT 2>/dev/null \
          || iptables -t nat -A PREROUTING -p tcp -m mac --mac-source $DEVMAC --dport $dp -j REDIRECT --to-ports $MQTTPORT
    done
    echo "iptables: $DEVMAC -> 80/7890 -> :$HTTPPORT ; DNS -> $LANIP ; 10883/8883 -> :$MQTTPORT"
}

nat_del() {
    for m in $(dev_macs); do
        DEVMAC=$m
        nat_del_one
    done
    DEVMAC=$DEVMAC_DEFAULT
    nat_any_del
}

nat_del_one() {
    # 当前端口
    while iptables -t nat -D PREROUTING -d $LANIP -p tcp --dport 80 -m mac --mac-source $DEVMAC -j REDIRECT --to-ports $HTTPPORT 2>/dev/null; do :; done
    while iptables -t nat -D PREROUTING -p tcp -m mac --mac-source $DEVMAC --dport 80 -j REDIRECT --to-ports $HTTPPORT 2>/dev/null; do :; done
    while iptables -t nat -D PREROUTING -p tcp -m mac --mac-source $DEVMAC --dport 7890 -j REDIRECT --to-ports $HTTPPORT 2>/dev/null; do :; done
    while iptables -t nat -D PREROUTING -p udp -m mac --mac-source $DEVMAC --dport 53 -j DNAT --to-destination $LANIP 2>/dev/null; do :; done
    while iptables -t nat -D PREROUTING -p tcp -m mac --mac-source $DEVMAC --dport 53 -j DNAT --to-destination $LANIP 2>/dev/null; do :; done
    # NTP 校时规则（之前漏删，多 MAC 时会残留）
    while iptables -t nat -D PREROUTING -p udp -m mac --mac-source $DEVMAC --dport 123 -j DNAT --to-destination $LANIP:123 2>/dev/null; do :; done
    for dp in 1883 8883 10883; do
        while iptables -t nat -D PREROUTING -p tcp -m mac --mac-source $DEVMAC --dport $dp -j REDIRECT --to-ports $MQTTPORT 2>/dev/null; do :; done
    done
    # 历史端口（8081 等）残留规则一并清理
    for oldp in 8081 8082 8080; do
        while iptables -t nat -D PREROUTING -p tcp -m mac --mac-source $DEVMAC --dport 80 -j REDIRECT --to-ports $oldp 2>/dev/null; do :; done
        while iptables -t nat -D PREROUTING -p tcp -m mac --mac-source $DEVMAC --dport 7890 -j REDIRECT --to-ports $oldp 2>/dev/null; do :; done
        while iptables -t nat -D PREROUTING -d $LANIP -p tcp --dport 80 -m mac --mac-source $DEVMAC -j REDIRECT --to-ports $oldp 2>/dev/null; do :; done
    done
    # 兜底：把 PREROUTING 里所有还引用该 MAC 的规则逐条删干净
    # （按 IP 兜底加的 -s DEVIP 规则也会在这里被清掉）
    iptables -t nat -S PREROUTING 2>/dev/null | grep -i -- "--mac-source $DEVMAC" | sed 's/^-A PREROUTING //' | while read -r spec; do
        [ -n "$spec" ] || continue
        eval "iptables -t nat -D PREROUTING $spec" 2>/dev/null
    done
    echo "iptables: rules removed ($DEVMAC)"
}

# 媒体目录：优先 U 盘（/tmp/mnt/<label>/chatrabbit-media），无盘时回落 JFFS
MEDIA_LOCAL=$BASE/media

# 选出"最合适的 U 盘挂载点"：
#   - 跳过 EFI / 系统小分区（之前按字母序取第一个，EFI 会排在数据盘前面 → 把歌存进 115MB 的 EFI 分区）
#   - 跳过总容量 < 512MB 的分区
#   - 多个候选时选**可用空间最大**的
pick_media_mount() {
    best=""
    bestfree=0
    for m in /tmp/mnt/*; do
        [ -d "$m" ] || continue
        name=$(basename "$m")
        case "$name" in
            [Ee][Ff][Ii]*) continue ;;
        esac
        free=$(df -k "$m" 2>/dev/null | awk 'NR==2{print $4}')
        total=$(df -k "$m" 2>/dev/null | awk 'NR==2{print $2}')
        [ -n "$free" ] || continue
        [ -n "$total" ] || continue
        [ "$total" -ge 524288 ] || continue
        if [ "$free" -gt "$bestfree" ]; then
            bestfree=$free
            best=$m
        fi
    done
    [ -n "$best" ] && echo "$best"
}

setup_media() {
    # U 盘可能比插件启动晚挂载：最多等 25 秒
    i=0
    while [ $i -lt 25 ]; do
        MNT=$(pick_media_mount)
        [ -n "$MNT" ] && break
        sleep 1
        i=$((i+1))
    done

    if [ -n "$MNT" ] && [ -d "$MNT" ]; then
        USB_DIR="${MNT%/}/chatrabbit-media"
        mkdir -p "$USB_DIR" 2>/dev/null
        if [ -d "$USB_DIR" ]; then
            # 目标已经就是 U 盘：什么都不用做
            if [ -L "$MEDIA_LOCAL" ] && [ "$(readlink "$MEDIA_LOCAL")" = "$USB_DIR" ]; then
                echo "media: USB $USB_DIR (already)"
                return 0
            fi
            # 先把现有数据迁到 U 盘（JFFS 实体目录，或指向旧盘的链接）
            migrated=1
            if [ -d "$MEDIA_LOCAL" ]; then
                if cp -a "$MEDIA_LOCAL/." "$USB_DIR/" 2>/dev/null; then
                    rm -rf "$MEDIA_LOCAL"
                else
                    migrated=0
                fi
            fi
            if [ "$migrated" -eq 1 ]; then
                [ -L "$MEDIA_LOCAL" ] && rm -f "$MEDIA_LOCAL"
                ln -s "$USB_DIR" "$MEDIA_LOCAL" 2>/dev/null
                echo "media: USB $USB_DIR"
                return 0
            fi
            echo "media: 迁移到 U 盘失败（空间/权限？），继续用 JFFS"
        fi
    fi

    # 回退 JFFS：如果链接已悬空（U 盘被拔掉），先清掉坏链接
    if [ -L "$MEDIA_LOCAL" ] && [ ! -d "$MEDIA_LOCAL" ]; then
        rm -f "$MEDIA_LOCAL"
    fi
    mkdir -p "$MEDIA_LOCAL" 2>/dev/null
    echo "media: JFFS $MEDIA_LOCAL"
}

start() {
    if is_running; then echo "already running pid=$(cat $PID)"; nat_add; setup_media; return 0; fi
    prepare_bin
    setup_media
    $BIN dns-fix >/dev/null 2>&1
    nat_add
    nohup $BIN serve >>"$LOG" 2>&1 &
    echo $! > "$PID"
    sleep 1
    if is_running; then echo "started pid=$(cat $PID)"; else echo "start failed, see $LOG"; return 1; fi
}

# 列出"服务进程"的 pid（只匹配二进制路径，避免把 ctl 脚本自己也算进去）
# 也能抓到已经删掉 /tmp 文件、只剩内核旧 inode 的残留进程。
chatrabbit_pids() {
    for d in /proc/[0-9]*; do
        pid=${d#/proc/}
        # 排除自己与父进程（本脚本/调用者命令行里也有 chatrabbit 字样）
        [ "$pid" = "$$" ] && continue
        [ "$pid" = "$PPID" ] && continue
        [ -r "$d/cmdline" ] || continue
        cmd=$(tr '\0' ' ' < "$d/cmdline" 2>/dev/null)
        case "$cmd" in
            *"/tmp/chatrabbit.bin"*|*"/koolshare/chatrabbit/bin/chatrabbit"*) echo "$pid" ;;
        esac
    done
}

stop() {
    if is_running; then
        p=$(cat "$PID")
        kill "$p" 2>/dev/null
        sleep 1
        [ -d "/proc/$p" ] && kill -9 "$p" 2>/dev/null
        echo "stopped pid=$p"
    else
        echo "not running"
    fi
    rm -f "$PID"
    # 兜底：pid 文件对不上 / 进程还持有旧 inode 时（升级后仍跑旧版本），
    # 按 /proc/<pid>/cmdline 里含 chatrabbit 全部清理，再确认端口释放。
    for p in $(chatrabbit_pids); do
        kill "$p" 2>/dev/null && echo "stopped stale pid=$p"
    done
    sleep 1
    for p in $(chatrabbit_pids); do
        kill -9 "$p" 2>/dev/null && echo "killed stale pid=$p"
    done
    sleep 1
    nat_del
}

status() {
    if is_running; then
        echo "running pid=$(cat $PID)"
        prepare_bin
        $BIN ctl status
    else
        echo "not running"
    fi
}

addmac() {
    m=$(echo "$1" | tr 'a-f' 'A-F')
    case "$m" in
        [0-9A-F][0-9A-F]:[0-9A-F][0-9A-F]:[0-9A-F][0-9A-F]:[0-9A-F][0-9A-F]:[0-9A-F][0-9A-F]:[0-9A-F][0-9A-F]) : ;;
        *) echo "用法: $0 addmac aa:bb:cc:dd:ee:ff"; return 1 ;;
    esac
    if echo "$(dev_macs)" | grep -qi "$m"; then
        echo "已存在: $m"
    else
        echo "$m" >> "$DEVMAC_FILE"
        echo "已添加: $m"
    fi
    nat_del
    nat_add
}

delmac() {
    m=$(echo "$1" | tr 'a-f' 'A-F')
    # 关键顺序：先按这个 MAC 删掉 iptables 规则（此时它还在列表里，nat_del 才会覆盖到）
    DEVMAC=$m
    nat_del_one
    if [ -f "$DEVMAC_FILE" ]; then
        grep -vi "^$m$" "$DEVMAC_FILE" > "$DEVMAC_FILE.tmp" 2>/dev/null
        mv -f "$DEVMAC_FILE.tmp" "$DEVMAC_FILE" 2>/dev/null
    fi
    echo "已删除: $m（若为默认 MAC 需改脚本）"
    nat_add
}

listmac() {
    echo "当前参与重定向的设备 MAC："
    dev_macs | while read -r m; do
        [ -n "$m" ] || continue
        ip=$(arp -n 2>/dev/null | grep -i "$m" | grep -o '([0-9][0-9.]*)' | tr -d '()' | head -1)
        echo "  $m  ${ip:-（当前不在 ARP 表，可能离线）}"
    done
}

case "$1" in
    start|boot-start) start ;;
    stop) stop ;;
    restart) stop; sleep 1; start ;;
    status) status ;;
    media) setup_media ;;
    addmac) addmac "$2" ;;
    delmac) delmac "$2" ;;
    listmac) listmac ;;
    *) echo "usage: $0 {start|stop|restart|status|media|addmac <mac>|delmac <mac>|listmac}"; exit 1 ;;
esac
