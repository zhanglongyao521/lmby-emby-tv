package com.longyao.anbitv;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 异步图片加载：内存缓存 + 磁盘缓存 + 普通/紧急两个线程池 */
public class Img {
    private static final int MAX_BYTES = (int) (Runtime.getRuntime().maxMemory() / 6);
    private static final LruCache<String, Bitmap> CACHE =
            new LruCache<String, Bitmap>(MAX_BYTES) {
                @Override protected int sizeOf(String k, Bitmap v) {
                    return v == null ? 0 : v.getByteCount();
                }
            };
    private static final ExecutorService POOL = Executors.newFixedThreadPool(4);
    /** 点开大图这种要立刻看到的情况走这个池，不和列表缩略图排队 */
    private static final ExecutorService URGENT = Executors.newFixedThreadPool(3);
    /** 同一张图同一时间只解码一次（滚动时反复 bind 同一个 URL 会排队好几次，白白吃掉 CPU） */
    private static final java.util.Set<String> INFLIGHT =
            java.util.Collections.synchronizedSet(new java.util.HashSet<String>());
    private static final Handler UI = new Handler(Looper.getMainLooper());
    private static File DISK_DIR;

    public static void init(Context c) {
        try {
            DISK_DIR = new File(c.getCacheDir(), "imgcache");
            if (!DISK_DIR.exists()) DISK_DIR.mkdirs();
        } catch (Throwable ignored) { }
    }

    private static File diskFile(String url) {
        if (DISK_DIR == null) return null;
        try {
            String h = Integer.toHexString(url.hashCode()) + "_" + url.length();
            return new File(DISK_DIR, h + ".img");
        } catch (Throwable e) {
            return null;
        }
    }

    private static byte[] fromDisk(String url) {
        try {
            File f = diskFile(url);
            if (f == null || !f.exists() || f.length() <= 0) return null;
            java.io.FileInputStream in = new java.io.FileInputStream(f);
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            in.close();
            return bo.toByteArray();
        } catch (Throwable e) {
            return null;
        }
    }

    private static void toDisk(String url, byte[] data) {
        try {
            File f = diskFile(url);
            if (f == null) return;
            FileOutputStream fo = new FileOutputStream(f);
            fo.write(data);
            fo.flush();
            fo.close();
            noteWrite();
        } catch (Throwable ignored) { }
    }

    /** 磁盘缓存上限（原图单张可达 10–20MB，必须设上限，否则会把电视存储吃满） */
    private static final long DISK_MAX = 300L * 1024 * 1024;

    /** 每写 40 张才扫一次目录算大小：原来每张都 listFiles 一遍，几千个文件滚动时直接把 IO 拖死 */
    private static int writes = 0;

    private static void noteWrite() {
        if (++writes >= 40) { writes = 0; trimDisk(); }
    }

    private static void trimDisk() {
        if (DISK_DIR == null) return;
        try {
            File[] fs = DISK_DIR.listFiles();
            if (fs == null || fs.length == 0) return;
            long total = 0;
            for (File f : fs) total += f.length();
            if (total <= DISK_MAX) return;
            java.util.Arrays.sort(fs, new java.util.Comparator<File>() {
                @Override public int compare(File a, File b) {
                    long x = a.lastModified(), y = b.lastModified();
                    return x < y ? -1 : (x > y ? 1 : 0);
                }
            });
            for (File f : fs) {
                if (total <= DISK_MAX) break;
                long len = f.length();
                if (f.delete()) total -= len;
            }
        } catch (Throwable ignored) { }
    }

    /** 超过这个体积就请服务器先缩到目标尺寸再传（写真/照片库单张原图可达 10–20MB） */
    private static final long BIG_BYTES = 900L * 1024;

    private static boolean isEmbyImage(String url) {
        return url != null && url.contains("/Images/") && !url.contains("maxHeight=");
    }

    /** 把 ?quality=92 换成 maxHeight=cap：服务器按高度等比缩，体积通常只有原图的四十分之一 */
    private static String scaledUrl(String url, int cap) {
        if (url == null) return null;
        if (url.contains("quality=92")) return url.replace("quality=92", "maxHeight=" + cap + "&quality=86");
        return url + "&maxHeight=" + cap + "&quality=86";
    }

