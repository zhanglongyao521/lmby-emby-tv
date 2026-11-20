package com.longyao.anbitv;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.UUID;

/** 鏈湴閰嶇疆锛氬彧淇濆瓨鏈嶅姟鍣ㄤ笌鐢ㄦ埛鍚嶏紝瀵嗙爜姘镐笉钀界洏 */
public class Store {
    private final SharedPreferences sp;

    public Store(Context c) {
        sp = c.getSharedPreferences("embytv", Context.MODE_PRIVATE);
        if (sp.getString("deviceId", null) == null) {
            sp.edit().putString("deviceId", UUID.randomUUID().toString().replace("-", "").substring(0, 12)).apply();
        }
        migrateSizes();
    }

    /** 全局视图尺寸统一 3 档：0 小 1 标准 2 大（媒体库/搜索/收藏/首页模块共用同一套档位） */
    public static final int[] SIZE_H = {112, 152, 196};
    /** 首页模块与收藏页用的放大一档基准（同样 3 档） */
    public static final int[] SIZE_H_BIG = {150, 190, 236};

    public static int clamp3(int i) { return i < 0 ? 0 : (i > 2 ? 2 : i); }

    /** 老 5 档（超小/小/标准/大/超大）压成 3 档（小/标准/大） */
    private static int m53(int v) { return v <= 1 ? 0 : (v == 2 ? 1 : 2); }

    /** 尺寸档位迁移：老 4 档 → 5 档（历史遗留），再把 5 档压成现在的 3 档（小/标准/大） */
    private void migrateSizes() {
        if (sp.getInt("sizeVer5", 0) == 0) {
            SharedPreferences.Editor e = sp.edit();
            e.putInt("thumbSize", Math.min(4, Math.max(0, sp.getInt("thumbSize", 2) + 1)));
            e.putInt("searchSize", Math.min(4, Math.max(0, sp.getInt("searchSize", 1) + 1)));
            e.putInt("favSize", Math.min(4, Math.max(0, sp.getInt("favSize", 1) + 1)));
            java.util.Map<String, ?> all = sp.getAll();
            for (java.util.Map.Entry<String, ?> en : all.entrySet()) {
                String k = en.getKey();
                Object v = en.getValue();
                if (k.startsWith("libSize_") && v instanceof Integer) {
                    e.putInt(k, Math.min(4, Math.max(0, ((Integer) v) + 1)));
                }
            }
            e.putInt("sizeVer5", 1).apply();
        }
        if (sp.getInt("sizeVer3", 0) == 1) return;
        SharedPreferences.Editor e2 = sp.edit();
        e2.putInt("thumbSize", m53(sp.getInt("thumbSize", 2)));
        e2.putInt("searchSize", m53(sp.getInt("searchSize", 1)));
        e2.putInt("favSize", m53(sp.getInt("favSize", 1)));
        e2.putInt("homeLibSize", m53(sp.getInt("homeLibSize", 2)));
        e2.putInt("homeRecSize", m53(sp.getInt("homeRecSize", 2)));
        e2.putInt("homeLatestSize", m53(sp.getInt("homeLatestSize", 3)));
        e2.putInt("libCardSize", m53(sp.getInt("libCardSize", 0)));
        java.util.Map<String, ?> all2 = sp.getAll();
        for (java.util.Map.Entry<String, ?> en : all2.entrySet()) {
            String k = en.getKey();
            Object v = en.getValue();
            if (k.startsWith("libSize_") && v instanceof Integer) {
                e2.putInt(k, m53((Integer) v));
            }
        }
        e2.putInt("sizeVer3", 1).apply();
    }

    public String server() { return sp.getString("server", ""); }
    public String user() { return sp.getString("user", ""); }
    public String deviceId() { return sp.getString("deviceId", "embytv001"); }
    public int playerMode() { return sp.getInt("playerMode", 0); }
    public int volume() { return sp.getInt("volume", 40); }

