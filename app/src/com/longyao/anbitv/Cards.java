package com.longyao.anbitv;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

/** 媒体卡片：缩略图按视频原始宽高比自适应（FlowGrid 依据 aspect 决定卡片宽度） */
/** 圆角图片：把缩略图裁成和焦点框一致的圆角，四角不会露在框外 */
class RoundedImageView extends ImageView {
    private final float radiusDp;
    private final RectF rect = new RectF();
    private final Path path = new Path();

    public RoundedImageView(Context c, float radiusDp) {
        super(c);
        this.radiusDp = radiusDp;
    }

    /** 连背景色一起裁：以前只裁 onDraw，缩略图的黑底四个角会露在圆角外 */
    @Override public void draw(Canvas canvas) {
        float r = U.dp(getContext(), radiusDp);
        int save = canvas.save();
        rect.set(0, 0, getWidth(), getHeight());
        path.reset();
        path.addRoundRect(rect, r, r, Path.Direction.CW);
        canvas.clipPath(path);
        super.draw(canvas);
        canvas.restoreToCount(save);
    }
}

/** 媒体卡片：上面是缩略图（按视频原始宽高比自适应），下面一条独立文字区（不在焦点框里） */
class Card extends FrameLayout {
    public final ImageView img;
    public final TextView title;
    public final TextView sub;
    public final TextView badge;
    /** 缩略图左下角的评分角标（有评分才显示） */
    public final TextView ratingTag;
    /** 缩略图底部居中的「继续播放」提示（最后退出来的那一集才显示） */
    public final TextView continueTag;
    /** 缩略图底部的播放进度条（官方 Emby 那种细条，0/100 不显示） */
    private int progPct = 0;
    public float aspect = 16f / 9f;
    /** 是否有刮削海报：有则固定 9:16，忽略「视图比例」 */
    public boolean posterCard = false;
    /** 视频帧自身宽高比（0 表示未知），「自动」档用它 */
    public float frameAspect = 0f;
    /** 视频文件本身是横屏还是竖屏（0 表示未知）：自动档优先按它排，跨服务器表现一致 */
    public float videoAspect = 0f;
    public String key;
    /** 窗口化网格里这张卡对应的绝对下标（-1 表示不在窗口模型里） */
    public int vIndex = -1;
    private GradientDrawable normalBg;
    private Paint ringPaint;
    private boolean folder;
    /** 延迟加载：由 FlowGrid 在进入可视区时才真正去下载图片 */
    public boolean lazy;
    public boolean imgLoaded;
    /** 容器统一画框时，用来标记「真正要框住的内层视图」（起始页 tile 用） */
    static final int TAG_RING = 0x0D0D0001;
    /** 顺带指定这个内层视图的框线颜色 */
    static final int TAG_RING_COLOR = 0x0D0D0002;
    /** 顺带指定框线圆角半径（像素，胶囊按钮用 20dp 才贴形状） */
    static final int TAG_RING_RADIUS = 0x0D0D0003;
    /** true = 缩略图完整显示（自适应模式），false = 裁切填满 */
    public boolean fitInside = true;
    /** 文件夹卡：尺寸固定为一格（只受「缩略图大小」控制），但照样显示封面缩略图 */
    public boolean folderCard;
    private String pendingUrl;
    private int labelH;

    /** 没有标题时不留文字区：卡片高度=缩略图高度，焦点框才和缩略图严丝合缝 */
    private int labelHNow() {
        boolean hasTitle = title != null && title.getText() != null && title.getText().length() > 0;
        return hasTitle ? labelH : 0;
    }

    /** 指定文字区高度（px）：首页媒体库卡用 20% 高度，让图片占满 80% */
    public void setLabelHeightPx(int px) {
        labelH = Math.max(px, 1);
        requestLayout();
        invalidate();
    }

    public void setFit(boolean fit) {
        fitInside = fit;
        if (img != null) img.setScaleType(fit ? ImageView.ScaleType.FIT_CENTER
                : ImageView.ScaleType.CENTER_CROP);
    }

    public Card(Context c, float aspect) {
        super(c);
        this.aspect = aspect;
        labelH = U.dp(c, 46);
        normalBg = U.cardBg(c);
        setBackground(null);   // 框只包缩略图，文字在框外
        setFocusable(true);
        setFocusableInTouchMode(true);
        img = new RoundedImageView(c, U.RADIUS);
        img.setScaleType(ImageView.ScaleType.FIT_CENTER);   // 缩略图完整显示，绝不裁切
        addView(img, new FrameLayout.LayoutParams(-1, -1));

        title = U.tv(c, "", 14, U.TXT, false);
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        addView(title, new FrameLayout.LayoutParams(-1, -2));

        sub = U.tv(c, "", 11, U.DIM, false);
        sub.setSingleLine(true);
        sub.setEllipsize(android.text.TextUtils.TruncateAt.END);
        addView(sub, new FrameLayout.LayoutParams(-1, -2));

        badge = U.tv(c, "", 11, 0xFFFFFFFF, false);
        badge.setVisibility(GONE);
        badge.setBackground(U.round(0xCC0A0C0E, U.dp(c, 5)));
        badge.setPadding(U.dp(c, 7), U.dp(c, 2), U.dp(c, 7), U.dp(c, 3));
        addView(badge, new FrameLayout.LayoutParams(-2, -2));

        ratingTag = U.tv(c, "", 11, 0xFFFFFFFF, false);
        ratingTag.setVisibility(GONE);
        ratingTag.setBackground(U.round(0xCC0A0C0E, U.dp(c, 5)));
        ratingTag.setPadding(U.dp(c, 7), U.dp(c, 2), U.dp(c, 7), U.dp(c, 3));
        addView(ratingTag, new FrameLayout.LayoutParams(-2, -2));

        continueTag = U.tv(c, "", 12, 0xFFFFFFFF, true);
        continueTag.setVisibility(GONE);
        continueTag.setSingleLine(true);
        continueTag.setBackground(U.round(0xE6000000, U.dp(c, 6)));
        continueTag.setPadding(U.dp(c, 9), U.dp(c, 3), U.dp(c, 9), U.dp(c, 4));
        addView(continueTag, new FrameLayout.LayoutParams(-2, -2));

        setOnFocusChangeListener(new OnFocusChangeListener() {
            @Override public void onFocusChange(View v, boolean has) {
                invalidate();
            }
        });
    }

    public void setFolder(String name, int count) {
        folder = true;
        img.setVisibility(GONE);
        setBackground(null);
        title.setText(name);
        sub.setText(count > 0 ? (count + L.t(" 个项目")) : L.t("文件夹"));
        sub.setVisibility(VISIBLE);
        setFocusable(true);
    }

    /** 缩略图区域高度（焦点框只框这一块） */
    private int imageH() {
        int h = getHeight();
        return Math.max(U.dp(getContext(), 40), h - labelH);
    }

    /** 焦点框要包住的矩形（相对卡片自身坐标），给容器统一绘制用 */
    RectF imageRectF() {
        RectF r = new RectF();
        if (img != null && img.getWidth() > 0 && img.getVisibility() == VISIBLE) {
            r.set(img.getLeft(), img.getTop(), img.getRight(), img.getBottom());
        } else {
            r.set(0, 0, getWidth(), (folder || folderCard) ? getHeight() : imageH());
        }
        return r;
    }

    /** 容器统一画焦点框：按卡片的缩放/轴心换算到容器坐标，四边都不受卡片边界限制 */
    private static Paint ringP;

    static void drawRingOf(Canvas canvas, android.view.ViewGroup g) {
        if (ringP == null) ringP = new Paint(Paint.ANTI_ALIAS_FLAG);
        View f = g.findFocus();
        if (f == null || f.getParent() != g || !f.hasFocus()) return;
        if (f instanceof Card) {
            Card c = (Card) f;
            RectF r = c.imageRectF();
            if (r.width() <= 0 || r.height() <= 0) return;
            int save = canvas.save();
            canvas.translate(c.getLeft() + c.getPivotX(), c.getTop() + c.getPivotY());
            canvas.scale(c.getScaleX(), c.getScaleY());
            canvas.translate(-c.getPivotX(), -c.getPivotY());
            canvas.translate(r.left, r.top);
            U.drawRing(canvas, r.width(), r.height(), U.dp(g.getContext(), U.RADIUS),
                    g.getResources().getDisplayMetrics().density, ringP,
                    U.dp(g.getContext(), U.RING_OUTSET));
            canvas.restoreToCount(save);
        } else {
            // 非卡片（起始页的方块 tile 等）：框住标记过的内层视图。
            // 这些小方块紧贴布局边界，外扩一半会被裁掉，所以描边整体收进方块内侧，四边一定闭合
            Object t = f.getTag(Card.TAG_RING);
            if (!(t instanceof View)) return;
            View target = (View) t;
            if (target.getWidth() <= 0 || target.getHeight() <= 0) return;
            Object cc = f.getTag(Card.TAG_RING_COLOR);
            int rc = cc instanceof Integer ? ((Integer) cc).intValue() : 0x80FFFFFF;
            Object rr = f.getTag(Card.TAG_RING_RADIUS);
            int rad = rr instanceof Integer ? ((Integer) rr).intValue() : U.dp(g.getContext(), U.RADIUS);
            int save = canvas.save();
            canvas.translate(f.getLeft() + target.getLeft(), f.getTop() + target.getTop());
            U.drawRing(canvas, target.getWidth(), target.getHeight(),
                    rad,
                    g.getResources().getDisplayMetrics().density, ringP, 0f, rc);
            canvas.restoreToCount(save);
        }
    }

