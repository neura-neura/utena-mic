package local.utena.mic;

import android.Manifest;
import android.app.*;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.media.*;
import android.os.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public final class MicService extends Service {
    static final String START = "local.utena.mic.START";
    static final String STOP = "local.utena.mic.STOP";
    static final String MUTE = "local.utena.mic.MUTE";
    private static final String CHANNEL = "microphone_controls";
    private static final int NOTIFICATION = 20;
    private static final int RATE = 48000, FRAME = 960;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final AtomicBoolean refresh = new AtomicBoolean(true);
    private final AtomicInteger muteRequests = new AtomicInteger();
    private final Set<Runnable> observers = new LinkedHashSet<>();
    private AudioManager audio;
    private NotificationManager notifications;
    private PowerManager.WakeLock wakeLock;
    private volatile Socket socket;
    private volatile boolean active, muted;
    private boolean connected;
    private Thread worker;
    private String route = "", status = "En espera";
    private int level;
    private final AudioDeviceCallback deviceChanges = new AudioDeviceCallback() {
        @Override public void onAudioDevicesAdded(AudioDeviceInfo[] devices) { changed(); }
        @Override public void onAudioDevicesRemoved(AudioDeviceInfo[] devices) { changed(); }
    };

    final class LocalBinder extends Binder {
        MicService service() { return MicService.this; }
    }

    @Override public IBinder onBind(Intent intent) { return new LocalBinder(); }

    @Override public void onCreate() {
        super.onCreate();
        audio = getSystemService(AudioManager.class);
        notifications = getSystemService(NotificationManager.class);
        NotificationChannel channel = new NotificationChannel(CHANNEL, "Control del micrófono", NotificationManager.IMPORTANCE_DEFAULT);
        channel.setSound(null, null);
        channel.enableVibration(false);
        channel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        notifications.createNotificationChannel(channel);
        wakeLock = getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "UtenaMic:stream");
        wakeLock.setReferenceCounted(false);
        audio.registerAudioDeviceCallback(deviceChanges, ui);
        changed();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? "" : intent.getAction();
        if (START.equals(action) && worker == null) {
            active = true;
            status = "Buscando Windows…";
            muteRequests.set(0);
            if (Build.VERSION.SDK_INT >= 30)
                startForeground(NOTIFICATION, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
            else startForeground(NOTIFICATION, notification());
            wakeLock.acquire();
            refresh.set(true);
            String host = intent.getStringExtra("host");
            worker = new Thread(() -> stream(host == null ? "" : host), "UtenaMic");
            worker.start();
            publish();
        } else if (STOP.equals(action)) {
            stop();
            if (worker == null) stopSelf();
        } else if (MUTE.equals(action)) {
            toggleMute();
            if (worker == null) stopSelf();
        }
        return START_NOT_STICKY;
    }

    boolean active() { return worker != null; }
    boolean connected() { return connected; }
    boolean muted() { return muted; }
    String route() { return route; }
    String status() { return status; }
    int level() { return level; }
    void observe(Runnable observer) { observers.add(observer); observer.run(); }
    void removeObserver(Runnable observer) { observers.remove(observer); }

    void toggleMute() {
        if (active && connected) muteRequests.incrementAndGet();
    }

    void stop() {
        active = false;
        connected = false;
        Socket current = socket;
        if (current != null) try { current.close(); } catch (IOException ignored) { }
        publish();
    }

    private void changed() {
        refresh.set(true);
        if (!active) {
            route = "Disponible: " + name(preferredInput());
            publish();
        }
    }

    private void publish() {
        for (Runnable observer : observers) observer.run();
    }

    private void showState() {
        if (active) notifications.notify(NOTIFICATION, notification());
        publish();
    }

    private Notification notification() {
        int flags = PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT;
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), flags);
        PendingIntent stop = PendingIntent.getService(this, 1, new Intent(this, MicService.class).setAction(STOP), flags);
        Notification.Builder builder = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.notification_mic)
                .setContentTitle("Utena Mic")
                .setContentText(connected ? (muted ? "Micrófono silenciado" : "Micrófono activo") : "Conectando…")
                .setContentIntent(open)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false);
        if (Build.VERSION.SDK_INT >= 31)
            builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        if (connected) {
            PendingIntent mute = PendingIntent.getService(this, 2, new Intent(this, MicService.class).setAction(MUTE), flags);
            builder.addAction(new Notification.Action.Builder(null, muted ? "Activar micrófono" : "Silenciar", mute).build());
        }
        return builder.addAction(new Notification.Action.Builder(null, "Desconectar", stop).build()).build();
    }

    @Override public void onDestroy() {
        stop();
        audio.unregisterAudioDeviceCallback(deviceChanges);
        if (wakeLock.isHeld()) wakeLock.release();
        super.onDestroy();
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
        Thread controls = null;
        int previousAudioMode = audio.getMode();
        boolean communication = false;
        String finalStatus = "En espera";
        try (Socket connection = new LanConnection(this).open(host, () -> active)) {
            socket = connection;
            if (!active) return;
            InetAddress ip = connection.getInetAddress();
            connection.setSoTimeout(5000);
            DataOutputStream wire = new DataOutputStream(new BufferedOutputStream(connection.getOutputStream()));
            wire.write("UTENA_MIC_V3\n".getBytes(StandardCharsets.US_ASCII));
            wire.flush();
            InputStream control = connection.getInputStream();
            if (control.read() != 'Y')
                throw new IOException("Actualiza Utena Mic en Windows y vuelve a conectar.");
            int initialState = control.read();
            if (initialState < 0 || initialState > 1) throw new IOException("Respuesta de Windows no válida.");
            muted = initialState == 1;
            connection.setSoTimeout(0);
            String destination = ip.isLoopbackAddress() ? "Transmitiendo por túnel local" : "Transmitiendo a " + ip.getHostAddress();
            ui.post(() -> {
                connected = true;
                status = destination;
                showState();
            });
            controls = new Thread(() -> receiveMute(connection, control), "UtenaMic-controls");
            controls.start();
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
                    ui.post(() -> { route = "En uso: " + source; publish(); });
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
                int toggles = muteRequests.getAndSet(0);
                for (int i = 0; i < toggles; i++) {
                    wire.writeByte(3);
                    wire.writeShort(1);
                    wire.writeByte(1);
                }
                if (muted) Arrays.fill(pcm, 0, read, (byte) 0);
                wire.writeByte(1);
                wire.writeShort(read);
                wire.write(pcm, 0, read);
                wire.flush();
                if (++tick % 5 == 0) {
                    int peak = 0;
                    for (int i = 0; i + 1 < read; i += 2)
                        peak = Math.max(peak, Math.abs((short)((pcm[i] & 255) | (pcm[i + 1] << 8))));
                    int level = Math.min(100, (int)(Math.sqrt(peak / 32768.0) * 100));
                    ui.post(() -> { this.level = muted ? 0 : level; publish(); });
                }
            }
        } catch (Exception e) {
            if (active) finalStatus = e.getMessage() == null ? "Conexión interrumpida" : e.getMessage();
        } finally {
            if (controls != null) {
                try { controls.join(); }
                catch (InterruptedException error) { Thread.currentThread().interrupt(); }
            }
            if (recorder != null) recorder.release();
            if (communication && sdk() >= 31) {
                clearBluetooth();
                audio.setMode(previousAudioMode);
            }
            socket = null;
            String message = finalStatus;
            ui.post(() -> {
                active = false;
                connected = false;
                muted = false;
                worker = null;
                status = message;
                level = 0;
                changed();
                if (wakeLock.isHeld()) wakeLock.release();
                stopForeground(STOP_FOREGROUND_REMOVE);
                stopSelf();
            });
        }
    }

    private void receiveMute(Socket connection, InputStream control) {
        try {
            int state;
            while ((state = control.read()) >= 0) {
                if (state > 1) break;
                muted = state == 1;
                ui.post(() -> {
                    if (socket == connection && connected) {
                        if (muted) level = 0;
                        showState();
                    }
                });
            }
        } catch (IOException ignored) {
        } finally {
            try { connection.close(); } catch (IOException ignored) { }
        }
    }
}
