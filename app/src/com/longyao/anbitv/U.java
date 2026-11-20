package com.longyao.anbitv;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.ScaleAnimation;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/** 公共 UI 工具与配色 */
public final class U {
    // 主题 0=原生（绿） 1=Apple TV（黑白） 2=胶片暗金 3=霓虹蓝紫
    public static int THEME = 0;

    public static int BG = 0xFF08090B;
    public static int PANEL = 0xFF14181C;
    public static int PANEL2 = 0xFF1B2126;
    public static int CARD = 0xFF232A31;
    public static int TXT = 0xFFF2F5F8;
    public static int DIM = 0xFF97A2AD;
    public static int ACC = 0xFF58CC6C;
    public static int WARN = 0xFFE0603F;
    /** 焦点描边色 / 卡片圆角 / 焦点缩放 */
    public static int RING = ACC;
    public static int RADIUS = 10;
    public static float FOCUS_SCALE = 1.04f;
    /** 焦点放大（缩略图放大）总开关 */
    public static boolean FOCUS_ZOOM = true;
    /** 页面切换过渡动画 0 跟手(无动画) / 1 淡入 / 2 缩放淡入 / 3 上滑淡入 / 4 左右推移 */
    public static int PAGE_ANIM = 1;
    /** 上下翻页（焦点行跟随）滚动动画 0 跟手 / 1 平滑 / 2 柔和 */
    public static int SCROLL_ANIM = 1;
    /** 背景图：0=原生渐变，1..10=内置壁纸 */
    public static int BG_INDEX = 0;
    private static Context BG_CTX;
    private static android.graphics.Bitmap BG_BMP;
    /** 背景图选择 = 纯白（设置里的「纯白」选项，不加载壁纸） */
    public static boolean BG_WHITE = false;
    /** 选中胶囊的底色与文字色 */
    public static int CHIP_ON_BG = 0xFF16241A;
    public static int CHIP_ON_TXT = 0xFFCFEBD4;
    /** 顶部标题左侧竖条宽度 */
    public static int ACCENT_BAR_W = 3;
    /** 强调色上的文字色 */
    public static int ON_ACC = 0xFF06210C;
    /** 浅色主题（iOS 白） */
    public static boolean LIGHT = false;
    /** 背景图偏亮时自动切浅底（前景转深色、卡片转白），否则亮背景会把白字全糊掉 */
    public static boolean LIGHT_BG = false;
    /** 渐变背景两端、卡片描边、第二强调色、焦点副色、是否玻璃拟态 */
    public static int BG1 = 0xFF14181F, BG2 = 0xFF07090B;
    public static int BORDER = 0x33FFFFFF;
    /** 浅色叠加底（按钮、未选中 chip、卡片）：玻璃是白色半透明，黑绿/潮牌是深色面板 */
    public static int SOFT = 0x33FFFFFF;
    public static int SOFT_L = 0x1FFFFFFF;
    /** 深色卡底（轮播海报后面那层） */
    public static int SOFT2 = 0x33202428;
    public static int ACC2 = 0xFF34D07A;
    public static int RING2 = ACC;
    /** 黑绿主题（夆刻 Emby TV）：扁平方角、绿强调 */
    public static boolean DG = false;
    /** 潮牌主题（跟图标一套）：纯黑底 + 粉/青/黄三色 + 全直角 + 贴纸硬边框 */
    public static boolean ST = false;
    public static boolean GLASS = false;
    /** 官方 Emby 风格（黑底 + Emby 绿 + 实心深色卡 + 小圆角） */
    public static boolean EMBY = false;

    public static void applyTheme(int t) {
        THEME = 0;
        EMBY = false;
        DG = (t == 1);
        ST = (t == 2);
        if (t == 2) {
            streetColors();      // 潮牌：黑底 + 三色 + 全直角
        } else if (t == 1) {
            darkGreenColors();   // 黑绿：复刻 Emby TV 客户端
        } else {
            // 玻璃风：深色半透明面板 + 白色强调
            LIGHT = false; GLASS = true; DG = false; ST = false;
            BG1 = 0xFF000000; BG2 = 0xFF000000;
            PANEL = 0xD926262A; PANEL2 = 0xB326262A; CARD = 0xA826262A;
            TXT = 0xFFF3F4F6; DIM = 0xFFB9BEC5;
            ACC = 0xFFFFFFFF; ON_ACC = 0xFF1C1C1E; WARN = 0xFF9AA0A8;
            RING = 0xE6FFFFFF; RING2 = 0xFFFFFFFF; RADIUS = 6; FOCUS_SCALE = 1.06f;
            CHIP_ON_BG = 0xFFFFFFFF; CHIP_ON_TXT = 0xFF1C1C1E; ACCENT_BAR_W = 0;
            BORDER = 0x40FFFFFF; ACC2 = 0xFFB9BEC5;
            SOFT = 0x33FFFFFF; SOFT_L = 0x1FFFFFFF; SOFT2 = 0x33202428;
        }
        applyLightBg(LIGHT_BG);
    }

    /** 黑绿（夆到 Emby TV 客户端）：近黑底 + Emby 绿强调 + 直角方形卡片 */
    private static void streetColors() {
        LIGHT = false; GLASS = false;
        BG1 = 0xFF080808; BG2 = 0xFF101011;
        PANEL = 0xFF131316; PANEL2 = 0xFF1A1A1F; CARD = 0xFF141418;
        TXT = 0xFFF7F7F8; DIM = 0xFF9C9CA4;
        ACC = 0xFFFF2E77; ON_ACC = 0xFF0A0A0A; WARN = 0xFFE8F00A;
        RING = 0xFFFFFFFF; RING2 = 0xFFE8F00A; RADIUS = 0; FOCUS_SCALE = 1.05f;
        CHIP_ON_BG = 0xFFFF2E77; CHIP_ON_TXT = 0xFF0A0A0A; ACCENT_BAR_W = 6;
        BORDER = 0x66FFFFFF; ACC2 = 0xFF22D3EE;
        SOFT = 0xFF1E1E24; SOFT_L = 0xFF191920; SOFT2 = 0xFF121214;
    }

    private static void darkGreenColors() {
        LIGHT = false; GLASS = false;
        BG1 = 0xFF0A0C0A; BG2 = 0xFF0F130F;
        PANEL = 0xFF161A16; PANEL2 = 0xFF1E241E; CARD = 0xFF161A16;
        TXT = 0xFFECEFF0; DIM = 0xFF98A29B;
        ACC = 0xFF52B54B; ON_ACC = 0xFF07120A; WARN = 0xFFCF6679;
        RING = 0xFF52B54B; RING2 = 0xFF8BD98A; RADIUS = 2; FOCUS_SCALE = 1.03f;
        CHIP_ON_BG = 0xFF52B54B; CHIP_ON_TXT = 0xFF07120A; ACCENT_BAR_W = 4;
        BORDER = 0xFF2C3A2E; ACC2 = 0xFF98A29B;
        SOFT = 0xFF1E241E; SOFT_L = 0xFF191D19; SOFT2 = 0xFF161A16;
    }


