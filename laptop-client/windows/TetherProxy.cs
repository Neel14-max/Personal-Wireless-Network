using System;
using System.IO;
using System.Net;
using System.Net.Sockets;
using System.Text;
using System.Threading;

public class TetherProxy
{
    private readonly string phoneHost;
    private readonly int socksPort;

    private TcpListener listener;
    private volatile bool stopping = false;

    public TetherProxy(string host, int port)
    {
        phoneHost = host;
        socksPort = port;
    }

    // Start the local HTTP proxy. Binds the listener synchronously (so a
    // real bind failure, e.g. "port already in use", throws immediately
    // and can be caught normally by the caller) then hands the infinite
    // accept loop to its own background thread and returns right away.
    // IMPORTANT: never call this from inside a manually-created thread
    // wrapping a PowerShell scriptblock. PowerShell's engine can only run
    // one thing at a time on its default runspace; since the accept loop
    // never returns, doing that permanently locks up the whole script.
    // This method itself now owns all the threading, so it's always safe
    // to call directly from the main script.
    public void Start(int localPort)
    {
        stopping = false;

        listener = new TcpListener(IPAddress.Loopback, localPort);
        listener.Start(); // throws immediately here if the port is taken

        Console.WriteLine(
            "Local HTTP proxy is listening on 127.0.0.1:" + localPort
        );

        Thread acceptThread = new Thread(() => AcceptLoop());
        acceptThread.IsBackground = true;
        acceptThread.Start();
    }

    private void AcceptLoop()
    {
        while (!stopping)
        {
            try
            {
                TcpClient client = listener.AcceptTcpClient();

                Thread t = new Thread(() =>
                {
                    HandleClient(client);
                });

                t.IsBackground = true;
                t.Start();
            }
            catch
            {
                if (!stopping)
                {
                    // Continue accepting clients if an individual
                    // accept operation fails.
                }
            }
        }
    }

    public void Stop()
    {
        stopping = true;

        try
        {
            if (listener != null)
                listener.Stop();
        }
        catch
        {
        }
    }

    private void HandleClient(TcpClient client)
    {
        try
        {
            client.ReceiveTimeout = 15000;
            client.SendTimeout = 15000;

            NetworkStream clientStream = client.GetStream();

            byte[] header = ReadHeader(clientStream);

            if (header == null || header.Length == 0)
            {
                client.Close();
                return;
            }

            string request = Encoding.ASCII.GetString(header);

            string[] lines = request.Split(
                new[] { "\r\n" },
                StringSplitOptions.None
            );

            if (lines.Length == 0)
            {
                client.Close();
                return;
            }

            string[] firstLine = lines[0].Split(
                new[] { ' ' },
                StringSplitOptions.RemoveEmptyEntries
            );

            if (firstLine.Length < 2)
            {
                SendError(clientStream, "400 Bad Request");
                client.Close();
                return;
            }

            string method = firstLine[0];

            // HTTPS
            if (method.Equals(
                "CONNECT",
                StringComparison.OrdinalIgnoreCase))
            {
                HandleConnect(
                    client,
                    clientStream,
                    firstLine[1]
                );

                return;
            }

            // Normal HTTP request
            HandleHttp(
                client,
                clientStream,
                request,
                lines,
                firstLine
            );
        }
        catch
        {
            try
            {
                client.Close();
            }
            catch
            {
            }
        }
    }

    private void HandleConnect(
        TcpClient client,
        NetworkStream clientStream,
        string target)
    {
        string host;
        int port;

        if (!ParseHostPort(target, 443, out host, out port))
        {
            SendError(clientStream, "400 Bad Request");
            client.Close();
            return;
        }

        TcpClient remote = ConnectThroughSocks(host, port);

        if (remote == null)
        {
            SendError(clientStream, "502 Bad Gateway");
            client.Close();
            return;
        }

        NetworkStream remoteStream = remote.GetStream();

        byte[] established = Encoding.ASCII.GetBytes(
            "HTTP/1.1 200 Connection Established\r\n" +
            "Proxy-Agent: TetherDirect\r\n" +
            "\r\n"
        );

        clientStream.Write(
            established,
            0,
            established.Length
        );

        Tunnel(
            clientStream,
            remoteStream
        );

        try
        {
            remote.Close();
        }
        catch
        {
        }

        try
        {
            client.Close();
        }
        catch
        {
        }
    }

