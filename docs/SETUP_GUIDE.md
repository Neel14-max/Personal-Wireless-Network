# TetherDirect — Setup Guide

A step-by-step guide to sharing your Android phone's internet with a Windows PC.
**No root. No USB. No carrier hotspot toggle.**

> ⚠️ Tethering (using phone data on another device) may be restricted by some
> carrier plans. Check your plan before relying on this.

---

## What you need

### Phone
- Android **10 or newer** (almost every phone since ~2019)
- Wi-Fi Direct support (standard on essentially all modern phones)
- A working **mobile data** connection (4G/5G)
- **No root required**

### PC
- **Windows 10 or 11** (64-bit)
- A Wi-Fi adapter
- Administrator rights (to set up routing)

---

## Part 1 — Phone

### Install the app
Get **TetherDirect.apk** (see the main [README](../README.md#getting-the-apps)):
- download it from the repo's **Releases**, or
- build `android-app/` in Android Studio.

Open the APK on your phone and allow **“Install from unknown sources”** if asked.

### Turn on sharing
1. Open **TetherDirect**.
2. Grant the permissions it requests:
   - **Nearby devices** (needed for Wi-Fi Direct)
   - **Notifications**
3. Turn **Wi-Fi ON** (it does not need to be connected to anything).
4. Turn **Mobile Data ON**.
5. Tap the switch to **ON**.
6. Wait until it shows **P2P READY / ACTIVE** with a:
   - **Network name** — looks like `DIRECT-xx-Android_xxxx`
   - **Wi-Fi password**

Leave this screen open while you set up the PC.

---

## Part 2 — Windows PC

### Step 1: Join the phone's Wi-Fi
1. Click the Wi-Fi icon in the Windows taskbar.
2. Find and connect to the **`DIRECT-...`** network from your phone.
3. Enter the **password** shown in the phone app.

Windows will say something like *“No internet”* on that network — that's expected
until the next step.

### Step 2: Run the TetherDirect app
1. Go to the **`windows-app`** folder.
2. Right-click **`TetherDirect.exe`** → **Run as administrator**
   (it needs admin rights to configure routing; Windows will prompt you).
3. Click **Connect**.
4. The activity log will walk through: *found phone → tunnel started → configured
   → internet working.*

That's it — **every app on your PC now uses the phone's internet.**

### To stop
Click **Disconnect**, or just close the window. Your PC returns to its normal
connection automatically.

---

## Troubleshooting

**PC app: “Could not reach the phone.”**
- The phone app must show **ACTIVE / P2P READY**.
- The PC must be connected to the phone's **`DIRECT-...`** Wi-Fi (Step 1).
- Try toggling the phone's sharing switch off and on.

**PC app: “Administrator rights are required.”**
- Right-click `TetherDirect.exe` → **Run as administrator**.

**Connected, but web pages don't load.**
- Confirm the **phone has working mobile data** (open a site on the phone itself).
- Disable any **VPN** on the phone.
- Give DNS a few seconds — the very first name lookups after connecting can lag.
- Test with a **web browser**, not `ping` — ICMP may not tunnel even when
  browsing works fine.

**Phone: error creating the Wi-Fi Direct group.**
- Turn phone **Wi-Fi off and on**.
- Turn **off** any active **Hotspot** and any **VPN**, then retry.
- Make sure **Nearby devices** permission is allowed for the app.

**`tun2socks-windows-amd64.exe` / `wintun.dll` missing.**
- Keep all three files (`TetherDirect.exe`, `tun2socks-windows-amd64.exe`,
  `wintun.dll`) together in the same folder.

---

## Advanced / alternative clients

You don't need these if you use the Windows app — they're here for power users.

- **Browser-only proxy (no admin):** `laptop-client/windows/Connect-Wireless-Internet.cmd`
  sets up a local HTTP proxy at `127.0.0.1:8080` that forwards to the phone's
  SOCKS5. Only apps that honor the Windows proxy use it.
- **Command-line full tunnel:** `START_INTERNET.bat` does what the app does, from a
  console window.
- **Linux client:** `laptop-client/connect.py` / `connect.sh` connect a Linux
  laptop to the phone's Wi-Fi Direct group.

---

## License

For personal/educational use. Modify freely.
