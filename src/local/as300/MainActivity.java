package local.as300;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.IOException;
import java.net.URL;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONArray;

public final class MainActivity extends Activity {
    private static final int SCAN = 1;
    private static final int CAMERA_PERMISSION = 2;
    private static final int WIFI_PERMISSION = 3;
    private LinearLayout layout;
    private ScrollView controls;
    private LinearLayout connectButtons;
    private LinearLayout recordingButtons;
    private TextView status;
    private TextView resolution;
    private ImageView preview;
    private Button record;
    private Button disconnect;
    private WifiQr pendingQr;
    private volatile Session session;
    private String cameraStatus = "";
    private boolean commandPending;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        layout = new LinearLayout(this);
        preview = new ImageView(this);
        preview.setContentDescription("AS300のライブビュー");
        preview.setBackgroundColor(Color.BLACK);
        preview.setScaleType(ImageView.ScaleType.FIT_CENTER);
        layout.addView(preview);

        controls = new ScrollView(this);
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(4), dp(4), dp(4), dp(4));
        controls.addView(panel, new ScrollView.LayoutParams(-1, -2));
        layout.addView(controls);

        connectButtons = new LinearLayout(this);
        connectButtons.addView(button("QRコードで接続", this::openScanner));
        connectButtons.addView(button("接続済みのAS300", () -> connectWithPermission(null)));
        connectButtons.addView(button("Wi-Fi設定", () -> startActivity(new Intent(Settings.ACTION_WIFI_SETTINGS))));
        panel.addView(connectButtons);
        recordingButtons = new LinearLayout(this);
        record = button("● 録画開始", this::toggleRecording);
        disconnect = button("切断", this::confirmDisconnect);
        recordingButtons.addView(record);
        recordingButtons.addView(disconnect);
        panel.addView(recordingButtons);
        status = label("AS300の電源を入れ、Wi-Fiをオンにしてください。", 13);
        resolution = label("ライブビュー待機中", 12);
        panel.addView(status);
        panel.addView(resolution);
        applyOrientation(getResources().getConfiguration());
        setContentView(layout);
        refreshButtons();
    }

    @Override public void onConfigurationChanged(Configuration configuration) {
        super.onConfigurationChanged(configuration);
        applyOrientation(configuration);
    }

    private void applyOrientation(Configuration configuration) {
        boolean landscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE;
        int match = ViewGroup.LayoutParams.MATCH_PARENT;
        int wrap = ViewGroup.LayoutParams.WRAP_CONTENT;
        layout.setOrientation(landscape ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        preview.setLayoutParams(new LinearLayout.LayoutParams(landscape ? 0 : match, landscape ? match : 0, 1));
        controls.setLayoutParams(new LinearLayout.LayoutParams(landscape ? dp(132) : match,
                landscape ? match : wrap));
        layoutButtons(connectButtons, landscape);
        layoutButtons(recordingButtons, landscape);
        if (landscape) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        }
    }

    private void layoutButtons(LinearLayout group, boolean landscape) {
        group.setOrientation(landscape ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        for (int i = 0; i < group.getChildCount(); i++) {
            group.getChildAt(i).setLayoutParams(new LinearLayout.LayoutParams(
                    landscape ? ViewGroup.LayoutParams.MATCH_PARENT : 0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, landscape ? 0 : 1));
        }
    }

    private TextView label(String text, int sp) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(sp);
        view.setPadding(0, dp(3), 0, dp(3));
        return view;
    }

    private int dp(int size) { return Math.round(size * getResources().getDisplayMetrics().density); }

    private Button button(String text, Runnable listener) {
        Button view = new Button(this);
        view.setText(text);
        view.setTextSize(12);
        view.setAllCaps(false);
        view.setOnClickListener(v -> listener.run());
        return view;
    }

    private void openScanner() {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, CAMERA_PERMISSION);
            return;
        }
        startActivityForResult(new Intent(this, QrActivity.class), SCAN);
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != SCAN || result != RESULT_OK || data == null) return;
        String text = data.getStringExtra("wifiQr");
        WifiQr qr = text == null ? null : WifiQr.parse(text);
        if (qr != null) connectWithPermission(qr);
    }

    private void connectWithPermission(WifiQr qr) {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            pendingQr = qr;
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, WIFI_PERMISSION);
            return;
        }
        connect(qr);
    }

    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(request, permissions, results);
        boolean granted = results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED;
        switch (request) {
            case CAMERA_PERMISSION:
                if (granted) openScanner();
                else status.setText("QRコードを読むにはカメラの権限が必要です。");
                break;
            case WIFI_PERMISSION:
                if (granted) connect(pendingQr);
                else status.setText("AndroidでWi-Fiの接続先を確認するには位置情報の権限が必要です。");
                pendingQr = null;
                break;
            default:
                break;
        }
    }

    private void connect(WifiQr qr) {
        if (session != null) return;
        Session next = new Session(new CameraWifi(this));
        session = next;
        status.setText("AS300のWi-Fiに接続しています…");
        refreshButtons();
        next.commands.execute(() -> {
            try {
                next.wifi.connect(qr);
                next.camera = SonyCamera.discover(next.wifi);
                next.camera.prepareMovie();
                updateStatus(next, next.camera.status());
                next.startPreview();
                next.commands.scheduleWithFixedDelay(() -> poll(next), 1, 1, TimeUnit.SECONDS);
            } catch (Exception error) {
                post(next, () -> endSession("接続できませんでした。 " + message(error)));
            }
        });
    }

    private void poll(Session current) {
        try {
            updateStatus(current, current.camera.status());
            current.failures = 0;
        } catch (Exception error) {
            if (++current.failures >= 3) {
                post(current, () -> endSession("接続が途切れました。録画状態はAS300本体で確認してください。"));
            }
        }
    }

    private void updateStatus(Session current, String value) {
        post(current, () -> {
            cameraStatus = value;
            if (!commandPending && !current.statusMessage) status.setText(statusText(value));
            refreshButtons();
        });
    }

    private static String statusText(String value) {
        if ("MovieRecording".equals(value)) return "● AS300で録画中";
        if ("IDLE".equals(value)) return "AS300に接続済み";
        if ("MovieWaitRecStart".equals(value)) return "録画を開始しています…";
        if ("MovieSaving".equals(value)) return "AS300で保存中…";
        return "AS300の状態  " + value;
    }

    private void toggleRecording() {
        Session current = session;
        if (current == null || current.camera == null || commandPending) return;
        boolean stop = "MovieRecording".equals(cameraStatus);
        commandPending = true;
        current.statusMessage = false;
        status.setText(stop ? "録画を停止しています…" : "録画を開始しています…");
        refreshButtons();
        current.commands.execute(() -> {
            try {
                String actual = current.camera.status();
                if (stop && "MovieRecording".equals(actual)) {
                    current.camera.call("stopMovieRec");
                    actual = "MovieSaving";
                } else if (!stop && "IDLE".equals(actual)) {
                    current.camera.prepareMovie();
                    JSONArray result = current.camera.call("startMovieRec");
                    if (result.optInt(0, -1) != 0) throw new IOException("録画開始の応答を確認できません。");
                    actual = "MovieWaitRecStart";
                }
                finishCommand(current, actual, null);
            } catch (Exception error) {
                String actual = "";
                try { actual = current.camera.status(); } catch (Exception ignored) {}
                finishCommand(current, actual, message(error));
            }
        });
    }

    private void finishCommand(Session current, String value, String error) {
        post(current, () -> {
            commandPending = false;
            cameraStatus = value;
            current.statusMessage = error != null;
            status.setText(error == null ? statusText(value) :
                    ("MovieRecording".equals(value) ? "● AS300で録画中。 " : "") + error);
            refreshButtons();
        });
    }

    private void refreshButtons() {
        boolean active = session != null;
        connectButtons.setVisibility(active ? View.GONE : View.VISIBLE);
        recordingButtons.setVisibility(active ? View.VISIBLE : View.GONE);
        boolean recording = "MovieRecording".equals(cameraStatus);
        record.setEnabled(active && !commandPending && (recording || "IDLE".equals(cameraStatus)));
        record.setText(recording ? "■ 録画停止" : "● 録画開始");
        disconnect.setEnabled(active && !commandPending);
    }

    private void confirmDisconnect() {
        if (cameraStatus.startsWith("Movie") && !"MovieSaving".equals(cameraStatus)) {
            new AlertDialog.Builder(this).setMessage("切断後もAS300の録画は続く場合があります。切断しますか？")
                    .setNegativeButton("戻る", null)
                    .setPositiveButton("切断", (d, w) -> endSession("AS300とのWi-Fi操作を終了しました。")).show();
        } else {
            endSession("AS300とのWi-Fi操作を終了しました。");
        }
    }

    private void endSession(String note) {
        Session current = session;
        session = null;
        if (current != null) current.close();
        cameraStatus = "";
        commandPending = false;
        status.setText(note);
        preview.setImageDrawable(null);
        resolution.setText("ライブビュー待機中");
        refreshButtons();
    }

    private void post(Session expected, Runnable action) {
        runOnUiThread(() -> { if (session == expected && !isDestroyed()) action.run(); });
    }

    private static String message(Exception error) {
        if (error instanceof IOException && error.getMessage() != null &&
                error.getMessage().matches("(?s).*[ぁ-んァ-ヶ一-龯].*")) return error.getMessage();
        return "カメラの電源、Wi-Fi設定、動画モードを確認してください。";
    }

    @Override protected void onDestroy() {
        Session current = session;
        session = null;
        if (current != null) current.close();
        super.onDestroy();
    }

    private final class Session {
        final ScheduledExecutorService commands = Executors.newSingleThreadScheduledExecutor();
        final CameraWifi wifi;
        final AtomicBoolean framePending = new AtomicBoolean();
        volatile SonyCamera camera;
        volatile Thread stream;
        volatile boolean closed;
        volatile boolean statusMessage;
        int failures;

        Session(CameraWifi wifi) { this.wifi = wifi; }

        synchronized void startPreview() {
            if (closed) return;
            stream = new Thread(() -> {
                for (int attempt = 0; attempt < 3 && !closed; attempt++) {
                    try {
                        URL url = camera.startPreview();
                        camera.stream(url, this::showFrame);
                        return;
                    } catch (Exception error) {
                        if (!closed) {
                            try { camera.call("stopLiveview"); } catch (Exception ignored) {}
                            post(this, () -> {
                                preview.setImageDrawable(null);
                                resolution.setText("ライブビューが途切れました。");
                            });
                        }
                    }
                }
                post(this, () -> resolution.setText("映像を取得できません。切断して接続し直してください。"));
            }, "AS300 Live View");
            stream.start();
        }

        void showFrame(byte[] jpeg) {
            if (closed || !framePending.compareAndSet(false, true)) return;
            Bitmap frame = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length);
            runOnUiThread(() -> {
                if (session == this && frame != null) {
                    preview.setImageBitmap(frame);
                    String dimensions = "ライブビュー  " + frame.getWidth() + " × " + frame.getHeight();
                    if (!dimensions.contentEquals(resolution.getText())) resolution.setText(dimensions);
                }
                framePending.set(false);
            });
        }

        synchronized void close() {
            closed = true;
            commands.shutdownNow();
            if (stream != null) stream.interrupt();
            wifi.close();
        }
    }
}