    public int thumbMode() { return sp.getInt("thumbMode", 2); }
    public void thumbMode(int m) { sp.edit().putInt("thumbMode", m).apply(); }

    public int favForm() { return sp.getInt("favForm", 0); }
    public void favForm(int v) { sp.edit().putInt("favForm", v).apply(); }

    public int favMode() { return sp.getInt("favMode", 2); }
    public void favMode(int v) { sp.edit().putInt("favMode", v).apply(); }

    public int favSize() { return sp.getInt("favSize", 1); }
    public void favSize(int v) { sp.edit().putInt("favSize", v).apply(); }

    private static final int[] FAV_H = SIZE_H_BIG;

    public int favHeightDp() {
        return FAV_H[clamp3(favSize())];
    }

    public int thumbSize() { return sp.getInt("thumbSize", 1); }
    public void thumbSize(int v) { sp.edit().putInt("thumbSize", v).apply(); }

    private static final int[] THUMB_H = SIZE_H;

    public int thumbHeightDp() {
        return THUMB_H[clamp3(thumbSize())];
    }

    public boolean libSortAsc(String libId) {
        if (libId == null || libId.isEmpty()) return true;
        return sp.getBoolean("libAsc_" + libId, true);
    }

    public void libSortAsc(String libId, boolean asc) {
        if (libId == null || libId.isEmpty()) return;
        sp.edit().putBoolean("libAsc_" + libId, asc).apply();
    }

    public int libSort(String libId) {
        if (libId == null || libId.isEmpty()) return 0;
        return sp.getInt("libSort_" + libId, 0);
    }

    public void libSort(String libId, int v) {
        if (libId == null || libId.isEmpty()) return;
        sp.edit().putInt("libSort_" + libId, v).apply();
    }

    public int libThumbMode(String libId) {
        if (libId == null || libId.isEmpty()) return -1;
        return sp.getInt("libMode_" + libId, -1);
    }

    public void libThumbMode(String libId, int v) {
        if (libId == null || libId.isEmpty()) return;
        sp.edit().putInt("libMode_" + libId, v).apply();
    }

    public int libThumbSize(String libId) {
        if (libId == null || libId.isEmpty()) return -1;
        return sp.getInt("libSize_" + libId, -1);
    }

    public void libThumbSize(String libId, int v) {
        if (libId == null || libId.isEmpty()) return;
        sp.edit().putInt("libSize_" + libId, v).apply();
    }

    /** 该作用域是否用海报形态：-1 未设置 → 跟随全局 */
    public boolean posterFor(String scope) {
        int v = (scope == null || scope.isEmpty()) ? -1 : sp.getInt("scopePoster_" + scope, -1);
        return v < 0 ? poster() : (v == 1);
    }

    public void setPosterFor(String scope, boolean on) {
        if (scope == null || scope.isEmpty()) { poster(on); return; }
        sp.edit().putInt("scopePoster_" + scope, on ? 1 : 0).apply();
    }

    public int modeFor(String libId) {
        int v = libThumbMode(libId);
        return v < 0 ? thumbMode() : v;
    }

    public int sizeFor(String libId) {
        int v = libThumbSize(libId);
        return v < 0 ? thumbSize() : v;
    }

    public void resetLibThumbs() {
        java.util.Map<String, ?> all = sp.getAll();
        android.content.SharedPreferences.Editor e = sp.edit();
        for (String k : all.keySet()) {
            if (k.startsWith("libMode_") || k.startsWith("libSize_")
                    || k.startsWith("scopePoster_")) e.remove(k);
        }
        e.apply();
    }

    public int heightDpFor(String libId) {
        return THUMB_H[clamp3(sizeFor(libId))];
    }

    /** 搜索结果列表的比例：0 自适应 / 1 16:9 / 2 9:16 */
    public int searchThumbMode() { return sp.getInt("searchMode", 2); }
    public void searchThumbMode(int v) { sp.edit().putInt("searchMode", v).apply(); }