    private void HandleHttp(
        TcpClient client,
        NetworkStream clientStream,
        string request,
        string[] lines,
        string[] firstLine)
    {
        string url = firstLine[1];

        Uri uri;

        if (!Uri.TryCreate(
            url,
            UriKind.Absolute,
            out uri))
        {
            string hostHeader = GetHeader(lines, "Host");

            if (String.IsNullOrEmpty(hostHeader))
            {
                SendError(clientStream, "400 Bad Request");
                client.Close();
                return;
            }

            string host;
            int port;

            if (!ParseHostPort(
                hostHeader,
                80,
                out host,
                out port))
            {
                SendError(clientStream, "400 Bad Request");
                client.Close();
                return;
            }

            uri = new Uri(
                "http://" +
                hostHeader +
                "/" +
                url.TrimStart('/')
            );
        }

        string targetHost = uri.Host;

        int targetPort =
            uri.Port > 0
                ? uri.Port
                : 80;

        TcpClient remote = ConnectThroughSocks(
            targetHost,
            targetPort
        );

        if (remote == null)
        {
            SendError(clientStream, "502 Bad Gateway");
            client.Close();
            return;
        }

        NetworkStream remoteStream =
            remote.GetStream();

        string path =
            String.IsNullOrEmpty(uri.PathAndQuery)
                ? "/"
                : uri.PathAndQuery;

        StringBuilder output =
            new StringBuilder();

        output.Append(firstLine[0]);
        output.Append(" ");
        output.Append(path);
        output.Append(" ");
        output.Append(
            firstLine.Length >= 3
                ? firstLine[2]
                : "HTTP/1.1"
        );
        output.Append("\r\n");

        bool hasHost = false;

        for (int i = 1; i < lines.Length; i++)
        {
            string line = lines[i];

            if (line.Length == 0)
                break;

            if (line.StartsWith(
                "Proxy-Connection:",
                StringComparison.OrdinalIgnoreCase))
            {
                continue;
            }

            if (line.StartsWith(
                "Connection:",
                StringComparison.OrdinalIgnoreCase))
            {
                continue;
            }

            if (line.StartsWith(
                "Host:",
                StringComparison.OrdinalIgnoreCase))
            {
                hasHost = true;

                output.Append(
                    "Host: " + uri.Host
                );

                if (!(
                    (uri.Scheme == "http" && targetPort == 80) ||
                    (uri.Scheme == "https" && targetPort == 443)
                ))
                {
                    output.Append(
                        ":" + targetPort
                    );
                }

                output.Append("\r\n");

                continue;
            }

            output.Append(line);
            output.Append("\r\n");
        }

        if (!hasHost)
        {
            output.Append("Host: ");
            output.Append(uri.Host);

            if (!(
                (uri.Scheme == "http" && targetPort == 80) ||
                (uri.Scheme == "https" && targetPort == 443)
            ))
            {
                output.Append(":");
                output.Append(targetPort);
            }

            output.Append("\r\n");
        }

        output.Append(
            "Connection: close\r\n"
        );

        output.Append("\r\n");

        byte[] data = Encoding.ASCII.GetBytes(
            output.ToString()
        );

        remoteStream.Write(
            data,
            0,
            data.Length
        );

        Copy(
            remoteStream,
            clientStream
        );

        try
        {
            remote.Close();
        }
        catch
        {
        }

        try
        {
            client.Close();
        }
        catch
        {
        }
    }

    private TcpClient ConnectThroughSocks(
        string host,
        int port)
    {
        try
        {
            TcpClient socks = new TcpClient();

            socks.Connect(
                phoneHost,
                socksPort
            );

            NetworkStream s =
                socks.GetStream();

            // SOCKS5 greeting:
            // Version 5
            // 1 authentication method
            // No authentication
            byte[] greeting =
            {
                0x05,
                0x01,
                0x00
            };

            s.Write(
                greeting,
                0,
                greeting.Length
            );

            byte[] greetingReply =
                ReadExact(s, 2);

            if (greetingReply == null ||
                greetingReply[0] != 0x05 ||
                greetingReply[1] != 0x00)
            {
                socks.Close();
                return null;
            }

            byte[] hostBytes =
                Encoding.ASCII.GetBytes(host);

            if (hostBytes.Length > 255)
            {
                socks.Close();
                return null;
            }

            MemoryStream request =
                new MemoryStream();

            request.WriteByte(0x05); // version
            request.WriteByte(0x01); // CONNECT
            request.WriteByte(0x00); // reserved
            request.WriteByte(0x03); // domain name
            request.WriteByte(
                (byte)hostBytes.Length
            );

            request.Write(
                hostBytes,
                0,
                hostBytes.Length
            );

            request.WriteByte(
                (byte)((port >> 8) & 0xff)
            );

            request.WriteByte(
                (byte)(port & 0xff)
            );

            byte[] connectRequest =
                request.ToArray();

            s.Write(
                connectRequest,
                0,
                connectRequest.Length
            );

            byte[] reply =
                ReadExact(s, 4);

            if (reply == null ||
                reply.Length < 4 ||
                reply[0] != 0x05 ||
                reply[1] != 0x00)
            {
                socks.Close();
                return null;
            }

            int addressLength = 0;

            if (reply[3] == 0x01)
            {
                // IPv4
                addressLength = 4;
            }
            else if (reply[3] == 0x03)
            {
                // Domain
                byte[] len =
                    ReadExact(s, 1);

                if (len == null)
                {
                    socks.Close();
                    return null;
                }

                addressLength = len[0];
            }
            else if (reply[3] == 0x04)
            {
                // IPv6
                addressLength = 16;
            }
            else
            {
                socks.Close();
                return null;
            }

            byte[] address =
                ReadExact(
                    s,
                    addressLength
                );

            if (address == null)
            {
                socks.Close();
                return null;
            }

            // SOCKS5 reply also contains destination port.
            byte[] destinationPort =
                ReadExact(s, 2);

            if (destinationPort == null)
            {
                socks.Close();
                return null;
            }

            return socks;
        }
        catch
        {
            return null;
        }
    }

