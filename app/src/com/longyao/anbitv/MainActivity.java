package com.longyao.anbitv;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/** 主界面：登录 → 媒体库 → 库内全部视频 / 文件夹 */
public class MainActivity extends Activity {

    private FrameLayout root;
    private Store store;
    private Api.Session session;
    private List<Api.Lib> libs = new ArrayList<Api.Lib>();
    private View settingsOverlay;
    /** 设置面板里第一个可聚焦的胶囊，打开面板时把焦点放这里 */
    private TextView settingsFirstChip;
    private boolean busy;

    private String libId, libName, folderId, folderName;
    private boolean folderMode;
    /** 当前媒体库类型（photos = 照片库，显示"照片"入口） */
    private String libType = "";
    /** 照片墙模式：在同一页面看这个库的全部图片 */
    private boolean photoWall = false;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        L.init(this);
        Crash.install(getApplicationContext());
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        store = new Store(this);
        store.secure(false);
        Img.init(this);
        if (store.uiTheme() > 1) store.uiTheme(0);   // 潮牌已下线，老设置回落到玻璃
        U.applyTheme(store.uiTheme());
        U.applyRing(store.ring());
        U.RING_STYLE = store.ringStyle();
        U.FOCUS_ZOOM = store.zoom();
        Api.DIRECT_ONLY = store.directOnly();
        U.SCROLL_ANIM = 1;   // 翻页曲线固定成官方列表同款，设置里不再暴露
        if (store.bg() > 32) store.bg(0);   // 背景图编号超出内置范围（老版本残留）就回退「原生」
        U.applyFont(this, store.font());
        U.applyTxt(store.txtColor());
        U.prepareBg(this, store.bg());
        applySecure();
        root = new FrameLayout(this);
        // 根背景用一层柔和渐变，页面用半透明底色叠在上面，和播放器菜单的玻璃质感一致
        root.setBackground(U.bgDraw());
        setContentView(root);
        // 调试入口：adb am start --ez openSettings true 直接打设置面板
        if (getIntent() != null && getIntent().getBooleanExtra("openSettings", false)) {
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(new Runnable() {
                @Override public void run() { if (!isFinishing()) openSettings(); }
            }, 1500);
        }
        if (getIntent() != null && getIntent().getBooleanExtra("openDonate", false)) {
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(new Runnable() {
                @Override public void run() { if (!isFinishing()) openDonate(); }
            }, 1500);
        }
        if (getIntent() != null && getIntent().getBooleanExtra("selectserver", false)) {
            showSelectServer();
            return;
        }
        // 调试入口：adb am start --ez openLogin true 直接打手动输入登录页
        if (getIntent() != null && getIntent().getBooleanExtra("openLogin", false)) {
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(new Runnable() {
                @Override public void run() {
                    if (!isFinishing()) showLogin(store.server(), store.user());
                }
            }, 1200);
        }
        // 调试入口：--ez openSeries true 自动打开一个剧集详情页（第一个剧集库的最新一部剧）
        if (getIntent() != null && getIntent().getBooleanExtra("openSeries", false)) {
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(new Runnable() {
                @Override public void run() { openFirstSeriesForTest(); }
            }, 3500);
            if (getIntent().getBooleanExtra("epJump", false)) {
                new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(new Runnable() {
                    @Override public void run() { showEpisodeJump(); }
                }, 7500);
            }
        }
        // 调试入口：--ez openLib true 直接进第一个媒体库；配合 --ez sortPanel / thumbPanel 打开对应下拉
        if (getIntent() != null && getIntent().getBooleanExtra("openLib", false)) {
            final android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
            h.postDelayed(new Runnable() {
                int tries = 0;
                @Override public void run() {
                    if (libs.isEmpty()) {
                        if (tries++ < 8) h.postDelayed(this, 900);
                        return;
                    }
                    Api.Lib l = libs.get(0);
                    String wantLib = getIntent().getStringExtra("libId");
                    if (wantLib != null && wantLib.length() > 0) {
                        for (Api.Lib x : libs) {
                            if (wantLib.equals(x.id)) { l = x; break; }
                        }
                    }
                    openLibrary(l.id, l.name, null, null, l.type);
                    h.postDelayed(new Runnable() {
                        @Override public void run() {
                            if (getIntent().getBooleanExtra("sortPanel", false)) openLibSortPanel();
                            else if (getIntent().getBooleanExtra("thumbPanel", false)) openLibThumbPanel();
                        }
                    }, 2600);
                }
            }, 3500);
        }
        if (store.lastUser().length() > 0) {
            // 设置里关掉「启动密码」时，直接用已保存的登录凭证进首页；
            // 刚从第三方播放器回来（进程可能被系统回收后重建）也不弹，否则看个片回来就要重输一遍
            if (store.askPassword() && !store.consumeExternalPlay() && !getIntent().getBooleanExtra("noPwd", false)) showQuickLogin();
            else autoLogin();
        } else showSelectServer();

        // 调试用：--es autoPhoto "photoId|倍数" 自动开图并缩放到指定倍数，便于 adb 验证区域解码清晰度
        String ap = getIntent() == null ? null : getIntent().getStringExtra("autoPhoto");
        if (ap != null) {
            String[] parts = ap.split("\\|");
            final String pid = parts[0];
            final float z = parts.length > 1 ? Float.parseFloat(parts[1]) : 8f;
            final Runnable[] openR = new Runnable[1];
            final int[] tries = {0};
            openR[0] = new Runnable() {
                @Override public void run() {
                    if (session == null) {
                        if (tries[0]++ < 6) { root.postDelayed(openR[0], 1000); return; }
                        // 调试兜底：停在登录页时用 --es dbgServer/dbgToken/dbgUserId 传入的凭证造会话，只为验证看图
                        Api.Session s = dbgSession();
                        if (s == null) { android.util.Log.e("LMBY", "自动看图 没有调试凭证"); return; }
                        session = s;
                        android.util.Log.e("LMBY", "自动看图兜底会话已建立");
                    }
                    Api.Item it = new Api.Item();
                    it.id = pid;
                    it.name = "test";
                    it.type = "Photo";
                    it.imgTag = "";
                    openPhoto(it);
                    root.postDelayed(new Runnable() {
                        @Override public void run() {
                            if (photoPv == null) {
                                android.util.Log.e("LMBY", "自动看图失败 photoPv=null");
                                return;
                            }
                            photoZoom = z;
                            photoFullUrl = Api.imageUrl(session, pid, "", 0);
                            ensureFullPhoto();
                            photoPv.setZoom(z);
                            int[] sz = photoPv.sourceSize();
                            android.util.Log.e("LMBY", "自动看图 源=" + sz[0] + "x" + sz[1]
                                    + L.t(" 倍数=") + photoPv.zoom());
                        }
                    }, 8000);
                }
            };
            root.postDelayed(openR[0], 3000);
        }

        // 调试用：--es autoPhotoWall "库id|库类型|名字" 自动进库并打开照片墙，验证图片入口
        String aw = getIntent() == null ? null : getIntent().getStringExtra("autoPhotoWall");
        if (aw != null) {
            final String[] wp = aw.split("\\|");
            final String lid = wp.length > 0 ? wp[0] : "";
            final String ltype = wp.length > 1 ? wp[1] : "homevideos";
            final String lname = wp.length > 2 ? wp[2] : L.t("照片");
            final Runnable[] wr = new Runnable[1];
            final int[] wt = {0};
            wr[0] = new Runnable() {
                @Override public void run() {
                    if (session == null) {
                        if (wt[0]++ < 6) { root.postDelayed(wr[0], 1000); return; }
                        Api.Session s = dbgSession();
                        if (s == null) { android.util.Log.e("LMBY", "照片墙 没有调试凭证"); return; }
                        session = s;
                    }
                    openLibrary(lid, lname, null, null, ltype);
                    root.postDelayed(new Runnable() {
                        @Override public void run() {
                            photoWall = true;
                            loadItems();
                            android.util.Log.e("LMBY", "照片墙 库=" + lname + " 类型=" + libType + " 入口按钮="
                                    + (("photos".equals(libType) || "homevideos".equals(libType)) ? "有" : "无"));
                            root.postDelayed(new Runnable() {
                                @Override public void run() {
                                    android.util.Log.e("LMBY", "照片墙 数量="
                                            + (itemsAll == null ? -1 : itemsAll.size()));
                                }
                            }, 6000);
                        }
                    }, 2500);
                }
            };
            root.postDelayed(wr[0], 3000);
        }

        // 调试用：--es serverScan 1 直接进服务器选择页并触发局域网扫描
        if (getIntent() != null && getIntent().hasExtra("serverScan")) {
            android.util.Log.e("LMBY", "调试 进入服务器扫描页");
            root.postDelayed(new Runnable() {
                @Override public void run() { showSelectServer(); }
            }, 1500);
        }

        // 调试用：--ez openQuick true 直接进「快捷登录」页
        if (getIntent() != null && getIntent().getBooleanExtra("openQuick", false)) {
            root.postDelayed(new Runnable() {
                @Override public void run() {
                    showPasswordDialog(store.lastServer(), store.lastUser(),
                            store.lastName().length() > 0 ? store.lastName() : store.lastUser());
                }
            }, 1200);
        }

        // 调试用：--ei uiTheme 0/1 直接切主题（0 玻璃 1 黑绿）
        if (getIntent() != null && getIntent().hasExtra("uiTheme")) {
            int dt = getIntent().getIntExtra("uiTheme", 0);
            if (dt > 1) dt = 0;
            store.uiTheme(dt);
            U.applyTheme(dt);
            U.applyRing(store.ring());
            if (root != null) root.setBackground(U.bgDraw());
            android.util.Log.e("LMBY", "调试 切主题=" + dt);
        }
    }

    @Override protected void onResume() {
        super.onResume();
        // 从桌面/别的 App 切回来（不是从播放器返回）：按启动密码设置重新验证一次
        if (wentBackground) {
            wentBackground = false;
            // 从第三方播放器回来的这一次不算「离开 App」，不重新验证（用户看片途中不该被要求输密码）
            boolean fromExternal = store.consumeExternalPlay();
            if (!fromExternal && store.askPassword() && session != null && root != null) {
                android.util.Log.e("LMBY", "后台返回 要求重新登录");
                if (photoOverlay != null) closePhoto();
                session = null;
                if (store.lastServer() != null && !store.lastServer().isEmpty()) showQuickLogin();
                else showSelectServer();
                return;
            }
        }
        if (resumeSearchOnReturn) {
            resumeSearchOnReturn = false;
            final String q = getSharedPreferences("lmby", 0).getString("sq", "");
            if (q != null && !q.isEmpty() && root != null) {
                root.postDelayed(new Runnable() {
                    @Override public void run() {
                        if (searchOverlay != null) return;
                        searchRestoreQuery = q;
                        openSearch();
                    }
                }, 250);
            }
        }
        // 从桌面（Home 键）切回来时，登录页要重新拿到焦点，否则方向键/确认键像"没反应"
        if (inQuickLogin && root != null) {
            if (root.getChildCount() == 0) {
                showQuickLogin();
                return;
            }
            root.postDelayed(new Runnable() {
                @Override public void run() {
                    if (root == null || !inQuickLogin) return;
                    for (int i = 0; i < root.getChildCount(); i++) {
                        View c = root.getChildAt(i);
                        if (c instanceof ViewGroup) {
                            ((ViewGroup) c).setDescendantFocusability(ViewGroup.FOCUS_BEFORE_DESCENDANTS);
                        }
                    }
                    if (getCurrentFocus() == null) {
                        if (quickPass != null && quickPass.isShown()) quickPass.requestFocus();
                        else if (quickGo != null) quickGo.requestFocus();
                    }
                }
            }, 350);
        }
        // 从播放器返回：详情页的「已播放」勾、选集卡的 √ / 百分比要立刻反映出来（服务器上报是异步的，补刷一次）
        if (detailOverlay != null && detailItem != null && root != null) {
            root.postDelayed(new Runnable() {
                @Override public void run() { refreshDetailWatchState(); }
            }, 500);
            root.postDelayed(new Runnable() {
                @Override public void run() { refreshDetailWatchState(); }
            }, 1800);
        }
        // 从播放器回到首页：最近观看那行的缩略图右上角进度立刻按服务器最新进度重算
        if (backFromPlayer) {
            backFromPlayer = false;
            final LinearLayout rr = homeRecRow;
            final TextView rh = homeRecHint;
            final int rw = homeRecW, rhh = homeRecH;
            if (detailOverlay == null && rr != null && rh != null && root != null
                    && session != null && store.showRecent() && rw > 0) {
                root.postDelayed(new Runnable() {
                    @Override public void run() {
                        if (homeRecRow == rr && rr.getWindowToken() != null)
                            loadRecent(rr, rh, rw, rhh);
                    }
                }, 500);
                root.postDelayed(new Runnable() {
                    @Override public void run() {
                        if (homeRecRow == rr && rr.getWindowToken() != null)
                            loadRecent(rr, rh, rw, rhh);
                    }
                }, 1800);
            }
        }
    }

    /** 播放开始时就本地点亮详情页的已播放标识，不等服务器回包 */
    private void markDetailPlaying(Api.Item played) {
        if (detailOverlay == null || detailItem == null || played == null) return;
        if (!"Series".equals(played.type)) {
            if (played.id != null && played.id.equals(detailItem.id) && detailWatchBtn != null) {
                detailWatchBtn.setActive(true);
            }
            return;
        }
        // 剧集：这一集的卡片清掉旧进度（刚开始播还没有进度百分比），这部剧的对勾也点亮
        if (detailEpRow != null) {
            for (int i = 0; i < detailEpRow.getChildCount(); i++) {
                View c = detailEpRow.getChildAt(i);
                if (c instanceof Card && played.id != null && played.id.equals(((Card) c).key)) {
                    ((Card) c).setBadge(null);
                    ((Card) c).setProgress(0);
                    ((Card) c).setContinue(null);
                    break;
                }
            }
        }
        if (detailWatchBtn != null) detailWatchBtn.setActive(true);
    }

    /** 从播放器回来：拿服务器最新的 UserData 刷新勾和选集卡状态 */
    private void refreshDetailWatchState() {
        final Api.Item it = detailItem;
        if (it == null || session == null || detailOverlay == null) return;
        final boolean series = "Series".equals(it.type);
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    final Api.Item fresh = Api.detail(session, it.id);
                    final java.util.List<Api.Item> eps = series ? Api.episodes(session, it.id) : null;
                    String nu = "";
                    if (series) {
                        try {
                            java.util.List<Api.Item> nn = Api.nextUp(session, it.id);
                            if (nn != null && !nn.isEmpty() && nn.get(0).id != null) nu = nn.get(0).id;
                        } catch (Throwable ignored) { }
                    }
                    final String fnu = nu;
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            if (detailOverlay == null || detailItem == null
                                    || !detailItem.id.equals(it.id)) return;
                            if (series) nextUpEpId = fnu;
                            if (fresh != null) {
                                it.played = fresh.played;
                                if (detailWatchBtn != null) detailWatchBtn.setActive(fresh.played);
                            }
                            if (!series) {
                                // 电影：退出播放器立刻把「续播 mm:ss」点出来（位置全部取服务器 UserData）
                                long pos = fresh == null ? 0 : (fresh.resumeMs > 3000 ? fresh.resumeMs
                                        : (fresh.duration > 0 && fresh.progress > 1 && fresh.progress < 99
                                        ? (long) (fresh.duration * (fresh.progress / 100.0)) : 0));
                                it.resumeMs = pos;
                                syncMovieResume(it, pos);
                            }
                            if (eps != null) {
                                updateEpisodeBadges(eps, it);
                                // 剧集：按最新的「看到一半」那集把续播按钮同步出来
                                Api.Item re = findResumeEpisode(eps);
                                if (re != null) showDetailResume(re);
                                else if (detailResumeBtn != null) detailResumeBtn.setVisibility(View.GONE);
                            }
                        }
                    });
                } catch (Throwable ignored) { }
            }
        }).start();
    }

    private void updateEpisodeBadges(java.util.List<Api.Item> eps, Api.Item seriesItem) {
        if (detailEpRow == null || eps == null) return;
        Api.Item cont = findResumeEpisode(eps);   // 播放器退出回来：继续播放提示要跟着最新那集走
        Card contCard = null;
        for (int i = 0; i < detailEpRow.getChildCount(); i++) {
            View c = detailEpRow.getChildAt(i);
            if (!(c instanceof Card)) continue;
            Card card = (Card) c;
            card.setContinue(null);
            for (int k = 0; k < eps.size(); k++) {
                Api.Item e = eps.get(k);
                if (e.id == null || !e.id.equals(card.key)) continue;
                // 跟媒体库同一个口径：右上角只显示播放进度百分比
                applyPctBadge(card, e);
                if (cont != null && cont.id != null && cont.id.equals(e.id)) {
                    int p = pctOf(e);
                    card.setContinue(L.t("继续播放") + (p > 0 ? "  " + p + "%" : ""));
                    contCard = card;
                }
                break;
            }
        }
        if (contCard != null) scrollEpRowTo(contCard);
    }

    /** 只有真的离开 App（按 Home / 切别的应用）才标记，进播放器不算。
     *  必须放在 onStop 判断：onPause 时播放器窗口还没建起来，会被误判成"切出去了"。 */
    @Override protected void onStop() {
        super.onStop();
        if (PlayerActivity.alive == 0 && SysPlayerActivity.alive == 0) {
            wentBackground = true;
            // 只有内存真的吃紧才放位图。之前每次切后台都全清，回来首页整页重新解码，
            // 表现就是"返回后白图一片、上下翻很卡"。磁盘缓存一直在，真被系统回收也只是重解码。
            if (Img.memTight()) Img.trimMemory();
        }
    }

    /** 防窥：最近任务预览 / 系统截屏里不显示 App 画面 */
    private void applySecure() {        if (store.secure()) getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
    }

    // ================= 登录 =================
    /** 遥控器方向键在输入框之间切换焦点（EditText 默认会吞掉上下键） */
    private void chainFocus(final View a, final View b, final View c, final View d) {
        hookKeys(a, b, null);
        hookKeys(b, c, a);
        if (c != null) hookKeys(c, d, b);
        if (d != null) hookKeys(d, null, c);
    }

    private void hookKeys(final View v, final View down, final View up) {
        v.setOnKeyListener(new View.OnKeyListener() {
            @Override public boolean onKey(View view, int code, KeyEvent e) {
                if (e.getAction() != KeyEvent.ACTION_DOWN) return false;
                if (code == KeyEvent.KEYCODE_DPAD_DOWN && down != null) { down.requestFocus(); return true; }
                if (code == KeyEvent.KEYCODE_DPAD_UP && up != null) { up.requestFocus(); return true; }
                return false;
            }
        });
    }

    private EditText mkField(LinearLayout parent, String label, String value, boolean pwd) {
        TextView l = U.tv(this, label, 13, U.DIM, false);
        LinearLayout.LayoutParams lp = U.lp(-1, -2);
        lp.topMargin = U.dp(this, 11);
        parent.addView(l, lp);

        EditText e = new EditText(this);
        e.setText(value == null ? "" : value);
        e.setTextColor(U.txt(U.TXT));
        e.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        e.setSingleLine(true);
        e.setBackground(U.round(0xFF20262D, U.dp(this, 6)));
        e.setPadding(U.dp(this, 14), U.dp(this, 7), U.dp(this, 14), U.dp(this, 7));
        if (pwd) e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        LinearLayout.LayoutParams ep = U.lp(-1, -2);
        ep.topMargin = U.dp(this, 6);
        parent.addView(e, ep);
        return e;
    }

    private java.io.File avatarFile() {
        return new java.io.File(getFilesDir(), "avatar.png");
    }

    private void saveAvatar(String server, String uid, String token) {
        try {
            String url = server + "/Users/" + uid + "/Images/Primary?maxHeight=320&quality=90";
            String h = "MediaBrowser Client=\"LMBY\", Device=\"AndroidTV\", DeviceId=\""
                    + store.deviceId() + "\", Version=\"1.0.0\", Token=\"" + token + "\"";
            Api.downloadFile(url, h, avatarFile());
        } catch (Exception ignored) { }
    }


    /** 原生 Emby 起始页上的白色小图标：服务器机箱 / 铅笔 / 链条 */
    static class IconTile extends View {
        static final int SERVER = 0, PENCIL = 1, LINK = 2;
        private final int kind;
        private final android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);

        IconTile(android.content.Context c, int kind) { super(c); this.kind = kind; }

        @Override protected void onDraw(android.graphics.Canvas cv) {
            float w = getWidth(), h = getHeight();
            float s = Math.min(w, h) * 0.52f;
            float cx = w / 2f, cy = h / 2f;
            p.setStyle(android.graphics.Paint.Style.FILL);
            p.setColor(U.TXT);
            if (kind == SERVER) {
                android.graphics.RectF rf = new android.graphics.RectF(cx - s * 0.52f, cy - s * 0.72f,
                        cx + s * 0.52f, cy + s * 0.72f);
                cv.drawRoundRect(rf, s * 0.06f, s * 0.06f, p);
                p.setColor(U.LIGHT_BG ? 0xFFF3F4F6 : 0xFF1B1B1B);
                for (int i = 0; i < 3; i++) {
                    float y = rf.top + s * 0.16f + i * s * 0.26f;
                    cv.drawRect(rf.left + s * 0.12f, y, rf.right - s * 0.12f, y + s * 0.09f, p);
                }
                cv.drawCircle(cx + s * 0.30f, rf.bottom - s * 0.18f, s * 0.055f, p);
            } else if (kind == PENCIL) {
                cv.save();
                cv.rotate(-45f, cx, cy);
                p.setColor(U.TXT);
                android.graphics.RectF rf = new android.graphics.RectF(cx - s * 0.16f, cy - s * 0.52f,
                        cx + s * 0.16f, cy + s * 0.20f);
                cv.drawRoundRect(rf, s * 0.05f, s * 0.05f, p);
                android.graphics.Path tip = new android.graphics.Path();
                tip.moveTo(cx - s * 0.16f, cy + s * 0.22f);
                tip.lineTo(cx + s * 0.16f, cy + s * 0.22f);
                tip.lineTo(cx, cy + s * 0.52f);
                tip.close();
                cv.drawPath(tip, p);
                cv.restore();
            } else {
                p.setStyle(android.graphics.Paint.Style.STROKE);
                p.setStrokeWidth(s * 0.13f);
                cv.save();
                cv.rotate(-40f, cx, cy);
                android.graphics.RectF r1 = new android.graphics.RectF(cx - s * 0.62f, cy - s * 0.30f,
                        cx + s * 0.06f, cy + s * 0.30f);
                android.graphics.RectF r2 = new android.graphics.RectF(cx - s * 0.06f, cy - s * 0.30f,
                        cx + s * 0.62f, cy + s * 0.30f);
                cv.drawRoundRect(r1, s * 0.30f, s * 0.30f, p);
                cv.drawRoundRect(r2, s * 0.30f, s * 0.30f, p);
                cv.restore();
            }
        }
    }

    /** 起始页的方块卡片（服务器 / 其它选项），焦点是绿色描边 */
    private View embyTile(int iconKind, int fillColor, String line1, String line2,
                          int wDp, int hDp, int labelSp, View.OnClickListener click) {
        LinearLayout col = U.col(this);
        col.setFocusable(true);
        col.setFocusableInTouchMode(true);

        final LinearLayout box = U.col(this);
        box.setGravity(Gravity.CENTER);
        final int fill = U.LIGHT_BG ? 0x1F000000
                : (U.GLASS ? 0x99FFFFFF : (U.LIGHT ? U.PANEL2 : fillColor));
        box.setBackground(U.roundStroke(fill, U.dp(this, U.RADIUS), U.dp(this, 2),
                U.LIGHT_BG ? 0x33000000 : 0x26FFFFFF));
        IconTile ic = new IconTile(this, iconKind);
        box.addView(ic, new LinearLayout.LayoutParams(-1, -1));

        // 独立的外框层：比方块四周各大 4dp，焦点时用和首页同一套白玻璃环
        final View ring = new RingView(this, U.dp(this, U.RADIUS + 4));
        ring.setVisibility(View.INVISIBLE);
        FrameLayout shell = new FrameLayout(this);
        shell.setPadding(U.dp(this, 4), U.dp(this, 4), U.dp(this, 4), U.dp(this, 4));
        FrameLayout.LayoutParams bfp = new FrameLayout.LayoutParams(
                U.dp(this, wDp), U.dp(this, hDp));
        bfp.gravity = Gravity.CENTER;
        shell.addView(box, bfp);
        shell.addView(ring, new FrameLayout.LayoutParams(-1, -1));
        col.addView(shell, new FrameLayout.LayoutParams(
                U.dp(this, wDp + 8), U.dp(this, hDp + 8)));

        TextView l1 = U.tv(this, line1, labelSp, U.TXT, false);
        l1.setGravity(Gravity.START);
        LinearLayout.LayoutParams lp1 = U.lp(-1, -2);
        lp1.topMargin = U.dp(this, 10);
        col.addView(l1, lp1);
        // 第二行即使为空也占位，保证同一行的方块上下对齐
        TextView l2 = U.tv(this, line2 == null ? "" : line2, labelSp, U.TXT, false);
        l2.setGravity(Gravity.START);
        col.addView(l2, U.lp(-1, -2));

        col.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override public void onFocusChange(View v, boolean has) {
                ring.setVisibility(has ? View.VISIBLE : View.INVISIBLE);
            }
        });
        col.setOnClickListener(click);
        return col;
    }

    /** 起始页：自动扫描局域网 Emby 服务器 + 手动输入 / Connect 登录 */
    void showSelectServer() {
        inQuickLogin = false;
        inLogin = false;
        if (session != null) session = null;
        root.removeAllViews();
        libId = folderId = null;
        busy = false;

        FrameLayout host = new FrameLayout(this);
        host.setBackground(U.bgDraw());

        LinearLayout col = U.col(this);
        col.setPadding(U.dp(this, 34), U.dp(this, 62), U.dp(this, 34), U.dp(this, 16));

        TextView t1 = U.tv(this, L.t("选择一个服务器"), 19, U.TXT, false);
        col.addView(t1, U.lp(-1, -2));

        final LinearLayout srvRow = U.row(this);
        srvRow.setGravity(Gravity.TOP);
        LinearLayout.LayoutParams srl = U.lp(-1, -2);
        srl.topMargin = U.dp(this, 8);
        col.addView(srvRow, srl);

        final TextView hint = U.tv(this, L.t("正在扫描局域网里的 Emby 服务器…"), 13, U.DIM, false);
        srvRow.addView(hint, U.lp(-2, -2));

        TextView t2 = U.tv(this, L.t("其它选项"), 19, U.TXT, false);
        LinearLayout.LayoutParams t2l = U.lp(-1, -2);
        t2l.topMargin = U.dp(this, 34);
        col.addView(t2, t2l);

        LinearLayout tiles = U.row(this);
        tiles.setGravity(Gravity.TOP);
        LinearLayout.LayoutParams tl = U.lp(-1, -2);
        tl.topMargin = U.dp(this, 10);
        col.addView(tiles, tl);

        View manual = embyTile(IconTile.PENCIL, 0xFF5C5CB0, L.t("手动输入"), "", 112, 112, 14,
                new View.OnClickListener() {
                    @Override public void onClick(View v) { showLogin(store.server(), ""); }
                });
        LinearLayout.LayoutParams ml = U.lp(-2, -2);
        ml.rightMargin = U.dp(this, 18);
        tiles.addView(manual, ml);

        View connect = embyTile(IconTile.LINK, 0xFF4A1414, L.t("使用 Emby"), L.t("Connect 登录"), 112, 112, 14,
                new View.OnClickListener() {
                    @Override public void onClick(View v) { showConnectLogin(store.server()); }
                });
        tiles.addView(connect, U.lp(-2, -2));

        host.addView(col, new FrameLayout.LayoutParams(-1, -1));
        root.addView(host, new FrameLayout.LayoutParams(-1, -1));
        manual.requestFocus();

        final java.util.List<String[]> saved = store.savedServers();
        final java.util.List<String[]> shown = new java.util.ArrayList<String[]>();
        if (!saved.isEmpty()) {
            for (String[] sv : saved) {
                shown.add(sv);
                final String addr = sv[1];
                View card = embyTileServer(sv[0], new View.OnClickListener() {
                    @Override public void onClick(View v) { pickUserAndLogin(addr); }
                });
                bindServerMenuKey(card, sv[0], addr);
                LinearLayout.LayoutParams cl = U.lp(-2, -2);
                cl.rightMargin = U.dp(this, 18);
                srvRow.addView(card, cl);
                // 老条目可能只存了地址：后台补回真实服务器名
                final TextView lbl = (TextView) card.getTag();
                final String raw = sv[0];
                if (raw.contains("://") || raw.startsWith("192.168.") || raw.isEmpty()) {
                    final String base = addr;
                    new Thread(new Runnable() {
                        @Override public void run() {
                            final String nm = Api.serverName(base);
                            if (nm == null) return;
                            runOnUiThread(new Runnable() {
                                @Override public void run() {
                                    if (lbl != null) lbl.setText(nm);
                                    store.addServer(nm, base);
                                }
                            });
                        }
                    }).start();
                }
            }
            hint.setVisibility(View.GONE);
            focusFirstServer(srvRow);
        }

        new Thread(new Runnable() {
            @Override public void run() {
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        hint.setText(L.t("正在搜索服务器…"));
                        hint.setVisibility(View.VISIBLE);
                    }
                });
                final java.util.List<String[]> found = new java.util.ArrayList<String[]>();
                java.util.List<String[]> bc = Api.discoverServers();
                if (bc != null) found.addAll(bc);
                final java.util.List<String[]> r1 = new java.util.ArrayList<String[]>(found);
                runOnUiThread(new Runnable() {
                    @Override public void run() { addServerCards(r1, shown, srvRow, hint); }
                });
                // 容器里 / 自建 Emby 常常不响应 UDP 广播，所以网段扫描始终补齐，不能只在广播为空时才扫
                java.util.List<String[]> sc = Api.scanLan();
                if (sc != null) {
                    for (String[] s : sc) {
                        if (s == null || s.length < 2 || s[1] == null) continue;
                        boolean dup = false;
                        for (String[] f : found) if (f[1] != null && f[1].equals(s[1])) dup = true;
                        if (!dup) found.add(s);
                    }
                }
                final java.util.List<String[]> r2 = new java.util.ArrayList<String[]>(found);
                runOnUiThread(new Runnable() {
                    @Override public void run() { addServerCards(r2, shown, srvRow, hint); }
                });
            }
        }).start();
    }

    /** 把搜到的服务器追加到选择页：只在右边加，不重排已有卡片，位置不会左右跳 */
    private void addServerCards(java.util.List<String[]> result, java.util.List<String[]> shown,
                                LinearLayout srvRow, TextView hint) {
        boolean added = false;
        if (result != null) {
            for (String[] sv : result) {
                if (sv == null || sv.length < 2 || sv[1] == null) continue;
                boolean dup = false;
                for (String[] x : shown) {
                    if (x[1] != null && x[1].equals(sv[1])) { dup = true; break; }
                }
                if (dup) continue;
                shown.add(sv);
                store.addServer(sv[0], sv[1]);
                final String addr = sv[1];
                View card = embyTileServer(sv[0], new View.OnClickListener() {
                    @Override public void onClick(View v) { pickUserAndLogin(addr); }
                });
                bindServerMenuKey(card, sv[0], addr);
                LinearLayout.LayoutParams cl = U.lp(-2, -2);
                cl.rightMargin = U.dp(this, 18);
                srvRow.addView(card, cl);
                added = true;
            }
        }
        if (shown.isEmpty()) {
            hint.setText(L.t("没有扫描到服务器，请用「手动输入」"));
            hint.setVisibility(View.VISIBLE);
        } else {
            hint.setVisibility(View.GONE);
            if (added) focusFirstServer(srvRow);
        }
    }

    /** 调试入口用的会话：凭证一律由 intent 传入，仓库里不写任何私有地址或 token */
    private Api.Session dbgSession() {
        if (getIntent() == null) return null;
        String sv = getIntent().getStringExtra("dbgServer");
        String tk = getIntent().getStringExtra("dbgToken");
        String uid = getIntent().getStringExtra("dbgUserId");
        if (sv == null || tk == null || uid == null) return null;
        Api.Session s = new Api.Session();
        s.server = sv;
        s.token = tk;
        s.userId = uid;
        s.deviceId = store.deviceId();
        return s;
    }

    /** 只让第一张服务器卡片吃焦点，避免两个焦点框同时亮 */
    private void focusFirstServer(LinearLayout row) {
        if (row == null) return;
        for (int i = 0; i < row.getChildCount(); i++) {
            View c = row.getChildAt(i);
            if (c != null && c.isFocusable() && c.getVisibility() == View.VISIBLE) {
                c.requestFocus();
                return;
            }
        }
    }

    // ================= 服务器：菜单键 → 修改 / 删除 =================

    /** 服务器卡片上按菜单键：弹出「修改 / 删除」两个选项 */
    private void bindServerMenuKey(View card, final String name, final String addr) {
        if (card == null) return;
        card.setOnKeyListener(new View.OnKeyListener() {
            @Override public boolean onKey(View v, int kc, KeyEvent e) {
                if (e.getAction() != KeyEvent.ACTION_DOWN) return false;
                // 遥控器「菜单」键各家用得不一样，常见的三种都认
                if (kc == KeyEvent.KEYCODE_MENU || kc == KeyEvent.KEYCODE_INFO
                        || kc == KeyEvent.KEYCODE_SETTINGS) {
                    showServerMenu(name, addr);
                    return true;
                }
                return false;
            }
        });
    }

    private FrameLayout serverMenuOverlay;
    private View serverMenuFirst;

    /** v 是不是 root0 自己或者它的子孙 */
    private boolean isInsideView(View root0, View v) {
        while (v != null) {
            if (v == root0) return true;
            android.view.ViewParent p = v.getParent();
            v = (p instanceof View) ? (View) p : null;
        }
        return false;
    }

    private void showServerMenu(final String name, final String addr) {
        if (serverMenuOverlay != null) return;
        FrameLayout overlay = new FrameLayout(this);
        overlay.setFocusable(true);
        overlay.setBackgroundColor(0x66000000);
        overlay.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { closeServerMenu(); }
        });

        LinearLayout panel = U.col(this);
        panel.setBackground(U.EMBY ? U.cyberSheet(this) : U.sheetBg(this));
        panel.setPadding(U.dp(this, 18), U.dp(this, 16), U.dp(this, 18), U.dp(this, 14));
        if (android.os.Build.VERSION.SDK_INT >= 21) panel.setElevation(U.dp(this, 14));
        panel.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { }
        });

        String title = (name == null || name.trim().isEmpty()) ? addr : name.trim();
        panel.addView(U.tv(this, title, 16, U.TXT, true), U.lp(-1, -2));
        TextView sub = U.tv(this, addr == null ? "" : addr, 11, U.DIM, false);
        sub.setMaxLines(1);
        sub.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        LinearLayout.LayoutParams slp = U.lp(-1, -2);
        slp.topMargin = U.dp(this, 3);
        panel.addView(sub, slp);

        TextView edit = menuChip(L.t("修改"));
        TextView del = menuChip(L.t("删除"));
        LinearLayout.LayoutParams elp = U.lp(-1, U.dp(this, 46));
        elp.topMargin = U.dp(this, 14);
        panel.addView(edit, elp);
        LinearLayout.LayoutParams dlp = U.lp(-1, U.dp(this, 46));
        dlp.topMargin = U.dp(this, 6);
        panel.addView(del, dlp);

        edit.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                closeServerMenu();
                // 修改 = 直接进「手动输入」页，地址预填这个服务器，改完点保存即可
                showLogin(addr, "");
            }
        });
        del.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                store.removeServer(addr);
                closeServerMenu();
                showSelectServer();
                try {
                    android.widget.Toast.makeText(MainActivity.this, L.t("已删除") + " "
                            + ((name == null || name.trim().isEmpty()) ? addr : name.trim()),
                            android.widget.Toast.LENGTH_SHORT).show();
                } catch (Throwable ignored) { }
            }
        });

        FrameLayout.LayoutParams pp = new FrameLayout.LayoutParams(U.dp(this, 320), -2);
        pp.gravity = Gravity.CENTER;
        overlay.addView(panel, pp);
        root.addView(overlay, new FrameLayout.LayoutParams(-1, -1));
        serverMenuOverlay = overlay;
        serverMenuFirst = edit;
        blockContentFocus(true);
        edit.requestFocus();
        // 布局还没走完时 requestFocus 会被系统丢掉，延迟再抢一次，保证方向键立刻能用
        panel.postDelayed(new Runnable() {
            @Override public void run() {
                if (serverMenuOverlay != null) edit.requestFocus();
            }
        }, 150);
    }

    private void closeServerMenu() {
        if (serverMenuOverlay == null) return;
        root.removeView(serverMenuOverlay);
        serverMenuOverlay = null;
        serverMenuFirst = null;
        blockContentFocus(false);
        resumeServerFocus();
    }

    /** 面板关掉后把焦点还回页面，免得太空导致遥控器按了没反应 */
    private void resumeServerFocus() {
        if (root == null) return;
        for (int i = 0; i < root.getChildCount(); i++) {
            View c = root.getChildAt(i);
            if (c == null || c == serverMenuOverlay || c == serverEditOverlay) continue;
            if (focusFirstIn(c)) return;
        }
    }

    private FrameLayout serverEditOverlay;

    /** 修改服务器：名称 + 地址，保存后重排选择页（不自动登录） */
    private void showServerEdit(final String oldAddr, String oldName) {
        if (serverEditOverlay != null) return;
        FrameLayout overlay = new FrameLayout(this);
        overlay.setFocusable(true);
        overlay.setBackgroundColor(0x66000000);
        overlay.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { closeServerEdit(); }
        });

        LinearLayout panel = U.col(this);
        panel.setBackground(U.EMBY ? U.cyberSheet(this) : U.sheetBg(this));
        panel.setPadding(U.dp(this, 18), U.dp(this, 16), U.dp(this, 18), U.dp(this, 14));
        if (android.os.Build.VERSION.SDK_INT >= 21) panel.setElevation(U.dp(this, 14));
        panel.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { }
        });
        panel.addView(U.tv(this, L.t("修改服务器"), 16, U.TXT, true), U.lp(-1, -2));

        FrameLayout bn = fieldBox();
        final EditText nm = labeledField(bn, L.t("服务器名称"), "Emby 服务器");
        nm.setText(oldName == null ? "" : oldName);
        LinearLayout.LayoutParams nlp = U.lp(-1, U.dp(this, 47));
        nlp.topMargin = U.dp(this, 12);
        panel.addView(bn, nlp);

        FrameLayout ba = fieldBox();
        final EditText ad = labeledField(ba, L.t("服务器地址"), "192.168.1.10:8096");
        ad.setText(oldAddr == null ? "" : oldAddr);
        LinearLayout.LayoutParams alp = U.lp(-1, U.dp(this, 47));
        alp.topMargin = U.dp(this, 8);
        panel.addView(ba, alp);

        LinearLayout btnRow = U.row(this);
        TextView cancel = formBtn(L.t("取消"), false, 44);
        TextView save = formBtn(L.t("保存"), true, 44);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(0, U.dp(this, 44), 1f);
        btnRow.addView(cancel, clp);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(0, U.dp(this, 44), 1.2f);
        slp.leftMargin = U.dp(this, 10);
        btnRow.addView(save, slp);
        LinearLayout.LayoutParams blp = U.lp(-1, U.dp(this, 44));
        blp.topMargin = U.dp(this, 14);
        panel.addView(btnRow, blp);

        cancel.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { closeServerEdit(); }
        });
        save.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                String n = nm.getText().toString().trim();
                String a = Api.normalize(ad.getText().toString().trim());
                if (a.isEmpty()) return;
                store.updateServer(oldAddr, n.isEmpty() ? a : n, a);
                closeServerEdit();
                showSelectServer();
                try {
                    android.widget.Toast.makeText(MainActivity.this, L.t("已保存"),
                            android.widget.Toast.LENGTH_SHORT).show();
                } catch (Throwable ignored) { }
            }
        });

        FrameLayout.LayoutParams pp = new FrameLayout.LayoutParams(U.dp(this, 420), -2);
        pp.gravity = Gravity.CENTER;
        overlay.addView(panel, pp);
        root.addView(overlay, new FrameLayout.LayoutParams(-1, -1));
        serverEditOverlay = overlay;
        serverMenuFirst = nm;
        blockContentFocus(true);
        nm.requestFocus();
        panel.postDelayed(new Runnable() {
            @Override public void run() {
                if (serverEditOverlay != null) nm.requestFocus();
            }
        }, 150);
    }

    private void closeServerEdit() {
        if (serverEditOverlay == null) return;
        root.removeView(serverEditOverlay);
        serverEditOverlay = null;
        serverMenuFirst = null;
        blockContentFocus(false);
        resumeServerFocus();
    }

    /** 面板里的一行选项：和设置页/排序面板同一套胶囊样式 */
    private TextView menuChip(String text) {
        TextView chip = new TextView(this);
        U.face(chip);
        chip.setText(text);
        chip.setTextSize(15);
        chip.setTextColor(U.txt(U.TXT));
        chip.setGravity(Gravity.CENTER_VERTICAL);
        chip.setPadding(U.dp(this, 16), 0, U.dp(this, 16), 0);
        chip.setBackground(chipStateBg(false));
        chip.setFocusable(true);
        chip.setFocusableInTouchMode(true);
        U.ringFg(chip, U.dp(this, U.RADIUS));
        return chip;
    }

    /** 选中服务器后自动查出用户名，直接弹只输密码的对话框 */
    private void pickUserAndLogin(final String server) {
        new Thread(new Runnable() {
            @Override public void run() {
                java.util.List<String[]> users = Api.publicUsers(server);
                final String[] pick;
                if (users.isEmpty()) {
                    pick = null;
                } else {
                    String[] chosen = users.get(0);
                    for (String[] u : users) {
                        if (u[0].equalsIgnoreCase(store.user())) chosen = u;
                    }
                    pick = chosen;
                }
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (pick == null) { showLogin(server, ""); return; }
                        showPasswordDialog(server, pick[0], pick[0]);
                    }
                });
            }
        }).start();
    }

    private View embyTileServer(String name, View.OnClickListener click) {
        LinearLayout col = U.col(this);
        col.setFocusable(true);
        col.setFocusableInTouchMode(true);
        final LinearLayout box = U.col(this);
        box.setGravity(Gravity.CENTER);
        box.setBackground(U.round(0x00000000, U.dp(this, 3)));
        IconTile ic = new IconTile(this, IconTile.SERVER);
        box.addView(ic, new LinearLayout.LayoutParams(-1, -1));

        final View ring = new View(this);
        ring.setBackground(U.roundStroke(0x00000000,
                U.dp(this, U.RADIUS + 4), U.dp(this, 3), U.RING));
        ring.setVisibility(View.INVISIBLE);
        FrameLayout shell = new FrameLayout(this);
        shell.setPadding(U.dp(this, 4), U.dp(this, 4), U.dp(this, 4), U.dp(this, 4));
        FrameLayout.LayoutParams bfp2 = new FrameLayout.LayoutParams(
                U.dp(this, 110), U.dp(this, 158));
        bfp2.gravity = Gravity.CENTER;
        shell.addView(box, bfp2);
        shell.addView(ring, new FrameLayout.LayoutParams(-1, -1));
        col.addView(shell, new FrameLayout.LayoutParams(
                U.dp(this, 118), U.dp(this, 166)));
        String label = name == null ? "" : name.trim();
        if (label.isEmpty() || label.contains("://") || label.startsWith("192.168.")) {
            label = L.t("Emby 服务器");
        }
        TextView l = U.tv(this, label, 14, U.TXT, false);
        l.setGravity(Gravity.START);
        l.setMaxLines(1);
        l.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams lp = U.lp(-1, -2);
        lp.topMargin = U.dp(this, 8);
        col.addView(l, lp);
        col.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override public void onFocusChange(View v, boolean has) {
                box.setBackground(has
                        ? U.round(U.SOFT_L, U.dp(MainActivity.this, 3))
                        : U.round(0x00000000, U.dp(MainActivity.this, 3)));
                ring.setVisibility(has ? View.VISIBLE : View.INVISIBLE);
            }
        });
        col.setOnClickListener(click);
        col.setTag(l);
        return col;
    }

    // ================= 手机扫码登录（设备上起一个极简 HTTP 表单） =================
    private static final int QR_PORT = 8123;
    private java.net.ServerSocket qrSocket;

    // 手机扫码回填用的表单引用（仅在手动输入页有效）
    private EditText lgName, lgAddr, lgPort, lgPath, lgUser, lgPass;
    private TextView lgPill, lgErr;
    private String[] lgProto;
    private LinearLayout lgChk1, lgChk2, lgChk3;
    private boolean[] lgDirect, lgStrm, lgTrust;

    private void refreshChk(LinearLayout row, boolean[] arr) {
        if (row == null || arr == null) return;
        Object o = row.getTag();
        if (o instanceof View) ((View) o).setVisibility(arr[0] ? View.VISIBLE : View.INVISIBLE);
    }

    /** 按当前 IP 现场生成二维码（IP 变了二维码跟着变） */
    private android.graphics.Bitmap qrBitmap(String text, int px) {
        try {
            java.util.EnumMap<com.google.zxing.EncodeHintType, Object> h =
                    new java.util.EnumMap<com.google.zxing.EncodeHintType, Object>(com.google.zxing.EncodeHintType.class);
            h.put(com.google.zxing.EncodeHintType.CHARACTER_SET, "UTF-8");
            h.put(com.google.zxing.EncodeHintType.MARGIN, 1);
            com.google.zxing.common.BitMatrix m = new com.google.zxing.qrcode.QRCodeWriter()
                    .encode(text, com.google.zxing.BarcodeFormat.QR_CODE, px, px, h);
            int[] pix = new int[px * px];
            for (int y = 0; y < px; y++) {
                for (int x = 0; x < px; x++) {
                    pix[y * px + x] = m.get(x, y) ? 0xFF000000 : 0xFFFFFFFF;
                }
            }
            android.graphics.Bitmap b = android.graphics.Bitmap.createBitmap(px, px, android.graphics.Bitmap.Config.ARGB_8888);
            b.setPixels(pix, 0, px, 0, 0, px, px);
            return b;
        } catch (Throwable e) { return null; }
    }

    private void startQrServer() {
        if (qrSocket != null) return;
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    qrSocket = new java.net.ServerSocket(QR_PORT);
                    while (qrSocket != null && !qrSocket.isClosed()) {
                        java.net.Socket sk = qrSocket.accept();
                        serveQr(sk);
                    }
                } catch (Exception ignored) { }
            }
        }).start();
    }

    private void stopQrServer() {
        try { if (qrSocket != null) qrSocket.close(); } catch (Exception ignored) { }
        qrSocket = null;
    }

    private View qrLoginPanel() {
        LinearLayout box = U.col(this);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        box.setBackground(U.glassPanel(this));
        box.setPadding(U.dp(this, 12), U.dp(this, 12), U.dp(this, 12), U.dp(this, 10));

        android.widget.ImageView iv = new android.widget.ImageView(this);
        String ip = Api.localIpAddress();
        if (ip == null) ip = "127.0.0.1";
        android.graphics.Bitmap qb = qrBitmap("http://" + ip + ":" + QR_PORT + "/", U.dp(this, 126));
        if (qb != null) {
            iv.setImageBitmap(qb);
            iv.setBackgroundColor(0xFFFFFFFF);
        } else {
            int rid = getResources().getIdentifier("qr_login", "drawable", getPackageName());
            if (rid != 0) iv.setImageResource(rid);
        }
        iv.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
        box.addView(iv, new LinearLayout.LayoutParams(U.dp(this, 126), U.dp(this, 126)));

        TextView t = U.tv(this, L.t("手机扫码登录"), 12, U.TXT, false);
        LinearLayout.LayoutParams tl = U.lp(-1, -2);
        tl.topMargin = U.dp(this, 8);
        t.setGravity(Gravity.CENTER_HORIZONTAL);
        box.addView(t, tl);

        TextView u = U.tv(this, ip + ":" + QR_PORT, 9, U.DIM, false);
        u.setGravity(Gravity.CENTER_HORIZONTAL);
        box.addView(u, U.lp(-1, -2));
        return box;
    }

    private void serveQr(final java.net.Socket sk) {
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    java.io.BufferedReader rd = new java.io.BufferedReader(
                            new java.io.InputStreamReader(sk.getInputStream(), "UTF-8"));
                    String first = rd.readLine();
                    if (first == null) { sk.close(); return; }
                    int len = 0;
                    while (true) {
                        String h = rd.readLine();
                        if (h == null || h.length() == 0) break;
                        if (h.toLowerCase().startsWith("content-length:")) {
                            try { len = Integer.parseInt(h.substring(15).trim()); } catch (Exception e) { }
                        }
                    }
                    StringBuilder body = new StringBuilder();
                    if (len > 0) {
                        char[] buf = new char[512];
                        int n;
                        while (body.length() < len && (n = rd.read(buf)) > 0) {
                            body.append(new String(buf, 0, n));
                        }
                    }
                    String path = first.split(" ").length > 1 ? first.split(" ")[1] : "/";
                    if (path.startsWith("/login") && body.length() > 0) {
                        String usr = urlParam(body.toString(), "user");
                        String pwd = urlParam(body.toString(), "pass");
                        String nm = urlParam(body.toString(), "name");
                        String proto = urlParam(body.toString(), "proto");
                        String addr = urlParam(body.toString(), "addr");
                        String pport = urlParam(body.toString(), "port");
                        String spath = urlParam(body.toString(), "srvpath");
                        String note = urlParam(body.toString(), "note");
                        boolean direct = "1".equals(urlParam(body.toString(), "direct"));
                        boolean strm = "1".equals(urlParam(body.toString(), "strm"));
                        boolean ssl = "1".equals(urlParam(body.toString(), "ssl"));
                        if (addr.trim().isEmpty()) {
                            String sv = urlParam(body.toString(), "server");
                            if (!sv.isEmpty()) addr = sv;
                        }
                        if (addr.trim().isEmpty() || usr.isEmpty()) {
                            send(sk, qrPage("还差两项", "<div class='err'>服务器地址和用户名都要填</div>",
                                    formHtml(proto, addr, pport, spath, usr, nm, note, direct, strm, ssl)));
                            return;
                        }
                        if (lgAddr == null) {
                            send(sk, qrPage(L.t("电视没准备好"),
                                    "<div class='err'>电视当前不在登录页<br>请先在电视上打开「手动输入」页，再点「发送到电视」</div>",
                                    formHtml(proto, addr, pport, spath, usr, nm, note, direct, strm, ssl)));
                            return;
                        }
                        final String fProto = proto, fAddr = addr, fPort = pport, fPath = spath,
                                fUsr = usr, fPwd = pwd, fName = nm, fNote = note;
                        final boolean fDirect = direct, fStrm = strm, fSsl = ssl;
                        runOnUiThread(new Runnable() {
                            @Override public void run() {
                                fillLoginFromPhone(fProto, fAddr, fPort, fPath, fUsr, fPwd, fName, fNote,
                                        fDirect, fStrm, fSsl);
                            }
                        });
                        send(sk, qrPage(L.t("已发送到电视"),
                                "<div class='ok'>已发送到电视，登录表单自动填好了<br>"
                                        + "<span style='color:#a9a49c;font-size:12px'>在电视上检查一下，点「保存」连接</span></div>", ""));
                        return;
                    }
                    send(sk, qrPage(L.t("手机扫码配置"),
                            "<div class='tip'>和电视上的「手动输入」页一样，填好点「发送到电视」<br>电视表单会自动填好，你只需点「保存」</div>",
                            formHtml("", "", "", "", store.user(), "", "", store.directOnly(), store.strm(), !store.secure())));
                } catch (Exception ignored) {
                    try { sk.close(); } catch (Exception e2) { }
                }
            }
        }).start();
    }

    /** 手机端表单回填：协议 / 地址 / 端口 / 路径 / 账号 / 名称 / 备注 / 三个开关 全部同步 */
    private void fillLoginFromPhone(String proto, String addr, String port, String path,
                                    String usr, String pwd, String name, String note,
                                    boolean direct, boolean strm, boolean ssl) {
        if (lgAddr == null) return;
        if (proto == null || proto.isEmpty()) proto = "http";
        if (port == null || port.isEmpty()) port = "8096";
        if (addr == null) addr = "";
        addr = addr.trim();
        int ic = addr.indexOf("://");
        if (ic >= 0) addr = addr.substring(ic + 3);
        int slash = addr.indexOf('/');
        if (slash > 0) {
            if (path == null || path.isEmpty()) path = addr.substring(slash + 1);
            addr = addr.substring(0, slash);
        }
        int colon = addr.lastIndexOf(':');
        if (colon > 0 && addr.substring(colon + 1).matches("[0-9]+")) {
            port = addr.substring(colon + 1);
            addr = addr.substring(0, colon);
        }
        if (lgProto != null && lgProto.length > 0) lgProto[0] = proto;
        if (lgPill != null) lgPill.setText(proto);
        lgAddr.setText(addr);
        lgPort.setText(port);
        if (path != null) lgPath.setText(path);
        if (usr != null && !usr.isEmpty()) lgUser.setText(usr);
        if (pwd != null && !pwd.isEmpty()) lgPass.setText(pwd);
        if (name != null && !name.isEmpty()) lgName.setText(name);
        if (lgDirect != null) { lgDirect[0] = direct; store.directOnly(direct); }
        if (lgStrm != null) { lgStrm[0] = strm; store.strm(strm); }
        if (lgTrust != null) { lgTrust[0] = ssl; store.secure(!ssl); }
        refreshChk(lgChk1, lgDirect);
        refreshChk(lgChk2, lgStrm);
        refreshChk(lgChk3, lgTrust);
        if (lgErr != null) {
            lgErr.setTextColor(U.txt(U.ACC));
            lgErr.setText(L.t("已从手机填入，检查后点「保存」连接"));
        }
        lgAddr.requestFocus();
    }

    private String urlParam(String body, String key) {
        try {
            for (String kv : body.split("&")) {
                int i = kv.indexOf('=');
                if (i < 0) continue;
                if (kv.substring(0, i).equals(key))
                    return java.net.URLDecoder.decode(kv.substring(i + 1), "UTF-8");
            }
        } catch (Exception ignored) { }
        return "";
    }

    private String esc(String t) {
        if (t == null) return "";
        return t.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private String formHtml(String proto, String addr, String port, String path,
                            String usr, String name, String note,
                            boolean direct, boolean strm, boolean ssl) {
        if (proto == null || proto.isEmpty()) proto = "http";
        if (port == null || port.isEmpty()) port = "8096";
        if (addr == null) addr = "";
        if (usr == null) usr = "";
        if (path == null) path = "";
        if (addr.trim().isEmpty()) {
            String sv = store.server();
            int ic = sv.indexOf("://");
            addr = ic >= 0 ? sv.substring(ic + 3) : sv;
            int slash = addr.indexOf('/');
            if (slash > 0) addr = addr.substring(0, slash);
            int colon = addr.lastIndexOf(':');
            if (colon > 0 && addr.substring(colon + 1).matches("[0-9]+")) addr = addr.substring(0, colon);
        }
        boolean https = "https".equals(proto);
        return "<div class='card'>"
                + "<div class='lb'>协议</div>"
                + "<div class='seg'>"
                + "<label><input type='radio' name='proto' value='http'" + (https ? "" : " checked") + ">HTTP</label>"
                + "<label><input type='radio' name='proto' value='https'" + (https ? " checked" : "") + ">HTTPS</label>"
                + "</div>"
                + "<div class='lb'>服务器地址</div>"
                + "<input type='text' name='addr' value='" + esc(addr) + "' placeholder='192.168.1.10' autocapitalize='off' autocorrect='off'>"
                + "<div class='lb'>端口</div>"
                + "<input type='text' name='port' value='" + esc(port) + "' inputmode='numeric' placeholder='8096'>"
                + "<div class='lb'>路径（可选）</div>"
                + "<input type='text' name='srvpath' value='" + esc(path) + "' placeholder='/emby' autocapitalize='off' autocorrect='off'>"
                + "<div class='lb'>用户名</div>"
                + "<input type='text' name='user' value='" + esc(usr) + "' placeholder='用户名' autocapitalize='off' autocorrect='off'>"
                + "<div class='lb'>密码</div>"
                + "<input type='password' name='pass' placeholder='密码'>"
                + "<div class='lb'>服务器名称</div>"
                + "<input type='text' name='name' value='" + esc((name == null || name.isEmpty()) ? store.srvName() : name) + "' placeholder='我的 Emby 服务器'>"
                + "<div class='lb'>高级选项</div>"
                + "<label class='ck'><input type='checkbox' name='direct' value='1'" + (direct ? " checked" : "") + ">仅直连（不使用代理）</label>"
                + "<label class='ck'><input type='checkbox' name='strm' value='1'" + (strm ? " checked" : "") + ">STRM 直链播放</label>"
                + "<label class='ck'><input type='checkbox' name='ssl' value='1'" + (ssl ? " checked" : "") + ">信任所有 SSL 证书</label>"
                + "<button type='submit'>发送到电视</button>"
                + "</div>";
    }

    private String qrPage(String title, String extra, String form) {
        String acc = String.format("#%06X", U.ACC & 0xFFFFFF);
        String onAcc = String.format("#%06X", U.ON_ACC & 0xFFFFFF);
        String bg = U.DG
                ? "radial-gradient(120% 90% at 50% 0%,#101610 0%,#080B08 55%,#040604 100%)"
                : U.GLASS
                ? "radial-gradient(120% 90% at 50% 0%,#1B1E23 0%,#0E1013 55%,#07080A 100%)"
                : "radial-gradient(120% 90% at 50% 0%,#221a14 0%,#0d0b09 55%,#07060a 100%)";
        return "<!DOCTYPE html><html><head><meta charset='utf-8'>"
                + "<meta name='viewport' content='width=device-width,initial-scale=1'>"
                + "<title>" + title + "</title><style>"
                + "*{box-sizing:border-box}"
                + "body{margin:0;background:" + bg + ";"
                + "background-attachment:fixed;"
                + "color:#f2f2f2;font-family:-apple-system,'PingFang SC','Microsoft YaHei',sans-serif;min-height:100vh}"
                + ".wrap{max-width:420px;margin:0 auto;padding:30px 18px 40px}"
                + ".brand{display:flex;align-items:center;justify-content:center;gap:10px;margin-bottom:16px}"
                + ".dot{width:30px;height:30px;border-radius:8px;background:" + acc + ";transform:rotate(45deg);"
                + "box-shadow:0 6px 18px rgba(0,0,0,.45)}"
                + ".wm{font-size:20px;font-weight:700;letter-spacing:2px}"
                + "h1{font-size:16px;font-weight:600;margin:0 0 8px;text-align:center;letter-spacing:.5px}"
                + ".tip{color:#a9a49c;font-size:12.5px;margin:0 0 16px;line-height:1.7;text-align:center}"
                + ".card{background:rgba(255,255,255,.06);border:1px solid rgba(255,255,255,.14);"
                + "border-radius:16px;padding:16px 16px 18px;backdrop-filter:blur(10px)}"
                + ".lb{color:#a9a49c;font-size:11.5px;letter-spacing:.5px;margin:13px 0 6px}"
                + ".lb:first-child{margin-top:0}"
                + "input{width:100%;border:1px solid rgba(255,255,255,.16);border-radius:11px;"
                + "background:rgba(255,255,255,.05);color:#fff;font-size:15.5px;padding:12px 12px;outline:none}"
                + "input:focus{border-color:" + acc + ";background:rgba(255,255,255,.08)}"
                + ".seg{display:flex;gap:10px}"
                + ".seg label{flex:1;display:flex;align-items:center;justify-content:center;gap:8px;"
                + "border:1px solid rgba(255,255,255,.16);border-radius:11px;background:rgba(255,255,255,.05);"
                + "padding:11px 0;font-size:14.5px}"
                + ".seg label input{width:auto;margin:0}"
                + ".ck{display:flex;align-items:center;gap:9px;font-size:13.5px;color:#d5d8dc;padding:9px 0}"
                + ".ck input{width:auto;margin:0}"
                + "button{width:100%;margin-top:20px;border:0;border-radius:13px;background:" + acc + ";"
                + "color:" + onAcc + ";font-size:16px;font-weight:700;padding:14px;letter-spacing:1px}"
                + ".ok{background:rgba(255,255,255,.08);border:1px solid rgba(255,255,255,.18);"
                + "border-radius:13px;padding:16px;font-size:14px;text-align:center;line-height:1.7}"
                + ".err{background:rgba(255,90,80,.12);border:1px solid rgba(255,90,80,.35);"
                + "border-radius:13px;padding:14px;font-size:13.5px;text-align:center;line-height:1.6}"
                + "</style></head><body><div class='wrap'>"
                + "<h1>" + title + "</h1>" + extra
                + "<form action='login' method='post'>" + form + "</form>"
                + "</div></body></html>";
    }

    private void send(java.net.Socket sk, String html) {
        try {
            byte[] b = html.getBytes("UTF-8");
            java.io.OutputStream os = sk.getOutputStream();
            String head = "HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\n"
                    + "Content-Length: " + b.length + "\r\nConnection: close\r\n\r\n";
            os.write(head.getBytes("UTF-8"));
            os.write(b);
            os.flush();
        } catch (Exception ignored) { }
        try { sk.close(); } catch (Exception ignored) { }
    }

    // ================= 原生 Emby 风格登录（一比一） =================
    /** Loyao 标志：assets/loyao_mark.png（深色圆角方块 + 白色 L + 橙色播放三角） */
    static class EmbyMark extends View {
        private final android.graphics.Paint pt = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private android.graphics.Bitmap bmp;
        EmbyMark(android.content.Context c) {
            super(c);
            try {
                java.io.InputStream in = c.getAssets().open("loyao_mark.png");
                bmp = android.graphics.BitmapFactory.decodeStream(in);
                in.close();
            } catch (Throwable ignored) { }
            pt.setFilterBitmap(true);
        }

        @Override protected void onDraw(android.graphics.Canvas cv) {
            float w = getWidth(), h = getHeight();
            if (bmp != null) {
                float size = Math.min(w, h) * 0.92f;
                android.graphics.RectF dst = new android.graphics.RectF(
                        (w - size) / 2f, (h - size) / 2f, (w + size) / 2f, (h + size) / 2f);
                cv.drawBitmap(bmp, null, dst, pt);
                return;
            }
            float size = Math.min(w, h) * 0.84f;
            float cx = w / 2f, cy = h / 2f, r = size / 2f;
            android.graphics.Path sq = new android.graphics.Path();
            sq.addRoundRect(new android.graphics.RectF(cx - r, cy - r, cx + r, cy + r),
                    r * 0.26f, r * 0.26f, android.graphics.Path.Direction.CW);
            android.graphics.Matrix m = new android.graphics.Matrix();
            m.postRotate(45f, cx, cy);
            sq.transform(m);
            pt.setStyle(android.graphics.Paint.Style.FILL);
            if (U.THEME == 1) {
                pt.setColor(0xFFFFFFFF);
                cv.drawPath(sq, pt);
                pt.setStyle(android.graphics.Paint.Style.STROKE);
                pt.setStrokeWidth(size * 0.04f);
                pt.setColor(0xFFDCE3ED);
                cv.drawPath(sq, pt);
                pt.setStyle(android.graphics.Paint.Style.FILL);
            } else {
                pt.setColor(0xFF52B54B);
                cv.drawPath(sq, pt);
            }

            pt.setColor(U.TXT);
            android.graphics.Path t = new android.graphics.Path();
            float tr = r * 0.32f;
            t.moveTo(cx - tr * 0.80f, cy - tr * 1.10f);
            t.lineTo(cx - tr * 0.80f, cy + tr * 1.10f);
            t.lineTo(cx + tr * 1.00f, cy);
            t.close();
            cv.drawPath(t, pt);
        }
    }

    /** 圆角输入框：无下划线，聚焦时描边变绿 */
    static class LineEdit extends EditText {
        LineEdit(android.content.Context c) {
            super(c);
            setBackground(bg(c, false));
            setFocusable(true);
            setFocusableInTouchMode(true);
            setPadding(U.dp(c, 18), U.dp(c, 10), U.dp(c, 18), U.dp(c, 10));
        }
        private static android.graphics.drawable.Drawable bg(android.content.Context c, boolean focus) {
            return U.inputBg(c, focus);
        }
        @Override protected void onFocusChanged(boolean gain, int dir, android.graphics.Rect prev) {
            super.onFocusChanged(gain, dir, prev);
            setBackground(bg(getContext(), gain));
        }
    }

    private View embyHeader(int markDp, int wordSp) {
        LinearLayout row = U.row(this);
        row.setGravity(Gravity.CENTER);
        EmbyMark mk = new EmbyMark(this);
        row.addView(mk, new LinearLayout.LayoutParams(U.dp(this, markDp), U.dp(this, markDp)));
        TextView wd = U.tv(this, "LonYao", wordSp, U.TXT, true);
        wd.setLetterSpacing(0.02f);
        LinearLayout.LayoutParams wl = U.lp(-2, -2);
        wl.leftMargin = U.dp(this, 18);
        row.addView(wd, wl);
        return row;
    }

    private TextView plainText(String text, int sp, int color, boolean bold) {
        TextView t = U.tv(this, text, sp, color, bold);
        t.setBackground(null);
        t.setPadding(U.dp(this, 12), U.dp(this, 10), U.dp(this, 12), U.dp(this, 10));
        return t;
    }

    /** 免密码启动：用上次登录保存的 token 直接进首页；失败就退回输密码 */
    private void autoLogin() {
        final String srv = store.lastServer();
        final String usr = store.lastUser();
        final String[] acc = store.accountOf(srv, usr);
        if (acc == null || acc.length < 2) { showQuickLogin(); return; }
        final Api.Session ss = new Api.Session();
        ss.server = Api.normalize(srv);
        ss.userId = acc[0];
        ss.token = acc[1];
        ss.userName = acc[2];
        ss.deviceId = store.deviceId();
        if (statusRef != null) statusRef.setText(L.t("正在登录…"));
        new Thread(new Runnable() {
            @Override public void run() {
                List<Api.Lib> got = null;
                Exception last = null;
                // 从第三方播放器/后台回来时进程可能是重建的，网络也常还没缓过来：
                // 一次失败就弹密码框太急，先静默重试两轮；只有 token 真失效(401/403)才立刻要密码
                for (int i = 0; i < 3; i++) {
                    try { got = Api.libraries(ss); last = null; break; }
                    catch (Exception e) {
                        last = e;
                        String m = e.getMessage() == null ? "" : e.getMessage();
                        if (m.indexOf("HTTP 401") >= 0 || m.indexOf("HTTP 403") >= 0) break;
                        try { Thread.sleep(i == 0 ? 1200 : 2500); } catch (InterruptedException ie) { break; }
                    }
                }
                final List<Api.Lib> ls = got;
                final Exception err = last;
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (ls != null) {
                            session = ss;
                            libs = ls;
                            showHome();
                        } else {
                            android.util.Log.w("LMBY", "autoLogin 失败，转手输密码: " + err);
                            showQuickLogin();
                        }
                    }
                });
            }
        }).start();
    }

    /** 快捷登录：原生 Emby 的「输入密码」对话框 */
    void showQuickLogin() {
        showPasswordDialog(store.lastServer(), store.lastUser(),
                store.lastName().length() > 0 ? store.lastName() : store.lastUser());
    }

    /** 通用头像：深色圆底 + 车手剪影（没有自定义头像时使用） */
    private android.graphics.Bitmap defaultAvatar(int size) {
        // 优先用内置的藤原拓海头像（res/drawable/takumi.png），切成圆形
        try {
            int rid = getResources().getIdentifier("takumi", "drawable", getPackageName());
            if (rid != 0) {
                android.graphics.Bitmap src = android.graphics.BitmapFactory.decodeResource(
                        getResources(), rid);
                if (src != null) {
                    int side = Math.min(src.getWidth(), src.getHeight());
                    int sx = (src.getWidth() - side) / 2;
                    int sy = (src.getHeight() - side) / 2;
                    android.graphics.Bitmap sq = android.graphics.Bitmap.createBitmap(src, sx, sy, side, side);
                    android.graphics.Bitmap sc = android.graphics.Bitmap.createScaledBitmap(sq, size, size, true);
                    android.graphics.Bitmap out = android.graphics.Bitmap.createBitmap(size, size,
                            android.graphics.Bitmap.Config.ARGB_8888);
                    android.graphics.Canvas oc = new android.graphics.Canvas(out);
                    android.graphics.Paint op = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
                    op.setShader(new android.graphics.BitmapShader(sc,
                            android.graphics.Shader.TileMode.CLAMP, android.graphics.Shader.TileMode.CLAMP));
                    oc.drawCircle(size / 2f, size / 2f, size / 2f, op);
                    return out;
                }
            }
        } catch (Throwable ignored) { }

        // 兜底：代码画的剪影头像
        android.graphics.Bitmap bmp = android.graphics.Bitmap.createBitmap(size, size,
                android.graphics.Bitmap.Config.ARGB_8888);
        android.graphics.Canvas c = new android.graphics.Canvas(bmp);
        android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        float r = size / 2f;

        // 圆形底：上浅下深的灰
        android.graphics.RadialGradient rg = new android.graphics.RadialGradient(
                r, r * 0.6f, r * 1.4f, 0xFF3A4248, 0xFF14181B,
                android.graphics.Shader.TileMode.CLAMP);
        p.setShader(rg);
        c.drawCircle(r, r, r, p);
        p.setShader(null);

        // 肩部
        p.setColor(0xFFE6ECEF);
        android.graphics.Path sh = new android.graphics.Path();
        float left = size * 0.16f, right = size * 0.84f, bottom = size * 1.02f;
        float topY = size * 0.62f;
        sh.moveTo(left, bottom);
        sh.quadTo(size * 0.5f, topY - size * 0.10f, right, bottom);
        sh.close();
        c.drawPath(sh, p);

        // 头
        p.setColor(0xFFE6ECEF);
        c.drawCircle(r, size * 0.40f, size * 0.185f, p);

        // 赛车头盔条纹（一点速度感）
        p.setColor(0xFF2B7BD6);
        android.graphics.RectF stripe = new android.graphics.RectF(
                size * 0.5f, size * 0.24f, size * 0.78f, size * 0.33f);
        c.drawRoundRect(stripe, size * 0.03f, size * 0.03f, p);

        return bmp;
    }

    /** 指定服务器 / 账号的「只输密码」对话框（选中服务器后自动带出用户名） */
    void showPasswordDialog(final String srv, final String usr, String displayName) {
        inQuickLogin = true;
        if (session != null) session = null;
        root.removeAllViews();
        libId = folderId = null;
        busy = false;

        // 键盘弹出时只让输入面板上移，背景层（logo/背景图）固定不动
        getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);

        FrameLayout host = new FrameLayout(this);
        host.setBackground(U.bgDraw());

        LinearLayout col = U.col(this);
        col.setGravity(Gravity.CENTER_HORIZONTAL);


        LinearLayout panel = U.col(this);
        panel.setGravity(Gravity.CENTER_HORIZONTAL);
        // 半透明框：把头像 + 用户名 + 密码 + 按钮整体框在一起
        panel.setBackground(U.glassPanel(this));
        panel.setPadding(U.dp(this, 26), U.dp(this, 22), U.dp(this, 26), U.dp(this, 18));

        String name = displayName == null || displayName.length() == 0 ? usr : displayName;

        // 圆形头像：默认用通用头像（没有自定义头像时）
        FrameLayout avWrap = new FrameLayout(this);
        android.graphics.drawable.GradientDrawable ring = new android.graphics.drawable.GradientDrawable();
        ring.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        ring.setColor(0x00000000);
        ring.setStroke(U.dp(this, 2), U.BORDER);
        avWrap.setBackground(ring);
        avWrap.setClipToOutline(false);
        android.widget.ImageView av = new android.widget.ImageView(this);
        av.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
        av.setImageBitmap(defaultAvatar(U.dp(this, 66)));
        avWrap.addView(av, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout.LayoutParams avlp = U.lp(U.dp(this, 66), U.dp(this, 66));
        panel.addView(avWrap, avlp);

        TextView who = U.tv(this, name, 19, U.TXT, true);
        who.setGravity(Gravity.CENTER_HORIZONTAL);
        LinearLayout.LayoutParams wlp = U.lp(-1, -2);
        wlp.topMargin = U.dp(this, 12);
        panel.addView(who, wlp);

        String srvName = store.serverLabel(srv);
        TextView srvLabel = U.tv(this, srvName, 12, U.DIM, false);
        srvLabel.setVisibility(srvName.isEmpty() ? View.GONE : View.VISIBLE);
        srvLabel.setGravity(Gravity.CENTER_HORIZONTAL);
        LinearLayout.LayoutParams svl = U.lp(-1, -2);
        svl.topMargin = U.dp(this, 3);
        panel.addView(srvLabel, svl);

        // 只输密码：一个输入框
        FrameLayout field = new FrameLayout(this);
        field.setBackground(U.inputBg(this, false));
        LinearLayout.LayoutParams flp = U.lp(-1, U.dp(this, 48));
        flp.topMargin = U.dp(this, 16);
        panel.addView(field, flp);

        final EditText pass = new LineEdit(this);
        pass.setTextColor(U.txt(U.TXT));
        pass.setHintTextColor(U.DIM);
        pass.setHint(L.t("密码"));
        pass.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        pass.setSingleLine(true);
        pass.setBackgroundColor(0x00000000);
        pass.setPadding(U.dp(this, 18), 0, U.dp(this, 18), 0);
        pass.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        field.addView(pass, new FrameLayout.LayoutParams(-1, -1));
        // 焦点框统一成外面那套白玻璃环：容器跟着子控件状态走，环画在容器上
        field.setAddStatesFromChildren(true);
        U.ringFg(field, U.dp(this, U.RADIUS));

        final TextView err = U.tv(this, "", 13, U.WARN, false);
        err.setGravity(Gravity.CENTER_HORIZONTAL);
        LinearLayout.LayoutParams el = U.lp(-1, -2);
        el.topMargin = U.dp(this, 8);
        panel.addView(err, el);

        final TextView go = new TextView(this);
        U.face(go);
        go.setText(L.t("登 录"));
        go.setTextSize(15);
        go.setTextColor(U.txt(0xFFFFFFFF));
        go.setTypeface(null, android.graphics.Typeface.BOLD);
        go.setGravity(Gravity.CENTER);
        go.setBackground(U.roundStroke(0x2EFFFFFF, U.dp(this, U.RADIUS), U.dp(this, 1.5f), 0x66FFFFFF));
        go.setFocusable(true);
        go.setFocusableInTouchMode(true);
        U.ringFg(go, U.dp(this, U.RADIUS));
        LinearLayout.LayoutParams glp = U.lp(-1, U.dp(this, 46));
        glp.topMargin = U.dp(this, 12);
        panel.addView(go, glp);

        go.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                doLogin(srv, usr, pass, err, go);
            }
        });
        pass.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override public boolean onEditorAction(TextView v, int a, KeyEvent e) {
                doLogin(srv, usr, pass, err, go);
                return true;
            }
        });
        // 输入框回车 → 焦点交给「确定」
        hookKeys(pass, go, null);
        hookKeys(go, null, pass);

        FrameLayout.LayoutParams pp = new FrameLayout.LayoutParams(U.dp(this, 330), -2);
        pp.gravity = Gravity.CENTER_HORIZONTAL;
        pp.topMargin = U.dp(this, 78);
        col.addView(panel, pp);

        ScrollView sv2 = new ScrollView(this);
        sv2.setFillViewport(true);
        FrameLayout inner = new FrameLayout(this);
        inner.setPadding(U.dp(this, 24), U.dp(this, 0), U.dp(this, 24), U.dp(this, 40));
        inner.addView(col, new FrameLayout.LayoutParams(-1, -2));
        sv2.addView(inner, new ViewGroup.LayoutParams(-1, -1));
        host.addView(sv2, new FrameLayout.LayoutParams(-1, -1));
        root.addView(host, new FrameLayout.LayoutParams(-1, -1));
        quickPass = pass;
        quickGo = go;
        pass.requestFocus();
    }

    /** 手动填写服务器：左侧手机扫码配置 + 右侧表单（LMBY 玻璃风格） */
    void showLogin(String preServer, String preUser) {
        inLogin = true;
        stopBannerLoop();
        bannerItems = null;
        bannerItem = null;
        if (session != null) session = null;
        root.removeAllViews();
        libId = folderId = null;
        busy = false;

        getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);

        FrameLayout host = new FrameLayout(this);
        host.setBackground(U.bgDraw());

        // 左上角不再显示品牌标志

        // 右上角「返回」
        TextView back = new TextView(this);
        U.face(back);
        back.setText(L.t("‹  返回"));
        back.setTextSize(13);
        back.setTextColor(U.txt(U.EMBY ? U.DIM : 0xFFB9C6C0));
        back.setGravity(Gravity.CENTER);
        back.setPadding(U.dp(this, 16), U.dp(this, 8), U.dp(this, 16), U.dp(this, 8));
        back.setBackground(U.EMBY ? U.roundStroke(0xFFF2F2F7, U.dp(this, 18), U.dp(this, 1), 0x1A000000)
                : U.roundStroke(0x33000000, U.dp(this, 6), U.dp(this, 1), U.SOFT));
        back.setFocusable(true);
        back.setFocusableInTouchMode(true);
        back.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { inLogin = false; showSelectServer(); }
        });
        U.ringFg(back, U.dp(this, 6));
        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(-2, -2);
        blp.gravity = Gravity.TOP | Gravity.RIGHT;
        blp.topMargin = U.dp(this, 16);
        blp.rightMargin = U.dp(this, 24);
        host.addView(back, blp);

        // ============ 两栏 ============
        LinearLayout rowWrap = U.row(this);
        rowWrap.setGravity(Gravity.CENTER_VERTICAL);
        rowWrap.setPadding(U.dp(this, 26), U.dp(this, 74), U.dp(this, 26), U.dp(this, 18));

        // —— 左：手机扫码配置 ——
        LinearLayout left = U.col(this);
        left.setGravity(Gravity.CENTER_HORIZONTAL);
        left.setBackground(U.glassPanel(this));
        left.setPadding(U.dp(this, 18), U.dp(this, 16), U.dp(this, 18), U.dp(this, 14));
        TextView lt = U.tv(this, L.t("📱 手机扫码配置"), 13, U.TXT, true);
        lt.setLetterSpacing(0.08f);
        lt.setGravity(Gravity.CENTER_HORIZONTAL);
        left.addView(lt, U.lp(-1, -2));
        String ip = Api.localIpAddress();
        if (ip == null) ip = "127.0.0.1";
        final String qrUrl = "http://" + ip + ":" + QR_PORT + "/";
        android.graphics.Bitmap qb = qrBitmap(qrUrl, U.dp(this, 186));
        android.widget.ImageView qr = new android.widget.ImageView(this);
        if (qb != null) {
            qr.setImageBitmap(qb);
            qr.setBackgroundColor(0xFFFFFFFF);
        } else {
            int rid = getResources().getIdentifier("qr_login", "drawable", getPackageName());
            if (rid != 0) qr.setImageResource(rid);
        }
        qr.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
        LinearLayout.LayoutParams qlp = new LinearLayout.LayoutParams(U.dp(this, 186), U.dp(this, 186));
        qlp.topMargin = U.dp(this, 12);
        left.addView(qr, qlp);
        TextView lh = U.tv(this, L.t("手机浏览器打开 ") + ip + ":" + QR_PORT + "\n填好账号点「发送到电视」\n这台电视的表单会自动填好", 10, U.DIM, false);
        lh.setGravity(Gravity.CENTER_HORIZONTAL);
        lh.setLineSpacing(U.dp(this, 2), 1f);
        LinearLayout.LayoutParams lhl = U.lp(-1, -2);
        lhl.topMargin = U.dp(this, 10);
        left.addView(lh, lhl);
        LinearLayout.LayoutParams lw2 = new LinearLayout.LayoutParams(U.dp(this, 300), -2);
        lw2.rightMargin = U.dp(this, 18);
        rowWrap.addView(left, lw2);

        // —— 右：表单 ——
        LinearLayout right = U.col(this);
        right.setBackground(U.glassPanel(this));
        right.setPadding(U.dp(this, 18), U.dp(this, 14), U.dp(this, 18), U.dp(this, 12));

        // 柴柴登录页的标题区：一个名字 + 一行说明，下面才是地址 / 账号
        right.addView(U.tv(this, L.t("连接到服务器"), 17, U.TXT, true), U.lp(-1, -2));
        TextView subline = U.tv(this, L.t("输入 Emby 服务器地址和账号"), 11, U.DIM, false);
        LinearLayout.LayoutParams sll = U.lp(-1, -2);
        sll.topMargin = U.dp(this, 2);
        sll.bottomMargin = U.dp(this, 8);
        right.addView(subline, sll);

        String pServer = preServer == null ? "" : preServer.trim();
        if (pServer.isEmpty()) pServer = store.server();
        String pProto = "http", pAddr = "", pPort = "8096", pPath = "";
        if (pServer.startsWith("https://")) { pProto = "https"; pServer = pServer.substring(8); }
        else if (pServer.startsWith("http://")) pServer = pServer.substring(7);
        int slash = pServer.indexOf('/');
        if (slash > 0) { pPath = pServer.substring(slash); pServer = pServer.substring(0, slash); }
        int colon = pServer.lastIndexOf(':');
        if (colon > 0) { pPort = pServer.substring(colon + 1); pServer = pServer.substring(0, colon); }
        pAddr = pServer.isEmpty() ? "192.168.1.10" : pServer;

        // 名称
        LinearLayout r1 = U.row(this);
        FrameLayout bn = fieldBox();
        final EditText srvName = labeledField(bn, L.t("服务器名称"), "FNOS");
        srvName.setText(store.srvName().isEmpty() ? "FNOS" : store.srvName());
        r1.addView(bn, new LinearLayout.LayoutParams(-1, U.dp(this, 46)));
        right.addView(r1, U.lp(-1, U.dp(this, 46)));

        // 协议 / 服务器地址 / Port —— 对齐柴柴：地址和端口同一行，端口靠右
        LinearLayout r2 = U.row(this);
        final String[] proto = {pProto};
        final TextView pill = protoPill(proto);
        r2.addView(pill, new LinearLayout.LayoutParams(U.dp(this, 78), U.dp(this, 44)));
        FrameLayout ba = fieldBox();
        final EditText addr = labeledField(ba, L.t("服务器地址"), "192.168.1.10");
        addr.setText(pAddr);
        LinearLayout.LayoutParams pa = new LinearLayout.LayoutParams(0, U.dp(this, 44), 1f);
        pa.leftMargin = U.dp(this, 10);
        r2.addView(ba, pa);
        FrameLayout bp = fieldBox();
        final EditText port = labeledField(bp, "Port:", "8096");
        port.setText(pPort);
        port.setInputType(InputType.TYPE_CLASS_NUMBER);
        LinearLayout.LayoutParams ppl = new LinearLayout.LayoutParams(U.dp(this, 104), U.dp(this, 44));
        ppl.leftMargin = U.dp(this, 10);
        r2.addView(bp, ppl);
        LinearLayout.LayoutParams r2l = U.lp(-1, U.dp(this, 44));
        r2l.topMargin = U.dp(this, 8);
        right.addView(r2, r2l);

        // 路径（可选，柴柴没有，但你的反代场景需要）
        LinearLayout r3 = U.row(this);
        FrameLayout bph = fieldBox();
        final EditText path = labeledField(bph, L.t("路径"), L.t("可选 / emby"));
        path.setText(pPath);
        r3.addView(bph, new LinearLayout.LayoutParams(-1, U.dp(this, 44)));
        LinearLayout.LayoutParams r3l = U.lp(-1, U.dp(this, 44));
        r3l.topMargin = U.dp(this, 8);
        right.addView(r3, r3l);

        // 用户名 / 密码
        LinearLayout r4 = U.row(this);
        FrameLayout bu = fieldBox();
        final EditText user = labeledField(bu, L.t("用户名"), "admin");
        user.setText(preUser.isEmpty() ? store.user() : preUser);
        FrameLayout bpp = fieldBox();
        final EditText pass = labeledField(bpp, L.t("密码"), "");
        pass.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        pass.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_DONE);
        r4.addView(bu, new LinearLayout.LayoutParams(0, U.dp(this, 44), 1f));
        LinearLayout.LayoutParams pwp = new LinearLayout.LayoutParams(0, U.dp(this, 44), 1f);
        pwp.leftMargin = U.dp(this, 10);
        r4.addView(bpp, pwp);
        LinearLayout.LayoutParams r4l = U.lp(-1, U.dp(this, 44));
        r4l.topMargin = U.dp(this, 8);
        right.addView(r4, r4l);

        // 勾选项（三个都能自由开关，并且立即生效 / 落盘）
        final boolean[] direct = {store.directOnly()};
        final boolean[] strm = {store.strm()};
        final boolean[] trust = {!store.secure()};
        LinearLayout chk1 = chkRow(L.t("仅直连（不使用代理）"), null, direct[0], direct, true,
                new Runnable() {
                    @Override public void run() { store.directOnly(direct[0]); }
                });
        LinearLayout chk2 = chkRow(L.t("STRM 直链播放"), null, strm[0], strm, true,
                new Runnable() {
                    @Override public void run() { store.strm(strm[0]); }
                });
        final LinearLayout chk3 = chkRow(L.t("信任所有 SSL 证书（不安全）"), null, trust[0], trust, true,
                new Runnable() {
                    @Override public void run() { store.secure(!trust[0]); }
                });
        LinearLayout.LayoutParams c1l = U.lp(-1, U.dp(this, 30));
        c1l.topMargin = U.dp(this, 8);
        right.addView(chk1, c1l);
        right.addView(chk2, U.lp(-1, U.dp(this, 30)));
        right.addView(chk3, U.lp(-1, U.dp(this, 30)));

        final TextView err = U.tv(this, "", 11, U.WARN, false);
        LinearLayout.LayoutParams el = U.lp(-1, -2);
        el.topMargin = U.dp(this, 4);
        right.addView(err, el);

        // 底部两枚按钮：取消 / 保存
        LinearLayout btnRow = U.row(this);
        final TextView cancel = formBtn(L.t("取消"), false, 44);
        final TextView save = formBtn(L.t("保存"), true, 44);
        LinearLayout.LayoutParams cbl = new LinearLayout.LayoutParams(0, U.dp(this, 44), 1f);
        btnRow.addView(cancel, cbl);
        LinearLayout.LayoutParams sbl = new LinearLayout.LayoutParams(0, U.dp(this, 44), 1.2f);
        sbl.leftMargin = U.dp(this, 10);
        btnRow.addView(save, sbl);
        LinearLayout.LayoutParams brl = U.lp(-1, U.dp(this, 44));
        brl.topMargin = U.dp(this, 8);
        right.addView(btnRow, brl);

        // Emby Connect 入口（柴柴的 btn_manually 反向入口：手动 / Connect 两条路）
        final TextView connectBtn = U.tv(this, L.t("用 Emby Connect 登录  ›"), 12, U.DIM, false);
        connectBtn.setGravity(Gravity.CENTER_HORIZONTAL);
        connectBtn.setFocusable(true);
        connectBtn.setFocusableInTouchMode(true);
        connectBtn.setPadding(0, U.dp(this, 6), 0, U.dp(this, 6));
        connectBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                String h = addr.getText().toString().trim();
                String base = proto[0] + "://" + h;
                String pr = port.getText().toString().trim().replace(":", "");
                if (!pr.isEmpty()) base = base + ":" + pr;
                inLogin = false;
                showConnectLogin(base);
            }
        });
        LinearLayout.LayoutParams ccl = U.lp(-1, -2);
        ccl.topMargin = U.dp(this, 4);
        right.addView(connectBtn, ccl);

        TextView hint = U.tv(this, L.t("点「保存」验证服务器连接并保存配置"), 10, U.DIM, false);
        hint.setGravity(Gravity.CENTER_HORIZONTAL);
        LinearLayout.LayoutParams htl = U.lp(-1, -2);
        htl.topMargin = U.dp(this, 4);
        right.addView(hint, htl);

        rowWrap.addView(right, new LinearLayout.LayoutParams(U.dp(this, 566), -2));
        host.addView(rowWrap, new FrameLayout.LayoutParams(-1, -1));

        // 焦点串联：方向键上下切换输入框（EditText 会吞方向键，必须自己接）
        hookKeys(srvName, addr, null);
        hookKeys(pill, addr, null);
        hookKeys(addr, path, pill);
        hookKeys(port, path, pill);
        hookKeys(path, user, addr);
        hookKeys(user, pass, path);
        hookKeys(pass, chk3, user);
        cancel.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { inLogin = false; hideIme(pass); showSelectServer(); }
        });
        save.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                String h = addr.getText().toString().trim();
                String u = user.getText().toString().trim();
                if (h.isEmpty() || u.isEmpty()) {
                    err.setTextColor(U.txt(U.WARN));
                    err.setText(L.t("请填写服务器地址和用户名"));
                    return;
                }
                String base = proto[0] + "://" + h;
                String pr = port.getText().toString().trim().replace(":", "");
                if (!pr.isEmpty()) base = base + ":" + pr;
                String pt = path.getText().toString().trim();
                if (!pt.isEmpty()) base = base + (pt.startsWith("/") ? pt : "/" + pt);
                store.srvName(srvName.getText().toString().trim());
                store.secure(!trust[0]);
                err.setText("");
                doLogin(base, u, pass, err, save);
            }
        });
        pass.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override public boolean onEditorAction(TextView v, int a, KeyEvent e) {
                save.performClick();
                return true;
            }
        });

        root.addView(host, new FrameLayout.LayoutParams(-1, -1));
        lgChk1 = chk1; lgChk2 = chk2; lgChk3 = chk3;
        lgDirect = direct; lgStrm = strm; lgTrust = trust;
        lgName = srvName; lgAddr = addr; lgPort = port; lgPath = path;
        lgUser = user; lgPass = pass; lgPill = pill; lgErr = err; lgProto = proto;
        startQrServer();
        addr.requestFocus();
    }

    /** Emby Connect 登录页：邮箱 + 密码，登录后回服务器换 token */
    void showConnectLogin(final String preServer) {
        inLogin = true;
        if (session != null) session = null;
        root.removeAllViews();
        libId = folderId = null;
        busy = false;

        getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);

        FrameLayout host = new FrameLayout(this);
        host.setBackground(U.bgDraw());

        LinearLayout col = U.col(this);
        col.setGravity(Gravity.CENTER_HORIZONTAL);

        LinearLayout panel = U.col(this);
        panel.setBackground(U.roundStroke(0xF0121212, U.dp(this, 24), U.dp(this, 1), 0x2EFFFFFF));
        panel.setPadding(U.dp(this, 26), U.dp(this, 20), U.dp(this, 26), U.dp(this, 16));
        panel.setElevation(U.dp(this, 12));

        TextView eyebrow = U.tv(this, "EMBY CONNECT", 12, 0x8834D07A, true);
        eyebrow.setLetterSpacing(0.18f);
        panel.addView(eyebrow, U.lp(-1, -2));

        TextView title = U.tv(this, L.t("Connect 登录"), 22, U.TXT, true);
        LinearLayout.LayoutParams tlp = U.lp(-1, -2);
        tlp.topMargin = U.dp(this, 6);
        panel.addView(title, tlp);

        TextView sub = U.tv(this, L.t("用 Emby Connect 账号（邮箱）登录，服务器需已开启 Connect"), 12, 0xFF8E9C96, false);
        LinearLayout.LayoutParams sl = U.lp(-1, -2);
        sl.topMargin = U.dp(this, 4);
        panel.addView(sub, sl);

        panel.addView(fieldLabel(L.t("服务器地址")), fieldLp(14));
        FrameLayout box0 = fieldBox();
        final EditText srv = fieldEdit(box0, "192.168.1.10:8096");
        srv.setText(preServer == null ? "" : preServer);
        panel.addView(box0, fieldLp2(0));

        panel.addView(fieldLabel(L.t("Connect 账号（邮箱）")), fieldLp(10));
        FrameLayout box1 = fieldBox();
        final EditText mail = fieldEdit(box1, "you@example.com");
        mail.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        panel.addView(box1, fieldLp2(0));

        panel.addView(fieldLabel(L.t("密码")), fieldLp(10));
        FrameLayout box2 = fieldBox();
        final EditText pass = fieldEdit(box2, L.t("Connect 密码"));
        pass.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        pass.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_DONE);
        panel.addView(box2, fieldLp2(0));

        final TextView err = U.tv(this, "", 13, 0xFFFF8A80, false);
        LinearLayout.LayoutParams el = U.lp(-1, -2);
        el.topMargin = U.dp(this, 8);
        panel.addView(err, el);

        final TextView go = new TextView(this);
        U.face(go);
        go.setText(L.t("登 录"));
        go.setTextSize(15);
        go.setTextColor(U.txt(0xFF07120B));
        go.setTypeface(null, android.graphics.Typeface.BOLD);
        go.setGravity(Gravity.CENTER);
        go.setBackground(U.roundStroke(0x2EFFFFFF, U.dp(this, 14), U.dp(this, 1.5f), 0x66FFFFFF));
        go.setFocusable(true);
        go.setFocusableInTouchMode(true);
        go.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override public void onFocusChange(View v, boolean has) {
                v.setBackground(has ? U.roundStroke(0x99FFFFFF, U.dp(MainActivity.this, 14),
                        U.dp(MainActivity.this, 2), 0xFFFFFFFF)
                        : U.roundStroke(0x2EFFFFFF, U.dp(MainActivity.this, 14),
                        U.dp(MainActivity.this, 1.5f), 0x66FFFFFF));
            }
        });
        LinearLayout.LayoutParams glp = U.lp(-1, U.dp(this, 48));
        glp.topMargin = U.dp(this, 16);
        panel.addView(go, glp);

        chainFocus(srv, mail, pass, go);
        hookKeys(go, null, pass);
        go.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                doConnectLogin(srv.getText().toString().trim(), mail.getText().toString().trim(),
                        pass, err, go);
            }
        });
        pass.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override public boolean onEditorAction(TextView v, int a, KeyEvent e) {
                doConnectLogin(srv.getText().toString().trim(), mail.getText().toString().trim(),
                        pass, err, go);
                return true;
            }
        });

        FrameLayout.LayoutParams pp = new FrameLayout.LayoutParams(U.dp(this, 460), -2);
        pp.gravity = Gravity.CENTER_HORIZONTAL;
        col.addView(panel, pp);

        ScrollView sv = new ScrollView(this);
        sv.setFillViewport(true);
        FrameLayout inner = new FrameLayout(this);
        inner.setPadding(U.dp(this, 24), U.dp(this, 84), U.dp(this, 24), U.dp(this, 12));
        inner.addView(col, new FrameLayout.LayoutParams(-1, -2));
        sv.addView(inner, new ViewGroup.LayoutParams(-1, -1));
        host.addView(sv, new FrameLayout.LayoutParams(-1, -1));

        TextView back = new TextView(this);
        U.face(back);
        back.setText(L.t("‹  返回"));
        back.setTextSize(13);
        back.setTextColor(U.txt(U.EMBY ? U.DIM : 0xFFB9C6C0));
        back.setGravity(Gravity.CENTER);
        back.setPadding(U.dp(this, 16), U.dp(this, 8), U.dp(this, 16), U.dp(this, 8));
        back.setBackground(U.EMBY ? U.roundStroke(0xFFF2F2F7, U.dp(this, 18), U.dp(this, 1), 0x1A000000)
                : U.roundStroke(0x33000000, U.dp(this, 6), U.dp(this, 1), U.SOFT));
        back.setFocusable(true);
        back.setFocusableInTouchMode(true);
        back.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { inLogin = false; showSelectServer(); }
        });
        back.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override public void onFocusChange(View v, boolean has) {
                v.setBackground(has ? U.roundStroke(0xFF1C1C1C, U.dp(MainActivity.this, 18),
                        U.dp(MainActivity.this, 2), 0xFFFFFFFF)
                        : U.roundStroke(0x33000000, U.dp(MainActivity.this, 18),
                        U.dp(MainActivity.this, 1), U.SOFT));
            }
        });
        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(-2, -2);
        blp.gravity = Gravity.TOP | Gravity.RIGHT;
        blp.topMargin = U.dp(this, 22);
        blp.rightMargin = U.dp(this, 24);
        host.addView(back, blp);

        root.addView(host, new FrameLayout.LayoutParams(-1, -1));
        mail.requestFocus();
    }

    /** 提交 Connect 登录：邮箱 + 密码 → Connect 服务 → 本地服务器换 token */
    private void doConnectLogin(final String srvArg, final String mailArg,
                                final EditText pass, final TextView err, final TextView go) {
        if (busy) return;
        final String s = srvArg;
        final String mail = mailArg;
        final String p = pass.getText().toString();
        if (s.isEmpty() || mail.isEmpty()) {
            err.setTextColor(U.txt(U.WARN));
            err.setText(L.t("请填写服务器地址和 Connect 邮箱"));
            return;
        }
        busy = true;
        hideIme(pass);
        go.setText(L.t("登录中…"));
        err.setTextColor(U.txt(U.DIM));
        err.setText(L.t("正在向 Emby Connect 验证…"));
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    Api.Session ss = Api.connectLogin(s, mail, p, store.deviceId());
                    List<Api.Lib> ls = Api.libraries(ss);
                    store.saveLogin(Api.normalize(s), ss.userName);
                    store.addServer(Api.normalize(s));
                    store.addUser(ss.userName);
                    store.saveLast(Api.normalize(s), ss.userName, ss.userId, ss.userName);
                    store.saveAccount(Api.normalize(s), ss.userName, ss.userId, ss.token, ss.userName);
                    saveAvatar(Api.normalize(s), ss.userId, ss.token);
                    final List<Api.Lib> fls = ls;
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            busy = false;
                            inLogin = false;
                            session = ss;
                            libs = fls;
                            showHome();
                        }
                    });
                } catch (final Exception e) {
                    final String msg = e == null || e.getMessage() == null ? L.t("未知错误") : e.getMessage();
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            busy = false;
                            go.setText(L.t("登 录"));
                            hideIme(pass);
                            pass.requestFocus();
                            err.setTextColor(U.txt(U.WARN));
                            err.setText(msg);
                        }
                    });
                }
            }
        }).start();
    }

    private TextView fieldLabel(String text) {
        return U.tv(this, text, 12, 0xFF8E9C96, false);
    }

    private LinearLayout.LayoutParams fieldLp(int topDp) {
        LinearLayout.LayoutParams l = U.lp(-1, -2);
        l.topMargin = U.dp(this, topDp);
        return l;
    }

    private LinearLayout.LayoutParams fieldLp2(int topDp) {
        LinearLayout.LayoutParams l = U.lp(-1, U.dp(this, 46));
        l.topMargin = U.dp(this, topDp);
        return l;
    }

    /** 带浮动小标签的输入框：标签压在框内左上，和参考图一致 */
    private EditText labeledField(FrameLayout box, String label, String hint) {
        EditText e = fieldEdit(box, hint);
        e.setPadding(U.dp(this, 18), U.dp(this, 17), U.dp(this, 18), 0);
        TextView lb = U.tv(this, label, 9, U.DIM, false);
        lb.setLetterSpacing(0.08f);
        FrameLayout.LayoutParams ll = new FrameLayout.LayoutParams(-2, -2);
        ll.leftMargin = U.dp(this, 18);
        ll.topMargin = U.dp(this, 5);
        box.addView(lb, ll);
        return e;
    }

    /** 协议切换胶囊：确认键 / 左右键都能 http、https 互换 */
    private TextView protoPill(final String[] val) {
        final TextView p = new TextView(this);
        U.face(p);
        p.setText(val[0]);
        p.setTextSize(16);
        p.setTypeface(null, android.graphics.Typeface.BOLD);
        p.setGravity(Gravity.CENTER);
        p.setTextColor(0xFFFFFFFF);
        p.setBackground(U.roundStroke(0x26FFFFFF, U.dp(this, U.RADIUS), U.dp(this, 1), 0x4DFFFFFF));
        p.setFocusable(true);
        p.setFocusableInTouchMode(true);
        U.ringFg(p, U.dp(this, 6));
        p.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                val[0] = val[0].equals("https") ? "http" : "https";
                p.setText(val[0]);
            }
        });
        p.setOnKeyListener(new View.OnKeyListener() {
            @Override public boolean onKey(View v, int code, KeyEvent e) {
                if (e.getAction() != KeyEvent.ACTION_DOWN) return false;
                if (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER
                        || code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT
                        || code == KeyEvent.KEYCODE_NUMPAD_ENTER) {
                    val[0] = val[0].equals("https") ? "http" : "https";
                    p.setText(val[0]);
                    return true;
                }
                return false;
            }
        });
        return p;
    }

    /** 勾选行：方块 + 标题（可带副说明），勾选状态存在 arr[0] */
    private LinearLayout chkRow(String title, String sub, boolean on, boolean[] arr, boolean enabled) {
        return chkRow(title, sub, on, arr, enabled, null);
    }

    /** onToggle：勾选状态变化时回调（登录页用来把开关立刻落盘并生效） */
    private LinearLayout chkRow(String title, String sub, boolean on,
                                final boolean[] arr, boolean enabled, final Runnable onToggle) {
        LinearLayout row = U.row(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(U.dp(this, 8), U.dp(this, 4), U.dp(this, 8), U.dp(this, 4));
        final FrameLayout sq = new FrameLayout(this);
        sq.setBackground(U.EMBY ? U.roundStroke(0xFFF2F2F7, U.dp(this, 6), U.dp(this, 1.5f), 0x1F000000)
                : U.roundStroke(U.SOFT, U.dp(this, 6), U.dp(this, 1.5f), U.BORDER));
        row.addView(sq, new LinearLayout.LayoutParams(U.dp(this, 19), U.dp(this, 19)));
        final View fill = new View(this);
        fill.setBackground(U.round(U.GLASS ? 0xF2FFFFFF : (U.ACC & 0x00FFFFFF) | 0xE6000000, U.dp(this, 4)));
        FrameLayout.LayoutParams fl = new FrameLayout.LayoutParams(-1, -1);
        fl.setMargins(U.dp(this, 4), U.dp(this, 4), U.dp(this, 4), U.dp(this, 4));
        final FrameLayout mark = new FrameLayout(this);
        FrameLayout.LayoutParams mlp = new FrameLayout.LayoutParams(-1, -1);
        mlp.setMargins(U.dp(this, 3), U.dp(this, 3), U.dp(this, 3), U.dp(this, 3));
        mark.addView(fill, new FrameLayout.LayoutParams(-1, -1));
        TextView tick = U.tv(this, "\u2713", 13, U.DG ? U.ON_ACC : 0xFF1C1C1E, true);
        tick.setTextColor(U.DG ? U.ON_ACC : 0xFF1C1C1E);
        tick.setGravity(Gravity.CENTER);
        mark.addView(tick, new FrameLayout.LayoutParams(-1, -1));
        sq.addView(mark, mlp);
        mark.setVisibility(on ? View.VISIBLE : View.INVISIBLE);
        row.setTag(mark);
        LinearLayout col = U.col(this);
        TextView t = U.tv(this, title, 12, U.TXT, false);
        col.addView(t);
        if (sub != null && !sub.isEmpty()) {
            TextView s = U.tv(this, sub, 9, U.DIM, false);
            LinearLayout.LayoutParams sl = U.lp(-1, -2);
            sl.topMargin = U.dp(this, 1);
            col.addView(s, sl);
        }
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(-1, -2);
        cl.leftMargin = U.dp(this, 10);
        row.addView(col, cl);
        arr[0] = on;
        if (enabled) {
            row.setFocusable(true);
            row.setFocusableInTouchMode(true);
            U.ringFg(row, U.dp(this, 10));
            row.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    arr[0] = !arr[0];
                    mark.setVisibility(arr[0] ? View.VISIBLE : View.INVISIBLE);
                    if (onToggle != null) onToggle.run();
                }
            });
        } else {
            row.setAlpha(0.45f);
            t.setTextColor(U.txt(U.DIM));
        }
        return row;
    }

    /** 表单按钮：primary = 实心主色，否则玻璃描边 */
    private TextView formBtn(String text, boolean primary, int heightDp) {
        final TextView b = new TextView(this);
        U.face(b);
        b.setText(text);
        b.setTextSize(15);
        b.setTypeface(null, android.graphics.Typeface.BOLD);
        b.setGravity(Gravity.CENTER);
        b.setTextColor(U.EMBY ? (primary ? U.ON_ACC : U.TXT)
                : (U.LIGHT_BG ? 0xFF1C1C1E : 0xFFFFFFFF));
        final android.graphics.drawable.Drawable idle = U.EMBY
                ? (primary ? U.round((U.ACC & 0x00FFFFFF) | 0xFF000000, U.dp(this, 6))
                           : U.roundStroke(0xFFF2F2F7, U.dp(this, 6), U.dp(this, 1.5f), 0x1A000000))
                : U.roundStroke(0x2EFFFFFF, U.dp(this, U.RADIUS), U.dp(this, 1.5f), 0x66FFFFFF);
        b.setBackground(idle);
        b.setFocusable(true);
        b.setFocusableInTouchMode(true);
        U.ringFg(b, U.dp(this, U.RADIUS));
        return b;
    }

    /** 深色胶囊输入框容器：焦点框统一用外面的白玻璃环 */
    private FrameLayout fieldBox() {
        final FrameLayout box = new FrameLayout(this);
        box.setBackground(U.inputBg(this, false));
        box.setAddStatesFromChildren(true);
        U.ringFg(box, U.dp(this, U.RADIUS));
        box.setTag(box);
        return box;
    }

    private EditText fieldEdit(final FrameLayout box, String hint) {
        final EditText e = new LineEdit(this);
        e.setHint(hint);
        e.setHintTextColor(U.DIM);
        e.setTextColor(U.txt(U.TXT));
        e.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        e.setSingleLine(true);
        e.setBackgroundColor(0x00000000);
        e.setPadding(U.dp(this, 18), 0, U.dp(this, 18), 0);
        box.addView(e, new FrameLayout.LayoutParams(-1, -1));
        return e;
    }

    private TextView chip(String text) {
        final TextView c = U.tv(this, text, 13, U.TXT, false);
        c.setPadding(U.dp(this, 12), U.dp(this, 8), U.dp(this, 12), U.dp(this, 8));
        c.setBackground(U.round(U.EMBY ? 0xFFF2F2F7 : 0xFF242C33, U.dp(this, 5)));
        c.setFocusable(true);
        c.setFocusableInTouchMode(true);
        U.ringFg(c, U.dp(this, 5));
        return c;
    }

    private void hideIme(View v) {
        try {
            android.view.inputmethod.InputMethodManager imm =
                    (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null && v != null) imm.hideSoftInputFromWindow(v.getWindowToken(), 0);
        } catch (Exception e) { }
    }

    private int ACC2() { return 0xFF1B2126; }

    private void doLogin(final String srvArg, final String usrArg, final EditText pass,
                         final TextView err, final TextView go) {
        if (busy) return;
        final String s = srvArg;
        final String u = usrArg;
        final String p = pass.getText().toString();
        final String goText = go.getText().toString();
        if (s.isEmpty() || u.isEmpty()) {
            err.setText(L.t("请填写服务器地址和用户名"));
            return;
        }
        busy = true;
        hideIme(pass);
        go.setText(L.t("连接中…"));
        err.setTextColor(U.txt(U.DIM));
        err.setText(L.t("正在连接 ") + s);
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    Api.Session ss = Api.login(s, u, p, store.deviceId());
                    List<Api.Lib> ls = Api.libraries(ss);
                    store.saveLogin(Api.normalize(s), u);
                    store.addServer(Api.normalize(s));
                    store.addUser(u);
                    store.saveLast(Api.normalize(s), u, ss.userId, ss.userName);
                    store.saveAccount(Api.normalize(s), u, ss.userId, ss.token, ss.userName);
                    saveAvatar(Api.normalize(s), ss.userId, ss.token);
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            busy = false;
                            hideIme(pass);
                            session = ss;
                            libs = ls;
                            showHome();
                        }
                    });
                } catch (Exception e) {
                    String raw = e == null || e.getMessage() == null ? L.t("未知错误") : e.getMessage();
                    String msg = raw;
                    if (raw.startsWith("HTTP 401")) msg = "用户名或密码错误";
                    else if (raw.startsWith("HTTP 403")) msg = "账号无权限或被服务器拒绝";
                    else if (raw.startsWith("HTTP 400")) msg = "服务器拒绝了这次请求（HTTP 400）";
                    else if (raw.contains("ECONNREFUSED") || raw.contains("Failed to connect")
                            || raw.contains("ConnectException") || raw.contains("timed out")
                            || raw.contains("timeout")) msg = "连接不到服务器，请检查地址与网络";
                    final String msgFinal = msg;
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            busy = false;
                            go.setText(goText);
                            hideIme(pass);
                            pass.requestFocus();
                            err.setTextColor(U.txt(U.WARN));
                            err.setText(L.t("登录失败：") + msgFinal);
                        }
                    });
                }
            }
        }).start();
    }

    // ================= 顶层框架 =================

    private LinearLayout page(String title, String subtitle) {
        root.removeAllViews();
        LinearLayout page = U.col(this);
        page.setBackgroundColor(U.pageBgColor());

        LinearLayout head = U.row(this);
        head.setPadding(U.dp(this, 50), U.dp(this, 22), U.dp(this, 50), U.dp(this, 10));
        boolean hasTitle = title != null && !title.isEmpty();
        // 官方 Emby TV：左上 绿色六边形标 + emby 字 + Home / Favorites（选中绿字）
        if (!hasTitle) {
            head.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout brand = U.row(this);
            brand.setGravity(Gravity.CENTER_VERTICAL);
            brand.addView(new EmbyMark(this),
                    new LinearLayout.LayoutParams(U.dp(this, 26), U.dp(this, 26)));
            TextView word = U.tv(this, "LonYao", 19, U.TXT, true);
            LinearLayout.LayoutParams wl = U.lp(-2, -2);
            wl.leftMargin = U.dp(this, 7);
            brand.addView(word, wl);
            head.addView(brand);
            // 首页 / 收藏：和右侧搜索、账号、设置同一套按钮（同高同圆角同焦点环）
            IconBtn hb = new IconBtn(this, IconBtn.HOME, L.t("首页"));
            hb.setPlain(true);
            homeBtn = hb;
            hb.setActive(homeTab == 0);
            hb.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    if (homeTab == 0) return;
                    homeTab = 0;
                    showHome();
                }
            });
            LinearLayout.LayoutParams hl1 = U.lp(-2, -2);
            hl1.leftMargin = U.dp(this, 18);
            head.addView(hb, hl1);

            IconBtn fb = new IconBtn(this, IconBtn.HEART, L.t("收藏"));
            fb.setPlain(true);
            fb.setActive(homeTab == 1);
            fb.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    if (homeTab == 1) return;
                    homeTab = 1;
                    showHome();
                }
            });
            LinearLayout.LayoutParams hl2 = U.lp(-2, -2);
            hl2.leftMargin = U.dp(this, 8);
            head.addView(fb, hl2);
        }
        View accent = new View(this);
        accent.setBackgroundColor(U.ACC);
        LinearLayout.LayoutParams al = new LinearLayout.LayoutParams(
                U.dp(this, U.ACCENT_BAR_W), U.dp(this, 22));
        al.rightMargin = U.dp(this, 12);
        if (hasTitle && U.ACCENT_BAR_W > 0) head.addView(accent, al);

        TextView t = U.tv(this, title == null ? "" : title, 24, U.TXT, true);
        if (hasTitle) head.addView(t);
        if (subtitle != null && !subtitle.isEmpty()) {
            TextView s = U.tv(this, "  " + subtitle, 13, U.DIM, false);
            head.addView(s);
        }
        View fill = new View(this);
        head.addView(fill, new LinearLayout.LayoutParams(0, 1, 1f));

        IconBtn search = new IconBtn(this, IconBtn.SEARCH, null);
        search.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { openSearch(); }
        });
        LinearLayout.LayoutParams shl = U.lpm(-2, -2, 0, 0, U.dp(this, 10), 0);
        head.addView(search, shl);

        IconBtn acct = new IconBtn(this, IconBtn.USER,
                session == null ? L.t("账号") : session.userName);
        acct.setAccentPill(true);
        acct.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { openAccountMenu(); }
        });
        LinearLayout.LayoutParams acl = U.lpm(-2, -2, 0, 0, U.dp(this, 12), 0);
        head.addView(acct, acl);

        IconBtn set = new IconBtn(this, IconBtn.GEAR, null);
        set.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { openSettings(); }
        });
        head.addView(set);

        if (U.EMBY) {
            // 炫酷顶栏：白纱渐隐，让首页弧形墙能顶到屏幕最上
            head.setBackground(new android.graphics.drawable.GradientDrawable(
                    android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                    new int[]{0xCCFFFFFF, 0x59FFFFFF, 0x00FFFFFF}));
        }
        // 两套主题都走浮层顶栏：滚动内容的裁剪边界就是屏幕顶，轮播才能完整滚出画面
        root.addView(page, new FrameLayout.LayoutParams(-1, -1));
        FrameLayout.LayoutParams hl = new FrameLayout.LayoutParams(-1, -2);
        hl.gravity = Gravity.TOP;
        root.addView(head, hl);
        page.setPadding(0, hasTitle ? U.dp(this, 68) : 0, 0, 0);
        return page;
    }

    /** 官方顶栏的文字导航（首页 / 收藏），选中为 Emby 绿 */
    private TextView navTab(String text, final int idx) {
        boolean on = homeTab == idx;
        TextView t = U.tv(this, text, 15, on ? U.ACC : 0xFFCFCFCF, on);
        t.setFocusable(true);
        t.setFocusableInTouchMode(true);
        t.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (homeTab == idx) return;
                homeTab = idx;
                showHome();
            }
        });
        t.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override public void onFocusChange(View v, boolean has) {
                v.setBackground(has ? U.roundStroke(0x26FFFFFF, U.dp(MainActivity.this, 4),
                        U.dp(MainActivity.this, 1), U.RING) : null);
            }
        });
        return t;
    }

    private TextView bannerTitle, bannerSub;
    private ImageView bannerImg, bannerBg;
    private Api.Item bannerItem;
    private Api.Item[] bannerItems;
    private int bannerIdx = 0;
    private Runnable bannerTick;
    private View bannerDots;
    private FrameLayout bannerBox;
    private View bannerBtn;
    private boolean detailFromBanner;

    /** 首页弧形堆叠轮播（替换原来的单张大 banner） */
    private Carousel carousel;
    private View homeBtn;
    private java.util.ArrayList<Api.Item> carouselItems;

    private Carousel buildCarousel() {
        Carousel c = new Carousel(this);
        carousel = c;
        c.setOnPick(new Carousel.OnPick() {
            @Override public void onPick(int index) {
                if (carouselItems == null || index < 0 || index >= carouselItems.size()) return;
                detailFromBanner = true;
                openDetail(carouselItems.get(index), null);
            }
        });
        // 轮播上按“上”直接跳到顶部按钮栏，不用一路按过去
        c.setOnUp(new Runnable() {
            @Override public void run() {
                if (homeBtn != null) homeBtn.requestFocus();
            }
        });
        // 一整圈播完：只有「最新」模式会借此切到下一个媒体库
        c.setOnLoop(new Runnable() {
            @Override public void run() {
                if (store.carSource() != 1) return;
                c.postDelayed(new Runnable() {
                    @Override public void run() {
                        if (c.getParent() != null) loadCarousel(c);
                    }
                }, 700);
            }
        });
        return c;
    }

    /** 拉一批片子的海报来铺弧形墙：有刮削图（海报）就用海报，没有就用视频缩略图 */
    private void loadCarousel(final Carousel c) {
        final Api.Session s0 = session;
        if (s0 == null || s0.server == null || libs.isEmpty()) { c.setVisibility(View.GONE); return; }
        final int src = store.carSource();   // 0 默认 1 最新 2 随机
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    java.util.ArrayList<Api.Item> pool = new java.util.ArrayList<Api.Item>();
                    if (src == 1) {
                        // 最新模式：一次只放一个媒体库的最新内容，按媒体库顺序一个一个往下切（每次重建首页换下一个库）
                        java.util.ArrayList<Api.Lib> usable = new java.util.ArrayList<Api.Lib>();
                        for (Api.Lib lb : libs) {
                            String t = latestTypes(lb.type);
                            if (t != null && t.length() > 0) usable.add(lb);
                        }
                        if (!usable.isEmpty()) {
                            String last = store.carLibId();
                            int start = 0;
                            for (int i = 0; i < usable.size(); i++) {
                                if (usable.get(i).id.equals(last)) { start = (i + 1) % usable.size(); break; }
                            }
                            Api.Lib lb = usable.get(start);
                            store.carLibId(lb.id);
                            try {
                                Api.Page p3 = Api.page(s0, lb.id, true, 0, 14,
                                        "DateCreated", true, null, latestTypes(lb.type));
                                if (p3 != null && p3.items != null) pool.addAll(p3.items);
                            } catch (Exception ignore) { }
                            android.util.Log.e("LMBYTEST", "carousel latest lib=" + lb.name + " idx=" + start);
                        }
                    } else if (src == 2) {
                        // 随机模式：所有媒体库各取一批最新条目，合在一起再随机抽
                        int maxLibs = Math.min(8, libs.size());
                        for (int i = 0; i < maxLibs; i++) {
                            Api.Lib lb = libs.get(i);
                            String types = latestTypes(lb.type);
                            if (types == null || types.length() == 0) types = "Movie,Episode,Series,Video";
                            try {
                                Api.Page p2 = Api.page(s0, lb.id, true, 0, 60, "DateCreated", true, null, types);
                                if (p2 != null && p2.items != null) pool.addAll(p2.items);
                            } catch (Exception ignore) { }
                        }
                    } else {
                        // 默认：第一个媒体库最新的 60 条里随机取
                        String types = latestTypes(libs.get(0).type);
                        if (types == null || types.length() == 0) types = "Movie,Episode,Video";
                        Api.Page pg = Api.page(s0, libs.get(0).id, true, 0, 60, "DateCreated", true, null, types);
                        if (pg != null && pg.items != null) pool.addAll(pg.items);
                    }
                    android.util.Log.e("LMBYTEST", "carousel pool=" + pool.size() + " src=" + src);
                    if (pool.isEmpty()) return;
                    if (src != 1) java.util.Collections.shuffle(pool);   // 「最新」保留入库顺序
                    final java.util.ArrayList<Api.Item> list = new java.util.ArrayList<Api.Item>(
                            pool.subList(0, Math.min(14, pool.size())));
                    final java.util.ArrayList<String> urls = new java.util.ArrayList<String>();
                    final java.util.ArrayList<String> names = new java.util.ArrayList<String>();
                    final java.util.ArrayList<String> pcts = new java.util.ArrayList<String>();
                    for (Api.Item it : list) {
                        String u = (it.imgTag != null && it.imgTag.length() > 0)
                                ? Api.imageUrl(s0, it.id, it.imgTag, 700)     // 刮削海报（竖版）
                                : Api.thumbUrl(s0, it.id, "", 900);           // 没刮削就用视频缩略图
                        urls.add(u);
                        names.add(cleanName(it.name));
                        // 看过一部分的片子：海报右上角挂进度角标
                        int pc = pctOf(it);
                        pcts.add(pc > 0 ? pc + "%" : "");
                    }
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            if (s0 != session || s0.server == null) return;
                            if (c.getParent() == null) return;
                            carouselItems = list;
                            c.setItems(urls, names, pcts);
                            c.startAuto();
                        }
                    });
                } catch (Exception e) { /* 轮播只是装饰，失败就空着 */ }
            }
        }).start();
    }

    /** 首页顶部大 banner（背景图 + 片名 + 简介 + 绿色「详情…」+ 圆点） */
    private View buildEmbyBanner() {
        FrameLayout f = new FrameLayout(this);
        bannerBox = f;
        f.setClipChildren(false);
        // 底层：同图降采样放大当虚化背景，保证整块区域永远是铺满的、没有黑边
        bannerBg = new ImageView(this);
        bannerBg.setScaleType(ImageView.ScaleType.FIT_XY);
        bannerBg.setBackgroundColor(0xFF0B0D10);   // 图加载出来之前的底色，别闪白
        f.addView(bannerBg, new FrameLayout.LayoutParams(-1, -1));
        // 前景：剧照裁切铺满整块（宽扁 banner 上等比缩放会留上下两条虚化，看起来就是一层雾）
        bannerImg = new ImageView(this);
        bannerImg.setScaleType(ImageView.ScaleType.CENTER_CROP);
        f.addView(bannerImg, new FrameLayout.LayoutParams(-1, -1));

        View scrim = new View(this) {
            private final android.graphics.Paint pp = new android.graphics.Paint();
            @Override protected void onDraw(android.graphics.Canvas c) {
                // 只在底部压暗保证文字可读，上半部分几乎不动，避免整张图像蒙了一层雾
                pp.setShader(new android.graphics.LinearGradient(0, 0, 0, getHeight(),
                        new int[]{0x00000000, 0x33000000, 0xE6000000},
                        new float[]{0f, 0.52f, 1f}, android.graphics.Shader.TileMode.CLAMP));
                c.drawRect(0, 0, getWidth(), getHeight(), pp);
            }
        };
        f.addView(scrim, new FrameLayout.LayoutParams(-1, -1));

        // 官方那排轮播圆点
        bannerDots = new View(this) {
            private final android.graphics.Paint dp2 = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
            @Override protected void onDraw(android.graphics.Canvas c) {
                int n = bannerItems == null ? 0 : bannerItems.length;
                if (n <= 0) return;
                float d = U.dp(getContext(), 3);
                float gap = U.dp(getContext(), 14);
                float total = n * d * 2 + (n - 1) * (gap - d * 2);
                float x = (getWidth() - total) / 2f + d;
                for (int i = 0; i < n; i++) {
                    dp2.setColor(i == bannerIdx ? 0xFFFFFFFF : 0x4DFFFFFF);
                    c.drawCircle(x, getHeight() / 2f, d, dp2);
                    x += gap;
                }
            }
        };
        FrameLayout.LayoutParams dl = new FrameLayout.LayoutParams(-1, U.dp(this, 20));
        dl.gravity = Gravity.BOTTOM;
        dl.bottomMargin = U.dp(this, 14);
        f.addView(bannerDots, dl);

        LinearLayout info = U.col(this);
        info.setClipChildren(false);
        bannerTitle = U.tv(this, "", 27, 0xFFFFFFFF, true);
        bannerTitle.setMaxLines(2);
        bannerTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
        info.addView(bannerTitle, U.lp(-1, -2));
        bannerSub = U.tv(this, "", 13, 0xFFC9C9C9, false);
        bannerSub.setMaxLines(3);
        bannerSub.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams sl = U.lp(-1, -2);
        sl.topMargin = U.dp(this, 7);
        info.addView(bannerSub, sl);

        final TextView btn = U.tv(this, L.t("详情…"), 14, 0xFFFFFFFF, true);
        bannerBtn = btn;
        btn.setGravity(Gravity.CENTER);
        btn.setBackground(U.round(U.ACC, U.dp(this, 4)));
        btn.setPadding(U.dp(this, 34), U.dp(this, 11), U.dp(this, 34), U.dp(this, 11));
        btn.setFocusable(true);
        btn.setFocusableInTouchMode(true);
        // 焦点反馈：和首页其它按钮同一套白玻璃环 + 放大
        U.ringFg(btn, U.dp(this, 4));
        btn.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override public void onFocusChange(View v, boolean has) {
                v.animate().scaleX(has ? 1.08f : 1f).scaleY(has ? 1.08f : 1f)
                        .setDuration(160).start();
            }
        });
        btn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (bannerItem != null) {
                    detailFromBanner = true;   // 退回来时焦点还回这个按钮
                    openDetail(bannerItem, null);
                }
            }
        });
        // 轮播交互：焦点在这个按钮上时，左右键翻上一张/下一张，按确认进详情
        btn.setOnKeyListener(new View.OnKeyListener() {
            @Override public boolean onKey(View v, int keyCode, android.view.KeyEvent e) {
                if (e.getAction() != android.view.KeyEvent.ACTION_DOWN) return false;
                if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_LEFT) return switchBanner(-1);
                if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_RIGHT) return switchBanner(1);
                return false;
            }
        });
        LinearLayout.LayoutParams bl = U.lp(-2, -2);
        bl.topMargin = U.dp(this, 18);
        info.addView(btn, bl);

        FrameLayout.LayoutParams il = new FrameLayout.LayoutParams(-2, -2);
        il.gravity = Gravity.BOTTOM | Gravity.LEFT;
        il.leftMargin = U.dp(this, 50);
        il.bottomMargin = U.dp(this, 44);
        f.addView(info, il);
        return f;
    }

    /** 画一张 banner：标题、年份/时长/分辨率、简介、配图、圆点 */
    private void applyBanner(Api.Item it, Api.Session s) {
        if (it == null || s == null || bannerImg == null) return;
        bannerItem = it;
        if (bannerTitle != null) bannerTitle.setText(cleanName(it.name));
        StringBuilder sb = new StringBuilder();
        if (it.year > 0) sb.append(it.year).append("    ");
        if (it.duration > 0) sb.append(U.time(it.duration)).append("    ");
        if (it.resLabel().length() > 0) sb.append(it.resLabel()).append("    ");
        if (it.overview != null && it.overview.length() > 0) sb.append("\n").append(it.overview);
        if (bannerSub != null) bannerSub.setText(sb.toString());
        // 有横版剧照就优先用剧照，没有才退回竖版缩略图
        final String url = (it.backdropTag != null && it.backdropTag.length() > 0)
                ? Api.backdropUrl(s, it.id, it.backdropTag, 2560)
                : Api.imageUrl(s, it.id, it.imgTag, 2160);
        // 轮播是整屏大图：必须走高清解码（普通 load 最长边封顶 1600，放大到 1920 就会糊）
        Img.loadFull(bannerImg, url, false, new Runnable() {
            @Override public void run() {
                if (bannerImg == null || !url.equals(bannerImg.getTag())) return;
                android.graphics.drawable.Drawable d = bannerImg.getDrawable();
                if (!(d instanceof android.graphics.drawable.BitmapDrawable)) return;
                android.graphics.Bitmap b = ((android.graphics.drawable.BitmapDrawable) d).getBitmap();
                if (b == null || b.getWidth() <= 0 || b.getHeight() <= 0) return;
                // 统一裁切铺满，前景自己就能盖整块，不要再露虚化底
                bannerImg.setScaleType(ImageView.ScaleType.CENTER_CROP);
                if (bannerBg != null) bannerBg.setVisibility(View.GONE);
                // 换图淡入（自动轮播和手动左右切换都有过渡，不是硬切）
                bannerImg.setAlpha(0f);
                bannerImg.animate().alpha(1f).setDuration(240).start();
            }
        });
        if (bannerDots != null) bannerDots.invalidate();
    }

    private void applyBanner(Api.Item it) {
        applyBanner(it, session);
    }

    /** 停掉自动轮换（离开首页时调用） */
    private void stopBannerLoop() {
        if (bannerTick != null && bannerBox != null) bannerBox.removeCallbacks(bannerTick);
        bannerTick = null;
    }

    private void loadEmbyBanner(final FrameLayout f) {
        stopBannerLoop();
        final Api.Session s0 = session;
        if (s0 == null || s0.server == null || libs.isEmpty()) { f.setVisibility(View.GONE); return; }
        final String firstLib = libs.get(0).id;
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    // 剧集库的轮播只取「剧集」本身，不要铺开成单集（不再出现"第3集"这种）
                    String types = latestTypes(libs.get(0).type);
                    if (types == null) types = "Movie,Episode,Video";
                    // 多拉一批再打乱，每次进首页挑到的都不一样
                    final Api.Page pg = Api.page(s0, firstLib, true, 0, 60, "DateCreated", true, null, types);
                    if (pg.items == null || pg.items.isEmpty()) return;
                    final java.util.ArrayList<Api.Item> pool = new java.util.ArrayList<Api.Item>(pg.items);
                    java.util.Collections.shuffle(pool);
                    final java.util.List<Api.Item> list =
                            new java.util.ArrayList<Api.Item>(pool.subList(0, Math.min(12, pool.size())));
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            // 这期间可能已经换了服务器 / 退回登录页，旧结果直接丢掉
                            if (s0 != session || s0.server == null) return;
                            if (f.getParent() == null) return;
                            bannerItems = list.toArray(new Api.Item[0]);
                            bannerIdx = 0;
                            applyBanner(bannerItems[0], s0);
                            if (bannerItems.length > 1) startBannerLoop(f);
                        }
                    });
                } catch (Exception e) { /* banner 只是装饰，失败就空着 */ }
            }
        }).start();
    }

    /** 手动左右翻轮播：翻完把自动轮播的计时重置，别刚翻完就自己跳走 */
    private boolean switchBanner(int dir) {
        if (bannerItems == null || bannerItems.length < 2) {
            android.util.Log.e("LMBYTEST", "switchBanner ignore dir=" + dir + " items=" + (bannerItems == null ? 0 : bannerItems.length));
            return false;
        }
        bannerIdx = (bannerIdx + dir + bannerItems.length) % bannerItems.length;
        android.util.Log.e("LMBYTEST", "switchBanner dir=" + dir + " idx=" + bannerIdx);
        applyBanner(bannerItems[bannerIdx]);
        if (bannerBox instanceof FrameLayout) {
            stopBannerLoop();
            startBannerLoop((FrameLayout) bannerBox);
        }
        return true;
    }

    /** 每 8 秒换一张，圆点跟着走 */
    private void startBannerLoop(final FrameLayout f) {
        stopBannerLoop();
        final Api.Session s0 = session;
        if (s0 == null || s0.server == null) return;
        bannerTick = new Runnable() {
            @Override public void run() {
                if (s0 != session || session == null) { bannerTick = null; return; }  // 换过服务器就停
                if (bannerItems == null || bannerItems.length < 2) return;
                if (bannerBox == null || bannerBox.getParent() == null) return;   // 已经离开首页
                bannerIdx = (bannerIdx + 1) % bannerItems.length;
                applyBanner(bannerItems[bannerIdx], s0);
                f.postDelayed(this, 8000);
            }
        };
        f.postDelayed(bannerTick, 8000);
    }

    /** 顶部中间的「首页 / 收藏」分段切换（原生 Emby 同款） */
    private LinearLayout tabsBar() {
        LinearLayout track = U.row(this);
        track.setGravity(Gravity.CENTER_VERTICAL);
        if (!U.EMBY) track.setBackground(U.round(0xFF3A3A3A, U.dp(this, 6)));
        track.setPadding(U.dp(this, 3), U.dp(this, 3), U.dp(this, 3), U.dp(this, 3));
        final TextView[] tabs = new TextView[2];
        String[] names = new String[]{L.t("首页"), L.t("收藏")};
        for (int i = 0; i < 2; i++) {
            final int idx = i;
            TextView t = U.tv(this, names[i], 14,
                    !U.EMBY ? (i == homeTab ? 0xFFFFFFFF : 0xFFC8C8C8)
                            : (i == homeTab ? U.TXT : U.DIM), i == homeTab);
            t.setGravity(Gravity.CENTER);
            t.setPadding(U.dp(this, 20), U.dp(this, 6), U.dp(this, 20), U.dp(this, 6));
            // 官方风格：文字 + 选中下划线；玻璃风格：深灰胶囊
            if (U.EMBY) {
                if (i == homeTab) t.setPaintFlags(t.getPaintFlags() | android.graphics.Paint.UNDERLINE_TEXT_FLAG);
            } else {
                t.setBackground(i == homeTab ? U.round(0xFF757575, U.dp(this, 6)) : null);
            }
            t.setFocusable(true);
            t.setFocusableInTouchMode(true);
            t.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    if (homeTab == idx) return;
                    homeTab = idx;
                    showHome();
                }
            });
            t.setOnFocusChangeListener(new View.OnFocusChangeListener() {
                @Override public void onFocusChange(View v, boolean has) {
                    if (U.EMBY) {
                        v.setBackground(has ? U.roundStroke(0x1AFFFFFF, U.dp(MainActivity.this, 6),
                                U.dp(MainActivity.this, 1), U.RING) : null);
                        return;
                    }
                    v.setBackground(has ? U.roundStroke(0xFF757575, U.dp(MainActivity.this, 6),
                            U.dp(MainActivity.this, 2), 0xFFFFFFFF)
                            : (idx == homeTab ? U.round(0xFF757575, U.dp(MainActivity.this, 6)) : null));
                }
            });
            tabs[i] = t;
            track.addView(t, U.lp(-2, -2));
        }
        return track;
    }

    // ================= 媒体库首页 =================

    /** 媒体库卡片高度（dp）：超小 / 小 / 标准 / 大 / 超大 */
    private static final int[] LIB_H = Store.SIZE_H_BIG;
    private boolean homeDirty;
    /** 首页「最近观看」「收藏」两行的引用，收藏改动后局部刷新用 */
    private LinearLayout homeFavRow, homeRecRow;
    private TextView homeFavHint, homeRecHint;
    private int homeRecH;
    private int homeRecW;
    /** 刚进过播放器：回来后要立刻刷新续播与缩略图右上角的进度 */
    private boolean backFromPlayer;
    private boolean showTabsFlag;
    private int homeTab = 0;

    void showHome() {
        inQuickLogin = false;
        stopQrServer();
        stopBannerLoop();
        libId = folderId = null;
        final boolean wantSearch = getSharedPreferences("lmby", 0).getBoolean("sr", false);
        final String wantQ = getSharedPreferences("lmby", 0).getString("sq", "");
        if (wantSearch) {
            getSharedPreferences("lmby", 0).edit().putBoolean("sr", false).apply();
        }
        LinearLayout page = page("", null);
        homeRecRow = null; homeRecHint = null;
        homeFavRow = null; homeFavHint = null;

        ScrollView sv = new SmoothScrollView(this);
        sv.setClipChildren(false);
        sv.setClipToPadding(false);
        sv.setVerticalScrollBarEnabled(false);
        LinearLayout body = U.col(this);
        // 收藏页没有 banner，内容会顶到屏幕最上；给浮动的顶栏让出位置，别压住标题
        int bodyTop = homeTab != 0 ? U.dp(this, 78) : U.dp(this, 10);   // 两套主题都是浮层顶栏，收藏页统一让位
        body.setPadding(U.dp(this, 50), bodyTop, U.dp(this, 50), U.dp(this, 40));

        final boolean embyFavOnly = homeTab == 1;
        LinearLayout row = U.row(this);
        TextView h = U.tv(this, L.t("媒体库"), U.EMBY ? 15 : 26, U.EMBY ? 0xFFA6A6A6 : U.TXT, !U.EMBY);
        row.addView(h);
        TextView c = U.tv(this, "   " + libs.size() + L.t(" 个"), 13, U.DIM, false);
        row.addView(c);
        body.addView(row, U.lp(-1, -2));
        if (embyFavOnly) row.setVisibility(View.GONE);

        final int libH = U.dp(this, LIB_H[Math.max(0, Math.min(2, store.homeLibSize()))]);
        // 缩略图区按 16:9 定宽度：Emby 自动拼图封面是 16:9，比例对齐后整图铺满、不裁不黑边
        final int libW = Math.round(libH * 0.8f * 16f / 9f);
        // 焦点放大要给四周留出余量，否则第一张卡放大后上边/左边会被容器裁掉
        final int zo = U.dp(this, 20);

        // ---- 媒体库：横排，遥控器左右滑 ----
        HorizontalScrollView libHsv = new HorizontalScrollView(this);
        libHsv.setHorizontalScrollBarEnabled(false);
        libHsv.setClipChildren(false);   // 焦点放大不被裁
        libHsv.setClipToPadding(false);   // 关键：不把子 View 裁到滚动区边界
        libHsv.setFocusable(false);      // 遥控器上下走时直接落到卡片上
        libHsv.setClipChildren(false);
        final LinearLayout libRow = U.row(this);
        libRow.setClipChildren(false);
        libRow.setPadding(zo, zo, zo, zo);
        for (int i = 0; i < libs.size(); i++) {
            final Api.Lib l = libs.get(i);
            Card card = new Card(this, 4f / 3f);
            card.setLabelHeightPx(Math.max(U.dp(this, 26), Math.round(libH * 0.20f)));
            card.key = l.id;
            card.setData(l.name, "", Api.imageUrl(session, l.id, "", 300));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(libW, libH);
            lp.rightMargin = U.dp(this, 18);
            card.setLayoutParams(lp);
            card.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    homeFocusKey = l.id;
                    libFocusKey = null;
                    fStackId.clear();
                    fStackName.clear();
                    openLibrary(l.id, l.name, null, null, l.type);
                }
            });
            attachCenterOnFocus(card);
            libRow.addView(card);
        }
        LinearLayout.LayoutParams llp = U.lp(-1, libH + zo * 2);
        llp.topMargin = U.dp(this, 12);
        libHsv.addView(libRow, new android.widget.FrameLayout.LayoutParams(-2, libH + zo * 2));
        body.addView(libHsv, llp);
        if (embyFavOnly) libHsv.setVisibility(View.GONE);

        if (libs.isEmpty()) {
            TextView e = U.tv(this, L.t("该账号下没有可见的媒体库。请检查 Emby 服务器是否已添加媒体库、账号是否有权限。"), 15, U.DIM, false);
            body.addView(e, U.lp(-1, -2));
        }

        // ---- 最近观看 + 收藏：各自一个区块，设置里可单独开关 ----
        final int recH = Math.round(U.dp(this, LIB_H[Math.max(0, Math.min(2, store.homeRecSize()))]) * 1.15f);
        final int recW = Math.round(recH * 16f / 9f);

        LinearLayout recBlock = U.col(this);
        TextView rh = U.tv(this, L.t("最近观看"), U.EMBY ? 15 : 20, U.EMBY ? 0xFFA6A6A6 : U.TXT, !U.EMBY);
        recBlock.addView(rh, U.lp(-1, -2));

        HorizontalScrollView recHsv = new HorizontalScrollView(this);
        recHsv.setHorizontalScrollBarEnabled(false);
        recHsv.setClipChildren(false);
        recHsv.setClipToPadding(false);
        recHsv.setFocusable(false);
        final LinearLayout recRow = U.row(this);
        recRow.setClipChildren(false);
        recRow.setPadding(zo, zo, zo, zo);
        recHsv.addView(recRow, new android.widget.FrameLayout.LayoutParams(-2, recH + zo * 2));
        LinearLayout.LayoutParams rlp = U.lp(-1, recH + zo * 2);
        rlp.topMargin = U.dp(this, 12);
        recBlock.addView(recHsv, rlp);

        TextView recHint = U.tv(this, L.t("正在读取最近观看…"), 12, U.DIM, false);
        LinearLayout.LayoutParams rhpl = U.lp(-1, -2);
        rhpl.topMargin = U.dp(this, 6);
        recBlock.addView(recHint, rhpl);

        LinearLayout.LayoutParams rbl = U.lp(-1, -2);
        rbl.topMargin = U.dp(this, 26);
        body.addView(recBlock, rbl);
        recBlock.setVisibility(embyFavOnly ? View.GONE
                : (store.showRecent() ? android.view.View.VISIBLE : android.view.View.GONE));

        // 首页不再放「收藏」横排模块；官方 UI 的「收藏」页签仍然整页显示收藏
        if (embyFavOnly) buildFavorites(body);

        // ---- 每个媒体库各一行「最新 XX」：设置里一键开启，尺寸独立可调 ----
        final int latestH = Math.round(U.dp(this, LIB_H[Math.max(0, Math.min(2, store.homeLatestSize()))]) * 1.15f);
        if (store.showLatestRows() && !embyFavOnly && session != null) {
            for (int i = 0; i < libs.size(); i++) {
                final Api.Lib l = libs.get(i);
                final String types = latestTypes(l.type);
                if (types == null) continue;
                LinearLayout blk = U.col(this);
                TextView lh = U.tv(this, L.t("最新") + " " + l.name,
                        U.EMBY ? 15 : 20, U.EMBY ? 0xFFA6A6A6 : U.TXT, !U.EMBY);
                blk.addView(lh, U.lp(-1, -2));

                HorizontalScrollView hsv = new HorizontalScrollView(this);
                hsv.setHorizontalScrollBarEnabled(false);
                hsv.setClipChildren(false);
                hsv.setClipToPadding(false);
                hsv.setFocusable(false);
                final LinearLayout rowL = U.row(this);
                rowL.setClipChildren(false);
                rowL.setPadding(zo, zo, zo, zo);
                hsv.addView(rowL, new android.widget.FrameLayout.LayoutParams(-2, latestH + zo * 2));
                LinearLayout.LayoutParams hl = U.lp(-1, latestH + zo * 2);
                hl.topMargin = U.dp(this, 12);
                blk.addView(hsv, hl);

                TextView hint = U.tv(this, L.t("正在读取…"), 12, U.DIM, false);
                LinearLayout.LayoutParams hpl = U.lp(-1, -2);
                hpl.topMargin = U.dp(this, 6);
                blk.addView(hint, hpl);

                LinearLayout.LayoutParams bl = U.lp(-1, -2);
                bl.topMargin = U.dp(this, 26);
                body.addView(blk, bl);
                loadLatest(l, rowL, hint, latestH);
            }
        }

        // 官方风格：顶部通栏大 banner，内容在下面
        LinearLayout wrap = U.col(this);
        // 轮播初始位置仍在顶栏下方，但裁剪边界是屏幕顶 —— 往上翻时海报可以完整滚出画面，不会被容器裁断
        if (!embyFavOnly) wrap.setPadding(0, U.dp(this, 84), 0, 0);
        if (!embyFavOnly && store.showCarousel()) {
            Carousel car = buildCarousel();
            int scrH = getResources().getDisplayMetrics().heightPixels;
            int bannerH = Math.max(U.dp(this, 280), Math.round(scrH * 0.55f));
            wrap.addView(car, U.lp(-1, bannerH));
            loadCarousel(car);
        }
        wrap.addView(body, U.lp(-1, -2));
        sv.addView(wrap, new ScrollView.LayoutParams(-1, -2));
        LinearLayout.LayoutParams slp = U.lp(-1, 0);
        slp.weight = 1;
        page.addView(sv, slp);

        homeScroll = sv;
        homeGrid = libRow;
        homeRecRow = recRow; homeRecHint = recHint;
        homeRecH = recH; homeRecW = recW;
        focusHomeKey();
        if (U.EMBY) {
            // 顶栏是浮层，layout 完成后焦点要落回第一张库卡，否则会停在顶栏 tab 上
            sv.postDelayed(new Runnable() {
                @Override public void run() { focusHomeKey(); }
            }, 350);
        }

        if (store.showRecent() && !embyFavOnly) loadRecent(recRow, recHint, recW, recH);
    }

    /** 收藏页：流式网格；有刮削信息显示竖海报（9:16），没有刮削只显示视频截图缩略图（16:9） */
    private void buildFavorites(final LinearLayout body) {
        LinearLayout headRow = U.row(this);
        headRow.setGravity(Gravity.CENTER_VERTICAL);
        headRow.addView(U.tv(this, L.t("收藏"), 26, U.TXT, true));
        final TextView hint = U.tv(this, L.t("   正在读取收藏…"), 13, U.DIM, false);
        headRow.addView(hint, U.lp(-2, -2));
        body.addView(headRow, U.lp(-1, -2));

        if (session == null) { hint.setText(L.t("   未登录")); return; }

        final FlowGrid grid = new FlowGrid(this);
        final int favForm = store.favForm();       // 0 自适应 1 缩略图 2 海报
        final int favMode = store.favMode();       // 0 自适应 1 统一16:9 2 统一9:16
        grid.configure(store.favHeightDp(), 0);
        grid.setRatio(store.favMode());
        LinearLayout.LayoutParams gp = U.lp(-1, -2);
        gp.topMargin = U.dp(this, 14);
        body.addView(grid, gp);

        new Thread(new Runnable() {
            @Override public void run() {
                final List<Api.Item> items = Api.favorites(session, 200);
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        hint.setText("   " + items.size() + L.t(" 个"));
                        if (items.isEmpty()) {
                            TextView e = U.tv(MainActivity.this,
                                    L.t("还没有收藏。在详情页点「最爱」就会出现在这里。"), 14, U.DIM, false);
                            LinearLayout.LayoutParams el = U.lp(-1, -2);
                            el.topMargin = U.dp(MainActivity.this, 16);
                            body.addView(e, el);
                            return;
                        }
                        Card first = null;
                        for (int i = 0; i < items.size(); i++) {
                            final Api.Item it = items.get(i);
                            final int idxF = i;
                            final boolean isSeries = "Series".equals(it.type);
                            // 形态：自适应看有没有刮削信息，也可以强制一律海报 / 一律缩略图
                            final boolean hasPoster = it.imgTag != null && !it.imgTag.isEmpty();
                            final boolean hasThumb = it.thumbTag != null && !it.thumbTag.isEmpty();
                            // 有刮削：统一 9:16 竖海报；没有刮削：视频缩略图，按「视图比例」设置
                            final boolean usePoster = posterIsPoster(it);
                            float asp = 16f / 9f;
                            if (usePoster) asp = 9f / 16f;
                            else if (favMode == 2) asp = 9f / 16f;
                            else if (favMode == 1) asp = 16f / 9f;
                            else if (it.width > 0 && it.height > 0) asp = (float) it.width / (float) it.height;
                            Card card = new Card(MainActivity.this, asp);
                            card.posterCard = usePoster;
                            card.frameAspect = it.posterAspect > 0 ? (float) it.posterAspect
                    : ((it.width > 0 && it.height > 0) ? (float) it.width / (float) it.height : 0f);
                            card.videoAspect = (it.width > 0 && it.height > 0)
                                    ? (float) it.width / (float) it.height : 0f;
                            String sub;
                            if (isSeries) {
                                sub = (it.year > 0 ? it.year + " · " : "")
                                        + (it.childCount > 0 ? it.childCount + L.t("集") : "");
                            } else if (it.series != null && !it.series.isEmpty()) {
                                sub = it.series;
                            } else {
                                sub = U.time(it.duration);
                            }
                            String img = usePoster ? Api.imageUrl(session, it.id, it.imgTag, 320)
                                    : (hasThumb ? Api.thumbUrl(session, it.id, it.thumbTag, 480)
                                                : Api.imageUrl(session, it.id, it.imgTag, 320));
                            card.setData(cleanName(it.name), sub, img);
                            if (it.rating > 0) card.setRating("★ " + String.format("%.1f", it.rating));
                            if (!isSeries) {
                                int pct = pctOf(it);
                                card.setProgress(pct);
                            }
                            LinearLayout.LayoutParams lp = U.lpm(-2, -2, 0, 0,
                                    U.dp(MainActivity.this, 14), U.dp(MainActivity.this, 14));
                            card.setLayoutParams(lp);
                            card.setOnClickListener(new View.OnClickListener() {
                                @Override public void onClick(View v) {
                                    if (isPhotoType(it.type)) { openPhoto(it, items, idxF); return; }
                                    if (isSeries || isFolderType(it.type)) { openDetail(it, (Card) v); return; }
                                    if (store.detailPage()) openDetail(it, (Card) v);
                                    else play(it);
                                }
                            });
                            if (first == null) first = card;
                            grid.addView(card);
                        }
                        final Card f0 = first;
                        if (f0 != null) f0.postDelayed(new Runnable() {
                            @Override public void run() { f0.requestFocus(); }
                        }, 200);
                    }
                });
            }
        }).start();
    }

    /** 本机播放历史（不看服务器判定，自己玩过的一定记下来） */
    /** 本机历史的作用域：服务器+用户，切号/切服务器都不串 */
    private String recentScope() {
        if (session == null) return "_anon";
        return session.server.replaceAll("[^0-9a-zA-Z]", "_") + "_" + session.userId;
    }

    private List<Api.Item> localRecent() {
        List<Api.Item> out = new ArrayList<Api.Item>();
        try {
            String js = store.recent(recentScope());
            if (js == null || js.isEmpty()) return out;
            org.json.JSONArray a = new org.json.JSONArray(js);
            for (int i = 0; i < a.length(); i++) {
                org.json.JSONObject o = a.optJSONObject(i);
                if (o == null) continue;
                Api.Item it = new Api.Item();
                it.id = o.optString("id");
                it.name = o.optString("name");
                it.series = o.optString("series");
                it.imgTag = o.optString("img");
                it.width = o.optInt("w");
                it.height = o.optInt("h");
                it.duration = o.optLong("dur");
                it.progress = o.optDouble("prog", 0);
                it.resumeMs = o.optLong("rm", 0);
                it.type = o.optString("type");
                it.seriesId = o.optString("sid");
                out.add(it);
            }
        } catch (Throwable ignored) { }
        return out;
    }

    /** 记一条本机播放历史（最新在前，最多 24 条） */
    private void recordRecent(Api.Item it) {
        if (it == null || it.id == null) return;
        try {
            String js = store.recent(recentScope());
            org.json.JSONArray old = new org.json.JSONArray(js == null || js.isEmpty() ? "[]" : js);
            org.json.JSONArray out = new org.json.JSONArray();
            org.json.JSONObject o = new org.json.JSONObject();
            o.put("id", it.id);
            o.put("name", cleanName(it.name));
            o.put("series", it.series == null ? "" : it.series);
            o.put("img", it.imgTag == null ? "" : it.imgTag);
            o.put("w", it.width);
            o.put("h", it.height);
            o.put("dur", it.duration);
            o.put("prog", it.progress);
            o.put("rm", it.resumeMs);
            o.put("type", it.type == null ? "" : it.type);
            o.put("sid", it.seriesId == null ? "" : it.seriesId);
            o.put("ts", System.currentTimeMillis());
            out.put(o);
            for (int i = 0; i < old.length() && out.length() < 24; i++) {
                org.json.JSONObject x = old.optJSONObject(i);
                if (x == null) continue;
                if (it.id.equals(x.optString("id"))) continue;
                out.put(x);
            }
            store.recent(recentScope(), out.toString());
        } catch (Throwable ignored) { }
    }

    private void loadRecent(final LinearLayout row, final TextView hint,
                            final int w, final int h) {
        if (session == null) { hint.setVisibility(android.view.View.GONE); return; }
        new Thread(new Runnable() {
            @Override public void run() {
                // 服务器最近列表打头：条目自带 PlaybackPositionTicks，续播位置才准；
                // 本机历史只用来补服务器还没同步到的条目（同一部剧只留最近看的那一集）
                final List<Api.Item> items = new ArrayList<Api.Item>();
                java.util.Set<String> seen = new java.util.HashSet<String>();
                final java.util.Map<String, Api.Item> srv = new java.util.HashMap<String, Api.Item>();
                for (Api.Item x : Api.resume(session, 24)) {
                    if (x.id == null) continue;
                    srv.put(x.id, x);
                    if (!seen.add(dedupeKeyOf(x))) continue;
                    items.add(x);
                }
                for (Api.Item x : Api.played(session, 24)) {
                    if (x.id == null) continue;
                    if (!srv.containsKey(x.id)) srv.put(x.id, x);
                    if (!seen.add(dedupeKeyOf(x))) continue;
                    items.add(x);
                }
                for (Api.Item x : localRecent()) {
                    if (x.id == null) continue;
                    Api.Item s = srv.get(x.id);
                    if (s != null) {
                        // 本机存的是「进播放器之前」的旧值，服务器有更靠后的进度就盖过来
                        if (s.resumeMs > x.resumeMs) x.resumeMs = s.resumeMs;
                        if (s.progress > x.progress) x.progress = s.progress;
                        if (x.duration <= 0) x.duration = s.duration;
                        if ((x.seriesId == null || x.seriesId.isEmpty()) && s.seriesId != null) {
                            x.seriesId = s.seriesId;
                        }
                    } else if (session != null) {
                        // 服务器列表里没有（多半是刚开播就看完了没进 Resume）：单独查一次真实进度
                        long p = Api.positionMs(session, x.id);
                        if (p > 0) {
                            x.resumeMs = p;
                            if (x.duration > 0) x.progress = 100.0 * p / x.duration;
                        }
                    }
                    if (!seen.add(dedupeKeyOf(x))) continue;
                    items.add(x);
                }
                runOnUiThread(new Runnable() {
                    @Override public void run() { populateRow(items, row, hint, h); }
                });
            }
        }).start();
    }

    /** 收藏：和「最近观看」同一套卡片，直接读服务器 IsFavorite */
    private void loadFavorites(final LinearLayout row, final TextView hint,
                               final int w, final int h) {
        if (session == null) { hint.setVisibility(android.view.View.GONE); return; }
        new Thread(new Runnable() {
            @Override public void run() {
                final List<Api.Item> items = Api.favorites(session, 24);
                runOnUiThread(new Runnable() {
                    @Override public void run() { populateRow(items, row, hint, h); }
                });
            }
        }).start();
    }

    /** 某个媒体库类型 → 「最新」模块要拉的条目类型；null 表示这个库不出模块 */
    private static String latestTypes(String libType) {
        if (libType == null) return "Movie,Series,Video";
        if ("photos".equals(libType) || "music".equals(libType)) return null;
        if ("movies".equals(libType)) return "Movie,Video,MusicVideo";
        if ("tvshows".equals(libType)) return "Series";
        if ("musicvideos".equals(libType)) return "MusicVideo";
        return "Movie,Series,Video";
    }

    private void loadLatest(final Api.Lib lib, final LinearLayout row, final TextView hint, final int h) {
        if (session == null) { if (hint != null) hint.setVisibility(android.view.View.GONE); return; }
        final String types = latestTypes(lib.type);
        if (types == null) { if (hint != null) hint.setVisibility(android.view.View.GONE); return; }
        new Thread(new Runnable() {
            @Override public void run() {
                final List<Api.Item> items = Api.latest(session, lib.id, types, 20);
                runOnUiThread(new Runnable() {
                    @Override public void run() { populatePosterRow(items, row, hint, h); }
                });
            }
        }).start();
    }

    /** 首页「最新 XX」：有刮削海报就用海报（竖版自适应），没海报才用视频帧缩略图（横版） */
    private void populatePosterRow(final List<Api.Item> items, final LinearLayout row,
                                   final TextView hint, final int h) {
        if (row == null) return;
        row.removeAllViews();
        if (hint != null) hint.setVisibility(android.view.View.GONE);
        if (items == null || items.isEmpty()) {
            if (hint != null) {
                hint.setVisibility(android.view.View.VISIBLE);
                hint.setText(L.t("还没有内容"));
            }
            return;
        }
        for (int i = 0; i < items.size() && i < 20; i++) {
            final Api.Item it = items.get(i);
            final boolean isSeries = "Series".equals(it.type);
            // 首页其他模块：有刮削信息统一走 9:16 竖海报；没刮削信息只显示视频缩略图，
            // 竖版 9:16 / 横版 16:9，同一行卡片同高、宽度按各自比例
            final boolean hasPoster = it.imgTag != null && !it.imgTag.isEmpty();
            final boolean hasThumb = it.thumbTag != null && !it.thumbTag.isEmpty();
            final boolean wide = it.width <= 0 || it.height <= 0
                    || (float) it.width / (float) it.height >= 1f;
            // 有海报就按海报真实宽高比排版，焦点框才能正好套在海报上（不套在黑边外）
            float asp = hasPoster
                    ? (it.posterAspect > 0 ? (float) it.posterAspect : 2f / 3f)
                    : (wide ? 16f / 9f : 9f / 16f);
            Card card = new Card(MainActivity.this, asp);
            card.key = it.id;
            String sub;
            if (isSeries) {
                sub = (it.year > 0 ? it.year + " · " : "")
                        + (it.childCount > 0 ? it.childCount + L.t("集") : "");
            } else if (it.series != null && !it.series.isEmpty()) {
                sub = it.series;
            } else {
                sub = U.time(it.duration);
            }
            String img = hasPoster ? Api.imageUrl(session, it.id, it.imgTag, 320)
                    : (hasThumb ? Api.thumbUrl(session, it.id, it.thumbTag, 480)
                                : Api.imageUrl(session, it.id, it.imgTag, 320));
            card.setData(cleanName(it.name), sub, img);
            if (it.rating > 0) card.setRating("★ " + String.format("%.1f", it.rating));
            // 首页海报墙不显示「XX集」角标：集数已经写在副标题里，角标只留给观看进度
            if (!isSeries) {
                int pct = pctOf(it);
                card.setProgress(pct);
            }
            int labelPx = U.dp(MainActivity.this, 46);
            card.setLabelHeightPx(labelPx);
            int cw = Math.round((h - labelPx) * asp);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(cw, h);
            lp.rightMargin = U.dp(MainActivity.this, 14);
            card.setLayoutParams(lp);
            card.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    // 剧集没有文件，永远先进详情页；其他看设置里的"详情页"开关
                    if (isSeries) { openDetail(it, (Card) v); return; }
                    if (store.detailPage()) openDetail(it, (Card) v);
                    else play(it);
                }
            });
            attachCenterOnFocus(card);
            row.addView(card);
        }
    }

    /** 收藏变化后只刷首页「收藏」那一行，不用整页重建 */
    private void refreshHomeFavorites() {
        if (homeFavRow == null || homeFavHint == null) return;
        if (homeRecH <= 0) return;
        if (!store.showFavorites()) return;
        if (homeFavRow.getWindowToken() == null) return;  // 首页不在屏幕上
        loadFavorites(homeFavRow, homeFavHint, 0, homeRecH);
    }

    /** 已看百分比：一律以 Emby 服务器为准 —— 续播位置 / 时长折算，缺位置才退回服务器的百分比 */
    private int pctOf(Api.Item it) {
        if (it == null) return 0;
        // 只看真实进度：Emby 有时把「看到一半」的条目也标成已播放，跟着 played 走会把百分比吞掉
        if (it.resumeMs > 0 && it.duration > 0) {
            int p = (int) Math.round(100.0 * it.resumeMs / it.duration);
            if (p >= 1 && p < 99) return p;
        }
        if (it.progress >= 1 && it.progress < 99.5) {
            int p = (int) Math.round(it.progress);
            if (p < 100) return Math.max(1, p);
        }
        return 0;
    }

    /** 缩略图右上角播放进度：跟媒体库同一个口径（有进度才显示 xx%，没看/看完不显示） */
    private void applyPctBadge(Card card, Api.Item it) {
        if (card == null) return;
        int pct = pctOf(it);
        card.setProgress(pct);
    }

    /** 「最近观看」去重键：同一部剧（剧名相同）只留一条，电影按自身 id；本机历史没存 seriesId，靠剧名对齐 */
    private static String dedupeKeyOf(Api.Item x) {
        if (x == null) return "";
        if (x.series != null && !x.series.isEmpty()) return "N:" + x.series;
        if (x.seriesId != null && !x.seriesId.isEmpty()) return "S:" + x.seriesId;
        return "I:" + (x.id == null ? "" : x.id);
    }

    /** 首页横排卡片填充（最近观看 / 收藏共用，保证两者外观一模一样） */    private void populateRow(final List<Api.Item> items, final LinearLayout row,
                             final TextView hint, final int h) {
        if (row == null) return;
        row.removeAllViews();
        hint.setVisibility(android.view.View.GONE);
        if (items == null || items.isEmpty()) {
            hint.setVisibility(android.view.View.VISIBLE);
            hint.setText(L.t("还没有内容"));
            return;
        }
        // 同一部剧只留最近看的那一集：按 seriesId 去重（电影/视频按自身 id）
        java.util.LinkedHashSet<String> seen = new java.util.LinkedHashSet<String>();
        int shown = 0;
        for (int i = 0; i < items.size() && shown < 24; i++) {
            final Api.Item it = items.get(i);
            String dedupeKey = dedupeKeyOf(it);
            if (!seen.add(dedupeKey)) continue;
            shown++;
            // 最近观看：一律视频缩略图（有海报也不换），竖版 9:16 / 横版 16:9，卡片同高
            final boolean hasThumb = it.thumbTag != null && !it.thumbTag.isEmpty();
            final boolean wide = it.width <= 0 || it.height <= 0
                    || (float) it.width / (float) it.height >= 1f;
            float asp = wide ? 16f / 9f : 9f / 16f;
            Card card = new Card(MainActivity.this, asp);
            card.key = it.id;
            String sub = it.series != null && !it.series.isEmpty()
                    ? it.series : U.time(it.duration);
            card.setData(cleanName(it.name), sub,
                    hasThumb ? Api.thumbUrl(session, it.id, it.thumbTag, 480)
                             : Api.imageUrl(session, it.id, it.imgTag, 320));
            { int pct = pctOf(it); card.setProgress(pct); }
            // 卡片高度里含 46dp 文字区，图片只占 h - labelPx，
            // 所以宽度必须按图片区高度算，否则图片框会被拉宽（原来成了约 2.4:1）
            int labelPx = U.dp(MainActivity.this, 46);
            card.setLabelHeightPx(labelPx);
            int cw = Math.round((h - labelPx) * asp);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(cw, h);
            lp.rightMargin = U.dp(MainActivity.this, 14);
            card.setLayoutParams(lp);
            card.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    if (store.detailPage()) openDetail(it, (Card) v);
                    else play(it);
                }
            });
            attachCenterOnFocus(card);
            row.addView(card);
        }
    }

    private String typeLabel(String type) {
        if (type == null) return L.t("媒体库");
        if (type.equals("movies")) return "电影";
        if (type.equals("tvshows")) return "电视剧";
        if (type.equals("music")) return "音乐";
        if (type.equals("homevideos")) return "家庭视频";
        if (type.equals("boxsets")) return "合集";
        return L.t("媒体库");
    }

    // ================= 库内视频 / 文件夹 =================

    /** 电影类型媒体库：入口按钮叫「电影」，列表只列电影 */
    private boolean movieLib() { return "movies".equals(libType); }

    /** 剧集类型媒体库（电视剧 / 动漫 / 综艺）：入口按钮叫「节目」，列表只列剧集 */
    private boolean showLib() { return "tvshows".equals(libType); }

    /** 拉列表时只要这些条目类型；null 表示用 Api 的默认集合 */
    private String listTypes() {
        if (movieLib()) return "Movie,Video";
        if (showLib()) return "Series";
        return null;
    }

    /** 正在看这部剧的集列表（null 表示没进剧集） */
    private Api.Item currentShow;

    void openLibrary(String id, String name, String fid, String fname, String type) {
        libId = id; libName = name; folderId = fid; folderName = fname;
        libType = type == null ? "" : type;
        folderMode = false;
        photoWall = false;
        currentShow = null;
        buildLibraryPage();
        loadItems();
    }

    /** 网格落焦点：能匹配到记忆里的那张卡就回它，否则第一张 */
    private void focusGridKey() {
        if (gridRef == null || gridRef.getChildCount() == 0) return;
        if (libFocusKey != null) {
            for (int i = 0; i < gridRef.getChildCount(); i++) {
                View v = gridRef.getChildAt(i);
                if (v instanceof Card && libFocusKey.equals(((Card) v).key)) {
                    v.requestFocus();
                    return;
                }
            }
        }
        gridRef.getChildAt(0).requestFocus();
    }

    /** 首页落焦点：回到刚才进入的那个媒体库 */
    private void focusHomeKey() {
        if (homeGrid == null || homeGrid.getChildCount() == 0) return;
        if (homeFocusKey != null) {
            for (int i = 0; i < homeGrid.getChildCount(); i++) {
                View v = homeGrid.getChildAt(i);
                if (v instanceof Card && homeFocusKey.equals(((Card) v).key)) {
                    v.requestFocus();
                    return;
                }
            }
        }
        homeGrid.getChildAt(0).requestFocus();
    }

    private FlowGrid gridRef;
    private FrameLayout detailOverlay;
    private FrameLayout detailChooser;
    private LinearLayout detailPage1, detailInfoBlock;
    // 选集：几百集的剧靠「分季标签 + 跳到第N集」快速定位
    private LinearLayout detailEpRow, detailSeasonRow;
    private android.widget.HorizontalScrollView detailEpScroll;
    private java.util.List<Api.Item> detailEps;
    private int detailSeason = -1;   // -1 = 全部季
    /** 这部剧官方 NextUp 给出的「该看的那一集」id（详情页续播/继续播放提示的第一优先口径） */
    private String nextUpEpId = "";
    private FrameLayout epJumpOverlay;
    private boolean epJumpAteBack;
    // 剧集续播按钮：等 episodes 回来后按「看到一半的那一集」补出来
    private LinearLayout detailBtnRow;
    private TextView detailResumeBtn;
    private IconBtn detailWatchBtn;
    private View detailPill;
    private android.widget.ScrollView detailScroll;
    private String techStr = "";
    private Api.Item detailItem;
    private Card detailCard;
    private ScrollView gridScroll, homeScroll;
    private ViewGroup homeGrid;
    /** 返回时要把焦点还原到的那张卡（媒体库 id / 文件夹 id） */
    private String homeFocusKey, libFocusKey;
    /** 已进入的文件夹路径（id / 名字），用于一层一层返回 */
    private final java.util.ArrayList<String> fStackId = new java.util.ArrayList<String>();
    private final java.util.ArrayList<String> fStackName = new java.util.ArrayList<String>();

    private String folderPath() {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < fStackName.size(); i++) {
            if (i > 0) b.append(" / ");
            b.append(fStackName.get(i));
        }
        return b.toString();
    }

    private TextView statusRef;

    void buildLibraryPage() {
        // 标题固定显示媒体库名字：进了文件夹也还是这个库，别在上面再叠一层文件夹名
        String title = libName == null ? "" : libName;
        if (currentShow != null) title = cleanName(currentShow.name);
        LinearLayout page = page(title, null);

        LinearLayout bar = U.row(this);
        bar.setPadding(U.dp(this, 40), 0, U.dp(this, 40), U.dp(this, 12));

        // 按媒体库类型改入口名字：电影库 →「电影」，剧集库 →「节目」
        String segName = movieLib() ? L.t("电影") : (showLib() ? L.t("节目") : L.t("全部视频"));
        final IconBtn segAll = new IconBtn(this, IconBtn.GRID, segName);
        final IconBtn segFolders = new IconBtn(this, IconBtn.FOLDER, L.t("文件夹"));
        segAll.setActive(!folderMode && !photoWall);
        segFolders.setActive(folderMode && !photoWall);
        // 照片类型媒体库：文件夹旁边多一个"照片"入口（图标用相框，别再跟"已观看"的眼睛撞）
        final IconBtn segPhotos = ("photos".equals(libType) || "homevideos".equals(libType))
                ? new IconBtn(this, IconBtn.IMAGE, L.t("照片")) : null;
        if (segPhotos != null) segPhotos.setActive(photoWall);
        bar.addView(segAll);
        LinearLayout.LayoutParams fl = U.lpm(-2, -2, U.dp(this, 10), 0, 0, 0);
        bar.addView(segFolders, fl);
        if (segPhotos != null) {
            bar.addView(segPhotos, U.lpm(-2, -2, U.dp(this, 10), 0, 0, 0));
            segPhotos.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    if (photoWall) return;
                    photoWall = true;
                    folderMode = false;
                    // 照片页是库的第一层：清掉文件夹路径，否则返回时会退回原来的子文件夹
                    fStackId.clear();
                    fStackName.clear();
                    folderId = null;
                    folderName = null;
                    libFocusKey = libId;
                    buildLibraryPage();
                    loadItems();
                }
            });
        }

        final TextView count = U.tv(this, "", 13, U.DIM, false);
        LinearLayout.LayoutParams cl = U.lpm(-2, -2, U.dp(this, 18), 0, 0, 0);
        bar.addView(count, cl);

        IconBtn libSize = new IconBtn(this, IconBtn.GRID, null);
        libSize.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { openLibThumbPanel(); }
        });
        bar.addView(libSize, U.lpm(-2, -2, U.dp(this, 12), 0, 0, 0));

        final IconBtn sortBtn = new IconBtn(this, IconBtn.SORT, null);
        sortBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { openLibSortPanel(); }
        });
        bar.addView(sortBtn, U.lpm(-2, -2, U.dp(this, 10), 0, 0, 0));

        segAll.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (!folderMode && !photoWall) return;
                folderMode = false;
                photoWall = false;
                fStackId.clear();
                fStackName.clear();
                folderId = null;
                folderName = null;
                libFocusKey = libId;
                buildLibraryPage();
                loadItems();
            }
        });
        segFolders.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (folderMode && !photoWall) return;
                folderMode = true;
                photoWall = false;
                segAll.setActive(false);
                segFolders.setActive(true);
                if (segPhotos != null) segPhotos.setActive(false);
                loadFolders();
            }
        });
        page.addView(bar, U.lp(-1, -2));

        ScrollView sv = new SmoothScrollView(this);
        sv.setClipChildren(false);
        sv.setClipToPadding(false);
        sv.setVerticalScrollBarEnabled(false);
        LinearLayout body = U.col(this);
        body.setPadding(U.dp(this, 40), U.dp(this, 4), U.dp(this, 40), U.dp(this, 30));

        statusRef = U.tv(this, L.t("正在载入…"), 14, U.DIM, false);
        body.addView(statusRef, U.lp(-1, -2));

        gridRef = new FlowGrid(this);
        gridRef.configure(store.heightDpFor(thumbScope()), 0);
        gridRef.setRatio(store.modeFor(thumbScope()));
        LinearLayout.LayoutParams gp = U.lp(-1, -2);
        gp.topMargin = U.dp(this, 12);
        body.addView(gridRef, gp);

        sv.addView(body, new ScrollView.LayoutParams(-1, -2));
        LinearLayout.LayoutParams slp = U.lp(-1, 0);
        slp.weight = 1;
        page.addView(sv, slp);

        gridScroll = sv;
        sv.getViewTreeObserver().addOnScrollChangedListener(
                new android.view.ViewTreeObserver.OnScrollChangedListener() {
            @Override public void onScrollChanged() {
                long now = android.os.SystemClock.uptimeMillis();
                if (now - lastScrollTick < 80) return;
                lastScrollTick = now;
                maybeExtendOnScroll();
                loadVisibleNow();
            }
        });
        // 集列表页只要网格：入口按钮、尺寸、排序都收起来，避免焦点乱跑
        if (currentShow != null) {
            segAll.setVisibility(View.GONE);
            segFolders.setVisibility(View.GONE);
            libSize.setVisibility(View.GONE);
            sortBtn.setVisibility(View.GONE);
            gridRef.setId(0x7f0a0103);
            gridRef.setNextFocusUpId(0x7f0a0103);
        } else {
            // 遥控器路径：网格按上 → 本库尺寸 → 右 → 排序 → 下 → 网格
            libSize.setId(0x7f0a0101);
            sortBtn.setId(0x7f0a0102);
            gridRef.setId(0x7f0a0103);
            libSize.setNextFocusRightId(0x7f0a0102);
            sortBtn.setNextFocusLeftId(0x7f0a0101);
            sortBtn.setNextFocusDownId(0x7f0a0103);
            gridRef.setNextFocusUpId(0x7f0a0101);
        }
        this.libSizeBtn = libSize;
        this.libSortBtn = sortBtn;
        this.segAll = segAll;
        this.segFolders = segFolders;
        this.countRef = count;
    }

    private IconBtn segAll, segFolders, libSizeBtn, libSortBtn;
    private TextView countRef;
    private final java.util.HashMap<String, List<Api.Item>> itemCache =
            new java.util.HashMap<String, List<Api.Item>>();
    /** 库内条目：分页热身，滚动到接近底部再续下一页（避免一次铺 1900 个 View 导致卡顿） */
    private List<Api.Item> itemsAll;
    private int itemsShown;
    private int itemsTotal;
    private long lastScrollTick;
    private static final int PAGE = 140;

    /** 服务器能直接排的写 SortBy；排不了的写 null，整表拉回本地排 */
    private static final String[] SORT_SERVER = {
            "SortName",         // 0  标题
            "CommunityRating",  // 1  IMDb 评分
            "CriticRating",     // 2  影评人评分
            "ProductionYear",   // 3  年份
            null,               // 4  分辨率（本地）
            "DateCreated",      // 5  加入日期
            "PremiereDate",     // 6  发行日期
            "DatePlayed",       // 7  播放日期
            "Runtime",          // 8  播放时长
            "PlayCount",        // 9  播放次数
            null,               // 10 比特率（本地）
            null,               // 11 媒体容器（本地）
            "OfficialRating",   // 12 家长分级（跟官方 App 一致，服务器直接排）
            null,               // 13 文件名（本地）
            null,               // 14 文件尺寸（本地）
            "Random"            // 15 随机
    };

    /** 每个库的排序偏好只从服务端读一次（跟官方 App 共用 DisplayPreferences） */
    private final java.util.ArrayList<String> sortPrefFetched = new java.util.ArrayList<String>();
    /** 已读过偏好的是哪台服务器+账号，换了就重读 */
    private String sortPrefHost = "";
    /** 随机档：已经出现过的条目 id，续批时用来去重 */
    private final java.util.HashSet<String> randomSeen = new java.util.HashSet<String>();
    private boolean randomLoading = false;
    private boolean randomExhausted = false;
    private int randomMiss = 0;

    private static final String[] SORT_NAMES = {
            L.t("标题"), L.t("IMDb评分"), L.t("影评人评分"), L.t("年份"),
            L.t("分辨率"), L.t("加入日期"), L.t("发行日期"), L.t("播放日期"),
            L.t("播放时长"), L.t("播放次数"), L.t("比特率"), L.t("媒体容器"),
            L.t("家长分级"), L.t("文件名"), L.t("文件尺寸"), L.t("随机")
    };

    /** 每种排序点选时的默认方向：文字类升序，数值/日期类从大到小 */
    private static boolean defaultAsc(int sort) {
        return sort == 0 || sort == 11 || sort == 12 || sort == 13 || sort == 15;
    }

    private String sortBy(int sort) {
        if (sort < 0 || sort >= SORT_SERVER.length) sort = 0;
        return SORT_SERVER[sort];
    }

    private boolean sortDesc(int sort) {
        return !store.libSortAsc(libId);
    }

    /** 分辨率 / 比特率 / 容器 / 文件名 / 文件尺寸服务器排不了，本地排 */
    private boolean localSort(int sort) {
        return sort == 4 || sort == 10 || sort == 11 || sort == 13 || sort == 14;
    }

    /** 这个排序项在服务端 DisplayPreferences 里存的值；本地专有的字段用 ly: 前缀自己认 */
    private String sortPrefValue(int sort) {
        String f = sortBy(sort);
        return f == null ? ("ly:" + sort) : f;
    }

    /** 把服务端存的 SortBy 反查回我们的排序项下标，认不出就 -1 */
    private int sortIndexFromPref(String by) {
        if (by == null || by.isEmpty()) return -1;
        if (by.startsWith("ly:")) {
            try { return Integer.parseInt(by.substring(3)); } catch (Throwable t) { return -1; }
        }
        String first = by;
        int c = first.indexOf(',');
        if (c >= 0) first = first.substring(0, c);
        for (int i = 0; i < SORT_SERVER.length; i++) {
            if (first.equalsIgnoreCase(SORT_SERVER[i])) return i;
        }
        return -1;
    }

    /** 服务端偏好覆盖本地缓存（没存过就保留用户原来的选择） */
    private void applySortPref(String lib, org.json.JSONObject cp) {
        if (cp == null || store == null || lib == null) return;
        String by = cp.optString("SortBy", "");
        if (by == null || by.isEmpty()) return;
        int idx = sortIndexFromPref(by);
        if (idx < 0 || idx >= SORT_NAMES.length) return;
        String ord = cp.optString("SortOrder", "a");
        boolean desc = "d".equalsIgnoreCase(ord) || "descending".equalsIgnoreCase(ord);
        store.libSort(lib, idx);
        store.libSortAsc(lib, !desc);
    }

    /** 点排序时顺手写回服务端，换设备 / 重装后照样记得住 */
    private void saveSortPref(String lib, int idx, boolean asc) {
        if (session == null || lib == null) return;
        final String by = sortPrefValue(idx);
        final boolean desc = !asc;
        new Thread(new Runnable() {
            @Override public void run() {
                try { Api.saveSortPrefs(session, lib, by, desc); } catch (Throwable ignored) { }
            }
        }).start();
    }

    private static String fileNameOf(Api.Item it) {
        String p = it.path == null ? "" : it.path;
        int i = Math.max(p.lastIndexOf('/'), p.lastIndexOf('\\'));
        return (i >= 0 && i + 1 < p.length()) ? p.substring(i + 1) : p;
    }

    private void sortLocally(List<Api.Item> list, int sort) {
        if (!localSort(sort) || list == null || list.isEmpty()) return;
        final boolean asc = store.libSortAsc(libId);
        try {
            java.util.Collections.sort(list, new java.util.Comparator<Api.Item>() {
                @Override public int compare(Api.Item a, Api.Item b) {
                    if (a == null || b == null) return 0;
                    int r;
                    switch (sort) {
                        case 4:  {   // 分辨率
                            long va = (long) a.width * a.height, vb = (long) b.width * b.height;
                            r = Long.compare(va, vb);
                            break;
                        }
                        case 10: r = Integer.compare(a.bitrate, b.bitrate); break;                 // 比特率
                        case 11: r = a.container.compareToIgnoreCase(b.container); break;          // 媒体容器
                        case 12: r = a.officialRating.compareToIgnoreCase(b.officialRating); break; // 家长分级
                        case 13: r = fileNameOf(a).compareToIgnoreCase(fileNameOf(b)); break;      // 文件名
                        default: r = Long.compare(a.size, b.size); break;                          // 文件尺寸
                    }
                    return asc ? r : -r;
                }
            });
        } catch (Throwable ignored) { }
    }

    void loadItems() {
        if (session == null) return;
        // 照片墙优先判断，避免切到照片时被上一次视频加载的 busy 挡住
        if (photoWall) { loadPhotos(); return; }
        // 换服务器 / 换账号就把上一台读过的记录清掉
        String prefHost = session == null ? "" : (session.server + "|" + session.userId);
        if (!prefHost.equals(sortPrefHost)) { sortPrefHost = prefHost; sortPrefFetched.clear(); }
        // 每个库第一次进来，先从服务端读一次排序偏好（官方 App 存的就是这份），读完再加载
        if (libId != null && !sortPrefFetched.contains(libId)) {
            sortPrefFetched.add(libId);
            final String pLib = libId;
            new Thread(new Runnable() {
                @Override public void run() {
                    try {
                        applySortPref(pLib, Api.displayPrefs(session, pLib));
                    } catch (Throwable ignored) { }
                    itemCache.clear();
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            busy = false;
                            loadItems();
                        }
                    });
                }
            }).start();
            return;
        }
        if (busy) return;
        busy = true;
        if (gridRef != null) {
            // 用这个库自己记住的比例 / 大小（文件夹卡自身固定 16:9，由 FlowGrid 内部处理）
            gridRef.configure(store.heightDpFor(thumbScope()), 0);
        gridRef.setRatio(store.modeFor(thumbScope()));
            gridRef.removeAllViews();
        }
        if (statusRef != null) { statusRef.setVisibility(View.VISIBLE); statusRef.setText(L.t("正在载入视频…")); }
        final String parent = folderId != null ? folderId : libId;
        final String types = listTypes();
        final int sort = store.libSort(libId);
        // 随机排序：像官方 Emby 那样每次进库现随机一批，绝不缓存、也不分页补全
        // （服务器每次 Random 都是新的顺序，硬拼第二页只会得到重复卡片）
        final boolean randomSort = sort == 15;
        final String cacheKey = parent + "#" + (types == null ? "" : types) + "#" + sort
                + (store.libSortAsc(libId) ? "a" : "d");
        List<Api.Item> cached = randomSort ? null : itemCache.get(cacheKey);
        if (cached != null && !cached.isEmpty()) {
            busy = false;
            fillItems(cached, cached.size());
            return;
        }
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    if (randomSort) {
                        // 一次只取一页（够铺十来屏），保证不重复；进库/切排序都会重新随机
                        final Api.Page pr = Api.page(session, parent, true, 0, 300,
                                "Random", false, null, types);
                        runOnUiThread(new Runnable() {
                            @Override public void run() {
                                busy = false;
                                randomSeen.clear();
                                randomExhausted = false;
                                randomMiss = 0;
                                if (pr != null && pr.items != null) {
                                    for (Api.Item it : pr.items) {
                                        if (it != null && it.id != null) randomSeen.add(it.id);
                                    }
                                }
                                fillItems(pr.items, pr.total);
                            }
                        });
                        return;
                    }
                    if (localSort(sort)) {
                        // 这些字段服务器排不了，整库分页拉回本地排（不再截断在 3000 条）
                        final List<Api.Item> all = Api.pageAll(session, parent, true, "MediaSources", types);
                        sortLocally(all, sort);
                        itemCache.put(cacheKey, all);
                        runOnUiThread(new Runnable() {
                            @Override public void run() {
                                busy = false;
                                fillItems(all, all.size());
                            }
                        });
                        return;
                    }
                    // 第一步：只取前 120 条，先把首屏画出来
                    final Api.Page p1 = Api.page(session, parent, true, 0, 120, sortBy(sort), sortDesc(sort), null, types);
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            busy = false;
                            fillItems(p1.items, p1.total);
                        }
                    });
                    if (p1.total <= p1.items.size()) {
                        itemCache.put(cacheKey, p1.items);
                        return;
                    }
                    // 第二步：后台把剩下的补齐。服务器单次最多只给 3000 条，
                    // 所以必须循环拉，否则超过 3120 条的库永远只能看到前 3120 个
                    final java.util.List<Api.Item> full = new ArrayList<Api.Item>(p1.items);
                    final int want = p1.total;
                    int guard = 0;
                    while (full.size() < want && guard++ < 40) {
                        Api.Page pn = Api.page(session, parent, true, full.size(), 3000,
                                sortBy(sort), sortDesc(sort), null, types);
                        if (pn == null || pn.items == null || pn.items.isEmpty()) break;
                        full.addAll(pn.items);
                        // 每拉完一批就把总数 / 已载入刷一遍，用户在底部时顺手续页。
                        // 交给界面的是当前这批的快照：后台还在往 full 里加，不能让它边加边被读
                        final java.util.List<Api.Item> snap = new ArrayList<Api.Item>(full);
                        runOnUiThread(new Runnable() {
                            @Override public void run() {
                                itemsAll = snap;
                                itemsTotal = want;
                                // 关键：虚拟网格的条数必须跟着数据一起扩，否则滚到第 120 条就到底了
                                growVirtual();
                                itemsShown = snap.size();
                                updateCount();
                                maybeExtendOnScroll();
                            }
                        });
                    }
                    itemCache.put(cacheKey, full);
                    final java.util.List<Api.Item> done = new ArrayList<Api.Item>(full);
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            itemsAll = done;
                            itemsTotal = want > 0 ? want : done.size();
                            growVirtual();
                            itemsShown = done.size();
                            updateCount();
                            loadVisibleNow();
                        }
                    });
                } catch (Exception e) {
                    final String m = e.getMessage();
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            busy = false;
                            if (statusRef != null) statusRef.setText(L.t("载入失败：") + m);
                        }
                    });
                }
            }
        }).start();
    }

    /** 照片墙：把这个媒体库里的全部图片拉回来铺成网格 */
    private void loadPhotos() {
        if (session == null || libId == null) return;
        busy = true;
        if (gridRef != null) {
            gridRef.configure(store.heightDpFor(thumbScope()), 0);
        gridRef.setRatio(store.modeFor(thumbScope()));
            gridRef.removeAllViews();
        }
        if (statusRef != null) { statusRef.setVisibility(View.VISIBLE); statusRef.setText(L.t("正在载入照片…")); }
        final String cacheKey = libId + "#photos";
        List<Api.Item> cached = itemCache.get(cacheKey);
        if (cached != null && !cached.isEmpty()) {
            busy = false;
            fillItems(cached, cached.size());
            return;
        }
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    // 先拿一页立刻铺出来，后台再把剩下的补进列表
                    final Api.Page p1 = Api.photosPage(session, libId, 0, 400);
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            busy = false;
                            fillItems(p1.items, p1.total);
                        }
                    });
                    if (p1.total <= p1.items.size()) {
                        itemCache.put(cacheKey, p1.items);
                        android.util.Log.e("LMBY", "照片墙 载入完成 数量=" + p1.items.size());
                        return;
                    }
                    final List<Api.Item> all = new ArrayList<Api.Item>(p1.items);
                    while (all.size() < p1.total && all.size() < 20000) {
                        Api.Page nx = Api.photosPage(session, libId, all.size(), 400);
                        if (nx.items.isEmpty()) break;
                        all.addAll(nx.items);
                    }
                    itemCache.put(cacheKey, all);
                    android.util.Log.e("LMBY", "照片墙 补全完成 数量=" + all.size());
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            itemsAll = all;
                            itemsTotal = all.size();
                            growVirtual();
                            itemsShown = all.size();
                            updateCount();
                            loadVisibleNow();
                        }
                    });
                } catch (Exception e) {
                    final String m = e.getMessage();
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            busy = false;
                            if (statusRef != null) statusRef.setText(L.t("载入失败：") + m);
                        }
                    });
                }
            }
        }).start();
    }

    void loadFolders() {
        if (busy || session == null) return;
        busy = true;
        if (gridRef != null) {
            // 目录视图同样受「比例 / 大小」设置控制
            gridRef.configure(store.heightDpFor(scopeFold()), 0);
        gridRef.setRatio(store.modeFor(scopeFold()));
            gridRef.removeAllViews();
        }
        if (statusRef != null) { statusRef.setVisibility(View.VISIBLE); statusRef.setText(L.t("正在读取目录…")); }
        final String target = folderId != null ? folderId : libId;
        final int sort = store.libSort(libId);
        final boolean desc = sortDesc(sort);
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    // 只取这一层的东西：本目录的子文件夹 + 本目录直接放的视频（不递归）
                    final List<Api.Item> kids = Api.childrenMixed(session, target, 500,
                            localSort(sort) ? null : sortBy(sort), desc);
                    final List<Api.Item> folders = new ArrayList<Api.Item>();
                    final List<Api.Item> vids = new ArrayList<Api.Item>();
                    final List<Api.Item> imgs = new ArrayList<Api.Item>();
                    for (int i = 0; i < kids.size(); i++) {
                        Api.Item it = kids.get(i);
                        if (isFolderType(it.type)) folders.add(it);
                        else if (isPhotoType(it.type)) imgs.add(it);
                        else vids.add(it);
                    }
                    sortLocally(vids, sort);
                    sortLocally(imgs, sort);
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            busy = false;
                            if (gridRef == null) return;
                            gridRef.removeAllViews();
                            itemsAll = null; itemsShown = 0; itemsTotal = 0;
                            if (countRef != null) {
                                countRef.setText(folders.size() + L.t(" 个文件夹 · ") + vids.size() + L.t(" 个视频")
                                        + (imgs.isEmpty() ? "" : " · " + imgs.size() + L.t(" 张图片")));
                            }
                            if (folders.isEmpty() && vids.isEmpty() && imgs.isEmpty()) {
                                if (statusRef != null) statusRef.setText(L.t("这个目录里没有内容。"));
                                return;
                            }
                            if (statusRef != null) statusRef.setVisibility(View.GONE);
                            // 先摆子目录
                            for (int i = 0; i < folders.size(); i++) {
                                final Api.Item it = folders.get(i);
                                Card card = new Card(MainActivity.this, 16f / 9f);
                                card.folderCard = true;
                                card.key = it.id;
                                card.setData(it.name, L.t("文件夹"), "");
                                card.setBadge(L.t("文件夹"));
                                // 兜底：即使自绘没生效，也保证这一格是块能看见的浅色砖
                                card.setBackground(U.roundStroke(0xFF2C343D, U.dp(MainActivity.this, U.RADIUS),
                                        U.dp(MainActivity.this, 1), 0x40FFFFFF));
                                card.img.setVisibility(View.GONE);   // 露出文件夹图标
                                LinearLayout.LayoutParams lp = U.lpm(-2, -2, 0, 0, U.dp(MainActivity.this, 14), U.dp(MainActivity.this, 14));
                                card.setLayoutParams(lp);
                                card.aspect = 16f / 9f;
                                card.setOnClickListener(new View.OnClickListener() {
                                    @Override public void onClick(View v) { enterFolder(it); }
                                });
                                gridRef.addView(card);
                           }
                            // 再摆当前目录里的视频，目录里就能直接点开
                            // 自适应模式：竖屏视频按 9:16、横屏按 16:9；其他模式用统一比例
                            int vmode = effMode(scopeFold());
                            for (int i = 0; i < vids.size(); i++) {
                                final Api.Item it = vids.get(i);
                                float vasp = 16f / 9f;
                                if (vmode == 2) vasp = 9f / 16f;
                                else if (vmode == 0 && it.width > 0 && it.height > 0)
                                    vasp = (float) it.width / (float) it.height;
                                if (vasp < 0.45f) vasp = 0.45f;
                                if (vasp > 3f) vasp = 3f;
                                Card card = new Card(MainActivity.this, vasp);
                                card.key = it.id;
                                String sub = (it.year > 0 ? it.year + " · " : "") + U.time(it.duration);
                                card.lazy = true;
                                card.setData(cleanName(it.name), sub, Api.imageUrl(session, it.id, it.imgTag, 360));
                                { int pct = pctOf(it); card.setProgress(pct); }
                                LinearLayout.LayoutParams lp = U.lpm(-2, -2, 0, 0, U.dp(MainActivity.this, 14), U.dp(MainActivity.this, 14));
                                card.setLayoutParams(lp);
                                card.aspect = vasp;
                                letterbox(card);
                                card.setOnClickListener(new View.OnClickListener() {
                                    @Override public void onClick(View v) {
                                        if (store.detailPage()) openDetail(it, (Card) v);
                                        else play(it);
                                    }
                                });
                                gridRef.addView(card);
                            }
                            // 再摆这一层里的图片，点开看大图
                            int pmode = effMode(scopeFold());
                            for (int i = 0; i < imgs.size(); i++) {
                                final Api.Item it = imgs.get(i);
                                float asp;
                                if (pmode == 2) asp = 9f / 16f;
                                else if (pmode == 1 || pmode == 3) asp = 16f / 9f;
                                else {
                                    asp = (it.width > 0 && it.height > 0)
                                            ? (float) it.width / (float) it.height : 1f;
                                    if (asp < 0.45f) asp = 0.45f;
                                    if (asp > 2.2f) asp = 2.2f;
                                }
                                Card card = new Card(MainActivity.this, asp);
                                card.key = it.id;
                                String sub = it.year > 0 ? (it.year + L.t(" · 图片")) : L.t("图片");
                                card.lazy = true;
                                card.setData(cleanName(it.name), sub,
                                        Api.imageUrl(session, it.id, it.imgTag, 480));
                                letterbox(card);
                                LinearLayout.LayoutParams lp = U.lpm(-2, -2, 0, 0,
                                        U.dp(MainActivity.this, 14), U.dp(MainActivity.this, 14));
                                card.setLayoutParams(lp);
                                card.aspect = asp;
                                final java.util.List<Api.Item> plist = imgs;
                                final int pidx = i;
                                card.setOnClickListener(new View.OnClickListener() {
                                    @Override public void onClick(View v) {
                                        openPhoto(it, plist, pidx);
                                    }
                                });
                                // 焦点落在这张图 → 提前拉好它和后面几张，点开即看，方向键往下切也顺
                                card.setOnFocusChangeListener(new View.OnFocusChangeListener() {
                                    @Override public void onFocusChange(View v, boolean has) {
                                        if (has) prefetchPhotoAround(plist, pidx);
                                    }
                                });
                                card.setOnKeyListener(new View.OnKeyListener() {
                                    @Override public boolean onKey(View v, int keyCode, android.view.KeyEvent e) {
                                        if (e.getAction() == android.view.KeyEvent.ACTION_DOWN
                                                && (keyCode == android.view.KeyEvent.KEYCODE_DPAD_DOWN
                                                || keyCode == android.view.KeyEvent.KEYCODE_DPAD_RIGHT
                                                || keyCode == android.view.KeyEvent.KEYCODE_DPAD_UP
                                                || keyCode == android.view.KeyEvent.KEYCODE_DPAD_LEFT)) {
                                            // 还没真正移动焦点，先按即将去的方向预取
                                            prefetchPhotoAround(plist, pidx + 1);
                                        }
                                        return false;
                                    }
                                });
                                gridRef.addView(card);
                            }
                            loadVisibleNow();
                            focusGridKey();
                        }
                    });
                } catch (Exception e) {
                    final String m = e.getMessage();
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            busy = false;
                            if (statusRef != null) statusRef.setText(L.t("读取失败：") + m);
                        }
                    });
                }
            }
        }).start();
    }

    /** 退到上一层文件夹（最外层则回到库根的文件夹列表），焦点落在刚退出的那张卡 */
    private void goUpFolder() {
        String leaveId = folderId;
        int n = fStackId.size();
        if (n >= 2) {
            fStackId.remove(n - 1);
            fStackName.remove(n - 1);
            folderId = fStackId.get(fStackId.size() - 1);
            folderName = folderPath();
        } else {
            fStackId.clear();
            fStackName.clear();
            folderId = null;
            folderName = null;
        }
        libFocusKey = leaveId;
        folderMode = true;
        buildLibraryPage();
        loadFolders();
    }

    private void enterFolder(Api.Item f) {
        libFocusKey = f.id;
        fStackId.add(f.id);
        fStackName.add(f.name);
        folderId = f.id;
        folderName = folderPath();
        folderMode = true;
        buildLibraryPage();
        loadFolders();
    }

    private void fillItems(List<Api.Item> items) { fillItems(items, items.size()); }

    private void fillItems(List<Api.Item> items, int total) {
        if (gridRef == null) return;
        itemsAll = items;
        itemsTotal = total > 0 ? total : items.size();
        if (items.isEmpty()) {
            gridRef.removeAllViews();
            itemsShown = 0;
            updateCount();
            if (statusRef != null) statusRef.setText(L.t("这里还没有视频。若服务器上确实有内容，请确认该媒体库已扫描完成。"));
            return;
        }
        if (statusRef != null) statusRef.setVisibility(View.GONE);
        applyVirtual();
        itemsShown = items.size();
        updateCount();
        gridRef.postDelayed(new Runnable() {
            @Override public void run() { focusGridKey(); }
        }, 80);
    }

    private float[] gridAsp;
    private boolean[] gridFold;

    /** 按当前设置算出每一条的最终比例（几何先算出来，卡等滚到才建） */
    private float[] calcAsp(int n) {
        float[] asp = new float[Math.max(1, n)];
        int mode = store.modeFor(thumbScope());
        for (int i = 0; i < n && itemsAll != null && i < itemsAll.size(); i++) {
            Api.Item it = itemsAll.get(i);
            boolean pc = !photoWall && posterIsPoster(it);
            float a;
            if (pc) a = 9f / 16f;                                      // 刮削竖海报永远 9:16，管道位优先
            else if (mode == 1) a = 16f / 9f;
            else if (mode == 2) a = 9f / 16f;
            else if (it.width > 0 && it.height > 0) a = it.width < it.height ? 9f / 16f : 16f / 9f;
            else if (it.posterAspect > 0f) a = it.posterAspect < 1f ? 9f / 16f : 16f / 9f;
            else a = 16f / 9f;
            if (a < 0.45f) a = 0.45f;
            if (a > 3f) a = 3f;
            asp[i] = a;
        }
        return asp;
    }

    /** 把整批条目交给网格：只算几何，卡按窗口按需生成 */
    private void applyVirtual() {
        if (gridRef == null) return;
        int n = itemsAll == null ? 0 : itemsAll.size();
        gridAsp = calcAsp(n);
        gridFold = new boolean[Math.max(1, n)];
        gridRef.startVirtual(n, gridAsp, gridFold, new FlowGrid.ItemFactory() {
            @Override public View create(int index) {
                if (itemsAll == null || index < 0 || index >= itemsAll.size())
                    return new Card(MainActivity.this, 16f / 9f);
                return makeCard(itemsAll, index);
            }
        });
    }

    /** 数据加长了（随机续批）：只扩大几何，已经建好的卡保持不动 */
    private void growVirtual() {
        if (gridRef == null || itemsAll == null) return;
        int n = itemsAll.size();
        gridAsp = calcAsp(n);
        gridFold = new boolean[Math.max(1, n)];
        gridRef.growVirtual(n, gridAsp, gridFold);
    }

    private void updateCount() {
        if (countRef == null) return;
        if (itemsAll == null) return;
        if (photoWall) {
            countRef.setText(itemsShown < itemsAll.size()
                    ? itemsTotal + L.t(" 张照片 · 已载入 ") + itemsShown
                    : itemsTotal + L.t(" 张照片"));
            return;
        }
        // 单位跟着当前视图走：库列表是「部剧集」/「个视频」，进了某部剧就是「集」
        String unit = currentShow != null ? L.t("集") : (showLib() ? L.t("部剧集") : L.t("个视频"));
        if (itemsShown < itemsAll.size()) {
            countRef.setText(itemsTotal + " " + unit + L.t(" · 已载入 ") + itemsShown);
        } else {
            countRef.setText(itemsTotal + " " + unit);
        }
    }

    /** 窗口化后不再"铺卡"：几何覆盖全部条目，滚到哪建哪；这里只在快到末尾时提前要下一批 */
    private void addNextPage() {
        if (gridRef == null || itemsAll == null) return;
        itemsShown = itemsAll.size();
        updateCount();
        prefetchMoreIfRandom();
    }

    /** 随机档：手里剩不到 15 张就提前要下一批（对齐官方 Emby 的 itemsLoaded-15 预取线） */
    private void prefetchMoreIfRandom() {
        if (libId == null || store.libSort(libId) != 15) return;
        if (itemsAll == null) return;
        if (itemsShown + 15 >= itemsAll.size()) loadMoreRandom();
    }

    /** 只加载可视区（含预取）内的缩略图 */
    private void loadVisibleNow() {
        if (gridRef == null || gridScroll == null) return;
        int top = gridScroll.getScrollY() - gridRef.getTop();
        int h = gridScroll.getHeight();
        gridRef.loadVisible(top - U.dp(this, 200), top + h + U.dp(this, 400));
    }

    private void maybeExtendOnScroll() {
        if (gridRef == null || gridScroll == null || itemsAll == null) return;
        int top = gridScroll.getScrollY() - gridRef.getTop();
        gridRef.ensureVirtual(top, top + gridScroll.getHeight());
        if (itemsShown >= itemsAll.size()) prefetchMoreIfRandom();
    }

    /** 随机档续批：同一批里重复的丢掉，只把没见过的追加进列表 */
    private void loadMoreRandom() {
        if (randomLoading || session == null || itemsAll == null) return;
        if (randomExhausted) return;
        if (itemsTotal > 0 && itemsAll.size() >= itemsTotal) return;
        randomLoading = true;
        final String parent = folderId != null ? folderId : libId;
        final String types = listTypes();
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    Api.Page pg = Api.page(session, parent, true, 0, 300, "Random", false, null, types);
                    final java.util.ArrayList<Api.Item> add = new java.util.ArrayList<Api.Item>();
                    if (pg != null && pg.items != null) {
                        for (Api.Item it : pg.items) {
                            if (it == null || it.id == null) continue;
                            if (randomSeen.add(it.id)) add.add(it);
                        }
                    }
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            randomLoading = false;
                            if (add.isEmpty()) {
                                // 连两批都是旧的，说明整库差不多翻完了，别再空转
                                if (++randomMiss > 2) randomExhausted = true;
                                return;
                            }
                            randomMiss = 0;
                            itemsAll.addAll(add);
                            // 虚拟网格：数据加长后只扩几何，滚到那一段才建卡
                            growVirtual();
                            itemsShown = itemsAll.size();
                            updateCount();
                            loadVisibleNow();
                        }
                    });
                } catch (Throwable ignored) {
                    runOnUiThread(new Runnable() {
                        @Override public void run() { randomLoading = false; }
                    });
                }
            }
        }).start();
    }

    /** 这张卡的图算「刮削竖海报」，还是服务器自动截的视频帧 */
    private boolean posterIsPoster(Api.Item it) {
        if (it.imgTag == null || it.imgTag.isEmpty()) return false;
        String t = it.type == null ? "" : it.type;
        if ("MusicVideo".equals(t) || "Video".equals(t)
                || "Photo".equals(t) || "TvChannel".equals(t)) return false;
        // 关键：只有真·竖版海报才算「海报卡」。Emby 给视频自动生成的 Primary 是视频帧，
        // 比例跟视频一样（常见 16:9），塞进 9:16 竖卡就变成中间一条小图 + 上下黑边，
        // 表现就是「刮削的图比例有时候不是 9:16」
        if (it.posterAspect > 0f) return it.posterAspect < 0.8f;
        if (it.width > 0 && it.height > 0) return it.width < it.height;
        return true;   // 电影 / 剧集 / 季 / 集 这类才有刮削海报
    }

    /** 分批把卡片加进网格：首屏先出，剩下的持续补齐 */
    private void addCards(List<Api.Item> all, int from, int to) {
        if (gridRef == null) return;
        for (int i = from; i < to; i++) gridRef.addView(makeCard(all, i));
    }

    /** 造一张卡但不挂进网格：窗口化后由网格滚动到哪就调哪一条 */
    private Card makeCard(List<Api.Item> all, final int i) {
        {
            final Api.Item it = all.get(i);
            final boolean isSeries = "Series".equals(it.type);
            final boolean isEp = "Episode".equals(it.type);
            // 刮削海报优先；没有海报才用视频帧缩略图
            final boolean hasPoster = !photoWall && it.imgTag != null && !it.imgTag.isEmpty();
            final boolean hasThumb = !photoWall && it.thumbTag != null && !it.thumbTag.isEmpty();
            final boolean posterCard = !photoWall && posterIsPoster(it);   // 只认刮削竖海报
            final int form = store.modeFor(thumbScope());   // 只作用于「没有刮削」的视频缩略图
            // 有刮削：统一 9:16 竖海报；没有刮削：视频缩略图，按「视图比例」设置
            float aspect = 16f / 9f;
            if (photoWall) {
                if (it.width > 0 && it.height > 0) aspect = (float) it.width / (float) it.height;
            } else if (posterCard) {
                aspect = 9f / 16f;
            } else if (form == 2) {
                aspect = 9f / 16f;
            } else if (form == 1) {
                aspect = 16f / 9f;
            } else if (it.width > 0 && it.height > 0) {
                aspect = it.width < it.height ? 9f / 16f : 16f / 9f;
            }
            final int idx = i;
            Card card = new Card(this, aspect);
            card.posterCard = posterCard;
            card.frameAspect = it.posterAspect > 0 ? (float) it.posterAspect
                    : ((it.width > 0 && it.height > 0) ? (float) it.width / (float) it.height : 0f);
            // 视频文件本身的横竖：「自动」比例按它排，跟服务器上的库类型无关
            card.videoAspect = (it.width > 0 && it.height > 0)
                    ? (float) it.width / (float) it.height : 0f;
            String sub;
            if (photoWall) {
                sub = (it.width > 0 && it.height > 0) ? it.width + " × " + it.height : L.t("照片");
            } else if (isSeries) {
                sub = (it.year > 0 ? it.year + " · " : "")
                        + (it.childCount > 0 ? it.childCount + L.t("集") : "");
            } else if (isEp && it.index > 0) {
                sub = U.time(it.duration);      // 集号已经写在右上角角标里了，副标题只留时长
            } else if (it.series != null && !it.series.isEmpty()) {
                sub = it.series + (it.index > 0 ? L.t(" · 第 ") + it.index + L.t(" 集") : "");
            } else {
                sub = (it.year > 0 ? it.year + " · " : "") + U.time(it.duration);
            }
            card.lazy = true;
            card.key = it.id;
            // 有刮削海报就显示海报；没有海报才显示视频帧缩略图（Thumb）
            String imgUrl;
            if (posterCard) {
                imgUrl = Api.imageUrl(session, it.id, it.imgTag, 360);
            } else if (hasThumb) {
                imgUrl = Api.thumbUrl(session, it.id, it.thumbTag, 480);
            } else if (isEp && it.seriesId != null && !it.seriesId.isEmpty()) {
                imgUrl = Api.imageUrl(session, it.seriesId, "", 360);
            } else {
                imgUrl = Api.imageUrl(session, it.id, it.imgTag, 360);
            }
            card.setData(cleanName(it.name), sub, imgUrl);
            letterbox(card);
            // 左下角评分：所有库统一，只要有评分就显示
            if (it.rating > 0) card.setRating("★ " + String.format("%.1f", it.rating));
            // 右上角：剧集卡显示总集数，集卡显示集号，视频卡显示观看进度
            if (isSeries) {
                if (it.childCount > 0) card.setBadge(it.childCount + L.t("集"));
            } else if (isEp && it.index > 0) {
                card.setBadge(L.t("第") + it.index + L.t("集"));
            } else if (it.progress > 0) {
                card.setProgress((int) Math.round(it.progress));
            }
            LinearLayout.LayoutParams lp = U.lpm(-2, -2, 0, 0, U.dp(this, 14), U.dp(this, 14));
            card.setLayoutParams(lp);
            card.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    if (photoWall) { openPhoto(it, itemsAll, idx); return; }
                    // 剧集永远先进展详情页（剧集本身没有文件，不能直接播）
                    if (isSeries) { openDetail(it, (Card) v); return; }
                    if (store.detailPage()) openDetail(it, (Card) v);
                    else play(it);
                }
            });
            attachCenterOnFocus(card);
            return card;
        }
    }

    /** 点剧集卡 → 进这部剧的集列表（跟 Emby 一样先看到一整个剧集，再点开选集） */
    private void openSeries(Api.Item show) {
        if (show == null) return;
        currentShow = show;
        busy = false;
        libFocusKey = show.id;
        buildLibraryPage();
        loadShowEpisodes();
    }

    /** 拉这部剧的全部集数铺成网格 */
    private void loadShowEpisodes() {
        if (session == null || currentShow == null) return;
        busy = true;
        if (gridRef != null) {
            gridRef.configure(store.heightDpFor(thumbScope()), 0);
        gridRef.setRatio(store.modeFor(thumbScope()));
            gridRef.removeAllViews();
        }
        if (statusRef != null) {
            statusRef.setVisibility(View.VISIBLE);
            statusRef.setText(L.t("正在载入视频…"));
        }
        final String showId = currentShow.id;
        final String cacheKey = "show#" + showId;
        List<Api.Item> cached = itemCache.get(cacheKey);
        if (cached != null && !cached.isEmpty()) {
            busy = false;
            fillItems(cached, cached.size());
            return;
        }
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    final List<Api.Item> eps = Api.episodes(session, showId);
                    itemCache.put(cacheKey, eps);
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            busy = false;
                            fillItems(eps, eps.size());
                        }
                    });
                } catch (Exception e) {
                    final String m = e.getMessage();
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            busy = false;
                            if (statusRef != null) statusRef.setText(L.t("载入失败：") + m);
                        }
                    });
                }
            }
        }).start();
    }

    /** 固定比例模式下：缩略图按完整画面显示，空出来的部分用黑边补 */
    private void letterbox(Card card) {
        if (card == null) return;
        card.setFit(true);
        if (card.img != null) card.img.setBackgroundColor(0xFF000000);
    }

    /** 这个条目在目录视图里算文件夹吗 */
    private boolean isPhotoType(String type) {
        return type != null && (type.equals("Photo") || type.equals("Image"));
    }

    private boolean isFolderType(String type) {
        if (type == null) return false;
        return type.equals("Folder") || type.equals("CollectionFolder") || type.equals("Series")
                || type.equals("Season") || type.equals("BoxSet") || type.equals("MusicAlbum")
                || type.equals("PhotoAlbum");
    }

    /** 预加载：当前图（高清）+ 后面 3 张 + 前 1 张（先小图后高清） */
    private void prefetchPhotoAround(java.util.List<Api.Item> list, int idx) {
        if (list == null || list.isEmpty()) return;
        int n = list.size();
        if (idx < 0) idx = 0;
        if (idx >= n) idx = n - 1;
        Api.Item cur = list.get(idx);
        Img.prefetch(Api.imageUrl(session, cur.id, cur.imgTag, 320));
        Img.prefetch(Api.imageUrl(session, cur.id, cur.imgTag, 1080));

        for (int k = 1; k <= 3; k++) {
            int j = idx + k;
            if (j >= n) break;
            Api.Item nx = list.get(j);
            Img.prefetch(Api.imageUrl(session, nx.id, nx.imgTag, 320));
        }
        int p = idx - 1;
        if (p >= 0) {
            Api.Item pv = list.get(p);
            Img.prefetch(Api.imageUrl(session, pv.id, pv.imgTag, 320));
        }
    }

    private FrameLayout photoOverlay;
    private PhotoView photoPv;
    private String photoFullUrl;         // 当前这张的原图地址（放大时才下载）
    private boolean photoLoadingFull;
    private Runnable photoFullJob;      // 原图落盘的延时任务（连切时取消上一个）
    private long photoOpenedAt;
    private android.widget.ImageView photoIv;
    private TextView photoCap;
    private TextView photoCount;
    private java.util.List<Api.Item> photoList;
    private int photoIdx;
    private float photoZoom = 1f;
    private boolean photoPan = false;
    private float photoPanX = 0f;
    private float photoPanY = 0f;
    private TextView photoHint;
    private Runnable photoHintHide;

    private void openPhoto(final Api.Item it) { openPhoto(it, null, 0); }

    /** 点图片开全屏大图：同一目录的图片可以用左右键翻看 */
    private void openPhoto(final Api.Item it, java.util.List<Api.Item> list, int idx) {
        if (photoOverlay != null) return;
        if (it == null || session == null) return;   // 会话没就绪前不碰图片接口，避免空指针
        if (list == null || list.isEmpty()) {
            java.util.List<Api.Item> one = new java.util.ArrayList<Api.Item>();
            one.add(it);
            photoList = one;
            photoIdx = 0;
        } else {
            photoList = list;
            photoIdx = idx;
        }

        FrameLayout ov = new FrameLayout(this);
        ov.setBackgroundColor(0xFF000000);
        final android.widget.ImageView iv = new android.widget.ImageView(this);
        iv.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
        iv.setVisibility(View.GONE);          // 只负责把图下下来，显示交给 PhotoView
        ov.addView(iv, new FrameLayout.LayoutParams(-1, -1));
        photoIv = iv;

        PhotoView pv = new PhotoView(this);
        pv.setBackgroundColor(0xFF000000);
        ov.addView(pv, new FrameLayout.LayoutParams(-1, -1));
        photoPv = pv;

        TextView cap = U.tv(this, cleanName(it.name), 14, 0xFFCCCCCC, false);
        FrameLayout.LayoutParams cp = new FrameLayout.LayoutParams(-2, -2);
        cp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        cp.bottomMargin = U.dp(this, 20);
        ov.addView(cap, cp);
        photoCap = cap;

        TextView cnt = U.tv(this, "", 12, 0xFF8E9C96, false);
        FrameLayout.LayoutParams cnp = new FrameLayout.LayoutParams(-2, -2);
        cnp.gravity = Gravity.TOP | Gravity.RIGHT;
        cnp.topMargin = U.dp(this, 18);
        cnp.rightMargin = U.dp(this, 22);
        ov.addView(cnt, cnp);
        photoCount = cnt;

        TextView hint = U.tv(this, "", 15, 0xFFFFFFFF, false);
        hint.setBackground(U.roundStroke(0xCC000000, U.dp(this, 18),
                U.dp(this, 1), 0x55FFFFFF));
        hint.setPadding(U.dp(this, 18), U.dp(this, 10), U.dp(this, 18), U.dp(this, 10));
        hint.setVisibility(View.GONE);
        FrameLayout.LayoutParams hp = new FrameLayout.LayoutParams(-2, -2);
        hp.gravity = Gravity.CENTER;
        ov.addView(hint, hp);
        photoHint = hint;

        ov.setFocusable(true);
        ov.setFocusableInTouchMode(true);
        ov.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { closePhoto(); }
        });
        root.addView(ov, new FrameLayout.LayoutParams(-1, -1));
        photoOverlay = ov;
        photoOpenedAt = android.os.SystemClock.uptimeMillis();
        ov.requestFocus();

        showPhotoAt(photoIdx);
    }

    /** 显示列表里第 idx 张（越大越清晰：先 320 秒出，再补 1080） */
    private void showPhotoAt(int idx) {
        if (photoList == null || photoList.isEmpty() || photoIv == null) {
            if (photoList == null || photoList.isEmpty()) {
                // 单张模式：用当前图
                return;
            }
        }
        int n = photoList.size();
        if (idx < 0) idx = n - 1;
        if (idx >= n) idx = 0;
        photoIdx = idx;
        final Api.Item it = photoList.get(idx);

        boolean portraitImg = it.height > 0 && it.width > 0 && it.height > it.width;
        photoIv.setScaleType(portraitImg
                ? android.widget.ImageView.ScaleType.FIT_CENTER
                : android.widget.ImageView.ScaleType.CENTER_CROP);
        photoIv.setImageBitmap(null);
        photoZoom = 1f;
        photoPan = false;
        photoPanX = 0f;
        photoPanY = 0f;
        if (photoPv != null) {
            photoPv.clearAll();
            photoPv.setPreview(null);
            applyPhotoFrame(portraitImg, photoShouldFill(portraitImg, it, 1f));
        }
        if (photoCap != null) photoCap.setText(cleanName(it.name));
        if (photoCount != null) photoCount.setText((idx + 1) + " / " + n);

        final String uSmall = Api.imageUrl(session, it.id, it.imgTag, 320);
        final String uPreview = Api.imageUrl(session, it.id, it.imgTag, 1080);
        final String wantId = it.id;
        photoFullUrl = Api.imageUrl(session, it.id, it.imgTag, 0);   // 原图，只在放大时才去下
        photoLoadingFull = false;
        // ① 320 缩略图刚从卡片列表显示过，基本都在内存缓存里：立刻铺一张，别黑屏干等
        Img.load(photoIv, uSmall, true, new Runnable() {
            @Override public void run() {
                if (photoOverlay == null || photoPv == null) return;
                if (photoList != null && photoIdx >= 0 && photoIdx < photoList.size()
                        && !wantId.equals(photoList.get(photoIdx).id)) return;
                android.graphics.drawable.Drawable d = photoIv.getDrawable();
                if (d instanceof android.graphics.drawable.BitmapDrawable) {
                    android.graphics.Bitmap bm = ((android.graphics.drawable.BitmapDrawable) d).getBitmap();
                    if (bm != null && photoPv.getPreview() == null) photoPv.setPreview(bm);
                }
            }
        });
        // ② 同一张原图再按 1440 / ARGB 重解一次（不再多发一次网络请求），1x 状态本身就是清晰的
        Img.loadPhoto(photoIv, uPreview, true, new Runnable() {
            @Override public void run() {
                if (photoOverlay == null || photoPv == null) return;
                if (photoList != null && photoIdx >= 0 && photoIdx < photoList.size()
                        && !wantId.equals(photoList.get(photoIdx).id)) return;
                android.graphics.drawable.Drawable d = photoIv.getDrawable();
                if (d instanceof android.graphics.drawable.BitmapDrawable) {
                    android.graphics.Bitmap bm = ((android.graphics.drawable.BitmapDrawable) d).getBitmap();
                    photoPv.setPreview(bm);
                    // 用图片真实宽高再校正一次：竖图放 9:16 竖框，横图铺满整屏
                    if (bm != null) {
                        boolean pt = bm.getHeight() > bm.getWidth();
                        applyPhotoFrame(pt, photoShouldFill(pt, it, photoZoom));
                    }
                }
            }
        });

        // 预取左右两张的 1080 图，左右切换时直接命中，不用再等下载
        if (n > 1) {
            int next = (idx + 1) % n;
            int prev = (idx - 1 + n) % n;
            Img.prefetch(Api.imageUrl(session, photoList.get(next).id, photoList.get(next).imgTag, 1080));
            Img.prefetch(Api.imageUrl(session, photoList.get(prev).id, photoList.get(prev).imgTag, 1080));
        }

        // 停在这一张上不到一秒，就把原图拉到本地：放大时 PhotoView 直接按原分辨率分块解码，
        // 不用等 16MB 慢慢下（按太快翻页就不下，省流量）
        if (photoPv != null) {
            final PhotoView host = photoPv;
            photoPv.postDelayed(new Runnable() {
                @Override public void run() {
                    if (photoPv == host) ensureFullPhoto();
                }
            }, 900);
        }
    }

    /** 确认键：放大状态下切换“可移动”模式；未放大则关闭图片 */
    private boolean isConfirmKey(int kc) {
        return kc == android.view.KeyEvent.KEYCODE_DPAD_CENTER
                || kc == android.view.KeyEvent.KEYCODE_ENTER
                || kc == android.view.KeyEvent.KEYCODE_NUMPAD_ENTER
                || kc == android.view.KeyEvent.KEYCODE_BUTTON_A;
    }

    @Override public boolean dispatchKeyEvent(android.view.KeyEvent e) {
        // 服务器面板开着时先吃掉返回键：保证方向键在面板内走、返回只关面板
        if (serverMenuOverlay != null || serverEditOverlay != null) {
            int k0 = e.getKeyCode();
            if (k0 == android.view.KeyEvent.KEYCODE_BACK
                    || k0 == android.view.KeyEvent.KEYCODE_ESCAPE) {
                if (e.getAction() == android.view.KeyEvent.ACTION_DOWN) {
                    if (serverEditOverlay != null) { hideIme(getCurrentFocus()); closeServerEdit(); }
                    else closeServerMenu();
                }
                return true;
            }
            // 兜底：焦点万一没落在面板里，抓回来，别让遥控器按了没反应
            if (e.getAction() == android.view.KeyEvent.ACTION_DOWN && serverMenuFirst != null) {
                View f = getCurrentFocus();
                View host0 = serverEditOverlay != null ? (View) serverEditOverlay : (View) serverMenuOverlay;
                if (f == null || host0 == null || !isInsideView(host0, f)) {
                    serverMenuFirst.requestFocus();
                    return true;
                }
            }
        }
        // 「跳到第N集」面板开着时，返回键只关面板，不退出详情页（up 事件也要吃掉，否则会冒到 onBackPressed）
        if (epJumpOverlay != null || epJumpAteBack) {
            int kc0 = e.getKeyCode();
            if (kc0 == android.view.KeyEvent.KEYCODE_BACK
                    || kc0 == android.view.KeyEvent.KEYCODE_ESCAPE) {
                if (e.getAction() == android.view.KeyEvent.ACTION_DOWN) {
                    epJumpAteBack = true;
                    closeEpisodeJump();
                } else if (e.getAction() == android.view.KeyEvent.ACTION_UP) {
                    epJumpAteBack = false;
                }
                return true;
            }
        }
        if (photoOverlay != null) {
            int kc = e.getKeyCode();
            boolean down = e.getAction() == android.view.KeyEvent.ACTION_DOWN;
            // 返回键负责关闭照片；确认键专管「移动模式」进/出
            if (kc == android.view.KeyEvent.KEYCODE_BACK && down) {
                closePhoto();
                return true;
            }
            if (isConfirmKey(kc)) {
                if (down) togglePhotoPan();
                return true;
            }
            if (photoPan && down) {
                // 移动模式下方向键跟手感走：按左看左边（图片向右移），按上看上面（图片向下移）
                if (kc == android.view.KeyEvent.KEYCODE_DPAD_LEFT) { panPhoto(1, 0); return true; }
                if (kc == android.view.KeyEvent.KEYCODE_DPAD_RIGHT) { panPhoto(-1, 0); return true; }
                if (kc == android.view.KeyEvent.KEYCODE_DPAD_UP) { panPhoto(0, 1); return true; }
                if (kc == android.view.KeyEvent.KEYCODE_DPAD_DOWN) { panPhoto(0, -1); return true; }
            }
            if (photoPan && !down && (kc == android.view.KeyEvent.KEYCODE_DPAD_LEFT
                    || kc == android.view.KeyEvent.KEYCODE_DPAD_RIGHT
                    || kc == android.view.KeyEvent.KEYCODE_DPAD_UP
                    || kc == android.view.KeyEvent.KEYCODE_DPAD_DOWN)) {
                return true;
            }
        }
        return super.dispatchKeyEvent(e);
    }

    /** 进入/退出“可移动”模式；退出时保留已经挪好的位置 */
    private void togglePhotoPan() {
        photoPan = !photoPan;
        showPhotoHint(photoPan ? L.t("可移动") : L.t("可缩放"));
    }

    private void showPhotoHint(String text) {
        if (photoHint == null || photoOverlay == null) return;
        photoHint.setText(text);
        photoHint.setVisibility(View.VISIBLE);
        photoHint.bringToFront();
        if (photoHintHide != null) photoOverlay.removeCallbacks(photoHintHide);
        photoHintHide = new Runnable() {
            @Override public void run() {
                if (photoHint != null) photoHint.setVisibility(View.GONE);
            }
        };
        photoOverlay.postDelayed(photoHintHide, 1000);
    }

    /** 移动模式下平移图片，边界由 PhotoView 内部夹取 */
    private void panPhoto(int dx, int dy) {
        if (photoPv == null) return;
        float step = U.dp(this, 60);
        photoPv.panBy(dx * step, dy * step);
    }

    /** 左右键翻图 */
    @Override public boolean onKeyDown(int keyCode, android.view.KeyEvent event) {
        if (photoOverlay != null) {
            if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_LEFT) {
                stepPhoto(-1);
                return true;
            }
            if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_RIGHT) {
                stepPhoto(1);
                return true;
            }
            if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_UP) {
                applyPhotoZoom(0.25f);
                return true;
            }
            if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_DOWN) {
                applyPhotoZoom(-0.25f);
                return true;
            }
        }
        return super.onKeyDown(keyCode, event);
    }

    /** 上下键缩放：1.0x ~ 12x，每次按倍数递增步长（缩放时保留已挪好的位置） */
    private void applyPhotoZoom(float delta) {
        if (photoPv == null) return;
        // 倍数越高步长越大，免得从 8x 到 24x 要按十几次
        float step;
        if (photoZoom < 2f) step = 0.25f;
        else if (photoZoom < 4f) step = 0.5f;
        else if (photoZoom < 8f) step = 1f;
        else if (photoZoom < 16f) step = 2f;
        else step = 4f;
        photoZoom = Math.max(1f, Math.min(24f, photoZoom + (delta > 0 ? step : -step)));
        photoPv.setZoom(photoZoom);
        photoZoom = photoPv.zoom();
        // 放大到超过整屏宽度时放开竖框；缩回去再收成 9:16
        Api.Item cur = (photoList != null && photoIdx >= 0 && photoIdx < photoList.size())
                ? photoList.get(photoIdx) : null;
        boolean pt = cur != null && cur.height > 0 && cur.width > 0 && cur.height > cur.width;
        applyPhotoFrame(pt, photoShouldFill(pt, cur, photoZoom));
        if (photoZoom > 1.05f && !photoPv.hasSource()) ensureFullPhoto();
        if (photoCount != null) {
            int n = photoList == null ? 1 : photoList.size();
            photoCount.setText((photoIdx + 1) + " / " + n
                    + (photoZoom > 1.001f ? "  ·  " + String.format("%.2f", photoZoom) + "x" : ""));
        }
    }

    private void stepPhoto(int dir) {
        if (photoList == null || photoList.size() < 2) return;
        showPhotoAt(photoIdx + dir);
    }

    /** 放大时才下载原图（单张可达 36MB），落盘后交给 PhotoView 做区域解码 */
    private void ensureFullPhoto() {
        if (photoLoadingFull || photoFullUrl == null || photoPv == null) return;
        photoLoadingFull = true;
        final String u = photoFullUrl;
        final String wantId = (photoList != null && photoIdx >= 0 && photoIdx < photoList.size())
                ? photoList.get(photoIdx).id : null;
        Img.ensureFile(u, true, new Img.FileCallback() {
            @Override public void onFile(java.io.File f) {
                photoLoadingFull = false;
                if (photoOverlay == null || photoPv == null || f == null) return;
                if (wantId != null && photoList != null && photoIdx >= 0
                        && photoIdx < photoList.size() && !wantId.equals(photoList.get(photoIdx).id)) return;
                photoPv.setSource(f.getAbsolutePath());
                if (photoZoom > 1.05f) photoPv.setZoom(photoZoom);
            }
        });
    }

    /** 照片一律按原比例完整显示：黑边是照片本身的宽高比造成的，横竖都不裁切、不铺满 */
    private void applyPhotoFrame(boolean portrait, boolean fillScreen) {
        if (photoPv == null) return;
        photoPv.setCover(false);
        photoPv.setFrameWidth(0);
    }

    /** 放大到什么倍数时该放开竖框（图宽刚好达到屏幕宽） */
    private boolean photoShouldFill(boolean portrait, Api.Item it, float zoomNow) {
        if (!portrait) return true;
        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        if (it == null || it.width <= 0 || it.height <= 0) return zoomNow >= 2.7f;
        float need = (dm.widthPixels * (float) it.height) / (it.width * (float) dm.heightPixels);
        return zoomNow >= need;
    }

    private void closePhoto() {
        if (photoOverlay == null) return;
        // 刚打开就被同一次按键的第二次点击命中时忽略，避免闪一下又关掉
        if (android.os.SystemClock.uptimeMillis() - photoOpenedAt < 420) return;

        // 记住当前正在看的那张图，退出后焦点回到它所在的卡片
        String lastId = null;
        if (photoList != null && photoIdx >= 0 && photoIdx < photoList.size()) {
            lastId = photoList.get(photoIdx).id;
        }

        root.removeView(photoOverlay);
        photoOverlay = null;
        if (photoPv != null) {
            photoPv.release();
            photoPv = null;
        }
        photoIv = null;
        photoCap = null;
        photoCount = null;
        photoHint = null;
        photoPan = false;
        photoPanX = 0f;
        photoPanY = 0f;
        photoList = null;

        if (gridRef == null) return;
        if (lastId != null) {
            for (int i = 0; i < gridRef.getChildCount(); i++) {
                View c = gridRef.getChildAt(i);
                if (c instanceof Card && lastId.equals(((Card) c).key)) {
                    c.requestFocus();
                    focusCardIntoView(c);
                    return;
                }
            }
        }
        if (gridRef.getChildCount() > 0) gridRef.getChildAt(0).requestFocus();
    }

    /** 让重新获得焦点的卡片滚到可见区域中间附近 */
    private void focusCardIntoView(View c) {
        try {
            if (gridScroll == null) return;
            int top = c.getTop();
            int h = c.getHeight();
            int viewH = gridScroll.getHeight();
            int target = top - (viewH - h) / 2;
            if (target < 0) target = 0;
            scrollGlide(gridScroll, target);
        } catch (Throwable ignored) { }
    }

    /** 页面切换入场过渡（设置页可选）：0 跟手无动画 / 1 淡入 / 2 缩放淡入 / 3 上滑淡入 / 4 左右推移 */
    private void animatePageIn(View v) {
        if (v == null) return;
        int st = U.PAGE_ANIM;
        if (st <= 0) return;
        v.setAlpha(0f);
        if (st == 2) { v.setScaleX(0.93f); v.setScaleY(0.93f); }
        if (st == 3) v.setTranslationY(U.dp(this, 56));
        if (st == 4) v.setTranslationX(U.dp(this, 72));
        v.animate().alpha(1f).scaleX(1f).scaleY(1f)
                .translationX(0f).translationY(0f)
                .setDuration(st == 2 ? 300 : 240)
                .setInterpolator(new android.view.animation.DecelerateInterpolator())
                .start();
    }

    /** 上下翻页滚动：普通 ScrollView 也走 OverScroller + 逐帧推进（与官方列表同一条曲线） */
    private android.widget.OverScroller glideScroller;
    /** 惰性创建：Activity 构造阶段还没 attach，直接 new OverScroller(this) 会 getResources() 空指针 */
    private android.widget.OverScroller gs() {
        if (glideScroller == null) glideScroller = new android.widget.OverScroller(this);
        return glideScroller;
    }
    private ScrollView glideOn;
    private void scrollGlide(ScrollView sc, int target) {
        try {
            if (sc instanceof SmoothScrollView) { ((SmoothScrollView) sc).glideTo(target); return; }
            int from = sc.getScrollY();
            if (from == target) return;
            gs().forceFinished(true);
            glideOn = sc;
            int dist = Math.abs(target - from);
            int dur = dist > U.dp(this, 900) ? 430 : 260;
            gs().startScroll(0, from, 0, target - from, dur);
            glideStep();
        } catch (Throwable ignored) { }
    }

    /** 每帧推进一次；View 已脱离窗口就停，避免残留回调一直跑 */
    private void glideStep() {
        final ScrollView sc = glideOn;
        if (sc == null || sc.getWindowToken() == null) { glideOn = null; return; }
        if (!gs().computeScrollOffset()) { glideOn = null; return; }
        int y = gs().getCurrY();
        sc.scrollTo(0, y < 0 ? 0 : y);
        sc.postOnAnimation(new Runnable() {
            @Override public void run() { glideStep(); }
        });
    }

    /** 上下翻动时把焦点卡所在的行滚到视口正中间（首页 / 媒体库 / 搜索结果共用） */
    /**
     * 上下翻动时只滚「必需的距离」：焦点这一行被上下边缘裁到，就滚到刚好整行露出；
     * 已经完整可见就完全不动（避免每翻一格都滚到屏幕正中，观感又慢又晃）。
     */
    private void centerCardInScroller(View c) {
        try {
            View p = c;
            ScrollView sc = null;
            while (true) {
                android.view.ViewParent vp = p.getParent();
                if (vp == null || !(vp instanceof View)) return;
                p = (View) vp;
                if (p instanceof ScrollView) { sc = (ScrollView) p; break; }
            }
            if (sc == null || sc.getChildCount() == 0) return;
            int contentH = sc.getChildAt(0).getHeight();
            if (contentH <= 0) return;
            int[] ca = new int[2];
            int[] sa = new int[2];
            c.getLocationOnScreen(ca);
            sc.getLocationOnScreen(sa);
            int top = ca[1] - sa[1] + sc.getScrollY();
            int vh = sc.getHeight(), ch = c.getHeight();
            int pad = U.dp(this, 10);
            int cur = sc.getScrollY();
            int target = cur;
            if (ch + pad * 2 >= vh) {
                target = top + ch / 2 - vh / 2;          // 整行比视口还高，只能居中
            } else if (top - pad < cur) {
                target = top - pad;                       // 上方被裁
            } else if (top + ch + pad > cur + vh) {
                target = top + ch + pad - vh;             // 下方被裁
            }
            int max = Math.max(0, contentH - vh);
            if (target < 0) target = 0;
            if (target > max) target = max;
            if (target != cur) scrollGlide(sc, target);
        } catch (Throwable ignored) { }
    }

    /** 卡片焦点变化：拿到焦点就把这一行居中，遥控器上下翻时行始终停在屏幕中间 */
    private void attachCenterOnFocus(final View card) {
        card.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override public void onFocusChange(final View v, boolean has) {
                if (!has) return;
                // 照官方 Emby 的做法：焦点一进到最后十来张，就提前把下一段要回来
                // （官方是 loadMoreItemsIfNeeded(index)，index >= itemsLoaded-15 就 RetrieveNext）
                final int idx = (gridRef == null) ? -1 : gridRef.indexOfChild(v);
                if (idx >= 0 && itemsAll != null) {
                    if (itemsShown < itemsAll.size()) {
                        if (idx >= itemsShown - 15) addNextPage();
                    } else {
                        prefetchMoreIfRandom();
                    }
                }
                // 延后一拍：让焦点缩放动画先起步，再开始滚动，避免两段动画互相抢帧
                v.postDelayed(new Runnable() {
                    @Override public void run() {
                        if (v.isFocused()) centerCardInScroller(v);
                    }
                }, 24);
            }
        });
    }

    private String cleanName(String n) {
        if (n == null) return "";
        int dot = n.lastIndexOf('.');
        if (dot > 0 && n.length() - dot <= 5) n = n.substring(0, dot);
        // 去掉文件名开头的顿号、点号、空格等杂物
        int i = 0;
        while (i < n.length() && ("、，。·.,:;-_ 	".indexOf(n.charAt(i)) >= 0)) i++;
        return i == 0 ? n : n.substring(i);
    }

    private boolean isPlayable(Api.Item it) {
        String t = it.type == null ? "" : it.type;
        return t.equals("Movie") || t.equals("Episode") || t.equals("Video") || t.equals("MusicVideo");
    }

    private void play(Api.Item it) { play(it, false); }

    /** fromStart=true 表示「从头播放」，否则按进度续播 */
    private void play(Api.Item it, boolean fromStart) {
        recordRecent(it);
        // 详情页打开着的时候，本地点亮「已播放」勾 / 这一集的 √，不用等服务器
        markDetailPlaying(it);
        // 播过一次就把这条标记为已观看：详情页的对勾变绿、媒体库/最近观看的状态也跟着变
        final String markId = it.id;
        new Thread(new Runnable() {
            @Override public void run() {
                try { Api.markPlayed(session, markId, true); } catch (Throwable ignored) { }
            }
        }).start();
        boolean trans = store.forceTranscode();
        String url = trans
                ? Api.transcodeUrl(session, it.id, store.maxHeightPx(), store.maxKbps())
                : Api.streamUrl(session, it.id);
        // STRM 直链：条目本身带 http 地址就直接播它，不再由服务器转发
        if (!trans && store.strm() && it.path != null
                && (it.path.startsWith("http://") || it.path.startsWith("https://"))) {
            url = it.path;
        }
        if (store.playerMode() == 1 || store.engine() == 3) {
            if (searchOverlay != null) {
                resumeSearchOnReturn = true;
                getSharedPreferences("lmby", 0).edit()
                        .putString("sq", lastSearchQuery == null ? "" : lastSearchQuery).apply();
            }
            launchExternal(url, it.name);
            return;
        }
        if (searchOverlay != null) {
            resumeSearchOnReturn = true;
            getSharedPreferences("lmby", 0).edit()
                    .putString("sq", lastSearchQuery == null ? "" : lastSearchQuery).apply();
        }
        // 强制 libVLC → PlayerActivity；自动 / 强制 ExoPlayer → SysPlayerActivity（Exo 内核）
        Class<?> target = (store.engine() == 2) ? PlayerActivity.class : SysPlayerActivity.class;
        android.util.Log.e("LMBY", "play engine=" + store.engine() + " -> " + target.getSimpleName());
        Intent i = new Intent(this, target);
        i.putExtra("url", url);
        i.putExtra("title", cleanName(it.name));
        i.putExtra("sub", it.series != null && !it.series.isEmpty() ? it.series : "");
        i.putExtra("itemId", it.id);
        // 播的是某一集时把剧 id 带进去：播放器里按上键就能弹这部剧的选集条
        i.putExtra("seriesId", it.seriesId == null ? "" : it.seriesId);
        i.putExtra("server", session.server);
        i.putExtra("token", session.token);
        i.putExtra("userId", session.userId);
        i.putExtra("deviceId", session.deviceId);
        i.putExtra("aspect", it.width > 0 && it.height > 0 ? it.aspectString() : "16:9");
        i.putExtra("method", trans ? "转码" : "直接播放");
        // 续播位置：看过一部分就跳到上次的进度，全新则从 0 开始
        long startMs = 0;
        if (!fromStart) {
            if (it.resumeMs > 3000) {
                startMs = it.resumeMs;            // 服务器记录的位置
            } else if (it.progress > 1 && it.progress < 99 && it.duration > 0) {
                startMs = (long) (it.duration * (it.progress / 100.0));
            }
            // 最近观看里的条目可能没带进度（本机历史存的还是开播前的旧值）：播放前现查一次服务器
            if (startMs <= 3000 && session != null && it.id != null && !it.id.isEmpty()) {
                final java.util.concurrent.CountDownLatch latch =
                        new java.util.concurrent.CountDownLatch(1);
                final long[] box = {0L};
                final String iid = it.id;
                final Api.Session ss = session;
                new Thread(new Runnable() {
                    @Override public void run() {
                        try { box[0] = Api.positionMs(ss, iid); } catch (Throwable ignored) { }
                        latch.countDown();
                    }
                }).start();
                try { latch.await(1200, java.util.concurrent.TimeUnit.MILLISECONDS); }
                catch (Throwable ignored) { }
                if (box[0] > 3000) { startMs = box[0]; it.resumeMs = box[0]; }
            }
        }
        android.util.Log.e("LMBY", "play startMs=" + startMs + " item=" + it.id
                + " resumeMs=" + it.resumeMs + " progress=" + it.progress + " dur=" + it.duration);
        i.putExtra("startMs", startMs);
        // 片长：老 TS 片源 libVLC 拿不到时长，播放器里预置一份，进度条右端和快进边界才正确
        if (it.duration > 0) i.putExtra("durMs", it.duration);
        // 播放列表上下文（当前列表里的所有可播放条目 + 当前下标），给播放模式用
        StringBuilder plist = new StringBuilder();
        int pidx = 0, seen = 0;
        boolean found = false;
        List<Api.Item> ctx = itemsAll;
        if (ctx != null) {
            for (int k = 0; k < ctx.size(); k++) {
                Api.Item x = ctx.get(k);
                if (x == null || !isPlayable(x)) continue;
                if (seen > 0) plist.append("\n");
                plist.append(x.id);
                if (!found && x.id != null && x.id.equals(it.id)) { pidx = seen; found = true; }
                seen++;
            }
        }
        if (seen > 0) {
            i.putExtra("plist", plist.toString());
            i.putExtra("pidx", pidx);
        }
        backFromPlayer = true;   // 退出播放器回来要立刻刷新续播/进度角标
        startActivity(i);
    }

    void launchExternal(String url, String title) {
        // 首次用会弹一次「选择第三方播放器」，选中后固定；设置页可清除后重选
        U.launchThird(this, url, title, store);
    }

    // ================= 详情页（电影选集式：播放 / 收藏 / 删除 / 文件信息） =================

    /** 剧集续播按钮：点了直接播「看到一半的那一集」并回到上次进度 */
    private void showDetailResume(final Api.Item ep) {
        if (detailBtnRow == null || ep == null || detailOverlay == null) return;
        String label = L.t("续播") + " S" + ep.seasonNo + "E" + ep.index + "  " + fmtClock(ep.resumeMs);
        if (detailResumeBtn == null) {
            detailResumeBtn = detPlayBtn(detailBtnRow, label, 200, false);
            detailResumeBtn.setTag("resume");
        } else {
            detailResumeBtn.setText(label);
        }
        detailResumeBtn.setVisibility(View.VISIBLE);
        detailResumeBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { play(ep, false); }
        });
    }

    /** 从剧集列表里挑「最后看的那一集」：官方 NextUp 优先，本机记录兜底，再退回服务器 UserData */
    private Api.Item findResumeEpisode(java.util.List<Api.Item> eps) {
        if (eps == null) return null;
        // 1) 官方 NextUp：Emby 自己算出来的「接着看这一集」（剧集列表接口不给 LastPlayedDate，只有它准）
        String nuId = nextUpEpId == null ? "" : nextUpEpId;
        if (!nuId.isEmpty()) {
            for (int i = 0; i < eps.size(); i++) {
                Api.Item e = eps.get(i);
                if (e.id == null || !e.id.equals(nuId)) continue;
                long rp = e.resumeMs > 3000 ? e.resumeMs
                        : (e.duration > 3000 && e.progress > 1 && e.progress < 99
                        ? (long) (e.duration * (e.progress / 100.0)) : 0);
                e.resumeMs = rp;   // 从头播的那集 rp=0，续播按钮按 00:00 显示，提示仍挂在它身上
                return e;
            }
        }
        // 2) 本机记录：这部剧最后退出播放器时播的是哪一集
        String lastId = (detailItem == null || detailItem.id == null) ? "" : store.lastEp(detailItem.id);
        if (!lastId.isEmpty()) {
            for (int i = 0; i < eps.size(); i++) {
                Api.Item e = eps.get(i);
                if (e.id == null || !e.id.equals(lastId)) continue;
                long rp = e.resumeMs > 3000 ? e.resumeMs
                        : (e.duration > 3000 && e.progress > 1 && e.progress < 99
                        ? (long) (e.duration * (e.progress / 100.0)) : 0);
                if (rp > 3000 && (e.duration <= 0 || rp < e.duration - 10000)) {
                    e.resumeMs = rp;
                    return e;
                }
                break;   // 本机记录的那集已经看完了（位置归零），交给服务器兜底
            }
        }
        // 2) 兜底：按服务器进度挑（列表没有 LastPlayedDate，就取第一集还有剩余的）
        Api.Item best = null;
        for (int i = 0; i < eps.size(); i++) {
            Api.Item e = eps.get(i);
            long rp = e.resumeMs > 3000 ? e.resumeMs
                    : (e.duration > 3000 && e.progress > 1 && e.progress < 99
                    ? (long) (e.duration * (e.progress / 100.0)) : 0);
            if (!(rp > 3000 && (e.duration <= 0 || rp < e.duration - 10000))) continue;
            e.resumeMs = rp;
            if (best == null) { best = e; continue; }
            String a = best.lastPlayed == null ? "" : best.lastPlayed;
            String b = e.lastPlayed == null ? "" : e.lastPlayed;
            if (b.compareTo(a) > 0) best = e;
        }
        return best;
    }

    /** 详情页选集行：滚动到某一集并居中，保证一进来就能看到它 */
    private void scrollEpRowTo(final View card) {
        final android.widget.HorizontalScrollView hs = detailEpScroll;
        if (hs == null || card == null) return;
        hs.post(new Runnable() {
            @Override public void run() {
                int center = card.getLeft() - (hs.getWidth() - card.getWidth()) / 2;
                if (center < 0) center = 0;
                hs.scrollTo(center, 0);
            }
        });
    }

    /** 退出播放器回来：按最新进度把电影的续播按钮同步出来（有进度就显示/更新，没有就藏） */
    private void syncMovieResume(final Api.Item it, final long pos) {
        if (detailBtnRow == null || it == null) return;
        if (pos <= 0) {
            if (detailResumeBtn != null) detailResumeBtn.setVisibility(View.GONE);
            return;
        }
        String label = L.t("续播") + " " + fmtClock(pos);
        if (detailResumeBtn == null || detailResumeBtn.getParent() != detailBtnRow) {
            detailResumeBtn = detPlayBtn(detailBtnRow, label, 150, false);
            detailResumeBtn.setTag("resume");
        } else {
            detailResumeBtn.setText(label);
        }
        detailResumeBtn.setVisibility(View.VISIBLE);
        detailResumeBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { play(it, false); }
        });
    }

    /** 详情页下方按钮：primary=白底主按钮（播放），次要=半透明玻璃按钮（续播） */
    private TextView detPlayBtn(LinearLayout row, String text, int wDp, final boolean primary) {
        int fg = U.LIGHT_BG ? 0xFF1C1C1E : 0xFFFFFFFF;
        final TextView b = U.tv(this, text, 17, fg, true);
        // 按钮文字必须跟按钮底色配对（主按钮白底→深字），不受「文字颜色」手选影响，否则会白底白字
        b.setTextColor(fg);
        b.setGravity(Gravity.CENTER);
        b.setFocusable(true);
        b.setFocusableInTouchMode(true);
        b.setSingleLine(true);
        b.setPadding(U.dp(this, 10), 0, U.dp(this, 10), 0);
        b.setBackground(normalBg(primary));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(U.dp(this, wDp), U.dp(this, 44));
        lp.rightMargin = U.dp(this, 10);
        row.addView(b, lp);
        b.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override public void onFocusChange(View v, boolean has) {
                b.setBackground(has ? focusBg(primary) : normalBg(primary));
                b.animate().scaleX(has ? 1.05f : 1f).scaleY(has ? 1.05f : 1f)
                        .setDuration(140).start();
            }
        });
        return b;
    }

    private android.graphics.drawable.Drawable normalBg(boolean primary) {
        if (U.DG) {   // 黑绿：播放/续播都做透明玻璃，播放稍亮一点点，靠焦点框的绿区分
            return primary
                    ? U.roundStroke(0x4DFFFFFF, U.dp(this, U.RADIUS), U.dp(this, 1), 0x66FFFFFF)
                    : U.roundStroke(0x26FFFFFF, U.dp(this, U.RADIUS), U.dp(this, 1), 0x40FFFFFF);
        }
        // 播放与续播同一套：半透明白底 + 细白边，压在画面上更干净
        return U.roundStroke(0x26FFFFFF, U.dp(this, U.RADIUS), U.dp(this, 1), 0x4DFFFFFF);
    }

    private android.graphics.drawable.Drawable focusBg(boolean primary) {
        if (U.DG) {   // 黑绿：焦点框统一走主题绿，不做白框
            return primary
                    ? U.roundStroke(0x66FFFFFF, U.dp(this, U.RADIUS), U.dp(this, 2.5f), U.RING)
                    : U.roundStroke(0x40FFFFFF, U.dp(this, U.RADIUS), U.dp(this, 2.5f), U.RING);
        }
        return U.roundStroke(0x40FFFFFF, U.dp(this, U.RADIUS), U.dp(this, 2.5f), 0xFFFFFFFF);
    }

    /** 毫秒 → 00:00 / 0:00:00 */
    private String fmtClock(long ms) {
        if (ms <= 0) return "";
        long s = ms / 1000, h = s / 3600, m = (s % 3600) / 60, ss = s % 60;
        return h > 0 ? String.format("%d:%02d:%02d", h, m, ss) : String.format("%02d:%02d", m, ss);
    }

    private String fmtSize(long bytes) {
        if (bytes <= 0) return "";
        double gb = bytes / 1073741824.0;
        if (gb >= 1) return String.format("%.2f GB", gb);
        return String.format("%.0f MB", bytes / 1048576.0);
    }

    private String fmtBitrate(int bps) {
        if (bps <= 0) return "";
        double mb = bps / 1000000.0;
        if (mb >= 1) return String.format("%.1f Mbps", mb);
        return (bps / 1000) + " Kbps";
    }

    private TextView badgeText(String t) {
        TextView b = U.tv(this, t, 12, 0xFFE8E8E8, false);
        b.setPadding(U.dp(this, 7), U.dp(this, 2), U.dp(this, 7), U.dp(this, 2));
        b.setBackground(U.round(0xFF393939, U.dp(this, 4)));
        return b;
    }

    private void fillMetaRow(LinearLayout row, Api.Item it) {
        row.removeAllViews();
        StringBuilder head = new StringBuilder();
        if (it.year > 0) head.append(it.year).append("    ");
        if (it.duration > 0) head.append(Math.max(1, it.duration / 60000)).append("m");
        row.addView(U.tv(this, head.toString(), 15, 0xFFEDEDED, false), U.lp(-2, -2));
        String res = it.resLabel();
        if (!res.isEmpty()) {
            TextView b = badgeText(res);
            LinearLayout.LayoutParams bl = U.lp(-2, -2);
            bl.leftMargin = U.dp(this, 10);
            row.addView(b, bl);
        }
        String ach = it.audioChip();
        if (!ach.isEmpty()) {
            TextView b = badgeText(ach);
            LinearLayout.LayoutParams bl = U.lp(-2, -2);
            bl.leftMargin = U.dp(this, 8);
            row.addView(b, bl);
        }
    }

    /** 原生 Emby 详情页的动作方块：方块图标 + 下方两行小字 */
    private DetailTile addTile(LinearLayout row, String glyph, String caption, boolean green) {
        LinearLayout box = U.col(this);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        DetailTile t = new DetailTile(this, glyph);
        t.setGreen(green);
        box.addView(t, new LinearLayout.LayoutParams(U.dp(this, 74), U.dp(this, 58)));
        TextView cap = U.tv(this, caption, 12, 0xFFD0D0D0, false);
        cap.setGravity(Gravity.CENTER_HORIZONTAL);
        cap.setMaxLines(2);
        LinearLayout.LayoutParams cl = U.lp(-1, -2);
        cl.topMargin = U.dp(this, 5);
        box.addView(cap, cl);
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(U.dp(this, 84), -2);
        bl.leftMargin = U.dp(this, 6);
        row.addView(box, bl);
        return t;
    }

    void openDetail(final Api.Item it, Card card) {
        android.util.Log.e("LMBYTEST", "openDetail enter: " + it.type + " / " + it.name
                + " overlay=" + (detailOverlay != null));
        if (detailOverlay != null) return;
        detailItem = it;
        detailCard = card;
        nextUpEpId = "";   // 换剧了：先清掉上一部剧的 NextUp 记录
        // 每次开新详情页都重置续播按钮的引用，避免指到上一个页面的 View
        detailBtnRow = null;
        detailResumeBtn = null;
        // 剧集本身没有文件：只隐藏没有意义的部分（版本、删除、码率参数、媒体信息），页面还是这一套
        final boolean isSeries = "Series".equals(it.type);

        final FrameLayout overlay = new FrameLayout(this);
        overlay.setBackgroundColor(U.EMBY ? 0xFFFFFFFF : 0xFF000000);
        overlay.setFocusable(true);
        final FrameLayout ov = overlay;

        final ImageView bg = new ImageView(this);
        bg.setScaleType(ImageView.ScaleType.CENTER_CROP);
        loadDetailBackdrop(bg, it);
        if (U.EMBY) bg.setAlpha(0.45f);
        overlay.addView(bg, new FrameLayout.LayoutParams(-1, -1));

        // 左侧压暗的横向渐变，文字才压得住底图
        View scrim = new View(this);
        android.graphics.drawable.GradientDrawable gd = new android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT,
                U.EMBY ? new int[]{0xE6FFFFFF, 0xD9FFFFFF, 0xCCFFFFFF}
                        : new int[]{0xF2000000, 0xC4000000, 0x50000000});
        scrim.setBackground(gd);
        overlay.addView(scrim, new FrameLayout.LayoutParams(-1, -1));

        final android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setFillViewport(true);
        final LinearLayout col = U.col(this);
        col.setPadding(U.dp(this, 52), U.dp(this, 64), U.dp(this, 52), U.dp(this, 44));
        scroll.addView(col, new FrameLayout.LayoutParams(-1, -2));
        overlay.addView(scroll, new FrameLayout.LayoutParams(-1, -1));

        final LinearLayout page1 = U.col(this);
        col.addView(page1, U.lp(-1, -2));
        detailPage1 = page1;
        detailScroll = scroll;

        final TextView name = U.tv(this, cleanName(it.name), 38, U.TXT, true);
        name.setMaxLines(2);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        name.setLineSpacing(U.dp(this, 2), 1f);
        page1.addView(name, U.lp(-1, -2));

        // 第二行：评分 · 上映日期 · 共几季/多少集 · 类型（和柴柴/官方 Emby 详情页一致）
        final TextView metaLine = U.tv(this, detailSubLine(it, -1), 14, U.DIM, false);
        LinearLayout.LayoutParams mll = U.lp(-1, -2);
        mll.topMargin = U.dp(this, 6);
        page1.addView(metaLine, mll);

        // 圆形图标按钮行（不包含收藏）
        LinearLayout icons = U.row(this);
        icons.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams il = U.lp(-2, -2);
        il.topMargin = U.dp(this, 18);
        page1.addView(icons, il);

        final LinearLayout infoBlock = U.col(this);

        // 第一个圆形按钮：跳到第 N 集（剧集才有，电影隐藏；原来是「选择版本」）
        IconBtn jumpBtn = detIcon(icons, IconBtn.SEARCH);
        jumpBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showEpisodeJump(); }
        });
        if (!isSeries) {
            jumpBtn.setVisibility(View.GONE);
            jumpBtn.setFocusable(false);
        }

        IconBtn restartBtn = detIcon(icons, IconBtn.RESTART);
        restartBtn.setVisibility(View.GONE);
        restartBtn.setFocusable(false);

        // 已观看按钮：对勾，状态以服务器的 UserData.Played 为准（播放过变绿）
        final IconBtn watchBtn = detIcon(icons, IconBtn.CHECK);
        detailWatchBtn = watchBtn;
        final boolean[] watched = new boolean[]{it.played};
        watchBtn.setActive(watched[0]);
        watchBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                final boolean want = !watched[0];
                new Thread(new Runnable() {
                    @Override public void run() {
                        final boolean ok = Api.markPlayed(session, it.id, want);
                        runOnUiThread(new Runnable() {
                            @Override public void run() {
                                if (!ok) {
                                    android.widget.Toast.makeText(MainActivity.this,
                                            L.t("操作失败，检查账号权限"),
                                            android.widget.Toast.LENGTH_SHORT).show();
                                    return;
                                }
                                watched[0] = want;
                                watchBtn.setActive(want);
                                it.progress = want ? 100 : 0;
                                android.widget.Toast.makeText(MainActivity.this,
                                        want ? L.t("已标记为已观看") : L.t("已取消观看标记"),
                                        android.widget.Toast.LENGTH_SHORT).show();
                            }
                        });
                    }
                }).start();
            }
        });

        // 收藏：同一行胶囊图标按钮，选中时实心
        final IconBtn favBtn = detIcon(icons, IconBtn.HEART);
        favBtn.setFilled(it.favorite);
        favBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                final boolean want = !it.favorite;
                new Thread(new Runnable() {
                    @Override public void run() {
                        final boolean ok = Api.favorite(session, it.id, want);
                        runOnUiThread(new Runnable() {
                            @Override public void run() {
                                if (!ok) {
                                    android.widget.Toast.makeText(MainActivity.this,
                                            L.t("操作失败，检查账号权限"),
                                            android.widget.Toast.LENGTH_SHORT).show();
                                    return;
                                }
                                it.favorite = want;
                                favBtn.setFilled(want);
                                favBtn.setActive(want);
                                android.widget.Toast.makeText(MainActivity.this,
                                        want ? L.t("已加入收藏") : L.t("已取消收藏"),
                                        android.widget.Toast.LENGTH_SHORT).show();
                                homeDirty = true;
                                refreshHomeFavorites();
                            }
                        });
                    }
                }).start();
            }
        });

        // 删除：排在收藏后面，点一下直接删
        IconBtn delBtn = detIcon(icons, IconBtn.TRASH);
        delBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { confirmDelete(it); }
        });

        // 播放 / 续播：两个小按钮并排。续播位置以服务器记录的进度为准
        long resumePos = it.resumeMs > 3000 ? it.resumeMs
                : (it.duration > 0 && it.progress > 1 && it.progress < 99
                ? (long) (it.duration * (it.progress / 100.0)) : 0);
        LinearLayout btnRow = U.row(this);
        btnRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams brl = U.lp(-2, -2);
        brl.topMargin = U.dp(this, 24);
        page1.addView(btnRow, brl);

        final TextView pill = detPlayBtn(btnRow, L.t("播放"), 108, true);
        pill.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                // 剧集没有文件，播放等于从这部剧接着看
                if (isSeries) playSeriesEpisode(it);
                else play(it, true);
            }
        });
        detailPill = pill;
        // 详情页默认焦点直接落在「播放」上，避免焦点跑到图标行或信息区
        pill.postDelayed(new Runnable() {
            @Override public void run() {
                if (pill.isShown() && !pill.hasFocus()) pill.requestFocus();
            }
        }, 260);

        if (!isSeries && resumePos > 0) {
            TextView rb = detPlayBtn(btnRow, L.t("续播") + " " + fmtClock(resumePos), 150, false);
            rb.setTag("resume");
            rb.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { play(it, false); }
            });
            detailResumeBtn = rb;
        }
        detailBtnRow = btnRow;

        // 小胶囊信息
        LinearLayout chipsRow = U.row(this);
        chipsRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams cl = U.lp(-2, -2);
        cl.topMargin = U.dp(this, 16);
        page1.addView(chipsRow, cl);
        fillChips(chipsRow, it);

        // 简介（刮削的介绍）：完整显示，不截断（详情页本身可以往下滚）
        final TextView ovTv = U.tv(this, "", 13, 0xFFC3CAD2, false);
        ovTv.setLineSpacing(U.dp(this, 4), 1.15f);
        if (it.overview != null && !it.overview.isEmpty()) ovTv.setText(it.overview);
        else ovTv.setVisibility(View.GONE);

        // 简介紧跟在标签下面（放在「选集」之前）：
        // 剧集页选集行很高，简介放在它后面会被顶到屏幕外，用户就以为「简介没出来」
        LinearLayout.LayoutParams ovl = U.lp(-1, -2);
        ovl.topMargin = U.dp(this, 16);
        page1.addView(ovTv, ovl);

        // 剧集：选集行（16:9 视频帧缩略图，确认就播这一集；电影没有这一行）
        final LinearLayout epBlock = U.col(this);
        final LinearLayout epRow;
        if (isSeries) {
            LinearLayout headRow = U.row(this);
            headRow.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams ehl = U.lp(-1, -2);
            ehl.topMargin = U.dp(this, 22);
            epBlock.addView(headRow, ehl);
            TextView eph = U.tv(this, L.t("选集"), 18, U.TXT, true);
            headRow.addView(eph, U.lp(-2, -2));

            // 分季标签（多季才出现）：几百集先按季缩范围
            LinearLayout seasonRow = U.row(this);
            seasonRow.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams srl = U.lpm(-2, -2, U.dp(this, 18), 0, 0, 0);
            headRow.addView(seasonRow, srl);
            detailSeasonRow = seasonRow;

            android.widget.HorizontalScrollView hsv = new android.widget.HorizontalScrollView(this);
            hsv.setHorizontalScrollBarEnabled(false);
            hsv.setClipChildren(false);
            hsv.setClipToPadding(false);
            hsv.setFocusable(false);
            detailEpScroll = hsv;
            epRow = U.row(this);
            detailEpRow = epRow;
            epRow.setClipChildren(false);
            int zo = U.dp(this, U.RING_OUTSET + 8);
            epRow.setPadding(zo, zo, zo, zo);
            int epH = U.dp(this, 120);
            hsv.addView(epRow, new android.widget.FrameLayout.LayoutParams(-2, epH + zo * 2));
            LinearLayout.LayoutParams hvl = U.lp(-1, epH + zo * 2);
            hvl.topMargin = U.dp(this, 10);
            epBlock.addView(hsv, hvl);
            epBlock.setVisibility(View.GONE);   // 集数拉回来再显示
            page1.addView(epBlock, U.lp(-1, -2));
        } else {
            epRow = null;
        }

        // 简介已经加在标签下面了，这里不再重复占位

        // 演职人员：头像 + 姓名 + 角色，横向可翻
        final LinearLayout crewBlock = U.col(this);
        final LinearLayout crewRow = U.row(this);
        crewRow.setClipChildren(false);
        {
            int zo = U.dp(this, U.RING_OUTSET + 8);
            crewRow.setPadding(zo, zo, zo, zo);
            TextView head = U.tv(this, L.t("演职人员"), 18, U.TXT, true);
            LinearLayout.LayoutParams hl = U.lp(-1, -2);
            hl.topMargin = U.dp(this, 24);
            crewBlock.addView(head, hl);
            android.widget.HorizontalScrollView hs = new android.widget.HorizontalScrollView(this);
            hs.setHorizontalScrollBarEnabled(false);
            hs.setClipChildren(false);
            hs.setClipToPadding(false);
            hs.setFocusable(false);
            int hh = U.dp(this, 152) + zo * 2;
            hs.addView(crewRow, new android.widget.FrameLayout.LayoutParams(-2, hh));
            LinearLayout.LayoutParams cl2 = U.lp(-1, hh);
            cl2.topMargin = U.dp(this, 6);
            crewBlock.addView(hs, cl2);
            crewBlock.setVisibility(View.GONE);
            page1.addView(crewBlock, U.lp(-1, -2));
        }

        // 类似作品：2:3 海报，点击直接换到那部作品的详情页
        final LinearLayout simBlock = U.col(this);
        final LinearLayout simRow = U.row(this);
        simRow.setClipChildren(false);
        {
            int zo = U.dp(this, U.RING_OUTSET + 8);
            simRow.setPadding(zo, zo, zo, zo);
            TextView head = U.tv(this, L.t("类似作品"), 18, U.TXT, true);
            LinearLayout.LayoutParams hl = U.lp(-1, -2);
            hl.topMargin = U.dp(this, 24);
            simBlock.addView(head, hl);
            android.widget.HorizontalScrollView hs = new android.widget.HorizontalScrollView(this);
            hs.setHorizontalScrollBarEnabled(false);
            hs.setClipChildren(false);
            hs.setClipToPadding(false);
            hs.setFocusable(false);
            int hh = U.dp(this, 240) + zo * 2;
            hs.addView(simRow, new android.widget.FrameLayout.LayoutParams(-2, hh));
            LinearLayout.LayoutParams cl2 = U.lp(-1, hh);
            cl2.topMargin = U.dp(this, 6);
            simBlock.addView(hs, cl2);
            simBlock.setVisibility(View.GONE);
            page1.addView(simBlock, U.lp(-1, -2));
        }

        // 演职 / 类似作品是异步拉的（Emby 这两个接口不漏，慢一点不影响主信息）
        new Thread(new Runnable() {
            @Override public void run() {
                final List<String[]> crew = Api.people(session, it.id, 40);
                // 调试：--ez fakeCrew true 时注入假演职数据，用来验证演职横排的排版（真库有数据自然走真数据）
                try {
                    if (crew.isEmpty() && getIntent() != null && getIntent().getBooleanExtra("fakeCrew", false)) {
                        for (int i = 0; i < 8; i++) crew.add(new String[]{"演员" + (i + 1), "角色" + (i + 1), ""});
                    }
                } catch (Throwable ignored) { }
                final List<Api.Item> sims = Api.similar(session, it.id, 12);
                android.util.Log.e("LMBYTEST", "detail extra: crew=" + crew.size() + " sim=" + sims.size());
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (detailOverlay == null) return;
                        // 服务器拿不到演职人员就整块不显示，不占位置也不摆“暂无”占位
                        if (!crew.isEmpty()) {
                            fillCrewRow(crewRow, crew);
                            crewBlock.setVisibility(View.VISIBLE);
                        } else {
                            crewBlock.setVisibility(View.GONE);
                        }
                        if (!sims.isEmpty()) {
                            fillSimilarRow(simRow, sims);
                            simBlock.setVisibility(View.VISIBLE);
                        } else {
                            simBlock.setVisibility(View.GONE);
                        }
                    }
                });
            }
        }).start();
        // 详情页补查一次完整信息：顺手拿服务器的续播进度（UserData），保证续播按钮跟服务器一致
        {
            final LinearLayout crow = chipsRow;
            final LinearLayout brow = btnRow;
            final String iid = it.id;
            final Api.Item cur = it;
            new Thread(new Runnable() {
                @Override public void run() {
                    Api.Item d = null;
                    try { d = Api.detail(session, iid); } catch (Throwable ignored) { }
                    if (d == null) return;
                    final Api.Item dd = d;
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            if (detailOverlay == null) return;
                            if (dd.played != watched[0]) {
                                watched[0] = dd.played;
                                watchBtn.setActive(dd.played);
                            }
                            long pos = dd.resumeMs > 3000 ? dd.resumeMs
                                    : (dd.duration > 0 && dd.progress > 1 && dd.progress < 99
                                    ? (long) (dd.duration * (dd.progress / 100.0)) : 0);
                            if (pos > 0) {
                                cur.resumeMs = pos;
                                boolean has = false;
                                for (int k = 0; k < brow.getChildCount(); k++) {
                                    Object tag = brow.getChildAt(k).getTag();
                                    if (tag != null && "resume".equals(tag)) has = true;
                                }
                                if (!has) {
                                    TextView rb = detPlayBtn(brow, L.t("续播") + " " + fmtClock(pos), 150, false);
                                    rb.setTag("resume");
                                    rb.setOnClickListener(new View.OnClickListener() {
                                        @Override public void onClick(View v) { play(cur, false); }
                                    });
                                }
                            } else {
                                // 服务器说没有进度了（看完了/被清），续播按钮也跟着去掉
                                for (int k = brow.getChildCount() - 1; k >= 0; k--) {
                                    View cv = brow.getChildAt(k);
                                    if ("resume".equals(cv.getTag())) brow.removeView(cv);
                                }
                            }
                            if ((cur.container == null || cur.container.isEmpty())
                                    && dd.container != null && !dd.container.isEmpty())
                                detChip(crow, dd.container.toUpperCase());
                            if (cur.size <= 0 && dd.size > 0) detChip(crow, fmtSize(dd.size));
                            if (cur.bitrate <= 0 && dd.bitrate > 0)
                                detChip(crow, String.format("%.1fMbps", dd.bitrate / 1000000f));
                        }
                    });
                }
            }).start();
        }

        // 单页到底：媒体信息 + 三张卡都接在选集/简介后面，滚动就能看到，不再分第二页
        infoBlock.setFocusable(true);
        infoBlock.setFocusableInTouchMode(true);
        detailInfoBlock = infoBlock;

        // 单页结构：从标题一路向下滚动就能看到选集/简介/媒体信息/三张卡，不用按 DOWN 翻页

        // 「媒体信息」放在第一页底部，第二页只放三张信息卡
        final LinearLayout mediaBlock = U.col(this);
        LinearLayout.LayoutParams mbl = U.lp(-1, -2);
        mbl.topMargin = U.dp(this, 26);
        page1.addView(mediaBlock, mbl);

        final TextView infoHead = U.tv(this, L.t("媒体信息"), 20, U.TXT, true);
        mediaBlock.addView(infoHead, U.lp(-1, -2));

        final TextView pathTv = U.tv(this, it.path == null || it.path.isEmpty() ? L.t("文件地址 —") : it.path,
                13, U.DIM, false);
        pathTv.setSingleLine(true);
        pathTv.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        LinearLayout.LayoutParams pth = U.lp(-1, -2);
        pth.topMargin = U.dp(this, 10);
        mediaBlock.addView(pathTv, pth);

        final TextView metaTv = U.tv(this, detailTechLine(it), 13, U.DIM, false);
        LinearLayout.LayoutParams mtl = U.lp(-1, -2);
        mtl.topMargin = U.dp(this, 6);
        mediaBlock.addView(metaTv, mtl);

        // 三张信息卡接在「媒体信息」下面（第8行），顺序和 Emby 网页一致
        LinearLayout.LayoutParams ibl = U.lp(-1, -2);
        ibl.topMargin = U.dp(this, 6);
        page1.addView(infoBlock, ibl);

        final LinearLayout cards = U.row(this);
        cards.setGravity(Gravity.TOP);
        LinearLayout.LayoutParams cdl = U.lp(-1, -2);
        cdl.topMargin = U.dp(this, 16);
        infoBlock.addView(cards, cdl);

        if (isSeries) {
            TextView ph = U.tv(this, L.t("正在读取文件信息…"), 13, U.DIM, false);
            cards.addView(ph, new LinearLayout.LayoutParams(-1, -2));
        } else {
            detFillCards(cards, it);
        }
        // 三张卡等高：哪怕音频/字幕内容少，外框也和视频卡一样高
        detCardHeights(cards);
        FrameLayout.LayoutParams ofl = new FrameLayout.LayoutParams(-1, -1);
        root.addView(overlay, ofl);
        detailOverlay = overlay;
        android.util.Log.e("LMBYTEST", "openDetail done: isSeries=" + isSeries
                + " epBtn=" + (epRow == null ? "null" : "ok"));
        blockContentFocus(true);
        // 一进详情页焦点就落在「播放」上：先立即抢一次，布局完成后再补一次（否则会被图标行抢走）
        pill.requestFocus();
        overlay.post(new Runnable() {
            @Override public void run() { pill.requestFocus(); }
        });

        // 详情接口回来后补全（路径/大小/码率/流明细都靠它）
        // 剧集本身没有文件：拿「正在看/第一集」这一集的文件信息来填三张卡和媒体信息
        new Thread(new Runnable() {
            @Override public void run() {
                Api.Item src = null;
                List<Api.Item> eps = null;
                String nuId = "";
                final Api.Item[] seriesRef = new Api.Item[1];
                if (isSeries) {
                    // 简介要用「剧集本身」的 Overview：单独拉一次剧集详情，别拿某一集的（集通常没简介）
                    try { seriesRef[0] = Api.detail(session, it.id); } catch (Throwable ignored) { }
                    try { eps = Api.episodes(session, it.id); } catch (Throwable ignored) { }
                    // 官方口径：NextUp 第一项就是「最后看到一半、接着看」的那一集
                    try {
                        List<Api.Item> nu = Api.nextUp(session, it.id);
                        if (nu != null && !nu.isEmpty() && nu.get(0).id != null) nuId = nu.get(0).id;
                    } catch (Throwable ignored) { }
                    Api.Item pick = null;
                    if (eps != null && !eps.isEmpty()) {
                        for (int i = 0; i < eps.size(); i++)
                            if (eps.get(i).id != null && eps.get(i).id.equals(nuId)) { pick = eps.get(i); break; }
                        if (pick == null)
                            for (int i = 0; i < eps.size(); i++)
                                if (eps.get(i).resumeMs > 3000) { pick = eps.get(i); break; }
                        if (pick == null)
                            for (int i = 0; i < eps.size(); i++)
                                if (!eps.get(i).played) { pick = eps.get(i); break; }
                        if (pick == null) pick = eps.get(0);
                        try { src = Api.detail(session, pick.id); } catch (Throwable ignored) { }
                        if (src == null) src = pick;
                        src.seasonNo = pick.seasonNo;
                        src.index = pick.index;
                    }
                } else {
                    try { src = Api.detail(session, it.id); } catch (Exception ignored) { }
                }
                final Api.Item f = src;
                final List<Api.Item> feps = eps;
                final String fnu = nuId;
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (detailOverlay != ov) return;
                        nextUpEpId = fnu;    // 先落地 NextUp，fillEpisodeRow 里的续播判断才拿得到它
                        if (feps != null && !feps.isEmpty()) {
                            int seasons = 0;
                            java.util.HashSet<Integer> ss = new java.util.HashSet<Integer>();
                            for (int i = 0; i < feps.size(); i++) {
                                int sn = feps.get(i).seasonNo;
                                if (sn > 0) ss.add(sn);
                            }
                            seasons = ss.size();
                            detailEps = feps;
                            detailSeason = -1;
                            buildSeasonTabs(feps);
                            fillEpisodeRow(epRow, feps);
                            epBlock.setVisibility(View.VISIBLE);
                            metaLine.setText(detailSubLine(it, seasons));
                            // 剧集续播：取「最后看的那一集」；服务器只给百分比时用它换算位置
                            Api.Item resumeEp = findResumeEpisode(feps);
                            if (resumeEp != null) showDetailResume(resumeEp);
                        }
                        if (f == null) return;
                        if (!isSeries) {
                            mergeDetail(it, f);
                            name.setText(cleanName(it.name));
                            metaLine.setText(detailSubLine(it, -1));
                        } else {
                            fillChips(chipsRow, f);
                            // 剧集也只用「媒体信息」四个字，不再追「取自第 N 集」
                            infoHead.setText(L.t("媒体信息"));
                        }
                        Api.Item ovSrc = f;
                        if (isSeries && seriesRef[0] != null
                                && seriesRef[0].overview != null && !seriesRef[0].overview.isEmpty()) {
                            ovSrc = seriesRef[0];
                        }
                        if (ovSrc != null && ovSrc.overview != null && !ovSrc.overview.isEmpty()) {
                            ovTv.setText(ovSrc.overview);
                            ovTv.setVisibility(View.VISIBLE);
                        }
                        pathTv.setText(f.path == null || f.path.isEmpty()
                                ? L.t("文件地址 —") : f.path);
                        metaTv.setText(detailTechLine(f));
                        cards.removeAllViews();
                        detFillCards(cards, f);
                        detCardHeights(cards);
                        loadDetailBackdrop(bg, it);
        if (U.EMBY) bg.setAlpha(0.45f);
                    }
                });
            }
        }).start();
    }

    /** 详情页大背景：优先用横版剧照（backdrop），没有再退回海报 */
    private void loadDetailBackdrop(final ImageView bg, Api.Item it) {
        try {
            if (it.backdropTag != null && !it.backdropTag.isEmpty()) {
                Img.load(bg, Api.backdropUrl(session, it.id, it.backdropTag, 1280));
            } else {
                Img.load(bg, Api.imageUrl(session, it.id, it.imgTag, 1080));
            }
        } catch (Throwable ignored) { }
    }

    /** 三张信息卡统一到最高的一张（内容少的也和外框一样高） */
    private void detCardHeights(final LinearLayout cards) {        cards.post(new Runnable() {
            @Override public void run() {
                int maxH = 0;
                for (int i = 0; i < cards.getChildCount(); i++) {
                    View c2 = cards.getChildAt(i);
                    c2.measure(0, 0);
                    if (c2.getMeasuredHeight() > maxH) maxH = c2.getMeasuredHeight();
                }
                int tall2 = Math.max(maxH, U.dp(MainActivity.this, 400));
                for (int i = 0; i < cards.getChildCount(); i++) {
                    View c2 = cards.getChildAt(i);
                    LinearLayout.LayoutParams lp2 = (LinearLayout.LayoutParams) c2.getLayoutParams();
                    lp2.height = tall2;
                    c2.setLayoutParams(lp2);
                }
            }
        });
    }

    /** 小胶囊行（第几集 / 分辨率 / HDR / 容器 / 大小 / 码率） */
    private void fillChips(LinearLayout row, Api.Item it) {
        if (row == null) return;
        row.removeAllViews();
        String ep = (it.index > 0 && (it.series != null && !it.series.isEmpty() || it.seasonNo > 0))
                ? (L.t("第") + it.index + L.t("集")) : "";
        String[] chips = new String[]{
                ep,
                it.duration > 0 ? U.time(it.duration) : "",
                it.resLabel(),
                it.vRange == null || it.vRange.isEmpty() ? "SDR" : it.vRange.toUpperCase(),
                it.container == null || it.container.isEmpty() ? "" : it.container.toUpperCase(),
                it.size > 0 ? fmtSize(it.size) : "",
                it.bitrate > 0 ? String.format("%.1fMbps", it.bitrate / 1000000f) : ""
        };
        for (String c0 : chips) {
            if (c0 == null || c0.isEmpty()) continue;
            detChip(row, c0);
        }
    }

    /** 剧集详情页的选集行：16:9 视频帧缩略图 + 集号，确认直接播这一集 */
    private void fillEpisodeRow(LinearLayout row, List<Api.Item> eps) {
        if (row == null || eps == null) return;
        row.removeAllViews();
        int epH = U.dp(this, 120);
        int labelPx = U.dp(this, 34);
        int cw = Math.round((epH - labelPx) * 16f / 9f);
        int n = Math.min(eps.size(), 200);
        Api.Item cont = findResumeEpisode(eps);     // 最后退出来、还有剩余的那一集
        View contCard = null;
        for (int i = 0; i < n; i++) {
            final Api.Item ep = eps.get(i);
            Card card = new Card(this, 16f / 9f);
            card.key = ep.id;
            String num = ep.index > 0 ? (L.t("第") + ep.index + L.t("集")) : ("#" + (i + 1));
            String sub = ep.duration > 0 ? U.time(ep.duration) : "";
            if (ep.seasonNo > 0 && ep.index > 0) sub = "S" + ep.seasonNo + "E" + ep.index;
            String img = ep.thumbTag != null && !ep.thumbTag.isEmpty()
                    ? Api.thumbUrl(session, ep.id, ep.thumbTag, 480)
                    : Api.imageUrl(session, ep.id, ep.imgTag, 480);
            card.setData(num, sub, img);
            // 右上角跟媒体库一致：只显示播放进度百分比（没看/看完都不显示）
            applyPctBadge(card, ep);
            // 最后退出来的那一集：缩略图底部再挂一条「继续播放」提示
            if (cont != null && ep.id != null && ep.id.equals(cont.id)) {
                int p = pctOf(ep);
                card.setContinue(L.t("继续播放") + (p > 0 ? "  " + p + "%" : ""));
                contCard = card;
            }
            card.setLabelHeightPx(labelPx);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(cw, epH);
            lp.rightMargin = U.dp(this, 12);
            card.setLayoutParams(lp);
            card.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { play(ep, false); }
            });
            attachCenterOnFocus(card);
            row.addView(card);
        }
        // 一进详情页就把「继续播放」那集滚到中间，保证直接看得见它是第几集
        if (contCard != null) scrollEpRowTo(contCard);
    }

    /** 横排里「暂无数据」的灰字提示 */
    private TextView detHint(String text) {
        TextView t = U.tv(this, text, 13, 0xFF8A9099, false);
        LinearLayout.LayoutParams lp = U.lp(-2, -2);
        lp.leftMargin = U.dp(this, 6);
        lp.topMargin = U.dp(this, 12);
        t.setLayoutParams(lp);
        return t;
    }

    /** 分季标签：只有一季就不显示 */
    private void buildSeasonTabs(List<Api.Item> eps) {
        LinearLayout row = detailSeasonRow;
        if (row == null) return;
        row.removeAllViews();
        java.util.LinkedHashSet<Integer> ss = new java.util.LinkedHashSet<Integer>();
        for (int i = 0; i < eps.size(); i++) if (eps.get(i).seasonNo > 0) ss.add(eps.get(i).seasonNo);
        if (ss.size() <= 1) {
            row.setVisibility(View.GONE);
            return;
        }
        row.setVisibility(View.VISIBLE);
        addSeasonChip(row, -1, L.t("全部"));
        for (Integer sn : ss) addSeasonChip(row, sn, "S" + sn);
    }

    private void addSeasonChip(LinearLayout row, final int season, String text) {
        final TextView chip = new TextView(this);
        U.face(chip);
        boolean sel = detailSeason == season;
        chip.setText(text);
        chip.setTextSize(13);
        chip.setTextColor(U.txt(sel ? 0xFFFFFFFF : 0xFFAEB4BA));
        chip.setGravity(Gravity.CENTER);
        chip.setPadding(U.dp(this, 14), U.dp(this, 6), U.dp(this, 14), U.dp(this, 6));
        chip.setBackground(chipBg(sel));
        chip.setFocusable(true);
        chip.setFocusableInTouchMode(true);
        U.ringFg(chip, U.dp(this, 16));
        chip.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                detailSeason = season;
                if (detailEps != null) {
                    buildSeasonTabs(detailEps);
                    fillEpisodeRow(detailEpRow, epsOfSeason(detailEps, detailSeason));
                }
                if (detailEpRow != null && detailEpRow.getChildCount() > 0)
                    detailEpRow.getChildAt(0).requestFocus();
            }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.leftMargin = U.dp(this, 8);
        row.addView(chip, lp);
    }

    /** 按季过滤；season<=0 返回全部 */
    private List<Api.Item> epsOfSeason(List<Api.Item> eps, int season) {
        if (eps == null) return new ArrayList<Api.Item>();
        if (season <= 0) return eps;
        List<Api.Item> out = new ArrayList<Api.Item>();
        for (int i = 0; i < eps.size(); i++) if (eps.get(i).seasonNo == season) out.add(eps.get(i));
        return out;
    }

    /** 跳到第 N 集：数字键盘 + 定位到那一集 */
    private void showEpisodeJump() {
        if (detailEps == null || detailEps.isEmpty() || detailEpRow == null) return;
        final FrameLayout overlay = new FrameLayout(this);
        epJumpOverlay = overlay;
        View dim = new View(this);
        dim.setBackgroundColor(0x99000000);
        dim.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { closeEpisodeJump(); }
        });
        overlay.addView(dim, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout panel = U.col(this);
        panel.setBackground((U.EMBY ? U.cyberSheet(this) : U.sheetBg(this)));
        panel.setPadding(U.dp(this, 14), U.dp(this, 12), U.dp(this, 14), U.dp(this, 10));
        if (android.os.Build.VERSION.SDK_INT >= 21) panel.setElevation(U.dp(this, 14));

        panel.addView(U.tv(this, L.t("跳到第N集"), 16, U.TXT, true), U.lp(-1, -2));
        final TextView input = U.tv(this, "", 24, U.TXT, true);
        LinearLayout.LayoutParams il = U.lp(-1, -2);
        il.topMargin = U.dp(this, 6);
        panel.addView(input, il);

        final String[] buf = {""};
        String[] keys = {"1", "2", "3", "4", "5", "6", "7", "8", "9", L.t("删除"), "0", L.t("确定")};
        final TextView[] grid = new TextView[keys.length];
        for (int r = 0; r < 4; r++) {
            LinearLayout row = U.row(this);
            for (int c = 0; c < 3; c++) {
                final int idx = r * 3 + c;
                final TextView k = new TextView(this);
                U.face(k);
                k.setText(keys[idx]);
                k.setTextSize(16);
                k.setTextColor(U.txt(U.TXT));
                k.setGravity(Gravity.CENTER);
                k.setBackground(chipBg(false));
                k.setFocusable(true);
                k.setFocusableInTouchMode(true);
                U.ringFg(k, U.dp(this, 12));
                k.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        String s = keys[idx];
                        if (L.t("删除").equals(s)) {
                            if (buf[0].length() > 0) buf[0] = buf[0].substring(0, buf[0].length() - 1);
                            input.setText(buf[0]);
                            return;
                        }
                        if (L.t("确定").equals(s)) {
                            int n = 0;
                            try { n = Integer.parseInt(buf[0]); } catch (Throwable ignored) { }
                            closeEpisodeJump();
                            jumpToEpisode(n);
                            return;
                        }
                        if (buf[0].length() >= 4) return;
                        buf[0] = buf[0] + s;
                        input.setText(buf[0]);
                    }
                });
                LinearLayout.LayoutParams kl = new LinearLayout.LayoutParams(0, U.dp(this, 48));
                kl.weight = 1;
                if (c > 0) kl.leftMargin = U.dp(this, 8);
                if (r > 0) kl.topMargin = U.dp(this, 8);
                row.addView(k, kl);
                grid[idx] = k;
            }
            LinearLayout.LayoutParams rl = U.lp(-1, -2);
            if (r > 0) rl.topMargin = U.dp(this, 8);
            panel.addView(row, rl);
        }

        FrameLayout.LayoutParams pp = new FrameLayout.LayoutParams(U.dp(this, 300), -2);
        pp.gravity = Gravity.CENTER;
        overlay.addView(panel, pp);
        root.addView(overlay, new FrameLayout.LayoutParams(-1, -1));
        grid[1].requestFocus();
    }

    /** 关掉「跳到第N集」面板，焦点回到选集行（返回键/取消都走这里） */
    private void closeEpisodeJump() {
        if (epJumpOverlay != null) {
            root.removeView(epJumpOverlay);
            epJumpOverlay = null;
        }
        if (detailEpRow != null && detailEpRow.getChildCount() > 0) {
            detailEpRow.getChildAt(0).requestFocus();
        }
    }

    /** 把焦点移到第 n 集（在当前显示的季里找） */
    private void jumpToEpisode(int n) {
        if (n <= 0 || detailEpRow == null || detailEps == null) return;
        List<Api.Item> shown = epsOfSeason(detailEps, detailSeason);
        int pos = -1;
        for (int i = 0; i < shown.size(); i++) if (shown.get(i).index == n) { pos = i; break; }
        if (pos < 0) return;
        final View target = detailEpRow.getChildAt(pos);
        if (target == null) return;
        target.requestFocus();
        if (target.getParent() instanceof android.view.View) {
            // 让横向滚动条把这一集滚进视野
            final android.view.ViewGroup parent = (android.view.ViewGroup) target.getParent();
            target.post(new Runnable() {
                @Override public void run() { parent.requestChildFocus(target, target); }
            });
        }
    }

    /** 演职人员横排：头像 + 名字 + 角色 */
    private void fillCrewRow(LinearLayout row, List<String[]> crew) {
        if (row == null || crew == null) return;
        row.removeAllViews();
        int h = U.dp(this, 152), lab = U.dp(this, 46);
        int w = h - lab;   // 方形头像
        int n = Math.min(crew.size(), 40);
        for (int i = 0; i < n; i++) {
            String[] p = crew.get(i);
            Card card = new Card(this, w / (float) Math.max(1, h - lab));
            card.setData(p[0], p[1], p[2]);
            // 没有头像时给一块稳重的灰底，别留一片黑洞
            if (p[2] == null || p[2].isEmpty()) card.img.setBackgroundColor(0xFF2B313A);
            card.setLabelHeightPx(lab);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(w, h);
            lp.rightMargin = U.dp(this, 10);
            card.setLayoutParams(lp);
            attachCenterOnFocus(card);
            row.addView(card);
        }
    }

    /** 类似作品横排：2:3 海报，点击切换到那部作品的详情页 */
    private void fillSimilarRow(LinearLayout row, List<Api.Item> items) {
        if (row == null || items == null) return;
        row.removeAllViews();
        int h = U.dp(this, 240), lab = U.dp(this, 44);
        int w = Math.round((h - lab) * 2f / 3f);
        int n = Math.min(items.size(), 12);
        for (int i = 0; i < n; i++) {
            final Api.Item sm = items.get(i);
            Card card = new Card(this, 2f / 3f);
            String sub = sm.year > 0 ? String.valueOf(sm.year) : "";
            card.setData(cleanName(sm.name), sub, Api.imageUrl(session, sm.id, sm.imgTag, 480));
            card.setLabelHeightPx(lab);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(w, h);
            lp.rightMargin = U.dp(this, 12);
            card.setLayoutParams(lp);
            card.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    closeDetail();
                    openDetail(sm, null);
                }
            });
            attachCenterOnFocus(card);
            row.addView(card);
        }
    }

    /** adb 调试：自动打开第一个剧集库里的最新一部剧的详情页 */
    private void openFirstSeriesForTest() {
        if (session == null) {
            android.util.Log.e("LMBYTEST", "openSeries: session null");
            return;
        }
        new Thread(new Runnable() {
            @Override public void run() {
                String want = null;
                try { want = getIntent() == null ? null : getIntent().getStringExtra("lib"); } catch (Throwable ignored) { }
                Api.Lib lib = null;
                if (want != null && !want.isEmpty()) {
                    for (int i = 0; i < libs.size(); i++)
                        if (want.equals(libs.get(i).name)) { lib = libs.get(i); break; }
                }
                if (lib == null) {
                    for (int i = 0; i < libs.size(); i++) {
                        if ("tvshows".equals(libs.get(i).type)) { lib = libs.get(i); break; }
                    }
                }
                if (lib == null && !libs.isEmpty()) lib = libs.get(0);
                if (lib == null) return;
                List<Api.Item> its = new ArrayList<Api.Item>();
                try { its = Api.latest(session, lib.id, "Series", 8); } catch (Throwable ignored) { }
                if (its.isEmpty()) {
                    try { its = Api.latest(session, lib.id, "Movie,Video", 8); } catch (Throwable ignored) { }
                }
                if (its.isEmpty()) {
                    android.util.Log.e("LMBYTEST", "openSeries: no items");
                    return;
                }
                Api.Item first = its.get(0);
                try { first = Api.detail(session, first.id); } catch (Throwable ignored) { }
                final Api.Item f = first;
                android.util.Log.e("LMBYTEST", "openSeries: " + lib.name + " -> " + f.type + " / " + f.name);
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (!isFinishing()) openDetail(f, null);
                    }
                });
            }
        }).start();
    }

    /** 剧集信息行：年份 · 总集数 */
    private String seriesMeta(Api.Item it) {        StringBuilder b = new StringBuilder();
        if (it.year > 0) b.append(it.year).append(" · ");
        b.append(it.childCount > 0 ? it.childCount + L.t("集") : L.t("剧集"));
        return b.toString();
    }

    /** 详情页第二行：★ 评分 · 上映日期 · 共几季 · 类型 */
    private String detailSubLine(Api.Item it, int seasons) {
        StringBuilder b = new StringBuilder();
        if (it.rating > 0) b.append("★ ").append(String.format("%.1f", (float) it.rating)).append("    ");
        String pd = it.premiereDate;
        if (pd != null && pd.length() >= 10) b.append(pd.substring(0, 10)).append("    ");
        else if (it.year > 0) b.append(it.year).append("    ");
        if (seasons > 0) b.append(L.t("共")).append(seasons).append(L.t("季")).append("    ");
        String t = it.type == null ? "" : it.type;
        if (("Series".equals(t) || "Season".equals(t)) && it.childCount > 0)
            b.append(it.childCount).append(L.t("集")).append("    ");
        if (it.genres != null && !it.genres.isEmpty()) b.append(it.genres);
        String s = b.toString().trim();
        return s.isEmpty() ? L.t("暂无刮削信息") : s;
    }

    /** 剧集详情页「播放」：有续播就接着那一集，否则从第一集没看过的开始 */
    private void playSeriesEpisode(final Api.Item show) {
        new Thread(new Runnable() {
            @Override public void run() {
                Api.Item target = null;
                try {
                    List<Api.Item> eps = Api.episodes(session, show.id);
                    for (int i = 0; i < eps.size(); i++) {
                        Api.Item e = eps.get(i);
                        if (e.resumeMs > 3000) { target = e; break; }
                    }
                    if (target == null) {
                        for (int i = 0; i < eps.size(); i++) {
                            Api.Item e = eps.get(i);
                            if (!e.played) { target = e; break; }
                        }
                    }
                    if (target == null && !eps.isEmpty()) target = eps.get(eps.size() - 1);
                } catch (Throwable ignored) { }
                final Api.Item t = target;
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (t == null) {
                            android.widget.Toast.makeText(MainActivity.this, L.t("没有可播放的集"),
                                    android.widget.Toast.LENGTH_SHORT).show();
                            return;
                        }
                        if (detailOverlay != null) closeDetail();
                        play(t, false);
                    }
                });
            }
        }).start();
    }

    /** 详情接口补回来的字段并进当前条目 */
    private void mergeDetail(Api.Item dst, Api.Item f) {
        if (f.width > 0) dst.width = f.width;
        if (f.height > 0) dst.height = f.height;
        if (f.duration > 0) dst.duration = f.duration;
        if (f.container != null && !f.container.isEmpty()) dst.container = f.container;
        if (f.path != null && !f.path.isEmpty()) dst.path = f.path;
        if (f.size > 0) dst.size = f.size;
        if (f.bitrate > 0) dst.bitrate = f.bitrate;
        if (f.videoCodec != null && !f.videoCodec.isEmpty()) dst.videoCodec = f.videoCodec;
        if (f.dateCreated != null) dst.dateCreated = f.dateCreated;
        dst.vProfile = f.vProfile;
        dst.vLevel = f.vLevel;
        dst.vBitRate = f.vBitRate;
        dst.vFps = f.vFps;
        dst.vBitDepth = f.vBitDepth;
        dst.vPixelFormat = f.vPixelFormat;
        dst.vRange = f.vRange;
        dst.vColorSpace = f.vColorSpace;
        dst.vAspect = f.vAspect;
        dst.vRefFrames = f.vRefFrames;
        dst.vInterlaced = f.vInterlaced;
        dst.aCodec = f.aCodec;
        dst.aProfile = f.aProfile;
        dst.aBitRate = f.aBitRate;
        dst.aSampleRate = f.aSampleRate;
        dst.aBitDepth = f.aBitDepth;
        dst.aDefault = f.aDefault;
        dst.subCodec = f.subCodec;
        dst.subDefault = f.subDefault;
        dst.subForced = f.subForced;
        dst.subExternal = f.subExternal;
        dst.audioChannels = f.audioChannels;
        dst.audioTracks = f.audioTracks;
        dst.subTracks = f.subTracks;
        dst.sourceCount = f.sourceCount;
        if (f.resHeight > 0) dst.resHeight = f.resHeight;
    }

    /** 视频 / 音频 / 字幕三张信息卡 */
    private void detFillCards(LinearLayout cards, Api.Item it) {
        LinearLayout vCard = detCard(cards, L.t("视频"));
        detRow(vCard, L.t("编码器"), it.videoCodec.isEmpty() ? "—" : it.videoCodec);
        detRow(vCard, L.t("配置"), emptyDash(it.vProfile));
        detRow(vCard, L.t("等级"), emptyDash(it.vLevel));
        detRow(vCard, L.t("分辨率"), it.width > 0 && it.height > 0 ? (it.width + "×" + it.height) : "—");
        detRow(vCard, L.t("宽高比"), emptyDash(it.vAspect));
        detRow(vCard, L.t("隔行"), it.vInterlaced ? L.t("是") : L.t("否"));
        detRow(vCard, L.t("帧率"), it.vFps > 0 ? (String.format("%.3f", (float) it.vFps) + " fps") : "—");
        detRow(vCard, L.t("比特率"), it.vBitRate > 0 ? fmtKbps(it.vBitRate) : "—");
        detRow(vCard, L.t("动态范围"), it.vRange.isEmpty() ? "SDR" : it.vRange.toUpperCase());
        detRow(vCard, L.t("色彩空间"), emptyDash(it.vColorSpace));
        detRow(vCard, L.t("位深"), it.vBitDepth > 0 ? (it.vBitDepth + " bit") : "—");
        detRow(vCard, L.t("像素格式"), emptyDash(it.vPixelFormat));
        detRow(vCard, L.t("参考帧"), it.vRefFrames > 0 ? String.valueOf(it.vRefFrames) : "—");

        LinearLayout aCard = detCard(cards, L.t("音频"));
        detRow(aCard, L.t("解码器"), emptyDash(it.aCodec));
        detRow(aCard, L.t("配置"), emptyDash(it.aProfile));
        detRow(aCard, L.t("声道"), it.audioChannels > 0 ? String.valueOf(it.audioChannels) : "—");
        detRow(aCard, L.t("采样率"), it.aSampleRate > 0 ? (it.aSampleRate + " Hz") : "—");
        detRow(aCard, L.t("比特率"), it.aBitRate > 0 ? fmtKbps(it.aBitRate) : "—");
        detRow(aCard, L.t("位深"), it.aBitDepth > 0 ? (it.aBitDepth + " bit") : "—");
        detRow(aCard, L.t("默认"), it.aDefault ? L.t("是") : L.t("否"));

        String sTitle = it.subTracks > 0 && !it.subCodec.isEmpty()
                ? (L.t("字幕 (") + it.subCodec + ")") : L.t("字幕");
        LinearLayout sCard = detCard(cards, sTitle);
        sCard.setVisibility(View.VISIBLE);
        if (!it.subDisplay.isEmpty() && !"null".equals(it.subDisplay)) {
            detRow(sCard, L.t("名称"), it.subDisplay);
        }
        detRow(sCard, L.t("解码器"), emptyDash(it.subCodec));
        detRow(sCard, L.t("默认"), it.subDefault ? L.t("是") : L.t("否"));
        detRow(sCard, L.t("强制"), it.subForced ? L.t("是") : L.t("否"));
        detRow(sCard, L.t("外部"), it.subExternal ? L.t("是") : L.t("否"));
    }

    /** 圆角图标按钮：详情页顶部的圆形操作行 */
    private IconBtn detIcon(LinearLayout row, String glyph) {
        IconBtn b = new IconBtn(this, glyph, "");
        b.setCircle(U.EMBY);   // 炫酷圆形图标键；玻璃跟着方形化
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(U.dp(this, 54), U.dp(this, 54));
        lp.rightMargin = U.dp(this, 16);
        row.addView(b, lp);
        return b;
    }

    /** 描边小胶囊（720P / SDR / MP4 / 206M / 1.5Mbps） */
    private TextView detChip(LinearLayout row, String text) {
        TextView t = U.tv(this, text, 12, U.EMBY ? 0xFF1C1C1E : 0xFFE6EAEE, false);
        t.setGravity(Gravity.CENTER);
        t.setPadding(U.dp(this, 13), U.dp(this, 6), U.dp(this, 13), U.dp(this, 6));
        if (U.EMBY) {   // 炫酷：幽蓝玻璃胶囊 + 青边
            t.setBackground(U.roundStroke(0xFFF2F2F7, U.dp(this, 6), U.dp(this, 1), 0x0F000000));
        } else {
            t.setBackground(U.roundStroke(0x33000000, U.dp(this, 6), U.dp(this, 1), 0x59FFFFFF));
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.rightMargin = U.dp(this, 10);
        row.addView(t, lp);
        return t;
    }

    /** 浅色半透明信息卡 */
    private LinearLayout detCard(LinearLayout parent, String title) {
        LinearLayout card = U.col(this);
        if (U.EMBY) {
            card.setBackground(U.roundStroke(0xFFF2F2F7, U.dp(this, 10), U.dp(this, 1), 0x0F000000));
        } else {
            card.setBackground(U.roundStroke(U.SOFT_L, U.dp(this, 12), U.dp(this, 1), U.SOFT));
        }
        card.setPadding(U.dp(this, 14), U.dp(this, 12), U.dp(this, 14), U.dp(this, 14));
        TextView h = U.tv(this, title, 14, 0xFFFFFFFF, true);
        LinearLayout.LayoutParams hl = U.lp(-1, -2);
        hl.bottomMargin = U.dp(this, 8);
        card.addView(h, hl);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1f);
        lp.rightMargin = U.dp(this, 12);
        parent.addView(card, lp);
        return card;
    }

    /** 一行「标签 值」 */
    private void detRow(LinearLayout card, String label, String value) {
        LinearLayout r = U.row(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        TextView l = U.tv(this, label, 12, 0xFF9AA0A6, false);
        TextView v = U.tv(this, value == null || value.isEmpty() ? "—" : value, 12, 0xFFE8EAED, false);
        v.setSingleLine(true);
        v.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams ll = new LinearLayout.LayoutParams(U.dp(this, 92), -2);
        r.addView(l, ll);
        r.addView(v, new LinearLayout.LayoutParams(0, -2, 1f));
        LinearLayout.LayoutParams rl = U.lp(-1, -2);
        rl.topMargin = U.dp(this, 5);
        card.addView(r, rl);
    }

    private String emptyDash(String s) {
        return s == null || s.isEmpty() || "null".equals(s) ? "—" : s;
    }

    private String fmtKbps(int bits) {
        if (bits <= 0) return "—";
        if (bits >= 1000000) return String.format("%.1fMbps", bits / 1000000f);
        return (bits / 1000) + "Kbps";
    }

    /** 「MP4 · 206M · 1.5Mbps · 添加于 2015/5/23 21:34」这一行 */
    private String detailTechLine(Api.Item it) {
        StringBuilder b = new StringBuilder();
        if (it.container != null && !it.container.isEmpty()) b.append(it.container.toUpperCase()).append("  ");
        if (it.size > 0) b.append(fmtSize(it.size)).append("  ");
        if (it.bitrate > 0) b.append(String.format("%.1fMbps", it.bitrate / 1000000f)).append("  ");
        String added = detDate(it.dateCreated);
        if (!added.isEmpty()) b.append(L.t("添加于 ")).append(added);
        return b.toString().trim();
    }

    private String detDate(String d) {
        try {
            if (d == null || d.length() < 10) return "";
            int y = Integer.parseInt(d.substring(0, 4));
            int mo = Integer.parseInt(d.substring(5, 7));
            int da = Integer.parseInt(d.substring(8, 10));
            String tm = d.length() >= 16 ? d.substring(11, 16) : "";
            return y + "/" + mo + "/" + da + (tm.isEmpty() ? "" : " " + tm);
        } catch (Throwable e) {
            return "";
        }
    }

    /** 「选择版本 / 选择字幕 / 选择音轨」弹层 */
    private void showDetailChooser(String title, java.util.List<String[]> items, int sel,
                                   boolean markPick) {
        showDetailChooser(title, items, sel, markPick, null);
    }

    /** 通用选择面板：版本 / 选集 / 音轨 / 字幕，样式参考主流播放器的选集弹层 */
    private void showDetailChooser(String title, java.util.List<String[]> items, int sel,
                                   boolean markPick, final java.util.List<Api.Item> eps) {
        if (detailChooser != null) {
            root.removeView(detailChooser);
            detailChooser = null;
        }
        final View prev = getCurrentFocus();
        final FrameLayout dim = new FrameLayout(this);
        dim.setBackgroundColor(0xD9000000);
        dim.setFocusable(true);
        final FrameLayout panel = new FrameLayout(this);
        panel.setFocusable(true);

        LinearLayout card = U.col(this);
        card.setBackground((U.EMBY ? U.cyberSheet(this) : U.sheetBg(this)));
        card.setPadding(U.dp(this, 24), U.dp(this, 20), U.dp(this, 24), U.dp(this, 18));

        LinearLayout headRow = U.row(this);
        headRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView h = U.tv(this, title, 20, 0xFFFFFFFF, true);
        headRow.addView(h, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView cnt = U.tv(this, items.size() + L.t(" 项"), 13, 0xFF8E9398, false);
        headRow.addView(cnt, U.lp(-2, -2));
        LinearLayout.LayoutParams hl = U.lp(-1, -2);
        hl.bottomMargin = U.dp(this, 14);
        card.addView(headRow, hl);

        final android.widget.ScrollView sc = new android.widget.ScrollView(this);
        sc.setVerticalScrollBarEnabled(false);
        sc.setClipToPadding(false);
        LinearLayout list = U.col(this);
        sc.addView(list, U.lp(-1, -2));
        int maxListH = items.size() > 7 ? U.dp(this, 440) : -2;
        card.addView(sc, new LinearLayout.LayoutParams(-1, maxListH));

        final java.util.List<View> rowViews = new java.util.ArrayList<View>();
        for (int i = 0; i < items.size(); i++) {
            final String[] row0 = items.get(i);
            final boolean picked = i == sel;
            LinearLayout r = U.row(this);
            r.setGravity(Gravity.CENTER_VERTICAL);
            r.setBackground(picked
                    ? U.round(0xFFFFFFFF, U.dp(this, 10))
                    : U.round(U.SOFT_L, U.dp(this, 10)));
            r.setPadding(U.dp(this, 16), U.dp(this, 12), U.dp(this, 16), U.dp(this, 12));
            r.setFocusable(true);
            r.setFocusableInTouchMode(true);
            LinearLayout.LayoutParams rl = U.lp(-1, -2);
            rl.topMargin = i == 0 ? 0 : U.dp(this, 6);
            list.addView(r, rl);
            rowViews.add(r);

            if (picked) {
                TextView tick = U.tv(this, "✓", 16, U.ACC, true);
                LinearLayout.LayoutParams tl = U.lp(-2, -2);
                tl.rightMargin = U.dp(this, 12);
                r.addView(tick, tl);
            }
            LinearLayout tx = U.col(this);
            r.addView(tx, new LinearLayout.LayoutParams(0, -2, 1f));
            TextView l = U.tv(this, row0[0], 16, picked ? U.ACC : 0xFFF1F3F4, picked);
            l.setSingleLine(true);
            l.setEllipsize(android.text.TextUtils.TruncateAt.END);
            tx.addView(l, U.lp(-1, -2));
            if (row0.length > 1 && row0[1] != null && !row0[1].isEmpty()) {
                TextView sub = U.tv(this, row0[1], 12,
                        picked ? 0xAA2FBF74 : 0xFF9AA0A6, false);
                LinearLayout.LayoutParams sl = U.lp(-1, -2);
                sl.topMargin = U.dp(this, 3);
                tx.addView(sub, sl);
            }

            r.setOnFocusChangeListener(new View.OnFocusChangeListener() {
                @Override public void onFocusChange(View v, boolean has) {
                    v.setBackground(has
                            ? U.roundStroke(picked ? 0xFFFFFFFF : 0x2BFFFFFF, U.dp(MainActivity.this, 10),
                            U.dp(MainActivity.this, 2), U.ACC)
                            : U.round(picked ? 0xFFFFFFFF : U.SOFT_L, U.dp(MainActivity.this, 10)));
                }
            });

            final int idx0 = i;
            r.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    closeChooser(dim, prev);
                    if (eps != null) {
                        if (idx0 < eps.size()) play(eps.get(idx0), false);
                        return;
                    }
                    if (markPick) {
                        android.widget.Toast.makeText(MainActivity.this,
                                L.t("已选择：") + row0[0] + L.t("（播放时可继续切换）"),
                                android.widget.Toast.LENGTH_SHORT).show();
                    }
                }
            });
            r.setOnKeyListener(new View.OnKeyListener() {
                @Override public boolean onKey(View v, int keyCode, android.view.KeyEvent e) {
                    if (e.getAction() == android.view.KeyEvent.ACTION_DOWN
                            && keyCode == android.view.KeyEvent.KEYCODE_BACK) {
                        closeChooser(dim, prev);
                        return true;
                    }
                    return false;
                }
            });
        }

        FrameLayout.LayoutParams cpl = new FrameLayout.LayoutParams(U.dp(this, 520), -2);
        cpl.gravity = Gravity.CENTER;
        panel.addView(card, cpl);
        dim.addView(panel, new FrameLayout.LayoutParams(-1, -1));
        root.addView(dim, new FrameLayout.LayoutParams(-1, -1));
        detailChooser = dim;
        blockContentFocus(true);
        final int selIdx = sel;
        sc.post(new Runnable() {
            @Override public void run() {
                if (selIdx >= 0 && selIdx < rowViews.size()) {
                    View v = rowViews.get(selIdx);
                    v.requestFocus();
                    int top = v.getTop() - U.dp(MainActivity.this, 120);
                    sc.smoothScrollTo(0, Math.max(0, top));
                } else if (!rowViews.isEmpty()) {
                    rowViews.get(0).requestFocus();
                }
            }
        });
    }

    /** 收起选择面板，并把焦点还给打开它的那个按钮 */
    private void closeChooser(View dim, View prev) {
        if (dim != null && dim.getParent() != null) root.removeView(dim);
        detailChooser = null;
        blockContentFocus(true);
        if (prev != null && prev.getParent() != null) prev.requestFocus();
    }

    /** 选集：剧集列出全部集，非剧集列出可播版本 */
    private void showVersions(final Api.Item it) {
        if (it.seriesId == null || it.seriesId.isEmpty()) {
            java.util.List<String[]> rows = new java.util.ArrayList<String[]>();
            StringBuilder sub = new StringBuilder();
            if (it.container != null && !it.container.isEmpty()) sub.append(it.container.toUpperCase());
            sub.append(sub.length() > 0 ? " " : "").append(it.resLabel());
            if (it.size > 0) sub.append(" ").append(fmtSize(it.size));
            rows.add(new String[]{cleanName(it.name), sub.toString()});
            for (int i = 1; i < it.sourceCount; i++) {
                rows.add(new String[]{L.t("版本 ") + (i + 1), L.t("同一文件的其他版本")});
            }
            showDetailChooser(L.t("选择版本"), rows, 0, false);
            return;
        }
        final android.app.ProgressDialog pd = new android.app.ProgressDialog(this);
        pd.setMessage(L.t("读取剧集…"));
        pd.show();
        new Thread(new Runnable() {
            @Override public void run() {
                java.util.ArrayList<Api.Item> eps = new java.util.ArrayList<Api.Item>();
                try { eps.addAll(Api.episodes(session, it.seriesId)); } catch (Exception ignored) { }
                final java.util.List<Api.Item> list = eps;
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        pd.dismiss();
                        if (detailOverlay == null) return;
                        if (list.isEmpty()) {
                            android.widget.Toast.makeText(MainActivity.this,
                                    L.t("没有读到剧集"), android.widget.Toast.LENGTH_SHORT).show();
                            return;
                        }
                        java.util.List<String[]> rows = new java.util.ArrayList<String[]>();
                        int sel = 0;
                        for (int i = 0; i < list.size(); i++) {
                            Api.Item e = list.get(i);
                            String ep = e.index > 0 ? (L.t("第 ") + e.index + L.t(" 集")) : (L.t("第 ") + (i + 1) + L.t(" 集"));
                            String title = ep + " · " + cleanName(e.name);
                            StringBuilder sub = new StringBuilder();
                            if (e.duration > 0) sub.append(U.time(e.duration));
                            if (e.progress > 0) {
                                if (sub.length() > 0) sub.append(" · ");
                                sub.append(L.t("已看 ")).append(Math.round(e.progress)).append("%");
                            }
                            rows.add(new String[]{title, sub.toString()});
                            if (e.id != null && e.id.equals(it.id)) sel = i;
                        }
                        showDetailChooser(L.t("选集 · ") + cleanName(it.series == null ? it.name : it.series),
                                rows, sel, false, list);
                    }
                });
            }
        }).start();
    }

    /** 选择音轨 / 字幕 */
    private void showTracks(final Api.Item it, final boolean subtitle) {
        final android.app.ProgressDialog pd = new android.app.ProgressDialog(this);
        pd.setMessage(subtitle ? L.t("读取字幕轨…") : L.t("读取音轨…"));
        pd.show();
        new Thread(new Runnable() {
            @Override public void run() {
                java.util.List<String[]> rows = new java.util.ArrayList<String[]>();
                if (subtitle) {
                    rows.add(new String[]{L.t("自动字幕"), L.t("根据播放器当前自动选择")});
                    try {
                        java.util.List<Api.SubStream> subs = Api.subtitleStreams(session, it.id);
                        for (int i = 0; i < subs.size(); i++) {
                            Api.SubStream s0 = subs.get(i);
                            String sub = (s0.external ? L.t("外挂") : L.t("内嵌"))
                                    + (s0.codec == null || s0.codec.isEmpty() ? "" : " · " + s0.codec.toUpperCase());
                            rows.add(new String[]{s0.title, sub});
                        }
                    } catch (Exception ignored) { }
                } else {
                    rows.add(new String[]{L.t("默认音轨"), L.t("根据播放器当前自动选择")});
                    try {
                        java.util.List<String[]> t = Api.audioTracks(session, it.id);
                        for (int i = 0; i < t.size(); i++) rows.add(t.get(i));
                    } catch (Exception ignored) { }
                }
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        pd.dismiss();
                        if (detailOverlay == null) return;
                        showDetailChooser(subtitle ? L.t("选择字幕") : L.t("选择音轨"), rows, 0, true);
                    }
                });
            }
        }).start();
    }

    /** 详情页底部一个方形图标按钮 */
    private DetailTile detailSquare(LinearLayout row, String glyph) {
        DetailTile t = new DetailTile(this, glyph);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(U.dp(this, 74), U.dp(this, 74));
        lp.rightMargin = U.dp(this, 14);
        row.addView(t, lp);
        return t;
    }

    private String detailMeta1(Api.Item it) {
        StringBuilder b = new StringBuilder();
        b.append(L.t("时长 ")).append(it.duration > 0 ? U.time(it.duration) : "—").append("   ");
        b.append(L.t("分辨率 ")).append(it.width > 0 && it.height > 0 ? (it.width + "×" + it.height) : "—");
        if (it.container != null && !it.container.isEmpty()) {
            b.append("   ").append(it.container.toUpperCase());
        }
        return b.toString();
    }

    private String detailMeta2(Api.Item it) {
        StringBuilder b = new StringBuilder();
        b.append(L.t("编码 ")).append(it.videoCodec.isEmpty() ? "—" : it.videoCodec).append("   ");
        b.append(L.t("码率 ")).append(it.bitrate > 0 ? ((it.bitrate / 1000) + " Kbps") : "—").append("   ");
        b.append(L.t("大小 ")).append(it.size > 0 ? fmtSize(it.size) : "—");
        return b.toString();
    }

    /** 视频轨 / 音频轨 / 字幕轨细节 */
    private String detailMeta4(Api.Item it) {
        StringBuilder b = new StringBuilder();
        if (it.videoTitle != null && !it.videoTitle.isEmpty()) b.append(L.t("视频  ")).append(it.videoTitle);
        if (it.audioTitle != null && !it.audioTitle.isEmpty()) {
            if (b.length() > 0) b.append("      ");
            b.append(L.t("音频  ")).append(it.audioTitle);
        }
        if (it.audioTracks > 0 || it.subTracks > 0) {
            if (b.length() > 0) b.append("      ");
            b.append(L.t("音轨 ")).append(Math.max(1, it.audioTracks))
             .append(L.t(" · 字幕 ")).append(it.subTracks);
        }
        return b.toString();
    }

    private String detailMeta3(Api.Item it) {
        String p = it.path;
        if (p == null || p.isEmpty()) return L.t("文件地址 —");
        return L.t("文件地址 ") + p;
    }

    private void toggleFavInDetail(final Api.Item it, final DetailTile t) {
        final boolean want = !it.favorite;
        new Thread(new Runnable() {
            @Override public void run() {
                final boolean ok = Api.favorite(session, it.id, want);
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (!ok) {
                            android.widget.Toast.makeText(MainActivity.this, L.t("收藏失败，检查账号权限"),
                                    android.widget.Toast.LENGTH_SHORT).show();
                            return;
                        }
                        it.favorite = want;
                        t.setFilled(want);
                        android.widget.Toast.makeText(MainActivity.this,
                                want ? L.t("已加入收藏") : L.t("已取消收藏"), android.widget.Toast.LENGTH_SHORT).show();
                    }
                });
            }
        }).start();
    }

    /** 直接删除（不弹确认框）：设置里可整体关掉详情页的删除按钮 */
    private void confirmDelete(final Api.Item it) {
        new Thread(new Runnable() {
            @Override public void run() {
                final String err = Api.deleteItem(session, it.id);
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (err == null) {
                            android.widget.Toast.makeText(MainActivity.this, L.t("已删除"),
                                    android.widget.Toast.LENGTH_SHORT).show();
                            removeFromGrid(it);
                            closeDetail();
                        } else {
                            android.widget.Toast.makeText(MainActivity.this, L.t("删除失败：") + err,
                                    android.widget.Toast.LENGTH_LONG).show();
                        }
                    }
                });
            }
        }).start();
    }

    private boolean inQuickLogin = false;
    /** 快捷登录页的密码框与确定按钮，从桌面切回来时用来恢复焦点 */
    private TextView quickPass, quickGo;
    /** 真的切出过 App（不是进播放器）：回来时要重新验证密码，避免任务残留直接进 */
    private boolean wentBackground = false;
    /** 手动登录页（服务器/用户名/密码），返回键要能回到服务器选择页 */
    private boolean inLogin = false;

    private void closeDetail() {
        if (detailOverlay == null) return;
        if (detailChooser != null) {
            root.removeView(detailChooser);
            detailChooser = null;
        }
        root.removeView(detailOverlay);
        detailOverlay = null;
        detailPage1 = null;
        detailInfoBlock = null;
        detailScroll = null;
        blockContentFocus(false);
        if (detailFromBanner) {
            detailFromBanner = false;
            if (bannerBtn != null && bannerBtn.getParent() != null) {
                bannerBtn.requestFocus();
                return;
            }
        }
        View dc = detailCard;
        detailCard = null;
        if (dc != null && dc.getParent() != null) {
            dc.requestFocus();
            return;
        }
        if (gridRef != null && gridRef.getChildCount() > 0) {
            gridRef.getChildAt(0).requestFocus();
        } else if (searchGrid != null && searchGrid.getChildCount() > 0) {
            searchGrid.getChildAt(0).requestFocus();
        }
    }

    /** 删除后把卡片从网格里摘掉，并更新缓存与计数 */
    private void removeFromGrid(Api.Item it) {
        if (gridRef != null) {
            for (int i = 0; i < gridRef.getChildCount(); i++) {
                View v = gridRef.getChildAt(i);
                if (v instanceof Card && it.id.equals(((Card) v).key)) {
                    gridRef.removeViewAt(i);
                    break;
                }
            }
        }
        if (itemsAll != null) itemsAll.remove(it);
        if (itemsShown > 0) itemsShown--;
        if (itemCache != null && libId != null) itemCache.remove(folderId != null ? folderId : libId);
        updateCount();
    }

    // ================= 单个媒体库的缩略图设置 =================

    private FrameLayout libPanel;

    /** 打开「本库尺寸」：只影响当前这个媒体库，其它库各管各的 */
    private void openLibThumbPanel() {
        if (libPanel != null || libId == null) return;
        FrameLayout overlay = new FrameLayout(this);
        View dim = new View(this);
        dim.setBackgroundColor(0x33000000);
        dim.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { closeLibPanel(); }
        });
        overlay.addView(dim, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout panel = U.col(this);
        panel.setBackground((U.EMBY ? U.cyberSheet(this) : U.sheetBg(this)));
        panel.setPadding(U.dp(this, 18), U.dp(this, 16), U.dp(this, 18), U.dp(this, 14));
        if (android.os.Build.VERSION.SDK_INT >= 21) panel.setElevation(U.dp(this, 14));

        panel.addView(U.tv(this, L.t("本库缩略图 · ") + (libName == null ? "" : libName), 16, U.TXT, true), U.lp(-1, -2));

        LinearLayout ratioRow = U.row(this);
        libChips(ratioRow, L.t("比例"), new String[]{L.t("自动"), "16:9", "9:16"}, 0);
        LinearLayout.LayoutParams rl = U.lp(-1, -2);
        rl.topMargin = U.dp(this, 16);
        panel.addView(ratioRow, rl);

        LinearLayout sizeRow = U.row(this);
        libChips(sizeRow, L.t("大小"), new String[]{L.t("小"), L.t("标准"), L.t("大")}, 1);

        LinearLayout.LayoutParams sl = U.lp(-1, -2);
        sl.topMargin = U.dp(this, 10);
        panel.addView(sizeRow, sl);

        IconBtn reset = new IconBtn(this, IconBtn.CLOSE, L.t("跟随全局设置"));
        reset.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                store.libThumbMode(libId, -1);
                store.libThumbSize(libId, -1);
                applyLibThumb();
                closeLibPanel();
            }
        });
        LinearLayout.LayoutParams rsl = U.lp(-1, -2);
        rsl.topMargin = U.dp(this, 16);
        panel.addView(reset, rsl);

        if (libSizeBtn == null) { ratioRow.getChildAt(1).requestFocus(); return; }
        int[] loc = new int[2];
        libSizeBtn.getLocationOnScreen(loc);
        FrameLayout.LayoutParams pp = new FrameLayout.LayoutParams(U.dp(this, 380), -2);
        pp.gravity = Gravity.TOP | Gravity.LEFT;
        pp.topMargin = loc[1] + libSizeBtn.getHeight() + U.dp(this, 8);
        pp.leftMargin = Math.max(U.dp(this, 16), Math.min(
                loc[0] + libSizeBtn.getWidth() / 2 - U.dp(this, 190),
                getResources().getDisplayMetrics().widthPixels - U.dp(this, 396)));
        overlay.addView(panel, pp);
        root.addView(overlay, new FrameLayout.LayoutParams(-1, -1));
        libPanel = overlay;
        blockContentFocus(true);
        ratioRow.getChildAt(1).requestFocus();
    }

    private void closeLibPanel() {
        if (libPanel == null) return;
        root.removeView(libPanel);
        libPanel = null;
        blockContentFocus(false);
        if (libSizeBtn != null) libSizeBtn.requestFocus();
        else if (gridRef != null && gridRef.getChildCount() > 0) gridRef.getChildAt(0).requestFocus();
    }

    /** 胶囊：0=本库比例 1=本库大小 */
    private boolean libSelected(int kind, int idx) {
        String sc = thumbScope();
        int v;
        if (kind == 0) v = store.modeFor(sc);
        else if (kind == 2) v = store.posterFor(sc) ? 1 : 0;
        else v = store.sizeFor(sc);
        return v == idx;
    }

    private void libChips(LinearLayout row, String label, String[] opts, final int kind) {
        TextView lb = U.tv(this, label, 13, U.DIM, false);
        row.addView(lb, U.lpm(-2, -2, 0, 0, U.dp(this, 4), 0));
        final java.util.ArrayList<TextView> list = new java.util.ArrayList<TextView>();
        for (int i = 0; i < opts.length; i++) {
            final int idx = i;
            final TextView c = new TextView(this);
            U.face(c);
            boolean sel = libSelected(kind, i);
            c.setText(opts[i]);
            c.setTextSize(13);
            c.setTextColor(U.txt(sel ? 0xFFFFFFFF : U.DIM));
            c.setGravity(Gravity.CENTER);
            c.setPadding(U.dp(this, 16), U.dp(this, 9), U.dp(this, 16), U.dp(this, 9));
            c.setBackground(chipBg(sel));
            c.setFocusable(true);
            c.setFocusableInTouchMode(true);
            // 焦点框必须和外面按钮一模一样：用 U.ringFg（前景绘制、玻璃遮罩样式）。
            // 不能交给外层 RingRow 画 —— 容器 dispatchDraw 在子控件之前执行，框会被胶囊自己的圆角底色盖掉
            U.ringFg(c, U.dp(this, 6));
            list.add(c);
            c.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    String sc = thumbScope();
                    if (kind == 0) store.libThumbMode(sc, idx);
                    else if (kind == 2) store.setPosterFor(sc, idx == 1);
                    else store.libThumbSize(sc, idx);
                    for (int k = 0; k < list.size(); k++) {
                        boolean s2 = libSelected(kind, k);
                        list.get(k).setBackground(chipBg(s2));
                        list.get(k).setTextColor(U.txt(s2 ? 0xFFFFFFFF : U.DIM));
                    }
                    applyLibThumb();
                }
            });
            c.setOnFocusChangeListener(new View.OnFocusChangeListener() {
                @Override public void onFocusChange(View v, boolean has) {
                    // 焦点框统一走「焦点框样式」
                    v.setBackground(chipBg(libSelected(kind, idx)));
                }
            });
            row.addView(c, U.lpm(-2, -2, U.dp(this, 8), 0, 0, 0));
        }
    }

    /** 作用域：每个媒体库的「全部视频」和「文件夹」各自记一套比例/大小/形态 */
    private String scopeAll() { return libId == null ? null : libId + "#a"; }
    private String scopeFold() { return libId == null ? null : libId + "#f"; }
    private String thumbScope() { return folderMode ? scopeFold() : scopeAll(); }

    /** 网格统一走自适应排布：每张卡的形态由 addCards 按「有没有刮削」决定 */
    private int effMode(String lib) { return 0; }

    private void applyLibThumb() {
        if (gridRef == null || libId == null) return;
        gridRef.configure(store.heightDpFor(thumbScope()), 0);
        gridRef.setRatio(store.modeFor(thumbScope()));
        loadVisibleNow();
    }

    // ================= 搜索 =================

    private FrameLayout searchOverlay;
    private String searchRestoreQuery = "";
    private String lastSearchQuery = "";
    /** 从搜索结果进播放后，回到主界面要自动重开搜索页 */
    private boolean resumeSearchOnReturn = false;
    private FlowGrid searchGrid;
    private TextView searchStatus;
    private android.widget.EditText searchEdit;
    private android.os.Handler searchH = new android.os.Handler(android.os.Looper.getMainLooper());
    private Runnable searchPending;

    private void openSearch() {
        if (searchOverlay != null || session == null) return;

        FrameLayout ov = new FrameLayout(this);
        ov.setBackgroundColor(0xE00C0F14);

        LinearLayout col = U.col(this);
        col.setPadding(U.dp(this, 40), U.dp(this, 24), U.dp(this, 40), U.dp(this, 18));

        LinearLayout head = U.row(this);
        head.setGravity(Gravity.CENTER_VERTICAL);

        final android.widget.EditText ed = new android.widget.EditText(this);
        searchEdit = ed;
        ed.setHint(L.t("输入片名搜索"));
        ed.setTextSize(17);
        ed.setTextColor(U.txt(0xFFFFFFFF));
        ed.setHintTextColor(0xFF8A8A8A);
        ed.setSingleLine(true);
        ed.setBackground(U.roundStroke(0xE6222830, U.dp(this, 12), U.dp(this, 1), 0x80485059));
        ed.setPadding(U.dp(this, 18), U.dp(this, 10), U.dp(this, 18), U.dp(this, 10));
        ed.setFocusable(true);
        ed.setFocusableInTouchMode(true);
        ed.setCursorVisible(true);
        ed.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override public void onFocusChange(View v, boolean has) {
                ed.setBackground(U.roundStroke(has ? 0xE62A333D : 0xE6222830,
                        U.dp(MainActivity.this, 12), U.dp(MainActivity.this, has ? 2 : 1),
                        has ? 0x66FFFFFF : 0x80485059));
            }
        });
        head.addView(ed, new LinearLayout.LayoutParams(0, -2, 1f));

        final TextView go = U.tv(this, L.t("搜索"), 16, U.ACC, true);
        go.setGravity(Gravity.CENTER);
        go.setBackground(U.roundStroke(0xE6222830, U.dp(this, 12),
                U.dp(this, 1), 0x6652B54B));
        go.setPadding(U.dp(this, 22), U.dp(this, 11), U.dp(this, 22), U.dp(this, 11));
        go.setMinWidth(U.dp(this, 84));
        go.setFocusable(true);
        go.setFocusableInTouchMode(true);
        go.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override public void onFocusChange(View v, boolean has) {
                go.setBackground(U.roundStroke(has ? 0xE62A333D : 0xE6222830,
                        U.dp(MainActivity.this, 12), U.dp(MainActivity.this, has ? 2 : 1),
                        has ? 0x66FFFFFF : 0x6652B54B));
                go.setTextColor(U.txt(has ? 0xFFFFFFFF : U.ACC));
            }
        });
        LinearLayout.LayoutParams gl = U.lp(-2, -2);
        gl.leftMargin = U.dp(this, 12);
        head.addView(go, gl);
        col.addView(head, U.lp(-1, -2));

        searchStatus = U.tv(this, "", 13, 0xFF9E9E9E, false);
        LinearLayout.LayoutParams stl = U.lp(-1, -2);
        stl.topMargin = U.dp(this, 12);
        col.addView(searchStatus, stl);
        searchStatus.setVisibility(View.GONE);   // 不开搜索前那行提示文字，直接铺推荐

        ScrollView sv = new SmoothScrollView(this);
        sv.setClipChildren(false);
        sv.setClipToPadding(false);
        sv.setVerticalScrollBarEnabled(false);
        searchGrid = new FlowGrid(this);
        searchGrid.configure(store.searchHeightDp(), 0);
        searchGrid.setRatio(store.searchThumbMode());
        LinearLayout.LayoutParams gpl = U.lp(-1, -2);
        gpl.topMargin = U.dp(this, 14);
        sv.addView(searchGrid, new ScrollView.LayoutParams(-1, -2));
        LinearLayout.LayoutParams svl = new LinearLayout.LayoutParams(-1, 0, 1f);
        svl.topMargin = U.dp(this, 8);
        col.addView(sv, svl);

        ov.addView(col, new FrameLayout.LayoutParams(-1, -1));
        root.addView(ov, new FrameLayout.LayoutParams(-1, -1));
        searchOverlay = ov;

        ed.setOnKeyListener(new View.OnKeyListener() {
            @Override public boolean onKey(View v, int keyCode, android.view.KeyEvent e) {
                if (e.getAction() != android.view.KeyEvent.ACTION_DOWN) return false;
                if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_DOWN) {
                    if (searchGrid != null && searchGrid.getChildCount() > 0) {
                        searchGrid.getChildAt(0).requestFocus();
                        return true;
                    }
                }
                if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_RIGHT) {
                    go.requestFocus();
                    return true;
                }
                return false;
            }
        });
        go.setOnKeyListener(new View.OnKeyListener() {
            @Override public boolean onKey(View v, int keyCode, android.view.KeyEvent e) {
                if (e.getAction() != android.view.KeyEvent.ACTION_DOWN) return false;
                if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_LEFT) {
                    ed.requestFocus();
                    return true;
                }
                if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_DOWN) {
                    if (searchGrid != null && searchGrid.getChildCount() > 0) {
                        searchGrid.getChildAt(0).requestFocus();
                        return true;
                    }
                }
                return false;
            }
        });
        ed.requestFocus();

        final Runnable doSearch = new Runnable() {
            @Override public void run() {
                final String q = ed.getText().toString().trim();
                lastSearchQuery = q;
                if (q.isEmpty()) {
                    searchGrid.removeAllViews();
                    searchStatus.setVisibility(View.GONE);
                    loadSearchRecommend();   // 清空关键词就当没搜过：回到随机推荐
                    return;
                }
                searchStatus.setVisibility(View.VISIBLE);
                searchStatus.setText(L.t("正在搜索「") + q + "」…");
                new Thread(new Runnable() {
                    @Override public void run() {
                        List<Api.Item> res = null;
                        String err = null;
                        try { res = Api.search(session, q, 300); }
                        catch (Exception e) { err = e.getMessage(); }
                        final List<Api.Item> fr = res;
                        final String fe = err;
                        runOnUiThread(new Runnable() {
                            @Override public void run() {
                                if (searchGrid == null) return;
                                searchGrid.removeAllViews();
                                if (fe != null) {
                                    searchStatus.setText(L.t("搜索失败：") + fe);
                                    return;
                                }
                                if (fr == null || fr.isEmpty()) {
                                    searchStatus.setText(L.t("没有找到「") + q + "」");
                                    return;
                                }
                                searchStatus.setText(L.t("找到 ") + fr.size() + L.t(" 条结果"));
                                for (int i = 0; i < fr.size(); i++) {
                                    final Api.Item it = fr.get(i);
                                    // 有刮削海报 → 统一 16:9 铺满；没有海报 → 视频帧缩略图
                                    boolean hasPoster = it.imgTag != null && !it.imgTag.isEmpty();
                                    int sPR = store.searchThumbMode();    // 0 自动 / 1 16:9 / 2 9:16
                                    boolean usePoster = posterIsPoster(it);
                                    if (isPhotoType(it.type)) usePoster = true;
                                    // 有刮削：统一 9:16 竖海报；没有刮削：视频缩略图，按「视图比例」设置
                                    float asp = 16f / 9f;
                                    if (isPhotoType(it.type)) {
                                        asp = (it.width > 0 && it.height > 0)
                                                ? (float) it.width / (float) it.height : 16f / 9f;
                                        if (asp < 0.55f) asp = 0.55f;
                                        if (asp > 1.9f) asp = 1.9f;
                                    } else if (usePoster) {
                                        asp = 9f / 16f;
                                    } else if (sPR == 2) {
                                        asp = 9f / 16f;
                                    } else if (sPR == 1) {
                                        asp = 16f / 9f;
                                    } else if (it.width > 0 && it.height > 0) {
                                        asp = (float) it.width / (float) it.height;
                                    }
                                    Card card = new Card(MainActivity.this, asp);
                                    card.posterCard = usePoster && !isPhotoType(it.type);
                                    card.frameAspect = it.posterAspect > 0 ? (float) it.posterAspect
                    : ((it.width > 0 && it.height > 0) ? (float) it.width / (float) it.height : 0f);
                                    card.videoAspect = (it.width > 0 && it.height > 0)
                                            ? (float) it.width / (float) it.height : 0f;
                                    card.key = it.id;
                                    String sub = subLabel(it);
                                    card.lazy = false;
                                    if (it.rating > 0) card.setRating("★ " + String.format("%.1f", it.rating));
                                    String imgUrl;
                                    if (usePoster) imgUrl = Api.imageUrl(session, it.id, it.imgTag, 400);
                                    else if (it.thumbTag != null && !it.thumbTag.isEmpty())
                                        imgUrl = Api.thumbUrl(session, it.id, it.thumbTag, 720);
                                    else imgUrl = Api.imageUrl(session, it.id, it.imgTag, 400);
                                    card.setData(cleanName(it.name), sub, imgUrl);
                                    LinearLayout.LayoutParams lp = U.lpm(-2, -2, 0, 0,
                                            U.dp(MainActivity.this, 14), U.dp(MainActivity.this, 14));
                                    card.setLayoutParams(lp);
                                    card.aspect = asp;
                                    attachCenterOnFocus(card);
                                    final android.widget.EditText fe2 = ed;
                                    card.setOnKeyListener(new View.OnKeyListener() {
                                        @Override public boolean onKey(View v, int keyCode,
                                                                    android.view.KeyEvent e) {
                                            if (e.getAction() == android.view.KeyEvent.ACTION_DOWN
                                                    && keyCode == android.view.KeyEvent.KEYCODE_DPAD_UP) {
                                                fe2.requestFocus();
                                                return true;
                                            }
                                            return false;
                                        }
                                    });
                                    card.setOnClickListener(new View.OnClickListener() {
                                        @Override public void onClick(View v) {
                                            if (isPhotoType(it.type)) openPhoto(it);
                                            else {
                                                getSharedPreferences("lmby", 0).edit()
                                                        .putString("sq", lastSearchQuery)
                                                        .putBoolean("sr", true).apply();
                                                if (store.detailPage()) openDetail(it, (Card) v);
                                                else play(it);
                                            }
                                        }
                                    });
                                    searchGrid.addView(card);
                                }
                                searchGrid.requestLayout();
                            }
                        });
                    }
                }).start();
            }
        };

        View.OnClickListener fire = new View.OnClickListener() {
            @Override public void onClick(View v) { doSearch.run(); }
        };
        go.setOnClickListener(fire);

        // 搜索框里按下方向键：直接进到搜索结果第一张，不再掉到下层媒体库
        ed.setOnKeyListener(new View.OnKeyListener() {
            @Override public boolean onKey(View v, int keyCode, android.view.KeyEvent e) {
                if (e.getAction() == android.view.KeyEvent.ACTION_DOWN
                        && keyCode == android.view.KeyEvent.KEYCODE_DPAD_DOWN) {
                    if (searchGrid != null && searchGrid.getChildCount() > 0) {
                        searchGrid.getChildAt(0).requestFocus();
                        return true;
                    }
                }
                return false;
            }
        });

        ed.setOnEditorActionListener(new android.widget.TextView.OnEditorActionListener() {
            @Override public boolean onEditorAction(android.widget.TextView v, int actionId,
                                                    android.view.KeyEvent e) {
                doSearch.run();
                return true;
            }
        });

        blockContentFocus(true);

        if (!searchRestoreQuery.isEmpty()) {
            ed.setText(searchRestoreQuery);
            ed.setSelection(ed.getText().length());
            searchRestoreQuery = "";
            final Runnable again = doSearch;
            root.postDelayed(new Runnable() {
                @Override public void run() { again.run(); }
            }, 300);
        } else {
            // 没带恢复关键词：进搜索页先铺一屏随机推荐，别空着
            root.postDelayed(new Runnable() {
                @Override public void run() { loadSearchRecommend(); }
            }, 260);
        }
    }

    /** 搜索页默认内容：跨库随机推荐一批，点开就能直接看 */
    private void loadSearchRecommend() {
        if (session == null) return;
        final FlowGrid g = searchGrid;
        if (g == null) return;
        new Thread(new Runnable() {
            @Override public void run() {
                final List<Api.Item> rec;
                try { rec = Api.randomPick(session, 60); } catch (Throwable e) { return; }
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (searchGrid == null || searchGrid != g) return;
                        if (g.getChildCount() > 0) return;   // 已经有搜索结果了，别覆盖
                        fillSearchGrid(rec);
                        g.requestLayout();
                    }
                });
            }
        }).start();
    }

    /** 搜索页/推荐共用的卡片填充 */
    private void fillSearchGrid(List<Api.Item> fr) {
        if (searchGrid == null || fr == null) return;
        for (int i = 0; i < fr.size(); i++) {
            final Api.Item it = fr.get(i);
            int sPR = store.searchThumbMode();    // 0 自动 / 1 16:9 / 2 9:16
            boolean usePoster = posterIsPoster(it);
            if (isPhotoType(it.type)) usePoster = true;
            float asp = 16f / 9f;
            if (isPhotoType(it.type)) {
                asp = (it.width > 0 && it.height > 0)
                        ? (float) it.width / (float) it.height : 16f / 9f;
                if (asp < 0.55f) asp = 0.55f;
                if (asp > 1.9f) asp = 1.9f;
            } else if (usePoster) {
                asp = 9f / 16f;
            } else if (sPR == 2) {
                asp = 9f / 16f;
            } else if (sPR == 1) {
                asp = 16f / 9f;
            } else if (it.width > 0 && it.height > 0) {
                asp = (float) it.width / (float) it.height;
            }
            Card card = new Card(MainActivity.this, asp);
            card.posterCard = usePoster && !isPhotoType(it.type);
            card.frameAspect = it.posterAspect > 0 ? (float) it.posterAspect
                    : ((it.width > 0 && it.height > 0) ? (float) it.width / (float) it.height : 0f);
            card.videoAspect = (it.width > 0 && it.height > 0)
                    ? (float) it.width / (float) it.height : 0f;
            card.key = it.id;
            boolean isSeriesCard = isFolderType(it.type) && !isPhotoType(it.type);
            String sub = subLabel(it);
            card.lazy = false;
            // 剧集：整个剧集一条，右上角角标显示集数
            if (isSeriesCard) card.setBadge(it.childCount > 0 ? it.childCount + L.t("集") : L.t("剧集"));
            if (it.rating > 0) card.setRating("★ " + String.format("%.1f", it.rating));
            String imgUrl;
            if (usePoster) imgUrl = Api.imageUrl(session, it.id, it.imgTag, 400);
            else if (it.thumbTag != null && !it.thumbTag.isEmpty())
                imgUrl = Api.thumbUrl(session, it.id, it.thumbTag, 720);
            else imgUrl = Api.imageUrl(session, it.id, it.imgTag, 400);
            card.setData(cleanName(it.name), sub, imgUrl);
            LinearLayout.LayoutParams lp = U.lpm(-2, -2, 0, 0,
                    U.dp(MainActivity.this, 14), U.dp(MainActivity.this, 14));
            card.setLayoutParams(lp);
            card.aspect = asp;
            attachCenterOnFocus(card);
            card.setOnKeyListener(new View.OnKeyListener() {
                @Override public boolean onKey(View v, int keyCode, android.view.KeyEvent e) {
                    if (e.getAction() == android.view.KeyEvent.ACTION_DOWN
                            && keyCode == android.view.KeyEvent.KEYCODE_DPAD_UP) {
                        if (searchEdit != null) searchEdit.requestFocus();
                        return true;
                    }
                    return false;
                }
            });
            card.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    if (isPhotoType(it.type)) openPhoto(it);
                    else if (isSeriesCard) {
                        // 剧集条目直接进剧集详情（季/集列表），不拿单集来播
                        openDetail(it, (Card) v);
                    } else {
                        getSharedPreferences("lmby", 0).edit()
                                .putString("sq", lastSearchQuery)
                                .putBoolean("sr", true).apply();
                        if (store.detailPage()) openDetail(it, (Card) v);
                        else play(it);
                    }
                }
            });
            searchGrid.addView(card);
        }
    }

    /** 卡片副标题：图片 / 剧集 / 电影 / 视频时长 */
    private String subLabel(Api.Item it) {
        if (isPhotoType(it.type)) return L.t("图片");
        String pre = it.year > 0 ? it.year + " · " : "";
        if (isFolderType(it.type)) return pre + L.t("剧集");
        if ("Movie".equals(it.type)) return pre + L.t("电影");
        return it.year > 0 ? it.year + " · " + U.time(it.duration) : U.time(it.duration);
    }

    private void closeSearch() {
        blockContentFocus(false);
        if (searchPending != null) searchH.removeCallbacks(searchPending);
        searchPending = null;
        if (searchOverlay == null) return;
        root.removeView(searchOverlay);
        searchOverlay = null;
        searchGrid = null;
        searchStatus = null;
        if (root.getChildCount() > 0) {
            View v = root.getChildAt(0);
            if (v != null) v.requestFocus();
        }
    }

    // ================= 单个媒体库的排序 =================

    private FrameLayout sortPanel;

    private void openLibSortPanel() {
        if (sortPanel != null || libId == null) return;
        FrameLayout overlay = new FrameLayout(this);
        View dim = new View(this);
        dim.setBackgroundColor(0x33000000);
        dim.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { closeSortPanel(); }
        });
        overlay.addView(dim, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout panel = U.col(this);
        panel.setBackground((U.EMBY ? U.cyberSheet(this) : U.sheetBg(this)));
        panel.setPadding(U.dp(this, 18), U.dp(this, 16), U.dp(this, 18), U.dp(this, 14));
        if (android.os.Build.VERSION.SDK_INT >= 21) panel.setElevation(U.dp(this, 14));

        panel.addView(U.tv(this, L.t("排序 · ") + (libName == null ? "" : libName), 16, U.TXT, true), U.lp(-1, -2));

        int cur = store.libSort(libId);
        // 向下展开的长条：一列到底，按钮正下方弹出（不再居中挡屏幕）
        LinearLayout list = U.col(this);
        for (int idx = 0; idx < SORT_NAMES.length; idx++) {
            final int fi = idx;
            boolean sel = cur == idx;
            boolean asc = store.libSortAsc(libId);
            TextView chip = new TextView(this);
            U.face(chip);
            chip.setText(SORT_NAMES[idx] + (sel ? (asc ? "  ↑" : "  ↓") : ""));
            chip.setTextSize(15);
            chip.setTextColor(U.txt(sel ? 0xFFFFFFFF : 0xFFB8BFC8));
            chip.setGravity(Gravity.CENTER_VERTICAL);
            chip.setPadding(U.dp(this, 18), 0, U.dp(this, 18), 0);
            chip.setBackground(chipBg(sel));
            chip.setFocusable(true);
            chip.setFocusableInTouchMode(true);
            U.ringFg(chip, U.dp(this, 14));
            chip.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    if (store.libSort(libId) == fi) {
                        store.libSortAsc(libId, !store.libSortAsc(libId));
                    } else {
                        store.libSort(libId, fi);
                        store.libSortAsc(libId, defaultAsc(fi));
                    }
                    saveSortPref(libId, fi, store.libSortAsc(libId));
                    itemCache.clear();
                    closeSortPanel();
                    if (folderMode || folderId != null) loadFolders(); else loadItems();
                }
            });
            chip.setOnFocusChangeListener(new View.OnFocusChangeListener() {
                @Override public void onFocusChange(View v, boolean has) {
                    v.setBackground(chipBg(store.libSort(libId) == fi));
                }
            });
            LinearLayout.LayoutParams cl = U.lp(-1, U.dp(this, 46));
            cl.topMargin = idx == 0 ? U.dp(this, 8) : U.dp(this, 4);
            list.addView(chip, cl);
            if (sel) firstChip = chip;
        }

        android.widget.ScrollView sc = new android.widget.ScrollView(this);
        sc.setVerticalScrollBarEnabled(false);
        sc.addView(list, new android.widget.ScrollView.LayoutParams(-1, -2));
        LinearLayout.LayoutParams scl = U.lp(-1, -2);
        scl.topMargin = U.dp(this, 10);
        int maxH = Math.min(
                (int) (getResources().getDisplayMetrics().heightPixels * 0.62f),
                U.dp(this, 44 * SORT_NAMES.length + 4 * (SORT_NAMES.length - 1)));
        scl.height = maxH;
        panel.addView(sc, scl);

        int[] loc = new int[2];
        libSortBtn.getLocationOnScreen(loc);
        FrameLayout.LayoutParams pp = new FrameLayout.LayoutParams(U.dp(this, 260), -2);
        pp.gravity = Gravity.TOP | Gravity.LEFT;
        pp.topMargin = loc[1] + libSortBtn.getHeight() + U.dp(this, 8);
        pp.leftMargin = Math.max(U.dp(this, 16), Math.min(
                loc[0] + libSortBtn.getWidth() / 2 - U.dp(this, 130),
                getResources().getDisplayMetrics().widthPixels - U.dp(this, 276)));
        overlay.addView(panel, pp);
        root.addView(overlay, new FrameLayout.LayoutParams(-1, -1));
        sortPanel = overlay;
        blockContentFocus(true);
        if (firstChip != null) firstChip.requestFocus();
    }

    private TextView firstChip;

    private void closeSortPanel() {
        if (sortPanel == null) return;
        root.removeView(sortPanel);
        sortPanel = null;
        blockContentFocus(false);
        if (libSortBtn != null) libSortBtn.requestFocus();
    }

    // ================= 设置 =================

    /** 弹窗打开时禁止焦点跑到下层的媒体库网格里 */
    private void blockContentFocus(boolean blocked) {
        if (root == null) return;
        for (int i = 0; i < root.getChildCount(); i++) {
            View c = root.getChildAt(i);
            if (c == settingsOverlay || c == detailOverlay || c == libPanel || c == sortPanel
                    || c == searchOverlay || c == photoOverlay || c == accountOverlay
                    || c == detailChooser
                    || c == serverMenuOverlay || c == serverEditOverlay) continue;
            if (!(c instanceof ViewGroup)) continue;
            ((ViewGroup) c).setDescendantFocusability(blocked
                    ? ViewGroup.FOCUS_BLOCK_DESCENDANTS : ViewGroup.FOCUS_BEFORE_DESCENDANTS);
        }
    }

    private boolean focusFirstIn(View v) {
        if (v == settingsOverlay || v.getVisibility() != View.VISIBLE) return false;
        if (v.isFocusable()) { return v.requestFocus(); }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                if (focusFirstIn(g.getChildAt(i))) return true;
            }
        }
        return false;
    }

    private View lastFocus;

    private boolean thumbDirty = false;

    // ================= 请作者吃拼好饭 =================

    private android.app.Dialog donateDlg;

    void openDonate() {
        android.util.Log.e("LMBY", "openDonate called, dlg=" + donateDlg);
        if (donateDlg != null) return;
        FrameLayout ov = new FrameLayout(this);
        View ddim = new View(this);
        ddim.setBackgroundColor(0xE6000000);
        ddim.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { closeDonate(); }
        });
        ov.addView(ddim, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout box = U.col(this);
        box.setBackground(U.sheetBgSquare(this));
        box.setPadding(U.dp(this, 16), U.dp(this, 14), U.dp(this, 16), U.dp(this, 12));
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        box.setFocusable(true);
        box.setFocusableInTouchMode(true);
        box.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { closeDonate(); }
        });

        android.widget.ImageView iv = new android.widget.ImageView(this);
        try {
            java.io.InputStream in = getAssets().open("donate.jpg");
            android.graphics.Bitmap bm = android.graphics.BitmapFactory.decodeStream(in);
            in.close();
            if (bm != null) iv.setImageBitmap(bm);
        } catch (Throwable e) {
            android.util.Log.w("LMBY", "donate.jpg 读取失败: " + e);
        }
        iv.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
        box.addView(iv, new LinearLayout.LayoutParams(U.dp(this, 272), U.dp(this, 404)));

        TextView tip = U.tv(this, L.t("觉得软件好用的，可以请作者吃个拼好饭咩。"), 14, U.TXT, false);
        tip.setGravity(Gravity.CENTER);
        tip.setPadding(0, U.dp(this, 10), 0, 0);
        box.addView(tip, U.lp(-1, -2));

        FrameLayout.LayoutParams bl = new FrameLayout.LayoutParams(-2, -2);
        bl.gravity = Gravity.CENTER;
        ov.addView(box, bl);

        android.app.Dialog d = new android.app.Dialog(this, android.R.style.Theme_Translucent_NoTitleBar_Fullscreen);
        d.setContentView(ov);
        d.setOnDismissListener(new android.content.DialogInterface.OnDismissListener() {
            @Override public void onDismiss(android.content.DialogInterface di) {
                donateDlg = null;
                if (getCurrentFocus() == null && settingsOverlay != null) focusFirstIn(settingsOverlay);
            }
        });
        android.view.Window dw = d.getWindow();
        if (dw != null) {
            dw.setLayout(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT);
        }
        donateDlg = d;
        d.show();
        android.util.Log.e("LMBY", "donate shown=" + d.isShowing());
        box.requestFocus();
    }

    private void closeDonate() {
        if (donateDlg == null) return;
        android.app.Dialog d = donateDlg;
        donateDlg = null;
        d.dismiss();
    }

    private void closeSettings() {
        if (donateDlg != null) closeDonate();
        if (settingsOverlay != null) {
            root.removeView(settingsOverlay);
            settingsOverlay = null;
        }
        ringLayer = null;
        blockContentFocus(false);
        if (thumbDirty) {
            thumbDirty = false;
            itemCache.clear();
            if (libId != null) {
                if (folderMode || folderId != null) loadFolders(); else loadItems();
                return;
            }
            showHome();
            return;
        }
        if (homeDirty && libId == null) { homeDirty = false; showHome(); return; }
        View f = lastFocus;
        lastFocus = null;
        // 原来焦点在网格卡片上才还原到那张卡；否则回到网格第一张
        boolean inGrid = false;
        for (View w = f; w != null; ) {
            if (w == gridRef) { inGrid = true; break; }
            android.view.ViewParent par = w.getParent();
            w = (par instanceof View) ? (View) par : null;
        }
        if (inGrid && f != null && f.getVisibility() == View.VISIBLE && f.requestFocus()) return;
        if (gridRef != null && gridRef.getChildCount() > 0 && gridRef.getChildAt(0).requestFocus()) return;
        focusFirstIn(root);
    }

    /** 换肤：保存 → 应用调色板 → 关面板 → 用新配色重建当前页面 */


    private void switchTheme(int t) {
        store.uiTheme(t);
        U.applyTheme(t);
        U.applyRing(store.ring());   // 主题换了也要尊重手选的焦点框颜色
        // 背景没手选过就跟主题走：官方 Emby → 原生，玻璃风 → 暗金
        if (!store.bgManual()) {
            store.bgAuto(t == 1 || t == 2 ? 0 : 10);
            U.prepareBg(this, store.bg());
        }
        closeSettings();
        if (root != null) root.setBackground(U.bgDraw());
        if (libId != null) openLibrary(libId, libName, folderId, folderName, libType);
        else if (session != null) showHome();
        else if (store.lastUser().length() > 0) showQuickLogin();
        else showLogin(store.server(), store.user());
    }

    private FrameLayout accountOverlay;

    private void closeAccountMenu() {
        if (accountOverlay != null) { root.removeView(accountOverlay); accountOverlay = null; }
    }

    /** 右上角头像按钮弹出的账号菜单：列出该服务器检测到的其它用户 / 切服务器 / 注销 */
    void openAccountMenu() {
        if (photoOverlay != null) { closePhoto(); return; }
        if (searchOverlay != null) { closeSearch(); return; }
        if (accountOverlay != null) { closeAccountMenu(); return; }
        final FrameLayout overlay = new FrameLayout(this);
        View dim = new View(this);
        dim.setBackgroundColor(0x4D000000);
        dim.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { closeAccountMenu(); }
        });
        overlay.addView(dim, new FrameLayout.LayoutParams(-1, -1));

        final LinearLayout menu = U.col(this);
        menu.setPadding(0, U.dp(this, 6), 0, U.dp(this, 6));
        // 账号菜单跟设置页同一套方正底：4dp 小圆角，不再用大圆角弹层
        menu.setBackground(U.sheetBgSquare(this));

        final String srv = store.server();
        final String cur = session == null ? "" : session.userName;

        fillAccountMenu(menu, srv, cur, null);

        FrameLayout.LayoutParams ml = new FrameLayout.LayoutParams(U.dp(this, 320), -2);
        ml.gravity = Gravity.TOP | Gravity.RIGHT;
        ml.rightMargin = U.dp(this, 40);
        ml.topMargin = U.dp(this, 76);
        overlay.addView(menu, ml);

        root.addView(overlay, new FrameLayout.LayoutParams(-1, -1));
        accountOverlay = overlay;
        menu.getChildAt(0).requestFocus();

        new Thread(new Runnable() {
            @Override public void run() {
                final java.util.List<String[]> users = Api.publicUsers(srv);
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (accountOverlay != overlay) return;
                        menu.removeAllViews();
                        fillAccountMenu(menu, srv, cur, users);
                        menu.getChildAt(0).requestFocus();
                    }
                });
            }
        }).start();
    }

    /** 账号菜单内容：每个检测到的其它用户一行「切换到 X」→ 只输密码；再加切换服务器 / 注销 */
    private void fillAccountMenu(LinearLayout menu, final String srv, final String cur,
                                 java.util.List<String[]> users) {
        int others = 0;
        if (users != null) {
            for (String[] u : users) {
                if (u[0].equalsIgnoreCase(cur)) continue;
                final String target = u[0];
                TextView row = menuRow(L.t("切换到 ") + target, new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        closeAccountMenu();
                        showPasswordDialog(srv, target, target);
                    }
                });
                LinearLayout.LayoutParams lp = U.lp(-1, -2);
                if (others > 0) lp.topMargin = U.dp(this, 1);
                menu.addView(row, lp);
                others++;
            }
        }
        if (others == 0) {
            TextView row = menuRow(users == null ? L.t("检测账号中…") : L.t("切换账号 / 输密码"),
                    new View.OnClickListener() {
                        @Override public void onClick(View v) {
                            closeAccountMenu();
                            showLogin(srv, "");
                        }
                    });
            menu.addView(row, U.lp(-1, -2));
        }

        TextView r2 = menuRow(L.t("切换服务器"), new View.OnClickListener() {
            @Override public void onClick(View v) {
                closeAccountMenu();
                showSelectServer();
            }
        });
        LinearLayout.LayoutParams r2l = U.lp(-1, -2);
        r2l.topMargin = U.dp(this, 1);
        menu.addView(r2, r2l);

        TextView r3 = menuRow(L.t("注销"), new View.OnClickListener() {
            @Override public void onClick(View v) {
                closeAccountMenu();
                stopBannerLoop();
                bannerItems = null;
                bannerItem = null;
                session = null;
                showSelectServer();
            }
        });
        LinearLayout.LayoutParams r3l = U.lp(-1, -2);
        r3l.topMargin = U.dp(this, 8);
        menu.addView(r3, r3l);
    }

    /** 用已保存的 token 直接切到同一服务器上的另一个账号（不需要重输密码） */
    private void switchAccount(final String server, final String user) {
        final String[] acc = store.accountOf(server, user);
        if (acc == null) { showLogin(server, user); return; }
        busy = true;
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    Api.Session ss = new Api.Session();
                    ss.server = server;
                    ss.userId = acc[0];
                    ss.token = acc[1];
                    ss.userName = acc[2];
                    ss.deviceId = store.deviceId();
                    final List<Api.Lib> ls = Api.libraries(ss);
                    store.saveLogin(server, user);
                    store.saveLast(server, user, ss.userId, ss.userName);
                    saveAvatar(server, ss.userId, ss.token);
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            busy = false;
                            session = ss;
                            libs = ls;
                            showHome();
                        }
                    });
                } catch (Exception e) {
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            busy = false;
                            showLogin(server, user);
                        }
                    });
                }
            }
        }).start();
    }

    private TextView menuRow(String text, View.OnClickListener click) {
        TextView t = new TextView(this);
        U.face(t);
        t.setText(text);
        t.setTextSize(18);
        t.setTextColor(U.txt(U.TXT));
        t.setGravity(Gravity.CENTER_VERTICAL);
        t.setPadding(U.dp(this, 22), U.dp(this, 16), U.dp(this, 22), U.dp(this, 16));
        t.setFocusable(true);
        t.setFocusableInTouchMode(true);
        t.setClickable(true);
        t.setOnClickListener(click);
        t.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override public void onFocusChange(View v, boolean has) {
                v.setBackground(has ? U.roundStroke(U.PANEL, 0, U.dp(MainActivity.this, 2), 0x55FFFFFF)
                        : U.round(0x00232A33, 0));
                ((TextView) v).setTextColor(U.txt(U.TXT));
            }
        });
        return t;
    }

    void openSettings() {
        if (settingsOverlay != null) { closeSettings(); return; }
        settingsFirstChip = null;
        ringLayer = null;
        View cur = getCurrentFocus();
        if (cur != null && cur != settingsOverlay) lastFocus = cur;
        FrameLayout overlay = new FrameLayout(this);
        View dim = new View(this);
        dim.setBackgroundColor(0x99000000);
        dim.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { openSettings(); }
        });
        overlay.addView(dim, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout panel = U.col(this);
        panel.setBackground(U.sheetBgSquare(this));
        panel.setPadding(U.dp(this, 14), U.dp(this, 12), U.dp(this, 14), U.dp(this, 10));

        // 顶部只有标题，返回键关闭设置页
        panel.addView(U.tv(this, L.t("设置"), 20, U.TXT, true), U.lp(-2, -2));

        // 主体：一页长列表，按分类顺序往下排，分组标题分段
        android.widget.ScrollView svAll = new android.widget.ScrollView(this);
        svAll.setVerticalScrollBarEnabled(false);
        svAll.setFillViewport(true);
        svAll.setOnScrollChangeListener(new View.OnScrollChangeListener() {
            @Override public void onScrollChange(View v, int x, int y, int ox, int oy) {
                if (ringLayer != null) ringLayer.invalidate();
            }
        });
        LinearLayout allCol = U.col(this);
        svAll.addView(allCol, new FrameLayout.LayoutParams(-1, -2));
        LinearLayout.LayoutParams svLp = new LinearLayout.LayoutParams(-1, 0, 1f);
        svLp.topMargin = U.dp(this, 6);
        panel.addView(svAll, svLp);
        settingsAllCol = allCol;
        settingsScroll = svAll;        settingsFirstChip = null;

        // —— 分类一：外观与文字 ——
        // —— 外观 ——
        settingsGroup(allCol, L.t("外观"));
        LinearLayout secLook = settingsSection(allCol, null);
        chipBlock(secLook, L.t("主题"), new String[]{L.t("玻璃"), L.t("黑绿")}, 3, null);
        chipBlock(secLook, L.t("壁纸"), new String[]{L.t("原生"), L.t("暗金"), L.t("积云")}, 17, null);
        chipBlock(secLook, L.t("字体"), new String[]{L.t("粗黑"), L.t("毛笔"), L.t("卡通")}, 29, null);
        chipBlock(secLook, L.t("字色"), new String[]{L.t("默认"), L.t("黑色"), L.t("白色")}, 30, null);

        // —— 首页 ——
        settingsGroup(allCol, L.t("首页"));
        LinearLayout secHome = settingsSection(allCol, null);
        chipBlock(secHome, L.t("轮播海报"), new String[]{L.t("显示"), L.t("隐藏")}, 31, null);
        chipBlock(secHome, L.t("轮播选择"), new String[]{L.t("最新"), L.t("随机")}, 32, 1, null);
        chipBlock(secHome, L.t("媒体模块"), new String[]{L.t("开"), L.t("关")}, 13, null);
        chipBlock(secHome, L.t("最新模块"), new String[]{L.t("开"), L.t("关")}, 21, null);
        chipBlock(secHome, L.t("媒体库模块尺寸"), new String[]{L.t("小"), L.t("标准"), L.t("大")}, 22, null);
        chipBlock(secHome, L.t("最近观看尺寸"), new String[]{L.t("小"), L.t("标准"), L.t("大")}, 23, null);
        chipBlock(secHome, L.t("最新模块尺寸"), new String[]{L.t("小"), L.t("标准"), L.t("大")}, 24, null);

        // —— 列表 ——
        settingsGroup(allCol, L.t("列表"));
        LinearLayout secLib = settingsSection(allCol, null);
        settingsSub(secLib, L.t("媒体库内部"));
        chipBlock(secLib, L.t("视图比例"), new String[]{L.t("自动"), "16:9", "9:16"}, 0, null);
        chipBlock(secLib, L.t("视图尺寸"), new String[]{L.t("小"), L.t("标准"), L.t("大")}, 1, null);

        TextView followAll = new TextView(this);
        U.face(followAll);
        followAll.setText(L.t("全部媒体库重置为全局"));
        followAll.setTextSize(12);
        followAll.setTextColor(U.txt(U.TXT));
        followAll.setGravity(Gravity.CENTER);
        followAll.setPadding(U.dp(this, 16), U.dp(this, 10), U.dp(this, 16), U.dp(this, 10));
        followAll.setBackground(U.round(U.PANEL2, U.dp(this, 8)));
        followAll.setFocusable(true);
        followAll.setFocusableInTouchMode(true);
        followAll.setTag(Card.TAG_RING_RADIUS, Integer.valueOf(U.dp(this, 6)));
        followRing(followAll);
        followAll.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                store.resetLibThumbs();
                if (gridRef != null && libId != null) applyLibThumb();
                followAll.setText(L.t("已重置 · 全部跟随全局"));
            }
        });
        LinearLayout.LayoutParams fal = U.lp(-2, -2);
        fal.topMargin = U.dp(this, 6);
        fal.leftMargin = U.dp(this, 64);
        secLib.addView(followAll, fal);

        settingsSub(secLib, L.t("搜索页界面"));
        chipBlock(secLib, L.t("视图比例"), new String[]{L.t("自动"), "16:9", "9:16"}, 11, null);
        chipBlock(secLib, L.t("视图尺寸"), new String[]{L.t("小"), L.t("标准"), L.t("大")}, 12, null);

        settingsSub(secLib, L.t("收藏页界面"));
        chipBlock(secLib, L.t("视图比例"), new String[]{L.t("自动"), "16:9", "9:16"}, 33, null);
        chipBlock(secLib, L.t("视图尺寸"), new String[]{L.t("小"), L.t("标准"), L.t("大")}, 34, null);

        // —— 播放 ——
        settingsGroup(allCol, L.t("播放"));
        LinearLayout secPlay = settingsSection(allCol, null);
        settingsSub(secPlay, L.t("播放器选择"));
        secPlay.addView(engineRow(L.t("自动选择播放器"), 0), spaced());
        secPlay.addView(engineRow(L.t("强制 EXO 播放器"), 1), spaced());
        secPlay.addView(engineRow(L.t("强制 VLC 播放器"), 2), spaced());
        secPlay.addView(engineRow(L.t("选第三方播放器"), 3), spaced());

        final TextView eClearThird = U.tv(this, L.t("播放器初始化"), 13, U.TXT, false);
        eClearThird.setPadding(U.dp(this, 14), U.dp(this, 11), U.dp(this, 14), U.dp(this, 11));
        eClearThird.setBackground(U.round(U.PANEL2, U.dp(this, 8)));
        eClearThird.setFocusable(true);
        eClearThird.setFocusableInTouchMode(true);
        eClearThird.setTag(Card.TAG_RING_RADIUS, Integer.valueOf(U.dp(this, 6)));
        followRing(eClearThird);
        eClearThird.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                store.clearThirdPlayer();
                android.widget.Toast.makeText(MainActivity.this,
                        L.t("已清除，下次用第三方播放器会重新让你选"), android.widget.Toast.LENGTH_SHORT).show();
            }
        });
        LinearLayout.LayoutParams ecl = U.lp(-2, -2);
        ecl.topMargin = U.dp(this, 5);
        ecl.leftMargin = U.dp(this, 64);
        secPlay.addView(eClearThird, ecl);


        // —— 隐私 ——
        settingsGroup(allCol, L.t("隐私"));
        LinearLayout secPriv = settingsSection(allCol, null);
        chipBlock(secPriv, L.t("启动验证"), new String[]{L.t("每次输入"), L.t("免输密码")}, 7, null);
        chipBlock(secPriv, L.t("隐私遮罩"), new String[]{L.t("开"), L.t("关")}, 2, null);

        // —— 支持作者 ——
        settingsGroup(allCol, L.t("支持"));
        LinearLayout secDonate = settingsSection(allCol, null);
        TextView donateBtn = U.tv(this, L.t("请作者吃拼好饭"), 14, U.TXT, true);
        donateBtn.setGravity(Gravity.CENTER);
        donateBtn.setPadding(U.dp(this, 16), U.dp(this, 12), U.dp(this, 16), U.dp(this, 12));
        donateBtn.setFocusable(true);
        donateBtn.setFocusableInTouchMode(true);
        donateBtn.setBackground(rowStateBg());
        donateBtn.setTag(Card.TAG_RING_RADIUS, Integer.valueOf(U.dp(this, 6)));
        followRing(donateBtn);
        donateBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                android.util.Log.e("LMBY", "donate click");
                openDonate();
            }
        });
        LinearLayout.LayoutParams dbl = U.lp(-1, -2);
        dbl.topMargin = U.dp(this, 4);
        secDonate.addView(donateBtn, dbl);

        FrameLayout.LayoutParams pp = new FrameLayout.LayoutParams(U.dp(this, 400), -1);
        pp.gravity = Gravity.RIGHT;
        pp.setMargins(U.dp(this, 0), U.dp(this, 16), U.dp(this, 18), U.dp(this, 16));
        overlay.addView(panel, pp);

        // 焦点框图层：盖在整个设置页最上层，跟着焦点走
        ringLayer = new RingLayer(this);
        overlay.addView(ringLayer, new FrameLayout.LayoutParams(-1, -1));

        root.addView(overlay, new FrameLayout.LayoutParams(-1, -1));
        settingsOverlay = overlay;

        refreshSettingsRows();
        blockContentFocus(true);
        final LinearLayout panelRef = panel;
        final Runnable grabFocus = new Runnable() {
            @Override public void run() {
                if (settingsOverlay == null) return;
                boolean ok = false;
                if (settingsScroll != null) settingsScroll.scrollTo(0, 0);
                if (settingsFirstChip != null) {
                    settingsFirstChip.requestFocus();
                    ok = settingsFirstChip.isFocused();
                }
                if (!ok && settingsAllCol != null) ok = focusFirstIn(settingsAllCol);
                if (!ok) ok = focusFirstIn(panelRef);
                android.util.Log.i("EMBYFOCUS", "cat=" + setCat + " grab=" + ok);
            }
        };
        panel.post(grabFocus);
        panel.postDelayed(grabFocus, 250);
    }

    /** 设置页一级/二级的状态（跨多次打开保留上次停在的分类） */
    private View[] setPages;
    private View[] setNavs;
    private TextView[] setNavVals;
    private android.widget.ScrollView settingsL1;
    private FrameLayout settingsHost;
    private int setCat = 0;
    private LinearLayout settingsAllCol;
    private android.widget.ScrollView settingsScroll;

    /** 一个分类页：独立 ScrollView，切换时只显示当前这一个 */
    private LinearLayout settingsPage(FrameLayout host, java.util.ArrayList<View> pages, String catName) {
        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        sv.setVerticalScrollBarEnabled(false);
        sv.setFillViewport(true);
        LinearLayout col = U.col(this);
        sv.addView(col, new FrameLayout.LayoutParams(-1, -2));
        host.addView(sv, new FrameLayout.LayoutParams(-1, -1));
        sv.setOnScrollChangeListener(new View.OnScrollChangeListener() {
            @Override public void onScrollChange(View v, int x, int y, int ox, int oy) {
                if (ringLayer != null) ringLayer.invalidate();
            }
        });

        // 二级顶部的返回条：← 分类名，点它回一级
        LinearLayout bc = U.row(this);
        bc.setGravity(Gravity.CENTER_VERTICAL);
        bc.setPadding(U.dp(this, 10), U.dp(this, 10), U.dp(this, 10), U.dp(this, 10));
        bc.setBackground(U.round(0x00000000, U.dp(this, 8)));
        bc.setFocusable(true);
        bc.setFocusableInTouchMode(true);
        bc.setTag(Card.TAG_RING_RADIUS, Integer.valueOf(U.dp(this, 8)));
        followRing(bc);
        bc.addView(U.tv(this, "\u2190", 16, U.DIM, false));
        TextView cn = U.tv(this, catName, 15, U.TXT, true);
        cn.setSingleLine(true);
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(0, -2, 1f);
        cl.leftMargin = U.dp(this, 10);
        bc.addView(cn, cl);
        bc.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { settingsToMain(); }
        });
        LinearLayout.LayoutParams bl = U.lp(-1, -2);
        bl.bottomMargin = U.dp(this, 2);
        col.addView(bc, bl);

        if (pages != null) pages.add(sv);
        return col;
    }

    /** 一级：分类列表 */
    private void settingsToMain() {
        if (settingsL1 == null || settingsHost == null) return;
        settingsHost.setVisibility(View.GONE);
        settingsL1.setVisibility(View.VISIBLE);
        if (setPages != null) {
            for (int k = 0; k < setPages.length; k++) setPages[k].setVisibility(View.GONE);
        }
        for (int k = 0; k < setNavs.length; k++) {
            setNavs[k].setBackground(catTabBg(false));
        }
        if (ringLayer != null) ringLayer.clear();
        if (setNavs != null && setNavs.length > setCat) setNavs[setCat].requestFocus();
        if (ringLayer != null) ringLayer.invalidate();
    }

    /** 左侧分类项的背景：选中给实底，未选中透明 */
    private android.graphics.drawable.Drawable catTabBg(boolean sel) {
        if (!sel) return U.round(0x00000000, U.dp(this, 8));
        return U.round(U.DG ? U.CHIP_ON_BG : U.SOFT, U.dp(this, 8));
    }

    /** 进入第 i 个分类的二级页：藏一级、显该页、焦点落到页首的返回条 */
    private void showSettingsCat(int i) {
        if (setPages == null || setPages.length == 0) return;
        if (i < 0 || i >= setPages.length) i = 0;
        setCat = i;
        if (settingsL1 != null) settingsL1.setVisibility(View.GONE);
        if (settingsHost != null) settingsHost.setVisibility(View.VISIBLE);
        for (int k = 0; k < setPages.length; k++) {
            setPages[k].setVisibility(k == i ? View.VISIBLE : View.GONE);
        }
        for (int k = 0; k < setNavs.length; k++) {
            setNavs[k].setBackground(catTabBg(k == i));
        }
        View p = setPages[i];
        if (p instanceof android.widget.ScrollView) ((android.widget.ScrollView) p).scrollTo(0, 0);
        if (ringLayer != null) ringLayer.clear();
        boolean ok = focusFirstIn(p);
        if (!ok && setNavs.length > i) setNavs[i].requestFocus();
        if (ringLayer != null) ringLayer.invalidate();
    }

    /** 壁纸界面只露三项：原生 / 暗金 / 积云 → 真实壁纸索引 */
    private static final int[] BG_UI = {0, 10, 19};
    /** 字体界面只露三项：粗黑 / 毛笔 / 卡通 → 真实字体索引 */
    private static final int[] FONT_UI = {0, 4, 5};

    /** 一页长列表里的分类分组标题 */
    private void settingsGroup(LinearLayout col, String name) {
        TextView t = U.tv(this, name, 16, U.TXT, true);
        LinearLayout.LayoutParams lp = U.lp(-1, -2);
        lp.topMargin = U.dp(this, 12);
        lp.leftMargin = U.dp(this, 2);
        lp.bottomMargin = U.dp(this, 2);
        col.addView(t, lp);
    }

    /** 一级分类行右侧的当前值摘要 */
    private void refreshCatVals() {
        if (setNavVals == null) return;
        int th = store.uiTheme();
        String s0 = (th == 0 ? L.t("玻璃") : L.t("黑绿"));
        int bg = store.bg();
        String bgN = (bg == 10 ? L.t("暗金") : (bg == 19 ? L.t("积云") : L.t("原生")));
        String s1 = (store.showCarousel() ? L.t("轮播开") : L.t("轮播关"));
        int tm = store.thumbMode();
        String s2 = (tm == 0 ? L.t("自动") : (tm == 1 ? "16:9" : "9:16"));
        int pm = store.playerMode();
        String s3 = (pm == 1 ? "EXO" : (pm == 2 ? "VLC" : (pm == 3 ? L.t("第三方") : L.t("自动"))));
        String s4 = (store.askPassword() ? L.t("每次输入") : L.t("免输密码"));
        String[] ss = {s0, s1, s2, s3, s4};
        for (int i = 0; i < setNavVals.length && i < ss.length; i++) setNavVals[i].setText(ss[i]);
    }

    /** 分类页里的小标题（列表页的「媒体库内部 / 搜索页界面 / 收藏页界面」、播放页的「播放器选择」） */
    private void settingsSub(LinearLayout sec, String title) {
        TextView t = U.tv(this, title, 12, U.ACC, true);
        LinearLayout.LayoutParams lp = U.lp(-1, -2);
        lp.topMargin = U.dp(this, 18);
        lp.bottomMargin = U.dp(this, 4);
        sec.addView(t, lp);
    }

    /** 播放器那几行之间的间距 */
    private LinearLayout.LayoutParams spaced() {
        LinearLayout.LayoutParams lp = U.lp(-2, -2);
        lp.topMargin = U.dp(this, 5);
        lp.leftMargin = U.dp(this, 64);
        return lp;
    }

    /** 设置面板里的分类分组：半透明圆角框 + 左上角标题 */
    private LinearLayout settingsSection(LinearLayout panel, String title) {
        LinearLayout box = U.col(this);
        LinearLayout.LayoutParams bl = U.lp(-1, -2);
        bl.topMargin = U.dp(this, 8);
        if (U.EMBY) {
            // iOS 分组列表：组标题放在卡片外（灰色小字），卡片是白色圆角块
            if (title != null && !title.isEmpty()) {
                TextView h = U.tv(this, title, 13, U.DIM, true);
                LinearLayout.LayoutParams hl = U.lp(-1, -2);
                hl.leftMargin = U.dp(this, 6);
                hl.topMargin = panel.getChildCount() > 1 ? U.dp(this, 20) : U.dp(this, 12);
                hl.bottomMargin = U.dp(this, 8);
                panel.addView(h, hl);
            }
            box.setBackground(U.roundStroke(0xFFFFFFFF, U.dp(this, 4), U.dp(this, 1), 0x14000000));
            box.setPadding(U.dp(this, 16), U.dp(this, 14), U.dp(this, 16), U.dp(this, 16));
            panel.addView(box, U.lp(-1, -2));
            return box;
        }
        box.setBackground(U.roundStroke(U.LIGHT ? 0xE6FFFFFF : 0xE614181D,
                U.dp(this, 4), U.dp(this, 1), U.BORDER));
        box.setPadding(U.dp(this, 8), U.dp(this, 6), U.dp(this, 8), U.dp(this, 8));
        TextView h = U.tv(this, title, 14, U.ACC, true);
        LinearLayout.LayoutParams hl2 = U.lp(-1, -2);
        hl2.bottomMargin = U.dp(this, 10);
        box.addView(h, hl2);
        panel.addView(box, bl);
        return box;
    }

    /** 胶囊是否处于选中态：0=比例 1=大小 2=防窥 */
    private void chipBlock(LinearLayout sec, String label, String[] opts, int kind, String tip) {
        chipBlock(sec, label, opts, kind, 0, tip);
    }

    /** base = 选项起始索引（界面只露后面几项时用） */
    private void chipBlock(LinearLayout sec, String label, String[] opts, int kind, int base, String tip) {
        if (U.EMBY && sec.getChildCount() > 0) {
            View line = new View(this);
            line.setBackgroundColor(0x12000000);
            LinearLayout.LayoutParams llp = U.lp(-1, U.dp(this, 1));
            llp.topMargin = U.dp(this, 14);
            llp.bottomMargin = U.dp(this, 14);
            sec.addView(line, llp);
        }
        LinearLayout row = U.row(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams rl = U.lp(-1, -2);
        if (!U.EMBY && sec.getChildCount() > 0) rl.topMargin = U.dp(this, 5);
        sec.addView(row, rl);
        // 左标签固定宽度、右选项占满剩余 —— 一行一条，跨项对齐，不再标签压在上面换行
        TextView lab = U.tv(this, label, 13, U.TXT, false);
        lab.setGravity(Gravity.CENTER_VERTICAL);
        lab.setMinWidth(U.dp(this, 64));
        LinearLayout.LayoutParams labLp = U.lp(-2, -2);
        labLp.rightMargin = U.dp(this, 8);
        row.addView(lab, labLp);
        chips(row, null, opts, kind, base);
        if (tip != null && !tip.isEmpty()) {
            TextView t = U.tv(this, tip, 11, U.DIM, false);
            t.setLineSpacing(U.dp(this, 4), 1f);
            LinearLayout.LayoutParams tlp = U.lp(-1, -2);
            tlp.topMargin = U.dp(this, 6);
            tlp.leftMargin = U.dp(this, 64);
            sec.addView(t, tlp);
        }
    }

    private boolean selected(int kind, int idx) {
        if (kind == 0) return store.thumbMode() == idx;
        if (kind == 1) return store.thumbSize() == idx;
        if (kind == 3) return store.uiTheme() == idx;
        if (kind == 4) return store.detailPage() == (idx == 0);
        if (kind == 5) return store.poster() == (idx == 1);
        if (kind == 6) return store.libCardSize() == idx;
        if (kind == 7) return store.askPassword() == (idx == 0);
        if (kind == 8) return store.forceTranscode() == (idx == 1);
        if (kind == 9) return store.maxHeightOpt() == idx;
        if (kind == 10) return store.maxKbpsOpt() == idx;
        if (kind == 11) return store.searchThumbMode() == idx;
        if (kind == 12) return store.searchThumbSize() == idx;
        if (kind == 13) return store.showRecent() == (idx == 0);
        if (kind == 14) return store.showFavorites() == (idx == 0);
        if (kind == 15) return store.zoom() == (idx == 0);
        if (kind == 16) return store.ring() == idx;
        if (kind == 17) return store.bg() == BG_UI[idx];
        if (kind == 18) return store.ringStyle() == idx;
        if (kind == 19) return store.detailDelete() == (idx == 0);
        if (kind == 20) return store.lang() == idx;
        if (kind == 21) return store.showLatestRows() == (idx == 0);
        if (kind == 22) return store.homeLibSize() == idx;
        if (kind == 23) return store.homeRecSize() == idx;
        if (kind == 24) return store.homeLatestSize() == idx;
        if (kind == 27) return false;
        if (kind == 28) return store.scrollAnim() == idx;
        if (kind == 29) return store.font() == FONT_UI[idx];
        if (kind == 30) return store.txtColor() == idx;
        if (kind == 31) return store.showCarousel() == (idx == 0);
        if (kind == 32) return store.carSource() == idx;
        if (kind == 33) return store.favMode() == idx;
        if (kind == 34) return store.favSize() == idx;
        if (kind == 35) return store.favForm() == idx;
        if (kind == 36) return store.searchViewType() == idx;
        return store.secure() == (idx == 0);
    }

    /** 设置页的焦点框图层：坐在设置面板最顶上，谁拿到焦点就把外面那一套焦点环画在谁身上。
     *  这样框一定画在所有控件之上 —— 不会被胶囊底色、分组卡片、滚动容器盖住，
     *  也不依赖控件自己的 onDraw 或 foreground 状态刷新。 */
    static class RingLayer extends View {
        private final android.graphics.Paint paint =
                new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private View target;
        private final int[] loc = new int[2];
        private final int[] self = new int[2];

        RingLayer(android.content.Context c) {
            super(c);
            setClickable(false);
            setFocusable(false);
        }

        View target() { return target; }

        void follow(View v) { target = v; invalidate(); }

        void clear() { target = null; invalidate(); }

        @Override protected void onDraw(android.graphics.Canvas canvas) {
            View t = target;
            if (t == null || !t.isFocused() || t.getVisibility() != View.VISIBLE) return;
            if (t.getWidth() <= 0 || t.getHeight() <= 0) return;
            t.getLocationOnScreen(loc);
            getLocationOnScreen(self);
            int rad = U.dp(getContext(), 6);   // 两套 UI 都用方形小圆角焦点框
            Object rr = t.getTag(Card.TAG_RING_RADIUS);
            if (rr instanceof Integer) rad = ((Integer) rr).intValue();
            int save = canvas.save();
            canvas.translate(loc[0] - self[0], loc[1] - self[1]);
            U.drawRing(canvas, t.getWidth(), t.getHeight(), rad,
                    getResources().getDisplayMetrics().density, paint, 0f, 0);
            canvas.restoreToCount(save);
        }
    }

    private RingLayer ringLayer;

    /** 只负责画一圈外面同款白玻璃环的透明 View（所有地方的焦点框都用它，保证样式一致） */
    static class RingView extends View {
        private final int radPx;
        private final android.graphics.Paint p =
                new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);

        RingView(android.content.Context c, int radPx) {
            super(c);
            this.radPx = radPx;
            setClickable(false);
            setFocusable(false);
        }

        @Override protected void onDraw(android.graphics.Canvas canvas) {
            U.drawRing(canvas, getWidth(), getHeight(), radPx,
                    getResources().getDisplayMetrics().density, p, 0f, 0);
        }
    }

    /** 让设置页里任意可聚焦控件在拿到焦点时，把顶层焦点框挪到自己身上 */
    private void followRing(final View v) {
        v.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override public void onFocusChange(View vv, boolean has) {
                if (ringLayer == null) return;
                if (has) ringLayer.follow(vv);
                else if (ringLayer.target() == vv) ringLayer.clear();
            }
        });
    }

    /** 设置页专用方形胶囊：小圆角，整体方正 */
    private android.graphics.drawable.Drawable settingsChipBg(boolean sel) {
        int rad = U.dp(this, 4);
        if (U.EMBY) return U.round(sel ? U.CHIP_ON_BG : 0x33FFFFFF, rad);
        return U.round(sel ? (U.DG ? U.CHIP_ON_BG : U.SOFT) : U.PANEL2, rad);
    }

    private android.graphics.drawable.Drawable chipBg(boolean sel) {
        // 炫酷：切角赛博胶囊（选中青紫渐变实底 / 未选幽蓝玻璃青边）
        if (U.EMBY) return U.cyberChip(this, sel, false);
        // 未选中：深色胶囊底；选中：亮一点的实心胶囊底。
        // 选中态刻意不用描边 —— 描边只留给焦点框，否则一眼分不清哪个是焦点
        int rad = U.dp(this, U.RADIUS);
        if (!sel) return U.round(U.PANEL2, rad);
        // 黑绿的未选中底跟 SOFT 同色（0xFF1E241E），要单独用 Emby 绿实底才看得出选中
        return U.round(U.DG ? U.CHIP_ON_BG : U.SOFT, rad);
    }

    /** 胶囊焦点态：炫酷是切角 + 亮青渐变边，其余是 3dp 纯白描边 */
    private android.graphics.drawable.Drawable chipStateBg(boolean sel) {
        android.graphics.drawable.StateListDrawable sl = new android.graphics.drawable.StateListDrawable();
        if (U.EMBY) {
            sl.addState(new int[]{android.R.attr.state_focused}, U.cyberChip(this, sel, true));
        } else {
            sl.addState(new int[]{android.R.attr.state_focused},
                    U.roundStroke(sel ? 0x26FFFFFF : 0x14FFFFFF, U.dp(this, U.RADIUS), U.dp(this, 3.0f), 0xFFFFFFFF));
        }
        sl.addState(new int[0], chipBg(sel));
        return sl;
    }

    /** 设置页整行按钮：焦点态同样是 3dp 纯白描边 */
    private android.graphics.drawable.Drawable rowStateBg() {
        android.graphics.drawable.StateListDrawable sl = new android.graphics.drawable.StateListDrawable();
        sl.addState(new int[]{android.R.attr.state_focused},
                U.roundStroke(0x14FFFFFF, U.dp(this, 6), U.dp(this, 3.0f), 0xFFFFFFFF));
        sl.addState(new int[0], U.round(U.PANEL2, U.dp(this, 6)));
        return sl;
    }

    /** 一组胶囊选项：标签独占一行，选项按可用宽度自动换行（适配窄面板） */
    private void chips(LinearLayout row, String label, String[] opts, final int kind) {
        chips(row, label, opts, kind, 0);
    }

    /** base = 选项起始索引（背景图第二组纯色从 11 开始） */
    private void chips(LinearLayout row, String label, String[] opts, final int kind,
                       final int base) {
        LinearLayout box = U.col(this);
        LinearLayout lines = U.col(this);
        lines.setGravity(Gravity.RIGHT);
        if (label != null && !label.isEmpty()) {
            box.addView(U.tv(this, label, 12, U.DIM, false), U.lp(-1, -2));
        }
        LinearLayout.LayoutParams llp = U.lp(-1, -2);
        llp.topMargin = (label == null || label.isEmpty()) ? 0 : U.dp(this, 6);
        box.addView(lines, llp);

        final java.util.ArrayList<TextView> list = new java.util.ArrayList<TextView>();
        int maxW = U.dp(this, 360);
        LinearLayout cur = U.row(this);
        cur.setGravity(Gravity.RIGHT);
        lines.addView(cur, U.lp(-1, -2));
        int usedW = 0;

        for (int i = 0; i < opts.length; i++) {
            final int idx = base + i;
            boolean sel = selected(kind, idx);
            final TextView c = U.tv(this, opts[i], 12,
                    sel ? 0xFFFFFFFF : U.DIM, false);
            if (U.DG && sel) c.setTextColor(U.CHIP_ON_TXT);   // 黑绿：绿底配深色字
            c.setSingleLine(true);
            c.setGravity(Gravity.CENTER);
            c.setPadding(U.dp(this, 9), U.dp(this, 6), U.dp(this, 9), U.dp(this, 6));
            c.setBackground(settingsChipBg(sel));
            c.setFocusable(true);
            c.setFocusableInTouchMode(true);
            c.setTag(Card.TAG_RING_RADIUS, Integer.valueOf(U.dp(this, 4)));
            list.add(c);
            if (settingsFirstChip == null) settingsFirstChip = c;

            // 估算宽度：中文按 14dp/字，西文按 7dp/字（估得宽一点，宁可早换行也不要被挤成竖排）
            int textW = 0;
            String s0 = opts[i];
            for (int k = 0; k < s0.length(); k++) {
                textW += s0.charAt(k) < 128 ? U.dp(this, 7) : U.dp(this, 14);
            }
            int chipW = textW + U.dp(this, 22) + U.dp(this, 6);
            if (usedW + chipW > maxW && usedW > 0) {
                cur = U.row(this);
                cur.setGravity(Gravity.RIGHT);
                lines.addView(cur, U.lp(-1, -2));
                LinearLayout.LayoutParams nl = U.lp(-1, -2);
                nl.topMargin = 0;
                usedW = 0;
            }
            // 选项均分整行宽度，右边贴到面板边缘，不留空档
            LinearLayout.LayoutParams clp = U.lp(-2, -2);
            clp.rightMargin = (i == opts.length - 1) ? 0 : U.dp(this, 6);
            cur.addView(c, clp);
            usedW += chipW;

            // 焦点态交给 state 列表（chipStateBg/chipTextColor）自动切换
            c.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    if (kind == 0) { store.thumbMode(idx); store.resetLibThumbs(); thumbDirty = true; }                    else if (kind == 1) { store.thumbSize(idx); store.resetLibThumbs(); thumbDirty = true; }
                    else if (kind == 3) { switchTheme(idx > 1 ? 0 : idx); return; }
                    else if (kind == 4) { store.detailPage(idx == 0); }
                    else if (kind == 5) { store.poster(idx == 1); }
                    else if (kind == 6) { store.libCardSize(idx); homeDirty = true; }
                    else if (kind == 7) { store.askPassword(idx == 0); }
                    else if (kind == 8) { store.forceTranscode(idx == 1); }
                    else if (kind == 9) { store.maxHeightOpt(idx); }
                    else if (kind == 10) { store.maxKbpsOpt(idx); }
                    else if (kind == 11) { store.searchThumbMode(idx); }
                    else if (kind == 12) { store.searchThumbSize(idx); }
                    else if (kind == 13) { store.showRecent(idx == 0); homeDirty = true; }
                    else if (kind == 14) { store.showFavorites(idx == 0); homeDirty = true; }
                    else if (kind == 15) {
                        store.zoom(idx == 0);
                        U.FOCUS_ZOOM = (idx == 0);
                        homeDirty = true;
                    }
                    else if (kind == 16) {
                        store.ring(idx);
                        U.applyRing(idx);
                        homeDirty = true;
                        v.getRootView().invalidate();
                    }
                    else if (kind == 17) {
                        int realBg = (idx >= 0 && idx < BG_UI.length) ? BG_UI[idx] : idx;
                        store.bg(realBg);
                        U.prepareBg(MainActivity.this, realBg);
                        if (root != null) root.setBackground(U.bgDraw());
                        homeDirty = true;
                    }
                    else if (kind == 18) {
                        store.ringStyle(idx);
                        U.RING_STYLE = idx;
                        homeDirty = true;
                        v.getRootView().invalidate();
                    }
                    else if (kind == 19) { store.detailDelete(idx == 0); }
                    else if (kind == 20) {
                        store.lang(idx);
                        L.applyMode(MainActivity.this, idx);
                        recreate();
                        return;
                    }
                    else if (kind == 21) { store.showLatestRows(idx == 0); homeDirty = true; }
                    else if (kind == 22) { store.homeLibSize(idx); homeDirty = true; }
                    else if (kind == 23) { store.homeRecSize(idx); homeDirty = true; }
                    else if (kind == 24) { store.homeLatestSize(idx); homeDirty = true; }
                    else if (kind == 31) { store.showCarousel(idx == 0); homeDirty = true; }
                    else if (kind == 32) { store.carSource(idx); homeDirty = true; }
                    else if (kind == 33) { store.favMode(idx); homeDirty = true; }
                    else if (kind == 34) { store.favSize(idx); homeDirty = true; }
                    else if (kind == 35) { store.favForm(idx); homeDirty = true; }
                    else if (kind == 36) { store.searchViewType(idx); }
                    else if (kind == 28) { store.scrollAnim(idx); }
                    else if (kind == 30) { store.txtColor(idx); U.applyTxt(idx); closeSettings();
                        if (libId != null) openLibrary(libId, libName, folderId, folderName, libType);
                        else if (session != null) showHome();
                        else if (store.lastUser().length() > 0) showQuickLogin();
                        else showLogin(store.server(), store.user());
                        return; }
                    else if (kind == 29) { int realFt = (idx >= 0 && idx < FONT_UI.length) ? FONT_UI[idx] : idx; store.font(realFt); U.applyFont(MainActivity.this, realFt); closeSettings();
                        if (libId != null) openLibrary(libId, libName, folderId, folderName, libType);
                        else if (session != null) showHome();
                        else if (store.lastUser().length() > 0) showQuickLogin();
                        else showLogin(store.server(), store.user());
                        return; }
                    else { store.secure(idx == 0); applySecure(); }
                    for (int k = 0; k < list.size(); k++) {
                        boolean s2 = selected(kind, k);
                        TextView cv = list.get(k);
                        cv.setBackground(settingsChipBg(s2));
                        cv.setTextColor(U.txt(s2 ? 0xFFFFFFFF : U.DIM));
                    }
                    if (gridRef != null && libId != null) applyLibThumb();
                    if (searchGrid != null) {
                        searchGrid.configure(store.searchHeightDp(), 0);
        searchGrid.setRatio(store.searchThumbMode());
                        searchGrid.requestLayout();
                    }
                }
            });
            c.setOnFocusChangeListener(new View.OnFocusChangeListener() {
                @Override public void onFocusChange(View v, boolean has) {
                    // 背景只表示「选中」；焦点框交给顶层 RingLayer 画，两套东西各干各的
                    v.setBackground(settingsChipBg(selected(kind, idx)));
                    if (ringLayer != null) {
                        if (has) ringLayer.follow(v);
                        else if (ringLayer.target() == v) ringLayer.clear();
                    }
                }
            });
        }
        row.addView(box, U.lp(-1, -2));
    }

    private void refreshSettingsRows() {
        // 通过 tag 标记行控件，简单刷新背景
    }

    /** 播放器选择行：自动 / 强制 ExoPlayer / 强制 libVLC / 第三方 */
    private TextView engineRow(final String label, final int mode) {
        final TextView r = U.tv(this, (store.engine() == mode ? "◉  " : "○  ") + label, 14, U.TXT, false);
        r.setPadding(U.dp(this, 14), U.dp(this, 12), U.dp(this, 14), U.dp(this, 12));
        r.setBackground(U.round(U.PANEL2, U.dp(this, 6)));
        r.setFocusable(true);
        r.setFocusableInTouchMode(true);
        r.setTag(Card.TAG_RING_RADIUS, Integer.valueOf(U.dp(this, 6)));
        followRing(r);
        r.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                store.engine(mode);
                store.playerMode(mode == 3 ? 1 : 0);   // 兼容旧的「第三方播放器」标记
                closeSettings();
                openSettings();
            }
        });
        return r;
    }

    private TextView settingRow(final String label, final int mode) {
        final TextView r = U.tv(this, (store.playerMode() == mode ? "◉  " : "○  ") + label, 14, U.TXT, false);
        r.setPadding(U.dp(this, 14), U.dp(this, 12), U.dp(this, 14), U.dp(this, 12));
        r.setBackground(U.round(U.PANEL2, U.dp(this, 6)));
        r.setFocusable(true);
        r.setFocusableInTouchMode(true);
        r.setTag(Card.TAG_RING_RADIUS, Integer.valueOf(U.dp(this, 6)));
        followRing(r);
        r.setTag(Integer.valueOf(mode));
        r.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                store.playerMode(mode);
                Intent i = new Intent("com.longyao.anbitv.SETTINGS_CHANGED");
                sendBroadcast(i);
                // 重建设置面板以刷新选中态
                closeSettings();
                openSettings();
            }
        });
        return r;
    }

    // ================= 返回键 =================

    @Override public void onBackPressed() {
        if (donateDlg != null) { closeDonate(); return; }
        // 服务器卡片的「修改 / 删除」面板、修改表单：返回键先关面板
        if (serverMenuOverlay != null) { closeServerMenu(); return; }
        if (serverEditOverlay != null) { hideIme(null); closeServerEdit(); return; }
        if (photoOverlay != null) { closePhoto(); return; }
        // 详情页永远是最上层，先关它，退回来才落在原来的列表（搜索结果 / 库网格）上
        if (detailOverlay != null) { closeDetail(); return; }
        if (searchOverlay != null) { closeSearch(); return; }
        if (inLogin) { inLogin = false; showSelectServer(); return; }
        if (inQuickLogin) { inQuickLogin = false; showSelectServer(); return; }
        if (accountOverlay != null) { closeAccountMenu(); return; }
        if (sortPanel != null) {
            closeSortPanel();
            return;
        }
        if (libPanel != null) {
            closeLibPanel();
            return;
        }
        if (settingsOverlay != null) {
            closeSettings();
            return;
        }
        if (libId != null) {
            // 剧集列表：先回第一张卡，再按一次退回媒体库列表
            if (currentShow != null) {
                if (gridRef != null && gridRef.virtualCount() > 0) {
                    // 窗口化：回顶必须把第 0 条重新绑出来，不能拿窗口里的第一张当“置顶”
                    if (!gridRef.firstItemFocused()) {
                        if (gridScroll != null) gridScroll.scrollTo(0, 0);
                        gridRef.focusFirstItem();
                        return;
                    }
                } else if (gridRef != null && gridRef.getChildCount() > 0) {
                    View first = gridRef.getChildAt(0);
                    if (getCurrentFocus() != first) {
                        if (gridScroll != null) gridScroll.scrollTo(0, 0);
                        first.requestFocus();
                        return;
                    }
                }
                currentShow = null;
                libFocusKey = null;
                buildLibraryPage();
                loadItems();
                return;
            }
            if (folderId != null) {
                View cur = getCurrentFocus();
                boolean onFolderCard = cur instanceof Card && ((Card) cur).folderCard;
                // 在文件夹视图里：焦点落在文件（视频/图片）上时，先回到置顶的文件夹，再按一次才退到上一层；
                // 焦点本来就在文件夹上，则直接返回上一层
                boolean needTop = folderMode ? !onFolderCard : true;
                if (needTop && gridRef != null && gridRef.virtualCount() > 0) {
                    if (!gridRef.firstItemFocused()) {
                        if (gridScroll != null) gridScroll.scrollTo(0, 0);
                        gridRef.focusFirstItem();
                        return;
                    }
                } else if (needTop && gridRef != null && gridRef.getChildCount() > 0) {
                    View first = gridRef.getChildAt(0);
                    if (cur != first) {
                        if (gridScroll != null) gridScroll.scrollTo(0, 0);
                        first.requestFocus();
                        return;
                    }
                }
                goUpFolder();
                return;
            }
            // 库内：不管焦点在哪、滚到多远，先回到置顶第一个缩略图；已在第一张才退出
            if (gridRef != null && gridRef.virtualCount() > 0) {
                // 窗口化后 getChildAt(0) 是窗口里最靠前的卡，不是第 0 条 → 必须让网格自己绑第 0 条
                if (!gridRef.firstItemFocused()) {
                    if (gridScroll != null) gridScroll.scrollTo(0, 0);
                    gridRef.focusFirstItem();
                    return;
                }
            } else if (gridRef != null && gridRef.getChildCount() > 0) {
                View first = gridRef.getChildAt(0);
                if (getCurrentFocus() != first) {
                    if (gridScroll != null) gridScroll.scrollTo(0, 0);
                    first.requestFocus();
                    return;
                }
            }
            libId = null;
            showHome();
            return;
        }
        // 收藏页按返回：直接切回首页那个 tab，不要留在收藏页、更不要退出 App
        if (homeTab != 0) {
            homeTab = 0;
            showHome();
            return;
        }
        // 首页：先回到第一个媒体库，再按一次才退出 App
        if (homeGrid != null && homeGrid.getChildCount() > 0) {
            View first = homeGrid.getChildAt(0);
            if (getCurrentFocus() != first) {
                if (homeScroll != null) homeScroll.scrollTo(0, 0);
                first.requestFocus();
                return;
            }
        }
        super.onBackPressed();
    }
}
