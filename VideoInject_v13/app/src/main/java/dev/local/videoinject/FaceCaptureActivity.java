package dev.local.videoinject;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.graphics.RectF;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.view.Gravity;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.TextureView;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.graphics.SurfaceTexture;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.util.Collections;

/**
 * RD Service face capture — single-surface pipeline.
 *
 * Architecture:
 *   Camera2 (or injected video) -> TextureView (one surface only)
 *   Analysis: periodic getBitmap() -> FaceQualityChecker + LivenessChecker
 *   Capture: getBitmap() -> JPEG compress -> PidBlockBuilder
 *
 * Why single surface:
 *   Whatever renders on the TextureView is EXACTLY what gets analyzed
 *   and captured. Real camera or injected video — zero difference.
 *   One surface, one frame, one truth.
 */
public class FaceCaptureActivity extends Activity implements TextureView.SurfaceTextureListener {

    private static final String TAG = "FaceCapture";
    private static final int QUALITY_THRESHOLD = 80;
    private static final long ANALYSIS_INTERVAL_MS = 200L;
    private static final int ANALYSIS_W = 320;
    private static final int ANALYSIS_H = 240;
    private static final int PERM_CAMERA = 101;

    private TextureView previewView;
    private SurfaceView overlayView;
    private TextView statusText;
    private TextView qualityText;
    private TextView instructionText;
    private Button captureBtn;

    private CameraDevice cameraDevice;
    private CameraCaptureSession captureSession;
    private HandlerThread bgThread;
    private Handler bgHandler;
    private Surface previewSurface;

    private int previewW = 1280;
    private int previewH = 720;
    private final FaceQualityChecker qualityChecker = new FaceQualityChecker();
    private final LivenessChecker livenessChecker = new LivenessChecker();

    private int lastQuality = 0;
    private boolean faceAligned = false;
    private boolean livenessPass = false;
    private boolean captureReady = false;
    private volatile boolean captured = false;
    private volatile boolean analyzing = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        setContentView(root);

        // Camera preview — the ONLY surface camera writes to
        previewView = new TextureView(this);
        previewView.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        previewView.setSurfaceTextureListener(this);
        root.addView(previewView);

