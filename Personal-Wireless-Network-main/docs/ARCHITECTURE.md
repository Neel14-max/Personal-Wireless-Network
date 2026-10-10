# TetherDirect — Technical Architecture

## System Overview

TetherDirect shares an Android phone's mobile-data connection with a PC over a
direct **Wi-Fi Direct (P2P)** link. Crucially, it does this **without root** and
**without `iptables`/NAT**.

Instead of kernel-level packet forwarding, the phone runs a small **user-space
SOCKS5 proxy**. Every outbound connection that proxy makes is created on the
phone's **cellular network** via Android's `Network.getSocketFactory()`. On the
PC, **tun2socks** creates a virtual network adapter and feeds all of Windows'
traffic into that proxy — so the whole PC goes online, not just the browser.

This is the key design decision: **no root is required**, because nothing touches
the kernel routing tables on the phone. It's ordinary app-level networking.

```
   PC (all apps)                         Phone (TetherDirect app)
        │                                        │
        │  Wi-Fi Direct link (WPA2)              │
        ▼                                        ▼
  tun2socks  ───────SOCKS5 :8888──────►   SOCKS5 proxy (P2pService)
  (virtual TUN adapter,                          │
   wintun.dll)                                    │  socket created on the
        ▲                                         ▼  cellular Network object
        │                                   Mobile data (4G/5G)
   default route                                  │
   0.0.0.0/0                                       ▼
                                                Internet
```

## Why Wi-Fi Direct instead of the built-in hotspot?

| Aspect | Android Hotspot | TetherDirect |
|--------|-----------------|--------------|
| Protocol | SoftAP (802.11) | Wi-Fi Direct P2P |
| Carrier hotspot toggle | Required (often plan-gated) | Not used |
| Root | No | No |
| PC coverage | Whole device | Whole device (via tun2socks) |

Wi-Fi Direct lets two devices form an encrypted link without using Android's
tetering/hotspot feature, which some plans restrict or meter separately.

---

## Components

### Phone (`android-app/`, package `com.p2pshare`)

#### `P2pManager.java`
- Wraps Android's `WifiP2pManager`.
- Calls `createGroup()` to make the phone a **Group Owner (GO)** — it becomes
  `192.168.49.1` and Android's framework runs DHCP for clients automatically
  (no custom DHCP server needed).
- `createGroup()` is asynchronous, so it **polls `requestGroupInfo()`** (24 tries,
  ~12 s) until Android publishes the group's **network name** and **passphrase**.
- Removes any stale group first to avoid the `BUSY` state on repeated starts.
- Checks the right runtime permission per OS level: `NEARBY_WIFI_DEVICES`
  (Android 13+) or `ACCESS_FINE_LOCATION` (older).

#### `P2pService.java` — the heart of the app
A foreground `Service` that:
1. **Requests the cellular network explicitly** via
   `ConnectivityManager.requestNetwork()` with `TRANSPORT_CELLULAR +
   NET_CAPABILITY_INTERNET`, and keeps a reference to that `Network`.
2. Starts the Wi-Fi Direct group (through `P2pManager`).
3. Runs a **user-space SOCKS5 server** on `0.0.0.0:8888`:
   - Standard SOCKS5 handshake, **no auth**. Handles both **`CONNECT`** (TCP)
     and **`UDP ASSOCIATE`** (UDP).
   - Supports address types IPv4 / domain-name / IPv6.
   - **CONNECT** opens the outbound TCP socket with
     **`mobileNetwork.getSocketFactory().createSocket(host, port)`**, so traffic
     leaves over mobile data even while the phone's *default* network is Wi-Fi
     Direct. Falls back to a plain socket if the cellular callback isn't ready.
     Bidirectional `tunnel()` copies bytes between the PC socket and the remote
     socket on a cached thread pool.
   - **UDP ASSOCIATE** binds a relay `DatagramSocket` on the Wi-Fi Direct IP and
     returns its `BND.ADDR:BND.PORT`. For each datagram it strips the SOCKS5 UDP
     header and forwards the payload out over the cellular network (the relay
     socket is pinned to mobile data with **`Network.bindSocket()`**), then relays
     replies back with the header re-applied. This is what lets **DNS, QUIC /
     HTTP-3 and other UDP** traffic tunnel too — not just TCP. The association
     lives as long as its TCP control connection stays open.
- Publishes state through `static volatile` fields
  (`isRunning`, `currentGroupName`, `currentGroupPass`, `proxyStatus`,
  `currentError`) that the UI reads, and shows an ongoing notification.

