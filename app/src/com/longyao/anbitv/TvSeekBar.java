package com.longyao.anbitv;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.Choreographer;
import android.view.View;

/**
 * 播放器底部进度条（libVLC / ExoPlayer 共用）。
 *
 * 只有两个数据入口：
 *   setData(pos, dur)      —— 正常播放，外部喂真实位置
 *   setPreview(pos, dur)   —— 按住快进/后退，进度条只认这个位置
 *
 * 处于 preview 状态时 setData 一律忽略，所以按住期间不会被内核的真实位置拉回去；
 * 松手后调用 endPreview()，进度条停在落点，再由外部继续喂真实位置。
 * 内部用 Choreographer 逐帧插值，走动是平滑的，不是整秒一跳。
 */
public class TvSeekBar extends View implements Choreographer.FrameCallback {

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();

    private long durMs;
    private long posMs;          // 外部喂进来的真实位置
    private float shownMs = -1f; // 当前绘制的位置
    private float targetMs = -1f;// 正在逼近的位置
    private boolean previewing = false;
    private boolean ticking = false;
    private long bufferedMs;

    public TvSeekBar(Context c) {
        super(c);
        p.setAntiAlias(true);
    }

    // ==================== 数据入口 ====================

    /** 正常播放：喂真实位置；preview 期间自动忽略 */
    public void setData(long pos, long dur) {
        if (dur > 0) durMs = dur;
        bufferedMs = 0;
        if (previewing) return;
        posMs = pos;
        targetMs = pos;
        if (shownMs < 0) {
            shownMs = pos;
            invalidate();
            return;
        }
        // 大跳变（seek 落地、换片）直接到位，避免拖一条长线
        if (Math.abs(targetMs - shownMs) > 1500) {
            shownMs = targetMs;
            invalidate();
            return;
        }
        schedule();
    }

    /** 按住快进/后退：进度条立刻跟到目标，并锁住不再接受真实位置 */
    public void setPreview(long pos, long dur) {
        previewing = true;
        if (dur > 0) durMs = dur;
        if (pos < 0) pos = 0;
        if (durMs > 0 && pos > durMs) pos = durMs;
        posMs = pos;
        targetMs = pos;
        shownMs = pos;
        invalidate();
    }

    /** 松手：退出预览，停在当前落点，等下一帧真实位置再接管 */
    public void endPreview() {
        previewing = false;
    }

    public boolean isPreviewing() { return previewing; }

    public void setDuration(long dur) {
        if (dur > 0) durMs = dur;
        invalidate();
    }

    public void setBuffered(long ms) {
        bufferedMs = ms;
        invalidate();
    }

    // ==================== 逐帧平滑 ====================

    private void schedule() {
        if (ticking) return;
        ticking = true;
        Choreographer.getInstance().postFrameCallback(this);
    }

    @Override public void doFrame(long frameTimeNanos) {
        ticking = false;
        if (shownMs < 0) {
            shownMs = targetMs;
            invalidate();
            return;
        }
        float d = targetMs - shownMs;
        // 每帧逼近 18%，播放时的正常走动看不出一跳一跳
        shownMs += d * 0.18f;
        if (Math.abs(targetMs - shownMs) < 25f) shownMs = targetMs;
        invalidate();
        if (Math.abs(targetMs - shownMs) > 25f) schedule();
    }

    // ==================== 绘制 ====================

    @Override protected void onDraw(Canvas cv) {
        int w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;
        int cy = h / 2;
        float barH = U.dp(getContext(), 3);
        float rad = barH / 2f;
        float headR = U.dp(getContext(), 7);

        float pct = durMs > 0 ? Math.min(1f, Math.max(0f, shownMs / (float) durMs)) : 0f;
        float bpct = (durMs > 0 && bufferedMs > 0)
                ? Math.min(1f, Math.max(0f, bufferedMs / (float) durMs)) : 0f;

        p.setShader(null);
        p.setStyle(Paint.Style.FILL);

        // 轨道
        p.setColor(0x40FFFFFF);
        rect.set(0, cy - barH / 2f, w, cy + barH / 2f);
        cv.drawRoundRect(rect, rad, rad, p);

        // 已缓冲
        if (bpct > 0.001f) {
            p.setColor(0x66FFFFFF);
            rect.set(0, cy - barH / 2f, Math.max(barH, w * bpct), cy + barH / 2f);
            cv.drawRoundRect(rect, rad, rad, p);
        }

        // 已播放
        float endX = w * pct;
        if (endX > barH) {
            p.setColor(0xFFFFFFFF);
            rect.set(0, cy - barH / 2f, endX, cy + barH / 2f);
            cv.drawRoundRect(rect, rad, rad, p);
        }

        // 末端圆头
        float hx = Math.max(headR, Math.min(w - headR, endX));
        p.setColor(0xFFFFFFFF);
        cv.drawCircle(hx, cy, headR, p);
    }
}
