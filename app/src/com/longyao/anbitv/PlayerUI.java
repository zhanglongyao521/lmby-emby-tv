package com.longyao.anbitv;

import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.media.AudioManager;
import android.os.Handler;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 两个播放内核（libVLC / ExoPlayer）共用的播放器界面层。
 *
 * 顶部：标题 + 副标题 + 右侧网速
 * 底部：播放状态图标 + 当前时间 + 进度条 + 总时长 + 一行操作提示
 * 左侧：竖向音量条
 * 中央：提示气泡（替代各自散落的 Toast）
 * 左上：播放统计卡
 *
 * 这里只负责「画」和「显隐」，按键处理、seek、音量调节仍留在各自的 Activity，
 * 所以两个内核的解码/输出/字幕渲染完全不受影响。
 */
public class PlayerUI {

    /** 播放状态图标状态：0 播放 1 暂停 2 结束 3 缓冲 */
    public static final int ST_PLAY = 0, ST_PAUSE = 1, ST_END = 2, ST_BUFFER = 3;

    private final Activity act;
    private final FrameLayout root;
    private final Handler ui = new Handler();

    private LinearLayout topBar, bottomBar, volumePanel;
    private TextView titleView, subView, stateView, timeNow, timeAll, hintLine, centerToast;
    private TvSeekBar seek;
    private PlayStateIcon stateIcon;
    private U.StatsCard statsCard;
    private FrameLayout.LayoutParams centerToastParams;

    private boolean shown = false;
    private int hideDelayMs = 6000;
    private boolean blockAutoHide = false;

    public PlayerUI(Activity a, FrameLayout root, String title, String subtitle) {
        this.act = a;
        this.root = root;
        build(title, subtitle);
    }

    // ==================== 构建 ====================