    @Override protected void onMeasure(int ws, int hs) {
        int w = MeasureSpec.getSize(ws);
        int h = MeasureSpec.getSize(hs);
        if (h <= labelH) h = labelH + U.dp(getContext(), 60);
        int pad = U.dp(getContext(), 10);
        int innerW = Math.max(1, w - pad * 2);
        img.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(h - labelH, MeasureSpec.EXACTLY));
        title.measure(MeasureSpec.makeMeasureSpec(innerW, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(labelH, MeasureSpec.AT_MOST));
        sub.measure(MeasureSpec.makeMeasureSpec(innerW, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(labelH, MeasureSpec.AT_MOST));
        badge.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.AT_MOST),
                MeasureSpec.makeMeasureSpec(U.dp(getContext(), 40), MeasureSpec.AT_MOST));
        ratingTag.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.AT_MOST),
                MeasureSpec.makeMeasureSpec(U.dp(getContext(), 40), MeasureSpec.AT_MOST));
        continueTag.measure(MeasureSpec.makeMeasureSpec(Math.max(1, w - U.dp(getContext(), 8)), MeasureSpec.AT_MOST),
                MeasureSpec.makeMeasureSpec(U.dp(getContext(), 40), MeasureSpec.AT_MOST));
        setMeasuredDimension(w, h);
    }

    @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int w = r - l, h = b - t;
        int ih = imageH();
        int pad = U.dp(getContext(), 10);
        img.layout(0, 0, w, ih);
        int th = title.getMeasuredHeight();
        boolean hasSub = sub.getVisibility() == VISIBLE && sub.getText().length() > 0;
        int ty = hasSub ? ih + U.dp(getContext(), 4) : ih + (labelH - th) / 2;
        title.layout(pad, ty, w - pad, ty + th);
        int sy = ih + U.dp(getContext(), 21);
        sub.layout(pad, sy, w - pad, sy + sub.getMeasuredHeight());
        int bw = badge.getMeasuredWidth(), bh = badge.getMeasuredHeight();
        // 右上角集数 / 进度角标：直接贴到缩略图最右上角，不留内缩
        badge.layout(w - bw, 0, w, bh);
        // 左下角评分角标：同样贴到最左下角
        if (ratingTag.getVisibility() == VISIBLE) {
            int rw = ratingTag.getMeasuredWidth(), rh = ratingTag.getMeasuredHeight();
            ratingTag.layout(0, ih - rh, rw, ih);
        } else {
            ratingTag.layout(0, ih, ratingTag.getMeasuredWidth(), ih);
        }
        // 「继续播放」提示：贴缩略图底部居中，横向不超出卡片；底部留出进度条位置
        if (continueTag.getVisibility() == VISIBLE) {
            int cw2 = Math.min(continueTag.getMeasuredWidth(), w - U.dp(getContext(), 8));
            int ch2 = continueTag.getMeasuredHeight();
            int cx = (w - cw2) / 2;
            int cb = ih - U.dp(getContext(), 5);
            continueTag.layout(cx, cb - ch2, cx + cw2, cb);
        } else {
            continueTag.layout(0, ih, continueTag.getMeasuredWidth(), ih);
        }
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth(), h = getHeight();
        if (ringPaint == null) ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        // 缩略图框：单独的圆角玻璃底 + 细描边；文字区透明
        int ih = (folder || folderCard) ? h : imageH();
        float rad = U.dp(getContext(), U.RADIUS);
        ringPaint.setStyle(Paint.Style.FILL);
        // 文件夹卡：底色要比页面深底明显亮一档，否则整块会跟背景融成一片
        int tile = (folder || folderCard) ? 0xFF2C343D : U.CARD;
        ringPaint.setColor(tile);
        canvas.drawRoundRect(new RectF(0, 0, w, ih), rad, rad, ringPaint);
        if (folder || folderCard || U.GLASS) {
            ringPaint.setStyle(Paint.Style.STROKE);
            ringPaint.setStrokeWidth(U.dp(getContext(), 1));
            ringPaint.setColor((folder || folderCard) ? 0x40FFFFFF : U.BORDER);
            float i1 = U.dp(getContext(), 0.5f);
            canvas.drawRoundRect(new RectF(i1, i1, w - i1, ih - i1), rad, rad, ringPaint);
            ringPaint.setStyle(Paint.Style.FILL);
        }
        if (!folder && !folderCard) return;
        float cw = Math.min(w * 0.26f, h * 0.42f), chh = cw * 0.80f;
        float left = (w - cw) / 2f, top = (h - chh) / 2f - h * 0.06f;
        if (ringPaint == null) ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        ringPaint.setStyle(Paint.Style.FILL);
        ringPaint.setColor(0x22FFFFFF);
        RectF shadow = new RectF(left + cw * 0.02f, top + chh * 0.06f, left + cw * 1.02f, top + chh * 1.06f);
        canvas.drawRoundRect(shadow, cw * 0.1f, cw * 0.1f, ringPaint);
        ringPaint.setColor(U.ACC | 0xFF000000);
        float r = cw * 0.09f;
        RectF tab = new RectF(left + cw * 0.03f, top - chh * 0.17f, left + cw * 0.46f, top + chh * 0.07f);
        canvas.drawRoundRect(tab, r, r, ringPaint);
        RectF body = new RectF(left, top, left + cw, top + chh);
        canvas.drawRoundRect(body, r, r, ringPaint);
        ringPaint.setColor(0x33000000);
        canvas.drawRect(left + cw * 0.03f, top + chh * 0.22f, left + cw * 0.97f, top + chh * 0.31f, ringPaint);
    }

    @Override protected void dispatchDraw(Canvas canvas) {
        super.dispatchDraw(canvas);
        drawProgressBar(canvas);
        if (!hasFocus()) return;
        // 网格 / 首页横排里，焦点框由容器统一画（容器画布不会被卡片边界裁掉）
        android.view.ViewParent par = getParent();
        if (par instanceof FlowGrid || par instanceof RingRow) return;
        if (ringPaint == null) ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        // 以缩略图实际位置为准画框，避免和布局高度有出入时框歪掉
        float x0 = 0, y0 = 0, x1 = getWidth(), y1 = imageH();
        if (img != null && img.getWidth() > 0) {
            x0 = img.getLeft(); y0 = img.getTop();
            x1 = img.getRight(); y1 = img.getBottom();
        }
        int save = canvas.save();
        canvas.translate(x0, y0);
        U.drawRing(canvas, x1 - x0, y1 - y0, U.dp(getContext(), U.RADIUS),
                getResources().getDisplayMetrics().density, ringPaint,
                U.dp(getContext(), U.RING_OUTSET));
        canvas.restoreToCount(save);
    }

    /** 10 种焦点框样式，颜色统一取 U.RING */
    private void drawFocusRing(Canvas canvas, int w, int ih, float rad) {
        float d = U.dp(getContext(), 1);
        int c = U.RING;
        RectF box = new RectF();
        ringPaint.setAntiAlias(true);
        ringPaint.setStyle(Paint.Style.STROKE);
        ringPaint.setPathEffect(null);
        switch (U.RING_STYLE) {
            case 1: {   // 粗描边
                float lw = U.dp(getContext(), 6);
                ringPaint.setStrokeWidth(lw);
                ringPaint.setColor(c);
                canvas.drawRoundRect(new RectF(lw / 2, lw / 2, w - lw / 2, ih - lw / 2), rad, rad, ringPaint);
                break;
            }
            case 2: {   // 双线
                ringPaint.setStrokeWidth(U.dp(getContext(), 2));
                ringPaint.setColor(c);
                canvas.drawRoundRect(new RectF(d, d, w - d, ih - d), rad, rad, ringPaint);
                float in = U.dp(getContext(), 6);
                canvas.drawRoundRect(new RectF(in, in, w - in, ih - in), Math.max(2, rad - 3),
                        Math.max(2, rad - 3), ringPaint);
                break;
            }
            case 3: {   // 外发光
                for (int i = 3; i >= 1; i--) {
                    float gw = U.dp(getContext(), 3 * i + 2);
                    ringPaint.setStrokeWidth(gw);
                    ringPaint.setColor((c & 0x00FFFFFF) | ((0x1A * (4 - i)) << 24));
                    float o = gw / 2f;
                    canvas.drawRoundRect(new RectF(o, o, w - o, ih - o), rad, rad, ringPaint);
                }
                ringPaint.setStrokeWidth(U.dp(getContext(), 2));
                ringPaint.setColor(c);
                canvas.drawRoundRect(new RectF(d, d, w - d, ih - d), rad, rad, ringPaint);
                break;
            }
            case 4: {   // 四角：只画四个转角
                float lw = U.dp(getContext(), 4);
                float seg = U.dp(getContext(), 22);
                float ins = lw / 2 + U.dp(getContext(), 2);
                ringPaint.setStrokeWidth(lw);
                ringPaint.setColor(c);
                float l = ins, t = ins, r = w - ins, bb = ih - ins;
                float rr = Math.min(rad, Math.min(w, ih) / 2f);
                if (seg < rr * 2) seg = rr * 2 + U.dp(getContext(), 6);
                // 左上
                canvas.drawArc(new RectF(l, t, l + rr * 2, t + rr * 2), 180, 90, false, ringPaint);
                canvas.drawLine(l + rr, t, Math.min(l + seg, r), t, ringPaint);
                canvas.drawLine(l, t + rr, l, Math.min(t + seg, bb), ringPaint);
                // 右上
                canvas.drawArc(new RectF(r - rr * 2, t, r, t + rr * 2), 270, 90, false, ringPaint);
                canvas.drawLine(Math.max(r - seg, l), t, r - rr, t, ringPaint);
                canvas.drawLine(r, t + rr, r, Math.min(t + seg, bb), ringPaint);
                // 左下
                canvas.drawArc(new RectF(l, bb - rr * 2, l + rr * 2, bb), 90, 90, false, ringPaint);
                canvas.drawLine(l, Math.max(bb - seg, t), l, bb - rr, ringPaint);
                canvas.drawLine(l + rr, bb, Math.min(l + seg, r), bb, ringPaint);
                // 右下
                canvas.drawArc(new RectF(r - rr * 2, bb - rr * 2, r, bb), 0, 90, false, ringPaint);
                canvas.drawLine(r, Math.max(bb - seg, t), r, bb - rr, ringPaint);
                canvas.drawLine(Math.max(r - seg, l), bb, r - rr, bb, ringPaint);
                break;
            }
            case 5: {   // 底部条
                float bh = U.dp(getContext(), 5);
                ringPaint.setStyle(Paint.Style.FILL);
                ringPaint.setColor(c);
                box.set(d, ih - bh - d, w - d, ih - d);
                canvas.drawRoundRect(box, rad, rad, ringPaint);
                break;
            }
            case 6: {   // 顶部条
                float bh = U.dp(getContext(), 5);
                ringPaint.setStyle(Paint.Style.FILL);
                ringPaint.setColor(c);
                box.set(d, d, w - d, d + bh);
                canvas.drawRoundRect(box, rad, rad, ringPaint);
                break;
            }
            case 7: {   // 虚线
                float lw = U.dp(getContext(), 3);
                ringPaint.setStrokeWidth(lw);
                ringPaint.setColor(c);
                ringPaint.setPathEffect(new android.graphics.DashPathEffect(
                        new float[]{U.dp(getContext(), 10), U.dp(getContext(), 7)}, 0));
                float o = lw / 2 + d;
                canvas.drawRoundRect(new RectF(o, o, w - o, ih - o), rad, rad, ringPaint);
                ringPaint.setPathEffect(null);
                break;
            }
            case 8: {   // 玻璃遮罩：盖住整块缩略图，98% 透明（只压 2% 淡白 + 顶部高光 + 玻璃边）
                ringPaint.setStyle(Paint.Style.FILL);
                ringPaint.setColor(0x05FFFFFF);      // 2% 白，等于 98% 透明
                canvas.drawRoundRect(new RectF(0, 0, w, ih), rad, rad, ringPaint);
                ringPaint.setColor(0x12FFFFFF);      // 顶部一条很淡的玻璃高光
                canvas.drawRoundRect(new RectF(0, 0, w, ih * 0.42f), rad, rad, ringPaint);
                ringPaint.setStyle(Paint.Style.STROKE);
                ringPaint.setStrokeWidth(U.dp(getContext(), 2));
                ringPaint.setColor((c & 0x00FFFFFF) | 0x99000000);   // 玻璃边缘，用焦点色
                canvas.drawRoundRect(new RectF(d, d, w - d, ih - d), rad, rad, ringPaint);
                break;
            }
            case 9: {   // 渐变边：上亮下淡
                float lw = U.dp(getContext(), 3);
                ringPaint.setStrokeWidth(lw);
                float o = lw / 2 + d;
                int save = canvas.save();
                canvas.clipRect(0f, 0f, (float) w, ih / 2f);
                ringPaint.setColor(c);
                canvas.drawRoundRect(new RectF(o, o, w - o, ih - o), rad, rad, ringPaint);
                canvas.restoreToCount(save);
                save = canvas.save();
                canvas.clipRect(0f, ih / 2f, (float) w, (float) ih);
                ringPaint.setColor((c & 0x00FFFFFF) | 0x66000000);
                canvas.drawRoundRect(new RectF(o, o, w - o, ih - o), rad, rad, ringPaint);
                canvas.restoreToCount(save);
                break;
            }
            default: {  // 0 细描边（原来的样子）
                float lw = U.dp(getContext(), 3);
                ringPaint.setStrokeWidth(lw);
                ringPaint.setColor(c);
                float o = lw / 2 + d * 0.5f;
                canvas.drawRoundRect(new RectF(o, o, w - o, ih - o), rad, rad, ringPaint);
                break;
            }
        }
        ringPaint.setPathEffect(null);
        ringPaint.setStyle(Paint.Style.FILL);
    }

    /** 焦点变化：框外描边重画 + 缩略图放大（设置里可关） */
    @Override protected void onFocusChanged(boolean gain, int direction, android.graphics.Rect prev) {
        super.onFocusChanged(gain, direction, prev);
        float want = (gain && U.FOCUS_ZOOM) ? U.FOCUS_SCALE : 1f;
        if (Math.abs(getScaleX() - want) > 0.001f) {
            animate().cancel();
            animate().scaleX(want).scaleY(want).setDuration(110)
                    .setUpdateListener(new android.animation.ValueAnimator.AnimatorUpdateListener() {
                        @Override public void onAnimationUpdate(android.animation.ValueAnimator a) {
                            // 只重画这一张卡附近的区域，别整块网格重绘（上下翻页时才不掉帧）
                            invalidateRingArea(Card.this);
                        }
                    }).start();
        }
        invalidate();
        invalidateParentRing();
    }

    /** 局部重画焦点框所在的小块区域 */
    static void invalidateRingArea(View v) {
        android.view.ViewParent pp = v.getParent();
        if (!(pp instanceof FlowGrid) && !(pp instanceof RingRow)) return;
        View pv = (View) pp;
        int o = U.dp(v.getContext(), 10);
        pv.invalidate(v.getLeft() - o, v.getTop() - o, v.getRight() + o, v.getBottom() + o);
    }

    /** 焦点框画在父容器上：父容器必须跟着重画，否则框会停在旧位置或干脆不显示 */
    private void invalidateParentRing() {
        android.view.ViewParent pp = getParent();
        if (pp instanceof FlowGrid || pp instanceof RingRow) ((View) pp).invalidate();
    }

    public void setData(String t, String s, String imgUrl) {
        title.setText(t);
        requestLayout();
        sub.setText(s == null ? "" : s);
        sub.setVisibility((s == null || s.isEmpty()) ? GONE : VISIBLE);
        // 窗口滑动时这张卡会被复用成另一个条目：先把上一条的图清掉。
        // 不清的话新图还没下载完之前会显示「别的视频的图」，看起来就是某张卡比例不对、
        // 过几秒图下载好了又自己变正常
        if (imgUrl == null || !imgUrl.equals(img.getTag())) {
            img.setTag(null);
            img.setImageBitmap(null);
        }
        pendingUrl = imgUrl;
        imgLoaded = false;
        if (!lazy) { Img.load(img, imgUrl); imgLoaded = true; }
    }

    /** 滚动到附近时才真正下载缩略图 */
    public void loadImage() {
        if (imgLoaded) return;
        imgLoaded = true;
        if (pendingUrl == null || !pendingUrl.equals(img.getTag())) {
            img.setTag(null);
            img.setImageBitmap(null);
        }
        Img.load(img, pendingUrl);
    }

    public void setBadge(String b) {
        if (b == null || b.isEmpty()) {
            badge.setVisibility(GONE);
        } else {
            badge.setText(b);
            badge.setVisibility(VISIBLE);
        }
    }

    /** 缩略图底部「继续播放」提示：null / 空串隐藏 */
    public void setContinue(String t) {
        if (t == null || t.isEmpty()) {
            continueTag.setVisibility(GONE);
        } else {
            continueTag.setText(t);
            continueTag.setVisibility(VISIBLE);
        }
        requestLayout();
        invalidate();
    }

    /** 播放进度：跟官方 Emby 一样画缩略图底部一条细进度条（0 或看完都不画） */
    public void setProgress(int pct) {
        int p = pct < 0 ? 0 : (pct > 100 ? 100 : pct);
        if (p == progPct) return;
        progPct = p;
        invalidate();
    }

    /** 进度条画在缩略图最上层（子视图画完再画，不会被图片盖住） */
    private void drawProgressBar(Canvas canvas) {
        if (progPct <= 0 || progPct >= 100) return;
        if (img == null || img.getVisibility() != VISIBLE) return;
        int w = getWidth(), ih = img.getBottom();
        if (w <= 0 || ih <= 0) return;
        if (ringPaint == null) ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        float ph = Math.max(3f, U.dp(getContext(), 3.5f));
        int save = canvas.save();
        // 跟着缩略图的圆角裁，底边不会露出直角
        float rad = U.dp(getContext(), U.RADIUS);
        rect2.set(0, 0, w, ih);
        clipPath.reset();
        clipPath.addRoundRect(rect2, rad, rad, Path.Direction.CW);
        canvas.clipPath(clipPath);
        ringPaint.setStyle(Paint.Style.FILL);
        ringPaint.setColor(0x99000000);
        canvas.drawRect(0, ih - ph, w, ih, ringPaint);
        ringPaint.setColor(U.ACC);
        canvas.drawRect(0, ih - ph, w * (progPct / 100f), ih, ringPaint);
        canvas.restoreToCount(save);
    }

    private final RectF rect2 = new RectF();
    private final Path clipPath = new Path();

    /** 左下角评分角标：null / 空串表示这条没有评分，直接不显示 */
    public void setRating(String r) {
        if (r == null || r.isEmpty()) {
            ratingTag.setVisibility(GONE);
        } else {
            ratingTag.setText(r);
            ratingTag.setVisibility(VISIBLE);
        }
        requestLayout();
    }
}

