package local.utena.mic;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.media.*;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

public final class MainActivity extends Activity {
    private static final int RATE = 48000, PORT = 48395, FRAME = 960;
    private static final String DISCOVERY = "UTENA_MIC_V2";
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final AtomicBoolean refresh = new AtomicBoolean(true);
    private AudioManager audio;
    private TextView route, status;
    private EditText address;
    private Button connect;
    private ProgressBar meter;
    private volatile boolean active;
    private volatile Socket socket;
    private volatile Thread worker;
    private boolean bluetoothRequested;
    private final AudioDeviceCallback deviceChanges = new AudioDeviceCallback() {
        @Override public void onAudioDevicesAdded(AudioDeviceInfo[] devices) { changed(); }
        @Override public void onAudioDevicesRemoved(AudioDeviceInfo[] devices) { changed(); }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        audio = (AudioManager) getSystemService(AUDIO_SERVICE);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(24, 24, 24, 24);
        box.setBackgroundColor(Color.rgb(216, 208, 211));
        TextView title = label("Utena Mic", 22);
        title.setTextColor(Color.WHITE);
        title.setBackgroundColor(Color.rgb(142, 35, 81));
        title.setPadding(12, 8, 12, 8);
        box.addView(title);
        route = label("", 16);
        box.addView(route);
        address = new EditText(this);
        address.setHint("IP de la PC (opcional)");
        address.setSingleLine(true);
        box.addView(address);
        connect = new Button(this);
        connect.setText("Conectar");
        connect.setTextSize(20);
        box.addView(connect, new LinearLayout.LayoutParams(-1, 68 * (int)Math.ceil(getResources().getDisplayMetrics().density)));
        status = label("En espera", 14);
        box.addView(status);
        meter = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        meter.setMax(100);
        box.addView(meter, new LinearLayout.LayoutParams(-1, 28));
        setContentView(box);
        connect.setOnClickListener(v -> { if (active) stop(); else requestStart(); });
        audio.registerAudioDeviceCallback(deviceChanges, ui);
        changed();
    }

    private TextView label(String text, int size) {
        TextView label = new TextView(this);
        label.setText(text);
        label.setTextSize(size);
        label.setTypeface(Typeface.SANS_SERIF);
        return label;
    }

    private void changed() {
        refresh.set(true);
        if (!active) route.setText("Disponible: " + name(preferredInput()));
    }

    private AudioDeviceInfo preferredInput() {
        AudioDeviceInfo best = null;
        int rank = -1;
        for (AudioDeviceInfo device : audio.getDevices(2 /* AudioManager.GET_DEVICES_INPUTS */)) {
            int next;
            switch (device.getType()) {
                case AudioDeviceInfo.TYPE_WIRED_HEADSET:
                case AudioDeviceInfo.TYPE_USB_DEVICE:
                case AudioDeviceInfo.TYPE_USB_HEADSET:
                case AudioDeviceInfo.TYPE_USB_ACCESSORY:
                case AudioDeviceInfo.TYPE_LINE_ANALOG: next = 2; break;
                case AudioDeviceInfo.TYPE_BLUETOOTH_SCO:
                case AudioDeviceInfo.TYPE_BLE_HEADSET:
                    next = sdk() >= 31 && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED ? 2 : -1;
                    break;
                case AudioDeviceInfo.TYPE_BUILTIN_MIC: next = 1; break;
                default: next = 0;
            }
            if (next > rank || (next == rank && best != null && device.getId() > best.getId())) {
                best = device;
                rank = next;
            }
        }
        return best;
    }

    private static String name(AudioDeviceInfo device) {
        if (device == null) return "Sin entrada confirmada";
        switch (device.getType()) {
            case AudioDeviceInfo.TYPE_BUILTIN_MIC: return "Micrófono del teléfono";
            case AudioDeviceInfo.TYPE_WIRED_HEADSET: return "Micrófono con cable (analógico)";
            case AudioDeviceInfo.TYPE_BLUETOOTH_SCO:
            case AudioDeviceInfo.TYPE_BLE_HEADSET: return "Bluetooth · " + device.getProductName();
            case AudioDeviceInfo.TYPE_USB_DEVICE:
            case AudioDeviceInfo.TYPE_USB_HEADSET:
            case AudioDeviceInfo.TYPE_USB_ACCESSORY: return "USB · " + device.getProductName();
            default: return device.getProductName().toString();
        }
    }

