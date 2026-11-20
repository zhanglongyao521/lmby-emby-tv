package com.longyao.anbitv;

import android.app.Activity;
import android.content.Context;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;

/**
 * 播放器右侧级联菜单 + 自绘字幕层。
 * 外观、层级、按键逻辑全部照 libVLC 版 PlayerActivity 原样搬过来（列宽 296/64dp、底色 0xF214181C、
 * 选中白底黑字 + 上级 0xFF39424C、未选 0.62 透明度），只把跟 kernel 相关的调用改为 Host 回调。
 */
public class VMenu {

    public interface Host {
        Store store();
        String itemId();
        Api.Session session();
        long posMs();
        String[] audioNames();
        int curAudio();
        void selectAudio(int i);
        void applyAspect(int idx);
        void setSpeed(float v);
        void restart();
        void bailVlc();
        void showStats(String[][] groups);
        /**
         * 播放统计面板的运行时信息：
         * {播放器名, HDR, 运行宽, 运行高, 视频codec, 音频codec, 视频码率bps}
         */
        String[] statsRuntime();

        /** 当前是否原码率直连（url 带 static=true），用于「播放方式」判断 */
        boolean directPlay();
        /** 当前播放会话 id，用来向服务器查真实播放方式 / 转码参数 */
        String playSessionId();
        void toast(String s);
        /** 内嵌字幕：播放器内核解析出的原字幕轨 */
        String[] embedSubNames();
        /** 当前生效的内嵌字幕轨下标，-1 表示没开 */
        int curEmbedSub();
        void selectEmbedSub(int i);
        void clearEmbedSub();
        /** 当前内核：1 = ExoPlayer，0 = libVLC（用于「播放切换」的勾选状态） */
        int kernelKind();
        /** 本次播放有多少集/多少个文件：<2 表示单个视频（电影、单集），列表模式对它没意义 */
        int listCount();
        /** 播放切换（只对本次播放生效，不写设置）：1 切 ExoPlayer / 0 切 libVLC / 2 第三方播放器 */
        void switchPlayer(int kind);
    }

    // ===== 与 VLC 版完全一致的常量 =====
    private static final String[] SIZE_NAMES = {L.t("小"), L.t("标准"), L.t("大")};
    private static final String[] COLOR_NAMES = {L.t("白色"), L.t("黄色"), L.t("青色"), L.t("绿色")};
    private static final int[] COLOR_VALUES = {0xFFFFFF, 0xFFFF00, 0x00FFFF, 0x00FF00};
    private static final String[] MODE_NAMES = {L.t("单片播放"), L.t("列表播放"), L.t("列表循环"), L.t("单片循环")};
    private static final float[] SUB_TEXT_SIZES = {20f, 28f, 38f};
    private static final int[] speeds = {25, 50, 100, 125, 150, 200};
    private static final String[] aspectNames = {L.t("原画"), L.t("全屏"), "4:3", "16:9"};
    private static final int COL_W = 296;
    private static final int RAIL_W = 64;

    private final Activity act;
    private final Host host;

    // 自动选字幕：用户手动选过就不再用自动逻辑覆盖；tries 用于等内核把内嵌轨报上来
    private volatile boolean subUserPicked = false;
    private int autoSubTries = 0;
    private volatile boolean autoSubTimerOn = false;   // 重试只用一条定时链，避免多个入口各挂一条链互相打乱

    // ---------- 菜单视图 ----------
    private LinearLayout menuHost, col1, col2, col3;
    private android.widget.ScrollView sv1, sv2, sv3;
    private final ArrayList<View> rows1 = new ArrayList<View>();
    private final ArrayList<View> rows2 = new ArrayList<View>();
    private final ArrayList<View> rows3 = new ArrayList<View>();
    private final ArrayList<Integer> acts1 = new ArrayList<Integer>();
    private final ArrayList<Integer> acts2 = new ArrayList<Integer>();
    private final int[] sel = {0, 0, 0};
    private int level = 0;
    private boolean menuOpen = false;
    private int expandedGroup = -1;
    private android.graphics.drawable.Drawable bgSel, bgParent, bgNone;
    private SubDelayBar delayBar;
    private TextView delayValue;
    private int delayRowIdx = -1;

    private final ArrayList<LinearLayout> g2 = new ArrayList<LinearLayout>();
    private final ArrayList<ArrayList<View>> gRows = new ArrayList<ArrayList<View>>();
    private final ArrayList<ArrayList<Integer>> gActs = new ArrayList<ArrayList<Integer>>();
    private final ArrayList<SubDelayBar> gBars = new ArrayList<SubDelayBar>();
    private final ArrayList<TextView> gBarVals = new ArrayList<TextView>();
    private final ArrayList<Integer> gBarRows = new ArrayList<Integer>();
    private SubDelayBar gBar;
    private TextView gBarVal;
    private int gBarRow = -1;

    // ---------- 字幕 ----------
    private TextView subView;
    private String subLabel = "";      // 当前生效的外挂字幕
    private String localSubLabel = ""; // 当前生效的内嵌字幕
    private String mediaName = "";
    private java.util.List<Api.SubStream> subs = new ArrayList<Api.SubStream>();
    private final ArrayList<Api.SubStream> subChoices = new ArrayList<Api.SubStream>();
    private final ArrayList<Long> cueStart = new ArrayList<Long>();
    private final ArrayList<Long> cueEnd = new ArrayList<Long>();
    private final ArrayList<String> cueText = new ArrayList<String>();
    private String cueShown = "";
    private long subDelayUs = 0;
    private int aspectIdx = 0;

    public VMenu(Activity act, Host host) {
        this.act = act;
        this.host = host;
        try { aspectIdx = host.store().aspect(); } catch (Throwable ignored) { }
    }

    // ==================== 视图搭建（照抄 PlayerActivity） ====================