/** 网格：自适应（按原始比例）或统一格子（16:9 / 9:16），统一模式下按可用宽度精确分列 */
class FlowGrid extends ViewGroup {
    private int gap;
    private int labelH;
    private int itemH;
    private int count;
    private int mode; // 0=自适应 1=统一 16:9 2=统一 9:16
    private int inset; // 网格内边距：给「放大 + 外圈焦点框」留的余量

    public FlowGrid(Context c) {
        super(c);
        gap = U.dp(c, 16);
        labelH = U.dp(c, 46);
        itemH = U.dp(c, 152);
        // 网格四周留一圈内边距：卡片聚焦会放大、焦点框又画在图片外侧，
        // 这一圈余量保证放大后的框仍然落在网格自己的范围内，不会被网格边界切掉
        inset = U.dp(c, 16);
        setClipChildren(false);
        setClipToPadding(false);
    }

    @Override public void addView(View child, int index, ViewGroup.LayoutParams params) {
        super.addView(child, index, params);
        if (child instanceof Card) {
            Card cc = (Card) child;
            cc.setFit(fitFor(cc, ratioMode));  // 比例接近就铺满，差太多才留黑边
            if (cc.img != null) cc.img.setBackgroundColor(0xFF000000);
        }
        count++;
    }

    /** 动态比例：-1 用建卡时的固定 aspect；0 自动 / 1 16:9 / 2 9:16，只作用于没有刮削的卡 */
    private int ratioMode = -1;

    /** 这张卡要不要「完整显示留黑边」：自动档一律铺满；
     *  手动档按方向判断——图和卡框同方向（都竖或都横）就裁切铺满，反向才留黑边 */
    private boolean fitFor(Card cc, int m) {
        if (m <= 0) return false;
        float fa = aspectOf(cc);
        float ia = cc.frameAspect > 0f ? cc.frameAspect : cc.aspect;
        if (fa <= 0f || ia <= 0f) return true;
        if (fa < 1f) return ia > 0.95f;      // 卡是竖的：图也是竖 → 铺满；横图 → 留边
        return ia < 1.05f;                    // 卡是横的：图也是横 → 铺满；竖图 → 留边
    }

    public void setRatio(int m) {
        ratioMode = m;
        // 自动档：卡框按每张图自己的比例给，铺满不留黑边
        // 手动档：图和卡框差在 10% 以内就裁切铺满，差太多（横视频塞竖卡）才完整显示留黑边
        for (int i = 0; i < getChildCount(); i++) {
            View v = getChildAt(i);
            if (v instanceof Card) {
                Card cc = (Card) v;
                cc.setFit(fitFor(cc, m));
            }
        }
        requestLayout();
    }

    public void configure(int itemHeightDp, int thumbMode) {
        itemH = U.dp(getContext(), thumbMode == 3 ? Math.round(itemHeightDp * 1.45f) : itemHeightDp);
        mode = thumbMode;
        for (int i = 0; i < getChildCount(); i++) {
            View v = getChildAt(i);
            if (v instanceof Card) {
                Card cc = (Card) v;
                cc.setFit(fitFor(cc, ratioMode));
                if (cc.img != null) cc.img.setBackgroundColor(0xFF000000);
            }
        }
        requestLayout();
    }

    public int count() { return count; }

    /** 只给落在可视区（含预取余量）的卡片发图片请求 */
    public void loadVisible(int top, int bottom) {
        for (int i = 0; i < getChildCount(); i++) {
            View v = getChildAt(i);
            if (!(v instanceof Card)) continue;
            Card cd = (Card) v;
            if (!cd.lazy || cd.imgLoaded) continue;
            if (v.getBottom() >= top && v.getTop() <= bottom) cd.loadImage();
        }
    }

    @Override public void removeAllViews() {
        super.removeAllViews();
        count = 0;
        factory = null;
        vcount = 0;
        vFrom = -1;
        vTo = -1;
    }

    // ---------------- 窗口化：几千条也只实例化可视附近的卡 ----------------
    /** 按绝对下标造卡（真实卡由 Activity 侧负责数据，占位回收由本网格负责） */
    public interface ItemFactory { View create(int index); }

    private ItemFactory factory;
    private int vcount;
    private float[] vAsp;
    private boolean[] vFold;
    private int[] vL, vT, vW, vH;
    private int vFrom = -1, vTo = -1;

    /** 换一整批数据（进库 / 搜索 / 收藏都走这条）：只算几何，先不建卡 */
    public void startVirtual(int n, float[] asp, boolean[] fold, ItemFactory f) {
        factory = f;
        vcount = Math.max(0, n);
        vAsp = asp;
        vFold = fold;
        vL = new int[Math.max(1, vcount)];
        vT = new int[Math.max(1, vcount)];
        vW = new int[Math.max(1, vcount)];
        vH = new int[Math.max(1, vcount)];
        vFrom = -1;
        vTo = -1;
        super.removeAllViews();
        requestLayout();
    }

    /** 数据变长了（随机续批）：几何扩容，已实例化的窗口保留不动 */
    public void growVirtual(int n, float[] asp, boolean[] fold) {
        if (factory == null) return;
        vAsp = asp;
        vFold = fold;
        int cap = Math.max(1, n);
        vL = java.util.Arrays.copyOf(vL, cap);
        vT = java.util.Arrays.copyOf(vT, cap);
        vW = java.util.Arrays.copyOf(vW, cap);
        vH = java.util.Arrays.copyOf(vH, cap);
        vcount = Math.max(0, n);
        requestLayout();
    }

    public int virtualCount() { return vcount; }

    /** 把 [topPx, botPx] 可视范围（上下各留一屏余量）内的条目变成真实卡，其余回收 */
    public void ensureVirtual(int topPx, int botPx) {
        if (factory == null || vcount <= 0 || vT == null) return;
        if (vT[vcount - 1] == 0) return;          // 几何还没算
        int vh = U.dp(getContext(), 700);         // 余量按一屏算，不能按整表高度（否则一次绑几千张卡）
        int a = indexAt(topPx - vh);
        int b = indexAt(botPx + vh) + 1;
        if (b - a < 6) b = Math.min(vcount, a + 6);
        if (a == vFrom && b == vTo) return;
        for (int p = getChildCount() - 1; p >= 0; p--) {
            View c = getChildAt(p);
            int idx = (c instanceof Card) ? ((Card) c).vIndex : -1;
            if (idx < a || idx >= b) removeViewAt(p);
        }
        for (int i = a; i < b; i++) {
            if (findCard(i) != null) continue;
            View v = factory.create(i);
            if (v instanceof Card) ((Card) v).vIndex = i;
            int pos = 0;
            while (pos < getChildCount()
                    && getChildAt(pos) instanceof Card
                    && ((Card) getChildAt(pos)).vIndex < i) pos++;
            addView(v, pos, U.lpm(-2, -2, 0, 0, U.dp(getContext(), 14), U.dp(getContext(), 14)));
        }
        vFrom = a;
        vTo = b;
    }

    /** 只显示某一条（返回时恢复焦点位置）：把窗口直接挪过去 */
    public void ensureVirtualFor(int idx) {
        if (factory == null || vcount <= 0) return;
        int vh = U.dp(getContext(), 700);
        ensureVirtual(vT[Math.max(0, Math.min(vcount - 1, idx))] - vh,
                vT[Math.max(0, Math.min(vcount - 1, idx))] + vh * 2);
    }

    private View findCard(int idx) {
        for (int p = 0; p < getChildCount(); p++) {
            View c = getChildAt(p);
            if (c instanceof Card && ((Card) c).vIndex == idx) return c;
        }
        return null;
    }

    /** 返回键回顶：窗口先挪回最前面，焦点交给第 0 张（窗口化后 getChildAt(0) 不一定是第 0 条） */
    public boolean focusFirstItem() {
        if (factory == null || vcount <= 0) return false;
        ensureVirtual(0, U.dp(getContext(), 700));
        View c = findCard(0);
        if (c == null) return false;
        c.requestFocus();
        // 新绑出来的卡要等这一轮 layout 结束才开始生效；布局完了再补一次焦点，
        // 并且把宿主 ScrollView 真正滚回顶部（否则滚动条还停在原处，窗口又被挪回去）
        postDelayed(new Runnable() {
            @Override public void run() {
                View v = findCard(0);
                if (v != null) v.requestFocus();
                android.widget.ScrollView sv = hostScroll();
                if (sv != null) sv.scrollTo(0, 0);
            }
        }, 120);
        postDelayed(new Runnable() {
            @Override public void run() {
                View v = findCard(0);
                if (v != null) v.requestFocus();
                android.widget.ScrollView sv = hostScroll();
                if (sv != null) sv.scrollTo(0, 0);
            }
        }, 320);
        return true;
    }