    public static android.graphics.Path cyberPath(float w, float h, float cut, float rad) {
        android.graphics.Path p = new android.graphics.Path();
        p.moveTo(rad, 0);
        p.lineTo(w - cut, 0);
        p.lineTo(w, cut);
        p.lineTo(w, h - rad);
        p.quadTo(w, h, w - rad, h);
        p.lineTo(cut, h);
        p.lineTo(0, h - cut);
        p.lineTo(0, rad);
        p.quadTo(0, 0, rad, 0);
        p.close();
        return p;
    }

    /** 赛博切角按钮底：切角形状 + 青紫渐变填充/描边，可当任意控件的 background */
    public static class CyberChip extends android.graphics.drawable.Drawable {
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float cut, rad, sw;
        private final int fill1, fill2, edge1, edge2;

        public CyberChip(float cut, float rad, float sw,
                         int fill1, int fill2, int edgeC1, int edgeC2) {
            this.cut = cut; this.rad = rad; this.sw = sw;
            this.fill1 = fill1; this.fill2 = fill2;
            this.edge1 = edgeC1; this.edge2 = edgeC2;
        }

        @Override public void draw(android.graphics.Canvas c) {
            android.graphics.Rect b = getBounds();
            float w = b.width(), h = b.height();
            float inset = sw / 2f;
            android.graphics.Path inner = cyberPath(w - sw, h - sw, Math.max(2f, cut - inset), rad);
            inner.offset(b.left + inset, b.top + inset);
            fill.setStyle(Paint.Style.FILL);
            if (fill2 != 0) {
                fill.setShader(new android.graphics.LinearGradient(b.left, b.top, b.right, b.bottom,
                        fill1, fill2, android.graphics.Shader.TileMode.CLAMP));
            } else {
                fill.setShader(null);
                fill.setColor(fill1);
            }
            c.drawPath(inner, fill);
            if (sw > 0 && edge1 != 0) {
                android.graphics.Path outer = cyberPath(w, h, cut, rad);
                outer.offset(b.left, b.top);
                edge.setStyle(Paint.Style.STROKE);
                edge.setStrokeWidth(sw);
                if (edge2 != 0) {
                    edge.setShader(new android.graphics.LinearGradient(b.left, b.top, b.right, b.bottom,
                            edge1, edge2, android.graphics.Shader.TileMode.CLAMP));
                } else {
                    edge.setShader(null);
                    edge.setColor(edge1);
                }
                c.drawPath(outer, edge);
            }
        }

        @Override public void setAlpha(int a) { fill.setAlpha(a); edge.setAlpha(a); }
        @Override public void setColorFilter(android.graphics.ColorFilter f) {
            fill.setColorFilter(f); edge.setColorFilter(f);
        }
        @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
    }

    /** 炫酷专属背景：极黑底 + 左上青光 / 右下紫光 + 细网格 */
    public static class CyberBg extends android.graphics.drawable.Drawable {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint grid = new Paint(Paint.ANTI_ALIAS_FLAG);

        @Override public void draw(android.graphics.Canvas c) {
            android.graphics.Rect b = getBounds();
            p.setShader(null);
            p.setColor(0xFF04050A);
            c.drawRect(b, p);
            float far = Math.max(b.width(), b.height());
            p.setShader(new android.graphics.RadialGradient(b.left, b.top, far * 0.75f,
                    0x3A2BE8FF, 0x002BE8FF, android.graphics.Shader.TileMode.CLAMP));
            c.drawRect(b, p);
            p.setShader(new android.graphics.RadialGradient(b.right, b.bottom, far * 0.65f,
                    0x3AB06BFF, 0x00B06BFF, android.graphics.Shader.TileMode.CLAMP));
            c.drawRect(b, p);
            p.setShader(null);
            grid.setColor(0x0D2BE8FF);
            grid.setStrokeWidth(1f);
            int step = 96;
            for (int x = b.left; x < b.right; x += step) c.drawLine(x, b.top, x, b.bottom, grid);
            for (int y = b.top; y < b.bottom; y += step) c.drawLine(b.left, y, b.right, y, grid);
        }

        @Override public void setAlpha(int a) { }
        @Override public void setColorFilter(android.graphics.ColorFilter f) { }
        @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
    }


    /** 苹果风弹层：深灰毛玻璃 + 大圆角 + 极淡描边 */
    public static android.graphics.drawable.Drawable cyberSheet(Context c) {
        return roundStroke(0xFFFFFFFF, dp(c, 10), dp(c, 1), 0x1A000000);
    }

    /** iOS 风胶囊：选中=黑实底白字，未选=浅灰底；focused 时描边加亮（黑底用白边、灰底用黑边） */
    public static android.graphics.drawable.Drawable cyberChip(Context c, boolean sel, boolean focused) {
        float rad = dp(c, 4);
        if (sel) {
            return roundStroke(0xFF1C1C1E, rad, dp(c, focused ? 2f : 1f),
                    focused ? 0xFFFFFFFF : 0xFF1C1C1E);
        }
        return roundStroke(0xFFF2F2F7, rad, dp(c, focused ? 2f : 1f),
                focused ? 0xFF1C1C1E : 0x0F000000);
    }

