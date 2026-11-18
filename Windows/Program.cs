using System.Buffers.Binary;
using System.Diagnostics;
using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;
using System.Runtime.InteropServices;
using System.Text;
using Microsoft.Win32;

namespace UtenaMic;

internal static class Program
{
    [STAThread]
    private static int Main(string[] args)
    {
        if (args.Length == 1 && args[0] == "--name-vac-capture")
            return VacCaptureName.Apply();
        if (args.Length == 1 && args[0] == "--vac-installed")
            return VacCaptureName.Exists() ? 0 : 2;
        if (args.Length == 2 && args[0] == "--save-audio-defaults")
            return AudioDefaults.Save(args[1]);
        if (args.Length == 2 && args[0] == "--restore-audio-defaults")
            return AudioDefaults.Restore(args[1]);
        Application.SetHighDpiMode(HighDpiMode.SystemAware);
        Application.Run(new ReceiverForm());
        return 0;
    }
}

internal static class VacCaptureName
{
    internal const string NewName = "Utena Mic";
    private const string OriginalName = "Line 1";
    private const string Description = "{a45c254e-df1c-4efd-8020-67d146a850e0},2";
    private const string Service = "{a8b865dd-2e3d-4094-ad97-e593a70c75d6},8";
    private const string DriverService = "VirtualAudioCable_83ed7f0e";
    private const string RenderPath = @"SOFTWARE\Microsoft\Windows\CurrentVersion\MMDevices\Audio\Render";
    private const string CapturePath = @"SOFTWARE\Microsoft\Windows\CurrentVersion\MMDevices\Audio\Capture";

    private static string? Find(RegistryKey endpoints, string? alternateName = null)
    {
        var matches = new List<string>();
        foreach (string id in endpoints.GetSubKeyNames())
        {
            using var endpoint = endpoints.OpenSubKey(id);
            if (endpoint?.GetValue("DeviceState") is not int state || (state & 1) == 0) continue;
            using var properties = endpoint.OpenSubKey("Properties");
            if (properties?.GetValue(Service) is not string service ||
                !service.Equals(DriverService, StringComparison.OrdinalIgnoreCase)) continue;
            if (properties.GetValue(Description) is string name && (name == OriginalName || name == alternateName))
                matches.Add(id + @"\Properties");
        }
        return matches.Count == 1 ? matches[0] : null;
    }

    internal static int Apply()
    {
        try
        {
            using var capture = Registry.LocalMachine.OpenSubKey(CapturePath);
            if (capture == null) return 2;
            for (int attempt = 0; attempt < 20; attempt++)
            {
                string? relative = Find(capture, NewName);
                if (relative != null)
                {
                    string id = "{0.0.1.00000000}." + relative.Split('\\')[0];
                    if (AudioDefaults.Rename(id, NewName) && Equals(capture.OpenSubKey(relative)?.GetValue(Description), NewName)) return 0;
                    using var property = Registry.LocalMachine.OpenSubKey(CapturePath + "\\" + relative,
                        RegistryKeyPermissionCheck.ReadWriteSubTree,
                        System.Security.AccessControl.RegistryRights.ReadKey | System.Security.AccessControl.RegistryRights.SetValue);
                    if (property == null) return 3;
                    property.SetValue(Description, NewName, RegistryValueKind.String);
                    return Equals(property.GetValue(Description), NewName) ? 0 : 3;
                }
                if (attempt < 19) Thread.Sleep(500);
            }
            return 2;
        }
        catch (Exception e) when (e is UnauthorizedAccessException or System.Security.SecurityException or IOException)
        {
            return 3;
        }
    }

    internal static string? RenderId()
    {
        using var render = Registry.LocalMachine.OpenSubKey(RenderPath);
        var relative = render == null ? null : Find(render);
        return relative == null ? null : "{0.0.0.00000000}." + relative.Split('\\')[0];
    }

    internal static bool Exists()
    {
        using var capture = Registry.LocalMachine.OpenSubKey(CapturePath);
        return capture != null && Find(capture, NewName) != null;
    }
}

internal sealed class CaptionButton : Button
{
    public CaptionButton(string glyph, string name)
    {
        Text = glyph;
        AccessibleName = name;
        Size = new Size(19, 18);
        Margin = new Padding(1, 0, 1, 0);
        Font = new Font("Tahoma", 9, FontStyle.Bold);
        BackColor = Color.FromArgb(216, 208, 211);
        UseVisualStyleBackColor = false;
    }

