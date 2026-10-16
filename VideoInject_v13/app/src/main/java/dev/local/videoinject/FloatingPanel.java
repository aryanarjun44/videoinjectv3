package dev.local.videoinject;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.graphics.SurfaceTexture;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.graphics.Outline;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.TextureView;
import android.view.ViewOutlineProvider;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;
import java.util.Locale;

/**
 * Runs inside the chosen app. A small round button floats on top of every screen; tap it to open a
 * control panel (arrows, zoom, face size, rotate, mirror). Changes show in the video at once and are
 * remembered. It needs no extra permission because it lives inside the app's own window.
 */
final class FloatingPanel {

    private static final String TAG = "vinject_panel";
    private static int btnX = -1, btnY = -1;
    private static boolean panelOnTop = false;

    private FloatingPanel() {}

    static void attach(final Activity a) {
        File dir = a.getFilesDir();
        if (!new File(dir, VideoInjector.VIDEO_NAME).isFile()) return;
        if ("0".equals(VideoInjector.readCfg(dir).getProperty("panel", "0").trim())) return;
        final ViewGroup decor = (ViewGroup) a.getWindow().getDecorView();
        if (decor.findViewWithTag(TAG) != null) return;
        new Ui(a, decor).build();
    }

    private static final class Ui {
        final Activity act;
        final ViewGroup decor;
        final float d;
        FrameLayout root;
        TextView fab, label;
        LinearLayout panel;
        TextureView miniView;
        SurfaceTexture miniTex;
        Surface miniSurface;
        GlVideoRenderer mini;
        View guideView;
        View miniMarks;
        final Handler main = new Handler(Looper.getMainLooper());

        Ui(Activity a, ViewGroup decor) {
            this.act = a;
            this.decor = decor;
            this.d = a.getResources().getDisplayMetrics().density;
        }

        int dp(int v) {
            return Math.round(v * d);
        }

        GradientDrawable bg(int color, int radius) {
            GradientDrawable g = new GradientDrawable();
            g.setColor(color);
            g.setCornerRadius(dp(radius));
            return g;
        }

        void build() {
            root = new FrameLayout(act);
            root.setTag(TAG);
            decor.addView(root, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));

            // ---- face guide marks (hidden at first), drawn over the app's round window
            guideView = new View(act) {
                @Override
                protected void onDraw(android.graphics.Canvas canvas) {
                    float[] c = AutoCal.fallbackCircle(act);
                    GuideDraw.draw(canvas, c[0], c[1], c[2], d);
                }
            };
            guideView.setVisibility(View.GONE);
            guideView.setClickable(false);
            root.addView(guideView, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));

            // ---- control panel (hidden at first)
            panel = new LinearLayout(act);
            panel.setOrientation(LinearLayout.VERTICAL);
            panel.setBackground(bg(0xEE14161C, 18));
            panel.setPadding(dp(12), dp(10), dp(12), dp(12));
            panel.setVisibility(View.GONE);
            panel.setClickable(true);
            root.addView(panel, panelParams());

            LinearLayout head = new LinearLayout(act);
            head.setOrientation(LinearLayout.HORIZONTAL);
            head.setGravity(Gravity.CENTER_VERTICAL);
            TextView title = text("Video fit", 14, true);
            head.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            TextView move = button("↕ panel", 12);
            move.setOnClickListener(v -> {
                panelOnTop = !panelOnTop;
                panel.setLayoutParams(panelParams());
            });
            head.addView(move);
            TextView hide = button("Hide button", 12);
            hide.setOnClickListener(v -> {
                VideoInjector.panelOff = true;
                VideoInjector.edit(act, f -> { });
                stopMini();
                decor.removeView(root);
            });
            LinearLayout.LayoutParams hl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            hl.leftMargin = dp(8);
            head.addView(hide, hl);
            TextView close = button("✕", 14);
            close.setOnClickListener(v -> panel.setVisibility(View.GONE));
            LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            cl.leftMargin = dp(8);
            head.addView(close, cl);
            panel.addView(head);

            label = text("", 12, false);
            label.setTextColor(0xFFB8BECC);
            label.setPadding(0, dp(6), 0, dp(4));
            panel.addView(label);

