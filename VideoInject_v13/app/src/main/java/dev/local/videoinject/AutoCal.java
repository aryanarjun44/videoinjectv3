package dev.local.videoinject;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.view.PixelCopy;
import android.view.SurfaceView;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;

import java.util.ArrayDeque;
import java.util.Locale;

/**
 * Runs inside the chosen app. It does not guess how the app shows the camera: it measures it.
 * For a moment the preview shows a colour map (red = left to right, green = bottom to top). The app's own
 * window is copied twice (map and inverted map). Pixels that changed belong to our picture; their colours tell
 * exactly which part of the picture is visible in the app's round window, and how it is stretched.
 * From that the stretch, size, centre and mirroring are set so the video fills the circle without distortion.
 */
final class AutoCal {

    interface Done {
        void done(String msg);
    }

    private interface Grabbed {
        void got(Bitmap b);
    }

    private static boolean running;

    private AutoCal() {}

    static void run(final Activity act, final Done done) {
        final Handler main = new Handler(Looper.getMainLooper());
        if (running) {
            done.done("NO: already measuring");
            return;
        }
        final GlVideoRenderer r = VideoInjector.renderer();
        final int[] bs = VideoInjector.previewSize();
        final View decor = act.getWindow().getDecorView();
        if (r == null || bs == null) {
            done.done("NO: the camera preview is not running yet. Open the camera screen first.");
            return;
        }
        final int w = decor.getWidth(), h = decor.getHeight();
        if (w <= 0 || h <= 0) {
            done.done("NO: the screen is not ready");
            return;
        }
        running = true;
        VideoInjector.pendingKey = bs[0] + "x" + bs[1] + "@" + w + "x" + h;
        final View hide = decor.findViewWithTag("vinject_panel");
        if (hide != null) hide.setVisibility(View.INVISIBLE);
        final Runnable restore = new Runnable() {
            @Override
            public void run() {
                r.setCalMode(0);
                if (hide != null) hide.setVisibility(View.VISIBLE);
                running = false;
            }
        };
        r.setCalMode(2);
        main.postDelayed(new Runnable() {
            @Override
            public void run() {
                grab(act, main, w, h, new Grabbed() {
                    @Override
                    public void got(final Bitmap a) {
                        if (a == null) {
                            restore.run();
                            done.done("NO: could not copy the screen (the app may block screenshots).");
                            return;
                        }
                        r.setCalMode(3);
                        main.postDelayed(new Runnable() {
                            @Override
                            public void run() {
                                grab(act, main, w, h, new Grabbed() {
                                    @Override
                                    public void got(Bitmap b) {
                                        restore.run();
                                        if (b == null) {
                                            done.done("NO: could not copy the screen.");
                                            return;
                                        }
                                        String msg;
                                        try {
                                            msg = analyse(act, a, b, bs[0], bs[1]);
                                        } catch (Throwable t) {
                                            msg = "NO: " + t;
                                        }
                                        done.done(msg);
                                    }
                                });
                            }
                        }, 150);
                    }
                });
            }
        }, 150);
    }

    private static void grab(Activity act, Handler h, int w, int hh, final Grabbed g) {
        try {
            final Bitmap b = Bitmap.createBitmap(w, hh, Bitmap.Config.ARGB_8888);
            PixelCopy.request(act.getWindow(), b, new PixelCopy.OnPixelCopyFinishedListener() {
                @Override
                public void onPixelCopyFinished(int result) {
                    g.got(result == PixelCopy.SUCCESS ? b : null);
                }
            }, h);
        } catch (Throwable t) {
            g.got(null);
        }
    }

