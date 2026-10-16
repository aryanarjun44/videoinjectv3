package dev.local.videoinject;

import android.content.Context;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;

/** Writes only the small settings file into the chosen app (used for live tuning). Needs root. */
final class RootCfg {

    private RootCfg() {}

    static String push(Context c, String pkg, GlVideoRenderer.Fit fit, boolean enabled, boolean loop) throws Exception {
        String dataDir = "/data/user/0/" + pkg;
        try {
            String d = c.getPackageManager().getApplicationInfo(pkg, 0).dataDir;
            if (d != null && !d.isEmpty()) dataDir = d;
        } catch (Exception ignored) {
        }
        int user = android.os.Process.myUid() / 100000;
        String[] cands = {dataDir, "/data/user/" + user + "/" + pkg, "/data/data/" + pkg,
                "/data_mirror/data_ce/null/" + user + "/" + pkg};
        StringBuilder s = new StringBuilder();
        s.append("echo @@RAN@@\nB=''\n");
        for (String x : cands) {
            s.append("[ -z \"$B\" ] && [ -d '").append(x).append("' ] && B='").append(x).append("'\n");
        }
        s.append("if [ -z \"$B\" ]; then echo @@NODIR@@; exit; fi\n");
        s.append("D=\"$B/files\"\nU=$(stat -c %u \"$B\" 2>/dev/null)\n");
        s.append(": > \"$D/vinject.cfg.tmp\"\n");
        String text = "enabled=" + (enabled ? 1 : 0) + "\nloop=" + (loop ? 1 : 0) + "\ngl=1\nautocal=" + (c.getSharedPreferences("config", 0).getBoolean("autocal", true) ? 1 : 0) + "\npanel=" + (c.getSharedPreferences("config", 0).getBoolean("panel", false) ? 1 : 0) + "\n" + fit.toCfg();
        for (String ln : text.split("\n")) {
            s.append("echo '").append(ln).append("' >> \"$D/vinject.cfg.tmp\"\n");
        }
        s.append("[ -n \"$U\" ] && chown $U:$U \"$D/vinject.cfg.tmp\"\n");
        s.append("chmod 666 \"$D/vinject.cfg.tmp\"\n");
        s.append("chcon --reference=\"$B\" \"$D/vinject.cfg.tmp\" 2>/dev/null\n");
        s.append("mv -f \"$D/vinject.cfg.tmp\" \"$D/vinject.cfg\"\n");
        s.append("echo @@DONE@@\nexit\n");

        String[][] attempts = {{"su", "-M"}, {"su", "-mm"}, {"su"}};
        String last = "";
        for (String[] a : attempts) {
            try {
                String out = exec(a, s.toString());
                if (out.contains("@@RAN@@")) return out;
                last = out;
            } catch (Exception ignored) {
            }
        }
        return last;
    }

    private static String exec(String[] cmd, String script) throws Exception {
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        OutputStream os = p.getOutputStream();
        os.write(script.getBytes("UTF-8"));
        os.flush();
        os.close();
        BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), "UTF-8"));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = r.readLine()) != null) sb.append(line).append('\n');
        p.waitFor();
        return sb.toString();
    }
}
