// TetherDirect - Windows desktop client
//
// A single-file WinForms app (C# 5 compatible so it builds with the copy of
// csc.exe that ships inside every Windows install - no Visual Studio, no .NET
// SDK download required). It gives the PC full, system-wide internet through
// an Android phone running the TetherDirect app, with no root and no USB.
//
// How it works:
//   Phone (TetherDirect app)  ->  Wi-Fi Direct  ->  this PC
//   The phone runs a SOCKS5 proxy on 192.168.49.1:8888 that sends traffic out
//   over mobile data. This app runs the bundled tun2socks, which creates a
//   virtual network adapter and routes ALL of Windows' traffic into that SOCKS5
//   proxy. Every app on the PC then uses the phone's internet.
//
// Build:  build.cmd   (or see the csc line at the bottom of build.cmd)

using System;
using System.Diagnostics;
using System.Drawing;
using System.IO;
using System.Net;
using System.Net.Sockets;
using System.Security.Principal;
using System.Text;
using System.Threading;
using System.Windows.Forms;

namespace TetherDirect
{
    static class Program
    {
        [STAThread]
        static void Main()
        {
            Application.EnableVisualStyles();
            Application.SetCompatibleTextRenderingDefault(false);
            Application.Run(new MainForm());
        }
    }

    public class MainForm : Form
    {
        // ---- Connection settings (match the Android app) ----
        private const string PhoneGateway = "192.168.49.1";  // Wi-Fi Direct group owner (the phone)
        private const int    SocksPort    = 8888;             // SOCKS5 port the phone app listens on
        private const string TunName      = "TetherDirect";   // name of the virtual adapter tun2socks creates
        private const string TunAddress   = "192.168.250.1";
        private const string TunMask      = "255.255.255.0";
        // The phone proxy carries TCP only, so DNS (UDP) is forwarded over TCP to
        // these public resolvers by the built-in DNS helper (see DnsProxy).
        private static readonly string[] Upstreams = new string[] { "1.1.1.1", "8.8.8.8" };

        // ---- Palette (matches the phone app) ----
        private static readonly Color ColBg      = FromHex("#0D1117");
        private static readonly Color ColCard    = FromHex("#161B22");
        private static readonly Color ColAccent  = FromHex("#58A6FF");
        private static readonly Color ColText    = FromHex("#C9D1D9");
        private static readonly Color ColMuted   = FromHex("#8B949E");
        private static readonly Color ColGood    = FromHex("#3FB950");
        private static readonly Color ColBad     = FromHex("#F85149");

        // ---- State ----
        private enum State { Idle, Connecting, Connected, Disconnecting }
        private State state = State.Idle;
        private Process tunProcess;
        private Thread worker;
        private DnsProxy dnsProxy;

        // ---- Controls ----
        private Label lblStatusDot;
        private Label lblStatus;
        private Button btnMain;
        private TextBox txtLog;

        public MainForm()
        {
            Text = "TetherDirect";
            BackColor = ColBg;
            ForeColor = ColText;
            Font = new Font("Segoe UI", 9.5f);
            FormBorderStyle = FormBorderStyle.FixedSingle;
            MaximizeBox = false;
            StartPosition = FormStartPosition.CenterScreen;
            ClientSize = new Size(540, 520);

            BuildUi();
            AppendLog("TetherDirect ready.");
            AppendLog("1) On the phone, open TetherDirect and turn sharing ON.");
            AppendLog("2) On this PC, connect to the phone's \"DIRECT-...\" Wi-Fi.");
            AppendLog("3) Click Connect below.");

            if (!IsAdministrator())
            {
                AppendLog("");
                AppendLog("WARNING: not running as Administrator. Routing may fail.");
            }
            FormClosing += OnClosing;
        }

        // ---------------------------------------------------------------- UI