    private static String analyse(Activity act, Bitmap A, Bitmap B, final int W, final int H) {
        int w = A.getWidth(), h = A.getHeight();
        int[] pa = new int[w * h];
        int[] pb = new int[w * h];
        A.getPixels(pa, 0, w, 0, 0, w, h);
        B.getPixels(pb, 0, w, 0, 0, w, h);

        long n = 0;
        double sx = 0, sy = 0, sxx = 0, syy = 0, su = 0, sv = 0, sxu = 0, syv = 0, syu = 0, sxv = 0;
        int minx = Integer.MAX_VALUE, maxx = -1, miny = Integer.MAX_VALUE, maxy = -1;
        for (int y = 0; y < h; y += 2) {
            for (int x = 0; x < w; x += 2) {
                int a = pa[y * w + x];
                int b = pb[y * w + x];
                int ra = (a >> 16) & 255, ga = (a >> 8) & 255, ba = a & 255;
                int rb = (b >> 16) & 255, gb = (b >> 8) & 255, bb = b & 255;
                if (Math.abs(ra - rb) + Math.abs(ga - gb) < 60) continue;
                if (Math.abs(ba - 128) > 16 || Math.abs(bb - 128) > 16) continue;
                if (Math.abs(ra + rb - 255) > 24 || Math.abs(ga + gb - 255) > 24) continue;
                double u = ra / 255.0, v = ga / 255.0;
                n++;
                sx += x;
                sy += y;
                sxx += (double) x * x;
                syy += (double) y * y;
                su += u;
                sv += v;
                sxu += x * u;
                syu += y * u;
                sxv += x * v;
                syv += y * v;
                if (x < minx) minx = x;
                if (x > maxx) maxx = x;
                if (y < miny) miny = y;
                if (y > maxy) maxy = y;
            }
        }
        if (n < 1500) return "NO: the colour map was not visible on screen (found " + n + " pixels).";

        double mx = sx / n, my = sy / n, mu = su / n, mv = sv / n;
        double varx = sxx / n - mx * mx, vary = syy / n - my * my;
        double sux = (sxu / n - mx * mu) / varx;   // how u changes along screen x
        double suy = (syu / n - my * mu) / vary;   // ... along screen y (should be ~0)
        double svy = (syv / n - my * mv) / vary;   // how v changes along screen y
        double svx = (sxv / n - mx * mv) / varx;   // ... along screen x (should be ~0)
        if (Math.abs(sux) < 3 * Math.abs(suy) || Math.abs(svy) < 3 * Math.abs(svx)) {
            return "NO: the app shows the picture turned sideways. This is not handled yet.";
        }
        if (svy > 0) return "NO: the app shows the picture upside down. This is not handled yet.";

        double pw = maxx - minx + 2, ph = maxy - miny + 2;
        double cxs = (minx + maxx) / 2.0, cys = (miny + maxy) / 2.0;
        double du = Math.abs(sux) * pw;
        double dv = Math.abs(svy) * ph;
        double cu = mu + sux * (cxs - mx);
        double cv = mv + svy * (cys - my);

        float D = Math.min(W, H);
        final float rx = (float) (du * W / 2.0);
        final float ry = (float) (dv * H / 2.0);
        final float aspect = GlVideoRenderer.clamp(rx / ry, 0.3f, 3f);
        final float comp = GlVideoRenderer.clamp(2f * ry / D, 0.05f, 5f);
        final float offX = GlVideoRenderer.clamp((float) (2.0 * (cu - 0.5) * W / D), -6f, 6f);
        final float offY = GlVideoRenderer.clamp((float) (-2.0 * (cv - 0.5) * H / D), -6f, 6f);
        final boolean mirror = sux < 0;

        VideoInjector.calKey = VideoInjector.pendingKey;
        VideoInjector.circle = new float[]{(float) cxs, (float) cys, (float) Math.min(pw, ph)};
        VideoInjector.edit(act, new VideoInjector.Edit() {
            @Override
            public void apply(GlVideoRenderer.Fit f) {
                f.aspect = aspect;
                f.comp = comp;
                f.offX = offX;
                f.offY = offY;
                f.mirror = mirror;
            }
        });
        return String.format(Locale.US,
                "OK: window %dx%d px sees %d%% x %d%% of the %dx%d picture -> stretch %d%%, size %d%%, shift %+.2f/%+.2f%s",
                (int) pw, (int) ph, Math.round(du * 100), Math.round(dv * 100), W, H,
                Math.round(aspect * 100), Math.round(comp * 100), offX, offY, mirror ? ", mirrored" : "");
    }

    /** Best guess of the round window on screen (centre x, centre y, diameter) when nothing was measured yet. */
    static float[] fallbackCircle(Activity act) {
        float[] c = VideoInjector.circle;
        if (c != null) return c;
        try {
            ViewGroup decor = (ViewGroup) act.getWindow().getDecorView();
            Rect best = null;
            long bestArea = 0;
            ArrayDeque<View> stack = new ArrayDeque<>();
            stack.push(decor);
            while (!stack.isEmpty()) {
                View v = stack.pop();
                if ("vinject_panel".equals(v.getTag())) continue;
                if ((v instanceof SurfaceView || v instanceof TextureView) && v.isShown()) {
                    Rect r = new Rect();
                    if (v.getGlobalVisibleRect(r)) {
                        long a = (long) r.width() * r.height();
                        if (a > bestArea) {
                            bestArea = a;
                            best = r;
                        }
                    }
                }
                if (v instanceof ViewGroup) {
                    ViewGroup g = (ViewGroup) v;
                    for (int i = 0; i < g.getChildCount(); i++) stack.push(g.getChildAt(i));
                }
            }
            if (best != null) {
                return new float[]{best.exactCenterX(), best.exactCenterY(), Math.min(best.width(), best.height())};
            }
        } catch (Throwable ignored) {
        }
        float w = decor(act).getWidth(), h = decor(act).getHeight();
        return new float[]{w / 2f, h / 2f, Math.min(w, h) * 0.8f};
    }

    private static View decor(Activity a) {
        return a.getWindow().getDecorView();
    }
}
