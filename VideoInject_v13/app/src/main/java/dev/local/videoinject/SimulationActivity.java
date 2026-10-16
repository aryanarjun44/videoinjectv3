package dev.local.videoinject;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.SurfaceTexture;
import android.os.Bundle;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

import java.io.File;

/**
 * Shows the video inside a round preview exactly the way the chosen app will show it (same renderer).
 * Move the sliders until the face sits nicely, then "Save as final output".
 */
public class SimulationActivity extends Activity {

    private static final String PREFS = "config";
    private static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT;
    private static final int WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;

    private static final String[] SHAPES = {"Square 1:1", "Portrait 3:4", "Landscape 4:3", "Tall 9:16"};
    private static final float[] ASPECT = {1f, 0.75f, 1.3333f, 0.5625f};

    /** Looks like the app's round preview: light page, thick orange-red ring. Same circle size the renderer uses. */
    private static final class GuideView extends View {
        private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        boolean marks = true;

        GuideView(android.content.Context c) {
            super(c);
            bgPaint.setColor(0xFFEFEFEA);
            bgPaint.setStyle(Paint.Style.FILL);
            ring.setColor(0xFFFF3B14);
            ring.setStyle(Paint.Style.STROKE);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float w = getWidth(), h = getHeight();
            float r = Math.min(w, h) / 2f;
            ring.setStrokeWidth(r * 0.05f);
            path.reset();
            path.setFillType(Path.FillType.EVEN_ODD);
            path.addRect(0, 0, w, h, Path.Direction.CW);
            path.addCircle(w / 2f, h / 2f, r - ring.getStrokeWidth() / 2f, Path.Direction.CW);
            canvas.drawPath(path, bgPaint);
            canvas.drawCircle(w / 2f, h / 2f, r - ring.getStrokeWidth() / 2f, ring);
            if (marks) GuideDraw.draw(canvas, w / 2f, h / 2f, r * 2f, getResources().getDisplayMetrics().density);
        }
    }

