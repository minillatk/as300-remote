package local.as300;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.hardware.Camera;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.widget.FrameLayout;
import android.widget.TextView;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.PlanarYUVLuminanceSource;
import com.google.zxing.ReaderException;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;

@SuppressWarnings("deprecation")
public final class QrActivity extends Activity implements SurfaceHolder.Callback {
    private final QRCodeReader reader = new QRCodeReader();
    private Camera camera;
    private TextView hint;
    private SurfaceHolder surface;
    private long nextScan;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        FrameLayout layout = new FrameLayout(this);
        SurfaceView preview = new SurfaceView(this);
        surface = preview.getHolder();
        surface.addCallback(this);
        layout.addView(preview);
        hint = new TextView(this);
        hint.setText("AS300のバッテリーカバー裏にあるQRコードを映してください");
        hint.setTextColor(Color.WHITE);
        hint.setTextSize(16);
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(16, 18, 16, 18);
        hint.setBackgroundColor(0xdd10171e);
        layout.addView(hint, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
        setContentView(layout);
    }

    @Override public void surfaceCreated(SurfaceHolder holder) { openPreview(); }
    @Override public void surfaceChanged(SurfaceHolder holder, int format, int w, int h) {}
    @Override public void surfaceDestroyed(SurfaceHolder holder) { closePreview(); }
    @Override protected void onResume() {
        super.onResume();
        if (surface.getSurface().isValid()) openPreview();
    }
    @Override protected void onPause() {
        closePreview();
        super.onPause();
    }

    private void openPreview() {
        if (camera != null) return;
        try {
            int back = 0;
            Camera.CameraInfo info = new Camera.CameraInfo();
            for (; back < Camera.getNumberOfCameras(); back++) {
                Camera.getCameraInfo(back, info);
                if (info.facing == Camera.CameraInfo.CAMERA_FACING_BACK) break;
            }
            camera = Camera.open(back);
            camera.setDisplayOrientation(info.orientation);
            camera.setPreviewDisplay(surface);
            Camera.Parameters config = camera.getParameters();
            if (config.getSupportedFocusModes().contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE)) {
                config.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE);
                camera.setParameters(config);
            }
            Camera.Size size = camera.getParameters().getPreviewSize();
            camera.setPreviewCallback((data, active) -> scan(data, size.width, size.height));
            camera.startPreview();
        } catch (Exception e) {
            closePreview();
            hint.setText("スマホのカメラを開けません。戻ってカメラの権限を確認してください。");
        }
    }

    private void scan(byte[] data, int width, int height) {
        if (data == null || isFinishing() || SystemClock.elapsedRealtime() < nextScan) return;
        nextScan = SystemClock.elapsedRealtime() + 230;
        try {
            PlanarYUVLuminanceSource frame = new PlanarYUVLuminanceSource(
                    data, width, height, 0, 0, width, height, false);
            String raw = reader.decode(new BinaryBitmap(new HybridBinarizer(frame))).getText();
            WifiQr qr = WifiQr.parse(raw);
            if (qr == null) {
                hint.setText("このコードからAS300の接続情報を読み取れません。AS300本体のシールを確認してください。");
                return;
            }
            setResult(RESULT_OK, new Intent().putExtra("wifiQr", raw));
            finish();
        } catch (ReaderException ignored) {
            // Wait for another frame.
        }
    }

    private void closePreview() {
        if (camera == null) return;
        camera.setPreviewCallback(null);
        camera.release();
        camera = null;
    }
}