        private void BuildUi()
        {
            Label title = new Label();
            title.Text = "TetherDirect";
            title.ForeColor = ColAccent;
            title.Font = new Font("Segoe UI", 22f, FontStyle.Bold);
            title.AutoSize = true;
            title.Location = new Point(28, 22);
            Controls.Add(title);

            Label subtitle = new Label();
            subtitle.Text = "Phone internet on your PC  -  no root, no USB, no hotspot";
            subtitle.ForeColor = ColMuted;
            subtitle.AutoSize = true;
            subtitle.Location = new Point(30, 66);
            Controls.Add(subtitle);

            // Status row
            lblStatusDot = new Label();
            lblStatusDot.Text = "●"; // filled circle
            lblStatusDot.ForeColor = ColMuted;
            lblStatusDot.Font = new Font("Segoe UI", 12f);
            lblStatusDot.AutoSize = true;
            lblStatusDot.Location = new Point(30, 104);
            Controls.Add(lblStatusDot);

            lblStatus = new Label();
            lblStatus.Text = "Not connected";
            lblStatus.ForeColor = ColText;
            lblStatus.Font = new Font("Segoe UI", 11f);
            lblStatus.AutoSize = true;
            lblStatus.Location = new Point(52, 104);
            Controls.Add(lblStatus);

            // Main button
            btnMain = new Button();
            btnMain.Text = "Connect";
            btnMain.FlatStyle = FlatStyle.Flat;
            btnMain.FlatAppearance.BorderSize = 0;
            btnMain.BackColor = ColAccent;
            btnMain.ForeColor = Color.White;
            btnMain.Font = new Font("Segoe UI", 12f, FontStyle.Bold);
            btnMain.Size = new Size(484, 52);
            btnMain.Location = new Point(28, 140);
            btnMain.Cursor = Cursors.Hand;
            btnMain.Click += OnMainClick;
            Controls.Add(btnMain);

            Label logTitle = new Label();
            logTitle.Text = "Activity";
            logTitle.ForeColor = ColAccent;
            logTitle.Font = new Font("Segoe UI", 9f, FontStyle.Bold);
            logTitle.AutoSize = true;
            logTitle.Location = new Point(30, 210);
            Controls.Add(logTitle);

            txtLog = new TextBox();
            txtLog.Multiline = true;
            txtLog.ReadOnly = true;
            txtLog.ScrollBars = ScrollBars.Vertical;
            txtLog.BackColor = ColCard;
            txtLog.ForeColor = ColMuted;
            txtLog.BorderStyle = BorderStyle.None;
            txtLog.Font = new Font("Consolas", 9f);
            txtLog.Location = new Point(28, 232);
            txtLog.Size = new Size(484, 262);
            txtLog.TabStop = false;
            Controls.Add(txtLog);
        }

        // ------------------------------------------------------------ actions

        private void OnMainClick(object sender, EventArgs e)
        {
            if (state == State.Idle)
                StartConnect();
            else if (state == State.Connected)
                StartDisconnect();
        }

        private void StartConnect()
        {
            SetState(State.Connecting);
            worker = new Thread(ConnectWork);
            worker.IsBackground = true;
            worker.Start();
        }

