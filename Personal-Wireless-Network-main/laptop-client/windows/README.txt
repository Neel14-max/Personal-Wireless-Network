TETHERDIRECT - WINDOWS BROWSER-PROXY CLIENT

EASIEST OPTION: use the app instead of this script.
  Run  windows-app\TetherDirect.exe  (Run as administrator), click Connect.
  That tunnels the WHOLE PC. This folder is only the browser-only fallback.

Browser-proxy client steps:
1. Start the Android app and turn sharing ON.
2. Wait until it shows P2P READY / ACTIVE with a network name and password.
3. On Windows, connect to that DIRECT-* Wi-Fi network once. Windows remembers it.
4. Run Connect-Wireless-Internet.cmd.
5. It finds the phone at 192.168.49.1, connects to its SOCKS5 port 8888, and
   creates a local HTTP proxy on 127.0.0.1:8080, then points Windows at it.
6. Proxy-aware apps (browsers) then use the phone's mobile-data connection.

This is NOT a full system tunnel - it is a user-space proxy bridge, so only apps
that honor the Windows proxy use it. For every app, use TetherDirect.exe instead.
No USB, no phone hotspot, and no root are required.