    private void build(String title, String subtitle) {
        Context c = act;

        // ---- 顶部（照当贝播放器：单行文件名、贴左、40dp 高、顶部渐变） ----
        topBar = U.row(c);
        topBar.setBackground(topGradient());
        topBar.setPadding(U.dp(c, 14), U.dp(c, 6), U.dp(c, 14), U.dp(c, 10));

        LinearLayout col = U.col(c);
        titleView = U.tv(c, title == null ? "" : title, 20, U.TXT, true);
        titleView.setSingleLine(true);
        titleView.setEllipsize(android.text.TextUtils.TruncateAt.END);
        titleView.setShadowLayer(U.dp(c, 3), 0, U.dp(c, 1), 0xCC000000);
        col.addView(titleView, U.lp(-1, -2));

        subView = U.tv(c, subtitle == null ? "" : subtitle, 11, 0xCCFFFFFF, false);
        subView.setSingleLine(true);
        subView.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams sl = U.lp(-1, -2);
        sl.topMargin = U.dp(c, 2);
        col.addView(subView, sl);
        subView.setVisibility(subtitle == null || subtitle.isEmpty() ? View.GONE : View.VISIBLE);

        topBar.addView(col, new LinearLayout.LayoutParams(0, -2, 1f));

        stateView = U.tv(c, "", 11, 0xB4FFFFFF, false);
        stateView.setGravity(Gravity.RIGHT | Gravity.TOP);
        topBar.addView(stateView);

        FrameLayout.LayoutParams tp = new FrameLayout.LayoutParams(-1, -2);
        tp.gravity = Gravity.TOP;
        root.addView(topBar, tp);

        // ---- 底部（照当贝播放器：贴底 61px 细条、小图标、细进度条、小字时间） ----
        bottomBar = U.col(c);
        bottomBar.setBackground(bottomGradient());
        bottomBar.setPadding(U.dp(c, 12), U.dp(c, 6), U.dp(c, 12), U.dp(c, 6));

        LinearLayout line = U.row(c);
        line.setGravity(Gravity.CENTER_VERTICAL);

        stateIcon = new PlayStateIcon(c);
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(U.dp(c, 15), U.dp(c, 15));
        ilp.rightMargin = U.dp(c, 9);
        line.addView(stateIcon, ilp);

        timeNow = U.tv(c, "00:00", 13, 0xFFFFFFFF, false);
        timeNow.setTypeface(android.graphics.Typeface.MONOSPACE);
        timeNow.setMinWidth(U.dp(c, 56));
        line.addView(timeNow, new LinearLayout.LayoutParams(-2, -2));

        seek = new TvSeekBar(c);
        LinearLayout.LayoutParams skl = new LinearLayout.LayoutParams(0, U.dp(c, 18), 1f);
        skl.leftMargin = U.dp(c, 8);
        skl.rightMargin = U.dp(c, 8);
        line.addView(seek, skl);

        timeAll = U.tv(c, "--:--", 13, 0x9EFFFFFF, false);
        timeAll.setTypeface(android.graphics.Typeface.MONOSPACE);
        timeAll.setGravity(Gravity.RIGHT);
        timeAll.setMinWidth(U.dp(c, 56));
        line.addView(timeAll, new LinearLayout.LayoutParams(-2, -2));

        bottomBar.addView(line, U.lp(-1, -2));

        // 操作提示：当贝是独立浮层（operation_tip），放在底部栏正上方居中，不占底栏
        hintLine = U.tv(c, "", 15, 0xFFFFFFFF, false);
        hintLine.setGravity(Gravity.CENTER);
        hintLine.setSingleLine(true);
        hintLine.setVisibility(View.GONE);
        FrameLayout.LayoutParams hl = new FrameLayout.LayoutParams(-2, -2);
        hl.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        hl.bottomMargin = U.dp(c, 52);
        root.addView(hintLine, hl);

        FrameLayout.LayoutParams bp = new FrameLayout.LayoutParams(-1, -2);
        bp.gravity = Gravity.BOTTOM;
        root.addView(bottomBar, bp);

        // ---- 左侧音量条 ----
        volumePanel = U.col(c);
        volumePanel.setBackground(U.round(0xCC0A0C0E, U.dp(c, 10)));
        volumePanel.setPadding(U.dp(c, 10), U.dp(c, 14), U.dp(c, 10), U.dp(c, 14));
        volumePanel.setVisibility(View.GONE);
        FrameLayout.LayoutParams vp = new FrameLayout.LayoutParams(U.dp(c, 66), U.dp(c, 300));
        vp.gravity = Gravity.LEFT | Gravity.CENTER_VERTICAL;
        vp.leftMargin = U.dp(c, 30);
        root.addView(volumePanel, vp);

        // ---- 中央提示气泡 ----
        centerToast = U.tv(c, "", 18, 0xFFFFFFFF, false);
        centerToast.setBackground(U.round(0xE6121619, U.dp(c, 24)));
        centerToast.setPadding(U.dp(c, 30), U.dp(c, 15), U.dp(c, 30), U.dp(c, 16));
        centerToast.setVisibility(View.GONE);
        FrameLayout.LayoutParams cp = new FrameLayout.LayoutParams(-2, -2);
        cp.gravity = Gravity.CENTER;
        centerToastParams = cp;
        root.addView(centerToast, cp);

        // ---- 播放统计卡 ----
        statsCard = new U.StatsCard(c);
        FrameLayout.LayoutParams sp = new FrameLayout.LayoutParams(-2, -2);
        sp.gravity = Gravity.LEFT | Gravity.TOP;
        sp.leftMargin = U.dp(c, 42);
        sp.topMargin = U.dp(c, 26);
        root.addView(statsCard, sp);

        topBar.setVisibility(View.GONE);
        bottomBar.setVisibility(View.GONE);
    }