        private void ConnectWork()
        {
            try
            {
                string dir = Application.StartupPath;
                string tun2socks = Path.Combine(dir, "tun2socks-windows-amd64.exe");
                string wintun = Path.Combine(dir, "wintun.dll");

                if (!File.Exists(tun2socks) || !File.Exists(wintun))
                {
                    Fail("tun2socks-windows-amd64.exe and wintun.dll must sit next to TetherDirect.exe.");
                    return;
                }

                if (!IsAdministrator())
                {
                    Fail("Administrator rights are required. Right-click TetherDirect.exe > Run as administrator.");
                    return;
                }

                // 1) Wait until the phone's SOCKS5 proxy is reachable.
                AppendLog("Looking for the phone at " + PhoneGateway + ":" + SocksPort + " ...");
                if (!WaitForSocks(20))
                {
                    Fail("Could not reach the phone.\r\n" +
                         "- Make sure the phone's TetherDirect app shows ACTIVE / P2P READY.\r\n" +
                         "- Make sure this PC is connected to the phone's \"DIRECT-...\" Wi-Fi.");
                    return;
                }
                AppendLog("Phone reached. Starting tunnel...");

                // 2) Launch tun2socks (creates the virtual adapter + does the routing).
                tunProcess = new Process();
                tunProcess.StartInfo.FileName = tun2socks;
                tunProcess.StartInfo.Arguments =
                    "--device tun://" + TunName +
                    " --proxy socks5://" + PhoneGateway + ":" + SocksPort +
                    " --loglevel error";
                tunProcess.StartInfo.WorkingDirectory = dir; // so wintun.dll is found
                tunProcess.StartInfo.UseShellExecute = false;
                tunProcess.StartInfo.CreateNoWindow = true;
                tunProcess.StartInfo.RedirectStandardOutput = true;
                tunProcess.StartInfo.RedirectStandardError = true;
                tunProcess.OutputDataReceived += OnTunOutput;
                tunProcess.EnableRaisingEvents = true;
                tunProcess.Exited += OnTunExited;
                tunProcess.ErrorDataReceived += OnTunOutput;
                tunProcess.Start();
                tunProcess.BeginOutputReadLine();
                tunProcess.BeginErrorReadLine();

                // 3) Wait for the TUN adapter to come up, then configure it.
                AppendLog("Configuring the " + TunName + " adapter...");
                if (!WaitForAdapter(10))
                {
                    Fail("The virtual network adapter did not appear. Try again.");
                    KillTun();
                    return;
                }

                RunNetsh("interface ipv4 set address name=\"" + TunName + "\" source=static addr=" + TunAddress + " mask=" + TunMask);
                RunNetsh("interface ipv4 add route 0.0.0.0/0 \"" + TunName + "\" " + TunAddress + " metric=1");

                // 3b) The phone's SOCKS5 proxy carries TCP only, not UDP. Ordinary DNS
                // is UDP, so it fails ("UDP ASSOCIATE: command not supported") and
                // nothing resolves. Run a tiny local DNS server on the TUN adapter that
                // forwards every lookup over TCP (which the proxy DOES support), then
                // point Windows' DNS at it.
                AppendLog("Starting DNS-over-TCP helper...");
                StartDnsProxy();
                RunNetsh("interface ipv4 set dnsservers name=\"" + TunName + "\" static address=" + TunAddress + " register=none validate=no");
                RunFlushDns();

                // 4) Confirm we actually have internet through the tunnel.
                AppendLog("Testing internet through the phone...");
                if (TcpProbe(Upstreams[0], 53, 4000))
                    AppendLog("TCP path to the internet works.");
                else
                    AppendLog("Tunnel is up, but no TCP reply yet. Check the phone has mobile data on.");

                if (DnsResolves("example.com"))
                    AppendLog("DNS is resolving through the phone. Internet is ready.");
                else
                    AppendLog("DNS not resolving yet - give it a few seconds, then reload the page.");

                SetState(State.Connected);
                AppendLog("");
                AppendLog("CONNECTED - all PC traffic now goes through the phone.");
                AppendLog("(Tip: some apps' QUIC/UDP won't tunnel and fall back to TCP - that's normal.)");
            }
            catch (Exception ex)
            {
                Fail("Unexpected error: " + ex.Message);
                StopDnsProxy();
                KillTun();
            }
        }

        private void StartDisconnect()
        {
            SetState(State.Disconnecting);
            worker = new Thread(DisconnectWork);
            worker.IsBackground = true;
            worker.Start();
        }