    private SharedPreferences sp;
    private Ui ui;
    private GlVideoRenderer.Fit fit;
    private GlVideoRenderer renderer;
    private Surface surface;
    private SurfaceTexture surfaceTexture;
    private TextureView tv;
    private FrameLayout stage;
    private TextView info, status;
    private GuideView guide;
    private Ctl cZoom, cComp, cFill, cX, cY, cAsp;
    private Switch swMirror;
    private File video;
    private int shape;
    private boolean ready;
    private boolean updating;
    private boolean live;
    private boolean pushing, pushAgain;
    private final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable livePush = new Runnable() {
        @Override
        public void run() {
            pushLive();
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        sp = getSharedPreferences(PREFS, MODE_PRIVATE);
        ui = new Ui(this);
        ui.system(getWindow());
        fit = GlVideoRenderer.Fit.fromPrefs(sp);
        shape = sp.getInt("sim_shape", 0);
        if (shape < 0 || shape >= SHAPES.length) shape = 0;
        video = new File(getFilesDir(), "video.mp4");
        build();
        syncControls();
    }

    private LinearLayout.LayoutParams lp(int w, int h, int l, int t, int r, int bm) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w < 0 ? w : ui.dp(w), h < 0 ? h : ui.dp(h));
        p.setMargins(ui.dp(l), ui.dp(t), ui.dp(r), ui.dp(bm));
        return p;
    }

    private void build() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(ui.bg);
        root.setPadding(ui.dp(16), ui.dp(24), ui.dp(16), 0);

        root.addView(ui.label("Fit simulation", 24, ui.text, true));
        TextView sub = ui.label("This is what the app will show. Drag the picture with a finger, pinch to zoom.", 13, ui.sub, false);
        sub.setPadding(0, ui.dp(2), 0, ui.dp(10));
        root.addView(sub);

        stage = new FrameLayout(this);
        stage.setBackground(ui.round(0xFF000000, 12));
        tv = new TextureView(this);
        stage.addView(tv, new FrameLayout.LayoutParams(MATCH, MATCH));
        guide = new GuideView(this);
        stage.addView(guide, new FrameLayout.LayoutParams(MATCH, MATCH));
        root.addView(stage, stageParams());

        // Touch the circle like a real camera app: drag with one finger to move, pinch with two to zoom.
        final android.view.ScaleGestureDetector scaler = new android.view.ScaleGestureDetector(this,
                new android.view.ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    @Override
                    public boolean onScale(android.view.ScaleGestureDetector d) {
                        fit.zoom = GlVideoRenderer.clamp(fit.zoom * d.getScaleFactor(), 0.1f, 3f);
                        return true;
                    }
                });
        stage.setOnTouchListener(new View.OnTouchListener() {
            private float lx, ly;
            private boolean drag;

            @Override
            public boolean onTouch(View v, android.view.MotionEvent e) {
                scaler.onTouchEvent(e);
                int a = e.getActionMasked();
                if (a == android.view.MotionEvent.ACTION_DOWN) {
                    lx = e.getX();
                    ly = e.getY();
                    drag = true;
                } else if (a == android.view.MotionEvent.ACTION_MOVE && drag && e.getPointerCount() == 1
                        && !scaler.isInProgress()) {
                    float d = Math.min(v.getWidth(), v.getHeight()) / 2f;
                    fit.offX = GlVideoRenderer.clamp(fit.offX + (e.getX() - lx) / d, -2f, 2f);
                    fit.offY = GlVideoRenderer.clamp(fit.offY + (e.getY() - ly) / d, -2f, 2f);
                    lx = e.getX();
                    ly = e.getY();
                } else if (a == android.view.MotionEvent.ACTION_POINTER_UP || a == android.view.MotionEvent.ACTION_UP) {
                    if (e.getPointerCount() > 1) {
                        int keep = e.getActionIndex() == 0 ? 1 : 0;
                        lx = e.getX(keep);
                        ly = e.getY(keep);
                    }
                    drag = a == android.view.MotionEvent.ACTION_POINTER_UP;
                }
                syncControls();
                push();
                return true;
            }
        });

        tv.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(SurfaceTexture t, int w, int h) {
                surfaceTexture = t;
                startRenderer();
            }

            @Override
            public void onSurfaceTextureSizeChanged(SurfaceTexture t, int w, int h) {
                startRenderer();
            }

            @Override
            public boolean onSurfaceTextureDestroyed(SurfaceTexture t) {
                stopRenderer();
                surfaceTexture = null;
                return true;
            }

            @Override
            public void onSurfaceTextureUpdated(SurfaceTexture t) {
            }
        });

        ScrollView scroll = new ScrollView(this);
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(0, ui.dp(12), 0, ui.dp(24));
        scroll.addView(panel, new ViewGroup.LayoutParams(MATCH, WRAP));
        root.addView(scroll, new LinearLayout.LayoutParams(MATCH, 0, 1f));

        status = ui.label("", 13, ui.sub, false);
        panel.addView(status);
        if (!video.isFile()) status.setText("Choose a video on the home screen first.");

        LinearLayout c = ui.card();
        c.setPadding(ui.dp(16), ui.dp(10), ui.dp(16), ui.dp(14));
        cZoom = new Ctl(c, "Zoom  (small = zoom out, 10% to 300%)", 1000, 15) {
            double val(int p) { return 0.1 * Math.pow(30.0, p / 1000.0); }
            int prog(double v) { return (int) Math.round(Math.log(v / 0.1) / Math.log(30.0) * 1000.0); }
            void set(double v) { fit.zoom = (float) v; }
            double get() { return fit.zoom; }
            String show(double v) { return Math.round(v * 100) + "%"; }
        };
        cFill = new Ctl(c, "Face size inside the circle", 1000, 20) {
            double val(int p) { return 0.3 + 1.2 * p / 1000.0; }
            int prog(double v) { return (int) Math.round((v - 0.3) / 1.2 * 1000.0); }
            void set(double v) { fit.fill = (float) v; }
            double get() { return fit.fill; }
            String show(double v) { return Math.round(v * 100) + "% of the circle"; }
        };
        cX = new Ctl(c, "Left  \u2194  Right", 800, 8) {
            double val(int p) { return (p - 400) / 200.0; }
            int prog(double v) { return (int) Math.round(v * 200.0) + 400; }
            void set(double v) { fit.offX = (float) v; }
            double get() { return fit.offX; }
            String show(double v) { return String.format(java.util.Locale.US, "%+.2f", v); }
        };
        cY = new Ctl(c, "Up  \u2194  Down", 800, 8) {
            double val(int p) { return (p - 400) / 200.0; }
            int prog(double v) { return (int) Math.round(v * 200.0) + 400; }
            void set(double v) { fit.offY = (float) v; }
            double get() { return fit.offY; }
            String show(double v) { return String.format(java.util.Locale.US, "%+.2f", v); }
        };
        cComp = new Ctl(c, "Match the app  (lower it if the app zooms in by itself)", 1000, 15) {
            double val(int p) { return 0.1 * Math.pow(20.0, p / 1000.0); }
            int prog(double v) { return (int) Math.round(Math.log(v / 0.1) / Math.log(20.0) * 1000.0); }
            void set(double v) { fit.comp = (float) v; }
            double get() { return fit.comp; }
            String show(double v) { return Math.round(v * 100) + "%"; }
        };
        cAsp = new Ctl(c, "Stretch fix  (picture looks too tall or too wide)", 1000, 15) {
            double val(int p) { return 0.4 * Math.pow(6.25, p / 1000.0); }
            int prog(double v) { return (int) Math.round(Math.log(v / 0.4) / Math.log(6.25) * 1000.0); }
            void set(double v) { fit.aspect = (float) v; }
            double get() { return fit.aspect; }
            String show(double v) { return Math.round(v * 100) + "%"; }
        };
        info = ui.label("", 12, ui.sub, false);
        info.setPadding(0, ui.dp(8), 0, 0);
        c.addView(info);
        panel.addView(c, lp(MATCH, WRAP, 0, 12, 0, 0));

        LinearLayout row1 = new LinearLayout(this);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        Button rot = ui.button("Rotate 90°", false);
        rot.setOnClickListener(v -> {
            fit.rot = GlVideoRenderer.normRot(fit.rot + 90);
            push();
        });
        row1.addView(rot, lp(0, WRAP, 0, 0, 6, 0));
        ((LinearLayout.LayoutParams) rot.getLayoutParams()).weight = 1f;
        Button shapeBtn = ui.button("Frame: " + SHAPES[shape], false);
        shapeBtn.setOnClickListener(v -> {
            shape = (shape + 1) % SHAPES.length;
            sp.edit().putInt("sim_shape", shape).apply();
            ((Button) v).setText("Frame: " + SHAPES[shape]);
            stage.setLayoutParams(stageParams());
        });
        row1.addView(shapeBtn, lp(0, WRAP, 6, 0, 0, 0));
        ((LinearLayout.LayoutParams) shapeBtn.getLayoutParams()).weight = 1f;
        panel.addView(row1, lp(MATCH, WRAP, 0, 12, 0, 0));

        swMirror = new Switch(this);
        swMirror.setText("Mirror (flip left/right)");
        swMirror.setTextColor(ui.text);
        swMirror.setTextSize(15);
        swMirror.setOnCheckedChangeListener((CompoundButton v, boolean on) -> {
            if (updating) return;
            fit.mirror = on;
            push();
        });
        panel.addView(swMirror, lp(MATCH, WRAP, 4, 14, 4, 0));

        Switch swMarks = new Switch(this);
        swMarks.setText("Face guide marks (eyes, nose, mouth)");
        swMarks.setTextColor(ui.text);
        swMarks.setTextSize(15);
        swMarks.setChecked(true);
        swMarks.setOnCheckedChangeListener((CompoundButton v, boolean on) -> {
            guide.marks = on;
            guide.invalidate();
        });
        panel.addView(swMarks, lp(MATCH, WRAP, 4, 10, 4, 0));

        Switch swLive = new Switch(this);
        swLive.setText("Show every change live inside the app (root)");
        swLive.setTextColor(ui.text);
        swLive.setTextSize(15);
        swLive.setOnCheckedChangeListener((CompoundButton v, boolean on) -> {
            live = on;
            if (on) {
                if (sp.getString("target", "").isEmpty() || !sp.getBoolean("applied", false)) {
                    status.setText("Live mode needs: app chosen and Apply done once on the home screen. "
                            + "Then open the app's camera screen and watch it change while you move the sliders.");
                }
                scheduleLive();
            }
        });
        panel.addView(swLive, lp(MATCH, WRAP, 4, 10, 4, 0));

        LinearLayout row2 = new LinearLayout(this);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        Button auto = ui.button("Auto-fit face", false);
        auto.setOnClickListener(v -> autoFit());
        row2.addView(auto, lp(0, WRAP, 0, 0, 6, 0));
        ((LinearLayout.LayoutParams) auto.getLayoutParams()).weight = 1f;
        Button reset = ui.button("Reset", false);
        reset.setOnClickListener(v -> {
            fit.zoom = 1f;
            fit.offX = 0f;
            fit.offY = 0f;
            fit.fill = 0.70f;
            fit.comp = 1f;
            fit.aspect = 1f;
            syncControls();
            push();
        });
        row2.addView(reset, lp(0, WRAP, 6, 0, 0, 0));
        ((LinearLayout.LayoutParams) reset.getLayoutParams()).weight = 1f;
        panel.addView(row2, lp(MATCH, WRAP, 0, 12, 0, 0));

        Button save = ui.button("Save as final output", true);
        save.setOnClickListener(v -> {
            SharedPreferences.Editor e = sp.edit();
            fit.toPrefs(e);
            e.apply();
            setResult(RESULT_OK);
            finish();
        });
        panel.addView(save, lp(MATCH, WRAP, 0, 16, 0, 0));

        setContentView(root);
    }

    private LinearLayout.LayoutParams stageParams() {
        int screenW = getResources().getDisplayMetrics().widthPixels - ui.dp(32);
        int screenH = getResources().getDisplayMetrics().heightPixels;
        float a = ASPECT[shape];
        int w = screenW;
        int h = Math.round(w / a);
        int maxH = Math.round(screenH * 0.46f);
        if (h > maxH) {
            h = maxH;
            w = Math.round(h * a);
        }
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h);
        p.gravity = android.view.Gravity.CENTER_HORIZONTAL;
        return p;
    }

    /** One control: title + value, a slider and small - / + buttons for fine steps. */
    private abstract class Ctl {
        final SeekBar sb;
        final TextView value;
        final int step;

        abstract double val(int p);
        abstract int prog(double v);
        abstract void set(double v);
        abstract double get();
        abstract String show(double v);

        Ctl(LinearLayout parent, String title, int max, int stepSize) {
            step = stepSize;
            LinearLayout head = new LinearLayout(SimulationActivity.this);
            head.setOrientation(LinearLayout.HORIZONTAL);
            head.setPadding(0, ui.dp(12), 0, 0);
            TextView t = ui.label(title, 13, ui.text, true);
            head.addView(t, new LinearLayout.LayoutParams(0, WRAP, 1f));
            value = ui.label("", 13, ui.accent, true);
            head.addView(value);
            parent.addView(head);

            LinearLayout line = new LinearLayout(SimulationActivity.this);
            line.setOrientation(LinearLayout.HORIZONTAL);
            line.setGravity(android.view.Gravity.CENTER_VERTICAL);
            Button minus = ui.button("\u2212", false);
            Button plus = ui.button("+", false);
            minus.setPadding(ui.dp(14), ui.dp(6), ui.dp(14), ui.dp(6));
            plus.setPadding(ui.dp(14), ui.dp(6), ui.dp(14), ui.dp(6));
            sb = new SeekBar(SimulationActivity.this);
            sb.setMax(max);
            line.addView(minus, new LinearLayout.LayoutParams(WRAP, WRAP));
            line.addView(sb, new LinearLayout.LayoutParams(0, WRAP, 1f));
            line.addView(plus, new LinearLayout.LayoutParams(WRAP, WRAP));
            parent.addView(line);

            sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                    if (!fromUser) return;
                    set(val(p));
                    value.setText(show(get()));
                    push();
                }

                @Override
                public void onStartTrackingTouch(SeekBar s) {
                }

                @Override
                public void onStopTrackingTouch(SeekBar s) {
                }
            });
            minus.setOnClickListener(v -> nudge(-step));
            plus.setOnClickListener(v -> nudge(step));
        }

        void nudge(int d) {
            int p = Math.max(0, Math.min(sb.getMax(), sb.getProgress() + d));
            sb.setProgress(p);
            set(val(p));
            value.setText(show(get()));
            push();
        }

        void sync() {
            sb.setProgress(Math.max(0, Math.min(sb.getMax(), prog(get()))));
            value.setText(show(get()));
        }
    }

    private void syncControls() {
        updating = true;
        cZoom.sync();
        cFill.sync();
        cX.sync();
        cY.sync();
        cComp.sync();
        cAsp.sync();
        swMirror.setChecked(fit.mirror);
        updating = false;
        updateInfo();
    }

    private void updateInfo() {
        String face = fit.faceSize > 0.01f ? "face found" : "no face found (centred)";
        info.setText("Zoom " + Math.round(fit.zoom * 100) + "%  ·  turn " + fit.rot + "°  ·  " + face);
    }

    private void push() {
        updateInfo();
        if (renderer != null) renderer.setFit(fit);
        scheduleLive();
    }

    private void scheduleLive() {
        if (!live) return;
        main.removeCallbacks(livePush);
        main.postDelayed(livePush, 450);
    }

    private void pushLive() {
        if (!live) return;
        if (pushing) {
            pushAgain = true;
            return;
        }
        final String pkg = sp.getString("target", "");
        if (pkg.isEmpty()) return;
        pushing = true;
        final GlVideoRenderer.Fit f = fit.copy();
        final boolean en = sp.getBoolean("enabled", true);
        final boolean lo = sp.getBoolean("loop", true);
        new Thread(() -> {
            String out = "";
            try {
                out = RootCfg.push(SimulationActivity.this, pkg, f, en, lo);
            } catch (Exception e) {
                out = String.valueOf(e);
            }
            final String fo = out;
            runOnUiThread(() -> {
                pushing = false;
                if (!fo.contains("@@DONE@@")) {
                    status.setText("Live update failed (root or app folder). Do Apply on the home screen first.");
                }
                if (pushAgain) {
                    pushAgain = false;
                    scheduleLive();
                }
            });
        }).start();
    }

    // ------------------------------------------------------------------ renderer

    private void startRenderer() {
        stopRenderer();
        if (surfaceTexture == null || !video.isFile()) return;
        surface = new Surface(surfaceTexture);
        renderer = new GlVideoRenderer(surface, video.getAbsolutePath(), true, fit,
                new GlVideoRenderer.Callback() {
                    @Override
                    public void onStatus(final String msg) {
                        runOnUiThread(() -> status.setText("Playing. Drag the sliders until the face sits well in the circle."));
                    }

                    @Override
                    public void onFailed(final String why, boolean wasShowing) {
                        runOnUiThread(() -> status.setText("Simulation could not start: " + why));
                    }
                }, null);
        renderer.start();
        ready = true;
    }

    private void stopRenderer() {
        if (renderer != null) {
            renderer.release();
            renderer = null;
        }
        if (surface != null) {
            surface.release();
            surface = null;
        }
        ready = false;
    }

    private void autoFit() {
        if (!video.isFile()) return;
        status.setText("Looking for the face...");
        new Thread(() -> {
            final FaceFit.Result r = FaceFit.detect(video);
            runOnUiThread(() -> {
                fit.faceCx = r.cx;
                fit.faceCy = r.cy;
                fit.faceSize = r.found ? r.size : 0f;
                fit.rot = r.rot;
                fit.bg = r.bg;
                fit.zoom = 1f;
                fit.offX = 0f;
                fit.offY = 0f;
                fit.fill = 0.70f;
                sp.edit().putString("face_note", r.note).apply();
                status.setText(r.note);
                syncControls();
                push();
            });
        }).start();
    }

    @Override
    protected void onDestroy() {
        live = false;
        main.removeCallbacks(livePush);
        stopRenderer();
        super.onDestroy();
    }
}
