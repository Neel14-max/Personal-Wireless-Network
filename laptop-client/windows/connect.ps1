Start-Transcript -Path "$env:TEMP\tetherdirect-debug.log" -Force | Out-Null

$ErrorActionPreference = "Stop"

# ==================================================
# Safety net: NOTHING can close this window silently
# anymore. Any crash, anywhere below, prints in full
# and waits for you to read it before exiting.
# ==================================================
trap {
    Write-Host ""
    Write-Host "==============================================" -ForegroundColor Red
    Write-Host " FATAL ERROR - SCRIPT CRASHED" -ForegroundColor Red
    Write-Host "==============================================" -ForegroundColor Red
    Write-Host ""
    Write-Host "Type: $($_.Exception.GetType().FullName)" -ForegroundColor Red
    Write-Host "Message: $($_.Exception.Message)" -ForegroundColor Red
    Write-Host ""
    Write-Host "Where:" -ForegroundColor Yellow
    Write-Host $_.InvocationInfo.PositionMessage
    Write-Host ""
    Read-Host "Press Enter to close"
    exit 1
}

$Port = 8888
$LocalProxy = 8080

$cs = Join-Path $PSScriptRoot "TetherProxy.cs"

# ==================================================
# Check the local port isn't already taken by a
# leftover/stuck process from a previous run
# ==================================================
$existing = Get-NetTCPConnection -LocalPort $LocalProxy -State Listen -ErrorAction SilentlyContinue
if ($existing) {
    $ownerPid = $existing[0].OwningProcess
    $ownerName = (Get-Process -Id $ownerPid -ErrorAction SilentlyContinue).ProcessName
    Write-Host ""
    Write-Host "WARNING: Port $LocalProxy is already in use by PID $ownerPid ($ownerName)." -ForegroundColor Yellow
    Write-Host "This is almost certainly a stuck copy of this script from a previous run." -ForegroundColor Yellow
    $answer = Read-Host "Kill it and continue? (y/n)"
    if ($answer -eq "y") {
        Stop-Process -Id $ownerPid -Force -ErrorAction SilentlyContinue
        Start-Sleep -Milliseconds 500
    } else {
        Write-Host "Cannot continue while port $LocalProxy is in use. Exiting." -ForegroundColor Red
        Read-Host "Press Enter to close"
        exit 1
    }
}

Write-Host "==============================================" -ForegroundColor Cyan
Write-Host " TetherDirect - Wireless Internet Client" -ForegroundColor Cyan
Write-Host " No USB / No hotspot" -ForegroundColor Cyan
Write-Host "==============================================" -ForegroundColor Cyan
Write-Host ""

Write-Host "Connect Windows to the phone's DIRECT-* Wi-Fi network first." -ForegroundColor Yellow
Write-Host "The phone app must show P2P GROUP READY / ACTIVE." -ForegroundColor Yellow
Write-Host ""

# ==================================================
# Disable any old Windows proxy first
# ==================================================

Write-Host "Clearing old Windows proxy settings..." -ForegroundColor Yellow

reg.exe add `
    "HKCU\Software\Microsoft\Windows\CurrentVersion\Internet Settings" `
    /v ProxyEnable `
    /t REG_DWORD `
    /d 0 `
    /f | Out-Null

# ==================================================
# Find phone gateway
# ==================================================

$phone = $null

for ($i = 0; $i -lt 30 -and -not $phone; $i++) {

    $routes = Get-NetRoute `
        -DestinationPrefix "0.0.0.0/0" `
        -ErrorAction SilentlyContinue |
        Where-Object {
            $_.InterfaceAlias -match "Wi-Fi" -and
            $_.NextHop -ne "0.0.0.0"
        } |
        Sort-Object RouteMetric, IfMetric

    foreach ($r in $routes) {

        if ($r.NextHop -eq "192.168.49.1") {
            $phone = $r.NextHop
            break
        }
    }

    if (-not $phone) {
        Start-Sleep -Seconds 1
    }
}

if (-not $phone) {

    Write-Host ""
    Write-Host "ERROR: Could not find Wi-Fi Direct gateway 192.168.49.1" -ForegroundColor Red
    Write-Host ""
    Write-Host "Run:"
    Write-Host "ipconfig"
    Write-Host ""

    exit 1
}

Write-Host "Phone gateway: $phone" -ForegroundColor Green

# ==================================================
# Check phone SOCKS5
# ==================================================

$tcp = New-Object System.Net.Sockets.TcpClient

try {

    $iar = $tcp.BeginConnect(
        $phone,
        $Port,
        $null,
        $null
    )

    if (-not $iar.AsyncWaitHandle.WaitOne(5000)) {
        throw "Connection timeout"
    }

    $tcp.EndConnect($iar)

    Write-Host "Phone SOCKS5 server reachable on $phone`:$Port" -ForegroundColor Green
}
catch {

    try {
        $tcp.Close()
    }
    catch {}

    Write-Host ""
    Write-Host "ERROR: Phone SOCKS5 server is not reachable." -ForegroundColor Red
    Write-Host "Make sure the Android app shows ACTIVE." -ForegroundColor Yellow
    Write-Host ""

    exit 1
}