    protected override void OnPaint(PaintEventArgs e)
    {
        e.Graphics.Clear(BackColor);
        var bounds = ClientRectangle;
        bool down = Capture && MouseButtons == MouseButtons.Left;
        ControlPaint.DrawBorder3D(e.Graphics, bounds, down ? Border3DStyle.Sunken : Border3DStyle.Raised);
        if (down) bounds.Offset(1, 1);
        TextRenderer.DrawText(e.Graphics, Text, Font, bounds, ForeColor,
            TextFormatFlags.HorizontalCenter | TextFormatFlags.VerticalCenter | TextFormatFlags.NoPadding);
        if (Focused && ShowFocusCues) ControlPaint.DrawFocusRectangle(e.Graphics, Rectangle.Inflate(ClientRectangle, -4, -4));
    }
}

internal static class AudioDefaults
{
    private static readonly Guid EnumeratorClass = new("BCDE0395-E52F-467C-8E3D-C4579291692E");
    private static readonly Guid PolicyClass = new("870AF99C-171D-4F9E-AF0D-E63DF40C2BC9");

    [ComImport, Guid("A95664D2-9614-4F35-A746-DE8DB63617E6"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    private interface IDeviceEnumerator
    {
        void EnumAudioEndpoints();
        [PreserveSig] int GetDefaultAudioEndpoint(int flow, int role, out IDevice device);
        [PreserveSig] int GetDevice([MarshalAs(UnmanagedType.LPWStr)] string id, out IDevice device);
    }

    [ComImport, Guid("D666063F-1587-4E43-81F1-B948E807363F"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    private interface IDevice
    {
        [PreserveSig] int Activate(in Guid iid, uint classContext, IntPtr parameters,
            [MarshalAs(UnmanagedType.IUnknown)] out object service);
        void OpenPropertyStore();
        [PreserveSig] int GetId([MarshalAs(UnmanagedType.LPWStr)] out string id);
    }

    [ComImport, Guid("F8679F50-850A-41CF-9C72-430F290290C8"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    private interface IPolicyConfig
    {
        void GetMixFormat(); void GetDeviceFormat(); void ResetDeviceFormat(); void SetDeviceFormat();
        void GetProcessingPeriod(); void SetProcessingPeriod(); void GetShareMode(); void SetShareMode();
        void GetPropertyValue();
        [PreserveSig] int SetPropertyValue([MarshalAs(UnmanagedType.LPWStr)] string id, int effectsStore, in PropertyKey key, ref PropertyValue value);
        [PreserveSig] int SetDefaultEndpoint([MarshalAs(UnmanagedType.LPWStr)] string id, int role);
    }

    private static T Open<T>(Guid clsid) => (T)Activator.CreateInstance(Type.GetTypeFromCLSID(clsid, true)!)!;

    internal static object Activate(string endpointId, Guid interfaceId)
    {
        var enumerator = Open<IDeviceEnumerator>(EnumeratorClass);
        try
        {
            Marshal.ThrowExceptionForHR(enumerator.GetDevice(endpointId, out var device));
            try
            {
                Marshal.ThrowExceptionForHR(device.Activate(in interfaceId, 1, IntPtr.Zero, out var service));
                return service;
            }
            finally { Marshal.ReleaseComObject(device); }
        }
        finally { Marshal.ReleaseComObject(enumerator); }
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct PropertyKey { public Guid Format; public uint Id; }

    [StructLayout(LayoutKind.Explicit, Size = 24)]
    private struct PropertyValue
    {
        [FieldOffset(0)] public ushort Type;
        [FieldOffset(8)] public IntPtr Text;
    }

    internal static bool Rename(string id, string name)
    {
        var key = new PropertyKey { Format = new Guid("a45c254e-df1c-4efd-8020-67d146a850e0"), Id = 2 };
        var value = new PropertyValue { Type = 31, Text = Marshal.StringToCoTaskMemUni(name) };
        try
        {
            var policy = Open<IPolicyConfig>(PolicyClass);
            try { return policy.SetPropertyValue(id, 0, in key, ref value) >= 0; }
            finally { Marshal.ReleaseComObject(policy); }
        }
        catch (COMException) { return false; }
        finally { Marshal.FreeCoTaskMem(value.Text); }
    }

    private static string[] Read(IDeviceEnumerator enumerator)
    {
        var defaults = new string[6];
        for (int index = 0; index < defaults.Length; index++)
        {
            defaults[index] = "";
            if (enumerator.GetDefaultAudioEndpoint(index / 3, index % 3, out var device) < 0) continue;
            try { if (device.GetId(out string id) >= 0) defaults[index] = id; }
            finally { Marshal.ReleaseComObject(device); }
        }
        return defaults;
    }

    internal static int Save(string path)
    {
        try
        {
            var enumerator = Open<IDeviceEnumerator>(EnumeratorClass);
            try { File.WriteAllLines(path, Read(enumerator)); }
            finally { Marshal.ReleaseComObject(enumerator); }
            return 0;
        }
        catch (Exception e) when (e is COMException or IOException or UnauthorizedAccessException) { return 4; }
    }

    internal static int Restore(string path)
    {
        try
        {
            var before = File.ReadAllLines(path);
            if (before.Length != 6) return 4;
            var enumerator = Open<IDeviceEnumerator>(EnumeratorClass);
            try
            {
                var policy = Open<IPolicyConfig>(PolicyClass);
                try
                {
                    var now = Read(enumerator);
                    for (int i = 0; i < now.Length; i++)
                    {
                        if (before[i].Length == 0 || now[i] == before[i]) continue;
                        policy.SetDefaultEndpoint(before[i], i % 3);
                    }
                    now = Read(enumerator);
                    return Enumerable.Range(0, now.Length).Any(i => before[i].Length != 0 && now[i] != before[i]) ? 4 : 0;
                }
                finally { Marshal.ReleaseComObject(policy); }
            }
            finally { Marshal.ReleaseComObject(enumerator); }
        }
        catch (Exception e) when (e is COMException or IOException or UnauthorizedAccessException) { return 4; }
    }
}

internal sealed class ReceiverForm : Form
{
    private const int Port = 48395;
    private const string Discovery = "UTENA_MIC_V2";
    private readonly Button connect = new() { Text = "Conectar", Dock = DockStyle.Top, Height = 70, FlatStyle = FlatStyle.System };
    private readonly Label status = new() { Text = "En espera", Dock = DockStyle.Top, Height = 47 };
    private readonly Label route = new() { Text = "Entrada Android: —", Dock = DockStyle.Top, Height = 30 };
    private readonly Label stats = new() { Dock = DockStyle.Top, Height = 31 };
    private readonly ProgressBar meter = new() { Dock = DockStyle.Top, Height = 18, Maximum = 100, Style = ProgressBarStyle.Continuous };
    private readonly System.Windows.Forms.Timer timer = new() { Interval = 150 };
    private CancellationTokenSource? stop;
    private long received;
    private int level;
    private readonly string? validationPath = Environment.GetEnvironmentVariable("UTENA_MIC_VALIDATION_FILE");

    private void Trace(string message)
    {
        if (!string.IsNullOrWhiteSpace(validationPath))
            File.AppendAllText(validationPath, message + Environment.NewLine);
    }

    public ReceiverForm()
    {
        Text = "Utena Mic";
        BackColor = Color.FromArgb(216, 208, 211);
        Font = new Font("Tahoma", 10);
        MinimumSize = new Size(420, 290);
        ClientSize = new Size(495, 267);
        StartPosition = FormStartPosition.CenterScreen;
        FormBorderStyle = FormBorderStyle.None;
        var title = new Panel { Dock = DockStyle.Top, Height = 23, BackColor = Color.FromArgb(142, 35, 81) };
        var caption = new Label { Text = "Utena Mic · receptor", Dock = DockStyle.Fill, Padding = new Padding(5, 0, 0, 0),
            Font = new Font("Tahoma", 9, FontStyle.Bold), TextAlign = ContentAlignment.MiddleLeft, ForeColor = Color.White };
        var buttons = new FlowLayoutPanel { Dock = DockStyle.Right, Width = 67, WrapContents = false, Padding = new Padding(1, 2, 1, 0) };
        var minimize = new CaptionButton("_", "Minimizar");
        var maximize = new CaptionButton("□", "Maximizar");
        var close = new CaptionButton("×", "Cerrar");
        minimize.Click += (_, _) => WindowState = FormWindowState.Minimized;
        maximize.Click += (_, _) => ToggleMaximized();
        close.Click += (_, _) => Close();
        Resize += (_, _) => maximize.Text = WindowState == FormWindowState.Maximized ? "▣" : "□";
        caption.MouseDown += (_, e) => { if (e.Button == MouseButtons.Left && e.Clicks == 1) { ReleaseCapture(); SendMessage(Handle, 0xA1, (IntPtr)2, IntPtr.Zero); } };
        caption.DoubleClick += (_, _) => ToggleMaximized();
        buttons.Controls.AddRange(new Control[] { minimize, maximize, close });
        title.Controls.Add(caption);
        title.Controls.Add(buttons);
        var body = new Panel { Dock = DockStyle.Fill, Padding = new Padding(9, 7, 9, 9) };
        body.Controls.AddRange(new Control[] { meter, stats, route, status, connect });
        var frame = new Panel { Dock = DockStyle.Fill, BorderStyle = BorderStyle.FixedSingle, Padding = new Padding(1) };
        frame.Controls.Add(body);
        frame.Controls.Add(title);
        Controls.Add(frame);
        connect.Font = new Font("Tahoma", 16, FontStyle.Bold);
        connect.Click += async (_, _) => { if (stop is { } current) current.Cancel(); else await Listen(); };
        FormClosing += (_, _) => stop?.Cancel();
        timer.Tick += (_, _) => { meter.Value = Volatile.Read(ref level); stats.Text = $"PCM 48 kHz · {Interlocked.Read(ref received) / 1024:N0} KiB recibidos"; };
        timer.Start();
        if (!string.IsNullOrWhiteSpace(validationPath)) Shown += async (_, _) => await Listen();
    }

    private void ToggleMaximized() => WindowState = WindowState == FormWindowState.Maximized ? FormWindowState.Normal : FormWindowState.Maximized;

    [DllImport("user32.dll")] private static extern bool ReleaseCapture();
    [DllImport("user32.dll")] private static extern IntPtr SendMessage(IntPtr hWnd, uint msg, IntPtr wParam, IntPtr lParam);

    private async Task Listen()
    {
        using var session = new CancellationTokenSource();
        stop = session;
        connect.Text = "Desconectar";
        Interlocked.Exchange(ref received, 0);
        Volatile.Write(ref level, 0);
        route.Text = "Entrada Android: —";
        TcpListener? tcp = null;
        Task? discoveryTask = null;
        try
        {
            tcp = new TcpListener(IPAddress.Any, Port);
            tcp.Start(2);
            discoveryTask = Discover(session.Token);
            var addresses = NetworkInterface.GetAllNetworkInterfaces()
                .Where(i => i.OperationalStatus == OperationalStatus.Up && i.NetworkInterfaceType != NetworkInterfaceType.Loopback)
                .SelectMany(i => i.GetIPProperties().UnicastAddresses)
                .Where(a => a.Address.AddressFamily == AddressFamily.InterNetwork && !IPAddress.IsLoopback(a.Address))
                .Select(a => a.Address.ToString());
            var waiting = $"Buscando teléfono en la red local\nIP: {string.Join(", ", addresses)}";
            status.Text = waiting;
            Trace("Listening " + Port);
            while (!session.IsCancellationRequested)
            {
                using var phone = await tcp.AcceptTcpClientAsync(session.Token);
                var cableStatus = new Progress<string>(message => status.Text = message);
                var inputRoute = new Progress<string>(message => route.Text = message);
                try { await Task.Run(() => Receive(phone, cableStatus, inputRoute, session.Token), session.Token); }
                catch (Exception e) when (e is IOException or SocketException or OperationCanceledException)
                {
                    if (session.IsCancellationRequested) break;
                    Debug.WriteLine(e.Message);
                }
                route.Text = "Entrada Android: —";
                Volatile.Write(ref level, 0);
                status.Text = waiting;
            }
        }
        catch (OperationCanceledException) { status.Text = "En espera"; }
        catch (SocketException e) { status.Text = "No se pudo abrir el puerto: " + e.SocketErrorCode; Trace(status.Text); }
        finally
        {
            tcp?.Stop();
            session.Cancel();
            if (discoveryTask != null)
                try { await discoveryTask; } catch (OperationCanceledException) { }
            stop = null;
            Volatile.Write(ref level, 0);
            connect.Text = "Conectar";
        }
    }

    private static async Task Discover(CancellationToken token)
    {
        try
        {
            using var udp = new UdpClient(Port);
            var reply = Encoding.ASCII.GetBytes(Discovery + "!");
            while (!token.IsCancellationRequested)
            {
                var query = await udp.ReceiveAsync(token);
                if (Encoding.ASCII.GetString(query.Buffer) == Discovery + "?" && IsLocalPeer(query.RemoteEndPoint.Address))
                    await udp.SendAsync(reply, query.RemoteEndPoint, token);
            }
        }
        catch (SocketException e) { Debug.WriteLine(e.Message); }
    }

    private async Task Receive(TcpClient phone, IProgress<string> cableStatus, IProgress<string> inputRoute,
        CancellationToken stopToken)
    {
        phone.NoDelay = true;
        var peer = ((IPEndPoint)phone.Client.RemoteEndPoint!).Address;
        if (!IsLocalPeer(peer)) return;
        using var stream = phone.GetStream();
        using var deadline = CancellationTokenSource.CreateLinkedTokenSource(stopToken);
        deadline.CancelAfter(TimeSpan.FromSeconds(3));
        string expected = $"{Discovery}\n";
        var auth = new byte[expected.Length];
        await stream.ReadExactlyAsync(auth, deadline.Token);
        if (!auth.AsSpan().SequenceEqual(Encoding.ASCII.GetBytes(expected))) return;
        using var speaker = VirtualCable.TryOpen(Trace, out string cableReport);
        // The sender begins capturing as soon as it receives the acknowledgment.
        await stream.WriteAsync(new byte[] { (byte)'Y' }, deadline.Token);
        deadline.CancelAfter(Timeout.InfiniteTimeSpan);
        Trace("Connected " + peer);
        cableStatus.Report(cableReport);
        Trace(cableReport);
        byte[] header = new byte[3], buffer = new byte[1920];
        using var idle = CancellationTokenSource.CreateLinkedTokenSource(stopToken);
        while (!stopToken.IsCancellationRequested)
        {
            idle.CancelAfter(TimeSpan.FromSeconds(8));
            await stream.ReadExactlyAsync(header, idle.Token);
            int length = BinaryPrimitives.ReadUInt16BigEndian(header.AsSpan(1));
            if (length is <= 0 or > 1920) throw new IOException("Longitud de paquete inválida.");
            await stream.ReadExactlyAsync(buffer.AsMemory(0, length), idle.Token);
            if (header[0] == 2)
            {
                if (length > 256) throw new IOException("Nombre de entrada inválido.");
                string source = "Entrada Android: " + Encoding.UTF8.GetString(buffer, 0, length);
                inputRoute.Report(source);
                Trace(source);
                continue;
            }
            if (header[0] != 1 || length % 2 != 0) throw new IOException("Paquete PCM inválido.");
            long total = Interlocked.Add(ref received, length);
            if (total / 96000 != (total - length) / 96000) Trace($"PCM bytes={total}");
            int peak = 0;
            for (int i = 0; i < length; i += 2)
                peak = Math.Max(peak, Math.Abs((int)BinaryPrimitives.ReadInt16LittleEndian(buffer.AsSpan(i, 2))));
            Volatile.Write(ref level, Math.Min(100, (int)(Math.Sqrt(peak / 32768.0) * 100)));
            speaker?.Enqueue(buffer.AsSpan(0, length));
        }
    }

    private static bool IsLocalPeer(IPAddress peer)
    {
        if (IPAddress.IsLoopback(peer)) return true;
        var bytes = peer.GetAddressBytes();
        if (bytes.Length != 4) return false;
        return NetworkInterface.GetAllNetworkInterfaces()
            .Where(i => i.OperationalStatus == OperationalStatus.Up && i.NetworkInterfaceType != NetworkInterfaceType.Loopback && i.NetworkInterfaceType != NetworkInterfaceType.Tunnel)
            .SelectMany(i => i.GetIPProperties().UnicastAddresses)
            .Where(a => a.Address.AddressFamily == AddressFamily.InterNetwork && a.PrefixLength is > 0 and < 32)
            .Any(a => {
                uint mask = uint.MaxValue << (32 - a.PrefixLength);
                return (BinaryPrimitives.ReadUInt32BigEndian(bytes) & mask) ==
                       (BinaryPrimitives.ReadUInt32BigEndian(a.Address.GetAddressBytes()) & mask);
            });
    }
}