        private void DisconnectWork()
        {
            try
            {
                AppendLog("Disconnecting...");
                // Remove our default route first so Windows falls back to normal Wi-Fi/Ethernet.
                RunNetsh("interface ipv4 delete route 0.0.0.0/0 \"" + TunName + "\" " + TunAddress);
                StopDnsProxy();
                KillTun();
                RunFlushDns();
                AppendLog("Disconnected. Normal internet restored.");
            }
            catch (Exception ex)
            {
                AppendLog("Cleanup warning: " + ex.Message);
            }
            SetState(State.Idle);
        }

        // ------------------------------------------------------------ helpers

        private void OnTunOutput(object sender, DataReceivedEventArgs e)
        {
            if (!string.IsNullOrEmpty(e.Data))
                AppendLog("tun2socks: " + e.Data);
        }

        private void OnTunExited(object sender, EventArgs e)
        {
            // If tun2socks dies while we thought we were connected, reflect that.
            if (state == State.Connected || state == State.Connecting)
            {
                AppendLog("tun2socks stopped.");
                StopDnsProxy();
                RunNetshQuiet("interface ipv4 delete route 0.0.0.0/0 \"" + TunName + "\" " + TunAddress);
                SetState(State.Idle);
            }
        }

        private void KillTun()
        {
            try
            {
                if (tunProcess != null && !tunProcess.HasExited)
                    tunProcess.Kill();
            }
            catch { }
            tunProcess = null;
        }

        // ---- DNS-over-TCP helper wiring ----

        private void StartDnsProxy()
        {
            StopDnsProxy();
            Exception last = null;
            // The TUN adapter's IP was just assigned; retry the bind while it settles.
            for (int i = 0; i < 15; i++)
            {
                try
                {
                    DnsProxy p = new DnsProxy(TunAddress, 53, Upstreams, AppendLog);
                    p.Start();
                    dnsProxy = p;
                    AppendLog("DNS helper listening on " + TunAddress + ":53 (forwarding over TCP).");
                    return;
                }
                catch (Exception ex)
                {
                    last = ex;
                    Thread.Sleep(400);
                }
            }
            AppendLog("DNS helper could not start: " + (last != null ? last.Message : "unknown") +
                      ". Names may not resolve.");
            dnsProxy = null;
        }

        private void StopDnsProxy()
        {
            try { if (dnsProxy != null) dnsProxy.Stop(); }
            catch { }
            dnsProxy = null;
        }

        private bool DnsResolves(string name)
        {
            // Ask our own local helper directly, to confirm the TCP path resolves.
            for (int i = 0; i < 6; i++)
            {
                if (dnsProxy != null && dnsProxy.TryResolve(name, 4000))
                    return true;
                Thread.Sleep(1000);
            }
            return false;
        }

        private void RunFlushDns()
        {
            try
            {
                Process p = new Process();
                p.StartInfo.FileName = "ipconfig";
                p.StartInfo.Arguments = "/flushdns";
                p.StartInfo.UseShellExecute = false;
                p.StartInfo.CreateNoWindow = true;
                p.StartInfo.RedirectStandardOutput = true;
                p.StartInfo.RedirectStandardError = true;
                p.Start();
                p.StandardOutput.ReadToEnd();
                p.WaitForExit(5000);
            }
            catch { }
        }

        private bool WaitForSocks(int seconds)
        {
            for (int i = 0; i < seconds; i++)
            {
                if (TcpProbe(PhoneGateway, SocksPort, 1500))
                    return true;
                Thread.Sleep(1000);
            }
            return false;
        }

        private bool WaitForAdapter(int seconds)
        {
            for (int i = 0; i < seconds; i++)
            {
                string outp = RunNetshCapture("interface ipv4 show interfaces");
                if (outp != null && outp.IndexOf(TunName, StringComparison.OrdinalIgnoreCase) >= 0)
                    return true;
                Thread.Sleep(1000);
            }
            return false;
        }

