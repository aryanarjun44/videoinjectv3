package dev.local.videoinject;

import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Small helper that builds the app's look (cards, buttons, status rows) in code, light and dark. */
final class Ui {

    final Context ctx;
    final float density;
    final boolean night;

    int bg, card, text, sub, accent, accent2, onAccent, ok, warn, bad, tonal, line;

    Ui(Context c) {
        ctx = c;
        density = c.getResources().getDisplayMetrics().density;
        night = true; // one premium dark look for the whole module
        bg = 0xFF0B0D14;
        card = 0xFF151926;
        text = 0xFFF2F4FA;
        sub = 0xFF98A0B5;
        accent = 0xFF8B7BFF;
        accent2 = 0xFF22D3EE;
        onAccent = 0xFF0A0B14;
        ok = 0xFF34D399;
        warn = 0xFFFBBF24;
        bad = 0xFFF87171;
        tonal = 0xFF222844;
        line = 0xFF2A3050;
    }

    int dp(int v) {
        return Math.round(v * density);
    }

    GradientDrawable round(int color, int radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radiusDp));
        return g;
    }

    TextView label(String s, int sp, int color, boolean bold) {
        TextView t = new TextView(ctx);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    LinearLayout card() {
        LinearLayout l = new LinearLayout(ctx);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(dp(18), dp(16), dp(18), dp(18));
        GradientDrawable g = round(card, 22);
        g.setStroke(Math.max(1, dp(1)), line);
        l.setBackground(g);
        l.setElevation(dp(4));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, 0, dp(14));
        l.setLayoutParams(lp);
        return l;
    }

    /** Numbered section title: a gradient badge and the heading. */
    LinearLayout section(String num, String title) {
        LinearLayout r = new LinearLayout(ctx);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        TextView b = label(num, 13, onAccent, true);
        b.setGravity(Gravity.CENTER);
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{accent, accent2});
        g.setShape(GradientDrawable.OVAL);
        b.setBackground(g);
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(dp(26), dp(26));
        bl.setMargins(0, 0, dp(12), 0);
        r.addView(b, bl);
        r.addView(label(title, 17, text, true));
        return r;
    }

    /** Top banner: gradient, title, version chip, one line of help. */
    LinearLayout header(String title, String chip, String subtitle) {
        LinearLayout h = new LinearLayout(ctx);
        h.setOrientation(LinearLayout.VERTICAL);
        h.setPadding(dp(22), dp(22), dp(22), dp(22));
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{0xFF4F46E5, 0xFF7C3AED, 0xFF0891B2});
        g.setCornerRadius(dp(26));
        h.setBackground(g);
        h.setElevation(dp(6));
        TextView c = label(chip, 11, 0xFFFFFFFF, true);
        c.setPadding(dp(10), dp(4), dp(10), dp(4));
        c.setBackground(round(0x33FFFFFF, 12));
        h.addView(c, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView t = label(title, 30, 0xFFFFFFFF, true);
        t.setPadding(0, dp(10), 0, 0);
        h.addView(t);
        TextView s = label(subtitle, 13, 0xFFE0E4FF, false);
        s.setPadding(0, dp(4), 0, 0);
        h.addView(s);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, 0, dp(16));
        h.setLayoutParams(lp);
        return h;
    }

    /** A switch with the module's colours. */
    android.widget.Switch sw() {
        android.widget.Switch s = new android.widget.Switch(ctx);
        try {
            s.setThumbTintList(ColorStateList.valueOf(0xFFFFFFFF));
            s.setTrackTintList(new ColorStateList(
                    new int[][]{{android.R.attr.state_checked}, {}},
                    new int[]{accent, 0xFF3A4160}));
        } catch (Throwable ignored) {
        }
        s.setPadding(0, dp(4), 0, dp(4));
        return s;
    }

    /** Dark status and navigation bars to match the screen. */
    void system(android.view.Window w) {
        try {
            w.setStatusBarColor(bg);
            w.setNavigationBarColor(bg);
        } catch (Throwable ignored) {
        }
    }

    Button button(String s, boolean primary) {
        Button b = new Button(ctx);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextSize(15);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setTextColor(primary ? onAccent : text);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setPadding(dp(16), dp(14), dp(16), dp(14));
        b.setStateListAnimator(null);
        GradientDrawable base;
        if (primary) {
            base = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                    new int[]{accent, accent2});
        } else {
            base = new GradientDrawable();
            base.setColor(tonal);
            base.setStroke(Math.max(1, dp(1)), line);
        }
        base.setCornerRadius(dp(16));
        b.setBackground(new RippleDrawable(ColorStateList.valueOf(0x44FFFFFF), base, null));
        return b;
    }

    /** One line of the status card: a coloured dot, a title and a short explanation. */
    final class Row {
        final LinearLayout root;
        private final View dot;
        private final TextView detail;

        Row(String name) {
            root = new LinearLayout(ctx);
            root.setOrientation(LinearLayout.HORIZONTAL);
            root.setGravity(Gravity.TOP);
            root.setPadding(0, dp(12), 0, 0);

            dot = new View(ctx);
            LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(dp(10), dp(10));
            dl.setMargins(0, dp(5), dp(12), 0);
            root.addView(dot, dl);

            LinearLayout col = new LinearLayout(ctx);
            col.setOrientation(LinearLayout.VERTICAL);
            col.addView(label(name, 14, text, true));
            detail = label("", 12, sub, false);
            col.addView(detail);
            root.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            set(0, "Not checked yet");
        }

        /** level: 0 grey, 1 green, 2 amber, 3 red */
        void set(int level, String msg) {
            int c = level == 1 ? ok : level == 2 ? warn : level == 3 ? bad : sub;
            dot.setBackground(round(c, 8));
            detail.setText(msg);
        }
    }
}
