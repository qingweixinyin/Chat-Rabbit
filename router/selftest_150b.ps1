# v1.5.0 extra checks: page wiring (device selector + wt), restart idempotency
$ErrorActionPreference = 'Stop'
$exe = "$env:TEMP\chatrabbit_test.exe"
$root = "$env:TEMP\cr_selftest2"
if (Test-Path $root) { Remove-Item $root -Recurse -Force }
$conf = Join-Path $root 'conf'
$media = Join-Path $root 'media'
$logs = Join-Path $root 'logs'
$webs = Join-Path $root 'webs'
New-Item -ItemType Directory -Force -Path $conf,$media,$logs,$webs | Out-Null
Copy-Item 'E:\Claude official\chat rabbit\router\pkg\webs\Module_chatrabbit.asp' (Join-Path $webs 'Module_chatrabbit.asp')

$cfgJson = @'
{
  "lan_ip": "127.0.0.1",
  "http_port": 18082,
  "mqtt_port": 11884,
  "extra_mqtt_port": 12457,
  "api_port": 18900,
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
'[{"na":"legacy","th":6,"tm":30,"sd":1793000000,"wk":63,"wt":0,"s":1}]' |
    Set-Content -Path (Join-Path $conf 'dev_alarms.json') -Encoding ascii
Set-Content -Path (Join-Path $media 'tone.mp3') -Value 'ID3fake' -Encoding ascii

$API = 'http://127.0.0.1:18900'
$script:fail = 0
function Check($n, $c, $d) { if ($c) { Write-Host ("  PASS  " + $n + "  " + $d) } else { Write-Host ("  FAIL  " + $n + "  " + $d) -ForegroundColor Red; $script:fail++ } }

function Start-Srv {
    Start-Process -FilePath $exe -ArgumentList @('-c', (Join-Path $conf 'chatrabbit.conf'), 'serve') -PassThru -WindowStyle Hidden
}
function J($url, $body) {
    if ($null -eq $body) { return Invoke-RestMethod -Uri $url -TimeoutSec 10 }
    return Invoke-RestMethod -Uri $url -Method Post -ContentType 'application/json' -Body ($body | ConvertTo-Json -Compress) -TimeoutSec 10
}

$p1 = Start-Srv
Start-Sleep -Seconds 3
try {
    Write-Host "`n=== A. page wiring ==="
    $html = (Invoke-WebRequest -Uri ($API + '/') -UseBasicParsing -TimeoutSec 10).Content
    Check 'page has devSel' ($html.Contains('id="devSel"')) 'devSel'
    Check 'page has a_wt' ($html.Contains('id="a_wt"')) 'a_wt'
    Check 'page calls /api/dev/wt' ($html.Contains('/api/dev/wt')) '/api/dev/wt'
    Check 'page calls /api/devices' ($html.Contains('/api/devices')) '/api/devices'
    Check 'page has withDev helper' ($html.Contains('function withDev')) 'withDev'
    Check 'page no stale logStatus' (-not $html.Contains('logStatus(')) 'clean'

    Write-Host "`n=== B. wt round-trip through API ==="
    $null = J ($API + '/api/dev/clear') @{}
    $null = J ($API + '/api/dev/add') @{ hour = 6; minute = 0; name = 'wtTest'; enabled = $true; wk = 63; wt = 7 }
    $lst = J ($API + '/api/dev/list')
    Check 'add carries wt=7' ($lst.list[0].wt -eq 7) ("wt=" + $lst.list[0].wt)
    $w = J ($API + '/api/dev/wt') @{ index = 0; wt = 2 }
    $lst = J ($API + '/api/dev/list')
    Check '/api/dev/wt updates' ($lst.list[0].wt -eq 2) ("wt=" + $lst.list[0].wt)
}
finally { if ($p1 -and -not $p1.HasExited) { Stop-Process -Id $p1.Id -Force } }

Write-Host "`n=== C. restart idempotency (no duplicate migration) ==="
$p2 = Start-Srv
Start-Sleep -Seconds 3
try {
    $lst = J ($API + '/api/dev/list')
    Check 'alarm count stays 1 after restart' ($lst.list.Count -eq 1) ("count=" + $lst.list.Count)
    $devs = J ($API + '/api/devices')
    Check 'device count stays 1' ($devs.devices.Count -eq 1) ("count=" + $devs.devices.Count)
    $files = Get-ChildItem (Join-Path $conf 'dev_alarms') -File
    Check 'one alarm file per device' ($files.Count -eq 1) ($files.Name -join ',')
}
finally { if ($p2 -and -not $p2.HasExited) { Stop-Process -Id $p2.Id -Force } }

Write-Host "`nFAILED ITEMS: $script:fail"
