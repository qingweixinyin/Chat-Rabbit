# v1.6.0: API port 8890 + 8898 compat listener + occupy-8898 skip + page candidate static check
$ErrorActionPreference = 'Stop'
$exe = "$env:TEMP\chatrabbit_test.exe"
$root = "$env:TEMP\cr_selftest160"
if (Test-Path $root) { Remove-Item $root -Recurse -Force }
$conf = Join-Path $root 'conf'
$media = Join-Path $root 'media'
$logs = Join-Path $root 'logs'
$webs = Join-Path $root 'webs'
New-Item -ItemType Directory -Force -Path $conf,$media,$logs,$webs | Out-Null
Copy-Item 'E:\Claude official\chat rabbit\router\pkg\webs\Module_chatrabbit.asp' (Join-Path $webs 'Module_chatrabbit.asp')

$script:fail = 0
function Check($n, $c, $d) { if ($c) { Write-Host ("  PASS  " + $n + "  " + $d) } else { Write-Host ("  FAIL  " + $n + "  " + $d) -ForegroundColor Red; $script:fail++ } }

function Write-Cfg($apiPort) {
  $cfgJson = @"
{
  "lan_ip": "127.0.0.1",
  "http_port": 18083,
  "mqtt_port": 11885,
  "extra_mqtt_port": 12458,
  "api_port": $apiPort,
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
"@
  $cfgJson = $cfgJson.Replace('MEDIA', $media.Replace('\','/')).Replace('LOGS', $logs.Replace('\','/'))
  Set-Content -Path (Join-Path $conf 'chatrabbit.conf') -Value $cfgJson -Encoding ascii
}
Set-Content -Path (Join-Path $media 'tone.mp3') -Value 'ID3fake' -Encoding ascii
function Start-Srv { Start-Process -FilePath $exe -ArgumentList @('-c', (Join-Path $conf 'chatrabbit.conf'), 'serve') -PassThru -WindowStyle Hidden }
function J($url, $body) {
    if ($null -eq $body) { return Invoke-RestMethod -Uri $url -TimeoutSec 10 }
    return Invoke-RestMethod -Uri $url -Method Post -ContentType 'application/json' -Body ($body | ConvertTo-Json -Compress) -TimeoutSec 10
}

Write-Host "`n=== A. primary 18890 + compat 8898 both listening ==="
Write-Cfg 18890
$p1 = Start-Srv
Start-Sleep -Seconds 3
try {
    $d = J 'http://127.0.0.1:18890/api/diag'
    Check 'version=1.6.0' ($d.version -eq '1.6.0') $d.version
    Check 'api_port=18890' ($d.api_port -eq 18890) ("api_port=" + $d.api_port)
    Check 'api_ports has 18890' ($d.api_ports -contains 18890) ("api_ports=" + ($d.api_ports -join ','))
    Check 'api_ports has 8898' ($d.api_ports -contains 8898) ("api_ports=" + ($d.api_ports -join ','))
    $s1 = J 'http://127.0.0.1:18890/api/status'
    Check 'primary port serves API' ($null -ne $s1.device_online) ('dev_id=' + $s1.dev_id)
    $s2 = J 'http://127.0.0.1:8898/api/status'
    Check 'compat 8898 serves same API' ($s2.dev_id -eq $s1.dev_id) ('dev_id=' + $s2.dev_id)
    $h = (Invoke-WebRequest -Uri 'http://127.0.0.1:18890/' -UseBasicParsing -TimeoutSec 10).Content
    Check 'page served on 18890' ($h.Contains('API_CANDIDATES')) 'API_CANDIDATES'
    Check 'page candidate order 8890 first' ($h.IndexOf(':8890') -lt $h.IndexOf(':8898')) '8890<8898'
    Check 'page has api_port span' ($h.Contains('id="api_port"')) 'api_port'
}
finally { if ($p1 -and -not $p1.HasExited) { Stop-Process -Id $p1.Id -Force } }
Start-Sleep -Seconds 1

Write-Host "`n=== B. occupy 8898 then start: must not exit, must log skip ==="
# occupy 8898 dual-stack (Go's ":port" listens on [::] dual-mode on Windows)
$occupy = New-Object System.Net.Sockets.Socket([System.Net.Sockets.AddressFamily]::InterNetworkV6, [System.Net.Sockets.SocketType]::Stream, [System.Net.Sockets.ProtocolType]::Tcp)
$occupy.SetSocketOption([System.Net.Sockets.SocketOptionLevel]::IPv6, [System.Net.Sockets.SocketOptionName]::IPv6Only, $false)
$occupy.Bind((New-Object System.Net.IPEndPoint([System.Net.IPAddress]::IPv6Any, 8898)))
$occupy.Listen(10)
Write-Cfg 18890
$p2 = Start-Srv
Start-Sleep -Seconds 3
try {
    Check 'process still running' (-not $p2.HasExited) ('exited=' + $p2.HasExited)
    $d2 = J 'http://127.0.0.1:18890/api/diag'
    Check 'api_ports has 18890' ($d2.api_ports -contains 18890) ("api_ports=" + ($d2.api_ports -join ','))
    Check 'api_ports lacks 8898' (-not ($d2.api_ports -contains 8898)) ("api_ports=" + ($d2.api_ports -join ','))
    $srvLog = Get-ChildItem (Join-Path $logs '*.log') | Sort-Object LastWriteTime | Select-Object -Last 1
    $txt = Get-Content $srvLog.FullName -Raw
    Check 'log has 8898 skip marker' (($txt -match '8898') -and ($txt -match 'kongke')) 'matched skip line'
    $st = J 'http://127.0.0.1:18890/api/status'
    Check 'API still works on primary' ($null -ne $st.device_online) ('dev_id=' + $st.dev_id)
    $null = J 'http://127.0.0.1:18890/api/dev/clear' @{}
    $null = J 'http://127.0.0.1:18890/api/dev/add' @{ hour = 7; minute = 0; name = 'portTest'; enabled = $true; wk = 63 }
    $l = J 'http://127.0.0.1:18890/api/dev/list'
    Check 'dev alarm works while 8898 busy' ($l.list.Count -eq 1) ('count=' + $l.list.Count)
}
finally {
    if ($p2 -and -not $p2.HasExited) { Stop-Process -Id $p2.Id -Force }
    try { $occupy.Close() } catch {}
}

Write-Host "`n=== C. multi-device sanity on 18890 ==="
Write-Cfg 18890
$p3 = Start-Srv
Start-Sleep -Seconds 3
try {
    $base1 = (J 'http://127.0.0.1:18890/api/dev/list').list.Count
    $null = J 'http://127.0.0.1:18890/api/devices/add' @{ id = 'AI9TEST00000002'; name = 'dev2'; apiKey = 'k2'; openid = 'o2' }
    $l2a = J 'http://127.0.0.1:18890/api/dev/list?dev=AI9TEST00000002'
    $null = J 'http://127.0.0.1:18890/api/dev/add?dev=AI9TEST00000002' @{ hour = 9; minute = 5; name = 'd2'; enabled = $true; wk = 63 }
    $l1 = J 'http://127.0.0.1:18890/api/dev/list'
    $l2 = J 'http://127.0.0.1:18890/api/dev/list?dev=AI9TEST00000002'
    Check 'dev2 starts empty' ($l2a.list.Count -eq 0) ('d2_before=' + $l2a.list.Count)
    Check 'isolation kept on new port' (($l2.list.Count -eq 1) -and ($l1.list.Count -eq $base1)) ("d1=" + $l1.list.Count + " (base " + $base1 + ") d2=" + $l2.list.Count)
}
finally { if ($p3 -and -not $p3.HasExited) { Stop-Process -Id $p3.Id -Force } }

Write-Host "`nFAILED ITEMS: $script:fail"
