package com.longyao.anbitv;

import android.app.Activity;
import android.content.Context;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.videolan.libvlc.LibVLC;
import org.videolan.libvlc.Media;
import org.videolan.libvlc.MediaPlayer;
import org.videolan.libvlc.interfaces.IVLCVout;

import java.util.ArrayList;

/** 播放器：VLC 内核，界面按当贝播放器——只有一条底部进度条，左右键快进快退 */
public class PlayerActivity extends Activity implements MediaPlayer.EventListener {

    /** 只允许一个播放器实例，避免两层窗口叠着各画一份字幕 */
    /** 当前活着的播放器窗口数（主界面用它区分"进播放器"和"真的切出去了"） */
    public static int alive = 0;
    private static PlayerActivity live = null;

    private SurfaceView surface;
    private LibVLC libVlc;
    private MediaPlayer mp;

    private FrameLayout root;
    /** 播放器界面层（App 内唯一一套） */
    private PlayerUI pui;
    /** 统一菜单 + 自绘字幕层 */
    private VMenu menu;

    // 级联菜单：第一层在最右，往左依次展开第二层、第三层
    private LinearLayout menuHost, col1, col2, col3;
    private android.widget.ScrollView sv1, sv2, sv3;
    private int lookLevel = -1;
    private android.graphics.drawable.Drawable bgSel, bgParent, bgNone;
    private final ArrayList<View> rows1 = new ArrayList<View>();
    private final ArrayList<View> rows2 = new ArrayList<View>();
    private final ArrayList<View> rows3 = new ArrayList<View>();
    private final ArrayList<Integer> acts1 = new ArrayList<Integer>();
    private final ArrayList<Integer> acts2 = new ArrayList<Integer>();
    private final ArrayList<Api.SubStream> subChoices = new ArrayList<Api.SubStream>();
    private int level = 0;                       // 0 = 第一层，1 = 第二层，2 = 第三层
    private final int[] sel = {0, 0, 0};
    private SubDelayBar delayBar;
    private TextView delayValue;
    private int delayRowIdx = -1;

    private Handler ui = new Handler();
    private boolean hudVisible, menuOpen = false, ended = false;
    private long lengthMs = 0, posMs = 0;
    /** 启动时服务器给的片长（老 TS 片源 libVLC 自己算不出来） */
    private long durationHint = 0;
    private long startMs = 0L;
    /** 转码流起点：渐进式转码流无法原地 seek，靠服务器从该点重新起转 */
    private long tcStartMs = 0L;
    private long lastSaveMs = 0L;
    private String playSessionId = "";
    private boolean reportedStart = false;
    private long statT = 0, statB = 0;
    private long seekHoldUntil = 0;
    private boolean dragging = false;
    private boolean resumeDone = false;
    private String itemId, server, token, userId, deviceId;
    /** 当前播的是某一集时，这部剧的 id（按上键弹选集用） */
    private String seriesId = "";

    private int[] speeds = {25, 50, 100, 125, 150, 200};
    private int speedIdx = 2;
    private float targetRate = 1f;
    private long rateGuardUntil = 0;
    private int aspectIdx = 0;
    private String[] aspectNames = {L.t("原画"), L.t("全屏"), "4:3", "16:9"};
    private boolean hwDecode = true;

    // ---- 字幕 ----
    private java.util.List<Api.SubStream> subs = new java.util.ArrayList<Api.SubStream>();
    private String mediaName = "";
    private String subLabel = L.t("自动");     // 当前生效的外挂字幕
    private String localSubLabel = "";    // 当前生效的内嵌字幕
    private int autoSubTries = 0;          // 自动选字幕时等内核报内嵌轨的重试次数
    private volatile boolean autoSubTimerOn = false;   // 重试只保留一条定时链
    /** 内嵌字幕走 VLC 原生 spu 轨，此时不再画自绘字幕层 */
    private boolean embedMode = false;
    private long subDelayUs = 0;
    private String subDbg = "";
    private String subStatus = L.t("空闲");
    private String playMethod = L.t("直接播放");
    private Store pstore;
    private String[] plist;
    private int pidx = 0;
    private String pendingSubPath = null;
    // 自绘字幕：自己解析 SRT 画文字，不再交给 VLC（VLC 会把同一条挂两遍）
    private TextView subView;
    private final java.util.ArrayList<Long> cueStart = new java.util.ArrayList<Long>();
    private final java.util.ArrayList<Long> cueEnd = new java.util.ArrayList<Long>();
    private final java.util.ArrayList<String> cueText = new java.util.ArrayList<String>();
    private String cueShown = "";

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        L.init(this);
        alive++;
        // 单实例守卫：同一时刻只允许一个播放窗口，避免字幕被两个窗口各画一遍
        if (live != null && live != this) {
            try { live.ui.removeCallbacks(live.subTicker); } catch (Exception e) { }
            try { if (live.subView != null) live.subView.setVisibility(View.GONE); } catch (Exception e) { }
            try { live.finish(); } catch (Exception e) { }
        }
        live = this;
        android.util.Log.e("LMBY", "player created inst=" + Integer.toHexString(System.identityHashCode(this)));
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (new Store(this).secure()) getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        itemId = getIntent().getStringExtra("itemId");
        startMs = getIntent().getLongExtra("startMs", 0L);
        durationHint = getIntent().getLongExtra("durMs", 0L);
        server = getIntent().getStringExtra("server");
        token = getIntent().getStringExtra("token");
        userId = getIntent().getStringExtra("userId");
        deviceId = getIntent().getStringExtra("deviceId");
        String sid0 = getIntent().getStringExtra("seriesId");
        seriesId = sid0 == null ? "" : sid0;

        pstore = new Store(this);
        aspectIdx = pstore.aspect();   // 画面比例沿用上次的选择，两个内核共用
        String pl = getIntent().getStringExtra("plist");
        if (pl != null && !pl.isEmpty()) plist = pl.split("\n");
        int pi = getIntent().getIntExtra("pidx", 0);
        pidx = pi < 0 ? 0 : pi;

        String m0 = getIntent().getStringExtra("method");
        if (m0 != null && !m0.isEmpty()) playMethod = m0;

        root = new FrameLayout(this);
        ui.post(smoothTick);
        root.setBackgroundColor(0xFF000000);
        setContentView(root);

