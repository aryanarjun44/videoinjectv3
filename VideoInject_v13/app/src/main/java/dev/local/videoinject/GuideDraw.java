package dev.local.videoinject;

import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.RectF;

/** Draws preset face marks (face oval, centre line, eye line, nose, mouth) inside a round window. */
final class GuideDraw {

    private GuideDraw() {}

    static void draw(Canvas c, float cx, float cy, float d, float density) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(Math.max(1.5f * density, d * 0.006f));
        p.setColor(0xFF00E676);
        p.setPathEffect(new DashPathEffect(new float[]{d * 0.03f, d * 0.02f}, 0));

        c.drawOval(new RectF(cx - 0.29f * d, cy - 0.39f * d, cx + 0.29f * d, cy + 0.39f * d), p);
        c.drawLine(cx, cy - 0.46f * d, cx, cy + 0.46f * d, p);

        float eye = cy - 0.06f * d;
        c.drawLine(cx - 0.26f * d, eye, cx + 0.26f * d, eye, p);
        c.drawLine(cx - 0.05f * d, cy + 0.08f * d, cx + 0.05f * d, cy + 0.08f * d, p);
        c.drawLine(cx - 0.09f * d, cy + 0.18f * d, cx + 0.09f * d, cy + 0.18f * d, p);

        Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
        dot.setStyle(Paint.Style.FILL);
        dot.setColor(0xFFFF1744);
        c.drawCircle(cx - 0.12f * d, eye, 0.016f * d, dot);
        c.drawCircle(cx + 0.12f * d, eye, 0.016f * d, dot);
    }
}