    public static android.graphics.drawable.Drawable neonBorder(Context c, int fill,
                                                                float radPx, float strokePx) {
        GradientDrawable edge = new GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{0xFF2BE8FF, 0xFF7C6BFF, 0xFFB06BFF});
        edge.setCornerRadius(radPx);
        GradientDrawable inner = round(fill, Math.max(1f, radPx - strokePx));
        android.graphics.drawable.LayerDrawable ld =
            new android.graphics.drawable.LayerDrawable(
                new android.graphics.drawable.Drawable[]{edge, inner});
        int s = (int) Math.max(1f, strokePx);
        ld.setLayerInset(1, s, s, s, s);
        return ld;
    }

    /** 深/浅底两套前景色，亮背景壁纸下自动切浅底，避免白字压在亮底上看不清 */
    public static void applyLightBg(boolean light) {
        LIGHT_BG = light;
        if (ST) { streetColors(); return; }      // 潮牌主题固定黑底，不被壁纸亮度改写
        if (DG) { darkGreenColors(); return; }   // 黑绿主题固定深色，不被壁纸亮度改写
        if (light) {
            TXT = 0xFF191A1C; DIM = 0xFF5C6268;
            CARD = 0xF2FFFFFF; PANEL = 0xF2FFFFFF; PANEL2 = 0xF7FFFFFF;
            BORDER = 0x24000000;
            ACC = 0xFF1C1C1E; ON_ACC = 0xFFFFFFFF;
            RING = 0xE6000000; RING2 = 0xFF1C1C1E;
            CHIP_ON_BG = 0xFF1C1C1E; CHIP_ON_TXT = 0xFFFFFFFF;
            ACC2 = 0xFF5C6268; WARN = 0xFF8A4A3A;
        } else {
            TXT = 0xFFF3F4F6; DIM = 0xFFB9BEC5;
            CARD = 0xA826262A; PANEL = 0xD926262A; PANEL2 = 0xB326262A;
            BORDER = 0x40FFFFFF;
            ACC = 0xFFFFFFFF; ON_ACC = 0xFF1C1C1E;
            RING = 0xE6FFFFFF; RING2 = 0xFFFFFFFF;
            CHIP_ON_BG = 0xFFFFFFFF; CHIP_ON_TXT = 0xFF1C1C1E;
            ACC2 = 0xFFB9BEC5; WARN = 0xFF9AA0A8;
        }
    }

    /** 焦点框样式（0 细描边 1 粗描边 2 双线 3 外发光 4 四角 5 底部条 6 顶部条 7 虚线 8 玻璃遮罩 9 渐变边） */
    public static final String[] RING_STYLE_NAMES = {L.t("细描边"), L.t("粗描边"), L.t("双线"), L.t("外发光"),
            L.t("四角"), L.t("底部条"), L.t("顶部条"), L.t("虚线"), L.t("玻璃遮罩"), L.t("渐变边")};
    public static int RING_STYLE = 0;

    /** 外圈留白（dp）：框画在缩略图外侧，不被亮画面吃掉，四边永远完整可见 */
    public static final float RING_OUTSET = 2.5f;

    /** 统一焦点框：只用一种「整块透明玻璃」盖住缩略图（能见度 90%） */
    public static void drawRing(android.graphics.Canvas canvas, float w, float h, float rad,
                               float dp1, Paint p) {
        drawRing(canvas, w, h, rad, dp1, p, 0f);
    }

    public static void drawRing(android.graphics.Canvas canvas, float w, float h, float rad,
                               float dp1, Paint p, float outsetPx) {
        drawRing(canvas, w, h, rad, dp1, p, outsetPx, 0);
    }

    /** ringColor==0 时用默认半透明白 */
    public static void drawRing(android.graphics.Canvas canvas, float w, float h, float rad,
                               float dp1, Paint p, float outsetPx, int ringColor) {
        if (w <= 0 || h <= 0) return;
        int style = 8;
        int c = RING;
        p.setAntiAlias(true);
        p.setStyle(Paint.Style.STROKE);
        p.setPathEffect(null);
        RectF box = new RectF();
        switch (style) {
            case 1: {   // 粗描边
                float lw = dp1 * 6;
                p.setStrokeWidth(lw);
                p.setColor(c);
                box.set(lw / 2, lw / 2, w - lw / 2, h - lw / 2);
                canvas.drawRoundRect(box, rad, rad, p);
                break;
            }
            case 2: {   // 双线
                float lw = dp1 * 2;
                p.setStrokeWidth(lw);
                p.setColor(c);
                box.set(lw / 2, lw / 2, w - lw / 2, h - lw / 2);
                canvas.drawRoundRect(box, rad, rad, p);
                float in = dp1 * 6;
                box.set(in, in, w - in, h - in);
                canvas.drawRoundRect(box, Math.max(dp1, rad - lw), Math.max(dp1, rad - lw), p);
                break;
            }
            case 3: {   // 外发光
                for (int i = 3; i >= 1; i--) {
                    float gw = dp1 * (3 * i + 2);
                    p.setStrokeWidth(gw);
                    p.setColor((c & 0x00FFFFFF) | ((0x1A * (4 - i)) << 24));
                    float o = gw / 2f;
                    box.set(o, o, w - o, h - o);
                    canvas.drawRoundRect(box, rad, rad, p);
                }
                float lw = dp1 * 2;
                p.setStrokeWidth(lw);
                p.setColor(c);
                box.set(lw / 2, lw / 2, w - lw / 2, h - lw / 2);
                canvas.drawRoundRect(box, rad, rad, p);
                break;
            }
            case 4: {   // 四角
                float lw = dp1 * 4;
                float rr = Math.min(rad, Math.min(w, h) / 2f);
                float seg = Math.min(Math.max(rr * 2 + dp1 * 6, dp1 * 14), Math.min(w, h) * 0.45f);
                float ins = lw / 2 + dp1 * 2;
                p.setStrokeWidth(lw);
                p.setColor(c);
                float l = ins, t = ins, r = w - ins, b = h - ins;
                canvas.drawArc(new RectF(l, t, l + rr * 2, t + rr * 2), 180, 90, false, p);
                canvas.drawLine(l + rr, t, Math.min(l + seg, r), t, p);
                canvas.drawLine(l, t + rr, l, Math.min(t + seg, b), p);
                canvas.drawArc(new RectF(r - rr * 2, t, r, t + rr * 2), 270, 90, false, p);
                canvas.drawLine(Math.max(r - seg, l), t, r - rr, t, p);
                canvas.drawLine(r, t + rr, r, Math.min(t + seg, b), p);
                canvas.drawArc(new RectF(l, b - rr * 2, l + rr * 2, b), 90, 90, false, p);
                canvas.drawLine(l, Math.max(b - seg, t), l, b - rr, p);
                canvas.drawLine(l + rr, b, Math.min(l + seg, r), b, p);
                canvas.drawArc(new RectF(r - rr * 2, b - rr * 2, r, b), 0, 90, false, p);
                canvas.drawLine(r, Math.max(b - seg, t), r, b - rr, p);
                canvas.drawLine(Math.max(r - seg, l), b, r - rr, b, p);
                break;
            }
            case 5: {   // 底部条
                float bh = dp1 * 5;
                p.setStyle(Paint.Style.FILL);
                p.setColor(c);
                box.set(dp1, h - bh - dp1, w - dp1, h - dp1);
                canvas.drawRoundRect(box, bh / 2f, bh / 2f, p);
                break;
            }
            case 6: {   // 顶部条
                float bh = dp1 * 5;
                p.setStyle(Paint.Style.FILL);
                p.setColor(c);
                box.set(dp1, dp1, w - dp1, dp1 + bh);
                canvas.drawRoundRect(box, bh / 2f, bh / 2f, p);
                break;
            }
            case 7: {   // 虚线
                float lw = dp1 * 3;
                p.setStrokeWidth(lw);
                p.setColor(c);
                p.setPathEffect(new android.graphics.DashPathEffect(
                        new float[]{dp1 * 10, dp1 * 7}, 0));
                float o = lw / 2 + dp1;
                box.set(o, o, w - o, h - o);
                canvas.drawRoundRect(box, rad, rad, p);
                p.setPathEffect(null);
                break;
            }
            case 8: {   // 纯透明玻璃：整块盖住缩略图 + 半透白边
                if (EMBY) {
                    // 苹果风：一圈干净的白边（tvOS 的焦点就是白色描边 + 轻微放大），不压暗画面
                    float o = outsetPx;
                    float lw = dp1 * 3f;
                    p.setStyle(Paint.Style.STROKE);
                    RectF ring = new RectF(-o - lw / 2f, -o - lw / 2f,
                            w + o + lw / 2f, h + o + lw / 2f);
                    float rr = Math.max(dp1, rad + o + lw / 2f);
                    p.setStrokeWidth(lw);
                    p.setColor(RING);
                    canvas.drawRoundRect(ring, rr, rr, p);
                    break;
                }
                if (ST) {
                    // 潮牌：贴纸错版焦点框 —— 白色粗描边 + 粉红错位描边（不动画面内容）
                    float lw2 = dp1 * 3.5f;
                    float off = dp1 * 3f;
                    p.setStyle(Paint.Style.STROKE);
                    p.setStrokeWidth(lw2);
                    p.setColor(0xFFFF2E77);
                    if (outsetPx > 0) {
                        box.set(-outsetPx + off, -outsetPx + off,
                                w + outsetPx + off, h + outsetPx + off);
                        canvas.drawRect(box, p);
                    }
                    p.setColor(0xFFFFFFFF);
                    if (outsetPx > 0) {
                        box.set(-outsetPx - lw2 / 2, -outsetPx - lw2 / 2,
                                w + outsetPx + lw2 / 2, h + outsetPx + lw2 / 2);
                    } else {
                        float i2 = lw2 / 2;
                        box.set(i2, i2, w - i2, h - i2);
                    }
                    canvas.drawRect(box, p);
                    if (outsetPx <= 0) {
                        // 小控件：再补一条很细的青色内线，增加潮味
                        p.setStrokeWidth(dp1 * 1.4f);
                        p.setColor(0xFFE8F00A);
                        float i3 = lw2 + dp1 * 1.6f;
                        box.set(i3, i3, w - i3, h - i3);
                        canvas.drawRect(box, p);
                    }
                    p.setStyle(Paint.Style.FILL);
                    break;
                }
                p.setStyle(Paint.Style.FILL);
                p.setColor(0x1AFFFFFF);
                box.set(0, 0, w, h);
                canvas.drawRoundRect(box, rad, rad, p);
                float lw = dp1 * 3;
                float o = outsetPx;
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(lw);
                p.setColor(ringColor == 0 ? (DG ? RING : 0x80FFFFFF) : ringColor);
                if (o > 0) {
                    // 卡片：框画在缩略图外侧（由容器统一绘制，不会被裁）
                    box.set(-o - lw / 2, -o - lw / 2, w + o + lw / 2, h + o + lw / 2);
                    canvas.drawRoundRect(box, rad + o, rad + o, p);
                } else {
                    // 按钮等画在自身画布里的控件：描边整体收进边界内，否则外半圈会被自身画布裁掉
                    float i = lw / 2;
                    box.set(i, i, w - i, h - i);
                    float rr = Math.max(0f, rad - i);
                    canvas.drawRoundRect(box, rr, rr, p);
                }
                break;
            }
            case 9: {   // 渐变边：上亮下淡
                float lw = dp1 * 3;
                p.setStrokeWidth(lw);
                float o = lw / 2 + dp1;
                box.set(o, o, w - o, h - o);
                int save = canvas.save();
                canvas.clipRect(0f, 0f, w, h / 2f);
                p.setColor(c);
                canvas.drawRoundRect(box, rad, rad, p);
                canvas.restoreToCount(save);
                save = canvas.save();
                canvas.clipRect(0f, h / 2f, w, h);
                p.setColor((c & 0x00FFFFFF) | 0x66000000);
                canvas.drawRoundRect(box, rad, rad, p);
                canvas.restoreToCount(save);
                break;
            }
            default: {  // 0 细描边
                float lw = dp1 * 3;
                p.setStrokeWidth(lw);
                p.setColor(c);
                float o = lw / 2;
                box.set(o, o, w - o, h - o);
                canvas.drawRoundRect(box, rad, rad, p);
                break;
            }
        }
        p.setPathEffect(null);
        p.setStyle(Paint.Style.FILL);
    }

    /** 给任意按钮套一层跟随样式的焦点框（前景绘制，不影响底色） */
    public static void ringFg(final android.view.View v, final float radPx) {
        final android.graphics.drawable.StateListDrawable sl =
                new android.graphics.drawable.StateListDrawable();
        final android.graphics.drawable.ColorDrawable none =
                new android.graphics.drawable.ColorDrawable(0x00000000);
        final android.graphics.drawable.Drawable ring =
                new android.graphics.drawable.Drawable() {
                    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

                    @Override public void draw(android.graphics.Canvas canvas) {
                        android.graphics.Rect b = getBounds();
                        drawRing(canvas, b.width(), b.height(), radPx,
                                v.getResources().getDisplayMetrics().density, p);
                    }

                    @Override public void setAlpha(int a) { }
                    @Override public void setColorFilter(android.graphics.ColorFilter f) { }
                    @Override public int getOpacity() {
                        return android.graphics.PixelFormat.TRANSPARENT;
                    }
                };
        sl.addState(new int[]{android.R.attr.state_focused}, ring);
        sl.addState(new int[0], none);
        v.setForeground(sl);
    }

    /** 焦点框颜色：0=跟随主题，其余为固定色 */
    public static final String[] RING_NAMES = {L.t("跟随主题"), L.t("绿"), L.t("白"), L.t("黄"), L.t("青"), L.t("紫"), L.t("橙")};
    public static final int[] RING_VALUES = {0xFFFFFFFF, 0xFF58CC6C, 0xFFFFFFFF,
            0xFFFFD54F, 0xFF4DD0E1, 0xFFB388FF, 0xFFFF8A65};

    /** 应用焦点框颜色选择（必须在 applyTheme 之后调用）；跟随主题时走主题色 */
    public static void applyRing(int idx) {
        if (idx <= 0 || idx >= RING_VALUES.length) {
            RING = ST ? 0xFFFFFFFF : (DG ? 0xFF52B54B : (EMBY ? 0xFF1C1C1E : 0xFFFFFFFF));
            RING2 = ST ? 0xFFE8F00A : (DG ? 0xFF8BD98A : RING);
        } else {
            RING = RING_VALUES[idx];
            RING2 = RING;
        }
    }

    /** 预载背景图（0 = 原生，11 = 纯白，其余 = assets/bg/bgN.jpg） */
    public static void prepareBg(Context c, int idx) {
        BG_CTX = c.getApplicationContext();
        BG_INDEX = idx;
        BG_WHITE = (idx == 11);
        if (idx <= 0) {
            BG_BMP = null;
            if (EMBY) { BG_WHITE = true; applyLightBg(true); return; }
            applyLightBg(false);
            return;
        }
        if (BG_WHITE) { BG_BMP = null; applyLightBg(true); return; }
        try {
            BG_BMP = android.graphics.BitmapFactory.decodeStream(
                    c.getAssets().open("bg/bg" + idx + ".jpg"));
        } catch (Throwable t) {
            BG_BMP = null;
        }
        // 采样 8×8 估平均亮度，亮底壁纸自动切浅色前景
        float lum = 0f;
        if (BG_BMP != null) {
            int w = BG_BMP.getWidth(), h = BG_BMP.getHeight();
            int n = 0;
            for (int y = 0; y < 8; y++) {
                for (int x = 0; x < 8; x++) {
                    int px = BG_BMP.getPixel(Math.min(w - 1, x * w / 8),
                                             Math.min(h - 1, y * h / 8));
                    lum += 0.2126f * Color.red(px) + 0.7152f * Color.green(px)
                            + 0.0722f * Color.blue(px);
                    n++;
                }
            }
            if (n > 0) lum /= n;
        }
        applyLightBg(lum >= 150f);
    }

    /** 页面底色：选了背景图就让页面透明，好让壁纸透出来 */
    public static int pageBgColor() {
        if (BG_BMP != null || EMBY) return 0x00000000;
        return GLASS ? 0x00FFFFFF : (LIGHT ? 0xE6F4F7FC : 0xD90C0F14);
    }

    /** 炫酷背景（iOS 浅色）：纯白到底灰的极淡渐变；选了壁纸则压一层白雾保证黑字可读 */
    public static android.graphics.drawable.Drawable bgDraw() {
        if (EMBY) {
            // 炫酷风格固定纯白底（背景图设置只对玻璃风格生效）
            return new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                    new int[]{0xFFFFFFFF, 0xFFF8F8FA});
        }
        if (BG_WHITE) {
            return new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                    new int[]{0xFFFFFFFF, 0xFFF7F7FA});
        }
        if (BG_BMP != null && BG_CTX != null) {
            android.graphics.drawable.BitmapDrawable bd =
                    new android.graphics.drawable.BitmapDrawable(BG_CTX.getResources(), BG_BMP);
            bd.setGravity(android.view.Gravity.FILL);
            GradientDrawable scrim = EMBY
                    ? new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                            new int[]{0xD9FFFFFF, 0xE6FFFFFF})
                    : new GradientDrawable(
                            GradientDrawable.Orientation.TOP_BOTTOM,
                            LIGHT_BG ? new int[]{0x3306070A, 0x5506070A}
                                     : new int[]{0x9E06070A, 0xB806070A});
            return new android.graphics.drawable.LayerDrawable(
                    new android.graphics.drawable.Drawable[]{bd, scrim});
        }
        if (EMBY) {
            return new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                    new int[]{0xFFF7F7FA, 0xFFF2F2F7, 0xFFEDEDF2});
        }
        return new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{BG1, BG2});
    }

    /** 卡片底：玻璃主题带描边，其它主题纯圆角；炫酷走霓虹渐变描边 */
    public static GradientDrawable cardBg(Context c) {
        if (EMBY) {
            GradientDrawable d = round(CARD, dp(c, RADIUS));
            d.setStroke(dp(c, 1), 0x14000000);
            return d;
        }
        GradientDrawable d = round(CARD, dp(c, RADIUS));
        if (DG || ST) {
            d.setStroke(dp(c, ST ? 1.5f : 1f), BORDER);
            return d;
        }
        if (GLASS || THEME == 1) d.setStroke(dp(c, 1), BORDER);
        return d;
    }

    /** 登录/设置这类大面板：半透明玻璃 */
    public static GradientDrawable glassPanel(Context c) {
        if (EMBY) return roundStroke(0xFFFFFFFF, dp(c, 12), dp(c, 1), 0x14000000);
        int fill = LIGHT_BG ? 0xF2FFFFFF : (GLASS ? 0xCC26262A : (LIGHT ? 0xF7FFFFFF : 0xE61A1A20));
        if (DG) fill = 0xFF151915;
        else if (ST) fill = 0xFF131316;
        GradientDrawable d = roundStroke(fill, dp(c, RADIUS), dp(c, DG || ST ? 1.5f : 1), BORDER);
        return d;
    }

    /** 输入框底：底色恒定，聚焦框交给外面的白玻璃环（不再描绿边） */
    public static GradientDrawable inputBg(Context c, boolean focused) {
        if (EMBY) return roundStroke(focused ? 0xFFFFFFFF : 0xFFF2F2F7, dp(c, 8), dp(c, focused ? 2f : 1f),
                focused ? 0xFF1C1C1E : 0x0F000000);
        int fill = LIGHT_BG ? 0xFFFFFFFF : (GLASS ? 0xC2303035 : (LIGHT ? 0xFFFFFFFF : 0xFF181818));
        if (DG) fill = 0xFF1E241E;
        else if (ST) fill = 0xFF1C1C22;
        return roundStroke(fill, dp(c, ST ? 0 : RADIUS), dp(c, DG || ST ? 1.5f : 1), BORDER);
    }

    public static int dp(Context c, float v) {
        return (int) (v * c.getResources().getDisplayMetrics().density + 0.5f);
    }

    public static GradientDrawable round(int color, float radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(radius);
        return d;
    }

    public static GradientDrawable roundStroke(int color, float radius, int strokeW, int strokeColor) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(radius);
        d.setStroke(strokeW, strokeColor);
        return d;
    }

    /** 弹层统一底：设置页、下拉面板、播放器菜单都走这一套；炫酷是幽蓝黑 + 青边 */
    public static GradientDrawable sheetBg(Context c) {
        if (EMBY && !LIGHT_BG) return roundStroke(0xFFFFFFFF, dp(c, 10), dp(c, 1), 0x1A000000);
        int fill = LIGHT_BG ? 0xF2FFFFFF
                : (GLASS ? 0xE626262A : (LIGHT ? 0xF7FFFFFF : 0xE6232A33));
        if (DG) fill = 0xFF131713;
        else if (ST) fill = 0xFF121215;
        return roundStroke(fill, dp(c, ST ? 0 : 18), dp(c, DG || ST ? 1.5f : 1), BORDER);
    }

    /** 设置页专用：方形面板底（小圆角），其余样式同 sheetBg */
    public static GradientDrawable sheetBgSquare(Context c) {
        if (EMBY && !LIGHT_BG) return roundStroke(0xFFFFFFFF, dp(c, 4), dp(c, 1), 0x1A000000);
        int fill = LIGHT_BG ? 0xF2FFFFFF
                : (GLASS ? 0xE626262A : (LIGHT ? 0xF7FFFFFF : 0xE6232A33));
        if (DG) fill = 0xFF131713;
        else if (ST) fill = 0xFF121215;
        return roundStroke(fill, dp(c, 4), dp(c, DG || ST ? 1.5f : 1), BORDER);
    }

    /** 字体：0=默认粗黑 1=常规 2=宋体 3=窄体 4=毛笔手写 5=卡通艺术 6=等宽 */
    public static int FONT = 0;
    private static android.graphics.Typeface FONT_TF;

    /** 文字颜色手选：0=跟随主题 1=黑 2=白（只改正文主色，灰阶/按钮色不受影响） */
    public static int FORCE_TXT = 0;
    public static void applyTxt(int mode) { FORCE_TXT = mode; }

    /** 手选文字颜色时，所有文字（含自绘、按钮、标签）都按它走；没选就返回原色 */
    public static int txt(int fallback) {
        if (FORCE_TXT == 1) return 0xFF000000;
        if (FORCE_TXT == 2) return 0xFFFFFFFF;
        return fallback;
    }

    public static void applyFont(Context c, int idx) {
        FONT = idx;
        try {
            if (idx == 1) FONT_TF = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.NORMAL);
            else if (idx == 2) FONT_TF = android.graphics.Typeface.create("serif", android.graphics.Typeface.NORMAL);
            else if (idx == 3) FONT_TF = android.graphics.Typeface.create("sans-serif-condensed", android.graphics.Typeface.BOLD);
            else if (idx == 4) FONT_TF = android.graphics.Typeface.createFromAsset(c.getAssets(), "fonts/MaShanZheng-Regular.ttf");
            else if (idx == 5) FONT_TF = android.graphics.Typeface.createFromAsset(c.getAssets(), "fonts/ZCOOLKuaiLe-Regular.ttf");
            else if (idx == 6) FONT_TF = android.graphics.Typeface.create("monospace", android.graphics.Typeface.NORMAL);
            else FONT_TF = null;
        } catch (Throwable t) { FONT_TF = null; }
    }

    /** 把设置里手选的字体套到「不是 U.tv 造出来」的文字控件上；没手选就保持原样 */
    public static void face(TextView t) { if (FONT_TF != null) t.setTypeface(FONT_TF); }

    /** 自绘文字（Canvas.drawText）用这个套字体 */
    public static void face(android.graphics.Paint p) { if (FONT_TF != null) p.setTypeface(FONT_TF); }

    public static TextView tv(Context c, String text, float sp, int color, boolean bold) {
        TextView t = new TextView(c);
        t.setText(text);
        t.setTextColor(txt(color));
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        // 设置里手选的字体优先；没选就按主题走（炫酷＝粗黑体），大小/颜色各自保留
        if (FONT_TF != null) {
            t.setTypeface(FONT_TF);
        } else if (EMBY) {
            t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        } else if (bold) {
            t.setTypeface(t.getTypeface(), android.graphics.Typeface.BOLD);
        }
        t.setIncludeFontPadding(false);
        return t;
    }

    /** 可聚焦的按钮：焦点统一用外面那套白玻璃环，底色不因焦点改变 */
    public static TextView button(Context c, String text, float sp, int wdp, int hdp) {
        final TextView t = tv(c, text, sp, TXT, false);
        t.setGravity(android.view.Gravity.CENTER);
        t.setPadding(dp(c, 18), 0, dp(c, 18), 0);
        t.setMinWidth(wdp > 0 ? dp(c, wdp) : dp(c, 90));
        t.setMinHeight(dp(c, hdp > 0 ? hdp : 40));
        t.setBackground(round(LIGHT ? 0xFFFFFFFF : PANEL2, dp(c, RADIUS)));
        t.setFocusable(true);
        t.setFocusableInTouchMode(true);
        ringFg(t, dp(c, RADIUS));
        t.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override public void onFocusChange(View v, boolean has) {
                animate(v, has ? 1.06f : 1f);
            }
        });
        return t;
    }

    public static TextView primaryButton(Context c, String text, float sp) {
        final TextView t = tv(c, text, sp, ON_ACC, true);
        t.setGravity(android.view.Gravity.CENTER);
        t.setPadding(dp(c, 24), dp(c, 12), dp(c, 24), dp(c, 12));
        t.setBackground(round(ACC, dp(c, RADIUS)));
        t.setFocusable(true);
        t.setFocusableInTouchMode(true);
        ringFg(t, dp(c, RADIUS));
        t.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override public void onFocusChange(View v, boolean has) {
                animate(v, has ? 1.06f : 1f);
            }
        });
        return t;
    }

    public static void animate(View v, float scale) {
        ScaleAnimation a = new ScaleAnimation(1f, scale, 1f, scale,
                ScaleAnimation.RELATIVE_TO_SELF, 0.5f, ScaleAnimation.RELATIVE_TO_SELF, 0.5f);
        a.setDuration(120);
        a.setFillAfter(true);
        v.startAnimation(a);
    }

    public static LinearLayout col(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    public static LinearLayout row(Context c) {
        // 横排容器换成 RingRow：焦点框由容器自己在 dispatchDraw 里画，
        // 不画在卡片边界外，就不会被滚动区/卡片的裁剪矩形切掉
        LinearLayout l = new RingRow(c);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(android.view.Gravity.CENTER_VERTICAL);
        return l;
    }

    public static LinearLayout.LayoutParams lp(int w, int h) {
        return new LinearLayout.LayoutParams(w, h);
    }

    public static LinearLayout.LayoutParams lpm(int w, int h, int left, int top, int right, int bottom) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h);
        p.setMargins(left, top, right, bottom);
        return p;
    }

    public static View spacer(Context c, int w, int h) {
        View v = new View(c);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h);
        v.setLayoutParams(p);
        return v;
    }

    public static FrameLayout.LayoutParams flp(int w, int h) {
        return new FrameLayout.LayoutParams(w, h);
    }

    public static int color(String hex, int def) {
        try { return Color.parseColor(hex); } catch (Exception e) { return def; }
    }

    public static String time(long ms) {
        if (ms < 0) ms = 0;
        long s = ms / 1000;
        long h = s / 3600, m = (s % 3600) / 60, ss = s % 60;
        if (h > 0) return String.format("%d:%02d:%02d", h, m, ss);
        return String.format("%02d:%02d", m, ss);
    }

    /**
     * 播放信息面板（复刻官方 Emby 的「播放统计」）：
     * 分段标题 + 左侧浅灰标签冒号 + 右侧白值的两列行，整块半透明卡片。
     */
    public static class StatsCard extends LinearLayout {
        public StatsCard(Context c) {
            super(c);
            setOrientation(VERTICAL);
            setBackground(round(0xF213161B, dp(c, 12)));
            setPadding(dp(c, 20), dp(c, 12), dp(c, 24), dp(c, 14));
            setVisibility(GONE);
        }

        /** 宽度最多占屏幕一部分，避免长值把卡片撑满；高度自然增长 */
        @Override protected void onMeasure(int wSpec, int hSpec) {
            int maxW = Math.max(dp(getContext(), 320),
                    getResources().getDisplayMetrics().widthPixels - dp(getContext(), 140));
            super.onMeasure(MeasureSpec.makeMeasureSpec(maxW, MeasureSpec.AT_MOST), hSpec);
        }

        /** groups[i] = {分组标题, 标签1, 值1, 标签2, 值2, ...} */
        public void render(String[][] groups) {
            removeAllViews();
            if (groups == null) return;
            Context c = getContext();
            StringBuilder log = new StringBuilder(L.t("统计面板 段=")).append(groups.length);
            for (int gi = 0; gi < groups.length; gi++) {
                String[] g = groups[gi];
                if (g == null || g.length == 0) continue;
                TextView t = tv(c, g[0], 16, 0xFFFFFFFF, true);
                t.setPadding(0, gi == 0 ? 0 : dp(c, 10), 0, dp(c, 5));
                addView(t, new LinearLayout.LayoutParams(-1, -2));
                for (int i = 1; i + 1 < g.length; i += 2) {
                    LinearLayout row = new LinearLayout(c);
                    row.setOrientation(HORIZONTAL);
                    row.setPadding(dp(c, 12), 0, 0, 0);
                    TextView l = tv(c, g[i] + "：", 14, 0xFFC2CBD6, false);
                    l.setSingleLine(true);
                    row.addView(l, new LinearLayout.LayoutParams(-2, -2));
                    TextView v = tv(c, g[i + 1], 14, 0xFFFFFFFF, false);
                    v.setSingleLine(false);
                    v.setPadding(dp(c, 6), 0, 0, 0);
                    row.addView(v, new LinearLayout.LayoutParams(-2, -2));
                    LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(-2, -2);
                    rl.bottomMargin = dp(c, 4);
                    addView(row, rl);
                    log.append(" | ").append(g[i]).append('=').append(g[i + 1]);
                }
            }
            android.util.Log.e("LMBY", log.toString());
            post(new Runnable() {
                @Override public void run() {
                    android.util.Log.e("LMBY", "统计面板尺寸 " + getWidth() + "x" + getHeight()
                            + L.t(" 屏=") + getResources().getDisplayMetrics().widthPixels
                            + "x" + getResources().getDisplayMetrics().heightPixels
                            + L.t(" 顶部=") + getTop());
                }
            });
        }
    }

    /**
     * 播放器菜单的一套线条图标。
     * 全部按同一 24 单位网格、同一线宽、同一圆角绘制，避免几种字符图标粗细 / 基线对不齐。
     */
    public static class IconView extends View {
        public static final int DECODE = 0, TRANSCODE = 1, SUBTITLE = 2, AUDIO = 3,
                DISPLAY = 4, SPEED = 5, MODE = 6, STATS = 7;

        private final int kind;
        private int iconColor;
        private final Paint ip = new Paint(Paint.ANTI_ALIAS_FLAG);

        public IconView(Context c, int kind, int color) {
            super(c);
            this.kind = kind;
            this.iconColor = color;
            ip.setStyle(Paint.Style.STROKE);
            ip.setStrokeCap(Paint.Cap.ROUND);
        }

        public void setIconColor(int c) {
            iconColor = c;
            invalidate();
        }

        @Override protected void onMeasure(int wSpec, int hSpec) {
            int d = (int) (22 * getResources().getDisplayMetrics().density);
            setMeasuredDimension(resolveSize(d, wSpec), resolveSize(d, hSpec));
        }

        @Override protected void onDraw(android.graphics.Canvas cv) {
            float w = getWidth(), h = getHeight();
            float s = Math.min(w, h);
            float ox = (w - s) / 2f, oy = (h - s) / 2f;
            float u = s / 24f;
            float x = ox, y = oy;
            ip.setColor(iconColor);
            ip.setStrokeWidth(Math.max(1f, s / 15f));

            switch (kind) {
                case DECODE: {   // 芯片
                    cv.drawRoundRect(x + 5 * u, y + 5 * u, x + 19 * u, y + 19 * u, 2.4f * u, 2.4f * u, ip);
                    cv.drawRoundRect(x + 9.5f * u, y + 9.5f * u, x + 14.5f * u, y + 14.5f * u, 1.4f * u, 1.4f * u, ip);
                    for (int i = 0; i < 3; i++) {
                        float q = y + (8f + i * 4f) * u;
                        cv.drawLine(x + 2 * u, q, x + 5 * u, q, ip);
                        cv.drawLine(x + 19 * u, q, x + 22 * u, q, ip);
                    }
                    break;
                }
                case TRANSCODE: {   // 双向箭头
                    float y1 = y + 8.5f * u, y2 = y + 15.5f * u;
                    cv.drawLine(x + 3 * u, y1, x + 19 * u, y1, ip);
                    cv.drawLine(x + 19 * u, y1, x + 15 * u, y1 - 3.4f * u, ip);
                    cv.drawLine(x + 19 * u, y1, x + 15 * u, y1 + 3.4f * u, ip);
                    cv.drawLine(x + 21 * u, y2, x + 5 * u, y2, ip);
                    cv.drawLine(x + 5 * u, y2, x + 9 * u, y2 - 3.4f * u, ip);
                    cv.drawLine(x + 5 * u, y2, x + 9 * u, y2 + 3.4f * u, ip);
                    break;
                }
                case SUBTITLE: {    // 对话气泡 + 两行字
                    cv.drawRoundRect(x + 2.5f * u, y + 4 * u, x + 21.5f * u, y + 16.5f * u, 3 * u, 3 * u, ip);
                    cv.drawLine(x + 6.5f * u, y + 9 * u, x + 17.5f * u, y + 9 * u, ip);
                    cv.drawLine(x + 6.5f * u, y + 12.5f * u, x + 13.5f * u, y + 12.5f * u, ip);
                    cv.drawLine(x + 7 * u, y + 16.5f * u, x + 5.5f * u, y + 20 * u, ip);
                    break;
                }
                case AUDIO: {   // 音符
                    cv.drawCircle(x + 8.5f * u, y + 17.5f * u, 3.2f * u, ip);
                    cv.drawLine(x + 11.7f * u, y + 17.5f * u, x + 11.7f * u, y + 5 * u, ip);
                    cv.drawLine(x + 11.7f * u, y + 5 * u, x + 18.5f * u, y + 7 * u, ip);
                    cv.drawLine(x + 18.5f * u, y + 7 * u, x + 18.5f * u, y + 13 * u, ip);
                    break;
                }
                case DISPLAY: { // 屏幕
                    cv.drawRoundRect(x + 2.5f * u, y + 4 * u, x + 21.5f * u, y + 16.5f * u, 2.6f * u, 2.6f * u, ip);
                    cv.drawLine(x + 12 * u, y + 16.5f * u, x + 12 * u, y + 20 * u, ip);
                    cv.drawLine(x + 7.5f * u, y + 20.5f * u, x + 16.5f * u, y + 20.5f * u, ip);
                    break;
                }
                case SPEED: {   // 快进三角
                    cv.drawLine(x + 7 * u, y + 5 * u, x + 7 * u, y + 19 * u, ip);
                    cv.drawLine(x + 7 * u, y + 5 * u, x + 19 * u, y + 12 * u, ip);
                    cv.drawLine(x + 19 * u, y + 12 * u, x + 7 * u, y + 19 * u, ip);
                    break;
                }
                case MODE: {    // 循环
                    cv.drawArc(x + 4 * u, y + 4 * u, x + 20 * u, y + 20 * u, 45, 245, false, ip);
                    cv.drawLine(x + 18.6f * u, y + 8.5f * u, x + 19.5f * u, y + 2.5f * u, ip);
                    cv.drawLine(x + 19.5f * u, y + 2.5f * u, x + 14 * u, y + 4.5f * u, ip);
                    break;
                }
                case STATS: {   // 三根柱子
                    float base = y + 19 * u;
                    cv.drawLine(x + 3 * u, base, x + 21 * u, base, ip);
                    cv.drawLine(x + 7 * u, base, x + 7 * u, y + 12 * u, ip);
                    cv.drawLine(x + 12 * u, base, x + 12 * u, y + 6 * u, ip);
                    cv.drawLine(x + 17 * u, base, x + 17 * u, y + 9 * u, ip);
                    break;
                }
                default:
                    break;
            }
        }
    }

    // ================= 第三方播放器（首次选一次，选中后固定） =================

    /** 常见播放器排前面 */
    private static final String[] THIRD_PREF = {
            "org.videolan.vlc", "com.mxtech.videoplayer.ad", "org.xbmc.kodi"};

    /**
     * 明显不是"本地视频播放器"的，别列进去：桌面 / 投屏 / 相册图库 / 音乐 /
     * 系统安装器与文档 / 当贝影视内容插件。文件管理器、影视应用都留着。
     */
    private static final String[] THIRD_BLOCK = {
            "tvlauncher", "leradlauncher", "launcher3", "launcher.companion", "launch",
            "screencast", "miracast", "cast", "yungallery", "pictureviewer", "gallery",
            "dbmusic", "certinstaller", "documentsui", "systemui", "tvcenter",
            "leradVideoPlugins", "com.android.providers",
            // 浏览器 / 网页查看器 / 安装器不是播放器
            "com.android.browser", "htmlviewer", "packageinstaller", "webview.shell"};

    private static boolean thirdBlocked(String p) {
        if (p == null || p.isEmpty()) return true;
        for (int i = 0; i < THIRD_BLOCK.length; i++)
            if (p.contains(THIRD_BLOCK[i])) return true;
        return false;
    }

    /**
     * 列出能接住视频的第三方播放器：{包名, Activity, 显示名}。
     * 按系统选择框（Emby 官方那种「选第三方客户端」）的口径来查：
     * ACTION_VIEW + http 地址 + video 系 mime，一种 mime「带 http data」查一次，
     * 再「只给 type 不带 data」查一次，覆盖只认 type 的播放器。
     * 不要用全通配 mime：那样会把浏览器、网页查看器、安装器全拉进来。
     */
    public static java.util.ArrayList<String[]> thirdCandidates(Context ctx) {
        java.util.ArrayList<String[]> out = new java.util.ArrayList<String[]>();
        String[] types = {"video/*", "video/mp4", "video/x-matroska", "video/mpeg",
                "video/mp2t", "video/avc", "video/x-msvideo", "application/octet-stream"};
        for (int t = 0; t < types.length; t++) {
            for (int withData = 0; withData < 2; withData++) {
                android.content.Intent probe = new android.content.Intent(android.content.Intent.ACTION_VIEW);
                probe.addCategory(android.content.Intent.CATEGORY_DEFAULT);
                if (withData == 0) probe.setType(types[t]);
                else probe.setDataAndType(android.net.Uri.parse("http://127.0.0.1/a.mp4"), types[t]);
                java.util.List<android.content.pm.ResolveInfo> list = null;
                try { list = ctx.getPackageManager().queryIntentActivities(probe, 0); }
                catch (Throwable ignored) { continue; }
                if (list == null) continue;
                for (int k = 0; k < list.size(); k++) {
                    android.content.pm.ResolveInfo ri = list.get(k);
                    if (ri.activityInfo == null) continue;
                    String p = ri.activityInfo.packageName;
                    if (p == null || p.equals(ctx.getPackageName())) continue;
                    if (thirdBlocked(p)) continue;
                    addCandidate(ctx, out, ri);
                }
            }
        }
        // 已知播放器排到最前面
        java.util.ArrayList<String[]> sorted = new java.util.ArrayList<String[]>();
        for (int p = 0; p < THIRD_PREF.length; p++) {
            for (int i = 0; i < out.size(); i++) {
                if (THIRD_PREF[p].equals(out.get(i)[0])) { sorted.add(out.get(i)); out.remove(i); i--; }
            }
        }
        sorted.addAll(out);
        return sorted;
    }

    private static void addCandidate(Context ctx, java.util.ArrayList<String[]> out, android.content.pm.ResolveInfo ri) {
        String p = ri.activityInfo.packageName;
        for (int i = 0; i < out.size(); i++)
            if (out.get(i)[0].equals(p)) return;       // 同一个包只留一个入口
        String lb;
        try { lb = String.valueOf(ri.loadLabel(ctx.getPackageManager())); }
        catch (Throwable t) { lb = p; }
        if (lb == null || lb.isEmpty() || "null".equals(lb)) lb = p;
        out.add(new String[]{p, ri.activityInfo.name, lb});
    }

    /**
     * 用第三方播放器打开视频：
     * 已经选过就直接用记住的那个；没选过（或被「清除初始化」过）弹一次选择框，选中即固定。
     */
    public static void launchThird(final Context ctx, final String url, final String title, final Store st) {
        launchThird(ctx, url, title, st, null);
    }

    /**
     * 同上，afterLaunch 在第三方播放器真正被拉起后才执行
     * （播放器里调用时用它来 finish 自己，不能提前 finish，否则选择框会跟着窗口一起没掉）。
     */
    public static void launchThird(final Context ctx, final String url, final String title, final Store st,
                                   final Runnable afterLaunch) {
        // 打点：从第三方播放器返回时不能按「启动密码」再验一遍（否则像看个片回来就要重输密码）
        if (st != null) st.markExternalPlay();
        final android.content.Intent base = new android.content.Intent(android.content.Intent.ACTION_VIEW);
        base.setDataAndType(android.net.Uri.parse(url), "video/*");
        base.putExtra("title", title);
        base.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);

        String pkg = st == null ? "" : st.thirdPkg();
        String cls = st == null ? "" : st.thirdCls();
        if (pkg != null && !pkg.isEmpty()) {
            android.content.Intent i = new android.content.Intent(base);
            if (cls != null && !cls.isEmpty()) i.setClassName(pkg, cls); else i.setPackage(pkg);
            try {
                ctx.startActivity(i);
                if (afterLaunch != null) afterLaunch.run();
                return;
            } catch (Throwable ignored) { }
            // 记住的那个被卸载了 → 清掉记忆，重新走选择流程
            if (st != null) st.clearThirdPlayer();
        }

        java.util.ArrayList<String[]> cs = thirdCandidates(ctx);
        if (cs.isEmpty()) {
            try {
                android.widget.Toast.makeText(ctx, L.t("没有可用的第三方播放器"), android.widget.Toast.LENGTH_LONG).show();
            } catch (Throwable ignored) { }
            return;
        }
        // 跟系统选择框一样的两个出口：仅此一次 / 始终使用。
        // 电视上 AlertDialog 的按钮用遥控器选不到，所以两级都做成「纯列表 + 方向键确认」：
        // 先选播放器，再选这次用多久
        final String[] labels = new String[cs.size()];
        for (int k = 0; k < cs.size(); k++) {
            String p = cs.get(k)[0];
            if (p.startsWith("com.")) p = p.substring(4);
            labels[k] = cs.get(k)[2] + "  (" + p + ")";
        }
        final java.util.ArrayList<String[]> fcs = cs;
        try {
            android.app.AlertDialog.Builder b =
                    new android.app.AlertDialog.Builder(ctx, android.R.style.Theme_DeviceDefault_Dialog_Alert);
            b.setTitle(L.t("选择播放器"));
            b.setItems(labels, new android.content.DialogInterface.OnClickListener() {
                @Override public void onClick(android.content.DialogInterface d, int which) {
                    if (which < 0 || which >= fcs.size()) return;
                    askHowLong(ctx, base, fcs.get(which), st, afterLaunch);
                }
            });
            b.show();
            return;
        } catch (Throwable ignored) { }
        // 弹不出来（极少数定制 ROM）：退回第一个候选
        startThird(ctx, base, cs.get(0), st, afterLaunch);
    }

    /** 第二级：这次只用一次，还是以后一直用它 */
    private static void askHowLong(final Context ctx, final android.content.Intent base,
                                   final String[] c, final Store st, final Runnable afterLaunch) {
        try {
            android.app.AlertDialog.Builder b =
                    new android.app.AlertDialog.Builder(ctx, android.R.style.Theme_DeviceDefault_Dialog_Alert);
            b.setTitle(L.t("用 ") + c[2] + L.t(" 播放"));
            b.setItems(new String[]{L.t("仅此一次"), L.t("始终使用（记住，设置页可清除）")},
                    new android.content.DialogInterface.OnClickListener() {
                @Override public void onClick(android.content.DialogInterface d, int which) {
                    // which==1 才写记忆；0 就是临时这一次
                    startThird(ctx, base, c, which == 1 ? st : null, afterLaunch);
                }
            });
            b.show();
        } catch (Throwable t) {
            startThird(ctx, base, c, null, afterLaunch);
        }
    }

    private static void startThird(Context ctx, android.content.Intent base, String[] c, Store st) {
        startThird(ctx, base, c, st, null);
    }

    private static void startThird(Context ctx, android.content.Intent base, String[] c, Store st,
                                   Runnable afterLaunch) {
        if (st != null) st.thirdPlayer(c[0], c[1]);
        android.content.Intent i = new android.content.Intent(base);
        i.setClassName(c[0], c[1]);
        try {
            ctx.startActivity(i);
            if (afterLaunch != null) afterLaunch.run();
        } catch (Throwable t) {
            try {
                android.widget.Toast.makeText(ctx, L.t("启动失败：") + c[0], android.widget.Toast.LENGTH_SHORT).show();
            } catch (Throwable ignored) { }
        }
    }
}