    /** 搜索结果的视图类型：0 自动 / 1 缩略图 / 2 海报 */
    public int searchViewType() { return sp.getInt("searchViewType", 0); }
    public void searchViewType(int v) { sp.edit().putInt("searchViewType", v).apply(); }

    /** 搜索结果列表的大小：0 小 / 1 标准 / 2 大 / 3 超大 */
    public int searchThumbSize() { return sp.getInt("searchSize", 1); }
    public void searchThumbSize(int v) { sp.edit().putInt("searchSize", v).apply(); }

    public int searchHeightDp() {
        return THUMB_H[clamp3(searchThumbSize())];
    }

    /** 卡片档位：0 超小 1 小 2 标准 3 大 4 超大；老版本 0..3 一次性右移，视觉尺寸不变 */
    /** 首页卡片大小：0=超小 1=小 2=标准 3=大 4=超大（默认超小） */
    public int libCardSize() {
        if (sp.getInt("libSizeVer", 0) == 0) {
            int v = Math.min(4, Math.max(0, sp.getInt("libCardSize", 0)));
            sp.edit().putInt("libCardSize", v).putInt("libSizeVer", 1).apply();
            return v;
        }
        return Math.max(0, Math.min(4, sp.getInt("libCardSize", 0)));
    }
    public void libCardSize(int v) { sp.edit().putInt("libCardSize", v).putInt("libSizeVer", 1).apply(); }

    /** 界面风格：0=玻璃风 1=官方 Emby（默认玻璃风） */
    public int uiTheme() { int v = sp.getInt("uiTheme", 0); return v <= 0 ? 0 : 1; }
    public void uiTheme(int t) { sp.edit().putInt("uiTheme", t).apply(); }

    /** 界面语言：0=跟随系统 1=中文 2=English */
    public int lang() { int v = sp.getInt("lang", 0); return v < 0 || v > 2 ? 0 : v; }
    public void lang(int v) { sp.edit().putInt("lang", v).apply(); }

    static final boolean SECURE_DEFAULT = false;

    /** 强制走服务器转码（默认直连） */
    public boolean showCarousel() { return sp.getBoolean("showCarousel", true); }
    public void showCarousel(boolean b) { sp.edit().putBoolean("showCarousel", b).apply(); }
    /** 轮播数据来源：0 默认（第一个媒体库）1 最新（随机一个媒体库的最新内容）2 随机（全部库混合随机） */
    public int carSource() { return sp.getInt("carSource", carRandom() ? 2 : 1); }
    public void carSource(int v) { sp.edit().putInt("carSource", v).putBoolean("carRandom", v == 2).apply(); }

    /** 轮播「最新」模式上次用的媒体库 id，用来按顺序切到下一个库 */
    public String carLibId() { return sp.getString("carLibId", ""); }
    public void carLibId(String s) { sp.edit().putString("carLibId", s).apply(); }

    public boolean carRandom() { return sp.getBoolean("carRandom", false); }
    public void carRandom(boolean b) { sp.edit().putBoolean("carRandom", b).apply(); }
    public boolean forceTranscode() { return sp.getBoolean("forceTranscode", false); }
    public void forceTranscode(boolean b) { sp.edit().putBoolean("forceTranscode", b).apply(); }

    /** 转码分辨率档：0=原画，之后按高度排，从 8K 到 480p */
    public int maxHeightOpt() { return sp.getInt("maxHeightOpt", 0); }
    public void maxHeightOpt(int v) { sp.edit().putInt("maxHeightOpt", v).apply(); }

    public static final int[] MAX_HEIGHTS = {0, 4320, 2160, 1440, 1080, 720, 480};
    public int maxHeightPx() {
        int i = maxHeightOpt();
        int n = MAX_HEIGHTS.length;
        return MAX_HEIGHTS[i < 0 ? 0 : (i >= n ? n - 1 : i)];
    }

