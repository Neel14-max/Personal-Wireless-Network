package com.p2pshare;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Wi-Fi Direct group + user-space SOCKS5 Internet proxy.
 *
 * This deliberately does NOT use iptables/NAT/root.  Windows connects to the
 * phone over Wi-Fi Direct and sends its traffic to SOCKS5 :8888.  Outbound
 * sockets are created through Android's cellular Network object.
 *
 * The proxy handles both SOCKS5 commands: CONNECT (TCP) and UDP ASSOCIATE
 * (UDP).  UDP support is what lets DNS, QUIC / HTTP-3 and other datagram
 * traffic tunnel through the phone as well, not just TCP.
 */
public class P2pService extends Service {
    private static final String TAG = "P2pService";
    public static final String ACTION_START = "com.p2pshare.START";
    public static final String ACTION_STOP = "com.p2pshare.STOP";
    private static final int NOTIF_ID = 1001;
    private static final String CHANNEL_ID = "p2p_channel";
    public static final int PROXY_PORT = 8888;

    public static volatile boolean isRunning = false;
    public static volatile String currentGroupName = "";
    public static volatile String currentGroupPass = "";
    public static volatile String currentError = "";
    public static volatile String proxyStatus = "Starting cellular network...";

    private P2pManager p2pManager;
    private ServerSocket proxyServer;
    private ExecutorService pool;
    private ConnectivityManager.NetworkCallback networkCallback;
    private volatile Network mobileNetwork;
    private volatile boolean proxyRunning;