    public void attach(FrameLayout root) {
        // 自绘字幕层
        subView = U.tv(act, "", 22, 0xFFFFFFFF, false);
        subView.setGravity(Gravity.CENTER);
        subView.setSingleLine(false);
        subView.setShadowLayer(6, 0, 2, 0xCC000000);
        subView.setPadding(U.dp(act, 80), 0, U.dp(act, 80), 0);
        subView.setTag("lmby_sub");
        subView.setVisibility(View.GONE);
        FrameLayout.LayoutParams slp = new FrameLayout.LayoutParams(-1, -2);
        slp.gravity = Gravity.BOTTOM;
        slp.bottomMargin = U.dp(act, 56);
        for (int qi = root.getChildCount() - 1; qi >= 0; qi--) {
            View qv = root.getChildAt(qi);
            if ("lmby_sub".equals(qv.getTag())) root.removeViewAt(qi);
        }
        root.addView(subView, slp);

        // 右侧级联菜单
        menuHost = U.row(act);
        menuHost.setVisibility(View.GONE);
        col3 = newColumn();
        col2 = newColumn();
        col1 = newColumn();
        sv3 = wrapColumn(col3);
        sv2 = wrapColumn(col2);
        sv1 = wrapColumn(col1);
        menuHost.addView(sv3);
        menuHost.addView(sv2);
        menuHost.addView(sv1);

        FrameLayout.LayoutParams mpp = new FrameLayout.LayoutParams(-2, -1);
        mpp.gravity = Gravity.RIGHT;
        mpp.topMargin = U.dp(act, 14);
        mpp.bottomMargin = U.dp(act, 14);
        root.addView(menuHost, mpp);
    }

    private LinearLayout newColumn() {
        LinearLayout c = U.col(act);
        c.setBackground((U.EMBY ? U.cyberSheet(act) : U.sheetBg(act)));
        c.setPadding(U.dp(act, 8), U.dp(act, 14), U.dp(act, 8), U.dp(act, 14));
        return c;
    }

    private android.widget.ScrollView wrapColumn(LinearLayout c) {
        android.widget.ScrollView sv = new android.widget.ScrollView(act);
        sv.setVerticalScrollBarEnabled(false);
        sv.addView(c, new FrameLayout.LayoutParams(-1, -2));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(U.dp(act, COL_W), -1);
        lp.leftMargin = U.dp(act, 3);
        lp.rightMargin = U.dp(act, 3);
        sv.setLayoutParams(lp);
        return sv;
    }

    private void setColumnWidth(android.widget.ScrollView sv, int dp) {
        if (sv == null) return;
        android.view.ViewGroup.LayoutParams lp = sv.getLayoutParams();
        if (lp == null) return;
        if (lp.width == U.dp(act, dp)) return;
        lp.width = U.dp(act, dp);
        sv.setLayoutParams(lp);
    }

    private TextView colHeader(String s) {
        TextView t = U.tv(act, s, 13, U.TXT, true);
        t.setSingleLine(true);
        t.setPadding(U.dp(act, 10), U.dp(act, 2), U.dp(act, 10), U.dp(act, 10));
        return t;
    }

    private void addCatRow(LinearLayout col, final ArrayList<View> list, final ArrayList<Integer> acts,
                           int iconKind, String label, String value, int actCode) {
        LinearLayout row = U.row(act);
        row.setPadding(U.dp(act, 10), U.dp(act, 11), U.dp(act, 10), U.dp(act, 11));
        row.setBackground(U.round(0x00000000, U.dp(act, 8)));
        row.setGravity(Gravity.CENTER_VERTICAL);
        final U.IconView ic = new U.IconView(act, iconKind, U.TXT);
        row.addView(ic, new LinearLayout.LayoutParams(U.dp(act, 22), U.dp(act, 22)));
        final TextView l = U.tv(act, label, 15, U.TXT, false);
        l.setSingleLine(true);
        LinearLayout.LayoutParams ll = new LinearLayout.LayoutParams(0, -2, 1f);
        ll.leftMargin = U.dp(act, 8);
        row.addView(l, ll);
        final TextView v = U.tv(act, value == null ? "" : value, 12, U.DIM, false);
        v.setSingleLine(true);
        row.addView(v);
        row.setTag(new Object[]{ic, l, v, label, Integer.valueOf(iconKind), Integer.valueOf(value == null ? 0 : 1)});
        col.addView(row, U.lp(-1, -2));
        list.add(row);
        acts.add(Integer.valueOf(actCode));
    }

    private void addRow(LinearLayout col, ArrayList<View> list, ArrayList<Integer> acts,
                        String label, String value, int actCode) {
        LinearLayout row = U.row(act);
        row.setPadding(U.dp(act, 12), U.dp(act, 11), U.dp(act, 10), U.dp(act, 11));
        row.setBackground(U.round(0x00000000, U.dp(act, 8)));
        TextView l = U.tv(act, label, 15, U.TXT, false);
        l.setSingleLine(true);
        l.setEllipsize(android.text.TextUtils.TruncateAt.END);
        row.addView(l, new LinearLayout.LayoutParams(0, -2, 1f));
        if (value != null && !value.isEmpty()) {
            TextView v = U.tv(act, value, 12, U.DIM, false);
            v.setSingleLine(true);
            v.setPadding(U.dp(act, 8), 0, 0, 0);
            row.addView(v);
        }
        col.addView(row, U.lp(-1, -2));
        list.add(row);
        if (acts != null) acts.add(Integer.valueOf(actCode));
    }