    private void requestStart() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 4);
        } else if (sdk() >= 31 && !bluetoothRequested &&
                checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            bluetoothRequested = true;
            requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT}, 5);
        } else start();
    }

    @Override public void onRequestPermissionsResult(int req, String[] names, int[] result) {
        super.onRequestPermissionsResult(req, names, result);
        if (req == 4 && result.length > 0 && result[0] == PackageManager.PERMISSION_GRANTED) requestStart();
        if (req == 5) { refresh.set(true); start(); }
        else if (req == 4) status.setText("Permite el micrófono para transmitir");
    }

    private void start() {
        if (worker != null) return;
        String host = address.getText().toString().trim();
        active = true;
        refresh.set(true);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        connect.setText("Desconectar");
        status.setText("Buscando Windows…");
        worker = new Thread(() -> stream(host), "UtenaMic");
        worker.start();
    }

    private void stop() {
        active = false;
        Socket current = socket;
        if (current != null) try { current.close(); } catch (IOException ignored) {}
    }

    private InetAddress discover() throws IOException {
        try (DatagramSocket udp = new DatagramSocket()) {
            udp.setBroadcast(true);
            Set<InetAddress> broadcast = new LinkedHashSet<>();
            java.util.Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces != null && interfaces.hasMoreElements()) {
                NetworkInterface nic = interfaces.nextElement();
                if (!nic.isUp() || nic.isLoopback() || nic.isPointToPoint()) continue;
                for (InterfaceAddress item : nic.getInterfaceAddresses())
                    if (item.getBroadcast() != null) broadcast.add(item.getBroadcast());
            }
            broadcast.add(InetAddress.getByName("255.255.255.255"));
            byte[] hello = (DISCOVERY + "?").getBytes(StandardCharsets.US_ASCII);
            Set<InetAddress> found = new LinkedHashSet<>();
            for (int attempt = 0; attempt < 3 && active && found.isEmpty(); attempt++) {
                for (InetAddress target : broadcast) {
                    try { udp.send(new DatagramPacket(hello, hello.length, target, PORT)); }
                    catch (IOException ignored) { /* Another interface may still work. */ }
                }
                long cutoff = android.os.SystemClock.elapsedRealtime() + 550;
                while (active) {
                    long remaining = cutoff - android.os.SystemClock.elapsedRealtime();
                    if (remaining <= 0) break;
                    udp.setSoTimeout((int)remaining);
                    byte[] data = new byte[32];
                    DatagramPacket reply = new DatagramPacket(data, data.length);
                    try {
                        udp.receive(reply);
                        String message = new String(data, 0, reply.getLength(), StandardCharsets.US_ASCII);
                        if (reply.getPort() == PORT && (DISCOVERY + "!").equals(message)) found.add(reply.getAddress());
                    } catch (SocketTimeoutException ignored) { break; }
                }
            }
            if (found.size() == 1) return found.iterator().next();
            if (found.size() > 1) throw new IOException("Hay varios receptores. Escribe la IP del que quieres usar.");
        }
        return null;
    }

    private boolean isBluetooth(AudioDeviceInfo input) {
        return input != null && (input.getType() == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                input.getType() == AudioDeviceInfo.TYPE_BLE_HEADSET);
    }

    private static int sdk() {
        try { return Class.forName("android.os.Build$VERSION").getField("SDK_INT").getInt(null); }
        catch (ReflectiveOperationException e) { return 26; }
    }

    private void clearBluetooth() {
        try { AudioManager.class.getMethod("clearCommunicationDevice").invoke(audio); }
        catch (ReflectiveOperationException ignored) {}
    }

    private boolean routeBluetooth(AudioDeviceInfo input) {
        if (sdk() < 31 || !isBluetooth(input)) return false;
        // These public Android 12 APIs are absent from the local layout-preview SDK.
        try {
            Object result = AudioManager.class.getMethod("getAvailableCommunicationDevices").invoke(audio);
            for (Object item : (java.util.List<?>)result) {
                AudioDeviceInfo candidate = (AudioDeviceInfo)item;
                if (candidate.getType() == input.getType() && candidate.getAddress().equals(input.getAddress())) {
                    audio.setMode(3 /* AudioManager.MODE_IN_COMMUNICATION */);
                    if (Boolean.TRUE.equals(AudioManager.class.getMethod("setCommunicationDevice",
                            AudioDeviceInfo.class).invoke(audio, candidate))) return true;
                    clearBluetooth();
                    return false;
                }
            }
        } catch (ReflectiveOperationException | SecurityException ignored) {}
        return false;
    }

    private void stream(String host) {
        AudioRecord recorder = null;
        int previousAudioMode = audio.getMode();
        boolean communication = false;
        String finalStatus = "En espera";
        try (Socket connection = new Socket()) {
            socket = connection;
            InetAddress discovered = host.isEmpty() ? discover() : InetAddress.getByName(host);
            InetAddress ip = discovered == null ? InetAddress.getByName("127.0.0.1") : discovered;
            if (!active) return;
            try { connection.connect(new InetSocketAddress(ip, PORT), 3500); }
            catch (IOException error) {
                if (discovered == null) throw new IOException("No encontré la PC. Abre el receptor o escribe su IP.");
                throw new IOException("No se pudo conectar a " + ip.getHostAddress() + ". Revisa la red y el receptor.");
            }
            connection.setTcpNoDelay(true);
            connection.setSoTimeout(3500);
            DataOutputStream wire = new DataOutputStream(new BufferedOutputStream(connection.getOutputStream()));
            wire.write((DISCOVERY + "\n").getBytes(StandardCharsets.US_ASCII));
            wire.flush();
            if (connection.getInputStream().read() != 'Y') throw new IOException("El receptor no confirmó la conexión.");
            ui.post(() -> status.setText(ip.isLoopbackAddress() ? "Transmitiendo por túnel local" : "Transmitiendo a " + ip.getHostAddress()));
            byte[] pcm = new byte[FRAME * 2];
            int target = -1, reported = -2, tick = 0;
            while (active) {
                if (recorder == null || refresh.getAndSet(false)) {
                    AudioDeviceInfo desired = preferredInput();
                    int next = desired == null ? -1 : desired.getId();
                    if (recorder == null || next != target) {
                        if (recorder != null) { recorder.release(); recorder = null; }
                        if (communication && sdk() >= 31) {
                            clearBluetooth();
                            audio.setMode(previousAudioMode);
                        }
                        communication = routeBluetooth(desired);
                        if (!communication && isBluetooth(desired)) audio.setMode(previousAudioMode);
                        int buffer = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
                        if (buffer <= 0) throw new IOException("Android no ofrece una entrada PCM de 48 kHz.");
                        recorder = new AudioRecord(communication ? MediaRecorder.AudioSource.VOICE_COMMUNICATION : MediaRecorder.AudioSource.MIC, RATE, AudioFormat.CHANNEL_IN_MONO,
                                AudioFormat.ENCODING_PCM_16BIT, Math.max(FRAME * 4, buffer));
                        if (recorder.getState() != AudioRecord.STATE_INITIALIZED) throw new IOException("No se pudo abrir el micrófono.");
                        if (desired != null) recorder.setPreferredDevice(desired);
                        recorder.startRecording();
                        target = next;
                    }
                }
                AudioDeviceInfo actual = recorder.getRoutedDevice();
                int current = actual == null ? -1 : actual.getId();
                if (current != reported) {
                    String source = name(actual);
                    ui.post(() -> route.setText("En uso: " + source));
                    byte[] text = source.getBytes(StandardCharsets.UTF_8);
                    wire.writeByte(2);
                    wire.writeShort(text.length);
                    wire.write(text);
                    reported = current;
                }
                int read = recorder.read(pcm, 0, pcm.length, AudioRecord.READ_BLOCKING);
                if (read < 0) {
                    if (refresh.get() || read == AudioRecord.ERROR_DEAD_OBJECT) {
                        recorder.release();
                        recorder = null;
                        // Keep the network stream alive while Android reopens the audio input.
                        continue;
                    }
                    throw new IOException("Android interrumpió la entrada de audio (" + read + ").");
                }
                if (read == 0) continue;
                wire.writeByte(1);
                wire.writeShort(read);
                wire.write(pcm, 0, read);
                wire.flush();
                if (++tick % 5 == 0) {
                    int peak = 0;
                    for (int i = 0; i + 1 < read; i += 2)
                        peak = Math.max(peak, Math.abs((short)((pcm[i] & 255) | (pcm[i + 1] << 8))));
                    int level = Math.min(100, (int)(Math.sqrt(peak / 32768.0) * 100));
                    ui.post(() -> meter.setProgress(level));
                }
            }
        } catch (Exception e) {
            if (active) finalStatus = e.getMessage() == null ? "Conexión interrumpida" : e.getMessage();
        } finally {
            if (recorder != null) recorder.release();
            if (communication && sdk() >= 31) {
                clearBluetooth();
                audio.setMode(previousAudioMode);
            }
            socket = null;
            String message = finalStatus;
            ui.post(() -> {
                active = false;
                worker = null;
                getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                connect.setText("Conectar");
                status.setText(message);
                meter.setProgress(0);
                changed();
            });
        }
    }

    @Override protected void onStop() {
        stop();
        super.onStop();
    }

    @Override protected void onDestroy() {
        stop();
        audio.unregisterAudioDeviceCallback(deviceChanges);
        super.onDestroy();
    }
}