        // Transparent overlay for oval guide
        overlayView = new SurfaceView(this);
        overlayView.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        overlayView.setZOrderOnTop(true);
        overlayView.getHolder().setFormat(PixelFormat.TRANSPARENT);
        overlayView.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override public void surfaceCreated(SurfaceHolder h) { drawOvalGuide(false); }
            @Override public void surfaceChanged(SurfaceHolder h, int f, int w, int ht) { drawOvalGuide(false); }
            @Override public void surfaceDestroyed(SurfaceHolder h) {}
        });
        root.addView(overlayView);

        // Bottom panel
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundColor(0xCC000000);
        int p20 = dp(20), p12 = dp(12);
        panel.setPadding(p20, p12, p20, p20);
        FrameLayout.LayoutParams panelLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT);
        panelLp.gravity = Gravity.BOTTOM;
        panel.setLayoutParams(panelLp);

        instructionText = new TextView(this);
        instructionText.setText("Position your face inside the oval");
        instructionText.setTextSize(16f);
        instructionText.setTextColor(Color.WHITE);
        instructionText.setTextAlignment(View.TEXT_ALIGNMENT_CENTER);
        panel.addView(instructionText);

        statusText = new TextView(this);
        statusText.setText("Initializing camera…");
        statusText.setTextSize(12f);
        statusText.setTextColor(0xFF81C784);
        statusText.setPadding(0, dp(6), 0, 0);
        panel.addView(statusText);

        qualityText = new TextView(this);
        qualityText.setText("Quality: — | Liveness: — | Aligned: —");
        qualityText.setTextSize(11f);
        qualityText.setTextColor(0xFF9E9E9E);
        qualityText.setPadding(0, dp(4), 0, dp(8));
        panel.addView(qualityText);

        captureBtn = new Button(this);
        captureBtn.setText("Waiting for face…");
        captureBtn.setEnabled(false);
        captureBtn.setOnClickListener(v -> triggerCapture());
        panel.addView(captureBtn);

        root.addView(panel);

        // Request camera permission
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startBgThread();
            if (previewView.isAvailable()) openCamera();
        } else {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, PERM_CAMERA);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERM_CAMERA) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startBgThread();
                if (previewView.isAvailable()) openCamera();
            } else {
                statusText.setText("Camera permission denied");
            }
        }
    }

    // ------------------------------------------------------------------ camera

    private void openCamera() {
        try {
            CameraManager manager = (CameraManager) getSystemService(CAMERA_SERVICE);
            String cameraId = findFrontCamera(manager);
            if (cameraId == null) cameraId = "0";

            CameraCharacteristics chars = manager.getCameraCharacteristics(cameraId);
            android.hardware.camera2.params.StreamConfigurationMap map =
                    chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            if (map == null) {
                statusText.setText("Camera config unavailable");
                return;
            }

            android.util.Size[] sizes = map.getOutputSizes(SurfaceTexture.class);
            android.util.Size chosen = sizes[0];
            for (android.util.Size s : sizes) {
                if (s.getWidth() <= 1920 && s.getHeight() <= 1080) {
                    if (s.getWidth() * s.getHeight() > chosen.getWidth() * chosen.getHeight()
                            || chosen.getWidth() > 1920 || chosen.getHeight() > 1080) {
                        chosen = s;
                    }
                }
            }
            previewW = chosen.getWidth();
            previewH = chosen.getHeight();

            Log.i(TAG, "Opening front camera " + cameraId + ", preview " + previewW + "x" + previewH);
            manager.openCamera(cameraId, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(CameraDevice device) {
                    cameraDevice = device;
                    startPreviewSession();
                }
                @Override
                public void onDisconnected(CameraDevice device) { device.close(); }
                @Override
                public void onError(CameraDevice device, int error) {
                    device.close();
                    runOnUiThread(() -> statusText.setText("Camera error: " + error));
                }
            }, bgHandler);
        } catch (SecurityException e) {
            statusText.setText("Camera permission denied");
        } catch (Exception e) {
            statusText.setText("Camera open failed: " + e.getMessage());
            Log.e(TAG, "Camera open failed", e);
        }
    }

    private String findFrontCamera(CameraManager manager) {
        try {
            for (String id : manager.getCameraIdList()) {
                Integer facing = manager.getCameraCharacteristics(id)
                        .get(CameraCharacteristics.LENS_FACING);
                if (facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT) return id;
            }
        } catch (Exception ignored) {}
        return null;
    }

    /**
     * Single-surface session — ONLY the TextureView preview surface.
     * One surface means one target for the hooked camera.
     * Whatever renders here is what getBitmap() reads.
     */
    @SuppressWarnings("deprecation")
    private void startPreviewSession() {
        if (cameraDevice == null) return;
        SurfaceTexture st = previewView.getSurfaceTexture();
        if (st == null) return;
        st.setDefaultBufferSize(previewW, previewH);
        previewSurface = new Surface(st);

        try {
            cameraDevice.createCaptureSession(
                    Collections.singletonList(previewSurface),
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(CameraCaptureSession session) {
                            captureSession = session;
                            try {
                                CaptureRequest.Builder builder =
                                        cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                                builder.addTarget(previewSurface);
                                builder.set(CaptureRequest.CONTROL_AF_MODE,
                                        CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO);
                                builder.set(CaptureRequest.CONTROL_AE_MODE,
                                        CaptureRequest.CONTROL_AE_MODE_ON);
                                session.setRepeatingRequest(builder.build(), null, bgHandler);

                                runOnUiThread(() -> {
                                    statusText.setText("Preview running — analyzing face");
                                    Log.i(TAG, "Single-surface session active");
                                });
                                startAnalysisLoop();
                            } catch (Exception e) {
                                Log.e(TAG, "Preview request failed", e);
                            }
                        }
                        @Override
                        public void onConfigureFailed(CameraCaptureSession session) {
                            runOnUiThread(() -> statusText.setText("Session config failed"));
                        }
                    }, bgHandler);
        } catch (Exception e) {
            Log.e(TAG, "createCaptureSession failed", e);
        }
    }

    // ------------------------------------------------------------------ analysis loop

    private void startAnalysisLoop() {
        analyzing = true;
        if (bgHandler != null) bgHandler.post(analysisRunnable);
    }

    private final Runnable analysisRunnable = new Runnable() {
        @Override
        public void run() {
            if (!analyzing || captured) return;
            try {
                Bitmap bitmap = previewView.getBitmap(ANALYSIS_W, ANALYSIS_H);
                if (bitmap != null) {
                    processFrame(bitmap);
                    bitmap.recycle();
                }
            } catch (Exception ignored) {}
            if (bgHandler != null) bgHandler.postDelayed(this, ANALYSIS_INTERVAL_MS);
        }
    };

    private void processFrame(Bitmap bitmap) {
        FaceQualityChecker.Result qr = qualityChecker.check(bitmap);
        LivenessChecker.Result lr = livenessChecker.check(bitmap);

        lastQuality = qr.score;
        faceAligned = qr.aligned;
        livenessPass = lr.passed;
        captureReady = faceAligned && livenessPass && lastQuality >= QUALITY_THRESHOLD;

        runOnUiThread(() -> {
            qualityText.setText("Quality: " + lastQuality
                    + " | Liveness: " + (livenessPass ? "✓" : "✗")
                    + " | Aligned: " + (faceAligned ? "✓" : "✗"));

            String instruction;
            if (!qr.faceFound) instruction = "No face detected";
            else if (!faceAligned && qr.tooClose) instruction = "Move back — too close";
            else if (!faceAligned && qr.tooFar) instruction = "Move closer";
            else if (!faceAligned) instruction = "Center your face in the oval";
            else if (!livenessPass) instruction = "Hold still — verifying liveness";
            else if (lastQuality < QUALITY_THRESHOLD) instruction = "Improve lighting";
            else instruction = "Ready — tap Capture";
            instructionText.setText(instruction);

            captureBtn.setEnabled(captureReady);
            captureBtn.setText(captureReady ? "Capture" : "Waiting for face…");
            drawOvalGuide(faceAligned && livenessPass);
        });
    }

    // ------------------------------------------------------------------ capture

    private void triggerCapture() {
        if (!captureReady || captured) return;
        captured = true;
        captureBtn.setEnabled(false);
        captureBtn.setText("Capturing…");
        instructionText.setText("Hold still…");

        if (bgHandler == null) return;
        bgHandler.post(() -> {
            try {
                Bitmap bitmap = previewView.getBitmap(previewW, previewH);
                if (bitmap == null) {
                    runOnUiThread(() -> {
                        captured = false;
                        captureBtn.setText("Retry");
                        captureBtn.setEnabled(true);
                        statusText.setText("Capture failed — try again");
                    });
                    return;
                }

                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                bitmap.compress(Bitmap.CompressFormat.JPEG, 95, baos);
                byte[] jpegBytes = baos.toByteArray();
                bitmap.recycle();

                Log.i(TAG, "Captured from preview: " + jpegBytes.length + " bytes, "
                        + previewW + "x" + previewH);

                PidBlockBuilder.PidResult pidBlock = PidBlockBuilder.build(
                        jpegBytes, lastQuality, previewW, previewH);

                // Save for verification
                writeFile("last_capture.jpg", jpegBytes);
                writeFile("last_pid.xml", pidBlock.pidXml.getBytes("UTF-8"));
                writeFile("last_piddata.xml", pidBlock.pidDataXml.getBytes("UTF-8"));

                runOnUiThread(() -> {
                    captureBtn.setText("Captured ✓");
                    instructionText.setText("Face captured successfully");
                    statusText.setText("JPEG: " + jpegBytes.length / 1024 + "KB | PID block generated | qScore=" + lastQuality);
                    showResultDialog(pidBlock, jpegBytes.length);
                });
            } catch (Exception e) {
                Log.e(TAG, "Capture failed", e);
                runOnUiThread(() -> {
                    captured = false;
                    captureBtn.setText("Retry");
                    captureBtn.setEnabled(true);
                });
            }
        });
    }

    private void writeFile(String name, byte[] data) {
        try {
            FileOutputStream fos = new FileOutputStream(new File(getFilesDir(), name));
            try { fos.write(data); } finally { fos.close(); }
        } catch (Exception ignored) {}
    }

    private void showResultDialog(PidBlockBuilder.PidResult pidBlock, int jpegSize) {
        StringBuilder msg = new StringBuilder();
        msg.append("Face capture complete\n\n");
        msg.append("Image: ").append(jpegSize / 1024).append(" KB\n");
        msg.append("Quality: ").append(pidBlock.qualityScore).append("\n");
        msg.append("Timestamp: ").append(pidBlock.timestamp).append("\n\n");
        msg.append("PID XML (inner):\n");
        String pidXml = pidBlock.pidXml;
        msg.append(pidXml.length() > 300 ? pidXml.substring(0, 300) + "…" : pidXml);
        msg.append("\n\nPidData XML (outer):\n");
        String pidDataXml = pidBlock.pidDataXml;
        msg.append(pidDataXml.length() > 400 ? pidDataXml.substring(0, 400) + "…" : pidDataXml);
        msg.append("\n\nSaved: last_capture.jpg, last_pid.xml, last_piddata.xml");

        new AlertDialog.Builder(this)
                .setTitle("PID Block Generated")
                .setMessage(msg.toString())
                .setPositiveButton("OK", (d, which) -> {
                    d.dismiss();
                    captured = false;
                    captureBtn.setText("Waiting for face…");
                    captureBtn.setEnabled(false);
                    livenessChecker.reset();
                })
                .show();
    }

    // ------------------------------------------------------------------ oval overlay

    private void drawOvalGuide(boolean aligned) {
        SurfaceHolder holder = overlayView.getHolder();
        if (holder == null) return;
        Canvas canvas;
        try { canvas = holder.lockCanvas(); } catch (Exception e) { return; }
        if (canvas == null) return;

        try {
            canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR);

            float w = canvas.getWidth();
            float h = canvas.getHeight();
            float cx = w / 2f;
            float cy = h * 0.38f;

            float ovalW = w * 0.55f;
            float ovalH = ovalW * 1.35f;
            RectF oval = new RectF(cx - ovalW / 2, cy - ovalH / 2, cx + ovalW / 2, cy + ovalH / 2);

            // Mask outside oval
            Paint maskPaint = new Paint();
            maskPaint.setColor(0x88000000);
            maskPaint.setStyle(Paint.Style.FILL);
            canvas.save();
            Path clipPath = new Path();
            clipPath.addOval(oval, Path.Direction.CW);
            canvas.clipOutPath(clipPath);
            canvas.drawRect(0, 0, w, h, maskPaint);
            canvas.restore();

            // Oval border
            Paint borderPaint = new Paint();
            borderPaint.setColor(aligned ? 0xFF4CAF50 : 0xFFFFFFFF);
            borderPaint.setStyle(Paint.Style.STROKE);
            borderPaint.setStrokeWidth(aligned ? 4f : 2.5f);
            borderPaint.setAntiAlias(true);
            canvas.drawOval(oval, borderPaint);

            // Alignment ticks
            Paint tickPaint = new Paint();
            tickPaint.setColor(aligned ? 0xFF4CAF50 : 0xFF90CAF9);
            tickPaint.setStrokeWidth(3f);
            tickPaint.setAntiAlias(true);
            float t = dp(16);
            float g = dp(4);
            canvas.drawLine(cx, oval.top - g, cx, oval.top - g - t, tickPaint);
            canvas.drawLine(cx, oval.bottom + g, cx, oval.bottom + g + t, tickPaint);
            canvas.drawLine(oval.left - g, cy, oval.left - g - t, cy, tickPaint);
            canvas.drawLine(oval.right + g, cy, oval.right + g + t, tickPaint);
        } finally {
            holder.unlockCanvasAndPost(canvas);
        }
    }

    // ------------------------------------------------------------------ TextureView

    @Override
    public void onSurfaceTextureAvailable(SurfaceTexture st, int w, int h) {
        if (cameraDevice == null) openCamera();
    }
    @Override public void onSurfaceTextureSizeChanged(SurfaceTexture st, int w, int h) {}
    @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture st) {
        analyzing = false;
        return true;
    }
    @Override public void onSurfaceTextureUpdated(SurfaceTexture st) {}

    // ------------------------------------------------------------------ lifecycle

    @Override
    protected void onDestroy() {
        super.onDestroy();
        analyzing = false;
        if (captureSession != null) try { captureSession.close(); } catch (Exception ignored) {}
        if (cameraDevice != null) try { cameraDevice.close(); } catch (Exception ignored) {}
        if (bgThread != null) bgThread.quitSafely();
    }

    private void startBgThread() {
        bgThread = new HandlerThread("FaceCapture");
        bgThread.start();
        bgHandler = new Handler(bgThread.getLooper());
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