            // small round preview of the result ("simulation") that follows every change
            FrameLayout sim = new FrameLayout(act);
            sim.setBackground(bg(0xFFEFEFEA, 80));
            sim.setClipToOutline(true);
            sim.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View v, Outline o) {
                    o.setOval(0, 0, v.getWidth(), v.getHeight());
                }
            });
            miniView = new TextureView(act);
            miniView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
                @Override
                public void onSurfaceTextureAvailable(SurfaceTexture t, int w, int h) {
                    miniTex = t;
                    startMini();
                }

                @Override
                public void onSurfaceTextureSizeChanged(SurfaceTexture t, int w, int h) {
                }

                @Override
                public boolean onSurfaceTextureDestroyed(SurfaceTexture t) {
                    stopMini();
                    miniTex = null;
                    return true;
                }

                @Override
                public void onSurfaceTextureUpdated(SurfaceTexture t) {
                }
            });
            sim.addView(miniView, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));
            View ring = new View(act);
            GradientDrawable rg = new GradientDrawable();
            rg.setShape(GradientDrawable.OVAL);
            rg.setColor(Color.TRANSPARENT);
            rg.setStroke(dp(4), 0xFFFF3B14);
            ring.setBackground(rg);
            sim.addView(ring, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));
            miniMarks = new View(act) {
                @Override
                protected void onDraw(android.graphics.Canvas canvas) {
                    GuideDraw.draw(canvas, getWidth() / 2f, getHeight() / 2f, Math.min(getWidth(), getHeight()), d);
                }
            };
            miniMarks.setVisibility(View.GONE);
            sim.addView(miniMarks, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));
            LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(dp(116), dp(116));
            sp.gravity = Gravity.CENTER_HORIZONTAL;
            sp.topMargin = dp(4);
            sp.bottomMargin = dp(4);
            panel.addView(sim, sp);

            panel.addView(row(btn("Face guide marks", () -> {
                boolean on = guideView.getVisibility() != View.VISIBLE;
                guideView.setVisibility(on ? View.VISIBLE : View.GONE);
                miniMarks.setVisibility(on ? View.VISIBLE : View.GONE);
                guideView.invalidate();
            })));

            // arrows
            panel.addView(row(spacer(), rep("▲", () -> move(0, -1)), spacer()));
            panel.addView(row(rep("◀", () -> move(-1, 0)), btn("Reset", () -> edit(f -> {
                f.zoom = 1f;
                f.offX = 0f;
                f.offY = 0f;
                f.aspect = 1f;
            })), rep("▶", () -> move(1, 0))));
            panel.addView(row(spacer(), rep("▼", () -> move(0, 1)), spacer()));
            panel.addView(row(rep("Zoom −", () -> edit(f -> f.zoom *= 0.975f)),
                    rep("Zoom +", () -> edit(f -> f.zoom *= 1.025f))));
            panel.addView(row(rep("Face −", () -> edit(f -> f.fill *= 0.985f)),
                    rep("Face +", () -> edit(f -> f.fill *= 1.015f))));
            panel.addView(row(rep("Narrower", () -> { VideoInjector.userTuned = true; edit(f -> f.aspect *= 0.985f); }),
                    rep("Wider", () -> { VideoInjector.userTuned = true; edit(f -> f.aspect *= 1.015f); }),
                    btn("Auto-calibrate", () -> {
                        label.setText("Measuring... the picture will flash for a second.");
                        AutoCal.run(act, msg -> {
                            if (mini != null) mini.setFit(VideoInjector.currentFit(act));
                            refreshLabel();
                            label.setText(label.getText() + "\n" + msg);
                            if (guideView != null) guideView.invalidate();
                        });
                    })));
            panel.addView(row(btn("Rotate 90°", () -> edit(f -> f.rot = GlVideoRenderer.normRot(f.rot + 90))),
                    btn("Mirror", () -> edit(f -> f.mirror = !f.mirror))));

            // ---- floating button
            fab = new TextView(act);
            fab.setText("◎");
            fab.setTextColor(Color.WHITE);
            fab.setTextSize(22);
            fab.setGravity(Gravity.CENTER);
            fab.setBackground(bg(0xCC3D5AFE, 30));
            int size = dp(52);
            FrameLayout.LayoutParams fl = new FrameLayout.LayoutParams(size, size);
            fl.gravity = Gravity.TOP | Gravity.START;
            DisplaySize ds = new DisplaySize();
            if (btnX < 0) {
                btnX = Math.max(0, ds.w - size - dp(8));
                btnY = Math.round(ds.h * 0.35f);
            }
            fl.leftMargin = btnX;
            fl.topMargin = btnY;
            root.addView(fab, fl);

            fab.setOnTouchListener(new View.OnTouchListener() {
                float sx, sy;
                int bx, by;
                boolean dragged;

                @Override
                public boolean onTouch(View v, MotionEvent e) {
                    FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) v.getLayoutParams();
                    switch (e.getActionMasked()) {
                        case MotionEvent.ACTION_DOWN:
                            sx = e.getRawX();
                            sy = e.getRawY();
                            bx = lp.leftMargin;
                            by = lp.topMargin;
                            dragged = false;
                            return true;
                        case MotionEvent.ACTION_MOVE:
                            float dx = e.getRawX() - sx;
                            float dy = e.getRawY() - sy;
                            if (Math.abs(dx) > dp(8) || Math.abs(dy) > dp(8)) dragged = true;
                            if (dragged) {
                                lp.leftMargin = Math.max(0, Math.round(bx + dx));
                                lp.topMargin = Math.max(0, Math.round(by + dy));
                                btnX = lp.leftMargin;
                                btnY = lp.topMargin;
                                v.setLayoutParams(lp);
                            }
                            return true;
                        case MotionEvent.ACTION_UP:
                            if (!dragged) {
                                boolean show = panel.getVisibility() != View.VISIBLE;
                                panel.setVisibility(show ? View.VISIBLE : View.GONE);
                                if (show) refreshLabel();
                            }
                            return true;
                        default:
                            return true;
                    }
                }
            });
        }

        final class DisplaySize {
            final int w = act.getResources().getDisplayMetrics().widthPixels;
            final int h = act.getResources().getDisplayMetrics().heightPixels;
        }

        FrameLayout.LayoutParams panelParams() {
            FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            p.gravity = (panelOnTop ? Gravity.TOP : Gravity.BOTTOM) | Gravity.CENTER_HORIZONTAL;
            p.setMargins(dp(12), dp(40), dp(12), dp(24));
            return p;
        }

        TextView text(String s, int sp, boolean bold) {
            TextView t = new TextView(act);
            t.setText(s);
            t.setTextSize(sp);
            t.setTextColor(Color.WHITE);
            if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
            return t;
        }

        TextView button(String s, int sp) {
            TextView t = text(s, sp, true);
            t.setGravity(Gravity.CENTER);
            t.setPadding(dp(12), dp(9), dp(12), dp(9));
            t.setBackground(bg(0xFF2A2F44, 12));
            t.setClickable(true);
            return t;
        }

        View spacer() {
            return new View(act);
        }

        LinearLayout row(View... views) {
            LinearLayout r = new LinearLayout(act);
            r.setOrientation(LinearLayout.HORIZONTAL);
            r.setGravity(Gravity.CENTER);
            for (View v : views) {
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                lp.setMargins(dp(3), dp(3), dp(3), dp(3));
                r.addView(v, lp);
            }
            return r;
        }

        TextView btn(String s, final Runnable r) {
            TextView t = button(s, 14);
            t.setOnClickListener(v -> r.run());
            return t;
        }

        /** Button that keeps repeating while it is held down. */
        TextView rep(String s, final Runnable r) {
            final TextView t = button(s, 15);
            final Runnable loop = new Runnable() {
                @Override
                public void run() {
                    r.run();
                    main.postDelayed(this, 60);
                }
            };
            t.setOnTouchListener((v, e) -> {
                int a = e.getActionMasked();
                if (a == MotionEvent.ACTION_DOWN) {
                    t.setAlpha(0.6f);
                    main.post(loop);
                } else if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) {
                    t.setAlpha(1f);
                    main.removeCallbacks(loop);
                }
                return true;
            });
            return t;
        }

        void move(int dx, int dy) {
            edit(f -> {
                f.offX += dx * 0.012f;
                f.offY += dy * 0.012f;
            });
        }

        void startMini() {
            stopMini();
            try {
                File v = new File(act.getFilesDir(), VideoInjector.VIDEO_NAME);
                if (miniTex == null || !v.isFile()) return;
                miniSurface = new Surface(miniTex);
                mini = new GlVideoRenderer(miniSurface, v.getAbsolutePath(), true, VideoInjector.currentFit(act),
                        new GlVideoRenderer.Callback() {
                            @Override
                            public void onStatus(String msg) {
                            }

                            @Override
                            public void onFailed(String why, boolean wasShowing) {
                            }
                        }, null);
                mini.start();
            } catch (Throwable ignored) {
            }
        }

        void stopMini() {
            try {
                if (mini != null) mini.release();
            } catch (Throwable ignored) {
            }
            mini = null;
            try {
                if (miniSurface != null) miniSurface.release();
            } catch (Throwable ignored) {
            }
            miniSurface = null;
        }

        void edit(final VideoInjector.Edit e) {
            VideoInjector.edit(act, e);
            if (mini != null) mini.setFit(VideoInjector.currentFit(act));
            refreshLabel();
        }

        void refreshLabel() {
            GlVideoRenderer.Fit f = VideoInjector.currentFit(act);
            String live = VideoInjector.smartFitActive() ? "" : "\nCamera preview is not running yet. Open the camera screen first.";
            label.setText(String.format(Locale.US, "Zoom %d%%   Stretch %d%%   Face %d%%   Left/Right %+.2f   Up/Down %+.2f   Turn %d°%s",
                    Math.round(f.zoom * 100), Math.round(f.aspect * 100), Math.round(f.fill * 100), f.offX, f.offY, f.rot, live));
        }
    }
}