    private void addSliderRow(LinearLayout col, ArrayList<View> list, ArrayList<Integer> acts) {
        LinearLayout row = U.row(act);
        row.setPadding(U.dp(act, 12), U.dp(act, 9), U.dp(act, 10), U.dp(act, 9));
        row.setBackground(U.round(0x00000000, U.dp(act, 8)));
        TextView l = U.tv(act, L.t("同步"), 15, U.TXT, false);
        row.addView(l);
        SubDelayBar bar = new SubDelayBar(act);
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(0, U.dp(act, 20));
        bl.weight = 1;
        bl.leftMargin = U.dp(act, 10);
        bl.rightMargin = U.dp(act, 6);
        row.addView(bar, bl);
        TextView dv = U.tv(act, delayText(), 12, U.DIM, false);
        dv.setMinWidth(U.dp(act, 46));
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

    // ==================== 菜单内容 ====================

    public void openMenu() {
        menuOpen = true;
        if (menuHost == null) return;
        menuHost.setVisibility(View.VISIBLE);
        level = 0;
        rebuildMenu();
    }

    public void closeMenu() {
        menuOpen = false;
        level = 0;
        if (menuHost != null) menuHost.setVisibility(View.GONE);
    }

    public boolean isOpen() { return menuOpen; }

    private void rebuildMenu() {
        if (menuHost == null) return;
        // ---- L1 ----
        col1.removeAllViews();
        rows1.clear();
        acts1.clear();
        addCatRow(col1, rows1, acts1, U.IconView.DECODE, L.t("解码方式"), decodeName(), 100);
        addCatRow(col1, rows1, acts1, U.IconView.MODE, L.t("播放切换"), L.t("临时启动一次"), 108);
        addCatRow(col1, rows1, acts1, U.IconView.TRANSCODE, L.t("转码设置"), transLabel(), 102);
        addCatRow(col1, rows1, acts1, U.IconView.SUBTITLE, L.t("字幕设置"), null, 103);
        addCatRow(col1, rows1, acts1, U.IconView.AUDIO, L.t("音频设置"), null, 104);
        addCatRow(col1, rows1, acts1, U.IconView.DISPLAY, L.t("显示设置"), aspectNames[aspectIdx], 105);
        addCatRow(col1, rows1, acts1, U.IconView.SPEED, L.t("倍速播放"), speedLabel(), 106);
        addCatRow(col1, rows1, acts1, U.IconView.MODE, L.t("播放模式"), modeLabel(), 107);
        addCatRow(col1, rows1, acts1, U.IconView.STATS, L.t("播放统计"), methodLabel(), 13);
        if (sel[0] >= rows1.size()) sel[0] = 0;

        // ---- L2 ----
        col2.removeAllViews();
        g2.clear(); gRows.clear(); gActs.clear();
        gBars.clear(); gBarVals.clear(); gBarRows.clear();
        for (int i = 0; i < acts1.size(); i++) {
            LinearLayout grp = U.col(act);
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
        android.util.Log.e("LMBY", "菜单已建 L1=" + rows1.size() + " L2组=" + g2.size()
                + L.t(" 当前组行数=") + rows2.size() + " level=" + level);
        if (level == 2) {
            if (hasThird(actAt(1, sel[1]))) rebuildL3(); else level = 1;
        }
        applyLevel();
        paint();
    }

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
        Store st = host.store();
        if (a1 == 100) {
            int dm = st == null ? 0 : st.decodeMode();
            addRow(col, rows, acts, (dm == 0 ? "\u25C9 " : "\u25CB ") + L.t("硬件解码"), "", 20);
            addRow(col, rows, acts, (dm == 1 ? "\u25C9 " : "\u25CB ") + L.t("软件解码"), "", 21);
            addRow(col, rows, acts, (dm == 2 ? "\u25C9 " : "\u25CB ") + L.t("系统解码"), "", 22);
        } else if (a1 == 102) {
            boolean tr = st != null && st.forceTranscode();
            addRow(col, rows, acts, (tr ? "\u25C9 " : "\u25CB ") + L.t("转码开关"), tr ? L.t("开") : L.t("关"), 14);
            addRow(col, rows, acts, L.t("分辨率"), resLabel(), 15);
            addRow(col, rows, acts, L.t("码率"), brLabel(), 16);
        } else if (a1 == 103) {
            boolean on = st == null || st.subOn();
            addRow(col, rows, acts, (on ? "\u25C9 " : "\u25CB ") + L.t("字幕开关"), on ? L.t("开") : L.t("关"), 23);
            addRow(col, rows, acts, L.t("外挂字幕"), subLabel.isEmpty() ? L.t("自动") : subLabel, 24);
            addRow(col, rows, acts, L.t("内嵌字幕"),
                    localSubLabel.isEmpty() ? (hasLocalSub() ? L.t("未选") : L.t("无内嵌")) : localSubLabel, 27);
            addRow(col, rows, acts, L.t("字号"), subSizeName(), 25);
            addRow(col, rows, acts, L.t("颜色"), subColorName(), 26);
            addSliderRow(col, rows, acts);
        } else if (a1 == 104) {
            String[] names = host.audioNames();
            if (names == null || names.length == 0) {
                addRow(col, rows, acts, L.t("没有可选音轨"), "", 999);
            } else {
                int cur = host.curAudio();
                for (int i = 0; i < names.length; i++) {
                    String nm = names[i] == null ? (L.t("音轨 ") + (i + 1)) : names[i];
                    addRow(col, rows, acts, (i == cur ? "\u25C9 " : "\u25CB ") + nm, "", 500 + i);
                }
            }
        } else if (a1 == 105) {
            for (int i = 0; i < aspectNames.length; i++)
                addRow(col, rows, acts, (i == aspectIdx ? "\u25C9 " : "\u25CB ") + aspectNames[i], "", 30 + i);
        } else if (a1 == 108) {
            int kd = host.kernelKind();
            addRow(col, rows, acts, (kd == 1 ? "\u25C9 " : "\u25CB ") + "ExoPlayer", L.t("硬解优先"), 810);
            addRow(col, rows, acts, (kd == 0 ? "\u25C9 " : "\u25CB ") + "libVLC", L.t("本地解码"), 811);
            addRow(col, rows, acts, L.t("第三方播放器"), L.t("当贝 / Kodi / MX"), 812);
        } else if (a1 == 106) {
            int si = speedIdx();
            for (int i = 0; i < speeds.length; i++)
                addRow(col, rows, acts, (i == si ? "\u25C9 " : "\u25CB ") + (speeds[i] / 100f) + "\u00D7", "", 300 + i);
        } else if (a1 == 107) {
            int n = host.listCount();
            for (int i = 0; i < MODE_NAMES.length; i++) {
                // 单个文件（电影 / 单独一集）没有列表可播，也不该列表循环
                if (n < 2 && (i == 1 || i == 2)) continue;
                addRow(col, rows, acts, (i == playModeIdx() ? "\u25C9 " : "\u25CB ") + MODE_NAMES[i], "", 40 + i);
            }
        }
    }

    private String row1Label(int i) {
        Object[] t = (Object[]) rows1.get(i).getTag();
        return t == null ? "" : (String) t[3];
    }

    private boolean hasThird(int a) {
        return a == 24 || a == 27 || a == 25 || a == 26 || a == 15 || a == 16;
    }

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
                addRow(col3, rows3, null, (cur ? "\u25C9 " : "\u25CB ") + nm,
                        x.desc(), 600 + subChoices.size() - 1);
            }
            if (subChoices.isEmpty()) addRow(col3, rows3, null, L.t("同目录没有外挂字幕"), "", 999);
        } else if (a2 == 27) {
            // 内嵌字幕：直接列播放器内核解析出的字幕轨，不依赖服务器导出
            String[] names = host.embedSubNames();
            int cur = host.curEmbedSub();
            if (names == null || names.length == 0) {
                if (serverHasEmbed())
                    addRow(col3, rows3, null, L.t("系统播放器解析不到，切到 libVLC 引擎可用"), "", 600);
                else
                    addRow(col3, rows3, null, L.t("这条片子没有内嵌字幕"), "", 999);
            } else {
                addRow(col3, rows3, null, (cur < 0 ? "\u25C9 " : "\u25CB ") + L.t("关闭内嵌字幕"), "", 600);
                for (int i = 0; i < names.length; i++)
                    addRow(col3, rows3, null, (cur == i ? "\u25C9 " : "\u25CB ") + names[i], "", 601 + i);
            }
        } else if (a2 == 25) {
            int cur = subSizeIdx();
            for (int i = 0; i < SIZE_NAMES.length; i++)
                addRow(col3, rows3, null, (i == cur ? "\u25C9 " : "\u25CB ") + SIZE_NAMES[i], "", 700 + i);
        } else if (a2 == 26) {
            int cur = subColorIdx();
            for (int i = 0; i < COLOR_NAMES.length; i++)
                addRow(col3, rows3, null, (i == cur ? "\u25C9 " : "\u25CB ") + COLOR_NAMES[i], "", 720 + i);
        } else if (a2 == 15) {
            int cur = maxHeightIdx();
            for (int i = 0; i < Store.MAX_HEIGHTS.length; i++)
                addRow(col3, rows3, null, (i == cur ? "\u25C9 " : "\u25CB ") + heightLabel(i), "", 740 + i);
        } else if (a2 == 16) {
            int cur = maxKbpsIdx();
            for (int i = 0; i < Store.MAX_KBPS.length; i++)
                addRow(col3, rows3, null, (i == cur ? "\u25C9 " : "\u25CB ") + kbpsLabel(i), "", 760 + i);
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
        if (h <= 0) return L.t("原画");
        if (h >= 4320) return "8K";
        if (h >= 2160) return "4K";
        if (h >= 1440) return "2K";
        return h + "p";
    }

    private String kbpsLabel(int i) {
        int n = Store.MAX_KBPS.length;
        int k = Store.MAX_KBPS[i < 0 ? 0 : (i >= n ? n - 1 : i)];
        if (k <= 0) return L.t("原码率");
        if (k % 1000 == 0) return (k / 1000) + " Mbps";
        return (k / 1000f) + " Mbps";
    }

    private int maxHeightIdx() {
        Store st = host.store();
        return st == null ? 0 : st.maxHeightOpt();
    }

    private int maxKbpsIdx() {
        Store st = host.store();
        return st == null ? 0 : st.maxKbpsOpt();
    }

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
                // 收起成图标栏：只留矢量图标
                LinearLayout.LayoutParams icp = (LinearLayout.LayoutParams) ic.getLayoutParams();
                icp.width = U.dp(act, 34);
                icp.height = U.dp(act, 34);
                ic.setLayoutParams(icp);
                ic.setVisibility(View.VISIBLE);
                l.setVisibility(View.GONE);
                v.setVisibility(View.GONE);
                ((LinearLayout) r).setGravity(Gravity.CENTER);
                r.setPadding(U.dp(act, 4), U.dp(act, 13), U.dp(act, 4), U.dp(act, 13));
            } else {
                LinearLayout.LayoutParams icp = (LinearLayout.LayoutParams) ic.getLayoutParams();
                icp.width = U.dp(act, 22);
                icp.height = U.dp(act, 22);
                ic.setLayoutParams(icp);
                ic.setVisibility(View.VISIBLE);
                l.setVisibility(View.VISIBLE);
                l.setText((String) t[3]);
                l.setTextSize(15f);
                l.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
                LinearLayout.LayoutParams ll = (LinearLayout.LayoutParams) l.getLayoutParams();
                ll.weight = 1;
                ll.width = 0;
                ll.leftMargin = U.dp(act, 8);
                l.setLayoutParams(ll);
                v.setVisibility(((Integer) t[5]).intValue() == 1 ? View.VISIBLE : View.GONE);
                ((LinearLayout) r).setGravity(Gravity.CENTER_VERTICAL);
                r.setPadding(U.dp(act, 10), U.dp(act, 11), U.dp(act, 10), U.dp(act, 11));
            }
        }
        if (col3 != null) col3.setVisibility(level == 2 && !rows3.isEmpty() ? View.VISIBLE : View.GONE);
        if (sv3 != null) sv3.setVisibility(level == 2 && !rows3.isEmpty() ? View.VISIBLE : View.GONE);
        if (sv2 != null) sv2.setVisibility(level >= 1 ? View.VISIBLE : View.GONE);
        if (menuHost != null) menuHost.animate().alpha(1f).setDuration(120).start();
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
            bgSel = U.roundStroke(0xFF1C1C1E, U.dp(act, 6), U.dp(act, 0), 0x00FFFFFF);
            bgParent = U.round(0xFF2C2C2E, U.dp(act, 6));
            bgNone = U.round(0x00000000, U.dp(act, 8));
        }
        int n = rows.size();
        for (int i = 0; i < n; i++) {
            View r = rows.get(i);
            boolean cur = level == lv && i == sel[lv];
            boolean parent = level > lv && i == sel[lv];
            if (cur) r.setBackground(bgSel);
            else if (parent) r.setBackground(bgParent);
            else r.setBackground(bgNone);
            r.setAlpha(cur ? 1f : 0.62f);
            setRowTextColor(r, cur);
        }
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
            if (hasThird(actAt(1, sel[1]))) rebuildL3();
            paint();
        } else paint();
    }

    private void onLeftKey() {
        if (level == 0) {
            if (actAt(0, sel[0]) == 13) { showStatsNow(); return; }
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

    private void onRightKey() {
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

    private void onOkKey() {
        if (level == 0) {
            if (actAt(0, sel[0]) == 13) { showStatsNow(); return; }
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

    private void applyChoice() {
        Store st = host.store();
        if (rows3.isEmpty()) return;
        int a2 = rows2.isEmpty() ? -1 : acts2.get(sel[1]).intValue();
        if (a2 == 24) { applySubChoice(sel[2]); return; }
        if (a2 == 27) {
            String[] names = host.embedSubNames();
            if (names == null) names = new String[0];
            if (sel[2] <= 0) {
                host.clearEmbedSub();
                localSubLabel = "";
                toast(L.t("内嵌字幕已关闭"));
            } else {
                int i = sel[2] - 1;
                if (i >= 0 && i < names.length) {
                    host.selectEmbedSub(i);
                    localSubLabel = names[i];
                    toast(L.t("内嵌字幕：") + names[i]);
                }
            }
            level = 1;
            rebuildMenu();
            return;
        }
        if (a2 == 25) { if (st != null) st.subSize(sel[2]); toast(L.t("字幕字号：") + SIZE_NAMES[sel[2]]); overlayRefresh(); }
        else if (a2 == 26) { if (st != null) st.subColor(sel[2]); toast(L.t("字幕颜色：") + COLOR_NAMES[sel[2]]); overlayRefresh(); }
        else if (a2 == 15) { if (st != null) st.maxHeightOpt(sel[2]); toast(L.t("转码分辨率：") + resLabel()); host.restart(); }
        else if (a2 == 16) { if (st != null) st.maxKbpsOpt(sel[2]); toast(L.t("转码码率：") + brLabel()); host.restart(); }
        level = 1;
        rebuildMenu();
    }

    private void change(int a, int dir) {
        Store st = host.store();
        android.util.Log.e("LMBY", "菜单动作 act=" + a + " dir=" + dir);
        int step = dir == 0 ? 1 : dir;
        if (a >= 810 && a <= 812) {
            int kind = a == 810 ? 1 : (a == 811 ? 0 : 2);
            host.switchPlayer(kind);
            return;
        }
        if (a == 14) {
            if (st != null) st.forceTranscode(!st.forceTranscode());
            toast(st != null && st.forceTranscode() ? L.t("已开启服务器转码") : L.t("已切回原码率直连"));
            host.restart();
            rebuildMenu();
            return;
        }
        if (a == 15) {
            int n = Store.MAX_HEIGHTS.length;
            if (st != null) st.maxHeightOpt((st.maxHeightOpt() + step + n) % n);
            toast(L.t("转码分辨率：") + resLabel());
            host.restart();
            rebuildMenu();
            return;
        }
        if (a == 16) {
            int n = Store.MAX_KBPS.length;
            if (st != null) st.maxKbpsOpt((st.maxKbpsOpt() + step + n) % n);
            toast(L.t("转码码率：") + brLabel());
            host.restart();
            rebuildMenu();
            return;
        }
        if (a == 23) {
            if (st != null) st.subOn(!st.subOn());
            boolean onNow = st != null && st.subOn();
            toast(onNow ? L.t("字幕已打开") : L.t("字幕已关闭"));
            // 重新打开时按「内嵌优先、外挂其次」补上一条，保证有字幕的片子一定显示字幕
            if (onNow) {
                subUserPicked = false;
                autoSubTries = 0;
                autoPickSub();
            }
            overlayRefresh();
            rebuildMenu();
            return;
        }
        if (a == 25) {
            int n = SIZE_NAMES.length;
            int v = ((st == null ? 1 : st.subSize()) + step + n) % n;
            if (st != null) st.subSize(v);
            toast(L.t("字幕字号：") + SIZE_NAMES[v]);
            overlayRefresh();
            rebuildMenu();
            return;
        }
        if (a == 26) {
            int n = COLOR_NAMES.length;
            int v = ((st == null ? 0 : st.subColor()) + step + n) % n;
            if (st != null) st.subColor(v);
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
            if (st != null) st.decodeMode(a - 20);
            host.restart();
            rebuildMenu();
            return;
        }
        if (a >= 30 && a <= 33) {
            aspectIdx = a - 30;
            try { host.store().aspect(aspectIdx); } catch (Throwable ignored) { }
            host.applyAspect(aspectIdx);
            toast(L.t("画面：") + aspectNames[aspectIdx]);
            rebuildMenu();
            return;
        }
        if (a >= 40 && a <= 43) {
            if (st != null) st.playMode(a - 40);
            toast(L.t("播放模式：") + MODE_NAMES[playModeIdx()]);
            rebuildMenu();
            return;
        }
        if (a >= 300 && a < 306) {
            host.setSpeed(speeds[a - 300] / 100f);
            speedSet(a - 300);
            toast(L.t("倍速 ") + speedLabel());
            rebuildMenu();
            return;
        }
        if (a >= 500 && a < 600) {
            String[] names = host.audioNames();
            int i = a - 500;
            host.selectAudio(i);
            if (names != null && i < names.length) toast(L.t("音轨：") + names[i]);
            rebuildMenu();
            return;
        }
        if (a >= 600) {
            applySubChoice(a - 600);
        }
    }

    private int speedIdxCache = 2;
    private int speedIdx() { return speedIdxCache; }
    private void speedSet(int i) { speedIdxCache = i; }

    private void applySubChoice(int idx) {
        subUserPicked = true;   // 用户手动选过字幕，自动挑选不再覆盖
        android.util.Log.e("LMBY", "vmenu applySub idx=" + idx + " n=" + subChoices.size()
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
        // 选外挂 → 关掉内嵌字幕轨，避免两层叠在一起
        host.clearEmbedSub();
        localSubLabel = "";
        attachRemote(st, false);
        level = 1;
    }

    /** 有没有内嵌字幕（播放器内核解析出的轨） */
    private boolean hasLocalSub() {
        String[] n = host.embedSubNames();
        return n != null && n.length > 0;
    }

    /** 服务器是否报了内嵌字幕（哪怕播放器内核解析不到） */
    private boolean serverHasEmbed() {
        for (int i = 0; i < subs.size(); i++) if (!subs.get(i).external) return true;
        return false;
    }

    /** 一级类目右侧的字幕摘要：优先显示当前生效的那条 */
    private String subSummary() {
        if (localSubLabel != null && !localSubLabel.isEmpty()) return localSubLabel;
        return subLabel;
    }

    private String decodeName() {
        int m = host.store() == null ? 0 : host.store().decodeMode();
        return m == 0 ? L.t("硬件解码") : (m == 1 ? L.t("软件解码") : L.t("系统解码"));
    }

    /** 一级类目右侧显示的当前播放器（App 内置只有一套：ExoPlayer + FFmpeg 兜底） */
    private String engineName() {
        int e = host.store() == null ? 0 : host.store().engine();
        return e == 3 ? L.t("第三方") : "ExoPlayer";
    }

    /** 一级菜单右侧状态：与播放统计面板的「播放器 / 播放方式」保持同一口径 */
    private String methodLabel() {
        String p = "ExoPlayer";
        try {
            String[] rt = host.statsRuntime();
            if (rt != null && rt.length > 0 && rt[0] != null && !rt[0].isEmpty()) p = rt[0];
        } catch (Throwable ignored) { }
        return p + (host.directPlay() ? L.t(" · 直连") : L.t(" · 转码"));
    }

    private String transLabel() {
        Store st = host.store();
        boolean tr = st != null && st.forceTranscode();
        return tr ? (resLabel() + " / " + brLabel()) : L.t("关");
    }

    private String resLabel() {
        return heightLabel(maxHeightIdx());
    }

    private String brLabel() {
        return kbpsLabel(maxKbpsIdx());
    }

    private int playModeIdx() {
        int m = host.store() == null ? 0 : host.store().playMode();
        return m < 0 || m > 3 ? 0 : m;
    }

    /** 模式名：单个文件（电影/单独一集）没有列表，列表类模式统一显示成单片播放 */
    private String modeLabel() {
        int m = playModeIdx();
        if (host.listCount() < 2 && (m == 1 || m == 2)) m = 0;
        return MODE_NAMES[m];
    }

    private int subSizeIdx() {
        int i = host.store() == null ? 1 : host.store().subSize();
        return i < 0 ? 0 : (i > 2 ? 2 : i);
    }

    private int subColorIdx() {
        int i = host.store() == null ? 0 : host.store().subColor();
        return i < 0 ? 0 : (i > 3 ? 3 : i);
    }

    private String subSizeName() { return SIZE_NAMES[subSizeIdx()]; }

    private String subColorName() { return COLOR_NAMES[subColorIdx()]; }

    private String speedLabel() { return (speeds[speedIdx()] / 100f) + "\u00D7"; }

    private String trackName() {
        String[] names = host.audioNames();
        if (names == null || names.length == 0) return L.t("无");
        int cur = host.curAudio();
        if (cur >= 0 && cur < names.length && names[cur] != null)
            return L.t("音轨 ") + (cur + 1) + " · " + names[cur];
        return L.t("音轨 ") + (cur + 1);
    }

    /** 外部（调试入口）触发一次播放统计 */
    public void statsNow() { showStatsNow(); }

    private void showStatsNow() {
        final String iid = host.itemId();
        if (iid == null || iid.isEmpty()) { toast(L.t("没有可用的统计信息")); return; }
        toast(L.t("正在读取播放信息…"));
        final Api.Session ss = host.session();
        new Thread(new Runnable() {
            @Override public void run() {
                final Api.MediaInfo mi = Api.mediaInfo(ss, iid);
                final String[] rt = host.statsRuntime();
                // 真实播放方式 / 转码输出参数以服务器的会话记录为准，两个内核走同一份数据
                final String[] live = Api.liveSession(ss, iid, host.playSessionId());
                act.runOnUiThread(new Runnable() {
                    @Override public void run() { buildStatsPanel(mi, rt, live); }
                });
            }
        }).start();
    }

    /** 复刻官方 Emby「播放统计」：播放信息 / 流媒体信息 / 原始媒体信息 三段 */
    private void buildStatsPanel(Api.MediaInfo mi, String[] rt, String[] live) {
        boolean tr = !host.directPlay();
        String pn = rt != null && rt.length > 0 && rt[0] != null && !rt[0].isEmpty() ? rt[0] : "ExoPlayer";
        String hdr = rt != null && rt.length > 1 && rt[1] != null ? rt[1] : "";
        int rw = rt != null && rt.length > 2 ? toInt(rt[2]) : 0;
        int rh = rt != null && rt.length > 3 ? toInt(rt[3]) : 0;
        String vcRun = rt != null && rt.length > 4 && rt[4] != null ? rt[4] : "";
        String acRun = rt != null && rt.length > 5 && rt[5] != null ? rt[5] : "";
        long vbrRun = rt != null && rt.length > 6 ? toLong(rt[6]) : 0;

        // 服务器记录的真实播放方式：DirectPlay / DirectStream / Transcode
        String pm = "";
        int lw = 0, lh = 0;
        long lbr = 0;
        String lvc = "", lac = "";
        if (live != null) {
            pm = live[0] == null ? "" : live[0];
            lw = toInt(live[1]);
            lh = toInt(live[2]);
            lbr = toLong(live[3]);
            lvc = live[4] == null ? "" : live[4];
            lac = live[5] == null ? "" : live[5];
        }
        if (pm.isEmpty()) pm = tr ? "Transcode" : "DirectPlay";
        // 本机请求的是转码流就以转码为准：Emby 有时不记录 TranscodingInfo，不能因此显示成直连
        boolean tc = tr || "Transcode".equals(pm);
        if (tc && !"Transcode".equals(pm)) pm = "Transcode";

        Store st = host.store();
        int w = lw, h = lh;
        // 没有转码信息时，用播放器实际请求的档位（分辨率上限 / 码率上限 / 目标编码器）
        if (tc && w <= 0 && st != null) {
            int mh = st.maxHeightPx();
            if (mh > 0 && mi.width > 0 && mi.height > 0) {
                h = mh;
                w = (int) (mh * (mi.width / (double) mi.height));
            }
            if (lvc.isEmpty()) lvc = "h264";
            if (lac.isEmpty()) lac = "aac";
            if (lbr <= 0 && st.maxKbps() > 0) lbr = st.maxKbps() * 1000L;
        }
        if (w <= 0) { w = rw; h = rh; }
        String res = (w > 0 && h > 0) ? (w + "x" + h)
                : (mi.width > 0 ? (mi.width + "x" + mi.height) : "?");
        String vcUse = !lvc.isEmpty() ? lvc : (!vcRun.isEmpty() ? vcRun : mi.videoCodec);
        String acUse = !lac.isEmpty() ? lac : (!acRun.isEmpty() ? acRun : mi.audioCodec);
        String kw = tc ? " (transcode)"
                : ("DirectStream".equals(pm) ? " (direct stream)" : " (direct)");
        long brUse = lbr > 0 ? lbr : vbrRun;

        host.showStats(new String[][]{
                {L.t("播放信息"),
                        L.t("播放器"), pn,
                        L.t("HDR 支持"), hdr.isEmpty() ? L.t("无") : hdr,
                        L.t("播放方式"), pm,
                        L.t("流媒体类型"), "HTTP"},
                {L.t("流媒体信息"),
                        L.t("视频分辨率"), res,
                        L.t("视频编解码器"), codecUp(vcUse) + kw,
                        L.t("比特率"), brUse > 0 ? bitRateStr(brUse) : (tc ? L.t("自动") : bitRateStr(mi.totalBitrate)),
                        L.t("音频编解码器"), codecUp(acUse) + kw},
                {L.t("原始媒体信息"),
                        L.t("媒体容器"), mi.container.isEmpty() ? "?" : mi.container,
                        L.t("视频编解码器"), codecUp(mi.videoCodec),
                        L.t("视频比特率"), bitRateStr(mi.videoBitrate),
                        L.t("帧率"), fpsStr(mi.fps),
                        L.t("音频编解码器"), codecUp(mi.audioCodec),
                        L.t("音频比特率"), bitRateStr(mi.audioBitrate),
                        L.t("音频频道"), mi.channels.isEmpty() ? "?" : mi.channels}
        });
    }

    private static int toInt(String s) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return 0; }
    }

    private static long toLong(String s) {
        try { return Long.parseLong(s.trim()); } catch (Exception e) { return 0; }
    }

    private static String bitRateStr(long bps) {
        if (bps <= 0) return "?";
        if (bps >= 1000000L) return String.format(java.util.Locale.US, "%.2f Mbps", bps / 1000000.0);
        return String.format(java.util.Locale.US, "%.2f Kbps", bps / 1000.0);
    }

    /** 帧率统一保留两位小数 */
    static String fpsStr(String fps) {
        if (fps == null || fps.isEmpty()) return "?";
        try {
            return String.format(java.util.Locale.US, "%.2f fps", Double.parseDouble(fps));
        } catch (Exception e) {
            return fps + " fps";
        }
    }

    static String codecUp(String c) {
        if (c == null || c.isEmpty()) return "?";
        String l = c.toLowerCase();
        if (l.equals("hevc") || l.equals("h265")) return "HEVC";
        if (l.equals("h264") || l.equals("avc")) return "H.264";
        if (l.equals("av1")) return "AV1";
        if (l.equals("vp9")) return "VP9";
        if (l.equals("mpeg2video")) return "MPEG-2";
        if (l.equals("ac3")) return "AC-3";
        if (l.equals("eac3")) return "E-AC-3";
        if (l.equals("truehd")) return "TrueHD";
        if (l.equals("dts")) return "DTS";
        if (l.equals("mp3")) return "MP3";
        if (l.equals("flac")) return "FLAC";
        if (l.equals("opus")) return "Opus";
        return c.toUpperCase();
    }

    private static String fmt(long ms) {
        if (ms < 0) ms = 0;
        long s = ms / 1000;
        long h = s / 3600, m = (s % 3600) / 60, sec = s % 60;
        if (h > 0) return String.format("%d:%02d:%02d", h, m, sec);
        return String.format("%02d:%02d", m, sec);
    }

    private void toast(String t) { host.toast(t); }

    // ==================== 按键 ====================

    /** 菜单打开时接管方向键；返回 true 表示已消费 */
    public boolean onKey(int kc, int action) {
        if (!menuOpen || action != KeyEvent.ACTION_DOWN) return false;
        if (kc == KeyEvent.KEYCODE_DPAD_UP) { moveSel(-1); return true; }
        if (kc == KeyEvent.KEYCODE_DPAD_DOWN) { moveSel(1); return true; }
        if (kc == KeyEvent.KEYCODE_DPAD_LEFT) { onLeftKey(); return true; }
        if (kc == KeyEvent.KEYCODE_DPAD_RIGHT) { onRightKey(); return true; }
        if (kc == KeyEvent.KEYCODE_DPAD_CENTER || kc == KeyEvent.KEYCODE_ENTER) { onOkKey(); return true; }
        if (kc == KeyEvent.KEYCODE_BACK || kc == KeyEvent.KEYCODE_MENU) { goBack(); return true; }
        return false;
    }

    // ==================== 字幕 ====================

    public void loadSubtitlesAsync() {
        final String itemId = host.itemId();
        if (itemId == null || itemId.isEmpty()) return;
        final Api.Session ss = host.session();
        new Thread(new Runnable() {
            @Override public void run() {
                final java.util.List<Api.SubStream> got = Api.subtitleStreams(ss, itemId);
                try {
                    Api.Item d = Api.detail(ss, itemId);
                    if (d != null && d.name != null) mediaName = d.name;
                } catch (Exception ignored) { }
                act.runOnUiThread(new Runnable() {
                    @Override public void run() {
                        subs = got;
                        android.util.Log.e("LMBY", "vmenu subs n=" + subs.size());
                        for (int k = 0; k < subs.size(); k++) {
                            Api.SubStream q = subs.get(k);
                            android.util.Log.e("LMBY", "  vsub idx=" + q.index + " ext=" + q.external
                                    + " codec=" + q.codec + " title=" + q.title
                                    + " url=" + (q.url == null || q.url.isEmpty() ? "EMPTY" : "OK"));
                        }
                        // 字幕开关开：优先内嵌字幕，其次外挂字幕。等 1.6 秒让内核把内嵌轨报上来再决定
                        if (host.store() != null && !host.store().subOn()) {
                            android.util.Log.e("LMBY", "字幕开关关着，不自动加载字幕");
                            return;
                        }
                        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(new Runnable() {
                            @Override public void run() { autoPickSub(); }
                        }, 1600);
                    }
                });
            }
        }).start();
    }

    /** 字幕自动加载口径（两个内核共用）：开关开就一定给字幕 → 先内嵌，内核解析不到就请服务器把内嵌导成文本，最后用外挂 */
    private void autoPickSub() {
        if (subUserPicked) return;
        if (host.store() != null && !host.store().subOn()) return;
        String[] loc = null;
        try { loc = host.embedSubNames(); } catch (Throwable ignored) { }
        if (loc != null && loc.length > 0) {
            try {
                host.selectEmbedSub(0);
                // 切到内嵌轨后清掉外挂/导出文本层，避免两层字幕叠在一起
                parseSrt("");
                overlayRefresh();
                localSubLabel = loc[0];
                subLabel = "";
            } catch (Throwable ignored) { }
            autoSubTries = 0;
            autoSubTimerOn = false;
            android.util.Log.e("LMBY", "自动字幕：内嵌轨 " + loc[0]);
            return;
        }
        // 内核还没把内嵌字幕轨报上来：等一会儿再判断，别急着去挂外挂（否则会闪一下再切回内嵌）
        if (autoSubTries < 4) {
            if (!autoSubTimerOn) {
                autoSubTimerOn = true;
                autoSubTries++;
                android.util.Log.e("LMBY", "自动字幕：内嵌轨还没就绪，第 " + autoSubTries + " 次重试");
                new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(new Runnable() {
                    @Override public void run() { autoSubTimerOn = false; autoPickSub(); }
                }, 800);
            }
            return;
        }
        autoSubTries = 0;
        autoSubTimerOn = false;
        if (subUserPicked) return;
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

    private void attachRemote(final Api.SubStream st, final boolean auto) {
        new Thread(new Runnable() {
            @Override public void run() {
                final String text = Api.downloadSubtitle(st.url);
                android.util.Log.e("LMBY", "vmenu sub dl title=" + st.title + " ext=" + st.external
                        + " len=" + (text == null ? -1 : text.length()));
                act.runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (text == null || text.trim().isEmpty()) {
                            if (!auto) toast(st.external ? L.t("字幕下载失败")
                                    : L.t("这条内嵌字幕服务器导不出来（EIA-608 / PGS 图形字幕）"));
                            return;
                        }
                        // 用文本层渲染时先把内核的内嵌字幕轨关掉，否则内嵌 + 文本两层字幕会同时出现
                        try { host.clearEmbedSub(); } catch (Throwable ignored) { }
                        parseSrt(stripTags(toPlainSrt(text)));
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
                String fixed = ln.replace('.', ',');
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

    private String stripTags(String t) {
        if (t == null) return "";
        String r = t.replaceAll("<[^>]*>", "");
        r = r.replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">");
        return r;
    }

    public void overlayRefresh() {
        if (subView == null) return;
        Store st = host.store();
        boolean on = st == null || st.subOn();
        int si = subSizeIdx();
        subView.setTextSize(SUB_TEXT_SIZES[si]);
        int ci = subColorIdx();
        subView.setTextColor(U.txt(COLOR_VALUES[ci] | 0xFF000000));
        subView.setShadowLayer(6, 0, 2, 0xCC000000);
        if (!on || cueText.isEmpty()) { subView.setVisibility(View.GONE); cueShown = ""; return; }
        tickSub();
    }

    public void tickSub() {
        if (subView == null) return;
        Store st = host.store();
        if (st != null && !st.subOn()) { subView.setVisibility(View.GONE); cueShown = ""; return; }
        if (cueText.isEmpty()) { subView.setVisibility(View.GONE); return; }
        long t = host.posMs() - subDelayUs / 1000L;
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

    public void hideSubView() {
        if (subView != null) subView.setVisibility(View.GONE);
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
}