    /** 转码码率档：0=不限 1=10M 2=5M 3=2.5M */
    public int maxKbpsOpt() { return sp.getInt("maxKbpsOpt", 0); }
    public void maxKbpsOpt(int v) { sp.edit().putInt("maxKbpsOpt", v).apply(); }

    /** 解码方式：0 硬件 1 软件 2 系统 */
    public int decodeMode() { return sp.getInt("decodeMode", 2); }
    /** Exo 播放器内部音量百分比（0~100）：切音轨、直通音轨下系统音量会失效，用这个兜住 */
    public int volPct() { return sp.getInt("volPct", 100); }
    public void volPct(int v) { sp.edit().putInt("volPct", Math.max(0, Math.min(100, v))).apply(); }    public void decodeMode(int v) { sp.edit().putInt("decodeMode", v).apply(); }

    /** 画面比例：0 原画 1 全屏 2 4:3 3 16:9（两个播放内核共用） */
    public int aspect() { return sp.getInt("aspect", 0); }
    public void aspect(int v) { sp.edit().putInt("aspect", v).apply(); }

    /** 播放器选择：0 自动混搭 1 强制 ExoPlayer 2 强制 libVLC 3 第三方播放器 */
    public int engine() { return sp.getInt("engine", 0); }
    public void engine(int v) { sp.edit().putInt("engine", v).apply(); }

    /** 第三方播放器：第一次用时选一次，选完固定下来；空串表示还没初始化过 */
    public String thirdPkg() { return sp.getString("thirdPkg", ""); }
    public String thirdCls() { return sp.getString("thirdCls", ""); }
    public void thirdPlayer(String pkg, String cls) {
        sp.edit().putString("thirdPkg", pkg == null ? "" : pkg)
                .putString("thirdCls", cls == null ? "" : cls).apply();
    }
    /** 设置页「清除第三方播放器初始化」：清掉记忆，下次重新弹选择框 */
    public void clearThirdPlayer() { sp.edit().remove("thirdPkg").remove("thirdCls").apply(); }

    /** 本机播放历史（JSON 数组字符串，最新在前）——按用户隔离，避免切号后串档 */
    public String recent(String scope) {
        return sp.getString("recent_" + (scope == null || scope.isEmpty() ? "_anon" : scope), "");
    }
    public void recent(String scope, String json) {
        sp.edit().putString("recent_" + (scope == null || scope.isEmpty() ? "_anon" : scope), json).apply();
    }

    /** 首页是否显示「最近观看」/「收藏」两块 */
    public boolean showRecent() { return sp.getBoolean("showRecent", true); }
    public void showRecent(boolean b) { sp.edit().putBoolean("showRecent", b).apply(); }
    public boolean showFavorites() { return sp.getBoolean("showFavorites", true); }
    public void showFavorites(boolean b) { sp.edit().putBoolean("showFavorites", b).apply(); }
    /** 首页是否按每个媒体库各生成一行「最新 XX」模块 */
    public boolean showLatestRows() { return sp.getBoolean("showLatestRows", true); }
    public void showLatestRows(boolean b) { sp.edit().putBoolean("showLatestRows", b).apply(); }
    /** 首页三块卡片各自的尺寸档位（0 超小 … 4 超大），互不影响 */
    public int homeLibSize() { return sp.getInt("homeLibSize", 1); }
    public void homeLibSize(int i) { sp.edit().putInt("homeLibSize", i).apply(); }
    public int homeRecSize() { return sp.getInt("homeRecSize", 1); }
    public void homeRecSize(int i) { sp.edit().putInt("homeRecSize", i).apply(); }
    public int homeLatestSize() { return sp.getInt("homeLatestSize", 2); }
    public void homeLatestSize(int i) { sp.edit().putInt("homeLatestSize", i).apply(); }