    @Override public void onCreate() {
        super.onCreate();
        AppContextHolder.init(this);
        createChannel();
        p2pManager = new P2pManager(this);
        pool = Executors.newCachedThreadPool();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            if (ACTION_START.equals(intent.getAction())) startSharing();
            else if (ACTION_STOP.equals(intent.getAction())) stopSharing();
        }
        return START_STICKY;
    }

    private void startSharing() {
        startForeground(NOTIF_ID, buildNotif("Starting Wi-Fi Direct..."));
        currentError = "";
        proxyStatus = "Requesting mobile Internet...";
        requestCellularNetwork();

        p2pManager.setListener(new P2pManager.Listener() {
            @Override public void onGroupCreated(android.net.wifi.p2p.WifiP2pGroup group) {
                currentGroupName = safe(group.getNetworkName());
                currentGroupPass = safe(group.getPassphrase());
                currentError = "";
                startSocks5();
                isRunning = true;
                updateNotif("P2P READY", currentGroupName + "  SOCKS5 :" + PROXY_PORT);
            }
            @Override public void onGroupRemoved() {
                isRunning = false;
                currentGroupName = "";
                currentGroupPass = "";
            }
            @Override public void onError(String msg) {
                currentError = msg == null ? "Unknown Wi-Fi Direct error" : msg;
                updateNotif("P2P ERROR", currentError);
                Log.e(TAG, currentError);
            }
        });

        p2pManager.createGroup();
    }

    private String safe(String s) { return s == null ? "" : s; }

    private void requestCellularNetwork() {
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            NetworkRequest req = new NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build();
            networkCallback = new ConnectivityManager.NetworkCallback() {
                @Override public void onAvailable(Network network) {
                    mobileNetwork = network;
                    proxyStatus = "Cellular Internet ready";
                    updateNotif("P2P READY", "Mobile data ready; SOCKS5 :" + PROXY_PORT);
                }
                @Override public void onLost(Network network) {
                    if (network.equals(mobileNetwork)) {
                        mobileNetwork = null;
                        proxyStatus = "Waiting for mobile Internet...";
                    }
                }
            };
            cm.requestNetwork(req, networkCallback);
        } catch (Throwable t) {
            proxyStatus = "Cellular request failed: " + t.getClass().getSimpleName();
            Log.e(TAG, "requestNetwork", t);
        }
    }

    private void startSocks5() {
        if (proxyRunning) return;
        proxyRunning = true;
        pool.execute(() -> {
            try {
                proxyServer = new ServerSocket();
                proxyServer.setReuseAddress(true);
                proxyServer.bind(new InetSocketAddress("0.0.0.0", PROXY_PORT));
                Log.i(TAG, "SOCKS5 listening on 0.0.0.0:" + PROXY_PORT);
                while (proxyRunning) {
                    Socket client = proxyServer.accept();
                    client.setSoTimeout(30000);
                    pool.execute(() -> handleSocks(client));
                }
            } catch (Throwable t) {
                if (proxyRunning) {
                    currentError = "SOCKS5 server failed: " + t.getMessage();
                    Log.e(TAG, currentError, t);
                }
            }
        });
    }

    private void handleSocks(Socket client) {
        try (Socket c = client) {
            InputStream in = c.getInputStream();
            OutputStream out = c.getOutputStream();

            int ver = in.read();
            int nMethods = in.read();
            if (ver != 5 || nMethods < 0) return;
            byte[] methods = readFully(in, nMethods);
            out.write(new byte[]{5, 0}); // NO AUTH
            out.flush();

            if (in.read() != 5) return;
            int cmd = in.read();
            in.read(); // RSV
            int atyp = in.read();

            // DST.ADDR + DST.PORT are present for both CONNECT and UDP ASSOCIATE.
            String host = parseAddr(in, atyp, out);
            if (host == null) return;
            byte[] pb = readFully(in, 2);
            int port = ((pb[0]&255) << 8) | (pb[1]&255);

            if (cmd == 1) {                 // CONNECT (TCP)
                Socket remote = openMobile(host, port);
                if (remote == null) { sendReply(out, 5); return; }
                try (Socket r = remote) {
                    sendReply(out, 0);
                    tunnel(c, r);
                }
            } else if (cmd == 3) {          // UDP ASSOCIATE (UDP relay)
                handleUdpAssociate(c, in, out);
            } else {
                sendReply(out, 7);          // command not supported
            }
        } catch (Throwable ignored) {}
    }

    /** Reads a SOCKS5 address of the given ATYP. Returns null (after sending an
     *  error reply for an unsupported ATYP) if it can't be read. */
    private String parseAddr(InputStream in, int atyp, OutputStream out) throws IOException {
        if (atyp == 1) {
            byte[] a = readFully(in, 4);
            return (a[0]&255)+"."+(a[1]&255)+"."+(a[2]&255)+"."+(a[3]&255);
        } else if (atyp == 3) {
            int len = in.read();
            if (len < 0) return null;
            return new String(readFully(in, len), StandardCharsets.UTF_8);
        } else if (atyp == 4) {
            byte[] a = readFully(in, 16);
            return InetAddress.getByAddress(a).getHostAddress();
        }
        sendReply(out, 8); // address type not supported
        return null;
    }

    private Socket openMobile(String host, int port) {
        try {
            Network n = mobileNetwork;
            if (n != null) return n.getSocketFactory().createSocket(host, port);
            // Fallback to normal Android networking if cellular callback is not ready.
            return new Socket(host, port);
        } catch (Throwable t) {
            Log.e(TAG, "connect " + host + ":" + port, t);
            return null;
        }
    }

    private void sendReply(OutputStream out, int code) throws IOException {
        out.write(new byte[]{5,(byte)code,0,1,0,0,0,0,0,0});
        out.flush();
    }

    // ------------------------------------------------------------------
    // UDP ASSOCIATE relay (SOCKS5 cmd = 3)
    //
    // tun2socks opens a TCP control connection, sends UDP ASSOCIATE, then sends
    // UDP datagrams (each wrapped in a SOCKS5 UDP header) to the BND address/port
    // we return.  We strip the header, forward the payload out over the cellular
    // network, and relay replies back with the header re-applied.  The
    // association lives exactly as long as the TCP control connection stays open.
    // ------------------------------------------------------------------
    private void handleUdpAssociate(Socket c, InputStream in, OutputStream out) {
        DatagramSocket clientSide = null;
        DatagramSocket mobileSide = null;
        try {
            // The local address tun2socks reached us on (the Wi-Fi Direct IP).
            InetAddress bnd = c.getLocalAddress();
            if (bnd == null || bnd.isAnyLocalAddress()) bnd = InetAddress.getByName("192.168.49.1");

            clientSide = new DatagramSocket(new InetSocketAddress(bnd, 0));
            mobileSide = new DatagramSocket();
            Network n = mobileNetwork;
            if (n != null) {
                try { n.bindSocket(mobileSide); }
                catch (Throwable t) { Log.w(TAG, "bindSocket(udp)", t); }
            }

            // Tell the client which address/port to send its UDP datagrams to.
            sendUdpReply(out, bnd, clientSide.getLocalPort());
            // A live UDP flow can idle far longer than the 30s control-socket timeout.
            try { c.setSoTimeout(0); } catch (Exception ignored) {}

            final DatagramSocket cs = clientSide;
            final DatagramSocket ms = mobileSide;
            final AtomicReference<InetSocketAddress> clientAddr = new AtomicReference<InetSocketAddress>();

            Future<?> up = pool.submit(() -> udpClientToRemote(cs, ms, clientAddr));
            Future<?> down = pool.submit(() -> udpRemoteToClient(cs, ms, clientAddr));

            // Block until the control connection closes; that ends the association.
            byte[] tmp = new byte[512];
            try { while (in.read(tmp) >= 0) { /* drain / keep-alive */ } } catch (Exception ignored) {}

            up.cancel(true);
            down.cancel(true);
        } catch (Throwable t) {
            Log.e(TAG, "udp associate", t);
        } finally {
            if (clientSide != null) try { clientSide.close(); } catch (Exception ignored) {}
            if (mobileSide != null) try { mobileSide.close(); } catch (Exception ignored) {}
        }
    }

    // Client -> remote: unwrap the SOCKS5 UDP header, send the payload out over mobile.
    private void udpClientToRemote(DatagramSocket cs, DatagramSocket ms, AtomicReference<InetSocketAddress> clientAddr) {
        byte[] buf = new byte[65535];
        while (!Thread.currentThread().isInterrupted()) {
            try {
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                cs.receive(p);
                clientAddr.set(new InetSocketAddress(p.getAddress(), p.getPort()));

                byte[] d = p.getData();
                int len = p.getLength();
                // RSV(2)=0, FRAG(1)=0 only (no reassembly, per common practice).
                if (len < 4 || d[0] != 0 || d[1] != 0 || (d[2] & 255) != 0) continue;
                int atyp = d[3] & 255;
                int idx = 4;
                InetAddress dst;
                if (atyp == 1) {
                    if (len < idx + 6) continue;
                    dst = InetAddress.getByAddress(new byte[]{d[idx],d[idx+1],d[idx+2],d[idx+3]});
                    idx += 4;
                } else if (atyp == 4) {
                    if (len < idx + 18) continue;
                    byte[] a = new byte[16];
                    System.arraycopy(d, idx, a, 0, 16);
                    dst = InetAddress.getByAddress(a);
                    idx += 16;
                } else if (atyp == 3) {
                    int dlen = d[idx] & 255; idx += 1;
                    if (len < idx + dlen + 2) continue;
                    dst = resolveOnMobile(new String(d, idx, dlen, StandardCharsets.UTF_8));
                    idx += dlen;
                    if (dst == null) continue;
                } else continue;
                int dport = ((d[idx]&255) << 8) | (d[idx+1]&255);
                idx += 2;
                ms.send(new DatagramPacket(d, idx, len - idx, dst, dport));
            } catch (Throwable t) {
                if (cs.isClosed() || ms.isClosed()) break;
            }
        }
    }

    // Remote -> client: wrap the reply in a SOCKS5 UDP header, hand it back to tun2socks.
    private void udpRemoteToClient(DatagramSocket cs, DatagramSocket ms, AtomicReference<InetSocketAddress> clientAddr) {
        byte[] buf = new byte[65535];
        while (!Thread.currentThread().isInterrupted()) {
            try {
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                ms.receive(p);
                InetSocketAddress ca = clientAddr.get();
                if (ca == null) continue;

                byte[] addr = p.getAddress().getAddress();
                int atyp = addr.length == 16 ? 4 : 1;
                int hdr = 4 + addr.length + 2;
                int dataLen = p.getLength();
                byte[] o = new byte[hdr + dataLen];
                o[0] = 0; o[1] = 0; o[2] = 0; o[3] = (byte) atyp;
                System.arraycopy(addr, 0, o, 4, addr.length);
                int sport = p.getPort();
                o[4 + addr.length]     = (byte)((sport >> 8) & 255);
                o[4 + addr.length + 1] = (byte)(sport & 255);
                System.arraycopy(p.getData(), p.getOffset(), o, hdr, dataLen);
                cs.send(new DatagramPacket(o, o.length, ca.getAddress(), ca.getPort()));
            } catch (Throwable t) {
                if (cs.isClosed() || ms.isClosed()) break;
            }
        }
    }

    private void sendUdpReply(OutputStream out, InetAddress bnd, int port) throws IOException {
        byte[] a = bnd.getAddress();
        int atyp = a.length == 16 ? 4 : 1;
        byte[] msg = new byte[4 + a.length + 2];
        msg[0] = 5; msg[1] = 0; msg[2] = 0; msg[3] = (byte) atyp;
        System.arraycopy(a, 0, msg, 4, a.length);
        msg[4 + a.length]     = (byte)((port >> 8) & 255);
        msg[4 + a.length + 1] = (byte)(port & 255);
        out.write(msg);
        out.flush();
    }

    private InetAddress resolveOnMobile(String host) {
        try {
            Network n = mobileNetwork;
            if (n != null) {
                InetAddress[] arr = n.getAllByName(host);
                if (arr != null && arr.length > 0) return arr[0];
            }
            return InetAddress.getByName(host);
        } catch (Throwable t) { return null; }
    }

    private void tunnel(Socket a, Socket b) throws IOException {
        Future<?> f1 = pool.submit(() -> { try { copy(a.getInputStream(), b.getOutputStream()); } catch (Exception ignored) {} });
        Future<?> f2 = pool.submit(() -> { try { copy(b.getInputStream(), a.getOutputStream()); } catch (Exception ignored) {} });
        try { f1.get(10, TimeUnit.MINUTES); } catch (Exception ignored) {}
        f2.cancel(true);
    }

    private void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[64 * 1024];
        int n;
        while ((n = in.read(buf)) > 0) { out.write(buf, 0, n); out.flush(); }
    }

    private byte[] readFully(InputStream in, int n) throws IOException {
        byte[] b = new byte[n]; int off = 0;
        while (off < n) { int r = in.read(b, off, n-off); if (r < 0) throw new EOFException(); off += r; }
        return b;
    }

    private void stopSharing() {
        proxyRunning = false;
        try { if (proxyServer != null) proxyServer.close(); } catch (Exception ignored) {}
        proxyServer = null;
        if (pool != null) pool.shutdownNow();
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            if (networkCallback != null) cm.unregisterNetworkCallback(networkCallback);
        } catch (Exception ignored) {}
        networkCallback = null;
        mobileNetwork = null;
        if (p2pManager != null) p2pManager.removeGroup();
        isRunning = false;
        currentGroupName = "";
        currentGroupPass = "";
        currentError = "";
        proxyStatus = "Stopped";
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "P2P Share", NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            getSystemService(NotificationManager.class).createNotificationChannel(ch);
        }
    }

    private Notification buildNotif(String title) {
        return new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle(title)
                .setSmallIcon(android.R.drawable.ic_menu_share)
                .setOngoing(true).build();
    }
    private void updateNotif(String title, String msg) {
        Notification n = new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle(title).setContentText(msg)
                .setSmallIcon(android.R.drawable.ic_menu_share).setOngoing(true).build();
        getSystemService(NotificationManager.class).notify(NOTIF_ID, n);
    }

    @Override public void onDestroy() { stopSharing(); }
    @Override public IBinder onBind(Intent intent) { return null; }
}
