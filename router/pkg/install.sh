#!/bin/sh
# chatrabbit router installer (Asuswrt / Merlin; AC68U armv7 + HND AX series arm64)
#
# Usage:
#   scp -r router/pkg /tmp/chatrabbit        # or copy dist + pkg
#   cd /tmp/chatrabbit && sh install.sh
#
# Auto-detects CPU arch:
#   aarch64            -> chatrabbit_aarch64  (HND / AX series, ARM64)
#   armv7l / armv6l    -> chatrabbit_armv7    (AC68U and older ARMv7)
#   anything else      -> chatrabbit          (legacy name)
set -e

BASE=/koolshare/chatrabbit
SRC=$(cd "$(dirname "$0")" && pwd)

echo "== arch detect =="
ARCH=$(uname -m)
case "$ARCH" in
    aarch64|arm64) BIN=chatrabbit_aarch64 ;;
    armv7l|armv6l|arm) BIN=chatrabbit_armv7 ;;
    *) BIN=chatrabbit ;;
esac
echo "   uname -m = $ARCH  ->  $BIN"

echo "== install to $BASE =="
mkdir -p "$BASE/bin" "$BASE/scripts" "$BASE/media" "$BASE/logs" "$BASE/webs"

# pick binary: prefer explicit arch build, fall back to plain name
for cand in "$SRC/$BIN" "$SRC/dist/$BIN" "$SRC/chatrabbit" "$SRC/dist/chatrabbit"; do
    if [ -f "$cand" ]; then
        cp -f "$cand" "$BASE/bin/chatrabbit"
        echo "   binary: $cand"
        break
    fi
done
chmod +x "$BASE/bin/chatrabbit"

if [ ! -f "$BASE/chatrabbit.conf" ]; then
cat > "$BASE/chatrabbit.conf" <<'EOF'
{
  "lan_ip": "",
  "http_port": 28081,
  "mqtt_port": 1883,
  "extra_mqtt_port": 24056,
  "api_port": 8898,
  "media_dir": "/koolshare/chatrabbit/media",
  "log_dir": "/koolshare/chatrabbit/logs",
  "gap_seconds": 6,
  "api_key": "69043ca347f34348b1e043c5429fef16",
  "device_id": "AI91144031DD06A2",
  "openid": "o0LocalOpenid0000000000000000",
  "kick_on_command": false
}
EOF
fi

for f in chatrabbit_ctl.sh; do
    if [ -f "$SRC/scripts/$f" ]; then
        cp -f "$SRC/scripts/$f" "$BASE/scripts/$f"; chmod +x "$BASE/scripts/$f"
    elif [ -f "$SRC/$f" ]; then
        cp -f "$SRC/$f" "$BASE/scripts/$f"; chmod +x "$BASE/scripts/$f"
    fi
done
if [ -f "$SRC/webs/Module_chatrabbit.asp" ]; then
    cp -f "$SRC/webs/Module_chatrabbit.asp" "$BASE/webs/Module_chatrabbit.asp"
fi

# ---- boot hooks: koolshare init.d (both old and HND koolshare) ----
mkdir -p /koolshare/init.d
cat > /koolshare/init.d/V99chatrabbit.sh <<'EOF'
#!/bin/sh
/koolshare/chatrabbit/scripts/chatrabbit_ctl.sh boot-start
EOF
cat > /koolshare/init.d/T99chatrabbit.sh <<'EOF'
#!/bin/sh
/koolshare/chatrabbit/scripts/chatrabbit_ctl.sh stop
EOF
chmod +x /koolshare/init.d/V99chatrabbit.sh /koolshare/init.d/T99chatrabbit.sh

# ---- boot hooks: Merlin custom scripts (HND AX series uses this) ----
mkdir -p /jffs/scripts
add_hook() {
    hookfile="$1"; cmd="$2"
    [ -f "$hookfile" ] || { echo "#!/bin/sh" > "$hookfile"; chmod +x "$hookfile"; }
    grep -q "chatrabbit_ctl.sh" "$hookfile" 2>/dev/null || echo "$cmd" >> "$hookfile"
    chmod +x "$hookfile"
}
add_hook /jffs/scripts/services-start "/koolshare/chatrabbit/scripts/chatrabbit_ctl.sh boot-start"
add_hook /jffs/scripts/firewall-start "/koolshare/chatrabbit/scripts/chatrabbit_ctl.sh start"
echo "   hooks: /koolshare/init.d/V99chatrabbit.sh, /jffs/scripts/services-start, /jffs/scripts/firewall-start"

# plugin page for koolshare soft center
cp -f "$BASE/webs/Module_chatrabbit.asp" /koolshare/webs/Module_chatrabbit.asp 2>/dev/null || true

echo "== point turing domains to router =="
"$BASE/bin/chatrabbit" dns-fix || echo "   dns-fix failed, check /jffs/configs/dnsmasq.conf.add"

echo "== start service =="
"$BASE/scripts/chatrabbit_ctl.sh" restart

echo ""
echo "done. check:"
echo "  /koolshare/chatrabbit/scripts/chatrabbit_ctl.sh status"
echo "  tail -f /koolshare/chatrabbit/logs/serve.log"
echo "  page: http://\$(nvram get lan_ipaddr)/Module_chatrabbit.asp  (or :8898/)"