    private void Tunnel(
        NetworkStream a,
        NetworkStream b)
    {
        Thread t1 = new Thread(() =>
        {
            Copy(a, b);
        });

        Thread t2 = new Thread(() =>
        {
            Copy(b, a);
        });

        t1.IsBackground = true;
        t2.IsBackground = true;

        t1.Start();
        t2.Start();

        t1.Join();
        t2.Join();
    }

    private void Copy(
        Stream source,
        Stream destination)
    {
        try
        {
            byte[] buffer =
                new byte[32768];

            while (true)
            {
                int n =
                    source.Read(
                        buffer,
                        0,
                        buffer.Length
                    );

                if (n <= 0)
                    break;

                destination.Write(
                    buffer,
                    0,
                    n
                );

                destination.Flush();
            }
        }
        catch
        {
        }
    }

    private byte[] ReadHeader(
        Stream stream)
    {
        MemoryStream ms =
            new MemoryStream();

        byte[] one =
            new byte[1];

        int state = 0;

        while (ms.Length < 65536)
        {
            int n =
                stream.Read(
                    one,
                    0,
                    1
                );

            if (n <= 0)
                return null;

            ms.WriteByte(one[0]);

            if (state == 0 &&
                one[0] == 0x0d)
            {
                state = 1;
            }
            else if (state == 1 &&
                     one[0] == 0x0a)
            {
                state = 2;
            }
            else if (state == 2 &&
                     one[0] == 0x0d)
            {
                state = 3;
            }
            else if (state == 3 &&
                     one[0] == 0x0a)
            {
                break;
            }
            else
            {
                state = 0;
            }
        }

        return ms.ToArray();
    }

    private byte[] ReadExact(
        Stream stream,
        int count)
    {
        byte[] buffer =
            new byte[count];

        int offset = 0;

        while (offset < count)
        {
            int n =
                stream.Read(
                    buffer,
                    offset,
                    count - offset
                );

            if (n <= 0)
                return null;

            offset += n;
        }

        return buffer;
    }

    private string GetHeader(
        string[] lines,
        string name)
    {
        foreach (string line in lines)
        {
            if (line.StartsWith(
                name + ":",
                StringComparison.OrdinalIgnoreCase))
            {
                return line.Substring(
                    name.Length + 1
                ).Trim();
            }
        }

        return null;
    }

    private bool ParseHostPort(
        string value,
        int defaultPort,
        out string host,
        out int port)
    {
        host = null;
        port = defaultPort;

        if (String.IsNullOrWhiteSpace(value))
            return false;

        value = value.Trim();

        int colon =
            value.LastIndexOf(':');

        if (colon > 0 &&
            colon < value.Length - 1)
        {
            int parsed;

            if (Int32.TryParse(
                value.Substring(colon + 1),
                out parsed))
            {
                host =
                    value.Substring(
                        0,
                        colon
                    );

                port = parsed;

                return true;
            }
        }

        host = value;

        return true;
    }

    private void SendError(
        NetworkStream stream,
        string status)
    {
        try
        {
            string response =
                "HTTP/1.1 " + status + "\r\n" +
                "Connection: close\r\n" +
                "Content-Type: text/plain\r\n" +
                "Content-Length: 0\r\n" +
                "\r\n";

            byte[] data =
                Encoding.ASCII.GetBytes(
                    response
                );

            stream.Write(
                data,
                0,
                data.Length
            );
        }
        catch
        {
        }
    }
}