    private static android.graphics.drawable.Drawable topGradient() {
        return new android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xB3000000, 0x00000000});
    }

    private static android.graphics.drawable.Drawable bottomGradient() {
        return new android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.BOTTOM_TOP,
                new int[]{0xE6000000, 0x4D000000, 0x00000000});
    }

    // ==================== 显隐 ====================

    private final Runnable hideRunnable = new Runnable() {
        @Override public void run() {
            if (blockAutoHide) {
                ui.postDelayed(this, 1000);
                return;
            }
            if (volumePanel.getVisibility() == View.VISIBLE) {
                ui.postDelayed(this, 1000);
                return;
            }
            shown = false;
            topBar.setVisibility(View.GONE);
            bottomBar.setVisibility(View.GONE);
            hintLine.setVisibility(View.GONE);
        }
    };

    /** 显示 HUD，默认 6 秒后自动隐藏 */
    public void show() { show(hideDelayMs); }

    public void show(int ms) {
        shown = true;
        topBar.setVisibility(View.VISIBLE);
        bottomBar.setVisibility(View.VISIBLE);
        ui.removeCallbacks(hideRunnable);
        if (ms > 0) ui.postDelayed(hideRunnable, ms);
    }

    public void hide() {
        ui.removeCallbacks(hideRunnable);
        shown = false;
        topBar.setVisibility(View.GONE);
        bottomBar.setVisibility(View.GONE);
        hintLine.setVisibility(View.GONE);
    }

    /** 只刷新隐藏倒计时，不改变可见状态（连续操作时调用） */
    public void keepAlive() {
        if (!shown) return;
        ui.removeCallbacks(hideRunnable);
        ui.postDelayed(hideRunnable, 4000);
    }

    /** 菜单、音量条等场景下临时禁用自动隐藏 */
    public void setBlockAutoHide(boolean b) {
        blockAutoHide = b;
        if (!b && shown) keepAlive();
    }

    public void setHideDelay(int ms) { hideDelayMs = ms; }

    public boolean visible() {
        return shown && bottomBar.getVisibility() == View.VISIBLE;
    }

    /** 给菜单调用：菜单开着时 HUD 不该自动消失 */
    public boolean autoHideBlocked() { return blockAutoHide; }

    /** 音量条是否正显示着（此时不该把 HUD 收起来） */
    public boolean volumeVisible() {
        return volumePanel != null && volumePanel.getVisibility() == View.VISIBLE;
    }

    // ==================== 文字 / 进度 ====================

    public void title(String t) { titleView.setText(t == null ? "" : t); }

    public void subtitle(String s) {
        subView.setText(s == null ? "" : s);
        subView.setVisibility(s == null || s.isEmpty() ? View.GONE : View.VISIBLE);
    }

    /** 右上角状态文字（网速等） */
    public void setStateText(String s) { stateView.setText(s == null ? "" : s); }

    public void setNetSpeed(String s) { stateView.setText(s == null ? "" : s); }

    /** 仅更新进度条 */
    public void setProgress(long pos, long dur) {
        if (seek != null) seek.setData(pos, dur);
    }

    /** 按住快进/后退：进度条锁在预览位置，不再接受真实位置 */
    public void previewSeek(long pos, long dur) {
        if (seek != null) seek.setPreview(pos, dur);
    }

    /** 松手：退出预览状态 */
    public void endSeekPreview() {
        if (seek != null) seek.endPreview();
    }

    public boolean seekPreviewing() {
        return seek != null && seek.isPreviewing();
    }

    public void setDurationOnly(long dur) {
        if (seek != null) seek.setDuration(dur);
    }

    /** 仅更新两侧时间文字 */
    public void setTimes(long pos, long dur) {
        if (timeNow != null) timeNow.setText(fmt(pos));
        if (timeAll != null) timeAll.setText(dur > 0 ? fmt(dur) : "--:--");
    }

    /** 进度条 + 时间一起更新 */
    public void setBoth(long pos, long dur) {
        setProgress(pos, dur);
        setTimes(pos, dur);
    }

    /** 只改右侧总时长（还没拿到时长时用） */
    public void setDuration(long dur) {
        if (timeAll != null) timeAll.setText(dur > 0 ? fmt(dur) : "--:--");
    }

    /** 播放状态图标 */
    public void setPlayState(int s) {
        if (stateIcon != null) stateIcon.setState(s);
    }

    /** 底部一行操作提示，传空串即隐藏 */
    public void setHint(String s) {
        if (hintLine == null) return;
        if (s == null || s.isEmpty()) {
            hintLine.setVisibility(View.GONE);
        } else {
            hintLine.setText(s);
            hintLine.setVisibility(View.VISIBLE);
        }
    }

    public void clearHint() { setHint(""); }

    // ==================== 提示气泡 ====================

    private final Runnable toastHider = new Runnable() {
        @Override public void run() { if (centerToast != null) centerToast.setVisibility(View.GONE); }
    };

    public void toast(String s) { toast(s, 1400); }

    public void toast(String s, int ms) {
        if (centerToast == null) return;
        centerToast.setText(s == null ? "" : s);
        centerToast.setLayoutParams(centerToastParams);
        centerToast.setVisibility(View.VISIBLE);
        ui.removeCallbacks(toastHider);
        ui.postDelayed(toastHider, ms);
    }

    // ==================== 音量条 ====================

    private AudioManager am() {
        return (AudioManager) act.getSystemService(Context.AUDIO_SERVICE);
    }

    public int volume() {
        try { return am().getStreamVolume(AudioManager.STREAM_MUSIC); } catch (Throwable e) { return 0; }
    }

    /** >=0 时音量条按这个 0~1 的比例画（播放器内部音量），<0 时跟系统媒体音量 */
    public float volRatio = -1f;

    public int maxVolume() {
        try { return am().getStreamMaxVolume(AudioManager.STREAM_MUSIC); } catch (Throwable e) { return 1; }
    }

    /** 调整媒体音量（delta 正负），并弹出音量条 */
    public void changeVolume(int delta) {
        try {
            am().adjustStreamVolume(AudioManager.STREAM_MUSIC,
                    delta > 0 ? AudioManager.ADJUST_RAISE : AudioManager.ADJUST_LOWER, 0);
        } catch (Throwable ignored) { }
        showVolume();
    }

    private final Runnable volumeHider = new Runnable() {
        @Override public void run() { if (volumePanel != null) volumePanel.setVisibility(View.GONE); }
    };

    /** 画出竖向 12 段音量条并显示 1.5 秒 */
    public void showVolume() {
        if (volumePanel == null) return;
        volumePanel.removeAllViews();
        TextView t = U.tv(act, L.t("音量"), 10, 0xB4FFFFFF, false);
        volumePanel.addView(t, U.lp(-1, -2));

        View bar = new View(act) {
            private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            @Override protected void onDraw(Canvas cv) {
                int w = getWidth(), h = getHeight();
                int seg = 12;
                int gap = Math.max(2, h / 96);
                int sh = (h - gap * (seg - 1)) / seg;
                int lit = volRatio >= 0f
                        ? Math.round(seg * Math.max(0f, Math.min(1f, volRatio)))
                        : Math.round(seg * volume() / (float) Math.max(1, maxVolume()));
                for (int i = 0; i < seg; i++) {
                    int top = h - (i + 1) * sh - i * gap;
                    p.setColor(i < lit ? U.ACC : 0x3DFFFFFF);
                    cv.drawRect(0, top, w, top + sh, p);
                }
            }
        };
        LinearLayout.LayoutParams blp = U.lp(-1, 0);
        blp.weight = 1;
        blp.topMargin = U.dp(act, 10);
        volumePanel.addView(bar, blp);

        volumePanel.setVisibility(View.VISIBLE);
        ui.removeCallbacks(volumeHider);
        ui.postDelayed(volumeHider, 1500);
    }

    public void hideVolume() {
        ui.removeCallbacks(volumeHider);
        if (volumePanel != null) volumePanel.setVisibility(View.GONE);
    }

    // ==================== 统计卡 ====================

    public U.StatsCard stats() { return statsCard; }

    private final Runnable statsHider = new Runnable() {
        @Override public void run() { if (statsCard != null) statsCard.setVisibility(View.GONE); }
    };

    /** 显示统计卡（自动 14 秒后隐藏，和菜单里的播放统计一致） */
    public void showStats(String[][] groups) {
        if (statsCard == null) return;
        statsCard.render(groups);
        statsCard.setVisibility(View.VISIBLE);
        ui.removeCallbacks(statsHider);
        ui.postDelayed(statsHider, 14000);
    }

    public void hideStats() {
        ui.removeCallbacks(statsHider);
        if (statsCard != null) statsCard.setVisibility(View.GONE);
    }

    /** Activity 销毁前清掉所有待执行的显隐任务 */
    public void release() {
        hideEpisodes();
        ui.removeCallbacksAndMessages(null);
    }

    // ==================== 选集面板（播放器里按上键弹出） ====================

    private FrameLayout epPanel;
    private LinearLayout epCards;

    public interface OnEpPick { void onPick(int index); }

    public boolean epOpen() { return epPanel != null; }

    public void hideEpisodes() {
        if (epPanel == null) return;
        final FrameLayout p = epPanel;
        epPanel = null;
        epCards = null;
        try { root.removeView(p); } catch (Throwable ignored) { }
    }

    /** 选集条：每集一张缩略图 + 集名，左右移动焦点，确认直接切集 */
    /** 缩略图底部细进度条（跟主界面媒体库同一套视觉） */
    static class PBar extends View {
        int pct;
        PBar(Context c) { super(c); }
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.RectF rf = new android.graphics.RectF();
        @Override protected void onDraw(Canvas canvas) {
            int w = getWidth(), h = getHeight();
            p.setStyle(Paint.Style.FILL);
            p.setColor(0x99000000);
            rf.set(0, 0, w, h);
            canvas.drawRect(rf, p);
            p.setColor(U.ACC);
            rf.set(0, 0, w * (pct / 100f), h);
            canvas.drawRect(rf, p);
        }
    }

    public void showEpisodes(String head, String[] labels, String[] urls, String[] badges, int curIdx,
                             final OnEpPick cb) {
        hideEpisodes();
        final Context c = act;
        FrameLayout panel = new FrameLayout(c);
        panel.setBackgroundColor(0xF0000000);
        panel.setFocusable(true);

        LinearLayout col = U.col(c);
        col.setPadding(U.dp(c, 44), U.dp(c, 34), U.dp(c, 44), U.dp(c, 30));
        panel.addView(col, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout headRow = U.row(c);
        headRow.setGravity(Gravity.CENTER_VERTICAL);
        col.addView(headRow, U.lp(-1, -2));
        TextView h = U.tv(c, head == null ? "" : head, 22, 0xFFFFFFFF, true);
        headRow.addView(h, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView tip = U.tv(c, L.t("左右选中 · 确认播放 · 返回关闭"), 12, 0x99FFFFFF, false);
        headRow.addView(tip);

        android.widget.HorizontalScrollView hs = new android.widget.HorizontalScrollView(c);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout row = U.row(c);
        row.setPadding(0, U.dp(c, 22), 0, U.dp(c, 6));
        hs.addView(row, new android.view.ViewGroup.LayoutParams(-2, -2));
        LinearLayout.LayoutParams hsl = U.lp(-1, -2);
        hsl.topMargin = U.dp(c, 6);
        col.addView(hs, hsl);

        epCards = row;
        for (int i = 0; i < labels.length; i++) {
            final int idx = i;
            LinearLayout card = U.col(c);
            card.setFocusable(true);
            card.setFocusableInTouchMode(true);
            card.setPadding(U.dp(c, 4), U.dp(c, 4), U.dp(c, 4), U.dp(c, 10));
            card.setBackground(U.roundStroke(0x00000000, U.dp(c, 10), U.dp(c, 3), 0x00FFFFFF));

            android.widget.ImageView iv = new android.widget.ImageView(c);
            iv.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
            iv.setBackgroundColor(U.SOFT);
            if (urls != null && idx < urls.length && urls[idx] != null && !urls[idx].isEmpty()) {
                Img.load(iv, urls[idx]);
            }
            // 缩略图容器：右上角叠播放进度角标（跟媒体库一样只显示 xx%）
            FrameLayout thumb = new FrameLayout(c);
            thumb.addView(iv, new FrameLayout.LayoutParams(-1, -1));
            String bd = (badges != null && idx < badges.length) ? badges[idx] : null;
            if (bd != null && !bd.isEmpty()) {
                // 跟主界面统一：不再用右上角文字角标，改画缩略图底部那条细进度条（官方 Emby 样式）
                int pv = 0;
                try { pv = Integer.parseInt(bd.replace("%", "").trim()); } catch (Throwable ignored) { }
                if (pv > 0 && pv < 100) {
                    PBar pb = new PBar(c);
                    pb.pct = pv;
                    FrameLayout.LayoutParams pl = new FrameLayout.LayoutParams(-1, Math.max(3, U.dp(c, 3.5f)),
                            Gravity.BOTTOM);
                    thumb.addView(pb, pl);
                }
            }
            card.addView(thumb, new LinearLayout.LayoutParams(-1, U.dp(c, 120)));

            TextView lb = U.tv(c, labels[idx], 13, 0xFFE6EAEE, false);
            lb.setSingleLine(true);
            lb.setEllipsize(android.text.TextUtils.TruncateAt.END);
            lb.setPadding(U.dp(c, 8), U.dp(c, 8), U.dp(c, 8), 0);
            card.addView(lb, U.lp(-1, -2));

            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(U.dp(c, 208), -2);
            clp.rightMargin = U.dp(c, 14);
            row.addView(card, clp);

            card.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    hideEpisodes();
                    if (cb != null) cb.onPick(idx);
                }
            });
            card.setOnFocusChangeListener(new View.OnFocusChangeListener() {
                @Override public void onFocusChange(View v, boolean has) {
                    v.setBackground(has
                            ? U.roundStroke(0x22FFFFFF, U.dp(act, 6), U.dp(act, 3), 0xFFFFFFFF)
                            : U.roundStroke(0x00000000, U.dp(act, 10), U.dp(act, 3), 0x00FFFFFF));
                    v.animate().scaleX(has ? 1.06f : 1f).scaleY(has ? 1.06f : 1f)
                            .setDuration(120).start();
                }
            });
        }

        root.addView(panel, new FrameLayout.LayoutParams(-1, -1));
        epPanel = panel;
        epFocus(curIdx);
    }

    /** 焦点落在当前播放的那一集上 */
    private void epFocus(final int idx) {
        if (epPanel == null) return;
        epPanel.post(new Runnable() {
            @Override public void run() {
                if (epCards == null || epCards.getChildCount() == 0) return;
                int i = idx < 0 ? 0 : Math.min(idx, epCards.getChildCount() - 1);
                epCards.getChildAt(i).requestFocus();
            }
        });
    }

    // ==================== 时间格式 ====================

    public static String fmt(long ms) {
        if (ms < 0) ms = 0;
        long s = ms / 1000;
        long h = s / 3600, m = (s % 3600) / 60, sec = s % 60;
        if (h > 0) return String.format("%d:%02d:%02d", h, m, sec);
        return String.format("%02d:%02d", m, sec);
    }

    // ==================== 播放状态图标 ====================

    /** 播放状态小图标：0 播放 ▶ / 1 暂停 ❚❚ / 2 结束 ■ / 3 缓冲 ◌ */
    private static class PlayStateIcon extends View {
        private int state = 3;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path tri = new Path();

        PlayStateIcon(Context c) { super(c); }

        void setState(int s) {
            if (state == s) return;
            state = s;
            invalidate();
        }

        @Override protected void onDraw(Canvas cv) {
            int w = getWidth(), h = getHeight();
            float cx = w / 2f, cy = h / 2f;
            float r = Math.min(w, h) / 2f;
            p.setStyle(Paint.Style.FILL);
            p.setShader(null);
            p.setColor(0x2EFFFFFF);
            cv.drawCircle(cx, cy, r, p);
            float s = r * 0.86f;
            p.setColor(0xFFFFFFFF);
            if (state == 0) {
                tri.reset();
                tri.moveTo(cx - s * 0.34f, cy - s * 0.55f);
                tri.lineTo(cx + s * 0.52f, cy);
                tri.lineTo(cx - s * 0.34f, cy + s * 0.55f);
                tri.close();
                cv.drawPath(tri, p);
            } else if (state == 1) {
                float bw = s * 0.17f;
                cv.drawRoundRect(cx - s * 0.36f, cy - s * 0.5f, cx - s * 0.36f + bw, cy + s * 0.5f,
                        bw * 0.3f, bw * 0.3f, p);
                cv.drawRoundRect(cx + s * 0.19f, cy - s * 0.5f, cx + s * 0.19f + bw, cy + s * 0.5f,
                        bw * 0.3f, bw * 0.3f, p);
            } else if (state == 2) {
                cv.drawRoundRect(cx - s * 0.42f, cy - s * 0.42f, cx + s * 0.42f, cy + s * 0.42f,
                        s * 0.14f, s * 0.14f, p);
            } else {
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(r * 0.22f);
                cv.drawCircle(cx, cy, s * 0.46f, p);
            }
        }
    }
}