#### `MainActivity.java`
- Single on/off switch UI, permission requests, and a friendly
  step-by-step panel showing the network name, password, and what to do on the PC.
- Reads the `P2pService` status fields to update the display.

#### `AppContextHolder.java`
- Tiny holder so `P2pManager` can check permissions with an app `Context`.

> There are **no** `NatRouter`, `DhcpServer`, or root/`iptables` components. Earlier
> prototypes had them; the shipped app doesn't need them and they've been removed.

### PC (`windows-app/`)

#### `TetherDirect.exe` (from `TetherDirect.cs`)
A single-file WinForms app (built with the C# compiler bundled in the .NET
Framework — no Visual Studio required). It:
1. Verifies it's running **as administrator** and that `tun2socks` + `wintun.dll`
   are next to it.
2. Waits until the phone's SOCKS5 (`192.168.49.1:8888`) is reachable.
3. Launches **tun2socks**:
   ```
   tun2socks-windows-amd64.exe --device tun://TetherDirect \
       --proxy socks5://192.168.49.1:8888
   ```
   `wintun.dll` provides the virtual **TUN** adapter it creates.
4. Configures that adapter with `netsh`: static IP `192.168.250.1/24`, a
   **default route** `0.0.0.0/0 → 192.168.250.1` (low metric) so all Windows
   traffic enters the tunnel, and sets the adapter's **DNS server to itself**
   (`192.168.250.1`).
5. Runs a **built-in DNS-over-TCP helper** bound to `192.168.250.1:53` as a
   reliability layer: it answers Windows' DNS queries by forwarding each one to a
   public resolver (`1.1.1.1`, then `8.8.8.8`) **over TCP** (RFC 7766), which
   always rides the tunnel. (The phone now also relays UDP directly via
   `UDP ASSOCIATE`, so DNS *could* go over UDP — but resolving over TCP locally
   keeps name lookups fast and robust regardless of the phone's UDP support.)
6. On **Disconnect**/close, deletes the route, stops the DNS helper, and kills
   tun2socks, restoring the PC's normal networking.

`START_INTERNET.bat` performs the same steps from a console; the app is the
one-click version of it.

### Alternative clients (`laptop-client/`)
- **Windows browser-proxy** (`connect.ps1` / `.cmd`): compiles a tiny local HTTP→
  SOCKS5 bridge (`TetherProxy.cs`) at `127.0.0.1:8080` and sets the Windows proxy.
  No admin, but only proxy-aware apps use it.
- **Linux** (`connect.py` / `connect.sh`): joins the phone's Wi-Fi Direct group.

---

## Data flow (full-tunnel path)

```
Any Windows app
    │  (default route 0.0.0.0/0)
    ▼
TetherDirect TUN adapter (192.168.250.1)   ← wintun.dll
    │  tun2socks encapsulates IP → SOCKS5
    ▼
Phone Wi-Fi Direct GO (192.168.49.1:8888)  ← P2pService SOCKS5 server
    │  new socket on the cellular Network object
    ▼
Mobile data (4G/5G)
    ▼
Internet
```

## Security

- The Wi-Fi Direct link is **WPA2-encrypted** with an auto-generated passphrase
  shown only in the app.
- The SOCKS5 proxy listens on the P2P interface and takes **no authentication**,
  so treat the link as trusted — anyone with the Wi-Fi Direct password can use it.
- No traffic passes through any third-party server; the phone connects directly.

## Limitations

- Full-PC tunneling on Windows needs **administrator rights** (to add the route).
- The proxy relays both TCP (`CONNECT`) and UDP (`UDP ASSOCIATE`), so DNS, QUIC /
  HTTP-3 and other UDP traffic tunnel natively. Only unfragmented UDP datagrams
  are relayed (`FRAG` must be 0) — standard behaviour that matches every
  mainstream SOCKS5 client. The Windows app also keeps its DNS-over-TCP helper as
  a belt-and-suspenders fallback for name resolution.
- Throughput depends on Wi-Fi Direct radio conditions and the mobile signal.
- Some carriers may still detect tethering by other means (e.g. DPI); this project
  makes no attempt to defeat that.

## Possible extensions

- **Bandwidth stats** in the notification / UI.
- **SOCKS5 authentication** for a locked-down link.
- **macOS/Linux full-tunnel** clients using their native `tun` + a tun2socks build.
- **Auto-reconnect** on Wi-Fi Direct state changes.
