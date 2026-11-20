package com.longyao.anbitv;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapRegionDecoder;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.view.View;

/**
 * 超高清看图控件。
 * 思路：整图只做缩略预览；真正显示的可见区域用 BitmapRegionDecoder 从原图按原分辨率解码，
 * 并且把可见区域切成多张 ≤2048 的小块平铺——单块不超过 GPU 纹理上限，所以既清晰又不花屏。
 */
public class PhotoView extends View {

    /** 单张贴图上限：老电视 GPU 纹理只有 2048，超过会整片撕裂 */
    private static final int TILE = 2048;

    private BitmapRegionDecoder dec;
    private int imgW = 0, imgH = 0;
    private Bitmap preview;              // 整图缩略（先用它铺满，拖动手感靠它）

    private Bitmap[] tiles = new Bitmap[0];     // 高清分块
    private Rect[] tilesSrc = new Rect[0];      // 每块对应的源区域
    private int tilesSample = 1;
    private final Rect tilesWant = new Rect();  // 本次解码覆盖的源区域
    private boolean tileHidden;                 // 缩放瞬间先藏高清块，避免与底图尺寸不匹配

    private final Rect tmpSrc = new Rect();
    private final Rect tmpDst = new Rect();
    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);

    private float zoom = 1f;       // 1f = 按原比例适配屏幕
    private float tx = 0f, ty = 0f;
    private int frameW = 0;        // 有效显示区宽度（保留，0 = 整屏）
    private boolean cover = true; // 横图铺满整屏；竖图 false → 完整显示

    private final Handler ui = new Handler(Looper.getMainLooper());
    private Runnable decodeJob;

    public PhotoView(Context c) {
        super(c);
        setWillNotDraw(false);
        paint.setAntiAlias(true);
        paint.setFilterBitmap(true);
        paint.setDither(true);
    }

    /** 设置原图文件（区域解码数据源）；替换数据源时保留当前缩放与位置 */
    public void setSource(String path) {
        float oz = zoom, otx = tx, oty = ty;
        release(false);
        try {
            dec = BitmapRegionDecoder.newInstance(path, false);
            imgW = dec.getWidth();
            imgH = dec.getHeight();
        } catch (Throwable e) {
            dec = null;
            imgW = 0;
            imgH = 0;
        }
        if (imgW > 0) {
            zoom = Math.max(1f, Math.min(24f, oz));
            tx = otx;
            ty = oty;
            clampPan();
        }
        scheduleDecode(120);
        invalidate();
    }

    public boolean hasSource() { return dec != null && imgW > 0 && imgH > 0; }

    /** 整图缩略预览（首屏立即有画面） */
    public void setPreview(Bitmap b) {
        preview = b;
        if (imgW <= 0 && b != null) { imgW = b.getWidth(); imgH = b.getHeight(); }
        invalidate();
    }

    public Bitmap getPreview() { return preview; }

    public int[] sourceSize() { return new int[]{imgW, imgH}; }

    public float zoom() { return zoom; }

    /** 当前绝对显示比例（屏幕像素 / 源像素） */
    public float scaleNow() { return scale(); }

    /** 有效显示区宽度（0 = 整屏）：竖图 1x 时传 9:16 宽度，放大后传 0 放开铺满 */
    public void setFrameWidth(int w) {
        int nw = Math.max(0, w);
        if (nw == frameW) return;
        float sOld = scaleNow();
        frameW = nw;
        float f = fitScale();
        if (f > 0f && sOld > 0f) {
            // 保留原来的显示比例，别因为放开竖框而突然变大
            zoom = Math.max(1f, Math.min(12f, sOld / f));
        }
        clampPan();
        invalidate();
        scheduleDecode(60);
    }

    private int vw() { if (frameW > 0 && frameW < getWidth()) return frameW; return getWidth(); }

    /** 设置倍数（1 = 填满容器），以屏幕中心为基准 */
    public void setZoom(float z) {
        float nz = Math.max(1f, Math.min(24f, z));
        if (Math.abs(nz - zoom) < 0.0001f) return;
        float oldS = scale();
        zoom = nz;
        float k = scale() / oldS;
        // tx/ty 是屏幕像素偏移，必须按缩放比例同步换算，
        // 这样「屏幕正中对应的那块画面」在缩放前后不动，才是真正的居中放大
        tx *= k;
        ty *= k;
        // 缩放过程中不藏高清块：让已有分块跟着实时缩放绘制，
        // 心里那块始终是原图细节，边缘缺的部分才是底图，停手 80ms 就补齐
        clampPan();
        invalidate();
        scheduleDecode(80);
    }

    public void panBy(float dx, float dy) {
        tx += dx;
        ty += dy;
        clampPan();
        invalidate();
        scheduleDecode(120);
    }

    public void reset() {
        zoom = 1f;
        tx = 0f;
        ty = 0f;
        invalidate();
        scheduleDecode(80);
    }

    /** 换图前清理：丢掉上一张的高清块、解码器和尺寸，避免上一张残留在画面里 */
    public void clearAll() {
        if (decodeJob != null && ui != null) ui.removeCallbacks(decodeJob);
        decodeJob = null;
        if (tiles != null) {
            for (Bitmap b : tiles) if (b != null && !b.isRecycled()) b.recycle();
        }
        tiles = new Bitmap[0];
        tilesSrc = new Rect[0];
        tilesWant.setEmpty();
        if (dec != null) {
            try { dec.recycle(); } catch (Throwable ignored) { }
            dec = null;
        }
        imgW = 0;
        imgH = 0;
        zoom = 1f;
        tx = 0f;
        ty = 0f;
        invalidate();
    }

    /** fit 缩放比例（屏幕像素 / 源像素）：竖图按原比例完整显示，横图铺满整屏 */
    private float fitScale() {
        if (imgW <= 0 || imgH <= 0) return 1f;
        int w = vw(), h = getHeight();
        if (w <= 0 || h <= 0) return 1f;
        float fx = w / (float) imgW;
        float fy = h / (float) imgH;
        // cover=true：横图铺满（裁掉多余上下）；cover=false：竖图整张可见，两侧黑边来自照片本身比例
        return cover ? Math.max(fx, fy) : Math.min(fx, fy);
    }

    /** 横图铺满整屏 / 竖图按原比例完整显示 */
    public void setCover(boolean b) {
        if (cover == b) return;
        cover = b;
        clampPan();
        invalidate();
        scheduleDecode(60);
    }

    private float scale() { return fitScale() * zoom; }

    /** 源像素坐标 → 屏幕坐标 */
    private float toScreenX(float px) { return getWidth() / 2f + (px - imgW / 2f) * scale() + tx; }
    private float toScreenY(float py) { return getHeight() / 2f + (py - imgH / 2f) * scale() + ty; }

    private void clampPan() {
        float s = scale();
        float overX = Math.max(0f, (imgW * s - getWidth()) / 2f);
        float overY = Math.max(0f, (imgH * s - getHeight()) / 2f);
        // 1x 铺满后没有余量时也允许挪动半屏，方便看边角
        float padX = overX > 0 ? overX : getWidth() * 0.5f;
        float padY = overY > 0 ? overY : getHeight() * 0.5f;
        if (tx > padX) tx = padX;
        if (tx < -padX) tx = -padX;
        if (ty > padY) ty = padY;
        if (ty < -padY) ty = -padY;
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (imgW <= 0 || imgH <= 0) return;
        float s = scale();

        // 1) 缩略图铺底（整图，跟手）
        if (preview != null) {
            tmpDst.set((int) Math.floor(toScreenX(0f)), (int) Math.floor(toScreenY(0f)),
                    (int) Math.ceil(toScreenX((float) imgW)), (int) Math.ceil(toScreenY((float) imgH)));
            canvas.drawBitmap(preview, null, tmpDst, paint);
        }

        // 2) 高清分块盖上去；位置每帧按当前 scale/tx/ty 实时算，缩放平移都不会错位
        if (!tileHidden && tiles != null && tiles.length > 0) {
            for (int i = 0; i < tiles.length; i++) {
                Bitmap b = tiles[i];
                Rect srcR = i < tilesSrc.length ? tilesSrc[i] : null;
                if (b == null || b.isRecycled() || srcR == null) continue;
                float fx = getWidth() / 2f + (srcR.left - imgW / 2f) * s + tx;
                float fy = getHeight() / 2f + (srcR.top - imgH / 2f) * s + ty;
                float fe = getWidth() / 2f + (srcR.right - imgW / 2f) * s + tx;
                float fb = getHeight() / 2f + (srcR.bottom - imgH / 2f) * s + ty;
                tmpDst.set((int) Math.floor(fx), (int) Math.floor(fy),
                        (int) Math.ceil(fe), (int) Math.ceil(fb));
                tmpSrc.set(0, 0, b.getWidth(), b.getHeight());
                canvas.drawBitmap(b, tmpSrc, tmpDst, paint);
            }
        }
    }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        clampPan();
        scheduleDecode(60);
    }

    private void scheduleDecode(long delay) {
        if (ui == null) return;
        if (decodeJob != null) ui.removeCallbacks(decodeJob);
        decodeJob = new Runnable() {
            @Override public void run() { decodeVisible(); }
        };
        ui.postDelayed(decodeJob, delay);
    }

    /** 解码当前可见区域（原图精度），按 2048 切成多块，解码放后台线程 */
    private void decodeVisible() {
        if (dec == null || imgW <= 0 || getWidth() <= 0) return;
        float s = scale();
        int vw = getWidth(), vh = getHeight();

        // 屏幕可视区反算到源像素
        int left = (int) ((0 - getWidth() / 2f - tx) / s + imgW / 2f);
        int top = (int) ((0 - getHeight() / 2f - ty) / s + imgH / 2f);
        int right = (int) ((vw - getWidth() / 2f - tx) / s + imgW / 2f);
        int bottom = (int) ((vh - getHeight() / 2f - ty) / s + imgH / 2f);

        // 留一点余量，平移时不用频繁重解
        int padX = (int) ((right - left) * 0.1f) + 8;
        int padY = (int) ((bottom - top) * 0.1f) + 8;
        left -= padX; right += padX; top -= padY; bottom += padY;

        if (left < 0) left = 0;
        if (top < 0) top = 0;
        if (right > imgW) right = imgW;
        if (bottom > imgH) bottom = imgH;
        if (right - left < 2 || bottom - top < 2) return;

        // 采样：只要「解码后的像素 ≥ 屏幕实际显示像素」即可；屏幕最多 1920 宽，超出无意义
        float shown = Math.max(imgW * s, imgH * s);
        int needPx = Math.max(1, Math.min(Math.max(vw, vh), (int) shown));
        int longSide = Math.max(right - left, bottom - top);
        int ss = 1;
        while (longSide / (ss * 2f) >= needPx) ss *= 2;

        // 分块（每块解码后不超过 TILE，保证不超纹理上限 → 不花屏）
        final int step = TILE * ss;
        int cols = (right - left + step - 1) / step;
        int rows = (bottom - top + step - 1) / step;
        if (cols < 1) cols = 1;
        if (rows < 1) rows = 1;
        // 块数过多说明区域太大，降一档采样，控制内存和解码时间
        while (cols * rows > 9) {
            ss *= 2;
            int st2 = TILE * ss;
            cols = (right - left + st2 - 1) / st2;
            rows = (bottom - top + st2 - 1) / st2;
        }

        final Rect want = new Rect(left, top, right, bottom);
        // 区域和采样都没变就直接用现有的
        if (tiles.length > 0 && ss == tilesSample
                && Math.abs(want.left - tilesWant.left) < 40 && Math.abs(want.top - tilesWant.top) < 40
                && Math.abs(want.right - tilesWant.right) < 40 && Math.abs(want.bottom - tilesWant.bottom) < 40) {
            return;
        }

        final int fss = ss;
        final int fstep = TILE * ss;
        final int fcols = cols, frows = rows;
        final BitmapRegionDecoder d = dec;
        DECODER.execute(new Runnable() {
            @Override public void run() {
                Bitmap[] out = new Bitmap[fcols * frows];
                Rect[] rcs = new Rect[out.length];
                int idx = 0;
                long bytes = 0;
                boolean ok = true;
                for (int r = 0; r < frows && ok; r++) {
                    for (int c = 0; c < fcols && ok; c++) {
                        int cl = want.left + c * fstep;
                        int ct = want.top + r * fstep;
                        int cr = Math.min(want.right, cl + fstep);
                        int cb = Math.min(want.bottom, ct + fstep);
                        if (cr - cl < 1 || cb - ct < 1) continue;
                        Rect cell = new Rect(cl, ct, cr, cb);
                        try {
                            android.graphics.BitmapFactory.Options o =
                                    new android.graphics.BitmapFactory.Options();
                            o.inSampleSize = fss;
                            o.inPreferredConfig = Bitmap.Config.ARGB_8888;
                            Bitmap b = d.decodeRegion(cell, o);
                            if (b == null) { ok = false; break; }
                            bytes += b.getByteCount();
                            if (bytes > 48L * 1024 * 1024) { b.recycle(); ok = false; break; }
                            out[idx] = b;
                            rcs[idx] = cell;
                            idx++;
                        } catch (Throwable e) {
                            ok = false;
                        }
                    }
                }
                if (!ok) {
                    for (Bitmap b : out) if (b != null && !b.isRecycled()) b.recycle();
                    return;
                }
                final Bitmap[] fo = out;
                final Rect[] fr = rcs;
                final int count = idx;
                android.util.Log.e("LMBY", "区域解码 源=" + imgW + "x" + imgH
                        + L.t(" 区域=") + want.width() + "x" + want.height()
                        + " sample=" + fss + L.t(" 块=") + count
                        + L.t(" 首个=") + (count > 0 ? fo[0].getWidth() + "x" + fo[0].getHeight() : "-")
                        + " zoom=" + zoom);
                ui.post(new Runnable() {
                    @Override public void run() {
                        if (dec == null) {
                            for (int i = 0; i < count; i++) {
                                if (fo[i] != null && !fo[i].isRecycled()) fo[i].recycle();
                            }
                            return;
                        }
                        Bitmap[] olds = tiles;
                        tiles = new Bitmap[count];
                        tilesSrc = new Rect[count];
                        for (int i = 0; i < count; i++) {
                            tiles[i] = fo[i];
                            tilesSrc[i] = fr[i];
                        }
                        tilesSample = fss;
                        tilesWant.set(want);
                        tileHidden = false;
                        if (olds != null) {
                            for (Bitmap b : olds) if (b != null && !b.isRecycled()) b.recycle();
                        }
                        invalidate();
                    }
                });
            }
        });
    }

    private static final java.util.concurrent.ExecutorService DECODER =
            java.util.concurrent.Executors.newSingleThreadExecutor();

    public void release() { release(true); }

    private void release(boolean clearPreview) {
        if (decodeJob != null && ui != null) ui.removeCallbacks(decodeJob);
        decodeJob = null;
        if (tiles != null) {
            for (Bitmap b : tiles) if (b != null && !b.isRecycled()) b.recycle();
        }
        tiles = new Bitmap[0];
        tilesSrc = new Rect[0];
        tilesWant.setEmpty();
        if (dec != null) {
            try { dec.recycle(); } catch (Throwable ignored) { }
            dec = null;
        }
        if (clearPreview) preview = null;
        imgW = 0; imgH = 0;
        zoom = 1f; tx = 0f; ty = 0f;
        tileHidden = false;
    }
}