try {
    $tcp.Close()
}
catch {}

# ==================================================
# Build TetherProxy
# ==================================================

Write-Host ""
Write-Host "Building local HTTP proxy..." -ForegroundColor Yellow

if (-not (Test-Path $cs)) {

    Write-Host "ERROR: TetherProxy.cs not found." -ForegroundColor Red
    Write-Host $cs
    exit 1
}

try {

    Add-Type `
        -TypeDefinition (Get-Content $cs -Raw) `
        -Language CSharp

    Write-Host "TetherProxy loaded." -ForegroundColor Green

}
catch {

    Write-Host ""
    Write-Host "ERROR: Could not compile TetherProxy.cs" -ForegroundColor Red
    Write-Host ""
    Write-Host $_.Exception.Message -ForegroundColor Red
    Write-Host ""

    exit 1
}

# ==================================================
# Create proxy
# ==================================================

try {

    $proxy = New-Object TetherProxy($phone, $Port)

}
catch {

    Write-Host ""
    Write-Host "ERROR: Could not create TetherProxy." -ForegroundColor Red
    Write-Host $_.Exception.Message
    exit 1
}

# ==================================================
# START PROXY
# ==================================================
# Start() now binds the listener and returns immediately (it manages its
# own background thread internally, entirely in .NET, with no PowerShell
# scriptblock ever crossing threads). If the port is already taken, this
# throws right here, on the main thread, where we can catch and report
# it normally.

Write-Host ""
Write-Host "Starting local HTTP proxy on 127.0.0.1:$LocalProxy ..." -ForegroundColor Yellow

try {
    $proxy.Start($LocalProxy)
}
catch {
    Write-Host ""
    Write-Host "==============================================" -ForegroundColor Red
    Write-Host " ERROR: COULD NOT START LOCAL PROXY" -ForegroundColor Red
    Write-Host "==============================================" -ForegroundColor Red
    Write-Host ""
    Write-Host "$($_.Exception.Message)" -ForegroundColor Red
    if ($_.Exception.Message -match "already in use|address already|Only one usage") {
        Write-Host ""
        Write-Host "Port $LocalProxy is already held by another process" -ForegroundColor Yellow
        Write-Host "(likely a stuck copy of this script). Close all old" -ForegroundColor Yellow
        Write-Host "connect.ps1 / cmd windows and try again." -ForegroundColor Yellow
    }
    Write-Host ""
    Read-Host "Press Enter to close"
    exit 1
}

# ==================================================
# Verify local proxy
# ==================================================

Write-Host ""
Write-Host "Checking local proxy..." -ForegroundColor Yellow

$proxyOK = $false
$checkDeadline = (Get-Date).AddSeconds(6)

for ($i = 0; $i -lt 10 -and (Get-Date) -lt $checkDeadline; $i++) {

    try {

        $testTcp = New-Object System.Net.Sockets.TcpClient

        $iar = $testTcp.BeginConnect(
            "127.0.0.1",
            $LocalProxy,
            $null,
            $null
        )

        if ($iar.AsyncWaitHandle.WaitOne(1000)) {

            try {
                $testTcp.EndConnect($iar)
            }
            catch {}

            if ($testTcp.Connected) {
                $proxyOK = $true
            }
        }

        $testTcp.Close()

        if ($proxyOK) {
            break
        }

    }
    catch {}

    Start-Sleep -Milliseconds 500
}