    /** 当前焦点是不是就在第 0 条上 */
    public boolean firstItemFocused() {
        View f = findFocus();
        return f instanceof Card && ((Card) f).vIndex == 0;
    }

    /** 像素 y 落在哪一条（vT 单调，二分） */
    private int indexAt(int y) {
        int lo = 0, hi = vcount - 1;
        while (lo < hi) {
            int mid = (lo + hi) / 2;
            if (vT[mid] + vH[mid] < y) lo = mid + 1;
            else hi = mid;
        }
        return lo;
    }

    /** 按每条的最终比例先算出全部位置（窗口外的条目只占高度，不建 View） */
    private void computeVirtual(int w) {
        if (vcount <= 0) return;
        int roomH = Math.max(itemH + labelH, U.dp(getContext(), 60));
        if (uniform()) {
            int[] g = columnsFor(w);
            int cols = g[0], cw = g[1], ch = g[2];
            int rows = (vcount + cols - 1) / cols;
            for (int i = 0; i < vcount; i++) {
                vL[i] = inset + (i % cols) * (cw + gap);
                vT[i] = inset + (i / cols) * (ch + gap);
                vW[i] = cw;
                vH[i] = ch;
            }
            return;
        }
        int slotW = Math.max(1, Math.round(itemH * 9f / 16f));
        int minW = U.dp(getContext(), 24);
        // 1) 先按每张卡自己的比例装箱，记下每行的区间
        int[] rs = new int[vcount];
        int[] re = new int[vcount];
        int rowsN = 0, i = 0;
        while (i < vcount) {
            int s = i, used = 0;
            while (i < vcount) {
                int cw0 = vFold[i] ? slotW * 2 : Math.max(minW, Math.round(itemH * vAsp[i]));
                int addGap = (i > s) ? gap : 0;
                if (i > s && used + addGap + cw0 > w) break;
                used += addGap + cw0;
                i++;
            }
            if (i == s) i++;      // 单张卡比整行还宽时也不要死循环
            rs[rowsN] = s;
            re[rowsN] = i;
            rowsN++;
        }
        // 2) 手动档统一卡宽：按最满的那一行算拉伸后的宽度，所有行共用同一宽度，
        //    这样行与行之间对齐；拉伸幅度超过 20% 就不拉（大档一张铺满整宽会变成扁条）
        int uniW = 0;
        if (ratioMode > 0 && rowsN > 0) {
            int kmax = 0, natSum = 0;
            for (int r = 0; r < rowsN; r++) {
                int cnt = re[r] - rs[r];
                if (cnt > kmax) {
                    kmax = cnt;
                    natSum = 0;
                    for (int k = rs[r]; k < re[r]; k++) {
                        natSum += vFold[k] ? slotW * 2 : Math.max(minW, Math.round(itemH * vAsp[k]));
                    }
                }
            }
            if (kmax > 0) {
                int nat = natSum / kmax;
                int fit = (w - gap * (kmax - 1)) / kmax;
                if (fit > 0 && (nat <= 0 || fit <= Math.round(nat * 1.2f))) uniW = fit;
            }
        }
        // 3) 摆位：每行等高；最后一行不额外拉伸，但宽度与其它行一致
        int y = inset;
        for (int r = 0; r < rowsN; r++) {
            int x = inset;
            for (int k = rs[r]; k < re[r]; k++) {
                int base = vFold[k] ? slotW * 2 : Math.max(minW, Math.round(itemH * vAsp[k]));
                int cw1 = uniW > 0 ? uniW : base;
                if (cw1 > w) cw1 = w;
                vL[k] = x;
                vT[k] = y;
                vW[k] = cw1;
                vH[k] = roomH;
                x += cw1 + gap;
            }
            y += roomH + gap;
        }
    }