    /** 焦点放大（缩略图变大） */
    public boolean zoom() { return sp.getBoolean("zoom", true); }
    public void zoom(boolean b) { sp.edit().putBoolean("zoom", b).apply(); }
    /** 页面切换过渡动画 0 跟手 / 1 淡入 / 2 缩放淡入 / 3 上滑淡入 / 4 左右推移 */
    public int pageAnim() { return sp.getInt("pageAnim", 1); }
    public void pageAnim(int i) { sp.edit().putInt("pageAnim", i).apply(); U.PAGE_ANIM = i; }
    /** 上下翻页滚动动画 0 跟手 / 1 平滑 / 2 柔和 */
    public int scrollAnim() { return sp.getInt("scrollAnim", 1); }
    public void scrollAnim(int i) { sp.edit().putInt("scrollAnim", i).apply(); U.SCROLL_ANIM = i; }
    public int font() { return sp.getInt("font", 5); }
    public void font(int i) { sp.edit().putInt("font", i).apply(); }
    public int txtColor() { return sp.getInt("txtColor", 0); }
    public void txtColor(int i) { sp.edit().putInt("txtColor", i).apply(); }
    /** 焦点框颜色索引：0=跟随主题 */
    public int ring() { return sp.getInt("ring", 0); }
    public void ring(int i) { sp.edit().putInt("ring", i).apply(); }
    /** 焦点框样式索引：0..9 */
    public int ringStyle() { return sp.getInt("ringStyle", 0); }
    public void ringStyle(int i) { sp.edit().putInt("ringStyle", i).apply(); }
    /** 背景图索引：0=原生渐变，1..10=内置壁纸 */
    /** 背景：官方 Emby 默认「原生」，玻璃风默认「暗金」；手动选过就固定用户选的 */
    public int bg() {
        if (!sp.contains("bg")) return 19;
        return sp.getInt("bg", 0);
    }
    public void bg(int i) { sp.edit().putInt("bg", i).putBoolean("bgManual", true).apply(); }
    public boolean bgManual() { return sp.getBoolean("bgManual", false); }
    /** 切主题时按主题写默认值，不标记成用户手选 */
    public void bgAuto(int i) { sp.edit().putInt("bg", i).apply(); }

    /** 字幕开关 / 字号档 / 颜色档 */
    public boolean subOn() { return sp.getBoolean("subOn", true); }
    public void subOn(boolean b) { sp.edit().putBoolean("subOn", b).apply(); }
    public int subSize() { return sp.getInt("subSize", 1); }
    public void subSize(int v) { sp.edit().putInt("subSize", v).apply(); }
    public int subColor() { return sp.getInt("subColor", 0); }
    public void subColor(int v) { sp.edit().putInt("subColor", v).apply(); }

    /** 播放模式：0 单片 1 列表 2 列表循环 3 单片循环 */
    public int playMode() { return sp.getInt("playMode", 0); }
    public void playMode(int v) { sp.edit().putInt("playMode", v).apply(); }

    /** 转码码率档：0=不限，之后从 80M 一路到 1.2M */
    public static final int[] MAX_KBPS = {0, 80000, 40000, 20000, 10000, 5000, 2500, 1200};
    public int maxKbps() {
        int i = maxKbpsOpt();
        int n = MAX_KBPS.length;
        return MAX_KBPS[i < 0 ? 0 : (i >= n ? n - 1 : i)];
    }

    public boolean poster() { return sp.getBoolean("poster", true); }
    public void poster(boolean b) { sp.edit().putBoolean("poster", b).apply(); }

    public boolean detailPage() { return sp.getBoolean("detailPage", true); }
    public void detailPage(boolean b) { sp.edit().putBoolean("detailPage", b).apply(); }
    /** 详情页是否显示删除按钮（默认显示，关掉后详情页不出现删除） */
    public boolean detailDelete() { return sp.getBoolean("detailDelete", false); }
    public void detailDelete(boolean b) { sp.edit().putBoolean("detailDelete", b).apply(); }
    /** 启动时是否需要输入密码（默认要） */
    public boolean askPassword() { return sp.getBoolean("askPassword", true); }
    public void askPassword(boolean b) { sp.edit().putBoolean("askPassword", b).apply(); }