if (-not $proxyOK) {

    Write-Host ""
    Write-Host "==============================================" -ForegroundColor Red
    Write-Host " ERROR: LOCAL HTTP PROXY DID NOT START" -ForegroundColor Red
    Write-Host "==============================================" -ForegroundColor Red
    Write-Host ""
    Write-Host "Phone SOCKS5 : $phone`:$Port" -ForegroundColor Yellow
    Write-Host "Local HTTP   : 127.0.0.1:$LocalProxy" -ForegroundColor Yellow
    Write-Host ""

    try {
        $proxy.Stop()
    }
    catch {}

    Read-Host "Press Enter to close"
    exit 1
}

Write-Host "Local HTTP proxy is listening on 127.0.0.1:$LocalProxy" -ForegroundColor Green

# ==================================================
# Configure Windows proxy
# ==================================================

Write-Host ""
Write-Host "Configuring Windows proxy..." -ForegroundColor Yellow

reg.exe add `
    "HKCU\Software\Microsoft\Windows\CurrentVersion\Internet Settings" `
    /v ProxyEnable `
    /t REG_DWORD `
    /d 1 `
    /f | Out-Null

reg.exe add `
    "HKCU\Software\Microsoft\Windows\CurrentVersion\Internet Settings" `
    /v ProxyServer `
    /t REG_SZ `
    /d "127.0.0.1:$LocalProxy" `
    /f | Out-Null

reg.exe add `
    "HKCU\Software\Microsoft\Windows\CurrentVersion\Internet Settings" `
    /v ProxyOverride `
    /t REG_SZ `
    /d "<local>;localhost;127.*" `
    /f | Out-Null

# ==================================================
# Refresh Windows Internet settings
# ==================================================

try {

    $signature = @'
using System;
using System.Runtime.InteropServices;

public class InternetSettingsRefresh
{
    [DllImport("wininet.dll", SetLastError = true)]
    public static extern bool InternetSetOption(
        IntPtr hInternet,
        int dwOption,
        IntPtr lpBuffer,
        int dwBufferLength
    );
}
'@

    Add-Type -TypeDefinition $signature -ErrorAction SilentlyContinue

    [InternetSettingsRefresh]::InternetSetOption(
        [IntPtr]::Zero,
        39,
        [IntPtr]::Zero,
        0
    )

    [InternetSettingsRefresh]::InternetSetOption(
        [IntPtr]::Zero,
        37,
        [IntPtr]::Zero,
        0
    )

}
catch {}

# ==================================================
# FINAL STATUS
# ==================================================

Write-Host ""
Write-Host "==============================================" -ForegroundColor Green
Write-Host "       WIRELESS INTERNET IS READY" -ForegroundColor Green
Write-Host "==============================================" -ForegroundColor Green
Write-Host ""

Write-Host "Phone SOCKS5 : $phone`:$Port" -ForegroundColor Green
Write-Host "Windows HTTP : 127.0.0.1:$LocalProxy" -ForegroundColor Green

Write-Host ""
Write-Host "Local proxy is ACTIVE." -ForegroundColor Green
Write-Host "Browser traffic can now go through the phone." -ForegroundColor Green

Write-Host ""
Write-Host "DO NOT CLOSE THIS WINDOW." -ForegroundColor Yellow
Write-Host "Press ENTER to stop the connection." -ForegroundColor Yellow
Write-Host ""

Read-Host | Out-Null

# ==================================================
# STOP
# ==================================================

Write-Host ""
Write-Host "Stopping proxy..." -ForegroundColor Yellow

try {
    $proxy.Stop()
}
catch {}

Start-Sleep -Milliseconds 500

# Disable Windows proxy

reg.exe add `
    "HKCU\Software\Microsoft\Windows\CurrentVersion\Internet Settings" `
    /v ProxyEnable `
    /t REG_DWORD `
    /d 0 `
    /f | Out-Null

reg.exe delete `
    "HKCU\Software\Microsoft\Windows\CurrentVersion\Internet Settings" `
    /v ProxyServer `
    /f 2>$null | Out-Null

reg.exe delete `
    "HKCU\Software\Microsoft\Windows\CurrentVersion\Internet Settings" `
    /v ProxyOverride `
    /f 2>$null | Out-Null

Write-Host "Windows proxy disabled." -ForegroundColor Green
Write-Host "Stopped." -ForegroundColor Green