        private static bool TcpProbe(string host, int port, int timeoutMs)
        {
            try
            {
                using (TcpClient c = new TcpClient())
                {
                    IAsyncResult ar = c.BeginConnect(host, port, null, null);
                    if (!ar.AsyncWaitHandle.WaitOne(timeoutMs))
                        return false;
                    c.EndConnect(ar);
                    return c.Connected;
                }
            }
            catch { return false; }
        }

        private void RunNetsh(string args)
        {
            string outp = RunNetshCapture(args);
            if (!string.IsNullOrEmpty(outp))
            {
                string trimmed = outp.Trim();
                if (trimmed.Length > 0)
                    AppendLog("netsh: " + trimmed);
            }
        }

        private void RunNetshQuiet(string args)
        {
            try { RunNetshCapture(args); } catch { }
        }

        private static string RunNetshCapture(string args)
        {
            try
            {
                Process p = new Process();
                p.StartInfo.FileName = "netsh";
                p.StartInfo.Arguments = args;
                p.StartInfo.UseShellExecute = false;
                p.StartInfo.CreateNoWindow = true;
                p.StartInfo.RedirectStandardOutput = true;
                p.StartInfo.RedirectStandardError = true;
                p.Start();
                string outp = p.StandardOutput.ReadToEnd();
                string err = p.StandardError.ReadToEnd();
                p.WaitForExit(8000);
                return outp + err;
            }
            catch (Exception ex)
            {
                return "error: " + ex.Message;
            }
        }

        private static bool IsAdministrator()
        {
            try
            {
                WindowsIdentity id = WindowsIdentity.GetCurrent();
                WindowsPrincipal pr = new WindowsPrincipal(id);
                return pr.IsInRole(WindowsBuiltInRole.Administrator);
            }
            catch { return false; }
        }

        // ------------------------------------------------------------ ui state

        private void Fail(string message)
        {
            AppendLog("");
            AppendLog("ERROR: " + message);
            SetState(State.Idle);
        }

        private void SetState(State s)
        {
            if (InvokeRequired) { BeginInvoke((MethodInvoker)delegate { SetState(s); }); return; }
            state = s;
            switch (s)
            {
                case State.Idle:
                    lblStatus.Text = "Not connected";
                    lblStatusDot.ForeColor = ColMuted;
                    btnMain.Text = "Connect";
                    btnMain.BackColor = ColAccent;
                    btnMain.Enabled = true;
                    break;
                case State.Connecting:
                    lblStatus.Text = "Connecting...";
                    lblStatusDot.ForeColor = ColAccent;
                    btnMain.Text = "Connecting...";
                    btnMain.Enabled = false;
                    break;
                case State.Connected:
                    lblStatus.Text = "Connected - internet via phone";
                    lblStatusDot.ForeColor = ColGood;
                    btnMain.Text = "Disconnect";
                    btnMain.BackColor = ColBad;
                    btnMain.Enabled = true;
                    break;
                case State.Disconnecting:
                    lblStatus.Text = "Disconnecting...";
                    lblStatusDot.ForeColor = ColMuted;
                    btnMain.Text = "Disconnecting...";
                    btnMain.Enabled = false;
                    break;
            }
        }

        private void AppendLog(string line)
        {
            if (InvokeRequired) { BeginInvoke((MethodInvoker)delegate { AppendLog(line); }); return; }
            txtLog.AppendText(line + "\r\n");
        }

        private void OnClosing(object sender, FormClosingEventArgs e)
        {
            // Never leave the machine with a broken default route.
            if (state == State.Connected || state == State.Connecting || tunProcess != null)
            {
                RunNetshQuiet("interface ipv4 delete route 0.0.0.0/0 \"" + TunName + "\" " + TunAddress);
                StopDnsProxy();
                KillTun();
            }
        }

        // ------------------------------------------------------------ util

