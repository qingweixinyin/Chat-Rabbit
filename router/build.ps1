# Cross-compile chatrabbit (Windows -> Linux static binary)
#
# Usage:
#   powershell -ExecutionPolicy Bypass -File .\build.ps1
#   powershell -ExecutionPolicy Bypass -File .\build.ps1 -GoExe 'E:\...\go\bin\go.exe'
#   powershell -ExecutionPolicy Bypass -File .\build.ps1 -Out dist\chatrabbit_aarch64 -GOARCH arm64
#
# Targets:
#   AC68U / older Merlin (armv7l): -GOARCH arm -GOARM 5   -> dist\chatrabbit_armv7
#   HND / AX series (aarch64)    : -GOARCH arm64          -> dist\chatrabbit_aarch64

param(
    [string]$GoExe,
    [string]$Out = "dist\chatrabbit",
    [string]$GOARM = "5",
    [string]$GOARCH = "arm"
)

$ErrorActionPreference = 'Stop'
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $here

if (-not $GoExe) {
    $cand = @(
        "$here\..\work\tools\go\bin\go.exe",
        "$env:ProgramFiles\Go\bin\go.exe",
        "$env:LOCALAPPDATA\Programs\Go\bin\go.exe"
    ) | Where-Object { Test-Path $_ } | Select-Object -First 1
    if (-not $cand) { Write-Host "go.exe not found, pass -GoExe" -ForegroundColor Red; exit 1 }
    $GoExe = $cand
}

Write-Host "Go: $GoExe"
& $GoExe version

$env:GOOS = 'linux'
$env:GOARCH = $GOARCH
if ($GOARCH -eq 'arm') { $env:GOARM = $GOARM }
$env:CGO_ENABLED = '0'
$env:GOPATH = Join-Path $here '.gopath'
$env:GOCACHE = Join-Path $here '.gocache'

New-Item -ItemType Directory -Force -Path (Split-Path $Out -Parent) | Out-Null

Push-Location (Join-Path $here 'src')
try {
    & $GoExe build -trimpath -ldflags "-s -w" -o (Join-Path $here $Out) .
    if ($LASTEXITCODE -ne 0) { throw "build failed" }
} finally {
    Pop-Location
}

$f = Get-Item (Join-Path $here $Out)
Write-Host ("OK: {0}  {1:N0} bytes" -f $f.FullName, $f.Length) -ForegroundColor Green
Write-Host "Deploy: copy to /koolshare/chatrabbit/bin/chatrabbit, chmod +x, then run scripts/chatrabbit_ctl.sh restart"
