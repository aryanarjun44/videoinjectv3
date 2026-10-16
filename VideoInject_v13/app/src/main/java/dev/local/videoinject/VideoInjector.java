package dev.local.videoinject;

import android.content.Context;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;
import android.view.Surface;
import android.view.View;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Properties;

import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Runs inside the target app. The module's settings screen (with root) puts vinject.mp4 and
 * vinject.cfg into the target app's own files folder, so the hook only reads local files and
 * the target app does not need any change. The hook also leaves a one-line vinject.status file
 * so the module's "Check setup" button can tell what happened last.
 */
final class VideoInjector {

    static final String VIDEO_NAME = "vinject.mp4";
    static final String CFG_NAME = "vinject.cfg";
    static final String STATUS_NAME = "vinject.status";

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static MediaPlayer player;
    private static GlVideoRenderer gl;
    private static GlVideoRenderer.Fit liveFit;
    private static Context panelCtx;
    static volatile boolean panelOff;
    static volatile boolean userTuned;
    static volatile float[] circle;
    private static GlVideoRenderer calDone;
    static volatile String calKey;
    static volatile String pendingKey;

    static GlVideoRenderer renderer() {
        return gl;
    }
    private static java.lang.ref.WeakReference<android.app.Activity> lastActivity;

    static void setActivity(android.app.Activity a) {
        lastActivity = new java.lang.ref.WeakReference<>(a);
    }

    static int[] previewSize() {
        GlVideoRenderer r = gl;
        if (r == null || r.lastW <= 0 || r.lastH <= 0) return null;
        return new int[]{r.lastW, r.lastH};
    }

    private final static Runnable AUTOCAL = new Runnable() {
        @Override
        public void run() {
            try {
                android.app.Activity a = lastActivity == null ? null : lastActivity.get();
                Context c = a != null ? a : appContext();
                if (a == null || userTuned || c == null) return;
                if ("0".equals(readCfg(c.getFilesDir()).getProperty("autocal", "1").trim())) return;
                if (gl == null || gl == calDone) return;
                final GlVideoRenderer cur = gl;
                // Measured once for this phone/app/preview size, then reused silently (no colour flash).
                int[] bs0 = previewSize();
                View dv = a.getWindow().getDecorView();
                if (bs0 != null && dv.getWidth() > 0) {
                    String key = bs0[0] + "x" + bs0[1] + "@" + dv.getWidth() + "x" + dv.getHeight();
                    String saved = readCfg(c.getFilesDir()).getProperty("calkey", "");
                    if (key.equals(saved.trim())) {
                        calKey = key;
                        calDone = cur;
                        note(c, "CAL", "OK: saved circle match reused (no re-measuring)");
                        return;
                    }
                }
                final Context cc = c;
                AutoCal.run(a, new AutoCal.Done() {
                    @Override
                    public void done(String msg) {
                        note(cc, "CAL", msg);
                        if (msg.startsWith("OK")) calDone = cur;
                    }
                });
            } catch (Throwable ignored) {
            }
        }
    };

    private static void noteCal(Context c, String line) {
        note(c, "CAL", line);
    }