    /** 拉起第三方播放器前打点：从那边返回时不该再走「启动密码」，否则看个片回来就要重输一遍 */
    public void markExternalPlay() { sp.edit().putLong("extPlayAt", System.currentTimeMillis()).apply(); }

    /** 从第三方播放器回来后消费这个标记：12 小时内有效，只豁免一次 */
    public boolean consumeExternalPlay() {
        long t = sp.getLong("extPlayAt", 0);
        if (t <= 0) return false;
        sp.edit().remove("extPlayAt").apply();
        return System.currentTimeMillis() - t < 12L * 3600L * 1000L;
    }

    public boolean secure() { return sp.getBoolean("secure", SECURE_DEFAULT); }
    public void secure(boolean b) { sp.edit().putBoolean("secure", b).apply(); }
    /** 仅直连：登录请求不走系统代理 */
    public boolean directOnly() { return sp.getBoolean("directOnly", true); }
    public void directOnly(boolean b) {
        sp.edit().putBoolean("directOnly", b).apply();
        Api.DIRECT_ONLY = b;
    }
    /** STRM 直链播放：strm 条目直接用文件里的 http 地址播放 */
    public boolean strm() { return sp.getBoolean("strm", false); }
    public void strm(boolean b) { sp.edit().putBoolean("strm", b).apply(); }

    /** 服务器显示名（手动填写页里的「我的 Emby 服务器」） */
    public String srvName() { return sp.getString("srvName", ""); }
    public void srvName(String s) { sp.edit().putString("srvName", s).apply(); }
    /** 服务器备注（可选） */
    public String srvNote() { return sp.getString("srvNote", ""); }
    public void srvNote(String s) { sp.edit().putString("srvNote", s).apply(); }

    public void saveLogin(String server, String user) {
        sp.edit().putString("server", server).putString("user", user).apply();
    }

    public void playerMode(int m) { sp.edit().putInt("playerMode", m).apply(); }
    public void volume(int v) { sp.edit().putInt("volume", v).apply(); }

    public String lastServer() { return sp.getString("lastServer", ""); }
    public String lastUser() { return sp.getString("lastUser", ""); }
    public String lastUid() { return sp.getString("lastUid", ""); }
    public String lastName() { return sp.getString("lastName", ""); }

    public void saveLast(String server, String user, String uid, String name) {
        sp.edit().putString("lastServer", server).putString("lastUser", user)
                .putString("lastUid", uid == null ? "" : uid)
                .putString("lastName", name == null ? "" : name).apply();
    }

    public void saveAccount(String server, String user, String uid, String token, String name) {
        if (server == null || user == null || token == null) return;
        sp.edit().putString("acc_" + server + "#" + user.toLowerCase(),
                uid + "\u0001" + token + "\u0001" + (name == null ? user : name)).apply();
    }

    public String[] accountOf(String server, String user) {
        if (server == null || user == null) return null;
        String v = sp.getString("acc_" + server + "#" + user.toLowerCase(), null);
        if (v == null) return null;
        String[] p = v.split("\u0001");
        return p.length >= 2 ? new String[]{p[0], p[1], p.length > 2 ? p[2] : user} : null;
    }

    public java.util.List<String> otherAccounts(String server, String curUser) {
        java.util.List<String> out = new java.util.ArrayList<String>();
        if (server == null) return out;
        String prefix = "acc_" + server + "#";
        for (String k : sp.getAll().keySet()) {
            if (!k.startsWith(prefix)) continue;
            String u = k.substring(prefix.length());
            if (curUser != null && u.equalsIgnoreCase(curUser)) continue;
            out.add(u);
        }
        return out;
    }

    public String[] servers() {
        String s = sp.getString("servers", "");
        return s.isEmpty() ? new String[0] : s.split("\n");
    }

