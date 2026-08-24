# TetherDirect

**Share your phone's internet with your PC — no root, no USB, no hotspot plan.**

TetherDirect turns your Android phone into a wireless internet source for any
Windows PC over **Wi-Fi Direct**. The phone runs a small proxy that sends traffic
out over its mobile data; the PC app routes *all* of Windows through it.

- ✅ **No root** — works on any normal Android phone (Android 10+)
- ✅ **No USB cable**
- ✅ **No carrier hotspot toggle** — uses Wi-Fi Direct, not Android's built-in hotspot
- ✅ **Whole-PC internet** — every app on the PC, not just the browser
- ✅ **Free & open** — you install both halves yourself

> ⚠️ **Note:** Using your phone's mobile data on another device (tethering) may be
> restricted by some carrier plans. Check your plan. This tool is for using data
> you're entitled to, on your own devices.

---

## What you install

TetherDirect has two halves:

| Half | What it is | Where |
|------|------------|-------|
| **Phone app** | Android app that shares the connection | `android-app/` → APK |
| **PC app** | Windows app that receives it | `windows-app/TetherDirect.exe` |

---

## Quick start

### 1. Phone (Android)
1. Install **TetherDirect.apk** (see [Getting the apps](#getting-the-apps) below).
2. Open the app, allow the permissions it asks for (Nearby devices + Notifications).
3. Make sure **Wi-Fi is ON** and **Mobile Data is ON**.
4. Tap the switch to **start sharing**.
5. The app shows a **network name** (`DIRECT-xxxxx`) and a **Wi-Fi password**.

### 2. PC (Windows 10/11)
1. Open **Wi-Fi settings** and connect to the phone's **`DIRECT-...`** network,
   using the password shown in the app.
2. Run **`windows-app/TetherDirect.exe`** → *Run as administrator*.
3. Click **Connect**.
4. Done — your whole PC is now online through the phone. 🎉

Click **Disconnect** (or just close the app) to go back to normal.

---

## Getting the apps

### The Windows app
It's ready to run in [`windows-app/`](windows-app/):

```
windows-app/
  TetherDirect.exe              <- the app (run as administrator)
  tun2socks-windows-amd64.exe   <- bundled engine
  wintun.dll                    <- bundled driver
```

Keep those three files together in the same folder. To rebuild it yourself,
run [`windows-app/build.cmd`](windows-app/build.cmd) (uses the C# compiler that
ships with Windows — no Visual Studio needed).

### The Android APK
Android APKs are built in the cloud by **GitHub Actions** (building needs the
Android SDK). Two ways to get it:

- **Download the prebuilt APK:** grab `TetherDirect.apk` from the repo's
  **Releases → “TetherDirect (latest build)”**.
- **Build it yourself:** open `android-app/` in Android Studio and Run, or from a
  terminal with the Android SDK installed:
  ```bash
  cd android-app
  gradle :app:assembleDebug
  # APK at: app/build/outputs/apk/debug/app-debug.apk
  ```

On the phone, open the downloaded APK and allow **“Install from unknown sources”**
if prompted.

---

## How it works (short version)

```
   PC (all apps)                    Phone (TetherDirect app)
        │                                   │
        │  Wi-Fi Direct link                │
        ▼                                   ▼
  tun2socks (virtual  ──SOCKS5 :8888──►  SOCKS5 proxy  ──►  Mobile data  ──► Internet
  network adapter)                       (routes via the phone's cellular network)
```

The phone creates a Wi-Fi Direct group (it becomes `192.168.49.1`) and runs a
**SOCKS5 proxy** on port `8888`. That proxy opens its outgoing connections on the
phone's **cellular** network, so no root or `iptables` is needed. On the PC,
`tun2socks` creates a virtual network adapter and feeds all of Windows' traffic
into that proxy.

See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for the full picture and
[docs/SETUP_GUIDE.md](docs/SETUP_GUIDE.md) for detailed steps and troubleshooting.

---

## Troubleshooting (quick)

- **PC app says “Could not reach the phone.”** Make sure the phone app shows
  *ACTIVE / P2P READY* and the PC is connected to the phone's `DIRECT-...` Wi-Fi.
- **Connected but pages don't load.** Check the phone has working mobile data.
  Also give DNS a few seconds — the app resolves the first names over TCP, so the
  very first lookups can lag briefly. Test with a **browser**, not `ping` (ICMP may
  not tunnel even when browsing works).
- **Phone app shows an error creating the group.** Turn Wi-Fi off and on, turn off
  any VPN and any active hotspot, then try again.

## License

For personal/educational use. Modify freely.