        private static Color FromHex(string hex)
        {
            hex = hex.Replace("#", "");
            int r = Convert.ToInt32(hex.Substring(0, 2), 16);
            int g = Convert.ToInt32(hex.Substring(2, 2), 16);
            int b = Convert.ToInt32(hex.Substring(4, 2), 16);
            return Color.FromArgb(r, g, b);
        }
    }

    // ------------------------------------------------------------------------
    // DnsProxy: a minimal local DNS server that forwards every query to public
    // resolvers over TCP. The phone's SOCKS5 proxy only tunnels TCP, so plain
    // UDP DNS fails; forwarding DNS over TCP (which DNS natively supports, RFC
    // 7766) routes it through the tunnel like any other TCP connection.
    //
    // It binds to the TUN adapter's own IP so replies always come from the same
    // address Windows queried, and listens on both UDP and TCP port 53.
    // ------------------------------------------------------------------------
    class DnsProxy
    {
        private readonly IPAddress bindAddr;
        private readonly int port;
        private readonly string[] upstreams;
        private readonly Action<string> log;

        private Socket udp;
        private TcpListener tcp;
        private volatile bool running;

        public DnsProxy(string bindAddress, int port, string[] upstreams, Action<string> log)
        {
            this.bindAddr = IPAddress.Parse(bindAddress);
            this.port = port;
            this.upstreams = upstreams;
            this.log = log;
        }

        public void Start()
        {
            try
            {
                udp = new Socket(AddressFamily.InterNetwork, SocketType.Dgram, ProtocolType.Udp);
                udp.SetSocketOption(SocketOptionLevel.Socket, SocketOptionName.ReuseAddress, true);
                udp.Bind(new IPEndPoint(bindAddr, port));

                tcp = new TcpListener(bindAddr, port);
                tcp.Start();
            }
            catch
            {
                try { if (udp != null) udp.Close(); } catch { }
                try { if (tcp != null) tcp.Stop(); } catch { }
                udp = null;
                tcp = null;
                throw;
            }

            running = true;
            Thread tu = new Thread(UdpLoop);
            tu.IsBackground = true;
            tu.Start();
            Thread tt = new Thread(TcpLoop);
            tt.IsBackground = true;
            tt.Start();
        }

        public void Stop()
        {
            running = false;
            try { if (udp != null) udp.Close(); } catch { }
            try { if (tcp != null) tcp.Stop(); } catch { }
            udp = null;
            tcp = null;
        }

        private void UdpLoop()
        {
            byte[] buf = new byte[2048];
            while (running)
            {
                EndPoint client = new IPEndPoint(IPAddress.Any, 0);
                int n;
                try { n = udp.ReceiveFrom(buf, ref client); }
                catch { if (!running) break; Thread.Sleep(50); continue; }
                if (n <= 0) continue;

                byte[] query = new byte[n];
                Array.Copy(buf, query, n);
                EndPoint c = client;
                ThreadPool.QueueUserWorkItem(delegate
                {
                    byte[] resp = Resolve(query);
                    if (resp != null)
                    {
                        try { udp.SendTo(resp, c); } catch { }
                    }
                });
            }
        }

        private void TcpLoop()
        {
            while (running)
            {
                TcpClient client;
                try { client = tcp.AcceptTcpClient(); }
                catch { if (!running) break; Thread.Sleep(50); continue; }
                TcpClient cc = client;
                ThreadPool.QueueUserWorkItem(delegate { HandleTcp(cc); });
            }
        }

        private void HandleTcp(TcpClient client)
        {
            try
            {
                using (client)
                {
                    NetworkStream ns = client.GetStream();
                    ns.ReadTimeout = 8000;
                    ns.WriteTimeout = 8000;
                    byte[] lenb = ReadN(ns, 2);
                    if (lenb == null) return;
                    int len = (lenb[0] << 8) | lenb[1];
                    if (len <= 0 || len > 65535) return;
                    byte[] query = ReadN(ns, len);
                    if (query == null) return;
                    byte[] resp = Resolve(query);
                    if (resp == null) return;
                    byte[] framed = Frame(resp);
                    ns.Write(framed, 0, framed.Length);
                    ns.Flush();
                }
            }
            catch { }
        }

