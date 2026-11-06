#!/bin/sh
# chatrabbit plugin uninstaller (called by softcenter remove).
BASE=/koolshare/chatrabbit

echo "== chatrabbit plugin uninstall =="

# stop service
if [ -x "$BASE/scripts/chatrabbit_ctl.sh" ]; then
    "$BASE/scripts/chatrabbit_ctl.sh" stop 2>/dev/null || true
fi

# boot hooks
rm -f /koolshare/init.d/V99chatrabbit.sh /koolshare/init.d/T99chatrabbit.sh

# /jffs hooks: drop our lines only
for f in /jffs/scripts/services-start /jffs/scripts/firewall-start; do
    if [ -f "$f" ]; then
        grep -v "chatrabbit_ctl.sh" "$f" > "$f.tmp" 2>/dev/null && mv -f "$f.tmp" "$f"
        chmod +x "$f" 2>/dev/null || true
    fi
done

# web pages
rm -f /koolshare/webs/Module_chatrabbit.asp

# remove files
rm -rf "$BASE"

# dbus entries
if [ -x /usr/bin/dbus ]; then
    dbus remove softcenter_module_chatrabbit_name
    dbus remove softcenter_module_chatrabbit_title
    dbus remove softcenter_module_chatrabbit_description
    dbus remove softcenter_module_chatrabbit_version
    dbus remove softcenter_module_chatrabbit_install
fi

echo "== uninstall done =="
exit 0