        surface = new SurfaceView(this);
        root.addView(surface, new FrameLayout.LayoutParams(-1, -1));
        surface.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override public void surfaceCreated(SurfaceHolder h) { attach(); playMedia(); }
            @Override public void surfaceChanged(SurfaceHolder h, int f, int w, int ht) {
                if (libVlc != null && mp != null) mp.getVLCVout().setWindowSize(w, ht);
            }
            @Override public void surfaceDestroyed(SurfaceHolder h) { }
        });

        buildHud();
        // 级联菜单 + 自绘字幕层统一交给 VMenu
        menu = new VMenu(this, new VlcHost());
        menu.attach(root);
        startVlc();
        showHud();
        // 直连的 MPEG-TS 老片子 libVLC 拿不到总时长（进度条右边显示 ---:--），
        // 先用主界面带进来的片长补上，再让服务器兜一次
        if (durationHint > 0) { lengthMs = durationHint; refreshBar(); refreshTime(); }
        android.util.Log.e("LMBY", "durHint=" + durationHint + " lengthMs=" + lengthMs + " itemId=" + itemId);
        lengthMsFallback();
    }

    // ---------- VLC ----------

    private void startVlc() {
        ArrayList<String> opts = new ArrayList<String>();
        opts.add("--no-drop-late-frames");
        opts.add("--no-skip-frames");
        opts.add("--avcodec-skiploopfilter=0");
        opts.add("--verbose=2");
        libVlc = new LibVLC(this, opts);
        mp = new MediaPlayer(libVlc);
        mp.setEventListener(this);
    }

    private void attach() {
        if (mp == null || surface == null) return;
        IVLCVout vout = mp.getVLCVout();
        vout.setVideoView(surface);
        vout.setWindowSize(surface.getWidth(), surface.getHeight());
        vout.attachViews();
    }

    private boolean started = false;
    // 方向键：点按 10 秒，按住逐级加速（10s → 30s → 1min → 2min → 5min）
    private long seekTarget = 0;
    private int seekDir = 0;
    private long seekPressAt = 0;
    private long seekBase = 0;

    /** 按住时长换算累计前进距离：1.4s 内 10 倍速，之后逐档加速到 160 倍；按时间积分，刷新多少次都不影响总距离 */
    private long holdDistanceMs(long heldMs) {
        long[] segMs = {1400, 1400, 1800, 2400, Long.MAX_VALUE / 4};
        int[] mult = {10, 20, 40, 80, 160};
        long d = 0, t = 0;
        for (int i = 0; i < mult.length && t < heldMs; i++) {
            long seg = Math.min(segMs[i], heldMs - t);
            d += (long) (seg * mult[i] / 1000.0);
            t += seg;
        }
        return d;
    }
    private long seekLastApplyAt = 0;

    /** 按当前设置算播放地址：直连 or 服务器转码 */
    private String currentUrl() {
        String fallback = getIntent().getStringExtra("url");
        if (pstore == null || itemId == null || itemId.isEmpty()) return fallback;
        if (pstore.forceTranscode())
            return Api.transcodeUrlFrom(session(), itemId, pstore.maxHeightPx(), pstore.maxKbps(), tcStartMs);
        return fallback != null && !fallback.isEmpty() ? fallback : Api.streamUrl(session(), itemId);
    }

    /** 当前是否走服务器转码 */
    private boolean isTranscoding() {
        // 按实际播放地址判断：static=true 是直连，只有 stream.mp4 才是转码流
        String c = currentUrl();
        if (c == null || itemId == null || itemId.isEmpty()) return false;
        return !c.contains("static=true");
    }

    /** 绝对播放位置：转码流的 getTime 是流内时间，要加上本次流的起点 */
    private long getPosMs() {
        return tcStartMs + (mp == null ? 0 : Math.max(0, mp.getTime()));
    }

    /** 转码流 seek：释放当前媒体，用 StartTimeTicks 从目标点重新起转 */
    private void retranscodeFrom(long t) {
        android.util.Log.e("LMBY", "VLC 转码流 seek → 从 " + t + "ms 重新起转");
        tcStartMs = Math.max(0, t);
        posMs = tcStartMs;
        seekTarget = tcStartMs;
        lengthMsFallback();
        restartMediaKeepPosition(false);
    }

    /** 转码流没有总时长，用服务器侧片长补进去，进度条和快进边界才正确 */
    private boolean lengthAsked = false;

    private void lengthMsFallback() {
        if (lengthMs > 0 || lengthAsked || itemId == null || itemId.isEmpty()) return;
        lengthAsked = true;
        final String iid = itemId;
        new Thread(new Runnable() { public void run() {
            final long r = Api.runtimeMs(session(), iid);
            android.util.Log.e("LMBY", "runtimeMs(" + iid + ")=" + r);
            if (r > 0) ui.post(new Runnable() { public void run() {
                if (lengthMs <= 0) { lengthMs = r; refreshBar(); refreshTime(); }
            }});
        }}).start();
    }

    /**
     * 快进/后退的目标位置一律过这里：
     * 有总时长就压在「时长 - 1 秒」以内；拿不到时长（老 TS 片源）时，
     * 也限制单次按住最多越过起点 10 分钟，避免时间冲成 3:47:00 这种非法值。
     */
    private long clampSeek(long t) {
        long lim = lengthMs;
        if (lim <= 0 && mp != null) {
            try { lim = mp.getLength(); } catch (Throwable ignored) { }
            if (lim > 0) lengthMs = lim;
        }
        if (lim <= 0) lengthMsFallback();
        if (t < 0) t = 0;
        if (lim > 0) {
            if (t > lim - 1000) t = lim - 1000;
        } else {
            long cap = seekBase + 600_000L;
            if (t > cap) t = cap;
        }
        return t;
    }

    /** 当前实际播放方式（跟随实际播放地址，与播放统计面板一致） */
    private String methodLabel() {
        String cu = currentUrl();
        return "libVLC · " + ((cu == null || !cu.contains("static=true")) ? "转码" : "直连");
    }

    private String transLabel() {
        if (pstore == null || !pstore.forceTranscode()) return L.t("自动直连");
        return L.t("强制转码 ") + resLabel() + " / " + brLabel();
    }

    private String resLabel() {
        return heightText(pstore == null ? 0 : pstore.maxHeightPx());
    }

    /** 分辨率档显示名：8K / 4K / 2K 用习惯叫法，其余按高度 */
    private static String heightText(int h) {
        if (h <= 0) return L.t("原画");
        if (h >= 4320) return "8K";
        if (h >= 2160) return "4K";
        if (h >= 1440) return "2K";
        return h + "p";
    }

    private String brLabel() {
        int k = pstore == null ? 0 : pstore.maxKbps();
        return kbpsText(k);
    }

    private static String kbpsText(int k) {
        if (k <= 0) return L.t("不限码率");
        if (k % 1000 == 0) return (k / 1000) + " Mbps";
        return (k / 1000f) + " Mbps";
    }

    /** 音画同步：长时间播放不脱慢，画面落后时丢晚帧追上来 */
    private void addSyncOptions(Media m) {
        if (m == null) return;
        m.addOption(":clock-jitter=0");
        m.addOption(":drop-late-frames");
        m.addOption(":skip-frames");
    }

    private void applyDecoder(Media m) {
        int mode = pstore == null ? 0 : pstore.decodeMode();
        if (mode == 1) m.setHWDecoderEnabled(false, false);
        else if (mode == 2) m.setHWDecoderEnabled(true, false);
        else m.setHWDecoderEnabled(true, false);
    }

    private void addSubOptions(Media m) {
        if (true) return; // 字幕改成自绘，不再交给 VLC 渲染
        if (pendingSubPath == null) return;
        m.addOption(":sub-file=" + pendingSubPath);
        int si = pstore == null ? 1 : pstore.subSize();
        m.addOption(":freetype-rel-fontsize=" + SUB_SIZES[si < 0 ? 0 : (si > 2 ? 2 : si)]);
        int ci = pstore == null ? 0 : pstore.subColor();
        m.addOption(":freetype-color=" + COLOR_VALUES[ci < 0 ? 0 : (ci > 3 ? 3 : ci)]);
        m.addOption(":freetype-opacity=255");
        m.addOption(":sub-delay=" + (subDelayUs / 1000L));
    }

    private void playMedia() {
        if (started || mp == null) return;
        started = true;
        String url = currentUrl();
        try {
            Media m = new Media(libVlc, Uri.parse(url));
            applyDecoder(m);
            m.addOption(":network-caching=1500");
            m.addOption(":spu");
            m.addOption(":sub-track=-1");
            addSyncOptions(m);
            m.addOption(":clock-jitter=0");
            addSubOptions(m);
            mp.setMedia(m);
            m.release();
            mp.play();
            settleSub();
            startSubTicker();
            // 调试用：intent 带 autoStats=1 时自动弹出播放统计，便于 adb 验证版式
            if (getIntent().getStringExtra("autoStats") != null) {
                ui.postDelayed(new Runnable() {
                    @Override public void run() { if (menu != null) menu.statsNow(); }
                }, 8000);
            }
            // 调试用：intent 带 autoSpeed=1.5 时自动切倍速并观察实际速率
            String spAdj = getIntent().getStringExtra("autoSpeed");
            if (spAdj != null) {
                final float v = Float.parseFloat(spAdj);
                ui.postDelayed(new Runnable() {
                    @Override public void run() {
                        targetRate = v;
                        rateGuardUntil = android.os.SystemClock.elapsedRealtime() + 4000;
                        if (mp != null) mp.setRate(v);
                        android.util.Log.e("LMBY", "VLC 设置倍速 " + v + " 当前="
                                + (mp == null ? -1 : mp.getRate()));
                    }
                }, 6000);
                ui.postDelayed(new Runnable() {
                    @Override public void run() {
                        android.util.Log.e("LMBY", "VLC 倍速观察 目标=" + v + " 实际="
                                + (mp == null ? -1 : mp.getRate()));
                    }
                }, 8200);
            }
            // 调试用：intent 带 autoSeek=1 时模拟按住后退连跳，观察进度是否来回抖（autoSeek=f 则前进）
            String asVal = getIntent().getStringExtra("autoSeek");
            if (asVal != null) {
                final int sdir = asVal.startsWith("f") ? 1 : -1;
                ui.postDelayed(new Runnable() {
                    @Override public void run() { seekKeyDown(sdir, false); }
                }, 6000);
                for (int i = 1; i <= 4; i++) {
                    final int k = i;
                    ui.postDelayed(new Runnable() {
                        @Override public void run() { seekKeyDown(sdir, true); }
                    }, 6000 + k * 320L);
                }
                ui.postDelayed(new Runnable() {
                    @Override public void run() { seekKeyUp(sdir); }
                }, 7600);
                for (int i = 0; i <= 6; i++) {
                    final int k = i;
                    ui.postDelayed(new Runnable() {
                        @Override public void run() {
                            android.util.Log.e("LMBY", "seek轨迹 t+" + (k * 300) + "ms pos=" + posMs
                                    + " real=" + (mp == null ? -1 : mp.getTime())
                                    + " dir=" + seekDir);
                        }
                    }, 6200 + k * 300L);
                }
            }
            // 调试用：intent 带 autoSub=1 时自动选第一条内嵌 spu 轨，便于 adb 验证
            if (getIntent().getStringExtra("autoSub") != null) {
                ui.postDelayed(new Runnable() {
                    @Override public void run() {
                        MediaPlayer.TrackDescription[] ts = mp == null ? null : mp.getSpuTracks();
                        android.util.Log.e("LMBY", "VLC 内嵌自动测试 轨数=" + (ts == null ? -1 : ts.length)
                                + L.t(" 名称=") + spuNames(ts));
                        if (ts != null && ts.length > 1 && mp != null) {
                            mp.setSpuTrack(ts[1].id);
                            embedMode = true;
                            localSubLabel = spuName(ts[1]);
                            android.util.Log.e("LMBY", "VLC 内嵌自动测试 已选 id=" + ts[1].id
                                    + L.t(" 当前=") + mp.getSpuTrack());
                        }
                    }
                }, 7000);
            }
            if (itemId != null && !itemId.isEmpty()) {
                String psid = playSessionId;
                if (psid == null || psid.isEmpty()) psid = "loyao-" + System.currentTimeMillis();
                playSessionId = psid;
                Api.reportStart(session(), itemId, psid);
            }
            if (menu != null) menu.loadSubtitlesAsync();
            // 后台缓存 HDR 标记，供统一播放统计面板显示
            if (itemId != null && !itemId.isEmpty()) {
                new Thread(new Runnable() {
                    @Override public void run() {
                        try {
                            Api.MediaInfo mi = Api.mediaInfo(session(), itemId);
                            if (mi != null) hdrText = hdrOf(mi.range);
                        } catch (Throwable ignored) { }
                    }
                }).start();
            }
        } catch (Exception e) {
            toast(L.t("播放失败：") + e.getMessage());
        }
    }

    /** 调试用：intent 带 autoSub 时自动选第一条内嵌 spu 轨 */
    private void autoPickSpu() {
        if (getIntent().getStringExtra("autoSub") == null || mp == null) return;
        ui.postDelayed(new Runnable() {
            @Override public void run() {
                MediaPlayer.TrackDescription[] ts = mp == null ? null : mp.getSpuTracks();
                android.util.Log.e("LMBY", "自动内嵌测试 spu 轨数=" + (ts == null ? -1 : ts.length)
                        + L.t(" 名称=") + spuNames(ts));
                if (ts == null) return;
                for (int i = 0; i < ts.length; i++) {
                    if (ts[i] != null && ts[i].id > 0) {
                        mp.setSpuTrack(ts[i].id);
                        embedMode = true;
                        if (subView != null) subView.setVisibility(View.GONE);
                        localSubLabel = spuName(ts[i]);
                        android.util.Log.e("LMBY", "自动内嵌测试 已选 id=" + ts[i].id
                                + " name=" + ts[i].name + L.t(" 当前=") + mp.getSpuTrack());
                        break;
                    }
                }
            }
        }, 3500);
    }

    private Api.Session session() {
        Api.Session s = new Api.Session();
        s.server = server; s.token = token; s.userId = userId; s.deviceId = deviceId;
        return s;
    }

    // ---------- HUD ----------

    /** 界面由 PlayerUI 统一创建（与 ExoPlayer 版同一套皮肤） */
    private void buildHud() {
        pui = new PlayerUI(this, root, str(getIntent().getStringExtra("title")),
                getIntent().getStringExtra("sub"));
    }

    private String str(String s) { return s == null ? "" : s; }

    private android.graphics.drawable.Drawable topGradient() {
        return new android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xB3000000, 0x00000000});
    }

    private android.graphics.drawable.Drawable bottomGradient() {
        return new android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.BOTTOM_TOP,
                new int[]{0xE6000000, 0x4D000000, 0x00000000});
    }

    private void showHud() {
        hudVisible = true;
        pui.show(0);                    // 自动隐藏交给下面的 hider，保持 6 秒节奏
        ui.removeCallbacks(hider);
        ui.postDelayed(hider, 6000);
        refreshTime();
    }

    private final Runnable hider = new Runnable() {
        @Override public void run() {
            if (menuOpen || pui.volumeVisible()) return;
            hideHud();
        }
    };

    private void hideHud() {
        ui.removeCallbacks(hider);
        hudVisible = false;
        pui.hide();
    }

    private void resetToastPos() { }

    /** 播完自动在服务器上标记「已观看」，详情页的已观看按钮据此点亮 */
    /** 把播放位置同步到 Emby 服务器（每约 10 秒一次），不做任何本地存储 */
    private void syncProgressToServer() {
        if (itemId == null || itemId.isEmpty() || posMs <= 0 || server == null) return;
        long now = android.os.SystemClock.elapsedRealtime();
        if (now - lastSaveMs < 10000) return;
        lastSaveMs = now;
        final long t = posMs;
        final String psid = playSessionId;
        new Thread(new Runnable() {
            @Override public void run() {
                try { Api.reportProgress(session(), itemId, t, false, psid); } catch (Throwable ignored) { }
            }
        }).start();
    }

    private void markWatched() {
        final String iid = itemId;
        if (iid == null || iid.isEmpty() || server == null) return;
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    Api.Session s = new Api.Session();
                    s.server = server;
                    s.token = token;
                    s.userId = userId;
                    s.deviceId = deviceId;
                    Api.markPlayed(s, iid, true);
                } catch (Throwable ignored) { }
            }
        }).start();
    }

    private void toast(String s) {
        resetToastPos();
        if (pui != null) pui.toast(s);
    }

    // ---------- 操作 ----------

    private void togglePlay() {
        if (mp == null) return;
        if (mp.isPlaying()) {
            mp.pause();
        } else {
            if (ended) mp.setTime(0, false);
            mp.play();
        }
        showHud();
    }

    /**
     * 按住倍率制：按 110ms 一步，每步前进 = 110ms × 倍率。
     * 1.4s 内 10×，之后 20/40/80/160×，对应「按住 1s≈前进 10s，2s≈26s，3s≈46s」。
     * 以前是每步固定加 10s（≈90×），按住两秒就冲出去两百多秒，直接把进度条顶到片尾。
     */
    /**
     * 按住倍率制：每 90ms 一步，速度 = mult 倍实时（对齐当贝：按住 3 秒 ≈ 快进 17 分钟）。
     * 0.8s 内 50×，之后 180/640/1600/2800×。
     */
    private long holdStepMs(long heldMs, int dir) {
        int mult;
        if (heldMs < 800) mult = 50;
        else if (heldMs < 1800) mult = 180;
        else if (heldMs < 3000) mult = 640;
        else if (heldMs < 4500) mult = 1600;
        else mult = 2800;
        return 90L * mult;   // 后退同速，钳制交给 clampSeek
    }

    /** 老片（mpeg4/rv40/avi 等）快速 seek 常常不生效，落点后复查一次再精确补跳 */
    private Runnable seekFixRunnable;

    private void seekTo(long t, boolean precise) {
        if (mp == null) return;
        if (t < 0) t = 0;
        if (lengthMs > 0 && t > lengthMs - 1000) t = lengthMs - 1000;
        if (isTranscoding()) {
            // 渐进式转码流没有 seek 索引，setTime 会失败或跳回 0；只更新目标，松手时重建
            posMs = t;
            seekTarget = t;
            refreshBar();
            if (hudVisible) refreshTime();
            return;
        }
        final long target = t;
        mp.setTime(t, !precise);
        posMs = t;
        seekTarget = t;
        seekHoldUntil = SystemClock.elapsedRealtime() + 1200;
        refreshBar();
        if (hudVisible) refreshTime();
        ui.removeCallbacks(seekFixRunnable == null ? noop : seekFixRunnable);
        seekFixRunnable = new Runnable() {
            @Override public void run() {
                if (mp == null) return;
                // 按住连跳期间不要补跳：一补就把 seekTarget 拉回按下那一刻，
                // 进度条看着像往回甩一下
                if (seekHoldRunnable != null) return;
                if (pui != null && pui.seekPreviewing()) return;
                long cur = mp.getTime();
                // 只有偏差很大才补一次精确跳；偏差在 4s 内就保持快速定位，避免慢码流二次缓冲
                if (Math.abs(cur - target) > 6000) {
                    mp.setTime(target, false);
                    posMs = target;
                    seekTarget = target;
                    refreshBar();
                    if (isHudVisible()) refreshTime();
                }
            }
        };
        ui.postDelayed(seekFixRunnable, 900);
    }

    private final Runnable noop = new Runnable() { @Override public void run() { } };

    private boolean isHudVisible() { return pui != null && pui.visible(); }

    private Runnable seekHoldRunnable;

    /** 长按由自己计时驱动：起点 300ms，之后每 90ms 走一步（步长随按住时长变大） */
    private void startSeekHold(final int dir) {
        stopSeekHold();
        seekHoldRunnable = new Runnable() {
            @Override public void run() {
                if (seekDir != dir) return;
                seekKeyDown(dir, true);
                ui.postDelayed(this, 90);
            }
        };
        ui.postDelayed(seekHoldRunnable, 300);
    }

    private void stopSeekHold() {
        if (seekHoldRunnable != null) ui.removeCallbacks(seekHoldRunnable);
        seekHoldRunnable = null;
    }

    /** 按下就立刻走一步，不用等抬手 */
    private void seekKeyDown(int dir, boolean repeat) {
        long now = SystemClock.uptimeMillis();
        if (!repeat) {
            seekDir = dir;
            seekPressAt = now;
            seekLastApplyAt = now;
            seekBase = mp == null ? 0 : getPosMs();
            seekTarget = seekBase + dir * 10000L;   // 单击前后都走 10 秒（对齐当贝）
            posMs = seekTarget;
            seekHoldUntil = SystemClock.elapsedRealtime() + 1500;
            seekTo(seekTarget, false);
            if (pui != null) pui.previewSeek(seekTarget, lengthMs);
            seekHud();
            startSeekHold(dir);
            return;
        }
        if (seekDir == 0) return;
        if (now - seekLastApplyAt < 85) { seekHud(); return; }
        seekLastApplyAt = now;
        // 连发时按「按住时长决定步长」累加，长按 1.4s→每步10s，2.8s→20s，4.6s→40s，7s→90s，再久 180s
        // 只更新目标位置和进度条，松手才真正跳一次，避免每次连发都重新缓冲
        long step = holdStepMs(now - seekPressAt, seekDir);
        seekTarget = clampSeek(seekTarget + seekDir * step);
        posMs = seekTarget;
        seekHoldUntil = SystemClock.elapsedRealtime() + 1500;
        refreshBar();
        refreshTime();
        if (pui != null) pui.previewSeek(seekTarget, lengthMs);
        seekHud();
    }

    /** 松手：再做一次精确跳转，修掉快速定位的关键帧偏差 */
    private void seekKeyUp(int dir) {
        if (seekDir == dir && mp != null) {
            long t = clampSeek(seekTarget);
            // 先撤掉按下时排的 900ms 补跳，避免和这次落点互相打架
            if (seekFixRunnable != null) ui.removeCallbacks(seekFixRunnable);
            if (isTranscoding()) {
                // 转码流：让服务器从目标点重新起转
                retranscodeFrom(t);
            } else {
                mp.setTime(t, false);          // 松手做一次精确跳，音画才能重新对齐
                posMs = t;
                seekHoldUntil = SystemClock.elapsedRealtime() + 2200;
                refreshBar();
                if (hudVisible) refreshTime();
            }
        }
        seekHud();
        if (pui != null) pui.endSeekPreview();
        stopSeekHold();
        seekDir = 0;
        seekPressAt = 0;
        seekLastApplyAt = 0;
    }

    /** 快进/后退期间显示底部进度条，停手 4 秒后隐藏 */
    private void seekHud() {
        if (menuOpen) return;
        hudVisible = true;
        pui.show(0);
        long t = seekTarget >= 0 ? seekTarget : posMs;
        if (seekDir != 0) pui.clearHint();
        refreshTime();
        ui.removeCallbacks(hider);
        ui.postDelayed(hider, 4000);
    }

    private void seekBy(long delta) {
        if (mp == null) return;
        long t = mp.getTime() + delta;
        if (t < 0) t = 0;
        if (lengthMs > 0 && t > lengthMs - 1000) t = lengthMs - 1000;
        mp.setTime(t, true);
        posMs = t;
        refreshBar();
        if (hudVisible) refreshTime();
    }

    private int volume() {
        AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        return am.getStreamVolume(AudioManager.STREAM_MUSIC);
    }

    private int maxVolume() {
        AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        return am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
    }

    private void changeVolume(int delta) {
        AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        am.adjustStreamVolume(AudioManager.STREAM_MUSIC,
                delta > 0 ? AudioManager.ADJUST_RAISE : AudioManager.ADJUST_LOWER, 0);
        drawVolume();
    }

    /** 音量条由 PlayerUI 统一绘制（与 ExoPlayer 版同一根竖条） */
    private void drawVolume() {
        if (pui != null) pui.showVolume();
    }

    // ---------- 右侧菜单 ----------

    private int expandedGroup = -1;      // 100 解码方式 102 转码设置 103 字幕设置 104 音频设置 105 显示设置 106 倍速播放 107 播放模式
    private int lastFocusAction = 13;

    private static final String[] DECODE_NAMES = {L.t("硬件解码"), L.t("软件解码"), L.t("系统解码")};
    private static final String[] SIZE_NAMES = {L.t("小"), L.t("标准"), L.t("大")};
    private static final String[] COLOR_NAMES = {L.t("白色"), L.t("黄色"), L.t("青色"), L.t("绿色")};
    private static final int[] COLOR_VALUES = {0xFFFFFF, 0xFFFF00, 0x00FFFF, 0x00FF00};
    private static final String[] MODE_NAMES = {L.t("单片播放"), L.t("列表播放"), L.t("列表循环"), L.t("单片循环")};
    private static final int[] SUB_SIZES = {14, 22, 32};
    private static final float[] SUB_TEXT_SIZES = {20f, 28f, 38f};

    /** 旧内联菜单已由 VMenu 取代；保留空壳避免老调用点改坏状态 */
    private void openMenu() {
        if (menuHost == null) return;
        menuOpen = true;
        menuHost.setVisibility(View.VISIBLE);
        hideHud();
        level = 0;
        rebuildMenu();
    }

    private void startSubTicker() {
        ui.removeCallbacks(subTicker);
        ui.postDelayed(subTicker, 200);
        killSpu();
        ui.postDelayed(new Runnable() {
            @Override public void run() { killSpu(); }
        }, 1200);
        for (int i = 1; i <= 10; i++) {
            ui.postDelayed(new Runnable() {
                @Override public void run() { killSpu(); }
            }, 4000 + i * 1000L);
        }
    }

    // ================= 级联菜单（当贝式三列） =================
    // L1 在最右：根层是文字列表，进入类目后收成窄图标条
    // L2 在 L1 左侧：当前类目的条目（带标题）
    // L3 在 L2 左侧：具体参数值（单选圆点）
    // 右键 = 回上一级；左键 = 进下一级（同步滑条例外，左右键调值）

    private static final int COL_W = 296;   // 常规列宽 dp
    private static final int RAIL_W = 64;   // 折叠后的图标条宽 dp

    private LinearLayout newColumn() {
        LinearLayout c = U.col(this);
        c.setBackground((U.EMBY ? U.cyberSheet(this) : U.sheetBg(this)));
        c.setPadding(U.dp(this, 8), U.dp(this, 14), U.dp(this, 8), U.dp(this, 14));
        return c;
    }

    private android.widget.ScrollView wrapColumn(LinearLayout c) {
        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        sv.setVerticalScrollBarEnabled(false);
        sv.addView(c, new FrameLayout.LayoutParams(-1, -2));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(U.dp(this, COL_W), -1);
        lp.leftMargin = U.dp(this, 3);
        lp.rightMargin = U.dp(this, 3);
        sv.setLayoutParams(lp);
        return sv;
    }

    private void setColumnWidth(android.widget.ScrollView sv, int dp) {
        if (sv == null) return;
        android.view.ViewGroup.LayoutParams lp = sv.getLayoutParams();
        if (lp == null) return;
        if (lp.width == U.dp(this, dp)) return;
        lp.width = U.dp(this, dp);
        sv.setLayoutParams(lp);
    }

    private TextView colHeader(String s) {
        TextView t = U.tv(this, s, 13, U.TXT, true);
        t.setSingleLine(true);
        t.setPadding(U.dp(this, 10), U.dp(this, 2), U.dp(this, 10), U.dp(this, 10));
        return t;
    }

    /** L1 类目行：图标 + 名称 + 当前值；进入类目后收起只剩图标 */
    private void addCatRow(LinearLayout col, final ArrayList<View> list, final ArrayList<Integer> acts,
                           int iconKind, String label, String value, int act) {
        LinearLayout row = U.row(this);
        row.setPadding(U.dp(this, 10), U.dp(this, 11), U.dp(this, 10), U.dp(this, 11));
        row.setBackground(U.round(0x00000000, U.dp(this, 8)));
        row.setGravity(Gravity.CENTER_VERTICAL);
        final U.IconView ic = new U.IconView(this, iconKind, U.TXT);
        row.addView(ic, new LinearLayout.LayoutParams(U.dp(this, 22), U.dp(this, 22)));
        final TextView l = U.tv(this, label, 15, U.TXT, false);
        l.setSingleLine(true);
        LinearLayout.LayoutParams ll = new LinearLayout.LayoutParams(0, -2, 1f);
        ll.leftMargin = U.dp(this, 8);
        row.addView(l, ll);
        final TextView v = U.tv(this, value == null ? "" : value, 12, U.DIM, false);
        v.setSingleLine(true);
        row.addView(v);
        row.setTag(new Object[]{ic, l, v, label, Integer.valueOf(iconKind), Integer.valueOf(value == null ? 0 : 1)});
        col.addView(row, U.lp(-1, -2));
        list.add(row);
        acts.add(Integer.valueOf(act));
    }

    private void addRow(LinearLayout col, ArrayList<View> list, ArrayList<Integer> acts,
                        String label, String value, int act) {
        LinearLayout row = U.row(this);
        row.setPadding(U.dp(this, 12), U.dp(this, 11), U.dp(this, 10), U.dp(this, 11));
        row.setBackground(U.round(0x00000000, U.dp(this, 8)));
        TextView l = U.tv(this, label, 15, U.TXT, false);
        l.setSingleLine(true);
        l.setEllipsize(android.text.TextUtils.TruncateAt.END);
        row.addView(l, new LinearLayout.LayoutParams(0, -2, 1f));
        if (value != null && !value.isEmpty()) {
            TextView v = U.tv(this, value, 12, U.DIM, false);
            v.setSingleLine(true);
            v.setPadding(U.dp(this, 8), 0, 0, 0);
            row.addView(v);
        }
        col.addView(row, U.lp(-1, -2));
        list.add(row);
        if (acts != null) acts.add(Integer.valueOf(act));
    }

    private void addSliderRow(LinearLayout col, ArrayList<View> list, ArrayList<Integer> acts) {
        LinearLayout row = U.row(this);
        row.setPadding(U.dp(this, 12), U.dp(this, 9), U.dp(this, 10), U.dp(this, 9));
        row.setBackground(U.round(0x00000000, U.dp(this, 8)));
        TextView l = U.tv(this, L.t("同步"), 15, U.TXT, false);
        row.addView(l);
        SubDelayBar bar = new SubDelayBar(this);
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(0, U.dp(this, 20));
        bl.weight = 1;
        bl.leftMargin = U.dp(this, 10);
        bl.rightMargin = U.dp(this, 6);
        row.addView(bar, bl);
        TextView dv = U.tv(this, delayText(), 12, U.DIM, false);
        dv.setMinWidth(U.dp(this, 46));
        dv.setGravity(Gravity.RIGHT);
        row.addView(dv);
        col.addView(row, U.lp(-1, -2));
        list.add(row);
        if (acts != null) acts.add(Integer.valueOf(11));
        gBar = bar;
        gBarVal = dv;
        gBarRow = list.size() - 1;
    }

    private String delayText() {
        float v = subDelayUs / 1000000f;
        String t = (v == Math.round(v)) ? String.valueOf((int) v) : String.format("%.1f", v);
        return t + L.t(" 秒");
    }

    // 每个类目的 L2 缓存：只建一次，切换靠显隐，避免每次按键重建
    private final ArrayList<LinearLayout> g2 = new ArrayList<LinearLayout>();
    private final ArrayList<ArrayList<View>> gRows = new ArrayList<ArrayList<View>>();
    private final ArrayList<ArrayList<Integer>> gActs = new ArrayList<ArrayList<Integer>>();
    private final ArrayList<SubDelayBar> gBars = new ArrayList<SubDelayBar>();
    private final ArrayList<TextView> gBarVals = new ArrayList<TextView>();
    private final ArrayList<Integer> gBarRows = new ArrayList<Integer>();
    private SubDelayBar gBar;
    private TextView gBarVal;
    private int gBarRow = -1;

    private void rebuildMenu() {
        if (menuHost == null) return;
        // ---- L1 ----
        col1.removeAllViews();
        rows1.clear();
        acts1.clear();
        addCatRow(col1, rows1, acts1, U.IconView.DECODE, L.t("解码方式"), decodeName(), 100);
        addCatRow(col1, rows1, acts1, U.IconView.TRANSCODE, L.t("转码设置"), transLabel(), 102);
        addCatRow(col1, rows1, acts1, U.IconView.SUBTITLE, L.t("字幕设置"), null, 103);
        addCatRow(col1, rows1, acts1, U.IconView.AUDIO, L.t("音频设置"), null, 104);
        addCatRow(col1, rows1, acts1, U.IconView.DISPLAY, L.t("显示设置"), aspectNames[aspectIdx], 105);
        addCatRow(col1, rows1, acts1, U.IconView.SPEED, L.t("倍速播放"), speedLabel(), 106);
        addCatRow(col1, rows1, acts1, U.IconView.MODE, L.t("播放模式"), MODE_NAMES[playModeIdx()], 107);
        addCatRow(col1, rows1, acts1, U.IconView.STATS, L.t("播放统计"), methodLabel(), 13);
        if (sel[0] >= rows1.size()) sel[0] = 0;

        // ---- L2：一次建好 8 组 ----
        col2.removeAllViews();
        g2.clear(); gRows.clear(); gActs.clear();
        gBars.clear(); gBarVals.clear(); gBarRows.clear();
        for (int i = 0; i < acts1.size(); i++) {
            LinearLayout grp = U.col(this);
            grp.addView(colHeader(row1Label(i)), U.lp(-1, -2));
            ArrayList<View> rr = new ArrayList<View>();
            ArrayList<Integer> aa = new ArrayList<Integer>();
            gBar = null; gBarVal = null; gBarRow = -1;
            int a1 = acts1.get(i).intValue();
            if (a1 == 13) {
                addRow(grp, rr, aa, L.t("当前：") + methodLabel(), "", 999);
                addRow(grp, rr, aa, L.t("格式：") + (mediaName.isEmpty() ? "-" : mediaName), "", 999);
            } else {
                buildLevel2(a1, grp, rr, aa);
            }
            col2.addView(grp, U.lp(-1, -2));
            g2.add(grp);
            gRows.add(rr);
            gActs.add(aa);
            gBars.add(gBar);
            gBarVals.add(gBarVal);
            gBarRows.add(Integer.valueOf(gBarRow));
        }
        showGroup(sel[0]);
        if (level == 2) {
            if (hasThird(actAt(1, sel[1]))) rebuildL3(); else level = 1;
        }
        applyLevel();
        paint();
    }

    /** 切到某个类目的 L2：只切显隐，不建视图 */
    private void showGroup(int idx) {
        if (idx < 0 || idx >= g2.size()) return;
        for (int i = 0; i < g2.size(); i++) g2.get(i).setVisibility(i == idx ? View.VISIBLE : View.GONE);
        rows2.clear();
        rows2.addAll(gRows.get(idx));
        acts2.clear();
        acts2.addAll(gActs.get(idx));
        delayBar = gBars.get(idx);
        delayValue = gBarVals.get(idx);
        delayRowIdx = gBarRows.get(idx).intValue();
        if (sel[1] >= rows2.size()) sel[1] = 0;
    }

    private void buildLevel2(int a1, LinearLayout col, ArrayList<View> rows, ArrayList<Integer> acts) {
        if (a1 == 100) {
            int dm = pstore == null ? 0 : pstore.decodeMode();
            addRow(col, rows, acts, (dm == 0 ? "◉ " : "○ ") + L.t("硬件解码"), "", 20);
            addRow(col, rows, acts, (dm == 1 ? "◉ " : "○ ") + L.t("软件解码"), "", 21);
            addRow(col, rows, acts, (dm == 2 ? "◉ " : "○ ") + L.t("系统解码"), "", 22);
        } else if (a1 == 102) {
            boolean tr = pstore != null && pstore.forceTranscode();
            addRow(col, rows, acts, (tr ? "◉ " : "○ ") + L.t("转码开关"), tr ? L.t("开") : L.t("关"), 14);
            addRow(col, rows, acts, L.t("分辨率"), resLabel(), 15);
            addRow(col, rows, acts, L.t("码率"), brLabel(), 16);
        } else if (a1 == 103) {
            boolean on = pstore == null || pstore.subOn();
            addRow(col, rows, acts, (on ? "◉ " : "○ ") + L.t("字幕开关"), on ? L.t("开") : L.t("关"), 23);
            addRow(col, rows, acts, L.t("外挂字幕"), subLabel.isEmpty() ? L.t("自动") : subLabel, 24);
            addRow(col, rows, acts, L.t("内嵌字幕"),
                    localSubLabel.isEmpty() ? (hasLocalSub() ? L.t("未选") : L.t("无内嵌")) : localSubLabel, 27);
            addRow(col, rows, acts, L.t("字号"), subSizeName(), 25);
            addRow(col, rows, acts, L.t("颜色"), subColorName(), 26);
            addSliderRow(col, rows, acts);
        } else if (a1 == 104) {
            MediaPlayer.TrackDescription[] ts = mp == null ? null : mp.getAudioTracks();
            if (ts == null || ts.length == 0) {
                addRow(col, rows, acts, L.t("没有可选音轨"), "", 999);
            } else {
                int cur = mp.getAudioTrack();
                for (int i = 0; i < ts.length; i++) {
                    String nm = ts[i].name == null ? (L.t("音轨 ") + (i + 1)) : ts[i].name;
                    addRow(col, rows, acts, (ts[i].id == cur ? "◉ " : "○ ") + nm, "", 500 + i);
                }
            }
        } else if (a1 == 105) {
            for (int i = 0; i < aspectNames.length; i++)
                addRow(col, rows, acts, (i == aspectIdx ? "◉ " : "○ ") + aspectNames[i], "", 30 + i);
        } else if (a1 == 106) {
            for (int i = 0; i < speeds.length; i++)
                addRow(col, rows, acts, (i == speedIdx ? "◉ " : "○ ") + (speeds[i] / 100f) + "×", "", 300 + i);
        } else if (a1 == 107) {
            for (int i = 0; i < MODE_NAMES.length; i++)
                addRow(col, rows, acts, (i == playModeIdx() ? "◉ " : "○ ") + MODE_NAMES[i], "", 40 + i);
        }
    }

    private String row1Label(int i) {
        Object[] t = (Object[]) rows1.get(i).getTag();
        return t == null ? "" : (String) t[3];
    }

    /** 该行是否有第三层可选值 */
    private boolean hasThird(int a) {
        return a == 24 || a == 27 || a == 25 || a == 26 || a == 15 || a == 16;
    }

    /** L3：具体参数值列表（左边单选圆点） */
    private void rebuildL3() {
        col3.removeAllViews();
        rows3.clear();
        subChoices.clear();
        col3.addView(colHeader(l3Title()), U.lp(-1, -2));
        int a2 = rows2.isEmpty() ? -1 : acts2.get(sel[1]).intValue();
        if (a2 == 24) {
            for (int i = 0; i < subs.size(); i++) {
                Api.SubStream x = subs.get(i);
                if (!x.external || x.url == null || x.url.isEmpty()) continue;
                subChoices.add(x);
                String nm = x.title == null || x.title.isEmpty() ? (L.t("字幕 ") + (i + 1)) : x.title;
                boolean cur = nm.equals(subLabel);
                addRow(col3, rows3, null, (cur ? "◉ " : "○ ") + nm,
                        x.desc(), 600 + subChoices.size() - 1);
            }
            if (subChoices.isEmpty()) addRow(col3, rows3, null, L.t("同目录没有外挂字幕"), "", 999);
        } else if (a2 == 27) {
            // 内嵌字幕：直接列 VLC 解析出的 spu 轨，不依赖服务器导出
            MediaPlayer.TrackDescription[] ts = mp == null ? null : mp.getSpuTracks();
            int curId = mp == null ? -1 : mp.getSpuTrack();
            int cnt = ts == null ? 0 : ts.length;
            if (cnt == 0) {
                addRow(col3, rows3, null, L.t("这条片子没有内嵌字幕"), "", 999);
            } else {
                addRow(col3, rows3, null, (curId < 0 ? "◉ " : "○ ") + L.t("关闭内嵌字幕"), "", 600);
                for (int i = 0; i < cnt; i++)
                    addRow(col3, rows3, null, (curId == ts[i].id ? "◉ " : "○ ") + spuName(ts[i]), "", 601 + i);
            }
        } else if (a2 == 25) {
            for (int i = 0; i < SIZE_NAMES.length; i++)
                addRow(col3, rows3, null, (i == subSizeIdx() ? "◉ " : "○ ") + SIZE_NAMES[i], "", 700 + i);
        } else if (a2 == 26) {
            for (int i = 0; i < COLOR_NAMES.length; i++)
                addRow(col3, rows3, null, (i == subColorIdx() ? "◉ " : "○ ") + COLOR_NAMES[i], "", 720 + i);
        } else if (a2 == 15) {
            for (int i = 0; i < Store.MAX_HEIGHTS.length; i++)
                addRow(col3, rows3, null, (i == maxHeightIdx() ? "◉ " : "○ ") + heightLabel(i), "", 740 + i);
        } else if (a2 == 16) {
            for (int i = 0; i < Store.MAX_KBPS.length; i++)
                addRow(col3, rows3, null, (i == maxKbpsIdx() ? "◉ " : "○ ") + kbpsLabel(i), "", 760 + i);
        }
        if (sel[2] >= rows3.size()) sel[2] = 0;
    }

    private String l3Title() {
        int a2 = rows2.isEmpty() ? -1 : acts2.get(sel[1]).intValue();
        if (a2 == 24) return L.t("外挂字幕");
        if (a2 == 27) return L.t("内嵌字幕");
        if (a2 == 25) return L.t("字号");
        if (a2 == 26) return L.t("颜色");
        if (a2 == 15) return L.t("分辨率");
        if (a2 == 16) return L.t("码率");
        return L.t("选项");
    }

    private String heightLabel(int i) {
        int n = Store.MAX_HEIGHTS.length;
        int h = Store.MAX_HEIGHTS[i < 0 ? 0 : (i >= n ? n - 1 : i)];
        return heightText(h);
    }

    private String kbpsLabel(int i) {
        int n = Store.MAX_KBPS.length;
        int k = Store.MAX_KBPS[i < 0 ? 0 : (i >= n ? n - 1 : i)];
        return kbpsText(k);
    }

    private int subSizeIdx() { return pstore == null ? 1 : pstore.subSize(); }
    private int subColorIdx() { return pstore == null ? 0 : pstore.subColor(); }
    private int maxHeightIdx() { return pstore == null ? 0 : pstore.maxHeightOpt(); }
    private int maxKbpsIdx() { return pstore == null ? 0 : pstore.maxKbpsOpt(); }

    /** 层级变化后统一刷新外观：L1 收成图标条 / 显示 L2 / 显示 L3 */
    private void applyLevel() {
        boolean rail = level >= 1;
        setColumnWidth(sv1, rail ? RAIL_W : COL_W);
        for (int i = 0; i < rows1.size(); i++) {
            View r = rows1.get(i);
            Object[] t = (Object[]) r.getTag();
            if (t == null) continue;
            U.IconView ic = (U.IconView) t[0];
            TextView l = (TextView) t[1];
            TextView v = (TextView) t[2];
            if (rail) {
                // 收起成图标栏：只留矢量图标，文字全部隐藏
                LinearLayout.LayoutParams icp = (LinearLayout.LayoutParams) ic.getLayoutParams();
                icp.width = U.dp(this, 34);
                icp.height = U.dp(this, 34);
                ic.setLayoutParams(icp);
                ic.setVisibility(View.VISIBLE);
                l.setVisibility(View.GONE);
                v.setVisibility(View.GONE);
                ((LinearLayout) r).setGravity(Gravity.CENTER);
                r.setPadding(U.dp(this, 4), U.dp(this, 13), U.dp(this, 4), U.dp(this, 13));
            } else {
                LinearLayout.LayoutParams icp = (LinearLayout.LayoutParams) ic.getLayoutParams();
                icp.width = U.dp(this, 22);
                icp.height = U.dp(this, 22);
                ic.setLayoutParams(icp);
                ic.setVisibility(View.VISIBLE);
                l.setVisibility(View.VISIBLE);
                l.setText((String) t[3]);
                l.setTextSize(15f);
                l.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
                LinearLayout.LayoutParams ll = (LinearLayout.LayoutParams) l.getLayoutParams();
                ll.weight = 1;
                ll.width = 0;
                ll.leftMargin = U.dp(this, 8);
                l.setLayoutParams(ll);
                v.setVisibility(((Integer) t[5]).intValue() == 1 ? View.VISIBLE : View.GONE);
                ((LinearLayout) r).setGravity(Gravity.CENTER_VERTICAL);
                r.setPadding(U.dp(this, 10), U.dp(this, 11), U.dp(this, 10), U.dp(this, 11));
            }
        }
        if (col3 != null) col3.setVisibility(level == 2 && !rows3.isEmpty() ? View.VISIBLE : View.GONE);
        if (sv3 != null) sv3.setVisibility(level == 2 && !rows3.isEmpty() ? View.VISIBLE : View.GONE);
        if (sv2 != null) sv2.setVisibility(level >= 1 ? View.VISIBLE : View.GONE);
        if (menuHost != null) {
            menuHost.animate().alpha(1f).setDuration(120).start();
        }
    }

    private void paint() {
        paintCol(rows1, 0);
        paintCol(rows2, 1);
        paintCol(rows3, 2);
        if (delayBar != null) {
            delayBar.setFrac((subDelayUs / 1000000f + 5f) / 15f);
            delayBar.setActive(level == 1 && sel[1] == delayRowIdx);
            if (delayValue != null) delayValue.setText(delayText());
        }
    }

    private void paintCol(ArrayList<View> rows, int lv) {
        if (bgSel == null) {
            bgSel = U.roundStroke(0xFF1C1C1E, U.dp(this, 6), U.dp(this, 0), 0x00FFFFFF);
            bgParent = U.round(0xFF2C2C2E, U.dp(this, 6));
            bgNone = U.round(0x00000000, U.dp(this, 8));
        }
        int n = rows.size();
        for (int i = 0; i < n; i++) {
            View r = rows.get(i);
            boolean cur = level == lv && i == sel[lv];
            boolean parent = level > lv && i == sel[lv];
            if (cur) { r.setBackground(bgSel); }
            else if (parent) r.setBackground(bgParent);
            else r.setBackground(bgNone);
            r.setAlpha(cur ? 1f : 0.62f);
            setRowTextColor(r, cur);
        }
    }

    /** BACK：逐级退回，最后才关菜单（不会直接退出播放器） */
    private void goBack() {
        if (level > 0) {
            level--;
            if (level == 1 && hasThird(actAt(1, sel[1]))) rebuildL3();
            applyLevel();
            paint();
            return;
        }
        closeMenu();
    }

    /** OK：有下级就进下级，否则执行/切换当前项 */
    private void onOkKey() {
        if (level == 0) {
            if (actAt(0, sel[0]) == 13) { showStats(); return; }
            level = 1;
            sel[1] = 0;
            if (hasThird(actAt(1, sel[1]))) rebuildL3(); else rows3.clear();
            applyLevel();
            paint();
            return;
        }
        if (level == 1) {
            int a = actAt(1, sel[1]);
            if (hasThird(a)) {
                level = 2;
                sel[2] = 0;
                rebuildL3();
                applyLevel();
                paint();
                return;
            }
            if (a != 999) change(a, 0);
            return;
        }
        applyChoice();
    }

    private void setRowTextColor(View row, boolean cur) {
        if (!(row instanceof LinearLayout)) return;
        LinearLayout ll = (LinearLayout) row;
        for (int i = 0; i < ll.getChildCount(); i++) {
            View c = ll.getChildAt(i);
            if (c instanceof TextView && !(c instanceof SubDelayBar)) {
                ((TextView) c).setTextColor(U.txt(cur ? 0xFFFFFFFF : 0xFFEDF0F2));
            } else if (c instanceof U.IconView) {
                ((U.IconView) c).setIconColor(cur ? 0xFFFFFFFF : U.TXT);
            }
        }
    }

    private int rowCount(int lv) {
        return lv == 0 ? rows1.size() : (lv == 1 ? rows2.size() : rows3.size());
    }

    private int actAt(int lv, int i) {
        if (lv == 0) return i < acts1.size() ? acts1.get(i).intValue() : -1;
        if (lv == 1) return i < acts2.size() ? acts2.get(i).intValue() : -1;
        return 600 + i;
    }

    private void moveSel(int d) {
        int n = rowCount(level);
        if (n <= 1) { paint(); return; }
        sel[level] = (sel[level] + d + n) % n;
        if (level == 0) { showGroup(sel[0]); paint(); }
        else if (level == 1) {
            if (hasThird(actAt(1, sel[1]))) { rebuildL3(); }
            paint();
        } else paint();
    }

    /** 左键：往下钻 */
    private void onLeftKey() {
        android.util.Log.e("LMBY", "LEFT in lv=" + level);
        if (level == 0) {
            if (actAt(0, sel[0]) == 13) { showStats(); return; }
            level = 1;
            sel[1] = 0;
            if (hasThird(actAt(1, sel[1]))) rebuildL3(); else rows3.clear();
            applyLevel();
            paint();
            return;
        }
        if (level == 1) {
            int a = actAt(1, sel[1]);
            if (a == 11) { change(11, 1); return; }
            if (hasThird(a)) {
                level = 2;
                sel[2] = 0;
                rebuildL3();
                applyLevel();
                paint();
                return;
            }
            change(a, 0);
            return;
        }
        applyChoice();
    }

    /** 右键：回上一级（同步滑条上用左右键调值） */
    private void onRightKey() {
        android.util.Log.e("LMBY", "RIGHT in lv=" + level);
        if (level == 2) {
            level = 1;
            applyLevel();
            paint();
            return;
        }
        if (level == 1) {
            int a = actAt(1, sel[1]);
            if (a == 11) { change(11, -1); return; }
            level = 0;
            applyLevel();
            paint();
        }
    }

    private void applyChoice() {
        if (rows3.isEmpty()) return;
        int a2 = rows2.isEmpty() ? -1 : acts2.get(sel[1]).intValue();
        if (a2 == 24) { applySubChoice(sel[2]); return; }
        if (a2 == 27) {
            MediaPlayer.TrackDescription[] ts = mp == null ? null : mp.getSpuTracks();
            int cnt = ts == null ? 0 : ts.length;
            if (sel[2] <= 0) {
                embedMode = false;
                if (mp != null) mp.setSpuTrack(-1);
                localSubLabel = "";
                toast(L.t("内嵌字幕已关闭"));
            } else {
                int i = sel[2] - 1;
                if (i >= 0 && i < cnt && mp != null) {
                    embedMode = true;
                    // 关掉自绘层，交给 VLC 自己渲染
                    cueText.clear();
                    cueStart.clear();
                    cueEnd.clear();
                    if (subView != null) subView.setVisibility(View.GONE);
                    mp.setSpuTrack(ts[i].id);
                    localSubLabel = spuName(ts[i]);
                    toast(L.t("内嵌字幕：") + localSubLabel);
                }
            }
            level = 1;
            rebuildMenu();
            return;
        }
        if (a2 == 25) { pstore.subSize(sel[2]); toast(L.t("字幕字号：") + SIZE_NAMES[sel[2]]); overlayRefresh(); }
        else if (a2 == 26) { pstore.subColor(sel[2]); toast(L.t("字幕颜色：") + COLOR_NAMES[sel[2]]); overlayRefresh(); }
        else if (a2 == 15) { pstore.maxHeightOpt(sel[2]); toast(L.t("转码分辨率：") + resLabel()); restartMedia(); }
        else if (a2 == 16) { pstore.maxKbpsOpt(sel[2]); toast(L.t("转码码率：") + brLabel()); restartMedia(); }
        level = 1;
        rebuildMenu();
    }

    /** dir: 1 加，-1 减，0 点按（循环/切换） */
    private void change(int a, int dir) {
        android.util.Log.e("LMBY", "change a=" + a + " dir=" + dir + " lv=" + level);
        int step = dir == 0 ? 1 : dir;
        if (a == 14) {
            pstore.forceTranscode(!pstore.forceTranscode());
            toast(pstore.forceTranscode() ? L.t("已开启服务器转码") : L.t("已切回原码率直连"));
            restartMedia();
            rebuildMenu();
            return;
        }
        if (a == 15) {
            int n = Store.MAX_HEIGHTS.length;
            pstore.maxHeightOpt((pstore.maxHeightOpt() + step + n) % n);
            toast(L.t("转码分辨率：") + resLabel());
            restartMedia();
            rebuildMenu();
            return;
        }
        if (a == 16) {
            int n = Store.MAX_KBPS.length;
            pstore.maxKbpsOpt((pstore.maxKbpsOpt() + step + n) % n);
            toast(L.t("转码码率：") + brLabel());
            restartMedia();
            rebuildMenu();
            return;
        }
        if (a == 23) {
            pstore.subOn(!pstore.subOn());
            toast(pstore.subOn() ? L.t("字幕已打开") : L.t("字幕已关闭"));
            overlayRefresh();
            rebuildMenu();
            return;
        }
        if (a == 25) {
            int n = SIZE_NAMES.length;
            int v = (pstore.subSize() + step + n) % n;
            pstore.subSize(v);
            toast(L.t("字幕字号：") + SIZE_NAMES[v]);
            overlayRefresh();
            rebuildMenu();
            return;
        }
        if (a == 26) {
            int n = COLOR_NAMES.length;
            int v = (pstore.subColor() + step + n) % n;
            pstore.subColor(v);
            toast(L.t("字幕颜色：") + COLOR_NAMES[v]);
            overlayRefresh();
            rebuildMenu();
            return;
        }
        if (a == 11) {
            long d = subDelayUs + step * 200000L;
            if (d > 10000000L) d = 10000000L;
            if (d < -5000000L) d = -5000000L;
            subDelayUs = d;
            toast(L.t("字幕同步 ") + delayText());
            paint();
            return;
        }
        if (a >= 20 && a <= 22) {
            pstore.decodeMode(a - 20);
            restartMedia();
            rebuildMenu();
            return;
        }
        if (a >= 30 && a <= 33) {
            aspectIdx = a - 30;
            applyAspect();
            toast(L.t("画面：") + aspectNames[aspectIdx]);
            rebuildMenu();
            return;
        }
        if (a >= 40 && a <= 43) {
            pstore.playMode(a - 40);
            toast(L.t("播放模式：") + MODE_NAMES[pstore.playMode()]);
            rebuildMenu();
            return;
        }
        if (a >= 300 && a < 306) {
            speedIdx = a - 300;
            targetRate = speeds[speedIdx] / 100f;
            rateGuardUntil = android.os.SystemClock.elapsedRealtime() + 4000;
            if (mp != null) mp.setRate(targetRate);
            toast(L.t("倍速 ") + speedLabel());
            rebuildMenu();
            return;
        }
        if (a >= 500 && a < 600) {
            MediaPlayer.TrackDescription[] ts = mp == null ? null : mp.getAudioTracks();
            int i = a - 500;
            if (ts != null && i < ts.length) {
                mp.setAudioTrack(ts[i].id);
                toast(L.t("音轨：") + ts[i].name);
            }
            rebuildMenu();
            return;
        }
        if (a >= 600) {
            applySubChoice(a - 600);
        }
    }

    private void applySubChoice(int idx) {
        android.util.Log.e("LMBY", "applySub idx=" + idx + " n=" + subChoices.size()
                + " rows3=" + rows3.size() + " lv=" + level);
        if (subChoices.isEmpty()) {
            int a2 = rows2.isEmpty() ? -1 : acts2.get(sel[1]).intValue();
            toast(a2 == 27 ? L.t("这条片子没有内嵌字幕，只有外挂的") : L.t("同目录没有外挂字幕"));
            return;
        }
        if (idx < 0 || idx >= subChoices.size()) return;
        Api.SubStream st = subChoices.get(idx);
        if (st.url == null || st.url.isEmpty()) {
            toast(L.t("这条内嵌字幕服务器导不出来（EIA-608 / PGS 图形字幕）"));
            return;
        }
        // 选外挂 → 关掉内嵌 spu 轨，避免两层字幕叠在一起
        embedMode = false;
        if (mp != null) mp.setSpuTrack(-1);
        attachRemote(st, false);
        level = 1;
    }

    /** 有没有内嵌字幕（含图形字幕） */
    private boolean hasLocalSub() {
        MediaPlayer.TrackDescription[] ts = mp == null ? null : mp.getSpuTracks();
        return ts != null && ts.length > 0;
    }

    /** VLC spu 轨的显示名 */
    private String spuName(MediaPlayer.TrackDescription t) {
        if (t == null) return L.t("字幕");
        String n = t.name;
        if (n == null || n.trim().isEmpty()) return L.t("内嵌字幕 ") + t.id;
        return n.trim();
    }

    /** 一级类目右侧的字幕摘要：优先显示当前生效的那条 */
    private String subSummary() {
        if (localSubLabel != null && !localSubLabel.isEmpty()) return localSubLabel;
        return subLabel;
    }

    private String decodeName() {
        int m = pstore == null ? 0 : pstore.decodeMode();
        return DECODE_NAMES[m < 0 || m > 2 ? 0 : m];
    }

    private int playModeIdx() {
        int m = pstore == null ? 0 : pstore.playMode();
        return m < 0 || m > 3 ? 0 : m;
    }

    private String subSizeName() {
        int i = pstore == null ? 1 : pstore.subSize();
        return SIZE_NAMES[i < 0 ? 0 : (i > 2 ? 2 : i)];
    }

    private String subColorName() {
        int i = pstore == null ? 0 : pstore.subColor();
        return COLOR_NAMES[i < 0 ? 0 : (i > 3 ? 3 : i)];
    }

    private String speedLabel() {
        return (speeds[speedIdx] / 100f) + "×";
    }

    private String trackName(MediaPlayer.TrackDescription[] ts, int cur, String prefix) {
        if (ts == null || ts.length == 0) return L.t("无");
        for (int i = 0; i < ts.length; i++) {
            if (ts[i].id == cur) return prefix + (i + 1) + " · " + ts[i].name;
        }
        return prefix + "1";
    }

    /** 播放统计统一交给 VMenu 渲染（两个内核同一份实现，不再各写一套） */
    private void showStats() {
        if (menu != null) menu.statsNow();
    }

    private static String bitRateStr(long bps) {
        if (bps <= 0) return "?";
        if (bps >= 1000000L) return String.format(java.util.Locale.US, "%.2f Mbps", bps / 1000000.0);
        return String.format(java.util.Locale.US, "%.2f Kbps", bps / 1000.0);
    }

    // ---------- 选集（播放器里按上键） ----------

    /** 按上键：拉出这部剧的所有集，缩略图 + 集名，选中即切集 */
    private void openEpisodes() {
        if (pui == null) return;
        final String sid = seriesId;
        new Thread(new Runnable() {
            @Override public void run() {
                java.util.List<Api.Item> eps = null;
                try { eps = Api.episodes(session(), sid); } catch (Throwable ignored) { }
                if (eps == null || eps.isEmpty()) {
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            if (pui != null) pui.toast(L.t("没有可选的集"));
                            showHud();
                        }
                    });
                    return;
                }
                final java.util.List<Api.Item> list = eps;
                int cur = 0;
                for (int i = 0; i < list.size(); i++) {
                    Api.Item e = list.get(i);
                    if (e.id != null && e.id.equals(itemId)) { cur = i; break; }
                }
                String[] labels = new String[list.size()];
                String[] urls = new String[list.size()];
                String[] badges = new String[list.size()];
                for (int i = 0; i < list.size(); i++) {
                    Api.Item e = list.get(i);
                    int num = e.index > 0 ? e.index : (i + 1);
                    labels[i] = (e.name == null || e.name.isEmpty())
                            ? (L.t("第 ") + num + L.t("集"))
                            : e.name;
                    badges[i] = e.pctBadge();
                    if (e.imgTag != null && !e.imgTag.isEmpty()) {
                        urls[i] = Api.imageUrl(session(), e.id, e.imgTag, 300);
                    } else if (e.thumbTag != null && !e.thumbTag.isEmpty()) {
                        urls[i] = Api.thumbUrl(session(), e.id, e.thumbTag, 320);
                    } else {
                        urls[i] = Api.imageUrl(session(), sid, "", 300);
                    }
                }
                final String head = L.t("选集") + " · " + list.size() + " " + L.t("集");
                final int curIdx = cur;
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (pui == null) return;
                        hideHud();
                        pui.showEpisodes(head, labels, urls, badges, curIdx, new PlayerUI.OnEpPick() {
                            @Override public void onPick(int index) { pickEpisode(list, index); }
                        });
                    }
                });
            }
        }).start();
    }

    /** 选集面板里确认了某一集：换源重播 */
    private void pickEpisode(java.util.List<Api.Item> list, int index) {
        if (index < 0 || index >= list.size() || mp == null) { showHud(); return; }
        Api.Item e = list.get(index);
        if (e.id != null && e.id.equals(itemId)) { showHud(); return; }
        if (itemId != null && !itemId.isEmpty()) {
            try { Api.reportStop(session(), itemId, posMs, playSessionId); } catch (Throwable ignored) { }
        }
        itemId = e.id;
        ended = false;
        posMs = 0;
        lengthMs = 0;
        reportedStart = false;
        if (pui != null) pui.title(e.name == null ? "" : e.name);
        restartFromStart();
        showHud();
    }

    /** 播完按播放模式自动续播 */
    private void advanceIfNeeded() {
        final int mode = pstore == null ? 0 : pstore.playMode();
        if (mode == 0) return;
        if (mode == 3) {
            ui.postDelayed(new Runnable() {
                @Override public void run() { seekTo(0L, false); restartMedia(); }
            }, 1200);
            return;
        }
        if (plist == null || plist.length < 2) return;   // 单个文件没有下一集可播，列表模式不参与
        int next = pidx + 1;
        if (next >= plist.length) {
            if (mode != 2) return;
            next = 0;
        }
        final String nextId = plist[next];
        pidx = next;
        new Thread(new Runnable() {
            @Override public void run() {
                String nm = null;
                try { Api.Item d = Api.detail(session(), nextId); if (d != null) nm = d.name; } catch (Exception ignored) { }
                final String title = nm;
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        itemId = nextId;
                        ended = false;
                        posMs = 0;
                        lengthMs = 0;
                        if (title != null && !title.isEmpty() && pui != null) pui.title(title);
                        restartFromStart();
                    }
                });
            }
        }).start();
    }

    private void restartFromStart() {
        if (mp == null) return;
        try {
            mp.stop();
            Media m = new Media(libVlc, Uri.parse(currentUrl()));
            applyDecoder(m);
            m.addOption(":network-caching=1500");
            m.addOption(":spu");
            m.addOption(":sub-track=-1");
            addSyncOptions(m);
            addSubOptions(m);
            mp.setMedia(m);
            m.release();
            mp.play();
            pendingSubPath = null;
            subs.clear();
            if (menu != null) menu.loadSubtitlesAsync();
        } catch (Exception ignored) { }
    }

    private String fmt(long sec) {
        long h = sec / 3600, m = (sec % 3600) / 60, s2 = sec % 60;
        return (h > 0 ? (h + ":") : "") + String.format("%02d:%02d", m, s2);
    }

    /** 多行浮层提示（比普通 toast 停留更久） */
    private void showPanel(String[][] groups) {
        if (pui == null || pui.stats() == null) { toast(L.t("没有可用的统计信息")); return; }
        pui.showStats(groups);
    }

    private void cycAudio() {
        if (mp == null) return;
        MediaPlayer.TrackDescription[] ts = mp.getAudioTracks();
        if (ts == null || ts.length == 0) { toast(L.t("没有可选音轨")); return; }
        int cur = mp.getAudioTrack();
        int idx = 0;
        for (int i = 0; i < ts.length; i++) if (ts[i].id == cur) idx = i;
        idx = (idx + 1) % ts.length;
        mp.setAudioTrack(ts[idx].id);
        toast(L.t("音轨：") + ts[idx].name);
    }

    private void cycSub() {
        if (mp == null) return;
        MediaPlayer.TrackDescription[] ts = mp.getSpuTracks();
        if (ts == null || ts.length == 0) { toast(L.t("没有字幕轨道")); return; }
        int cur = mp.getSpuTrack();
        int idx = -1;
        for (int i = 0; i < ts.length; i++) if (ts[i].id == cur) idx = i;
        idx = (idx + 1) % ts.length;
        mp.setSpuTrack(ts[idx].id);
        toast(L.t("字幕：") + ts[idx].name);
    }


    // ==================== 字幕 ====================

    /** 播放一开始就去问服务器：这条片子有没有字幕流（外挂 / 内嵌） */
    private void loadSubtitlesAsync() {
        if (itemId == null || itemId.isEmpty()) return;
        final Api.Session ss = session();
        new Thread(new Runnable() {
            @Override public void run() {
                final java.util.List<Api.SubStream> got = Api.subtitleStreams(ss, itemId);
                try {
                    Api.Item d = Api.detail(ss, itemId);
                    if (d != null && d.name != null) mediaName = d.name;
                } catch (Exception ignored) { }
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        subs = got;
                        android.util.Log.e("LMBY", "subs loaded n=" + subs.size());
                        for (int k = 0; k < subs.size(); k++) {
                            Api.SubStream q = subs.get(k);
                            android.util.Log.e("LMBY", "  sub idx=" + q.index + " ext=" + q.external
                                    + " codec=" + q.codec + " title=" + q.title
                                    + " url=" + (q.url == null || q.url.isEmpty() ? "EMPTY" : "OK"));
                        }
                        // 字幕开关开：优先内嵌字幕，其次外挂字幕。等 1.6 秒让 libVLC 把内嵌 spu 轨报上来
                        if (pstore != null && !pstore.subOn()) {
                            android.util.Log.e("LMBY", "字幕开关关着，不自动加载字幕");
                            return;
                        }
                        ui.postDelayed(new Runnable() {
                            @Override public void run() { autoPickSub(); }
                        }, 1600);
                    }
                });
            }
        }).start();
    }

    /** 字幕自动加载口径（两个内核共用）：开关开就一定给字幕 → 先内嵌，内核解析不到就请服务器把内嵌导成文本，最后用外挂 */
    private void autoPickSub() {
        if (pstore != null && !pstore.subOn()) return;
        String[] loc = null;
        try { loc = vlcEmbedNames(); } catch (Throwable ignored) { }
        if (loc != null && loc.length > 0) {
            try {
                vlcSelectEmbed(0);
                // 切到内嵌 spu 后清掉外挂/导出文本层，避免两层字幕叠在一起
                parseSrt("");
                overlayRefresh();
                localSubLabel = loc[0];
                subLabel = "";
            } catch (Throwable ignored) { }
            autoSubTries = 0;
            autoSubTimerOn = false;
            android.util.Log.e("LMBY", "自动字幕：内嵌 spu " + loc[0]);
            return;
        }
        // libVLC 还没把 spu 轨报上来：等一会儿再判断，别急着挂外挂
        if (autoSubTries < 4) {
            if (!autoSubTimerOn) {
                autoSubTimerOn = true;
                autoSubTries++;
                android.util.Log.e("LMBY", "自动字幕：内嵌 spu 还没就绪，第 " + autoSubTries + " 次重试");
                ui.postDelayed(new Runnable() {
                    @Override public void run() { autoSubTimerOn = false; autoPickSub(); }
                }, 800);
            }
            return;
        }
        autoSubTries = 0;
        autoSubTimerOn = false;
        Api.SubStream pick = null;
        for (int i = 0; i < subs.size(); i++) {
            Api.SubStream x = subs.get(i);
            if (!x.external && x.url != null && !x.url.isEmpty()) { pick = x; break; }
        }
        if (pick != null) {
            android.util.Log.e("LMBY", "自动字幕：内嵌轨导文本 " + pick.title);
            attachRemote(pick, true);
            return;
        }
        pick = null;
        for (int i = 0; i < subs.size(); i++) {
            Api.SubStream x = subs.get(i);
            if (x.external && x.url != null && !x.url.isEmpty()) {
                if (Api.isChineseSub(x.lang)) { pick = x; break; }
                if (pick == null) pick = x;
            }
        }
        if (pick != null) {
            android.util.Log.e("LMBY", "自动字幕：外挂 " + pick.title);
            attachRemote(pick, true);
        } else {
            android.util.Log.e("LMBY", "自动字幕：这条片子没有字幕");
        }
    }

    /** 把外挂字幕挂到播放器上 */
    private void attachRemote(final Api.SubStream st, final boolean auto) {
        new Thread(new Runnable() {
            @Override public void run() {
                final String text = Api.downloadSubtitle(st.url);
                android.util.Log.e("LMBY", "sub dl title=" + st.title + " ext=" + st.external
                        + " len=" + (text == null ? -1 : text.length()));
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (text == null || text.trim().isEmpty()) {
                            if (!auto) toast(st.external ? L.t("字幕下载失败")
                                    : L.t("这条内嵌字幕服务器导不出来（EIA-608 / PGS 图形字幕）"));
                            return;
                        }
                        // 用文本层渲染时先关掉 libVLC 的内嵌 spu，避免内嵌 + 文本两层字幕同时出现
                        try { vlcClearEmbed(); } catch (Throwable ignored) { }
                        parseSrt(stripTags(toPlainSrt(text)));
                        pendingSubPath = null;
                        overlayRefresh();
                        if (st.external) { subLabel = st.title; localSubLabel = ""; }
                        else { localSubLabel = st.title; }
                        if (auto) toast(L.t("已加载字幕 · ") + st.title);
                        else if (menuOpen) rebuildMenu();
                    }
                });
            }
        }).start();
    }

    /** 写进应用缓存，交给 VLC 读 */
    private String saveSubtoCache(String text) {
        try {
            java.io.File f = new java.io.File(getCacheDir(), "sub.srt");
            java.io.FileOutputStream fo = new java.io.FileOutputStream(f);
            fo.write(toPlainSrt(text).getBytes("UTF-8"));
            fo.close();
            return f.getAbsolutePath();
        } catch (Exception e) { return null; }
    }

    /** ASS/VTT 一律转成纯 SRT：去掉样式标签，字号/颜色/同步才能真正生效 */
    private String toPlainSrt(String raw) {
        if (raw == null) return "";
        String t = raw;
        if (t.startsWith("\uFEFF")) t = t.substring(1);
        if (t.indexOf("[Events]") >= 0 || t.indexOf("[Script Info]") >= 0) return assToSrt(t);
        if (t.trim().startsWith("WEBVTT")) return vttToSrt(t);
        return t;
    }

    private String vttToSrt(String t) {
        StringBuilder out = new StringBuilder();
        String[] lines = t.split("\r?\n");
        int idx = 1;
        StringBuilder body = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            String ln = lines[i];
            if (ln.startsWith("WEBVTT") || ln.startsWith("NOTE") || ln.startsWith("Kind:")
                    || ln.startsWith("Language:")) continue;
            if (ln.indexOf("-->") > 0) {
                String fixed = ln.replace('.', ',').replace("--> ", "--> ");
                String[] r = fixed.split("\\s*-->\\s*");
                if (r.length == 2) {
                    flushVtt(out, idx++, r[0].trim(), r[1].trim(), body.toString());
                    body.setLength(0);
                }
                continue;
            }
            if (ln.trim().isEmpty()) continue;
            if (body.length() > 0) body.append("\n");
            body.append(ln);
        }
        flushVtt(out, idx, "", "", body.toString());
        return out.toString();
    }

    private void flushVtt(StringBuilder out, int idx, String s, String e, String txt) {
        if (txt == null || txt.trim().isEmpty() || s.isEmpty()) return;
        out.append(idx).append("\n").append(s).append(" --> ").append(e)
                .append("\n").append(txt.trim()).append("\n\n");
    }

    /** ASS/SSA -> SRT（取 Dialogue 行，剥掉 {\...} 标签） */
    private String assToSrt(String t) {
        String[] lines = t.split("\r?\n");
        String[] fmt = null;
        StringBuilder out = new StringBuilder();
        int idx = 1;
        for (int i = 0; i < lines.length; i++) {
            String ln = lines[i].trim();
            if (ln.startsWith("Format:")) {
                fmt = ln.substring(7).split("\\s*,\\s*");
                continue;
            }
            if (!ln.startsWith("Dialogue:")) continue;
            if (fmt == null) fmt = new String[]{"Layer", "Start", "End", "Style", "Name",
                    "MarginL", "MarginR", "MarginV", "Effect", "Text"};
            String body = ln.substring(9);
            String[] parts = body.split(",", fmt.length);
            int si = -1, ei = -1, ti = -1;
            for (int k = 0; k < fmt.length && k < parts.length; k++) {
                String f = fmt[k].trim();
                if ("Start".equals(f)) si = k;
                else if ("End".equals(f)) ei = k;
                else if ("Text".equals(f)) ti = k;
            }
            if (si < 0 || ei < 0 || ti < 0 || ti >= parts.length) continue;
            String st = assTime(parts[si].trim());
            String et = assTime(parts[ei].trim());
            if (st == null || et == null) continue;
            String txt = parts[ti];
            txt = txt.replaceAll("\\{[^}]*\\}", "");
            txt = txt.replace("\\N", "\n").replace("\\n", "\n").replace("\\h", " ");
            txt = txt.trim();
            if (txt.isEmpty()) continue;
            out.append(idx++).append("\n").append(st).append(" --> ").append(et)
                    .append("\n").append(txt).append("\n\n");
        }
        return out.toString();
    }

    /** 0:00:01.50 -> 00:00:01,500 */
    private String assTime(String v) {
        try {
            int dot = v.indexOf('.');
            String hms = dot >= 0 ? v.substring(0, dot) : v;
            String cs = dot >= 0 ? v.substring(dot + 1) : "0";
            while (cs.length() < 3) cs = cs + "0";
            cs = cs.substring(0, 3);
            String[] p = hms.split(":");
            if (p.length != 3) return null;
            return pad2(p[0]) + ":" + pad2(p[1]) + ":" + pad2(p[2]) + "," + cs;
        } catch (Exception e) { return null; }
    }

    private String pad2(String v) {
        String x = v.trim();
        return x.length() == 1 ? "0" + x : x;
    }

    /** 挂本地字幕文件并选中它 */
    private void attachFile(String path) {
        if (mp == null || path == null) return;
        pendingSubPath = path;
        // 这个内核上运行期 addSlave 加不上轨道，改成用 :sub-file 参数重开媒体（位置保留）
        restartMedia();
        ui.postDelayed(new Runnable() {
            @Override public void run() {
                subDbg = "spu=" + spuCount() + " cur=" + (mp == null ? -9 : mp.getSpuTrack());
                subStatus = subDbg;
                if (menuOpen) openMenu();
            }
        }, 2500);
    }

    private int spuCount() {
        MediaPlayer.TrackDescription[] ts = mp == null ? null : mp.getSpuTracks();
        return ts == null ? 0 : ts.length;
    }

    /** 选中最后一条字幕轨（刚挂上的外挂字幕排在最后） */
    private void selectLastSpu() {
        if (mp == null) return;
        MediaPlayer.TrackDescription[] ts = mp.getSpuTracks();
        if (ts == null || ts.length == 0) return;
        int id = -1;
        for (int i = 0; i < ts.length; i++) if (ts[i].id >= 0) id = ts[i].id;
        if (id >= 0) mp.setSpuTrack(id);
    }

    private void applyAspect() {
        if (mp == null) return;
        try {
            if (aspectIdx == 0) {
                mp.setVideoScale(MediaPlayer.ScaleType.SURFACE_BEST_FIT);
                mp.setAspectRatio(null);
            } else if (aspectIdx == 1) {
                // 全屏：裁掉黑边铺满整块屏幕
                mp.setVideoScale(MediaPlayer.ScaleType.SURFACE_FILL);
                mp.setAspectRatio(null);
            } else if (aspectIdx == 2) {
                mp.setVideoScale(MediaPlayer.ScaleType.SURFACE_4_3);
            } else {
                mp.setVideoScale(MediaPlayer.ScaleType.SURFACE_16_9);
            }
        } catch (Exception e) {
            android.util.Log.e("LMBY", "applyAspect err " + e);
        }
        // 让视频窗口立刻按新比例重排
        try { mp.updateVideoSurfaces(); } catch (Exception ignored) { }
    }

    private void settleSub() {
        ui.postDelayed(new Runnable() {
            @Override public void run() {
                killSpu();
                if (menu != null) menu.overlayRefresh();
            }
        }, 2500);
    }

    /** 把 VLC 自己的字幕轨全部关掉，只留自绘层（内嵌字幕模式下不动） */
    private void killSpu() {
        if (mp == null || embedMode) return;
        try {
            MediaPlayer.TrackDescription[] ts = mp.getSpuTracks();
            int cur = mp.getSpuTrack();
            android.util.Log.e("LMBY", "spu before: tracks=" + (ts == null ? -1 : ts.length)
                    + " cur=" + cur + " names=" + spuNames(ts));
            mp.setSpuTrack(-1);
            android.util.Log.e("LMBY", "spu after: cur=" + mp.getSpuTrack());
        } catch (Exception e) {
            android.util.Log.e("LMBY", "killSpu err " + e);
        }
    }

    private String spuNames(MediaPlayer.TrackDescription[] ts) {
        if (ts == null) return "-";
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < ts.length; i++) b.append(ts[i].id).append(":").append(ts[i].name).append(";");
        return b.toString();
    }

    // ---------- 自绘字幕 ----------

    /** 解析 SRT 成内存表 */
    private void parseSrt(String srt) {
        cueStart.clear();
        cueEnd.clear();
        cueText.clear();
        cueShown = "";
        if (srt == null) return;
        String[] blocks = srt.replace("\r", "").split("\n\n");
        for (int i = 0; i < blocks.length; i++) {
            String b = blocks[i].trim();
            if (b.isEmpty()) continue;
            int arrow = b.indexOf("-->");
            if (arrow < 0) continue;
            String head = b.substring(0, arrow).trim();
            if (head.lastIndexOf('\n') >= 0) head = head.substring(head.lastIndexOf('\n') + 1).trim();
            String rest = b.substring(arrow + 3).trim();
            int nl = rest.indexOf('\n');
            String tail = nl >= 0 ? rest.substring(0, nl) : rest;
            String body = nl >= 0 ? rest.substring(nl + 1).trim() : "";
            long st = srtTime(head);
            long en = srtTime(tail);
            if (st < 0 || en < 0 || body.isEmpty()) continue;
            cueStart.add(Long.valueOf(st));
            cueEnd.add(Long.valueOf(en));
            cueText.add(body);
        }
    }

    /** 00:00:01,500 -> 1500ms */
    private long srtTime(String v) {
        try {
            String t = v.trim().replace('.', ',');
            String[] p = t.split(":");
            if (p.length < 3) return -1;
            String sec = p[2].replace(',', '.');
            float s2 = Float.parseFloat(sec);
            return (long) ((Integer.parseInt(p[0].trim()) * 3600L
                    + Integer.parseInt(p[1].trim()) * 60L) * 1000L + s2 * 1000L);
        } catch (Exception e) { return -1L; }
    }

    /** 去掉 <font ...> 之类的 HTML 标签和实体 */
    private String stripTags(String t) {
        if (t == null) return "";
        String r = t.replaceAll("<[^>]*>", "");
        r = r.replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">");
        return r;
    }

    /** 按字号/颜色/开关刷新字幕层外观 */
    private void overlayRefresh() {
        if (subView == null) return;
        boolean on = pstore == null || pstore.subOn();
        int si = pstore == null ? 1 : pstore.subSize();
        subView.setTextSize(SUB_TEXT_SIZES[si < 0 ? 0 : (si > 2 ? 2 : si)]);
        int ci = pstore == null ? 0 : pstore.subColor();
        subView.setTextColor(U.txt(COLOR_VALUES[ci < 0 ? 0 : (ci > 3 ? 3 : ci)] | 0xFF000000));
        subView.setShadowLayer(6, 0, 2, 0xCC000000);
        if (!on || cueText.isEmpty()) { subView.setVisibility(View.GONE); cueShown = ""; return; }
        tickSub();
    }

    /** 每 200ms 对一次时间轴，决定显示哪一句 */
    private void tickSub() {
        if (subView == null) return;
        if (embedMode) return;   // 内嵌字幕由 VLC 原生渲染，自绘层让位
        if (pstore != null && !pstore.subOn()) { subView.setVisibility(View.GONE); cueShown = ""; return; }
        if (cueText.isEmpty() || mp == null) { subView.setVisibility(View.GONE); return; }
        long now = posMs;
        if (now <= 0) now = mp.getTime();
        long t = now - subDelayUs / 1000L;
        String show = "";
        for (int i = 0; i < cueStart.size(); i++) {
            if (t >= cueStart.get(i).longValue() && t <= cueEnd.get(i).longValue()) {
                show = cueText.get(i);
                break;
            }
        }
        if (!show.equals(cueShown)) {
            cueShown = show;
            if (show.isEmpty()) subView.setVisibility(View.GONE);
            else { subView.setText(show); subView.setVisibility(View.VISIBLE); }
        }
    }

    private Runnable subTicker = new Runnable() {
        @Override public void run() {
            if (menu != null) menu.tickSub();
            ui.postDelayed(this, 200);
        }
    };

    private void closeMenu() {
        menuOpen = false;
        level = 0;
        if (menuHost != null) menuHost.setVisibility(View.GONE);
        showHud();
    }

    private void restartMedia() {
        restartMediaKeepPosition(false);
    }

    /** 重开当前媒体；转码流的起点已经由 tcStartMs 交给服务器，客户端从流头开始播 */
    private void restartMediaKeepPosition(boolean ignore) {
        if (mp == null) return;
        long t = isTranscoding() ? 0L : getPosMs();
        mp.stop();
        try {
            Media m = new Media(libVlc, Uri.parse(currentUrl()));
            applyDecoder(m);
            m.addOption(":network-caching=1500");
            m.addOption(":spu");
            m.addOption(":sub-track=-1");
            addSyncOptions(m);
            if (t > 0) m.addOption(":start-time=" + (t / 1000));
            addSubOptions(m);
            mp.setMedia(m);
            m.release();
            mp.play();
            settleSub();
        } catch (Exception ignored) { }
    }

    // ---------- 事件 ----------

    @Override public void onEvent(MediaPlayer.Event e) {
        switch (e.type) {
            case MediaPlayer.Event.LengthChanged: {
                // 老 TS 片源这里会报 0，一覆盖就把预置片长冲掉，进度条右边又变 --:--，
                // 快进也没了边界，所以只认有效长度
                long nl = e.getLengthChanged();
                if (nl > 0) lengthMs = nl;
                else if (lengthMs <= 0) lengthMsFallback();
                break;
            }
            
            case MediaPlayer.Event.TimeChanged: {
                long t = tcStartMs + Math.max(0, e.getTimeChanged());
                long now = SystemClock.elapsedRealtime();
                // 老格式（rmvb/mpeg2）seek 落定前解码器时间会乱报旧值：
                // 按住连跳期间一律以目标位置为准；落定窗口内偏差过大也不采信，进度条才不会来回摆
                boolean keep = seekDir != 0
                        || (now < seekHoldUntil && Math.abs(t - posMs) > 1000);
                if (t >= 0 && !keep) posMs = t;
                syncProgressToServer();
                break;
            }
            case MediaPlayer.Event.Playing:
                if (!reportedStart && itemId != null) {
                    reportedStart = true;
                    new Thread(new Runnable() {
                        @Override public void run() {
                            try {
                                String psid = Api.playSession(session(), itemId);
                                if (psid == null || psid.isEmpty())
                                    psid = "loyao-" + System.currentTimeMillis();
                                playSessionId = psid;
                                Api.reportStart(session(), itemId, psid);
                            } catch (Throwable ignored) { }
                        }
                    }).start();
                }
                if (!resumeDone && startMs > 0) {
                    resumeDone = true;
                    final long t0 = startMs;
                    ui.postDelayed(new Runnable() {
                        @Override public void run() { seekTo(t0, true); }
                    }, 600);   // 等解码器准备好，setTime 才会真正跳转
                }
                runOnUiThread(new Runnable() {
                    @Override public void run() { ended = false; pui.setStateText(""); pui.setPlayState(PlayerUI.ST_PAUSE); }
                });
                break;
            case MediaPlayer.Event.Paused:
                runOnUiThread(new Runnable() {
                    @Override public void run() { pui.setStateText(""); pui.setPlayState(PlayerUI.ST_PLAY); }
                });
                break;
            case MediaPlayer.Event.Buffering:
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (mp != null && mp.isPlaying()) pui.setPlayState(PlayerUI.ST_PAUSE);
                        updateSpeed();
                    }
                });
                break;
            case MediaPlayer.Event.EndReached:
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        ended = true;
                        pui.setStateText(L.t("播放结束"));
                        pui.setPlayState(PlayerUI.ST_END);
                        markWatched();
                        showHud();
                        advanceIfNeeded();
                    }
                });
                break;
            case MediaPlayer.Event.EncounteredError:
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        toast(L.t("播放失败，试试切换解码方式"));
                        pui.setStateText(L.t("播放失败"));
                    }
                });
                break;
            case MediaPlayer.Event.Vout:
                runOnUiThread(new Runnable() {
                    @Override public void run() { applyAspect(); }
                });
                break;
        }
    }

    /** 每 100ms 从解码器取一次真实时间，进度条才不会一格一格跳 */
    private final Runnable smoothTick = new Runnable() {
        @Override public void run() {
            if (mp != null && !ended) {
                long t = getPosMs();
                long nowMs = SystemClock.elapsedRealtime();
                if (t >= 0) {
                    if (seekDir != 0) {
                        // 按住连跳期间完全以目标位置为准，绝不采信解码器旧值（老编码 seek 落定慢）
                    } else if (nowMs < seekHoldUntil) {
                        if (Math.abs(t - posMs) <= 1000) posMs = t;
                    } else {
                        posMs = t;
                    }
                    if (!dragging) refreshBar();
                    refreshTime();
                }
            }
            updateSpeed();
            ui.postDelayed(this, 100);
        }
    };

    /** 右上角显示实时下载速率（MB/s），取不到就留空 */
    private void updateSpeed() {
        if (pui == null) return;
        // 只有呼出进度条（顶栏可见）时才在右上角显示网速
        if (!isHudVisible()) {
            pui.setStateText("");
            statT = 0;
            return;
        }
        long now = android.os.SystemClock.elapsedRealtime();
        long bytes = mediaStatBytes();
        if (bytes < 0) { pui.setStateText(""); return; }
        if (statT == 0) { statT = now; statB = bytes; return; }
        long dt = now - statT;
        if (dt < 1000) return;
        double mbps = (bytes - statB) / 1048576.0 / (dt / 1000.0);
        statT = now;
        statB = bytes;
        pui.setStateText(mbps > 0.05
                ? String.format(java.util.Locale.US, "%.1f MB/s", mbps) : "");
    }

    private long mediaStatBytes() {
        try {
            long rx = android.net.TrafficStats.getUidRxBytes(getApplicationInfo().uid);
            if (rx > 0) return rx;
        } catch (Throwable ignore) { }
        try {
            return android.net.TrafficStats.getTotalRxBytes();
        } catch (Throwable e) {
            return -1;
        }
    }

    private void refreshBar() {
        if (pui == null) return;
        pui.setProgress(posMs, lengthMs);
    }

    private void refreshTime() {
        refreshBar();
        if (pui != null) pui.setTimes(posMs, lengthMs);
        ui.removeCallbacks(tick);
        if (hudVisible) ui.postDelayed(tick, 500);
    }

    private Runnable tick = new Runnable() {
        @Override public void run() {
            if (mp != null) {
                long t = getPosMs();
                long nowMs = android.os.SystemClock.elapsedRealtime();
                // 刚松手/内核还在缓冲解码期间，不要用真实位置把进度拉回去，否则进度条会来回摆
                if (seekDir == 0 && nowMs >= seekHoldUntil) {
                    posMs = t;
                }
            }
            if (lengthMs <= 0 && mp != null) lengthMs = mp.getLength();
            // 倍速守护：切换瞬间音频输出重配会让速率短暂回落，几秒内把它拉回目标
            if (mp != null && targetRate != 1f
                    && android.os.SystemClock.elapsedRealtime() < rateGuardUntil) {
                try {
                    float cur = mp.getRate();
                    if (Math.abs(cur - targetRate) > 0.02f) mp.setRate(targetRate);
                } catch (Throwable ignored) { }
            }
            refreshBar();
            if (hudVisible) ui.postDelayed(this, 500);
        }
    };

    // ---------- 按键 ----------

    @Override public boolean dispatchKeyEvent(KeyEvent ev) {
        int code = ev.getKeyCode();
        int action = ev.getAction();

        // 菜单打开时全部按键先交给 VMenu
        if (menu != null && menu.isOpen() && menu.onKey(code, action)) {
            if (!menu.isOpen()) showHud();
            return true;
        }
        // 选集面板打开时：左右/确认交给面板里的卡片焦点，返回或上键收起
        if (pui != null && pui.epOpen()) {
            if (action == KeyEvent.ACTION_DOWN) {
                if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_DPAD_UP) {
                    pui.hideEpisodes();
                    showHud();
                    return true;
                }
                if (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT
                        || code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER) {
                    return super.dispatchKeyEvent(ev);
                }
                if (code == KeyEvent.KEYCODE_MENU) {
                    pui.hideEpisodes();
                    showHud();
                    return true;
                }
            }
            if (action == KeyEvent.ACTION_UP) return true;
            return super.dispatchKeyEvent(ev);
        }
        if (code == KeyEvent.KEYCODE_BACK && action == KeyEvent.ACTION_DOWN) {
            finish();
            return true;
        }
        if (code == KeyEvent.KEYCODE_MENU) {
            if (action == KeyEvent.ACTION_DOWN) {
                if (menu != null) {
                    if (menu.isOpen()) menu.closeMenu();
                    else { hideHud(); menu.openMenu(); }
                }
            }
            return true;
        }

        if (action == KeyEvent.ACTION_DOWN) {
            boolean repeat = ev.getRepeatCount() > 0;
            switch (code) {
                case KeyEvent.KEYCODE_DPAD_LEFT:
                    seekKeyDown(-1, repeat);
                    return true;
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    seekKeyDown(1, repeat);
                    return true;
                case KeyEvent.KEYCODE_DPAD_UP:
                    // 是某一集的话，上键弹出这部剧的选集条
                    if (!seriesId.isEmpty()) { openEpisodes(); return true; }
                    showHud();
                    return true;
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    // 上下键只唤出/续命 HUD（音量仍交给遥控器音量键），与 ExoPlayer 版一致
                    showHud();
                    return true;
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                    if (repeat) return true;
                    // 一下 OK 直接暂停/继续并显示 HUD，与 ExoPlayer 版一致
                    togglePlay();
                    showHud();
                    return true;
                case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
                case KeyEvent.KEYCODE_MEDIA_NEXT:
                    // 遥控器 ⏩ 键：与方向键同一套「按住连发」逻辑
                    seekKeyDown(1, repeat);
                    return true;
                case KeyEvent.KEYCODE_MEDIA_REWIND:
                case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
                    seekKeyDown(-1, repeat);
                    return true;
                case KeyEvent.KEYCODE_1:
                    seekBy(-60000);
                    return true;
                case KeyEvent.KEYCODE_3:
                    seekBy(60000);
                    return true;
            }
        }
        if (action == KeyEvent.ACTION_UP) {
            switch (code) {
                case KeyEvent.KEYCODE_DPAD_LEFT:
                case KeyEvent.KEYCODE_MEDIA_REWIND:
                case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
                    seekKeyUp(-1);
                    return true;
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
                case KeyEvent.KEYCODE_MEDIA_NEXT:
                    seekKeyUp(1);
                    return true;
            }
        }
        return super.dispatchKeyEvent(ev);
    }

    /** 同步滑条：左右键当进度条推 */
    static class SubDelayBar extends View {
        private float frac = 0.333f;
        private boolean active = false;

        SubDelayBar(Context c) { super(c); }

        void setFrac(float f) { frac = f < 0 ? 0f : (f > 1f ? 1f : f); invalidate(); }

        void setActive(boolean a) { active = a; invalidate(); }

        @Override protected void onDraw(android.graphics.Canvas cv) {
            int w = getWidth(), h = getHeight();
            float ty = h / 2f;
            float trackH = Math.max(dp(3), h / 4f);
            android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
            p.setColor(active ? 0xFF3A454F : 0xFF2A3138);
            float r = trackH / 2f;
            cv.drawRoundRect(0, ty - r, w, ty + r, r, r, p);
            p.setColor(0xFFFFFFFF);
            float fx = Math.max(trackH, w * frac);
            cv.drawRoundRect(0, ty - r, fx, ty + r, r, r, p);
            p.setColor(active ? 0xFFFFFFFF : 0xFFC3CDD6);
            cv.drawCircle(fx, ty, Math.max(dp(6), h / 2.2f), p);
        }

        private float dp(float v) {
            return v * getResources().getDisplayMetrics().density;
        }
    }

    // ==================== VMenu 内核适配（libVLC 侧） ====================
    // 界面（菜单 / 字幕 / 统计）全部交给 VMenu，这里只把 VLC 内核的能力暴露给菜单。

    /** 播放统计里的 HDR 标记，由 playMedia 后台从 Api.MediaInfo 缓存 */
    private String hdrText = "";
    /** 过滤掉 VLC 的 "Disable" 占位轨之后的内嵌字幕轨，供菜单索引映射 */
    private MediaPlayer.TrackDescription[] spuPick = null;
    /** 同上，音频轨（VLC 也会塞一条 id=-1 的 Disable） */
    private MediaPlayer.TrackDescription[] audPick = null;

    private class VlcHost implements VMenu.Host {
        public Store store() { return pstore; }
        public String itemId() { return itemId; }
        public Api.Session session() { return PlayerActivity.this.session(); }
        public long posMs() { return PlayerActivity.this.posMs; }
        public String[] audioNames() { return vlcAudioNames(); }
        public int curAudio() { return vlcCurAudio(); }
        public void selectAudio(int i) { vlcSelectAudio(i); }
        public void applyAspect(int idx) { aspectIdx = idx; PlayerActivity.this.applyAspect(); }
        public void setSpeed(float v) { vlcSetRate(v); }
        public void restart() { restartMedia(); }
        /** libVLC 本身就是兜底内核，不存在"再退一级" */
        public void bailVlc() { }
        public void showStats(String[][] groups) { vlcShowStats(groups); }
        public String[] statsRuntime() { return vlcStatsRuntime(); }
        public boolean directPlay() {
            String c = currentUrl();
            return c != null && c.contains("static=true");
        }
        public String playSessionId() { return playSessionId; }
        public void toast(String s) { PlayerActivity.this.toast(s); }
        public String[] embedSubNames() { return vlcEmbedNames(); }
        public int curEmbedSub() { return vlcCurEmbed(); }
        public void selectEmbedSub(int i) { vlcSelectEmbed(i); }
        public void clearEmbedSub() { vlcClearEmbed(); }

        public int kernelKind() { return 0; }

        /** 剧集播放时才带 plist（多集列表）；电影等单个文件为 1 或 0 */
        public int listCount() { return plist == null ? 0 : plist.length; }

        /** 播放切换：只对本次播放生效，不写 engine 设置，退出后仍按主界面设置走 */
        public void switchPlayer(int kind) {
            if (kind == 0) { toast(L.t("当前已经在用 libVLC")); return; }
            String u = currentUrl();
            String t = getIntent().getStringExtra("title");
            if (kind == 1) {
                long pos = posMs > 0 ? posMs : startMs;
                android.content.Intent i = new android.content.Intent(PlayerActivity.this, SysPlayerActivity.class);
                i.putExtra("url", u);
                i.putExtra("title", t == null ? "" : t);
                i.putExtra("itemId", itemId == null ? "" : itemId);
                i.putExtra("server", server);
                i.putExtra("token", token);
                i.putExtra("userId", userId);
                i.putExtra("deviceId", deviceId);
                i.putExtra("startMs", pos);
                toast(L.t("临时切到 ExoPlayer"));
                startActivity(i);
                finish();
                return;
            }
            toast(L.t("临时改用第三方播放器"));
            U.launchThird(PlayerActivity.this, u, t == null ? "" : t, pstore, new Runnable() {
                @Override public void run() { finish(); }
            });
        }
    }

    private void vlcShowStats(String[][] groups) {
        if (pui == null) return;
        pui.showStats(groups);
    }

    /** 菜单里切倍速：立刻下发 + 4 秒守护窗口（音频重配会让速率短暂回落） */
    private void vlcSetRate(float v) {
        targetRate = v;
        rateGuardUntil = SystemClock.elapsedRealtime() + 4000;
        try { if (mp != null) mp.setRate(v); } catch (Exception ignored) { }
        android.util.Log.e("LMBY", "VLC 设置倍速 " + v + " 当前=" + (mp == null ? -1 : mp.getRate()));
    }

    private String[] vlcAudioNames() {
        MediaPlayer.TrackDescription[] ts = mp == null ? null : mp.getAudioTracks();
        if (ts == null || ts.length == 0) { audPick = null; return new String[0]; }
        ArrayList<MediaPlayer.TrackDescription> keep = new ArrayList<MediaPlayer.TrackDescription>();
        for (int i = 0; i < ts.length; i++) {
            MediaPlayer.TrackDescription t = ts[i];
            if (t == null || t.id < 0) continue;            // -1 = VLC 的 Disable 占位轨
            if (t.name != null && "Disable".equalsIgnoreCase(t.name.trim())) continue;
            keep.add(t);
        }
        audPick = keep.toArray(new MediaPlayer.TrackDescription[keep.size()]);
        String[] n = new String[audPick.length];
        for (int i = 0; i < audPick.length; i++) {
            String nm = (audPick[i].name == null || audPick[i].name.isEmpty())
                    ? (L.t("音轨 ") + (i + 1)) : audPick[i].name;
            n[i] = nm;
        }
        return n;
    }

    private int vlcCurAudio() {
        if (audPick == null) vlcAudioNames();
        if (audPick == null || audPick.length == 0) return 0;
        int cur = mp == null ? -1 : mp.getAudioTrack();
        for (int i = 0; i < audPick.length; i++) if (audPick[i].id == cur) return i;
        return 0;
    }

    private void vlcSelectAudio(int i) {
        if (audPick == null) vlcAudioNames();
        if (audPick == null || i < 0 || i >= audPick.length) return;
        try {
            mp.setAudioTrack(audPick[i].id);
            android.util.Log.e("LMBY", "VLC 音轨切换到 idx=" + i + " id=" + audPick[i].id
                    + " name=" + audPick[i].name + L.t(" 当前=") + mp.getAudioTrack());
        } catch (Exception ignored) { }
    }

    private String[] vlcEmbedNames() {
        MediaPlayer.TrackDescription[] ts = mp == null ? null : mp.getSpuTracks();
        if (ts == null || ts.length == 0) { spuPick = null; return new String[0]; }
        ArrayList<MediaPlayer.TrackDescription> keep = new ArrayList<MediaPlayer.TrackDescription>();
        for (int i = 0; i < ts.length; i++) {
            MediaPlayer.TrackDescription t = ts[i];
            if (t == null || t.id < 0) continue;           // -1 = VLC 的 Disable 占位轨
            if (t.name == null) continue;
            if ("Disable".equalsIgnoreCase(t.name.trim())) continue;
            keep.add(t);
        }
        spuPick = keep.toArray(new MediaPlayer.TrackDescription[keep.size()]);
        String[] n = new String[spuPick.length];
        for (int i = 0; i < spuPick.length; i++) n[i] = spuName(spuPick[i]);
        return n;
    }

    private int vlcCurEmbed() {
        if (spuPick == null) vlcEmbedNames();
        if (spuPick == null || spuPick.length == 0) return -1;
        int cur = mp == null ? -1 : mp.getSpuTrack();
        for (int i = 0; i < spuPick.length; i++) if (spuPick[i].id == cur) return i;
        return -1;
    }

    private void vlcSelectEmbed(int i) {
        if (spuPick == null) vlcEmbedNames();
        if (spuPick == null || i < 0 || i >= spuPick.length) return;
        try {
            mp.setSpuTrack(spuPick[i].id);
            embedMode = true;
            localSubLabel = spuName(spuPick[i]);
            if (menu != null) menu.hideSubView();
            if (subView != null) subView.setVisibility(View.GONE);
            android.util.Log.e("LMBY", "内嵌字幕切换到 idx=" + i + " id=" + spuPick[i].id
                    + " name=" + spuName(spuPick[i]));
        } catch (Exception ignored) { }
    }

    private void vlcClearEmbed() {
        try { if (mp != null) mp.setSpuTrack(-1); } catch (Exception ignored) { }
        embedMode = false;
        localSubLabel = "";
        if (menu != null) menu.overlayRefresh();
        android.util.Log.e("LMBY", "内嵌字幕已关闭");
    }

    /** 播放统计的运行时信息：{播放器名, HDR, 运行宽, 运行高, 视频codec, 音频codec, 视频码率bps} */
    /** VMenu 统一渲染统计：这里只报内核名 + HDR，实际分辨率/码率/编码器由 VMenu 向会话查 */
    private String[] vlcStatsRuntime() {
        String[] r = new String[7];
        r[0] = "libVLC";
        r[1] = hdrText;
        return r;
    }

    private static String hdrOf(String range) {
        if (range == null) return "";
        String s = range.toUpperCase();
        if (s.contains("DOLBY") || s.contains("DV")) return "Dolby Vision";
        if (s.contains("HDR")) return "HDR10";
        return "";
    }

    @Override protected void onPause() {
        super.onPause();
        if (mp != null && mp.isPlaying()) mp.pause();
        // 位置为 0（刚起播/还没画面）时不要上报，否则会把服务器上的续播进度清零
        if (itemId != null && posMs > 0) Api.reportProgress(session(), itemId, posMs, true, playSessionId);
    }

    @Override protected void onDestroy() {
        if (alive > 0) alive--;
        if (live == this) live = null;
        if (itemId != null) {
            long p = posMs > 0 ? posMs : startMs;
            if (p > 0) Api.reportStop(session(), itemId, p, playSessionId);
        }
        // 记下「这部剧最后播的是哪一集」：详情页的续播按钮和「继续播放」提示都认它
        if (seriesId != null && !seriesId.isEmpty() && itemId != null && !itemId.isEmpty()) {
            try {
                pstore.setLastEp(seriesId, itemId);
                pstore.setLastEpAt(seriesId, System.currentTimeMillis());
            } catch (Throwable ignored) { }
        }
        if (mp != null && mp.getVLCVout() != null && mp.getVLCVout().areViewsAttached()) mp.getVLCVout().detachViews();
        if (mp != null) { mp.stop(); mp.release(); mp = null; }
        if (libVlc != null) { libVlc.release(); libVlc = null; }
        ui.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}