    /** 璁颁綇鎵弿鍒扮殑鏈嶅姟鍣紙鍚嶅瓧 + 鍦板潃锛夛紝涓嬫杩涙潵鐩存帴鏄剧ず */
    public void addServer(String name, String addr) {
        if (addr == null || addr.isEmpty()) return;
        String line = (name == null || name.isEmpty() ? addr : name) + "\u0002" + addr;
        String list = sp.getString("servers", "");
        String old = null;
        for (String l : list.isEmpty() ? new String[0] : list.split("\n")) {
            if (l.endsWith("\u0002" + addr)) { old = l; break; }
        }
        if (old != null) {
            list = list.replace(old, line);
        } else {
            list = list.isEmpty() ? line : list + "\n" + line;
        }
        sp.edit().putString("servers", list).apply();
    }

    public void addServer(String s) { addServer(s, s); }

    /** 删除一个已记住的服务器（按地址匹配） */
    public void removeServer(String addr) {
        if (addr == null || addr.isEmpty()) return;
        String list = sp.getString("servers", "");
        if (list.isEmpty()) return;
        StringBuilder sb = new StringBuilder();
        for (String l : list.split("\n")) {
            if (l.isEmpty()) continue;
            if (l.endsWith("\u0002" + addr)) continue;
            if (sb.length() > 0) sb.append('\n');
            sb.append(l);
        }
        sp.edit().putString("servers", sb.toString()).apply();
    }

    /** 修改服务器：换名字或换地址（地址变了就把旧条目一起替换掉） */
    public void updateServer(String oldAddr, String name, String addr) {
        if (addr == null || addr.isEmpty()) return;
        removeServer(oldAddr);
        addServer(name, addr);
    }

    /** 根据地址取服务器显示名（没记录名字时返回空，界面上就不显示） */
    public String serverLabel(String addr) {
        if (addr == null) return "";
        for (String[] sv : savedServers()) {
            if (sv.length < 2 || !sv[1].equals(addr)) continue;
            String nm = sv[0];
            if (nm == null) return "";
            if (nm.equals(addr) || nm.startsWith("http") || nm.matches("[0-9.]+(:[0-9]+)?")) return "";
            return nm;
        }
        return "";
    }

    /** 宸茶浣忕殑鏈嶅姟鍣ㄥ垪琛紝姣忛」 {鍚嶅瓧, 鍦板潃} */
    public java.util.List<String[]> savedServers() {
        java.util.List<String[]> out = new java.util.ArrayList<String[]>();
        String list = sp.getString("servers", "");
        if (list.isEmpty()) return out;
        for (String l : list.split("\n")) {
            if (l.isEmpty()) continue;
            int i = l.indexOf('\u0002');
            if (i > 0) out.add(new String[]{l.substring(0, i), l.substring(i + 1)});
            else out.add(new String[]{l, l});
        }
        return out;
    }

    public String[] users() {
        String s = sp.getString("users", "");
        return s.isEmpty() ? new String[0] : s.split("\n");
    }

    public void addUser(String s) {
        String list = sp.getString("users", "");
        if (list.contains(s)) return;
        sp.edit().putString("users", list.isEmpty() ? s : list + "\n" + s).apply();
    }

    /** 这部剧最后一次播放的是哪一集（退出播放器时记下：详情页续播按钮/继续播放提示都用它） */
    public String lastEp(String seriesId) {
        return seriesId == null ? "" : sp.getString("lastEp_" + seriesId, "");
    }

    public void setLastEp(String seriesId, String epId) {
        if (seriesId == null || seriesId.isEmpty() || epId == null || epId.isEmpty()) return;
        sp.edit().putString("lastEp_" + seriesId, epId).apply();
    }

    /** 这部剧最后一次播放的时间（毫秒，本地时钟），用来跟服务器时间比新旧 */
    public long lastEpAt(String seriesId) {
        return seriesId == null ? 0 : sp.getLong("lastEpAt_" + seriesId, 0L);
    }

    public void setLastEpAt(String seriesId, long at) {
        if (seriesId == null || seriesId.isEmpty()) return;
        sp.edit().putLong("lastEpAt_" + seriesId, at).apply();
    }
}