    private static void note(Context ctx, String which, String msg) {
        try {
            String stamp = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date());
            FileOutputStream o = new FileOutputStream(new File(ctx.getFilesDir(), "vinject.cal"));
            try {
                o.write((stamp + "  " + msg + "\n").getBytes("UTF-8"));
            } finally {
                o.close();
            }
        } catch (Throwable ignored) {
        }
    }
    static volatile boolean noEnlarge;

    static float autoAspect() {
        GlVideoRenderer r = gl;
        if (r == null || r.lastW <= 0 || r.lastH <= 0) return 1f;
        return r.lastW / (float) r.lastH;
    }

    /**
     * Apps often give the camera preview a small surface (for example 640x480), which makes any picture
     * soft. When the module is active, ask for a bigger one (short side 1080) so the video stays sharp.
     * Returns null to keep the app's own size.
     */
    static int[] betterSize(int w, int h) {
        try {
            if (noEnlarge || w <= 0 || h <= 0) return null;
            int m = Math.min(w, h);
            if (m >= 1080 || m < 64) return null;
            Context c = appContext();
            if (c == null) return null;
            File video = new File(c.getFilesDir(), VIDEO_NAME);
            if (!video.isFile() || video.length() == 0) return null;
            Properties p = readCfg(c.getFilesDir());
            if ("0".equals(p.getProperty("enabled", "1").trim()) || "0".equals(p.getProperty("hq", "1").trim())) return null;
            float sc = 1080f / m;
            int nw = Math.round(w * sc);
            int nh = Math.round(h * sc);
            int mx = Math.max(nw, nh);
            if (mx > 2048) {
                float k = 2048f / mx;
                nw = Math.round(nw * k);
                nh = Math.round(nh * k);
            }
            return new int[]{nw, nh};
        } catch (Throwable t) {
            return null;
        }
    }

    interface Edit {
        void apply(GlVideoRenderer.Fit f);
    }

    static Properties readCfg(File dir) {
        Properties p = new Properties();
        File cfg = new File(dir, CFG_NAME);
        try {
            if (cfg.isFile()) {
                FileInputStream in = new FileInputStream(cfg);
                try {
                    p.load(in);
                } finally {
                    in.close();
                }
            }
        } catch (Throwable ignored) {
        }
        return p;
    }

    static synchronized GlVideoRenderer.Fit currentFit(Context ctx) {
        if (liveFit == null) liveFit = GlVideoRenderer.Fit.fromProps(readCfg(ctx.getFilesDir()));
        return liveFit.copy();
    }

    static boolean smartFitActive() {
        return gl != null;
    }

    /** Called by the floating panel: change the picture right now and remember it in the settings file. */
    static void edit(Context ctx, Edit e) {
        GlVideoRenderer r;
        GlVideoRenderer.Fit f;
        synchronized (VideoInjector.class) {
            f = currentFit(ctx);
            e.apply(f);
            f.zoom = GlVideoRenderer.clamp(f.zoom, 0.02f, 12f);
            f.offX = GlVideoRenderer.clamp(f.offX, -6f, 6f);
            f.offY = GlVideoRenderer.clamp(f.offY, -6f, 6f);
            f.fill = GlVideoRenderer.clamp(f.fill, 0.1f, 2f);
            liveFit = f;
            r = gl;
            panelCtx = ctx.getApplicationContext() != null ? ctx.getApplicationContext() : ctx;
        }
        if (r != null) r.setFit(f);
        MAIN.removeCallbacks(WRITER);
        MAIN.postDelayed(WRITER, 500);
    }

    private static final Runnable WRITER = new Runnable() {
        @Override
        public void run() {
            Context c = panelCtx;
            GlVideoRenderer.Fit f;
            GlVideoRenderer r;
            synchronized (VideoInjector.class) {
                f = liveFit;
                r = gl;
            }
            if (c == null || f == null) return;
            try {
                File dir = c.getFilesDir();
                Properties p = readCfg(dir);
                if (panelOff) p.setProperty("panel", "0");
                if (calKey != null) p.setProperty("calkey", calKey);
                for (String line : f.toCfg().split("\n")) {
                    int i = line.indexOf('=');
                    if (i > 0) p.setProperty(line.substring(0, i), line.substring(i + 1));
                }
                StringBuilder sb = new StringBuilder();
                for (String k : p.stringPropertyNames()) sb.append(k).append('=').append(p.getProperty(k)).append('\n');
                File tmp = new File(dir, CFG_NAME + ".tmp");
                FileOutputStream o = new FileOutputStream(tmp);
                try {
                    o.write(sb.toString().getBytes("UTF-8"));
                } finally {
                    o.close();
                }
                File dst = new File(dir, CFG_NAME);
                if (!tmp.renameTo(dst)) {
                    FileOutputStream o2 = new FileOutputStream(dst);
                    try {
                        o2.write(sb.toString().getBytes("UTF-8"));
                    } finally {
                        o2.close();
                    }
                    tmp.delete();
                }
                if (r != null) r.markCfgWritten();
            } catch (Throwable ignored) {
            }
        }
    };

    private VideoInjector() {}

    private static Context appContext() {
        try {
            return (Context) XposedHelpers.callStaticMethod(
                    XposedHelpers.findClass("android.app.ActivityThread", null), "currentApplication");
        } catch (Throwable t) {
            return null;
        }
    }

    private static void note(Context ctx, String msg) {
        try {
            String stamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date());
            FileOutputStream o = new FileOutputStream(new File(ctx.getFilesDir(), STATUS_NAME));
            try {
                o.write((stamp + "  " + msg + "\n").getBytes("UTF-8"));
            } finally {
                o.close();
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * Returns true if the video will be played on {@code target}; the caller must then
     * swap the real camera output for a dummy. Returns false (change nothing) on any problem.
     */
    static boolean begin(final Surface target) {
        Context ctx = null;
        try {
            ctx = appContext();
            if (ctx == null) return false;
            final Context fctx = ctx;
            if (target == null || !target.isValid()) {
                note(ctx, "SKIP: camera preview surface was not ready");
                return false;
            }

            File dir = ctx.getFilesDir();
            final File video = new File(dir, VIDEO_NAME);
            if (!video.isFile() || video.length() == 0) {
                note(ctx, "SKIP: no video in the app folder (tap Apply to app)");
                return false;
            }

            Properties p = new Properties();
            File cfg = new File(dir, CFG_NAME);
            if (cfg.isFile()) {
                FileInputStream in = new FileInputStream(cfg);
                try {
                    p.load(in);
                } finally {
                    in.close();
                }
            }
            synchronized (VideoInjector.class) {
                liveFit = GlVideoRenderer.Fit.fromProps(p);
            }
            boolean enabled = !"0".equals(p.getProperty("enabled", "1").trim());
            final boolean loop = !"0".equals(p.getProperty("loop", "1").trim());
            boolean useGl = !"0".equals(p.getProperty("gl", "1").trim());
            if (!enabled) {
                note(ctx, "SKIP: Inject video is switched off");
                return false;
            }

            releaseAll(target);
            if (useGl) {
                final GlVideoRenderer r = new GlVideoRenderer(target, video.getAbsolutePath(), loop,
                        GlVideoRenderer.Fit.fromProps(p), new GlVideoRenderer.Callback() {
                            @Override
                            public void onStatus(String msg) {
                                note(fctx, msg);
                                MAIN.postDelayed(AUTOCAL, 900);
                                MAIN.postDelayed(AUTOCAL, 3000);
                            }

                            @Override
                            public void onFailed(final String why, boolean wasShowing) {
                                XposedBridge.log("[VideoInject] GL failed: " + why);
                                if (wasShowing) {
                                    note(fctx, "ERROR: " + why);
                                    return;
                                }
                                MAIN.post(new Runnable() {
                                    @Override
                                    public void run() {
                                        note(fctx, "NOTE: smart fit unavailable (" + why + "), using plain video");
                                        playDirect(fctx, target, video, loop);
                                    }
                                });
                            }
                        }, cfg);
                gl = r;
                r.start();
            } else {
                MAIN.post(new Runnable() {
                    @Override
                    public void run() {
                        playDirect(fctx, target, video, loop);
                    }
                });
            }
            return true;
        } catch (Throwable t) {
            XposedBridge.log("[VideoInject] begin failed: " + t);
            if (ctx != null) note(ctx, "ERROR: " + t);
            return false;
        }
    }

    /** Old simple path: the player draws straight onto the preview surface (no fitting). */
    private static void playDirect(final Context fctx, Surface target, File video, boolean loop) {
        try {
            releasePlayer();
            MediaPlayer mp = new MediaPlayer();
            mp.setSurface(target);
            mp.setDataSource(video.getAbsolutePath());
            mp.setLooping(loop);
            mp.setVolume(0f, 0f);
            mp.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
                @Override
                public void onPrepared(MediaPlayer p) {
                    p.start();
                    note(fctx, "OK: video is playing in the camera preview (plain, no fit)");
                }
            });
            mp.setOnErrorListener(new MediaPlayer.OnErrorListener() {
                @Override
                public boolean onError(MediaPlayer p, int what, int extra) {
                    XposedBridge.log("[VideoInject] player error " + what + "/" + extra);
                    note(fctx, "ERROR: video could not play (" + what + "/" + extra
                            + "). Try another MP4 (H.264, 1080p or lower)");
                    return true;
                }
            });
            mp.prepareAsync();
            player = mp;
        } catch (Throwable t) {
            XposedBridge.log("[VideoInject] play failed: " + t);
            note(fctx, "ERROR: " + t);
        }
    }

    private static volatile Thread lastRel;
    private static volatile GlVideoRenderer lastRelR;

    /** Frees the old picture in the background so the app never freezes while the camera closes. */
    private static void releaseLater(final GlVideoRenderer r, final MediaPlayer mp) {
        if (r == null && mp == null) return;
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                if (r != null) {
                    try { r.release(); } catch (Throwable ignored) {}
                }
                killPlayer(mp);
            }
        }, "vinject-release");
        lastRel = t;
        lastRelR = r;
        t.start();
    }

    static void stop() {
        final GlVideoRenderer r;
        final MediaPlayer mp;
        synchronized (VideoInjector.class) {
            r = gl;
            gl = null;
            mp = player;
            player = null;
        }
        releaseLater(r, mp);
    }

    private static synchronized void releaseAll(Surface target) {
        GlVideoRenderer r = gl;
        gl = null;
        if (r != null && r.drawsOn(target)) {
            try { r.release(); } catch (Throwable ignored) {}
            r = null;
        } else if (r != null) {
            releaseLater(r, null);
            r = null;
        }
        Thread t = lastRel;
        GlVideoRenderer lr = lastRelR;
        if (t != null && t.isAlive() && lr != null && lr.drawsOn(target)) {
            try { t.join(1200); } catch (InterruptedException ignored) {}
        }
        releasePlayer();
    }

    private static void releasePlayer() {
        MediaPlayer mp = player;
        player = null;
        killPlayer(mp);
    }

    private static void killPlayer(MediaPlayer mp) {
        if (mp == null) return;
        try { mp.stop(); } catch (Throwable ignored) {}
        try { mp.release(); } catch (Throwable ignored) {}
    }
}