        // Forward one raw DNS message to an upstream resolver over TCP and return
        // the raw response. Tries each upstream in turn.
        private byte[] Resolve(byte[] query)
        {
            for (int i = 0; i < upstreams.Length; i++)
            {
                try
                {
                    using (TcpClient up = new TcpClient())
                    {
                        IAsyncResult ar = up.BeginConnect(upstreams[i], 53, null, null);
                        if (!ar.AsyncWaitHandle.WaitOne(5000)) continue;
                        up.EndConnect(ar);
                        up.NoDelay = true;
                        NetworkStream ns = up.GetStream();
                        ns.ReadTimeout = 6000;
                        ns.WriteTimeout = 6000;

                        byte[] framed = Frame(query);
                        ns.Write(framed, 0, framed.Length);
                        ns.Flush();

                        byte[] lenb = ReadN(ns, 2);
                        if (lenb == null) continue;
                        int len = (lenb[0] << 8) | lenb[1];
                        if (len <= 0 || len > 65535) continue;
                        byte[] resp = ReadN(ns, len);
                        if (resp != null) return resp;
                    }
                }
                catch { }
            }
            return null;
        }

        // Used by the app to confirm the whole DNS-over-TCP path works.
        public bool TryResolve(string name, int timeoutMs)
        {
            try
            {
                byte[] query = BuildQuery(name);
                using (Socket s = new Socket(AddressFamily.InterNetwork, SocketType.Dgram, ProtocolType.Udp))
                {
                    s.ReceiveTimeout = timeoutMs;
                    s.SendTo(query, new IPEndPoint(bindAddr, port));
                    byte[] buf = new byte[2048];
                    EndPoint ep = new IPEndPoint(IPAddress.Any, 0);
                    int n = s.ReceiveFrom(buf, ref ep);
                    if (n < 8) return false;
                    int ancount = (buf[6] << 8) | buf[7];
                    return ancount > 0;
                }
            }
            catch { return false; }
        }

        private static byte[] BuildQuery(string name)
        {
            // Minimal DNS query: header + one QNAME + QTYPE A + QCLASS IN.
            byte[] header = new byte[] { 0x12, 0x34, 0x01, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00 };
            byte[] tail = new byte[] { 0x00, 0x00, 0x01, 0x00, 0x01 }; // root, QTYPE=A, QCLASS=IN
            string[] labels = name.Split('.');
            int qnameLen = 0;
            for (int i = 0; i < labels.Length; i++)
                qnameLen += 1 + Encoding.ASCII.GetByteCount(labels[i]);
            byte[] msg = new byte[header.Length + qnameLen + tail.Length];
            int off = 0;
            Array.Copy(header, 0, msg, off, header.Length);
            off += header.Length;
            for (int i = 0; i < labels.Length; i++)
            {
                byte[] lb = Encoding.ASCII.GetBytes(labels[i]);
                msg[off++] = (byte)lb.Length;
                Array.Copy(lb, 0, msg, off, lb.Length);
                off += lb.Length;
            }
            Array.Copy(tail, 0, msg, off, tail.Length);
            return msg;
        }

        private static byte[] Frame(byte[] payload)
        {
            byte[] f = new byte[payload.Length + 2];
            f[0] = (byte)(payload.Length >> 8);
            f[1] = (byte)(payload.Length & 0xff);
            Array.Copy(payload, 0, f, 2, payload.Length);
            return f;
        }

        private static byte[] ReadN(NetworkStream ns, int n)
        {
            byte[] b = new byte[n];
            int off = 0;
            while (off < n)
            {
                int r;
                try { r = ns.Read(b, off, n - off); }
                catch { return null; }
                if (r <= 0) return null;
                off += r;
            }
            return b;
        }
    }
}