    /** 虚拟模式下的方向键：按几何找最近一条（上下左右都能走） */
    private int spatialVirtual(int from, int keyCode) {
        int fx = vL[from] + vW[from] / 2, fy = vT[from] + vH[from] / 2;
        int best = -1;
        float bestScore = Float.MAX_VALUE;
        for (int i = 0; i < vcount; i++) {
            if (i == from) continue;
            int cx = vL[i] + vW[i] / 2, cy = vT[i] + vH[i] / 2;
            int dx = cx - fx, dy = cy - fy;
            boolean ok;
            float main, side;
            if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_LEFT) {
                ok = dx < -1; main = -dx; side = Math.abs(dy);
            } else if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_RIGHT) {
                ok = dx > 1; main = dx; side = Math.abs(dy);
            } else if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_UP) {
                ok = dy < -1; main = -dy; side = Math.abs(dx);
            } else {
                ok = dy > 1; main = dy; side = Math.abs(dx);
            }
            if (!ok) continue;
            float score = main + side * 2.2f;
            if (score < bestScore) {
                bestScore = score;
                best = i;
            }
        }
        return best;
    }

    private boolean uniform() { return mode == 1 || mode == 2 || mode == 3; }

    /** 横屏卡（宽高比 >= 1）参与自适应缩放；竖屏卡保持原尺寸 */
    private boolean isLandscape(View v) {
        float a = v instanceof Card ? ((Card) v).aspect : 16f / 9f;
        return a >= 1f;
    }

    private float ratio() {
        if (mode == 2) return 9f / 16f;
        if (mode == 3) return 2f / 3f;
        return 16f / 9f;
    }

    /** 一条 9:16 卡占 1 个槽位；16:9 卡按比例四舍五入占整槽位（通常 2 槽） */
    private int slotsOf(View c) {
        if (c instanceof Card && ((Card) c).folderCard) return 2;
        float a = aspectOf(c);
        int sp = Math.round(a * 16f / 9f);
        if (sp < 1) sp = 1;
        if (sp > 4) sp = 4;
        return sp;
    }

    /** 自适应：卡片宽度 = 缩略图高度 × 视频比例（竖屏窄、横屏宽），高度统一 */
    private int widthOf(View c) {
        float a = aspectOf(c);
        if (a < 0.7f) a = 0.7f;
        if (a > 2.6f) a = 2.6f;
        return Math.max(Math.round(itemH * a), U.dp(getContext(), 110));
    }

    /** 视频原始宽高比（限制在 0.45~3 之间，避免极端长条） */
    private float aspectOf(View c) {
        float a;
        if (c instanceof Card && ratioMode >= 0) {
            Card cd = (Card) c;
            // 刮削竖海报最优先：不管当前选 16:9 还是 9:16，海报一律按 9:16 竖卡，
            // 免得竖海报被压进横卡里只剩中间一条
            if (cd.posterCard) a = 9f / 16f;
            else if (ratioMode == 1) a = 16f / 9f;
            else if (ratioMode == 2) a = 9f / 16f;
            else if (cd.videoAspect > 0f) a = cd.videoAspect < 1f ? 9f / 16f : 16f / 9f;
            else if (cd.frameAspect > 0f) a = cd.frameAspect < 1f ? 9f / 16f : 16f / 9f;
            else a = cd.aspect;
        } else {
            a = c instanceof Card ? ((Card) c).aspect : 16f / 9f;
        }
        if (a < 0.45f) a = 0.45f;
        if (a > 3f) a = 3f;
        return a;
    }

    /** 统一模式：按宽度算出列数，卡片宽度自适应填满整行，高度按比例反推 */
    private int[] columnsFor(int w) {
        float r = ratio();
        int tw = Math.max(1, Math.round(itemH * r));
        int cols = Math.max(1, (int) Math.round((double) (w + gap) / (double) (tw + gap)));
        int cw = (w - gap * (cols - 1)) / cols;
        int ch = Math.round(cw / r) + labelH;
        return new int[]{cols, cw, ch};
    }

    /** 卡片的自然宽度：按自身比例和行高算（竖卡窄、横卡宽），最后整行拉伸填满 */
    private int cardW(View c, int slotW) {
        if (c instanceof Card && ((Card) c).folderCard) return slotW * 2;
        // 严格按比例算宽度，不设最小宽度下限：否则小档会被撑宽，比例就跑掉了
        return Math.max(U.dp(getContext(), 24), Math.round(itemH * aspectOf(c)));
    }

    /** 每行的卡数（用于方向键导航） */
    private int rowStride() {
        int n = getChildCount();
        if (n <= 1) return Math.max(1, n);
        if (uniform()) return Math.max(1, columnsFor(getWidth())[0]);
        int y0 = getChildAt(0).getTop();
        int c = 1;
        for (int i = 1; i < n; i++) {
            if (getChildAt(i).getTop() != y0) break;
            c++;
        }
        return Math.max(1, c);
    }

    /** 按几何位置找方向上最近的卡片：上下左右都能走 */
    private View spatialTarget(View from, int keyCode) {
        int fw = from.getWidth(), fh = from.getHeight();
        float fx = from.getLeft() + fw / 2f, fy = from.getTop() + fh / 2f;
        View best = null;
        float bestScore = Float.MAX_VALUE;
        for (int i = 0; i < getChildCount(); i++) {
            View c = getChildAt(i);
            if (c == from) continue;
            float cx = c.getLeft() + c.getWidth() / 2f;
            float cy = c.getTop() + c.getHeight() / 2f;
            float dx = cx - fx, dy = cy - fy;
            boolean ok;
            float main, side;
            if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_LEFT) {
                ok = dx < -1; main = -dx; side = Math.abs(dy);
            } else if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_RIGHT) {
                ok = dx > 1; main = dx; side = Math.abs(dy);
            } else if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_UP) {
                ok = dy < -1; main = -dy; side = Math.abs(dx);
            } else {
                ok = dy > 1; main = dy; side = Math.abs(dx);
            }
            if (!ok) continue;
            // 同方向优先、垂直偏差惩罚更重，保证「上一行 / 下一行」不会被斜着抢走
            float score = main + side * 2.2f;
            if (score < bestScore) {
                bestScore = score;
                best = c;
            }
        }
        return best;
    }

    @Override public boolean dispatchKeyEvent(android.view.KeyEvent e) {
        if (e.getAction() == android.view.KeyEvent.ACTION_DOWN) {
            int k = e.getKeyCode();
            if (k == android.view.KeyEvent.KEYCODE_DPAD_LEFT
                    || k == android.view.KeyEvent.KEYCODE_DPAD_RIGHT
                    || k == android.view.KeyEvent.KEYCODE_DPAD_UP
                    || k == android.view.KeyEvent.KEYCODE_DPAD_DOWN) {
                View f = findFocus();
                if (factory != null && vcount > 0) {
                    // 窗口化：方向键按整张表的几何算目标，目标不在窗口里就先把它拉回来
                    int fi = (f instanceof Card) ? ((Card) f).vIndex : -1;
                    if (fi >= 0) {
                        int t = spatialVirtual(fi, k);
                        if (t >= 0) {
                            ensureVirtualFor(t);
                            final int ti = t;
                            postDelayed(new Runnable() {
                                @Override public void run() {
                                    View v = findCard(ti);
                                    if (v != null) v.requestFocus();
                                }
                            }, 30);
                            return true;
                        }
                    }
                    return false;
                }
                int idx = f == null ? -1 : indexOfChild(f);
                if (idx >= 0 && !uniform()) {
                    // 自适应模式卡片大小不一，按屏幕上的真实位置做二维导航
                    View target2 = spatialTarget(f, k);
                    if (target2 != null) {
                        target2.requestFocus();
                        int top = target2.getTop() - getHeight() / 3;
                        if (target2.getTop() < 0 || target2.getBottom() > getHeight()) {
                            // 交给外层 ScrollView 滚动到可见
                        }
                        return true;
                    }
                    return false;
                }
                if (idx >= 0) {
                    int stride = rowStride();
                    int target = -1;
                    if (k == android.view.KeyEvent.KEYCODE_DPAD_LEFT) {
                        if (idx % stride != 0) target = idx - 1;
                    } else if (k == android.view.KeyEvent.KEYCODE_DPAD_RIGHT) {
                        if (idx % stride != stride - 1 && idx + 1 < getChildCount()) target = idx + 1;
                    } else if (k == android.view.KeyEvent.KEYCODE_DPAD_UP) {
                        if (idx - stride >= 0) target = idx - stride;
                    } else {
                        if (idx + stride < getChildCount()) target = idx + stride;
                    }
                    if (target >= 0) {
                        getChildAt(target).requestFocus();
                        return true;
                    }
                    return false;   // 走到边界，交给上一层（比如回到搜索框）
                }
            }
        }
        return super.dispatchKeyEvent(e);
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int full = MeasureSpec.getSize(widthSpec);
        if (full <= 0) full = getResources().getDisplayMetrics().widthPixels;
        int w = Math.max(1, full - inset * 2);   // 分列按去掉内边距后的净宽算
        if (factory != null) {
            // 窗口化：高度按全部条目算出来，真正 measure 的只有窗口内的卡
            if (vcount <= 0) {
                setMeasuredDimension(full, resolveSize(0, heightSpec));
                return;
            }
            computeVirtual(w);
            for (int i = 0; i < getChildCount(); i++) {
                View c = getChildAt(i);
                int idx = (c instanceof Card) ? ((Card) c).vIndex : -1;
                if (idx < 0 || idx >= vcount) continue;
                c.measure(MeasureSpec.makeMeasureSpec(Math.max(1, vW[idx]), MeasureSpec.EXACTLY),
                        MeasureSpec.makeMeasureSpec(Math.max(1, vH[idx]), MeasureSpec.EXACTLY));
            }
            int totalH = vT[vcount - 1] + vH[vcount - 1] + inset;
            // 放在 ScrollView 里要撑满整张表的高度：不能用 resolveSize 让父容器的 AT_MOST 把它截成一屏
            setMeasuredDimension(full, totalH);
            return;
        }
        int totalH;
        if (uniform()) {
            int[] g = columnsFor(w);
            int cols = g[0], cw = g[1], ch = g[2];
            int n = getChildCount();
            for (int i = 0; i < n; i++) {
                View c0 = getChildAt(i);
                boolean fold0 = c0 instanceof Card && ((Card) c0).folderCard;
                int hh = fold0 ? (itemH + labelH) : ch;
                c0.measure(MeasureSpec.makeMeasureSpec(cw, MeasureSpec.EXACTLY),
                        MeasureSpec.makeMeasureSpec(hh, MeasureSpec.EXACTLY));
            }
            int rows = n == 0 ? 0 : (n + cols - 1) / cols;
            totalH = rows == 0 ? 0 : rows * ch + (rows - 1) * gap;
        } else {
            // 自适应模式：竖屏(9:16)卡保持原始尺寸不变；横屏(16:9)卡按整行剩余空隙整体缩放。
            // 1) 先按每张卡自己的真实宽度装箱，记下每行的区间
            int n = getChildCount();
            int roomH = itemH + labelH;
            int slotW = Math.max(1, Math.round(itemH * 9f / 16f));
            int[] rs = new int[Math.max(1, n)];
            int[] re = new int[Math.max(1, n)];
            int rowsN = 0, i = 0;
            while (i < n) {
                int s = i, used = 0;
                while (i < n) {
                    View c0 = getChildAt(i);
                    int cw0 = cardW(c0, slotW);
                    int addGap = (i > s) ? gap : 0;
                    if (i > s && used + addGap + cw0 > w) break;
                    used += addGap + cw0;
                    i++;
                }
                if (i == s) i++;
                rs[rowsN] = s;
                re[rowsN] = i;
                rowsN++;
            }
            // 2) 手动档统一卡宽：按最满那一行算拉伸后的宽度，所有行共用同一宽度（行行对齐）；
            //    自动档不拉伸；拉伸幅度超过 20% 就不拉（大档一张铺满整宽会变成扁条）
            int uniW = 0;
            if (ratioMode > 0 && rowsN > 0) {
                int kmax = 0, natSum = 0;
                for (int r = 0; r < rowsN; r++) {
                    int cnt = re[r] - rs[r];
                    if (cnt > kmax) {
                        kmax = cnt;
                        natSum = 0;
                        for (int k = rs[r]; k < re[r]; k++) natSum += cardW(getChildAt(k), slotW);
                    }
                }
                if (kmax > 0) {
                    int nat = natSum / kmax;
                    int fit = (w - gap * (kmax - 1)) / kmax;
                    if (fit > 0 && (nat <= 0 || fit <= Math.round(nat * 1.2f))) uniW = fit;
                }
            }
            // 3) 摆位：同一行一律等高；最后一行不额外拉伸，但宽度与其它行一致
            int y = 0;
            for (int r = 0; r < rowsN; r++) {
                for (int k = rs[r]; k < re[r]; k++) {
                    View c = getChildAt(k);
                    int cw = uniW > 0 ? uniW : cardW(c, slotW);
                    if (cw > w) cw = w;
                    c.measure(MeasureSpec.makeMeasureSpec(cw, MeasureSpec.EXACTLY),
                            MeasureSpec.makeMeasureSpec(Math.max(roomH, U.dp(getContext(), 60)),
                                    MeasureSpec.EXACTLY));
                }
                y += roomH + gap;
            }
            totalH = n == 0 ? 0 : y - gap;
        }
        setMeasuredDimension(full, resolveSize(totalH + inset * 2, heightSpec));
    }

    @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int w = (r - l) - inset * 2;
        if (factory != null) {
            // 窗口化：只把窗口内的卡摆到算好的位置上，窗口外没有 View（高度已经由 onMeasure 撑住）
            for (int i = 0; i < getChildCount(); i++) {
                View c = getChildAt(i);
                int idx = (c instanceof Card) ? ((Card) c).vIndex : -1;
                if (idx < 0 || idx >= vcount) continue;
                c.layout(vL[idx], vT[idx], vL[idx] + c.getMeasuredWidth(), vT[idx] + c.getMeasuredHeight());
            }
            // 第一批卡：进来先绑可视区，剩下的等滚动时再按需绑
            if (vFrom < 0 && vcount > 0) ensureVirtual(0, U.dp(getContext(), 700));
            return;
        }
        if (uniform()) {
            int[] g = columnsFor(w);
            int cols = g[0], cw = g[1], ch = g[2];
            int n = getChildCount();
            for (int i = 0; i < n; i++) {
                int col = i % cols, row = i / cols;
                int left = col * (cw + gap) + inset, top = row * (ch + gap) + inset;
                View cc0 = getChildAt(i);
                int hh0 = cc0.getMeasuredHeight();
                int off0 = (ch - hh0) / 2;
                cc0.layout(left, top + off0, left + cw, top + off0 + hh0);
            }
            return;
        }
        int n = getChildCount();
        int y = 0, i = 0;
        int tol = U.dp(getContext(), 8);
        while (i < n) {
            int s = i, x = 0, rowH = 0;
            while (i < n) {
                View c = getChildAt(i);
                int cw = c.getMeasuredWidth();
                int add = cw + (i > s ? gap : 0);
                if (i > s && x + add - gap > w + tol) break;
                rowH = Math.max(rowH, c.getMeasuredHeight());
                x += cw + gap;
                i++;
            }
            int xx = inset;
            for (int k = s; k < i; k++) {
                View c = getChildAt(k);
                int cw = c.getMeasuredWidth(), chh = c.getMeasuredHeight();
                int ty = y + (rowH - chh) / 2 + inset;
                c.layout(xx, ty, xx + cw, ty + chh);
                xx += cw + gap;
            }
            if (rowH == 0) rowH = itemH + labelH;
            y += rowH + gap;
        }
    }

    /** 找出包住自己的滚动容器（用于只绘制视口内的卡片） */
    private android.widget.ScrollView hostScroll() {
        android.view.ViewParent p = getParent();
        while (p instanceof View) {
            if (p instanceof android.widget.ScrollView) return (android.widget.ScrollView) p;
            p = p.getParent();
        }
        return null;
    }

    @Override protected void dispatchDraw(Canvas canvas) {
        // 只画视口（含预取余量）内的卡片：滚出屏幕的卡不参与绘制，
        // 这样上下翻时的绘制量恒定，效果等同于 Leanback 的视图回收
        android.widget.ScrollView host = hostScroll();
        int lo = 0, hi = getHeight();
        if (host != null) {
            int sy = host.getScrollY() - getTop();
            int pad = U.dp(getContext(), 300);
            lo = sy - pad;
            hi = sy + host.getHeight() + pad;
        }
        for (int i = 0; i < getChildCount(); i++) {
            View ch = getChildAt(i);
            if (ch.getVisibility() != android.view.View.VISIBLE) continue;
            if (ch.getBottom() < lo || ch.getTop() > hi) continue;
            int save = canvas.save();
            canvas.translate(ch.getLeft() + ch.getTranslationX(), ch.getTop() + ch.getTranslationY());
            float sx = ch.getScaleX(), ys = ch.getScaleY();
            if (sx != 1f || ys != 1f) {
                canvas.translate(ch.getPivotX(), ch.getPivotY());
                canvas.scale(sx, ys);
                canvas.translate(-ch.getPivotX(), -ch.getPivotY());
            }
            ch.draw(canvas);
            canvas.restoreToCount(save);
        }
        Card.drawRingOf(canvas, this);   // 焦点框由网格自己画，不会被网格/卡片边界裁
    }
}

/** 横排容器：焦点框在容器层绘制，避免被滚动区和卡片边界裁掉 */
class RingRow extends android.widget.LinearLayout {
    public RingRow(Context c) {
        super(c);
        setClipChildren(false);
        setClipToPadding(false);
        // 给焦点环留出外扩空隙：环画在本行画布上，行自身没有内边距时贴边那一侧会被裁掉
        int pad = U.dp(c, U.RING_OUTSET) + U.dp(c, 1);
        setPadding(pad, pad, pad, pad);
    }

    @Override protected void dispatchDraw(Canvas canvas) {
        super.dispatchDraw(canvas);
        Card.drawRingOf(canvas, this);
    }
}

/** 文件夹图标（自绘，避免依赖图片资源） */
class FolderGlyph extends View {
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private int color = U.ACC;

    public FolderGlyph(Context c) {
        super(c);
        p.setStyle(Paint.Style.FILL);
    }

    public void setColor(int c) { color = c; invalidate(); }

    @Override protected void onDraw(Canvas canvas) {
        int w = getWidth(), h = getHeight();
        p.setColor(color);
        float r = w * 0.06f;
        RectF body = new RectF(w * 0.06f, h * 0.28f, w * 0.94f, h * 0.86f);
        canvas.drawRoundRect(body, r, r, p);
        RectF tab = new RectF(w * 0.10f, h * 0.16f, w * 0.46f, h * 0.34f);
        canvas.drawRoundRect(tab, r, r, p);
        p.setColor(0x33000000);
        canvas.drawRect(w * 0.06f, h * 0.36f, w * 0.94f, h * 0.42f, p);
    }
}

/** 播放器进度条：细轨道 + 白色进度 + 端点圆钮（不跟随主题绿） */
class SeekBarView extends View {
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    public long pos, len, buffered;
    private int focusOn;

    public SeekBarView(Context c) {
        super(c);
        p.setAntiAlias(true);
    }

    public void setFocusBar(boolean f) { focusOn = f ? 1 : 0; invalidate(); }

    public void update(long pos, long len) {
        this.pos = pos;
        this.len = len;
        invalidate();
    }

    @Override protected void onDraw(Canvas canvas) {
        int w = getWidth(), h = getHeight();
        int cy = h / 2;
        float barH = U.dp(getContext(), 3);
        float rad = barH / 2f;
        float pct = len > 0 ? Math.min(1f, Math.max(0f, (float) pos / len)) : 0f;
        float bpct = len > 0 ? Math.min(1f, Math.max(0f, (float) buffered / len)) : 0f;
        float headR = U.dp(getContext(), 7);

        p.setShader(null);
        p.setStyle(Paint.Style.FILL);
        // 轨道
        p.setColor(0x40FFFFFF);
        canvas.drawRoundRect(new RectF(0, cy - barH / 2f, w, cy + barH / 2f), rad, rad, p);
        // 已缓冲
        if (bpct > 0.001f) {
            p.setColor(0x66FFFFFF);
            canvas.drawRoundRect(new RectF(0, cy - barH / 2f, Math.max(barH, w * bpct), cy + barH / 2f),
                    rad, rad, p);
        }
        // 已播放
        float endX = w * pct;
        if (endX > barH) {
            p.setColor(0xFFFFFFFF);
            canvas.drawRoundRect(new RectF(0, cy - barH / 2f, endX, cy + barH / 2f), rad, rad, p);
        }
        // 末端白色圆头（实心，不再套主题色内圈）
        float hx = Math.max(headR, Math.min(w - headR, endX));
        p.setColor(0xFFFFFFFF);
        canvas.drawCircle(hx, cy, headR, p);
    }
}

