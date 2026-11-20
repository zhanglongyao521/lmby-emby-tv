package com.longyao.anbitv;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

/** 原生 Emby 详情页的动作方块：图标居中 + 下方小字说明 */
class DetailTile extends View {
    public static final String PLAY = "play", PAUSE = "pause", CHECK = "check",
            HEART = "heart", REFRESH = "refresh", MORE = "more",
            CLOCK = "clock", TRASH = "trash", CLOSE = "close";

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float dens;
    private final String glyph;
    private boolean green;
    private boolean filled;

    public DetailTile(Context c, String glyph) {
        super(c);
            this.glyph = glyph;
        dens = c.getResources().getDisplayMetrics().density;
        setFocusable(true);
        setClickable(true);
    }

    public void setGreen(boolean g) { green = g; invalidate(); }

    public void setFilled(boolean f) { filled = f; invalidate(); }

    private int dp(float v) { return (int) (v * dens); }

    @Override protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        boolean foc = isFocused();
        p.setStyle(Paint.Style.FILL);
        p.setColor(green ? U.ACC : U.PANEL2);
        c.drawRoundRect(new RectF(0, 0, w, h), dp(20), dp(20), p);

        int ic = dp(26);
        float x = (w - ic) / 2f, y = (h - ic) / 2f;
        drawGlyph(c, x, y, ic);

        if (foc) {
            U.drawRing(c, w, h, dp(6), dens, p);
            p.setStyle(Paint.Style.FILL);
        }
    }

    private void drawGlyph(Canvas c, float x, float y, int s) {
        float cx = x + s / 2f, cy = y + s / 2f;
        p.setColor(0xFFFFFFFF);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(dp(2.6f));
        Path pa = new Path();
        if (PLAY.equals(glyph)) {
            pa.moveTo(x + s * 0.26f, y + s * 0.12f);
            pa.lineTo(x + s * 0.86f, cy);
            pa.lineTo(x + s * 0.26f, y + s * 0.88f);
            pa.close();
            p.setStyle(Paint.Style.FILL);
            c.drawPath(pa, p);
            p.setStyle(Paint.Style.STROKE);
        } else if (PAUSE.equals(glyph)) {
            p.setStyle(Paint.Style.FILL);
            c.drawRect(x + s * 0.22f, y + s * 0.14f, x + s * 0.42f, y + s * 0.86f, p);
            c.drawRect(x + s * 0.58f, y + s * 0.14f, x + s * 0.78f, y + s * 0.86f, p);
            p.setStyle(Paint.Style.STROKE);
        } else if (CHECK.equals(glyph)) {
            pa.moveTo(x + s * 0.14f, cy + s * 0.06f);
            pa.lineTo(x + s * 0.38f, cy + s * 0.30f);
            pa.lineTo(x + s * 0.86f, cy - s * 0.26f);
            c.drawPath(pa, p);
        } else if (HEART.equals(glyph)) {
            Path h = new Path();
            h.moveTo(cx, y + s * 0.88f);
            h.cubicTo(x + s * 0.00f, y + s * 0.56f, x + s * 0.14f, y + s * 0.08f, cx, y + s * 0.34f);
            h.cubicTo(x + s * 0.86f, y + s * 0.08f, x + s * 1.00f, y + s * 0.56f, cx, y + s * 0.88f);
            if (filled) {
                p.setStyle(Paint.Style.FILL);
                c.drawPath(h, p);
                p.setStyle(Paint.Style.STROKE);
            } else {
                c.drawPath(h, p);
            }
        } else if (REFRESH.equals(glyph)) {
            float rr = s * 0.30f;
            RectF rect = new RectF(cx - rr, cy - rr, cx + rr, cy + rr);
            c.drawArc(rect, -50, 300, false, p);
            Path ar = new Path();
            double a0 = Math.toRadians(-50);
            float px = (float) (cx + rr * Math.cos(a0));
            float py = (float) (cy + rr * Math.sin(a0));
            ar.moveTo(px + s * 0.16f, py + s * 0.02f);
            ar.lineTo(px, py - s * 0.20f);
            ar.lineTo(px - s * 0.12f, py + s * 0.04f);
            ar.close();
            p.setStyle(Paint.Style.FILL);
            c.drawPath(ar, p);
            p.setStyle(Paint.Style.STROKE);
        } else if (CLOCK.equals(glyph)) {
            float rr = s * 0.34f;
            c.drawCircle(cx, cy, rr, p);
            pa.moveTo(cx, cy - rr * 0.62f);
            pa.lineTo(cx, cy + s * 0.02f);
            pa.lineTo(cx + rr * 0.52f, cy + s * 0.02f);
            c.drawPath(pa, p);
        } else if (TRASH.equals(glyph)) {
            float lw = s * 0.36f;
            c.drawLine(cx - lw, cy - s * 0.28f, cx + lw, cy - s * 0.28f, p);
            c.drawLine(cx - s * 0.10f, cy - s * 0.40f, cx + s * 0.10f, cy - s * 0.40f, p);
            RectF body = new RectF(cx - s * 0.30f, cy - s * 0.18f, cx + s * 0.30f, cy + s * 0.40f);
            c.drawRoundRect(body, dp(2.5f), dp(2.5f), p);
            c.drawLine(cx - s * 0.10f, cy - s * 0.06f, cx - s * 0.08f, cy + s * 0.26f, p);
            c.drawLine(cx + s * 0.10f, cy - s * 0.06f, cx + s * 0.08f, cy + s * 0.26f, p);
        } else if (CLOSE.equals(glyph)) {
            c.drawLine(cx - s * 0.28f, cy - s * 0.28f, cx + s * 0.28f, cy + s * 0.28f, p);
            c.drawLine(cx + s * 0.28f, cy - s * 0.28f, cx - s * 0.28f, cy + s * 0.28f, p);
        } else {
            p.setStyle(Paint.Style.FILL);
            c.drawCircle(cx - s * 0.28f, cy, dp(2.6f), p);
            c.drawCircle(cx, cy, dp(2.6f), p);
            c.drawCircle(cx + s * 0.28f, cy, dp(2.6f), p);
            p.setStyle(Paint.Style.STROKE);
        }
    }
}
