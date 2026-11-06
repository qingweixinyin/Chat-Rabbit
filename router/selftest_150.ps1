# chatrabbit v1.5.0 multi-device smoke test (Windows build, logic only; does not touch dist/pkg)
$ErrorActionPreference = 'Stop'
$exe = "$env:TEMP\chatrabbit_test.exe"
$root = "$env:TEMP\cr_selftest"
if (Test-Path $root) { Remove-Item $root -Recurse -Force }
$conf = Join-Path $root 'conf'
$media = Join-Path $root 'media'
$logs = Join-Path $root 'logs'
New-Item -ItemType Directory -Force -Path $conf,$media,$logs | Out-Null

$cfgJson = @'
{
  "lan_ip": "127.0.0.1",
  "http_port": 18081,
  "mqtt_port": 11883,
  "extra_mqtt_port": 12456,
  "api_port": 18899,
  "media_dir": "MEDIA",
  "log_dir": "LOGS",
  "gap_seconds": 1,
  "log_max_mb": 1,
  "api_key": "69043ca347f34348b1e043c5429fef16",
  "device_id": "AI91144031DD06A2",
  "openid": "o0LocalOpenid0000000000000000",
  "time_mode": 10,
  "tz_hours": 8,
  "once_mode": "weekday",
  "ws_accept": true
}
'@
$cfgJson = $cfgJson.Replace('MEDIA', $media.Replace('\','/')).Replace('LOGS', $logs.Replace('\','/'))
Set-Content -Path (Join-Path $conf 'chatrabbit.conf') -Value $cfgJson -Encoding ascii
'[{"na":"legacy-alarm","th":6,"tm":30,"sd":1793000000,"wk":63,"wt":0,"s":1,"rv":"tone.mp3"}]' |
    Set-Content -Path (Join-Path $conf 'dev_alarms.json') -Encoding ascii
Set-Content -Path (Join-Path $media 'tone.mp3') -Value 'ID3fake' -Encoding ascii
Set-Content -Path (Join-Path $media 'song.mp3') -Value 'ID3fake2' -Encoding ascii

$p = Start-Process -FilePath $exe -ArgumentList @('-c', (Join-Path $conf 'chatrabbit.conf'), 'serve') -PassThru -WindowStyle Hidden
Start-Sleep -Seconds 3
$API = 'http://127.0.0.1:18899'
$script:fail = 0
function Check($name, $cond, $detail) {
    if ($cond) { Write-Host ("  PASS  " + $name + "  " + $detail) }
    else { Write-Host ("  FAIL  " + $name + "  " + $detail) -ForegroundColor Red; $script:fail++ }
}
function J($url, $body) {
    if ($null -eq $body) { return Invoke-RestMethod -Uri $url -TimeoutSec 10 }
    return Invoke-RestMethod -Uri $url -Method Post -ContentType 'application/json' -Body ($body | ConvertTo-Json -Compress) -TimeoutSec 10
}

try {
    Write-Host "`n=== 1. legacy migration ==="
    $diag = J ($API + '/api/diag')
    Check 'version=1.6.0' ($diag.version -eq '1.6.0') $diag.version
    Check 'device_count=1' ($diag.device_count -eq 1) ("count=" + $diag.device_count)
    Check 'default_device' ($diag.default_device -eq 'AI91144031DD06A2') $diag.default_device
    $migFile = Join-Path $conf 'dev_alarms\AI91144031DD06A2.json'
    Check 'dev_alarms/<id>.json created' (Test-Path $migFile) $migFile
    Check 'legacy .bak kept' (Test-Path (Join-Path $conf 'dev_alarms.json.bak')) 'dev_alarms.json.bak'
    $mig = Get-Content $migFile -Raw | ConvertFrom-Json
    Check 'migrated 1 alarm' ($mig.Count -eq 1) ("count=" + $mig.Count)

    Write-Host "`n=== 2. single-device regression (no ?dev=) ==="
    $st = J ($API + '/api/status')
    Check '/api/status has device_online' ($null -ne $st.device_online) ("online=" + $st.device_online)
    Check '/api/status dev_id' ($st.dev_id -eq 'AI91144031DD06A2') $st.dev_id
    $lst = J ($API + '/api/dev/list')
    Check '/api/dev/list count=1' ($lst.list.Count -eq 1) ("count=" + $lst.list.Count)
    Check 'old shape ok+list' (($lst.ok -eq $true) -and ($null -ne $lst.list)) 'ok+list'
    $add = J ($API + '/api/dev/add') @{ hour = 7; minute = 30; name = 'regA'; enabled = $true; wk = 31; ring = 'tone.mp3' }
    Check '/api/dev/add' ($add.ok -eq $true) ($add | ConvertTo-Json -Compress)
    $lst = J ($API + '/api/dev/list')
    Check 'after add count=2' ($lst.list.Count -eq 2) ("count=" + $lst.list.Count)
    $up = J ($API + '/api/dev/upsert') @{ hour = 8; minute = 0; name = 'regA2'; enabled = $true; wk = 31; match = @{ name = 'regA'; hour = 7; minute = 30 } }
    Check '/api/dev/upsert replaced' ($up.replaced -eq $true) ($up | ConvertTo-Json -Compress)
    $rg = J ($API + '/api/dev/ring') @{ index = 0; file = 'song.mp3' }
    Check '/api/dev/ring' (($rg.ok -eq $true) -and ($rg.ring -eq 'song.mp3')) ($rg | ConvertTo-Json -Compress)
    $wt = J ($API + '/api/dev/wt') @{ index = 0; wt = 3 }
    Check '/api/dev/wt' (($wt.ok -eq $true) -and ($wt.wt -eq 3)) ($wt | ConvertTo-Json -Compress)
    $lst = J ($API + '/api/dev/list')
    Check 'wt persisted' ($lst.list[0].wt -eq 3) ("wt=" + $lst.list[0].wt)
    $pl = J ($API + '/api/play') @{ file = 'song.mp3' }
    Check '/api/play' ($pl.ok -eq $true) ($pl | ConvertTo-Json -Compress)
    $sp = J ($API + '/api/stop') @{}
    Check '/api/stop' ($sp.ok -eq $true) ($sp | ConvertTo-Json -Compress)
    $rt = J ($API + '/api/ringtest') @{}
    Check '/api/ringtest' ($rt.ok -eq $true) ($rt | ConvertTo-Json -Compress)
    foreach ($ep in @('/api/reqlog', '/api/mqttlog', '/api/medialog', '/api/log/stat', '/api/storage')) {
        $r = J ($API + $ep)
        Check ($ep + ' ok') ($r.ok -eq $true) ('ok=' + $r.ok)
    }
    $f1 = J ($API + '/api/files'); Check '/api/files has files' ($null -ne $f1.files) ('n=' + $f1.files.Count)
    $f2 = J ($API + '/api/list');  Check '/api/list has files' ($null -ne $f2.files) ('n=' + $f2.files.Count)
    $null = J ($API + '/api/dev/clear') @{}
    $lst = J ($API + '/api/dev/list')
    Check 'clear -> 0' ($lst.list.Count -eq 0) ("count=" + $lst.list.Count)

    Write-Host "`n=== 3. multi-device: add / isolation / default switch ==="
    $d2 = J ($API + '/api/devices/add') @{ id = 'AI9TEST00000002'; name = 'dev2'; apiKey = 'testkey0000000000000000000000002'; openid = 'o0Test2' }
    Check '/api/devices/add' ($d2.ok -eq $true) ($d2 | ConvertTo-Json -Compress)
    $devs = J ($API + '/api/devices')
    Check '/api/devices count=2' ($devs.devices.Count -eq 2) ("count=" + $devs.devices.Count)
    $l2 = J ($API + '/api/dev/list?dev=AI9TEST00000002')
    Check 'dev2 initial 0 alarms' ($l2.list.Count -eq 0) ("count=" + $l2.list.Count)
    $null = J ($API + '/api/dev/add?dev=AI9TEST00000002') @{ hour = 9; minute = 15; name = 'dev2alarm'; enabled = $true; wk = 63 }
    $l1 = J ($API + '/api/dev/list')
    $l2 = J ($API + '/api/dev/list?dev=AI9TEST00000002')
    Check 'dev2 has 1 alarm' ($l2.list.Count -eq 1) ("count=" + $l2.list.Count)
    Check 'dev1 still 0 (isolated)' ($l1.list.Count -eq 0) ("count=" + $l1.list.Count)
    $sel = J ($API + '/api/devices/select') @{ id = 'AI9TEST00000002' }
    Check '/api/devices/select' (($sel.ok -eq $true) -and ($sel.default -eq 'AI9TEST00000002')) ($sel | ConvertTo-Json -Compress)
    $st2 = J ($API + '/api/status')
    Check 'default switched (status.dev_id)' ($st2.dev_id -eq 'AI9TEST00000002') $st2.dev_id

    Write-Host "`n=== 4. MQTT identity (simulate dev2 CONNECT) ==="
    $clientId = 'GID_device@@@testkey0000000000000000000000002@AI9TEST00000002'
    $tcp = New-Object System.Net.Sockets.TcpClient
    $tcp.Connect('127.0.0.1', 11883)
    $stream = $tcp.GetStream()
    $payload = [System.Text.Encoding]::UTF8.GetBytes($clientId)
    $ms = New-Object System.IO.MemoryStream
    $ms.WriteByte(0x10)
    $remaining = 2 + 4 + 1 + 1 + 2 + 2 + $payload.Length
    $ms.WriteByte([byte]$remaining)
    $ms.WriteByte(0); $ms.WriteByte(4); $ms.WriteByte([byte][char]'M'); $ms.WriteByte([byte][char]'Q'); $ms.WriteByte([byte][char]'T'); $ms.WriteByte([byte][char]'T')
    $ms.WriteByte(4)
    $ms.WriteByte(2)
    $ms.WriteByte(0); $ms.WriteByte(60)
    $ms.WriteByte([byte][math]::Floor($payload.Length / 256)); $ms.WriteByte([byte]($payload.Length % 256))
    $ms.Write($payload, 0, $payload.Length)
    $bytes = $ms.ToArray()
    $stream.Write($bytes, 0, $bytes.Length)
    $stream.Flush()
    Start-Sleep -Milliseconds 900
    $devs = J ($API + '/api/devices')
    $d2info = $devs.devices | Where-Object { $_.id -eq 'AI9TEST00000002' }
    Check 'CONNECT -> dev2 online' ($d2info.online -eq $true) ("online=" + $d2info.online + " ip=" + $d2info.ip)
    $st3 = J ($API + '/api/status?dev=AI9TEST00000002')
    Check 'status?dev= online' ($st3.device_online -eq $true) ("clients=" + $st3.clients)
    $tcp.Close()

    Write-Host "`n=== 5. delete device ==="
    Start-Sleep -Milliseconds 300
    $del = J ($API + '/api/devices/del') @{ id = 'AI9TEST00000002' }
    Check '/api/devices/del' ($del.ok -eq $true) ($del | ConvertTo-Json -Compress)
    $devs = J ($API + '/api/devices')
    Check 'after delete count=1' ($devs.devices.Count -eq 1) ("count=" + $devs.devices.Count)
    $del2 = J ($API + '/api/devices/del') @{ id = 'AI91144031DD06A2' }
    Check 'refuse deleting last device' ($del2.ok -eq $false) ($del2 | ConvertTo-Json -Compress)
}
finally {
    if ($p -and -not $p.HasExited) { Stop-Process -Id $p.Id -Force }
    Write-Host "`nFAILED ITEMS: $script:fail"
    Write-Host "conf dir: $conf"
}