/** 圆形头像：有图按 center-crop 裁成圆，没图显示首字母 */
class AvatarView extends View {
    private Bitmap bmp;
    private String letter = "?";
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private BitmapShader shader;
    private final Matrix matrix = new Matrix();

    public AvatarView(Context c) { super(c); }

    public void setBitmap(Bitmap b) {
        bmp = b;
        shader = b == null ? null : new BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        invalidate();
    }

    public void setLetter(String s) {
        letter = (s == null || s.isEmpty()) ? "?" : s.substring(0, 1).toUpperCase();
        invalidate();
    }

    @Override protected void onDraw(Canvas canvas) {
        int w = getWidth(), h = getHeight();
        float r = Math.min(w, h) / 2f;
        float cx = w / 2f, cy = h / 2f;
        p.setShader(null);
        p.setColor(U.PANEL2);
        canvas.drawCircle(cx, cy, r, p);
        if (bmp != null && shader != null) {
            float scale = Math.max((2 * r) / bmp.getWidth(), (2 * r) / bmp.getHeight());
            matrix.setScale(scale, scale);
            matrix.postTranslate(cx - bmp.getWidth() * scale / 2f, cy - bmp.getHeight() * scale / 2f);
            shader.setLocalMatrix(matrix);
            p.setShader(shader);
            canvas.drawCircle(cx, cy, r - U.dp(getContext(), 1), p);
            p.setShader(null);
        } else {
            p.setColor(U.TXT);
            p.setTextSize(r * 0.95f);
            p.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
            U.face(p);   // 设置里手选的字体也管自绘文字
            p.setTextAlign(Paint.Align.CENTER);
            Paint.FontMetrics fm = p.getFontMetrics();
            canvas.drawText(letter, cx, cy - (fm.ascent + fm.descent) / 2f, p);
        }
        p.setShader(null);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(U.dp(getContext(), 2));
        p.setColor(U.SOFT);
        canvas.drawCircle(cx, cy, r - U.dp(getContext(), 1), p);
        p.setStyle(Paint.Style.FILL);
    }
}

/** 图标按钮：圆角底 + 矢量小图标（可选文字标签），支持焦点框 */
class IconBtn extends View {
    public static final String BACK = "back", GEAR = "gear", GRID = "grid",
            FOLDER = "folder", CLOSE = "close", SERVER = "server",
            USER = "user", EYE = "eye", PLAY = "play", HEART = "heart",
            TRASH = "trash", INFO = "info", SORT = "sort", SEARCH = "search",
            RESTART = "restart", IMAGE = "image", CHECK = "check", HOME = "home";

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final String glyph;
    private String label;
    private boolean circle = false;
    private final float dens;
    private boolean active;
    private boolean filled;
    private boolean accentPill;

    /** 收藏这类图标可用实心表示已选中 */
    public void setFilled(boolean f) { filled = f; invalidate(); }

    public IconBtn(Context c, String glyph, String label) {
        super(c);
        this.glyph = glyph == null ? GRID : glyph;
        this.label = label == null ? "" : label;
        dens = c.getResources().getDisplayMetrics().density;
        setFocusable(true);
        setFocusableInTouchMode(false);
        setClickable(true);
        p.setTypeface(Typeface.DEFAULT_BOLD);
    }

    private int dp(float v) { return (int) (v * dens); }

    /** 选中态（分段按钮用）：深绿底 */
    public void setActive(boolean a) { active = a; invalidate(); }

    /** 头像胶囊：常态就是实心强调色药丸（如右上角账号按钮） */
    public void setAccentPill(boolean a) { accentPill = a; invalidate(); }

    /** 动态改按钮文字（例如 收藏 / 已收藏）后重新量宽 */
    public void setLabel(String l) {
        label = l == null ? "" : l;
        requestLayout();
        invalidate();
    }

    private int textW() {
        if (label.isEmpty()) return 0;
        p.setTextSize(dp(14));
        return (int) p.measureText(label);
    }

    private int wantedW() {
        int tw = textW();
        return dp(18) + dp(24) + (tw > 0 ? dp(8) + tw : 0) + dp(18);
    }

    @Override protected void onMeasure(int wSpec, int hSpec) {
        int w = resolveSize(Math.max(dp(54), wantedW()), wSpec);
        int h = resolveSize(dp(42), hSpec);
        setMeasuredDimension(w, h);
    }

    public void setCircle(boolean b) { circle = b; }

    /** 顶栏导航按钮：选中时不做红心/绿底等语义染色，和旁边按钮完全同款 */
    private boolean plain;
    public void setPlain(boolean b) { plain = b; }

    /** 焦点态是自己画的，必须手动重绘：牵动父容器一起刷新，避免停在旧底色 */
    @Override protected void onFocusChanged(boolean gain, int dir, android.graphics.Rect prev) {
        super.onFocusChanged(gain, dir, prev);
        invalidate();
    }

    @Override protected void onDraw(Canvas canvas) {
        int w = getWidth(), h = getHeight();
        float r = circle ? h / 2f : (U.ST ? 0 : (U.DG ? dp(1) : dp(6)));
        boolean foc = isFocused();
        boolean neon = U.EMBY;   // 炫酷主题：渐变底 + 青紫描边 + 发光
        // 状态图标（已观看 / 收藏）：只让里面那个图标变色，底色和描边跟旁边的普通图标键完全一样
        boolean stateGlyph = (CHECK.equals(glyph) || EYE.equals(glyph))
                || (HEART.equals(glyph) && !plain);
        boolean tintBg = !(stateGlyph || plain);
        p.setStyle(Paint.Style.FILL);
        // 底色只表示状态，焦点一律由 U.drawRing 的环表达（和首页/海报卡同一套）
        if (neon) {
            // 苹果风：半透明白玻璃圆角块，选中的那一项变成黑实底
            float rr = circle ? h / 2f : (U.ST ? 0 : dp(6));
            p.setColor((active && tintBg) ? 0xFF1C1C1E : 0xFFF2F2F7);
            canvas.drawRoundRect(new RectF(0, 0, w, h), rr, rr, p);
            if (!(active && tintBg)) {
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(dp(1));
                p.setColor(0x0F000000);
                canvas.drawRoundRect(new RectF(dp(0.5f), dp(0.5f), w - dp(0.5f), h - dp(0.5f)), rr, rr, p);
                p.setStyle(Paint.Style.FILL);
            }
            if (foc) {   // 苹果风焦点：一圈干净的白边（不发光）
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(dp(2.5f));
                p.setColor((active && tintBg) ? 0xFFFFFFFF : 0xFF1C1C1E);
                float e = dp(2.5f) / 2f;
                canvas.drawRoundRect(new RectF(-e, -e, w + e, h + e),
                        rr + dp(1.5f), rr + dp(1.5f), p);
                p.setStyle(Paint.Style.FILL);
            }
        } else {
        int base;
        if (active && tintBg) base = U.ST ? 0xFFFF2E77 : (U.DG ? U.CHIP_ON_BG : U.SOFT);   // 玻璃选中=半透明白；黑绿选中=嫡哥绿嫦底
        else if (U.THEME == 1) base = circle ? 0xCC3A3B40 : 0xCC2E2F34;
        else base = circle ? U.SOFT : U.PANEL2;
        p.setColor(base);
        canvas.drawRoundRect(new RectF(0, 0, w, h), r, r, p);
        if (U.THEME == 1) {
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(dp(1f));
            p.setColor(U.BORDER);
            canvas.drawRoundRect(new RectF(dp(1), dp(1), w - dp(1), h - dp(1)), r, r, p);
        } else if (U.ST) {
            // 潮牌：纯黑标签 + 白细描边（贴纸感）
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(dp(1.6f));
            p.setColor(active && tintBg ? 0xFF0A0A0A : 0xFFFFFFFF);
            canvas.drawRect(dp(0.8f), dp(0.8f), w - dp(0.8f), h - dp(0.8f), p);
        }
        }

        int ic = circle ? dp(26) : dp(24);
        float ix = (circle || textW() == 0) ? (w - ic) / 2f : dp(18);
        float iy = (h - ic) / 2f;
        // 收藏点亮 → 红心；已观看点亮 → 绿眼；其余选中态图标统一亮白
        int iconColor;
        if (HEART.equals(glyph) && (filled || active) && !plain) iconColor = 0xFFEF4B5B;
        else if ((CHECK.equals(glyph) || EYE.equals(glyph)) && active) iconColor = 0xFF35C46B;
        else if (neon) iconColor = (active && tintBg) ? 0xFFFFFFFF : 0xFF1C1C1E;
        else iconColor = (active && tintBg) ? 0xFFFFFFFF : (accentPill ? U.TXT : (U.THEME == 1 ? 0xFFFFFFFF : U.DIM));
        p.setColor(iconColor);
        p.setStrokeWidth(dp(neon ? 2.4f : 2f));
        p.setStyle(Paint.Style.STROKE);
        
        drawGlyph(canvas, ix, iy, ic);
        p.setShader(null);

        int tw = textW();
        if (tw > 0) {
            p.setStyle(Paint.Style.FILL);
            // 文字跟图标同步：选中态亮白/红，避免深色字压在彩色底上糊掉
            if (HEART.equals(glyph) && (filled || active) && !plain) p.setColor(0xFFFFFFFF);
            else if (neon && active && tintBg) p.setColor(0xFFFFFFFF);   // 黑/深底上用白字
            else p.setColor((active && tintBg) ? 0xFFFFFFFF : U.TXT);
            p.setTextSize(dp(14));
            U.face(p);   // 顶栏「首页 / 收藏」这类自绘标签也跟手选字体
            float tx = ix + ic + dp(8);
            float ty = h / 2f - (p.descent() + p.ascent()) / 2f;
            canvas.drawText(label, tx, ty, p);
        }
        if (foc) {
            U.drawRing(canvas, w, h, (U.ST ? 0 : (U.DG ? dp(1) : dp(6))), dens, p);
            p.setStyle(Paint.Style.FILL);
        }
    }

