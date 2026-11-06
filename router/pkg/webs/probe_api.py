import urllib.request, json
base="http://192.168.6.1:8898"
for p in ("/api/status","/api/list","/api/files","/api/alarmvoice","/api/mode","/api/dev/list"):
    try:
        with urllib.request.urlopen(base+p, timeout=6) as r:
            body=r.read().decode("utf-8","replace")[:160]
            print(f"{p}: HTTP {r.status} {body}")
    except Exception as e:
        print(f"{p}: ERR {e}")