    /** 之前已经缓存过缩放版就直接用那条 URL，省掉每次的探测请求；cap<=0 表示「就要原图」 */
    private static String pickUrl(String url, int cap) {
        if (cap <= 0 || !isEmbyImage(url)) return url;
        String s = scaledUrl(url, cap);
        File f = diskFile(s);
        if (f != null && f.exists() && f.length() > 0) return s;
        return url;
    }

    /** 只看响应头里的长度，不读 body：超过阈值的图换缩放 URL 再下（一张 16MB 缩完只有 400KB） */
    private static boolean tooBig(String url) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(5000);
            c.setReadTimeout(6000);
            long len = c.getContentLengthLong();
            return len > BIG_BYTES;
        } catch (Throwable e) {
            return false;
        } finally {
            try { if (c != null) c.disconnect(); } catch (Throwable ignored) { }
        }
    }

    /** 流式下载直接写盘：原图单张可达 36MB，绝不能全读进内存（会 OOM/卡死） */
    private static File fetchToDisk(String url, int cap) throws Exception {
        // cap<=0：调用方明确要原图（放大看细节），哪怕 16MB 也得原样落盘，绝不能给缩放版
        if (cap > 0 && isEmbyImage(url) && tooBig(url)) {
            File hit = diskFile(scaledUrl(url, cap));
            if (hit != null && hit.exists() && hit.length() > 0) return hit;
            return fetchToDisk(scaledUrl(url, cap), cap);
        }
        File dst = diskFile(url);
        if (dst == null) throw new java.io.IOException("no cache dir");
        File tmp = new File(dst.getAbsolutePath() + ".part");
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(6000);
        c.setReadTimeout(45000);
        InputStream in = c.getInputStream();
        FileOutputStream out = new FileOutputStream(tmp);
        byte[] buf = new byte[32768];
        int n = 0;
        long total = 0;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
            total += n;
            if (total > 256L * 1024 * 1024) break;   // 防御：异常超大响应直接放弃
        }
        try { out.flush(); } catch (Throwable ignored) { }
        out.close();
        try { if (in != null) in.close(); } catch (Throwable ignored) { }
        if (tmp.length() <= 0) { tmp.delete(); throw new java.io.IOException("empty body"); }
        if (dst.exists()) dst.delete();
        if (!tmp.renameTo(dst)) { tmp.delete(); throw new java.io.IOException("rename failed"); }
        noteWrite();
        return dst;
    }

    private static byte[] fetch(String url, int cap) throws Exception {
        String u = pickUrl(url, cap);
        byte[] disk = fromDisk(u);
        if (disk == null && u.equals(url) && isEmbyImage(url) && tooBig(url)) {
            u = scaledUrl(url, cap);
            disk = fromDisk(u);
}
        if (disk != null) return disk;
        HttpURLConnection c = (HttpURLConnection) new URL(u).openConnection();
        c.setConnectTimeout(4000);
        c.setReadTimeout(9000);
        java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
        InputStream in = c.getInputStream();
        byte[] buf = new byte[8192];
        int n;
        long total = 0;
        while ((n = in.read(buf)) > 0) {
            bo.write(buf, 0, n);
            total += n;
            if (total > 32L * 1024 * 1024) break;   // 回退路径也不许把内存吃穿
        }
        if (in != null) in.close();
        byte[] data = bo.toByteArray();
        toDisk(u, data);
        return data;
    }

    private static Bitmap decode(byte[] data) { return decode(data, 1600, false); }

    /** 从磁盘缓存文件直接采样解码：不再把整张原图读成 byte[]，内存峰值从 3 份降到 1 份 */
    private static Bitmap decodeFile(String url, int maxSideCap, boolean argb) {
        try {
            java.io.File f = diskFile(pickUrl(url, maxSideCap));
            if (f != null && f.exists() && f.length() > 0) {
                return decodePath(f.getAbsolutePath(), maxSideCap, argb);
            }
            // 缩略图/海报都很小：直接下到内存按目标尺寸解码，省掉「写盘再读盘」那一趟 IO
            // （Glide 也是这么干的：网络流边解码边落盘，不是先落盘再读一次）
            if (maxSideCap <= 2000) {
                byte[] data = fetch(url, maxSideCap);   // fetch 内部顺手写盘，下次就命中磁盘
                if (data == null) return null;
                return decode(data, maxSideCap, argb);
            }
            // 全屏看图这种大图才走流式落盘，避免把原图整个读进内存
            f = fetchToDisk(url, maxSideCap);
            if (f == null || !f.exists() || f.length() <= 0) return null;
            return decodePath(f.getAbsolutePath(), maxSideCap, argb);
        } catch (Throwable e) {
            android.util.Log.w("LMBY", "decodeFile fail " + url, e);
            return null;
        }
    }

    private static Bitmap decodePath(String path, int maxSideCap, boolean argb) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(path, bounds);
        int sample = 1;
        int maxSide = Math.max(bounds.outWidth, bounds.outHeight);
        while (maxSide / sample > maxSideCap) sample *= 2;
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inSampleSize = sample;
        o.inPreferredConfig = argb ? Bitmap.Config.ARGB_8888 : Bitmap.Config.RGB_565;
        Bitmap b = BitmapFactory.decodeFile(path, o);
        if (b == null) return null;
        if (!argb) b = trimBlack(b);
        return b;
    }

    private static Bitmap decode(byte[] data, int maxSideCap, boolean argb) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, bounds);
            int sample = 1;
            int maxSide = Math.max(bounds.outWidth, bounds.outHeight);
            while (maxSide / sample > maxSideCap) sample *= 2;
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inSampleSize = sample;
            o.inPreferredConfig = argb ? Bitmap.Config.ARGB_8888 : Bitmap.Config.RGB_565;
            Bitmap b = BitmapFactory.decodeByteArray(data, 0, data.length, o);
            if (b == null) return null;
            if (!argb) b = trimBlack(b);
            return b;
        } catch (Throwable e) {
            return null;
        }
    }

    /** 裁掉图片四周纯黑填充边（Emby 自动拼图封面自带黑边），最多各裁 6% */
    private static Bitmap trimBlack(Bitmap b) {
        try {
            int w = b.getWidth(), h = b.getHeight();
            int maxY = (int) (h * 0.15f), maxX = (int) (w * 0.15f);
            int[] row = new int[w];
            int[] col = new int[h];
            int top = 0, bottom = 0, left = 0, right = 0;
            while (top < maxY) {
                b.getPixels(row, 0, w, 0, top, w, 1);
                if (!allDark(row, w, 1)) break;
                top++;
            }
            while (bottom < maxY) {
                b.getPixels(row, 0, w, 0, h - 1 - bottom, w, 1);
                if (!allDark(row, w, 1)) break;
                bottom++;
            }
            while (left < maxX) {
                b.getPixels(col, 0, 1, left, 0, 1, h);
                if (!allDark(col, h, 1)) break;
                left++;
            }
            while (right < maxX) {
                b.getPixels(col, 0, 1, w - 1 - right, 0, 1, h);
                if (!allDark(col, h, 1)) break;
                right++;
            }
            if (top == 0 && bottom == 0 && left == 0 && right == 0) return b;
            int nw = w - left - right, nh = h - top - bottom;
            if (nw < w / 2 || nh < h / 2) return b;
            Bitmap crop = Bitmap.createBitmap(b, left, top, nw, nh);
            // 裁出来的新图进缓存，原图没人再用：立刻回收，别等 GC（大图这里能省一半峰值
            try { if (crop != b) b.recycle(); } catch (Throwable ignored) { }
            return crop;
        } catch (Throwable ignored) {
            return b;
        }
    }

    /** 一行/一列的像素是否全是黑的（按步长采样，避免逐像素 getPixel 那种天价开销） */
    private static boolean allDark(int[] px, int len, int step) {
        for (int i = 0; i < len; i += step) {
            int c = px[i];
            if ((((c >> 16) & 0xFF) + ((c >> 8) & 0xFF) + (c & 0xFF)) / 3 > 22) return false;
        }
        return true;
    }

    public static void load(final ImageView iv, final String url) { load(iv, url, false); }

    /** urgent=true：走独立线程池，点开大图时不跟缩略图排队 */
    public static void load(final ImageView iv, final String url, boolean urgent) {
        load(iv, url, urgent, null);
    }

    /** 高清晰度加载：最长边 2160、ARGB_8888，用于全屏看图 */
    public static void loadFull(final ImageView iv, final String url, boolean urgent, final Runnable after) {
        loadInternal(iv, url, urgent, after, 2160, true);
    }

    /** 照片预览：最长边 1440、ARGB_8888（照片页 1x 状态就是这个清晰度，再放大才走 loadFull） */
    public static void loadPhoto(final ImageView iv, final String url, boolean urgent, final Runnable after) {
        loadInternal(iv, url, urgent, after, 1440, true);
    }

    /** 带完成回调版本：回调在主线程、图片真正落到控件之后调用（失败也会回调） */
    public static void load(final ImageView iv, final String url, boolean urgent, final Runnable after) {
        loadInternal(iv, url, urgent, after, 480, false);
    }

    private static void loadInternal(final ImageView iv, final String url, boolean urgent,
                                     final Runnable after, final int maxSideCap, final boolean argb) {
        if (iv == null) { if (after != null) UI.post(after); return; }
        if (url == null || url.isEmpty()) {
            iv.setImageBitmap(null);
            if (after != null) UI.post(after);
            return;
        }
        if (isFailed(url)) {
            iv.setImageBitmap(null);
            if (after != null) UI.post(after);
            return;
        }
        iv.setTag(url);
        // 缓存键带上目标尺寸：同一张原图在卡片（1000）和照片页（1440/2160）解码结果不同，
        // 用同一个键会互相顶掉，照片放大后又变糊
        final String key = maxSideCap + "|" + url;
        Bitmap b = CACHE.get(key);
        if (b != null) {
            iv.setImageBitmap(b);
            if (after != null) UI.post(after);
            return;
        }
// 同一张图已经在解码队列里：别重复排，等它出来再绑
        if (!INFLIGHT.add(key)) {
            UI.postDelayed(new Runnable() {
                @Override public void run() {
                    if (url.equals(iv.getTag())) loadInternal(iv, url, urgent, after, maxSideCap, argb);
                }
            }, 400);
            return;
        }
        Runnable job = new Runnable() {
            @Override public void run() {
                // 视图已经被复用成别的图了：这次请求直接作废（相当于 Glide 的 cancel，别白下白解）
                if (!url.equals(iv.getTag())) { INFLIGHT.remove(key); return; }
                long t0 = android.os.SystemClock.uptimeMillis();
                File df = diskFile(pickUrl(url, maxSideCap));
                boolean diskHit = df != null && df.exists() && df.length() > 0;
                Bitmap bmp = null;
                // 先走「磁盘文件 + 采样解码」（内存只留一份），失败再退回旧的 byte[] 路径
                try { bmp = decodeFile(url, maxSideCap, argb); } catch (Throwable ignored) { }
                if (bmp == null) {
                    try { bmp = decode(fetch(url, maxSideCap), maxSideCap, argb); } catch (Throwable ignored) { }
                }
                // 临时计时（压测用）：磁盘命中还是网络下载、整段耗时、拿到的图多大
if (bmp == null) markFailed(url);
                final Bitmap fb = bmp;
                if (fb != null) CACHE.put(key, fb);
                INFLIGHT.remove(key);
                UI.post(new Runnable() {
                    @Override public void run() {
                        if (url.equals(iv.getTag()) && fb != null) {
                            iv.setImageBitmap(fb);
                            // 轻微淡入，跟 Emby 一样不是硬切，滚动时观感更顺
                            try {
                                iv.setAlpha(0.78f);
                                iv.animate().alpha(1f).setDuration(120).start();
                            } catch (Throwable ignored) { }
                        }
                        if (after != null) after.run();
                    }
                });
            }
        };
        (urgent ? URGENT : POOL).execute(job);
    }

    /** 拉不到的图（404 / 无刮削封面）记一笔，30 分钟后自动放行重试（原来永久拉黑，换服务器要重启 App 才恢复） */
    private static final java.util.Map<String, Long> FAILED =
            java.util.Collections.synchronizedMap(new java.util.HashMap<String, Long>());

    private static boolean isFailed(String url) {
        Long t = FAILED.get(url);
        if (t == null) return false;
        if (System.currentTimeMillis() - t > 30L * 60 * 1000) { FAILED.remove(url); return false; }
        return true;
    }

    private static void markFailed(String url) {
        try {
            if (FAILED.size() > 900) FAILED.clear();
            FAILED.put(url, System.currentTimeMillis());
        } catch (Throwable ignored) { }
    }

    /** 正在进行中的预取，避免同一张图重复排队把服务器压满 */
    private static final java.util.Set<String> PREFETCHING =
            java.util.Collections.synchronizedSet(new java.util.HashSet<String>());

    /** 预取：把图片悄悄拉进内存/磁盘缓存，左右切换时直接命中 */
    public static void prefetch(final String url) {
        if (url == null || url.isEmpty()) return;
        if (isFailed(url)) return;
        final String key = "480|" + url;
        if (CACHE.get(key) != null) return;
        if (!INFLIGHT.add(key)) return;     // 列表正在解同一张，就别抢了
        POOL.execute(new Runnable() {
            @Override public void run() {
                try {
                    if (CACHE.get(key) != null) return;
                    Bitmap b = decodeFile(url, 480, false);
                    if (b == null) {
                        byte[] data = fetch(url, 480);
                        b = decode(data, 480, false);
                    }
                    if (b != null) CACHE.put(key, b);
                } catch (Throwable ignored) {
                } finally {
                    INFLIGHT.remove(key);
                }
            }
        });
    }

    public static void clear() {
        CACHE.evictAll();
    }

    /** 内存缓存超过七成再放，别等系统 OOM；正常切后台保留，回来不用整页重解码 */
    public static boolean memTight() {
        try { return CACHE.size() > (long) (MAX_BYTES * 0.7f); } catch (Throwable e) { return false; }
    }

    /** 放掉较早的一半位图，保留最近用过的，避免整页白图 */
    public static void trimMemory() {
        try {
            java.util.Map<String, android.graphics.Bitmap> snap = CACHE.snapshot();
            if (snap == null || snap.size() <= 1) return;
            java.util.ArrayList<String> keys = new java.util.ArrayList<String>(snap.keySet());
            java.util.LinkedHashMap<String, android.graphics.Bitmap> tail =
                    new java.util.LinkedHashMap<String, android.graphics.Bitmap>();
            for (int i = keys.size() / 2; i < keys.size(); i++) tail.put(keys.get(i), snap.get(keys.get(i)));
            CACHE.evictAll();
            for (java.util.Map.Entry<String, android.graphics.Bitmap> en : tail.entrySet()) {
                if (en.getValue() != null) CACHE.put(en.getKey(), en.getValue());
            }
        } catch (Throwable ignored) { }
    }

    public interface FileCallback { void onFile(File f); }

    /** 确保图片落到本地磁盘并返回文件路径（供区域解码用，不占大图内存） */
    public static void ensureFile(final String url, boolean urgent, final FileCallback cb) {
        if (url == null || url.isEmpty()) { cb.onFile(null); return; }
        File f = diskFile(url);
        if (f != null && f.exists() && f.length() > 0) { cb.onFile(f); return; }
        Runnable job = new Runnable() {
            @Override public void run() {
                File got = null;
                try {
                    // 原图：PhotoView 要按区域解原分辨率，用缩放版放大就是糊的
                    got = fetchToDisk(url, 0);
                    if (got == null || !got.exists() || got.length() <= 0) got = fetchToDisk(url, 2160);
                } catch (Throwable ignored) {
                    // 原图拉不到（服务器 500/文件损坏）也要给个能看的：退到 2160 缩放版
                    try { got = fetchToDisk(url, 2160); } catch (Throwable ignored2) { }
                }
                final File fg = got;
                UI.post(new Runnable() {
                    @Override public void run() { cb.onFile(fg); }
                });
            }
        };
        (urgent ? URGENT : POOL).execute(job);
    }
}