    private void drawGlyph(Canvas c, float x, float y, int s) {
        float cx = x + s / 2f, cy = y + s / 2f;
        float q = s / 2f;
        Path pa = new Path();
        if (BACK.equals(glyph)) {
            pa.moveTo(x + s * 0.18f, cy);
            pa.lineTo(x + s * 0.52f, y + s * 0.16f);
            pa.moveTo(x + s * 0.18f, cy);
            pa.lineTo(x + s * 0.52f, y + s * 0.84f);
            pa.moveTo(x + s * 0.20f, cy);
            pa.lineTo(x + s * 0.92f, cy);
            c.drawPath(pa, p);
        } else if (GEAR.equals(glyph)) {
            // iOS 风格「推子」图标：三条滑杆 + 实心圆点
            float bx1 = x + s * 0.14f, bx2 = x + s * 0.86f;
            float[] ys = {y + s * 0.25f, y + s * 0.5f, y + s * 0.75f};
            Path bars = new Path();
            for (int i = 0; i < 3; i++) {
                bars.moveTo(bx1, ys[i]);
                bars.lineTo(bx2, ys[i]);
            }
            c.drawPath(bars, p);
            p.setStyle(Paint.Style.FILL);
            c.drawCircle(bx1 + (bx2 - bx1) * 0.70f, ys[0], q * 0.16f, p);
            c.drawCircle(bx1 + (bx2 - bx1) * 0.32f, ys[1], q * 0.16f, p);
            c.drawCircle(bx1 + (bx2 - bx1) * 0.58f, ys[2], q * 0.16f, p);
            p.setStyle(Paint.Style.STROKE);
        } else if (HOME.equals(glyph)) {
            // 房子：屋顶两条斜线 + 屋身方框
            Path roof = new Path();
            roof.moveTo(cx, y + s * 0.08f);
            roof.lineTo(x + s * 0.90f, cy + s * 0.12f);
            roof.moveTo(cx, y + s * 0.08f);
            roof.lineTo(x + s * 0.10f, cy + s * 0.12f);
            c.drawPath(roof, p);
            Path body = new Path();
            body.moveTo(x + s * 0.24f, cy + s * 0.04f);
            body.lineTo(x + s * 0.24f, y + s * 0.88f);
            body.lineTo(x + s * 0.76f, y + s * 0.88f);
            body.lineTo(x + s * 0.76f, cy + s * 0.04f);
            c.drawPath(body, p);
        } else if (GRID.equals(glyph)) {
            float g = q * 0.10f, b = q * 0.62f;
            Path sq = new Path();
            sq.addRect(cx - b - g / 2, cy - b - g / 2, cx - g / 2, cy - g / 2, Path.Direction.CW);
            sq.addRect(cx + g / 2, cy - b - g / 2, cx + b + g / 2, cy - g / 2, Path.Direction.CW);
            sq.addRect(cx - b - g / 2, cy + g / 2, cx - g / 2, cy + b + g / 2, Path.Direction.CW);
            sq.addRect(cx + g / 2, cy + g / 2, cx + b + g / 2, cy + b + g / 2, Path.Direction.CW);
            c.drawPath(sq, p);
        } else if (FOLDER.equals(glyph)) {
            pa.moveTo(x + s * 0.08f, y + s * 0.40f);
            pa.lineTo(x + s * 0.08f, y + s * 0.24f);
            pa.lineTo(x + s * 0.42f, y + s * 0.24f);
            pa.lineTo(x + s * 0.50f, y + s * 0.34f);
            pa.lineTo(x + s * 0.92f, y + s * 0.34f);
            pa.lineTo(x + s * 0.92f, y + s * 0.40f);
            pa.moveTo(x + s * 0.08f, y + s * 0.40f);
            pa.lineTo(x + s * 0.08f, y + s * 0.78f);
            pa.lineTo(x + s * 0.92f, y + s * 0.78f);
            pa.lineTo(x + s * 0.92f, y + s * 0.40f);
            c.drawPath(pa, p);
        } else if (CLOSE.equals(glyph)) {
            pa.moveTo(x + s * 0.2f, y + s * 0.2f);
            pa.lineTo(x + s * 0.8f, y + s * 0.8f);
            pa.moveTo(x + s * 0.8f, y + s * 0.2f);
            pa.lineTo(x + s * 0.2f, y + s * 0.8f);
            c.drawPath(pa, p);
        } else if (SERVER.equals(glyph)) {
            Path r1 = new Path();
            r1.addRect(x + s * 0.16f, y + s * 0.18f, x + s * 0.84f, y + s * 0.46f, Path.Direction.CW);
            Path r2 = new Path();
            r2.addRect(x + s * 0.16f, y + s * 0.54f, x + s * 0.84f, y + s * 0.82f, Path.Direction.CW);
            c.drawPath(r1, p);
            c.drawPath(r2, p);
            p.setStyle(Paint.Style.FILL);
            c.drawCircle(x + s * 0.28f, y + s * 0.32f, q * 0.09f, p);
            c.drawCircle(x + s * 0.28f, y + s * 0.68f, q * 0.09f, p);
            p.setStyle(Paint.Style.STROKE);
        } else if (CHECK.equals(glyph)) {
            // 对勾：播放过就把这个标记点亮成绿勾
            pa.moveTo(x + s * 0.20f, y + s * 0.52f);
            pa.lineTo(x + s * 0.42f, y + s * 0.74f);
            pa.lineTo(x + s * 0.82f, y + s * 0.28f);
            c.drawPath(pa, p);
        } else if (EYE.equals(glyph)) {
            pa.moveTo(x + s * 0.10f, cy);
            pa.quadTo(cx, y + s * 0.12f, x + s * 0.90f, cy);
            pa.quadTo(cx, y + s * 0.88f, x + s * 0.10f, cy);
            c.drawPath(pa, p);
            p.setStyle(Paint.Style.FILL);
            c.drawCircle(cx, cy, q * 0.18f, p);
            p.setStyle(Paint.Style.STROKE);
        } else if (RESTART.equals(glyph)) {
            // 回绕箭头（上弧 + 左端箭头）+ 中间三角
            android.graphics.RectF arc = new android.graphics.RectF(
                    x + s * 0.20f, y + s * 0.20f, x + s * 0.80f, y + s * 0.80f);
            c.drawArc(arc, 250f, 260f, false, p);
            Path head = new Path();
            head.moveTo(x + s * 0.13f, y + s * 0.34f);
            head.lineTo(x + s * 0.34f, y + s * 0.34f);
            head.lineTo(x + s * 0.22f, y + s * 0.54f);
            head.close();
            p.setStyle(Paint.Style.FILL);
            c.drawPath(head, p);
            Path tri2 = new Path();
            tri2.moveTo(x + s * 0.40f, y + s * 0.36f);
            tri2.lineTo(x + s * 0.64f, cy);
            tri2.lineTo(x + s * 0.40f, y + s * 0.64f);
            tri2.close();
            c.drawPath(tri2, p);
            p.setStyle(Paint.Style.STROKE);
        } else if (IMAGE.equals(glyph)) {
            // 相框 + 太阳 + 远山：照片入口专用，跟「已观看」的眼睛区分开
            Path box = new Path();
            box.addRect(x + s * 0.12f, y + s * 0.18f, x + s * 0.88f, y + s * 0.82f, Path.Direction.CW);
            c.drawPath(box, p);
            p.setStyle(Paint.Style.FILL);
            c.drawCircle(x + s * 0.30f, y + s * 0.36f, q * 0.09f, p);
            Path hill = new Path();
            hill.moveTo(x + s * 0.15f, y + s * 0.78f);
            hill.lineTo(x + s * 0.42f, y + s * 0.46f);
            hill.lineTo(x + s * 0.63f, y + s * 0.72f);
            hill.lineTo(x + s * 0.72f, y + s * 0.60f);
            hill.lineTo(x + s * 0.85f, y + s * 0.78f);
            hill.close();
            c.drawPath(hill, p);
            p.setStyle(Paint.Style.STROKE);
        } else if (PLAY.equals(glyph)) {
            Path tri = new Path();
            tri.moveTo(x + s * 0.30f, y + s * 0.16f);
            tri.lineTo(x + s * 0.84f, cy);
            tri.lineTo(x + s * 0.30f, y + s * 0.84f);
            tri.close();
            p.setStyle(Paint.Style.FILL);
            c.drawPath(tri, p);
            p.setStyle(Paint.Style.STROKE);
        } else if (HEART.equals(glyph)) {
            Path h = new Path();
            h.moveTo(cx, y + s * 0.82f);
            h.cubicTo(x + s * 0.02f, y + s * 0.52f, x + s * 0.16f, y + s * 0.10f, cx, y + s * 0.34f);
            h.cubicTo(x + s * 0.84f, y + s * 0.10f, x + s * 0.98f, y + s * 0.52f, cx, y + s * 0.82f);
            if (filled) {
                p.setStyle(Paint.Style.FILL);
                c.drawPath(h, p);
                p.setStyle(Paint.Style.STROKE);
            } else {
                c.drawPath(h, p);
            }
        } else if (TRASH.equals(glyph)) {
            pa.moveTo(x + s * 0.22f, y + s * 0.30f);
            pa.lineTo(x + s * 0.78f, y + s * 0.30f);
            pa.moveTo(x + s * 0.36f, y + s * 0.30f);
            pa.lineTo(x + s * 0.40f, y + s * 0.18f);
            pa.lineTo(x + s * 0.60f, y + s * 0.18f);
            pa.lineTo(x + s * 0.64f, y + s * 0.30f);
            pa.moveTo(x + s * 0.28f, y + s * 0.30f);
            pa.lineTo(x + s * 0.32f, y + s * 0.84f);
            pa.lineTo(x + s * 0.68f, y + s * 0.84f);
            pa.lineTo(x + s * 0.72f, y + s * 0.30f);
            pa.moveTo(x + s * 0.50f, y + s * 0.40f);
            pa.lineTo(x + s * 0.50f, y + s * 0.74f);
            c.drawPath(pa, p);
        } else if (SORT.equals(glyph)) {
            // 三条递减横线 + 箭头，和 Emby 的排序图标同款
            pa.moveTo(x + s * 0.08f, y + s * 0.22f);
            pa.lineTo(x + s * 0.68f, y + s * 0.22f);
            pa.moveTo(x + s * 0.08f, y + s * 0.44f);
            pa.lineTo(x + s * 0.52f, y + s * 0.44f);
            pa.moveTo(x + s * 0.08f, y + s * 0.66f);
            pa.lineTo(x + s * 0.38f, y + s * 0.66f);
            c.drawPath(pa, p);
            Path ar = new Path();
            ar.moveTo(x + s * 0.80f, y + s * 0.16f);
            ar.lineTo(x + s * 0.80f, y + s * 0.82f);
            ar.moveTo(x + s * 0.70f, y + s * 0.70f);
            ar.lineTo(x + s * 0.80f, y + s * 0.84f);
            ar.lineTo(x + s * 0.90f, y + s * 0.70f);
            c.drawPath(ar, p);
        } else if (SEARCH.equals(glyph)) {
            // 放大镜：圆环 + 斜柄
            c.drawCircle(x + s * 0.44f, y + s * 0.42f, s * 0.30f, p);
            Path hd = new Path();
            hd.moveTo(x + s * 0.66f, y + s * 0.64f);
            hd.lineTo(x + s * 0.90f, y + s * 0.88f);
            c.drawPath(hd, p);
        } else if (INFO.equals(glyph)) {
            c.drawCircle(cx, cy, q * 0.78f, p);
            p.setStyle(Paint.Style.FILL);
            c.drawCircle(cx, cy - q * 0.38f, q * 0.10f, p);
            Path bar = new Path();
            bar.moveTo(cx, cy - q * 0.10f);
            bar.lineTo(cx, cy + q * 0.40f);
            c.drawPath(bar, p);
            p.setStyle(Paint.Style.STROKE);
        } else if (USER.equals(glyph)) {
            c.drawCircle(cx, y + s * 0.34f, s * 0.20f, p);
            Path sh = new Path();
            sh.moveTo(x + s * 0.16f, y + s * 0.86f);
            sh.cubicTo(x + s * 0.20f, y + s * 0.56f, x + s * 0.80f, y + s * 0.56f, x + s * 0.84f, y + s * 0.86f);
            c.drawPath(sh, p);
        } else {
            float g = q * 0.10f, b = q * 0.62f;
            Path sq = new Path();
            sq.addRect(cx - b, cy - b, cx + b, cy + b, Path.Direction.CW);
            c.drawPath(sq, p);
        }
    }
}

/**
 * 平滑滚动容器：和官方 Leanback 列表同一套做法 ——
 * OverScroller（系统 fling 同款 viscous fluid 曲线）+ Choreographer 逐帧推进；
 * 连续按方向键时从当前位置接着滑，而不是"停一下再从零加速"，所以是一口气连贯的。
 */
class SmoothScrollView extends android.widget.ScrollView {
    private final android.widget.OverScroller scroller;
    private boolean gliding;
    private int glideTarget = -1;

    public SmoothScrollView(Context c) {
        super(c);
        scroller = new android.widget.OverScroller(c);
        setVerticalScrollBarEnabled(false);
        setClipToPadding(false);
    }

    int maxScroll() {
        if (getChildCount() == 0) return 0;
        int content = getChildAt(0).getHeight();
        return Math.max(0, content + getPaddingTop() + getPaddingBottom() - getHeight());
    }

    /** 让某张卡片所在的一行在滚动容器里完整露出（只滚必需的距离） */
    static void revealRow(android.widget.ScrollView sc, View card) {
        if (sc == null || card == null) return;
        try {
            int cur = sc.getScrollY();
            int[] ca = new int[2], sa = new int[2];
            card.getLocationOnScreen(ca);
            sc.getLocationOnScreen(sa);
            int top = ca[1] - sa[1] + cur;
            int vh = sc.getHeight(), ch = card.getHeight();
            if (vh <= 0 || ch <= 0) return;
            int pad = U.dp(sc.getContext(), 10);
            int target = cur;
            if (ch + pad * 2 >= vh) {
                target = top + ch / 2 - vh / 2;          // 整行比视口还高，只能居中
            } else if (top - pad < cur) {
                target = top - pad;                       // 上方被裁：刚好露出整行
            } else if (top + ch + pad > cur + vh) {
                target = top + ch + pad - vh;             // 下方被裁：刚好露出整行
            }
            int content = sc.getChildCount() > 0 ? sc.getChildAt(0).getHeight() : 0;
            int max = Math.max(0, content - vh);
            if (target < 0) target = 0;
            if (target > max) target = max;
            if (target == cur) return;
            if (sc instanceof SmoothScrollView) ((SmoothScrollView) sc).glideTo(target);
            else sc.smoothScrollTo(0, target);
        } catch (Throwable ignored) { }
    }

