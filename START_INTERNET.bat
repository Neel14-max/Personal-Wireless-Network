@echo off
title TetherDirect - Phone Internet

echo ==========================================
echo       TetherDirect Wireless Internet
echo ==========================================
echo.
echo TIP: For a one-click experience, use the app instead:
echo      windows-app\TetherDirect.exe  (Run as administrator)
echo.

echo [1] Checking phone connection...
powershell -Command "if (Test-NetConnection 192.168.49.1 -Port 8888 -InformationLevel Quiet) { exit 0 } else { exit 1 }"

if errorlevel 1 (
    echo.
    echo ERROR: Phone SOCKS5 is not reachable.
    echo.
    echo Make sure:
    echo 1. Phone app is running
    echo 2. Sharing is ON / P2P GROUP is ACTIVE
    echo 3. This PC is connected to the phone's DIRECT-... Wi-Fi network
    echo.
    pause
    exit /b 1
)

echo Phone SOCKS5 is reachable.
echo.

echo [2] Starting TUN interface...
start "TetherDirect Tunnel" /min "%~dp0tun2socks-windows-amd64.exe" ^
    --device "tun://TetherDirect" ^
    --proxy "socks5://192.168.49.1:8888" ^
    --loglevel info

timeout /t 3 /nobreak >nul

echo.
echo [3] Configuring TUN adapter...

netsh interface ipv4 set address name="TetherDirect" source=static addr=192.168.250.1 mask=255.255.255.0

netsh interface ipv4 set dnsservers name="TetherDirect" static address=1.1.1.1 register=none validate=no

echo.
echo [4] Adding Internet route...

netsh interface ipv4 add route 0.0.0.0/0 "TetherDirect" 192.168.250.1 metric=1

echo.
echo ==========================================
echo        TETHERDIRECT IS ACTIVE
echo ==========================================
echo.
echo Phone:       192.168.49.1
echo SOCKS5:      192.168.49.1:8888
echo TUN:         192.168.250.1
echo.
echo NOTE: with the latest phone app, UDP (including DNS) tunnels via SOCKS5
echo UDP ASSOCIATE, so name resolution works here. If names do NOT resolve,
echo your phone app is older - update it, or use windows-app\TetherDirect.exe
echo (it has a built-in DNS-over-TCP fallback). Do NOT test with "ping" -
echo ICMP may not tunnel even when browsing works; open a website instead.
echo.
echo DO NOT CLOSE the TetherDirect Tunnel window.
echo.

pause
