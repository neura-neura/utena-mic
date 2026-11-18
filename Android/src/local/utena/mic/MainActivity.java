package local.utena.mic;

import android.Manifest;
import android.app.Activity;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.view.ViewGroup;
import android.widget.*;

public final class MainActivity extends Activity {
    private TextView route, status;
    private EditText address;
    private Button connect, mute;
    private ProgressBar meter;
    private MicService service;
    private boolean bound, bluetoothRequested, notificationsRequested;
    private final Runnable refreshUI = this::showServiceState;
    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            service = ((MicService.LocalBinder) binder).service();
            service.observe(refreshUI);
        }
        @Override public void onServiceDisconnected(ComponentName name) {
            service = null;
            connect.setEnabled(false);
            mute.setEnabled(false);
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int padding = Math.round(16 * getResources().getDisplayMetrics().density);
        box.setPadding(padding, padding, padding, padding);
        box.setOnApplyWindowInsetsListener((view, insets) -> {
            view.setPadding(padding + insets.getSystemWindowInsetLeft(), padding + insets.getSystemWindowInsetTop(),
                    padding + insets.getSystemWindowInsetRight(), padding + insets.getSystemWindowInsetBottom());
            return insets;
        });
        box.setBackgroundColor(Color.rgb(216, 208, 211));

        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.app_icon);
        int iconSize = Math.round(56 * getResources().getDisplayMetrics().density);
        box.addView(logo, new LinearLayout.LayoutParams(iconSize, iconSize));
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
        address.setText(getPreferences(MODE_PRIVATE).getString("host", ""));
        box.addView(address);

        connect = new Button(this);
        connect.setText("Conectar");
        connect.setTextSize(20);
        connect.setEnabled(false);
        box.addView(connect, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        mute = new Button(this);
        mute.setText("Silenciar");
        mute.setEnabled(false);
        box.addView(mute, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        status = label("En espera", 14);
        box.addView(status);
        meter = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        meter.setMax(100);
        box.addView(meter, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 28));
        setContentView(box);
        connect.setOnClickListener(view -> {
            if (service == null) return;
            if (service.active()) service.stop();
            else requestStart();
        });
        mute.setOnClickListener(view -> {
            if (service != null) service.toggleMute();
        });
    }

    @Override protected void onStart() {
        super.onStart();
        bound = bindService(new Intent(this, MicService.class), connection, BIND_AUTO_CREATE);
    }

    @Override protected void onStop() {
        if (service != null) service.removeObserver(refreshUI);
        if (bound) unbindService(connection);
        service = null;
        bound = false;
        super.onStop();
    }

    private TextView label(String text, int size) {
        TextView label = new TextView(this);
        label.setText(text);
        label.setTextSize(size);
        label.setTypeface(Typeface.SANS_SERIF);
        return label;
    }

    private void showServiceState() {
        if (service == null) return;
        connect.setEnabled(true);
        connect.setText(service.active() ? "Desconectar" : "Conectar");
        mute.setEnabled(service.connected());
        mute.setText(service.muted() ? "Silenciado · Activar micrófono" : "Silenciar");
        status.setText(service.status());
        route.setText(service.route());
        meter.setProgress(service.level());
    }

    private void requestStart() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 4);
        } else if (Build.VERSION.SDK_INT >= 31 && !bluetoothRequested &&
                checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            bluetoothRequested = true;
            requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT}, 5);
        } else if (Build.VERSION.SDK_INT >= 33 && !notificationsRequested &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationsRequested = true;
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 6);
        } else {
            String host = address.getText().toString().trim();
            getPreferences(MODE_PRIVATE).edit().putString("host", host).apply();
            startForegroundService(new Intent(this, MicService.class).setAction(MicService.START).putExtra("host", host));
        }
    }

    @Override public void onRequestPermissionsResult(int request, String[] names, int[] result) {
        super.onRequestPermissionsResult(request, names, result);
        if (request == 4 && (result.length == 0 || result[0] != PackageManager.PERMISSION_GRANTED)) {
            status.setText("Permite el micrófono para transmitir");
        } else if (request == 4 || request == 5 || request == 6) {
            requestStart();
        }
    }
}