    /** 交给 OverScroller 逐帧推进：系统列表滚动用的就是这条曲线 */
    @Override public void computeScroll() {
        if (gliding) {
            if (scroller.computeScrollOffset()) {
                scrollTo(0, scroller.getCurrY());
                postInvalidateOnAnimation();   // 与 Choreographer 同拍，一帧推一步
                return;
            }
            gliding = false;
        }
        super.computeScroll();
    }

    /** 缓动到 y：官方列表同款 OverScroller 曲线，连续按键接着滑、不从头重启 */
    public void glideTo(int target) {
        if (target < 0) target = 0;
        int max = maxScroll();
        if (target > max) target = max;
        int from = getScrollY();
        if (from == target) { gliding = false; return; }
        if (gliding && Math.abs(glideTarget - target) <= U.dp(getContext(), 8)) return;
        scroller.forceFinished(true);
        glideTarget = target;
        int dist = Math.abs(target - from);
        int dur = dist > U.dp(getContext(), 900) ? 430 : 260;   // 近程不拖，远程不飘
        scroller.startScroll(0, from, 0, target - from, dur);
        gliding = true;
        postInvalidateOnAnimation();
    }
}

/** 首页弧形堆叠轮播：一排海报按弧形排布，正中间的完整显示并压在最前，两侧倾斜缩小堆叠；可聚焦，左右键切换、确认进详情 */
class Carousel extends android.widget.FrameLayout {
    public interface OnPick { void onPick(int index); }

    private static final int WIN = 15;   // 同屏画 15 张（中间 + 左右各 7），排满整条弧
    private final float dens;
    private final java.util.ArrayList<String> urls = new java.util.ArrayList<String>();
    private final java.util.ArrayList<String> labels = new java.util.ArrayList<String>();
    private final java.util.ArrayList<android.widget.ImageView> views = new java.util.ArrayList<android.widget.ImageView>();
    private final java.util.ArrayList<android.widget.FrameLayout> cells = new java.util.ArrayList<android.widget.FrameLayout>();
    private final java.util.ArrayList<android.widget.TextView> badges = new java.util.ArrayList<android.widget.TextView>();
    private final java.util.ArrayList<String> badgeText = new java.util.ArrayList<String>();
    private final android.widget.TextView cap;
    private int cur = 0;
    private int cw, ch;
    private OnPick pick;
    private boolean focusedSelf;
    private Runnable autoTick;
    private Runnable onUp;
    private Runnable onLoop;

    public Carousel(android.content.Context c) {
        super(c);
        dens = c.getResources().getDisplayMetrics().density;
        setClipChildren(false);
        setClipToPadding(false);
        setFocusable(true);
        setFocusableInTouchMode(true);

        cap = new android.widget.TextView(c);
        cap.setTextColor(U.txt(0xFFFFFFFF));
        cap.setTextSize(13);
        cap.setGravity(android.view.Gravity.CENTER);
        cap.setSingleLine(true);
        cap.setEllipsize(android.text.TextUtils.TruncateAt.END);
        cap.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        U.face(cap);   // 轮播片名也跟设置里手选的字体
        cap.setShadowLayer(dp(7), 0, dp(1), 0xD9000000);
        FrameLayout.LayoutParams cl = new FrameLayout.LayoutParams(-1, -2);
        cl.gravity = android.view.Gravity.BOTTOM | android.view.Gravity.CENTER_HORIZONTAL;
        cl.bottomMargin = dp(2);
        addView(cap, cl);

        setOnKeyListener(new android.view.View.OnKeyListener() {
            @Override public boolean onKey(android.view.View v, int code, android.view.KeyEvent e) {
                if (e.getAction() != android.view.KeyEvent.ACTION_DOWN) return false;
                if (code == android.view.KeyEvent.KEYCODE_DPAD_LEFT) { move(-1); return true; }
                if (code == android.view.KeyEvent.KEYCODE_DPAD_RIGHT) { move(1); return true; }
                if (code == android.view.KeyEvent.KEYCODE_DPAD_UP) {
                    if (onUp != null) onUp.run();   // 往上交给顶部按钮栏
                    return true;
                }
                if (code == android.view.KeyEvent.KEYCODE_DPAD_CENTER
                        || code == android.view.KeyEvent.KEYCODE_ENTER
                        || code == android.view.KeyEvent.KEYCODE_NUMPAD_ENTER) {
                    if (pick != null && urls.size() > 0) pick.onPick(cur);
                    return true;
                }
                return false;
            }
        });
    }

    private int dp(float v) { return (int) (v * dens); }

    public void setOnPick(OnPick p) { pick = p; }

    public void setOnUp(Runnable r) { onUp = r; }

    /** 一小圈（一张一张播到回到第一张）播完时回调，主页借此切下一个媒体库 */
    public void setOnLoop(Runnable r) { onLoop = r; }

    public int currentIndex() { return cur; }

    public void setItems(java.util.List<String> imageUrls, java.util.List<String> titles) {
        urls.clear(); labels.clear();
        if (imageUrls != null) urls.addAll(imageUrls);
        if (titles != null) labels.addAll(titles);
        while (labels.size() < urls.size()) labels.add("");
        cur = 0;
        rebuild();
    }

    /** 带右上角进度角标的版本：badgeList 里空串表示这张不挂角标 */
    public void setItems(java.util.List<String> imageUrls, java.util.List<String> titles,
                         java.util.List<String> badgeList) {
        badgeText.clear();
        if (badgeList != null) badgeText.addAll(badgeList);
        while (badgeText.size() < (imageUrls == null ? 0 : imageUrls.size())) badgeText.add("");
        setItems(imageUrls, titles);
    }

    /** 换一批数据时重建子 View（数量变化才增删） */
    private void rebuild() {
        int n = urls.size();
        // 数据够多就固定铺满 11 个槽位（环形重复填满两侧空档），只有 1 张时才退化
        int win = n <= 1 ? Math.max(1, n) : WIN;
        while (views.size() < win) {
            android.widget.FrameLayout cell = new android.widget.FrameLayout(getContext());
            cell.setBackground(U.round(U.SOFT2, U.ST ? 0 : dp(6)));
            cell.setClipToOutline(true);
            cell.setElevation(dp(4));                       // 东一块西一块的卡之间有点层次
            cell.setCameraDistance(dp(9000));
            android.widget.ImageView v = new android.widget.ImageView(getContext());
            v.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
            cell.addView(v, new FrameLayout.LayoutParams(-1, -1));
            android.widget.TextView b = new android.widget.TextView(getContext());
            b.setTextColor(0xFFFFFFFF);
            b.setTextSize(11);
            b.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            b.setGravity(android.view.Gravity.CENTER);
            b.setPadding(dp(6), dp(2), dp(6), dp(2));
            b.setBackground(U.round(0xE6000000, dp(4)));
            b.setVisibility(android.view.View.GONE);
            FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(-2, -2);
            blp.gravity = android.view.Gravity.TOP | android.view.Gravity.END;
            blp.topMargin = dp(6);
            blp.rightMargin = dp(6);
            cell.addView(b, blp);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(cw > 0 ? cw : 1, ch > 0 ? ch : 1);
            lp.gravity = android.view.Gravity.CENTER_HORIZONTAL | android.view.Gravity.TOP;
            lp.topMargin = dp(54);   // 让出顶部浮动按钮栏
            addView(cell, lp);
            cells.add(cell);
            views.add(v);
            badges.add(b);
        }
        for (int i = 0; i < cells.size(); i++) {
            cells.get(i).setVisibility(i < win ? android.view.View.VISIBLE : android.view.View.GONE);
        }
        android.util.Log.e("LMBYTEST", "carousel rebuild: n=" + n + " win=" + win + " views=" + views.size());
        cap.bringToFront();   // 片名要压在海报上面，不能被中心卡盖住
        bind();
        relayout(false);
    }

    /** 给每个槽位装图片：槽位相对中心 d，数据索引 = cur + d（环形） */
    private void bind() {
        int n = urls.size();
        if (n == 0) return;
        int c = views.size() / 2;
        for (int i = 0; i < views.size(); i++) {
            android.widget.FrameLayout cell = cells.get(i);
            if (cell.getVisibility() != android.view.View.VISIBLE) continue;
            android.widget.ImageView v = views.get(i);
            int d = i - c;
            int di = ((cur + d) % n + n) % n;
            String url = urls.get(di);
            cell.setBackground(U.round(U.SOFT2, U.ST ? 0 : dp(6)));
            // 右上角进度角标（这张看过一部分才显示）
            android.widget.TextView bd = badges.get(i);
            String bt = di < badgeText.size() ? badgeText.get(di) : "";
            if (bt == null || bt.length() == 0) {
                bd.setVisibility(android.view.View.GONE);
            } else {
                bd.setText(bt);
                bd.setVisibility(android.view.View.VISIBLE);
            }
            if (url == null || url.length() == 0) v.setImageDrawable(null);
            else Img.load(v, url, true);
        }
        if (!labels.isEmpty()) cap.setText(labels.get(((cur % labels.size()) + labels.size()) % labels.size()));
    }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        ch = Math.round(h * 0.74f);
        cw = Math.round(ch * 2f / 3f);
        int top = dp(54);   // 往下让出顶部浮动按钮栏，别被 logo/首页/收藏 挡住
        for (android.widget.FrameLayout v : cells) {
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) v.getLayoutParams();
            lp.width = cw; lp.height = ch; lp.topMargin = top;
            v.setLayoutParams(lp);
        }
        relayout(false);
    }

    /** 弧形排布：d 是相对中间的距离，越远越靠下、越小、越斜、越暗 */
    private void relayout(boolean animate) {
        if (cw <= 0) return;
        int c = views.size() / 2;
        // 间距：基础步长按屏宽算（外侧要贴边），并且越往外间距越小（透视压缩），保证卡与卡之间不留缝
        float base = cw * 0.62f;
        if (getWidth() > 0 && c > 0) {
            float fit = (getWidth() / 2f + cw * 0.75f) / c;
            base = Math.min(base, Math.max(fit, cw * 0.30f));
        }
        for (int i = 0; i < views.size(); i++) {
            android.widget.FrameLayout v = cells.get(i);
            if (v.getVisibility() != android.view.View.VISIBLE) continue;
            int d = i - c;
            float tx = 0f;
            float s = base;
            for (int k = 1; k <= Math.abs(d); k++) { tx += s; s *= 0.90f; }
            if (d < 0) tx = -tx;
            float ty = Math.abs(d) * ch * 0.045f;
            float sc = 1f - 0.07f * Math.abs(d);
            float rot = Math.max(-32f, Math.min(32f, -d * 10f));   // 弧形倾斜，限制最大角度别把边上的卡压成一条线
            float al = 1f - 0.045f * Math.abs(d);
            if (d == 0) {
                tx = 0f; ty = 0f; rot = 0f; al = 1f;
                sc = focusedSelf ? 1.06f : 1f;
            }
            v.setTranslationZ(-Math.abs(d) * dp(24));
            if (animate) {
                v.animate().translationX(tx).translationY(ty).scaleX(sc).scaleY(sc)
                        .rotationY(rot).alpha(al).setDuration(270).start();
            } else {
                v.setTranslationX(tx); v.setTranslationY(ty);
                v.setScaleX(sc); v.setScaleY(sc);
                v.setRotationY(rot); v.setAlpha(al);
            }
        }
    }

    private void move(int dir) {
        int n = urls.size();
        if (n < 2) return;
        int prev = cur;
        cur = ((cur + dir) % n + n) % n;
        bind();
        relayout(true);
        resetAuto();
        // 往后翻到最后一圈末尾（回到第 0 张）：交给上层换下一个媒体库
        if (onLoop != null && dir > 0 && prev == n - 1) onLoop.run();
    }

    @Override protected void onFocusChanged(boolean gain, int dir, android.graphics.Rect prev) {
        super.onFocusChanged(gain, dir, prev);
        focusedSelf = gain;
        relayout(true);
    }

    public void startAuto() {
        stopAuto();
        autoTick = new Runnable() {
            @Override public void run() {
                if (urls.size() > 1 && getParent() != null && isShown()) move(1);
            }
        };
        postDelayed(autoTick, 8000);
    }

    private void resetAuto() {
        if (autoTick == null) return;
        removeCallbacks(autoTick);
        postDelayed(autoTick, 8000);
    }

    public void stopAuto() {
        if (autoTick != null) removeCallbacks(autoTick);
    }
}
