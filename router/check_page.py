import urllib.request
with urllib.request.urlopen("http://192.168.6.1:8898/dev-alarms", timeout=10) as r:
    html=r.read().decode()
print("页面大小:", len(html), "字节")
for kw in ("立刻响铃","响铃时长","上传为新铃声","/api/ringtone/upload","/api/files"):
    print(f"  {kw}: {'✅' if kw in html else '❌'}")
