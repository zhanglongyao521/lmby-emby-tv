package com.longyao.anbitv;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 界面文字：默认跟系统语言，中文系统显示中文，其它语言显示英文。
 * 用户也可以在设置页里手动指定（跟随系统 / 中文 / English）。
 *
 * 用法：L.t("媒体库") —— 中文原文当 key，英文查表，查不到就回退中文。
 * 这样不用给每处文字编 key，也不会因为漏翻译把界面变成 blank。
 */
public final class L {

    /** 手动语言：0 跟随系统，1 中文，2 English */
    public static final int MODE_AUTO = 0, MODE_ZH = 1, MODE_EN = 2;

    private static boolean english = false;
    private static int mode = MODE_AUTO;
    private static final Map<String, String> EN = new HashMap<String, String>();

    public static void init(Context c) {
        int m = MODE_AUTO;
        try {
            SharedPreferences sp = c.getSharedPreferences("embytv", 0);
            m = sp.getInt("lang", MODE_AUTO);
        } catch (Throwable ignored) { }
        applyMode(c, m);
    }

    /** 切语言后立即生效（下一次刷新界面就换过来） */
    public static void applyMode(Context c, int m) {
        mode = (m == MODE_ZH || m == MODE_EN) ? m : MODE_AUTO;
        if (mode == MODE_ZH) {
            english = false;
        } else if (mode == MODE_EN) {
            english = true;
        } else {
            Locale lo = null;
            try { lo = c.getResources().getConfiguration().locale; } catch (Throwable ignored) { }
            if (lo == null) lo = Locale.getDefault();
            String lang = lo == null ? "zh" : lo.getLanguage();
            english = !(lang != null && lang.toLowerCase(Locale.US).startsWith("zh"));
        }
    }

    public static int mode() { return mode; }

    public static boolean isEnglish() { return english; }

    /** 取界面文字；英文环境下查表，查不到保留中文 */
    public static String t(String zh) {
        if (!english || zh == null || zh.length() == 0) return zh;
        String en = EN.get(zh);
        return en == null ? zh : en;
    }

    private static void p(String zh, String en) { EN.put(zh, en); }

    static {
        // ---- 通用 ----
        p("关", "Off"); p("开", "On"); p("是", "Yes"); p("否", "No"); p("无", "None");
        p("大", "Large"); p("小", "Small"); p("标准", "Standard"); p("超大", "Extra large"); p("超小", "Extra small");
        p("自动", "Auto"); p("默认", "Default"); p("确定", "OK"); p("取消", "Cancel"); p("保存", "Save");
        p("收藏", "Favorites"); p("文件夹", "Folders"); p("媒体库", "Libraries"); p("照片", "Photos");
        p("图片", "Pictures"); p("视频", "Videos"); p("音频", "Audio"); p("音乐", "Music"); p("电影", "Movies");
        p("节目", "Shows"); p("部剧集", "Shows"); p("集", "Episodes");
        p("剧集", "Series"); p("选集", "Episodes"); p("简介", "Overview");
        p("暂无简介", "No overview"); p("正在读取…", "Loading…");
        p("最新", "Latest");
        p("首页按媒体库生成「最新」模块", "Latest rows per library");
        p("媒体库模块大小", "Library row size");
        p("最近观看模块大小", "Recent row size");
        p("最新模块大小", "Latest rows size");
        p("超小", "XS"); p("小", "S"); p("标准", "M"); p("大", "L"); p("超大", "XL");
        p("页面切换动画", "Page transition");
        p("跟手", "Instant"); p("淡入", "Fade"); p("缩放", "Zoom");
        p("上滑", "Slide up"); p("推移", "Push");
        p("上下翻页滚动", "Row scroll");
        p("平滑", "Smooth"); p("柔和", "Soft");
        p("没有可播放的集", "No playable episode");
        p("没有可选的集", "No episodes to pick");
        p("左右选中 · 确认播放 · 返回关闭", "◀ ▶ select · OK play · BACK close");
        p("电视剧", "TV Shows"); p("首页", "Home"); p("搜索", "Search"); p("设置", "Settings");
        p("登录", "Sign in"); p("登 录", "Sign in"); p("密码", "Password"); p("用户名", "Username"); p("账号", "Account");
        p("注销", "Sign out"); p("切换服务器", "Switch server"); p("切换账号 / 输密码", "Switch account / password");
        p("服务器地址", "Server address"); p("服务器名称", "Server name"); p("端口", "Port");
        p("我的 Emby 服务器", "My Emby Server"); p("Emby 服务器", "Emby Server"); p("官方 Emby", "Official Emby");
        p("布局", "Layout"); p("界面", "Interface"); p("界面风格", "Interface style"); p("隐私", "Privacy");
        p("显示", "Display"); p("画面", "Picture"); p("选项", "Options"); p("配置", "Configure");
        p("其它选项", "Other options"); p("备注", "Notes"); p("名称", "Name"); p("年份", "Year");
        p("时长", "Duration"); p("时长未知", "Unknown duration"); p("大小", "Size"); p("版本", "Version");
        p("路径", "Path"); p("文件名", "File name"); p("文件尺寸", "File size"); p("文件地址", "File path");
        p("类型", "Type"); p("等级", "Rating"); p("合集", "Collection"); p("排行", "Ranking");
        p("排序", "Sort"); p("搜索列表", "Search results"); p("随机", "Random"); p("正在载入…", "Loading…");
        p("加载失败", "Load failed"); p("读取失败：", "Read failed: "); p("载入失败：", "Load failed: ");
        p("删除失败", "Delete failed"); p("删除失败：", "Delete failed: "); p("已删除", "Deleted");
        p("播放失败", "Playback failed"); p("播放失败：", "Playback failed: "); p("播放失败，试试切换解码方式", "Playback failed — try another decoder");
        p("播放错误: ", "Playback error: "); p("未知错误", "Unknown error"); p("操作失败，检查账号权限", "Failed — check the account permissions");
        p("收藏失败，检查账号权限", "Failed — check the account permissions");
        p("连接失败：", "Connect failed: "); p("连接中…", "Connecting…"); p("正在登录…", "Signing in…");
        p("登录中…", "Signing in…"); p("登录失败：", "Sign-in failed: "); p("登录返回异常", "Unexpected sign-in response");
        p("用户名或密码错误", "Wrong username or password");
        p("请填写服务器地址和用户名", "Enter the server address and username");
        p("请填写服务器地址和 Connect 邮箱", "Enter the server address and Connect email");
        p("已经", "Already"); p("有", "Yes"); p("未选", "None"); p("未选择", "None"); p("正在读取…", "Loading…");

        // ---- 登录 ----
        p("Connect 登录", "Connect sign-in"); p("Connect 密码", "Connect password");
        p("Connect 账号（邮箱）", "Connect account (email)");
        p("Connect 登录返回异常", "Unexpected Connect sign-in response");
        p("Emby Connect 登录失败: ", "Emby Connect sign-in failed: ");
        p("Emby Connect 账号或密码错误", "Wrong Emby Connect account or password");
        p("Emby Connect 没有返回凭证，请先在网页端登录一次", "Emby Connect returned no credentials — sign in once on the web first");
        p("服务器拒绝了 Connect 登录，请确认服务器已开启 Emby Connect 且账号已关联", "The server rejected the Connect sign-in — make sure Emby Connect is enabled and the account is linked");
        p("服务器拒绝了这次请求（HTTP 400）", "The server rejected this request (HTTP 400)");
        p("连不上 Emby Connect 服务，请检查网络", "Can't reach Emby Connect — check your network");
        p("连接不到服务器，请检查地址与网络", "Can't reach the server — check the address and network");
        p("账号无权限或被服务器拒绝", "No permission, or rejected by the server");
        p("用 Emby Connect 账号（邮箱）登录，服务器需已开启 Connect", "Sign in with an Emby Connect account (email); Connect must be enabled on the server");
        p("检测账号中…", "Checking the account…"); p("正在向 Emby Connect 验证…", "Verifying with Emby Connect…");
        p("后台返回 要求重新登录", "The server asked to sign in again");
        p("选择一个服务器", "Choose a server"); p("切换服务器", "Switch server");
        p("手动输入", "Enter manually"); p("手机扫码登录", "Scan the QR code to sign in");
        p("手机扫码配置", "Scan the QR code to configure"); p("📱 手机扫码配置", "📱 Scan to configure");
        p("手机浏览器打开 ", "Open in your phone browser: "); p("正在扫描局域网里的 Emby 服务器…", "Scanning the LAN for Emby servers…");
        p("没有扫描到服务器，请用「手动输入」", "No servers found — use \"Enter manually\"");
        p("正在搜索服务器…", "Searching for servers…"); p("正在搜索「", "Searching: \"");
        p("还没有内容", "Nothing here yet");
        p("该账号下没有可见的媒体库。请检查 Emby 服务器是否已添加媒体库、账号是否有权限。", "No libraries are visible to this account. Check that the Emby server has libraries and the account can see them.");
        p("点击保存将验证服务器连接并保存配置", "Saving will verify the server and store the settings");
        p("启动密码", "Startup PIN"); p("每次输入", "Ask every launch"); p("免密码", "No PIN");
        p("后台防窥", "Hide in recents"); p("启动密码", "Startup PIN");
        p("开启后，从最近任务里看不到 App 画面（系统截屏也会变黑）。另外 App 从不保存密码明文。", "When on, the app is hidden from recents (system screenshots go black). The app never stores your password in plain text.");
        p("已从手机填入，检查后点「保存」连接", "Filled in from your phone — check and tap \"Save\" to connect");

        // ---- 首页 / 媒体库 ----
        p("最近观看", "Recently Watched"); p("最近观看", "Recently Watched");
        p("全部视频", "All videos"); p("全部视频", "All videos"); p("详情…", "Details…");
        p("继续播放", "Resume"); p("播放", "Play"); p("播放器", "Player"); p("选集 · ", "Episodes · ");
        p("第 ", "Ep. "); p("集", "Episodes"); p("项", "items"); p("个视频", "videos");
        p("共", "Total "); p("季", " seasons"); p("第", "Ep. ");
        p("取自", "from "); p("正在读取文件信息…", "Reading file info…");
        p("演职人员", "Cast & Crew"); p("类似作品", "More Like This");
        p("暂无演职人员信息", "No cast info"); p("暂无类似作品", "No similar titles");
        p("暂无刮削信息", "No metadata");
        p("个", ""); p("张照片", "photos"); p("张图片", "pictures"); p("条结果", "results");
        p("个文件夹 · ", "folders · "); p("个视频 · 已载入 ", "videos · loaded ");
        p("个视频 · 已载入 ", "videos · loaded "); p("张照片 · 已载入 ", "photos · loaded ");
        p("还没有收藏。在详情页点「最爱」就会出现在这里。", "No favorites yet. Tap \"Favorite\" on a details page and it shows up here.");
        p("这个目录里没有内容。", "This folder is empty.");
        p("这里还没有视频。若服务器上确实有内容，请确认该媒体库已扫描完成。", "No videos here yet. If the server does have content, make sure the library finished scanning.");
        p("没有读到剧集", "No episodes found"); p("没有找到「", "Nothing matches \"");
        p("请输入关键词", "Type a keyword"); p("输入片名搜索", "Search by title");
        p("输入关键词后按「搜索」，也可以直接按键盘回车", "Type a keyword and press \"Search\", or just hit Enter");
        p("搜索失败：", "Search failed: "); p("正在搜索「", "Searching: \"");
        p("读取剧集…", "Loading episodes…"); p("正在读取目录…", "Loading the folder…");
        p("正在载入照片…", "Loading photos…"); p("正在载入视频…", "Loading videos…");
        p("正在读取最近观看…", "Loading recently watched…"); p("正在读取收藏…", "Loading favorites…");
        p("正在读取收藏…", "Loading favorites…"); p("正在读取播放信息…", "Loading playback info…");
        p("正在读取收藏…", "Loading favorites…"); p("还没有内容", "Nothing here yet");
        p("顶部条", "Top bar"); p("底部条", "Bottom bar"); p("背景图", "Backdrop"); p("缩略图", "Thumbnails");
        p("媒体库缩略图", "Library thumbnails"); p("首页卡片大小", "Home card size");
        p("焦点放大（缩略图变大）", "Focus zoom (larger thumbnails)");
        p("首页显示「收藏」", "Show \"Favorites\" on Home"); p("首页显示「最近观看」", "Show \"Recently Watched\" on Home");
        p("点视频先看详情", "Open details before playing");
        p("开启后点缩略图先进入详情页；关闭则直接播放。", "When on, tapping a thumbnail opens the details page; when off it plays right away.");
        p("详情页删除按钮", "Delete button on details page");
        p("显示后详情页的删除按钮点一下直接删掉服务器上的文件，不再弹确认框。", "When on, the delete button on the details page removes the file on the server immediately, with no confirmation.");
        p("媒体信息", "Media info"); p("文件地址 —", "File path —"); p("文件地址 ", "File path ");
        p("加入日期", "Date added"); p("发行日期", "Release date"); p("播放日期", "Played on");
        p("播放次数", "Play count"); p("播放时长", "Playback time"); p("已看 ", "Watched ");
        p("添加于 ", "Added "); p("评级", "Rating"); p("家长分级", "Parental rating");
        p("IMDb评分", "IMDb rating"); p("影评人评分", "Critics rating"); p("类型", "Genre");
        p("区域", "Region"); p("形态", "Format"); p("跟随主题", "Follow theme");

        // ---- 播放器菜单 ----
        p("解码方式", "Decoder"); p("硬件解码", "Hardware"); p("软件解码", "Software"); p("系统解码", "System");
        p("硬解优先", "Hardware first"); p("本地解码", "Local decode"); p("强制", "Force");
        p("播放切换", "Player switch"); p("临时启动一次", "One-time launch");
        p("临时切到 ExoPlayer", "Using ExoPlayer once"); p("临时切到 libVLC", "Using libVLC once");
        p("当前已经在用 ExoPlayer", "Already using ExoPlayer"); p("当前已经在用 libVLC", "Already using libVLC");
        p("第三方播放器", "Third-party player"); p("第三方", "Third party");
        p("当贝 / Kodi / MX", "Dangbei / Kodi / MX");
        p("第三方播放器（当贝 / Kodi / MX）", "Third-party player (Dangbei / Kodi / MX)");
        p("选择播放器", "Choose a player"); p("仅此一次", "Just once");
        p("始终使用（记住，设置页可清除）", "Always (remembered; clearable in Settings)");
        p("没有可用的第三方播放器", "No third-party players available");
        p("清除第三方播放器初始化（当前：未选择）", "Reset third-party player (current: none)");
        p("清除第三方播放器初始化（当前：", "Reset third-party player (current: ");
        p("已清除，下次用第三方播放器会重新让你选", "Cleared — you'll be asked again next time");
        p("用 ", "Play with "); p("临时改用第三方播放器", "Using a third-party player for now");
        p("转码设置", "Transcoding"); p("转码", "Transcode"); p("直连", "Direct"); p("自动直连", "Direct (auto)");
        p("转码开关", "Transcoding switch"); p("转码分辨率：", "Transcode resolution: ");
        p("转码码率：", "Transcode bitrate: "); p("分辨率", "Resolution"); p("码率", "Bitrate");
        p("比特率", "Bitrate"); p("不限码率", "Unlimited bitrate"); p("原码率", "Original bitrate");
        p("已切回原码率直连", "Back to the original bitrate (direct)");
        p("已开启服务器转码", "Server transcoding is on"); p("重新转码中…", "Restarting the transcode…");
        p("转码流 seek → 从 ", "Transcode seek → from "); p("ms 重新起转", " ms, restarting");
        p("字幕设置", "Subtitles"); p("字幕", "Subtitles"); p("字幕 ", "Subtitles ");
        p("内嵌字幕", "Embedded"); p("内嵌字幕：", "Embedded: "); p("外挂字幕", "External");
        p("外挂字幕 ", "External "); p("无内嵌", "No embedded"); p("内嵌", "Embedded"); p("外挂", "External");
        p("关闭内嵌字幕", "Turn embedded subtitles off"); p("字幕开关", "Subtitle switch");
        p("字幕已关闭", "Subtitles off"); p("字幕已打开", "Subtitles on");
        p("内嵌字幕已关闭", "Embedded subtitles off");
        p("没有字幕轨道", "No subtitle tracks"); p("这条片子没有内嵌字幕", "This video has no embedded subtitles");
        p("这条片子没有内嵌字幕，只有外挂的", "This video has no embedded subtitles, only external ones");
        p("同目录没有外挂字幕", "No external subtitles in this folder");
        p("这条内嵌字幕服务器导不出来（EIA-608 / PGS 图形字幕）", "The server can't export this embedded track (EIA-608 / PGS bitmap)");
        p("字幕下载失败", "Subtitle download failed"); p("切换内嵌字幕失败：", "Failed to switch the embedded track: ");
        p("读取字幕轨…", "Loading subtitle tracks…"); p("选择字幕", "Choose subtitles");
        p("字幕同步 ", "Subtitle sync "); p("同步", "Sync"); p("字号", "Size");
        p("字幕字号：", "Subtitle size: "); p("字幕颜色：", "Subtitle colour: "); p("颜色", "Colour");
        p("白色", "White"); p("黄色", "Yellow"); p("绿色", "Green"); p("青色", "Cyan"); p("紫色", "Purple");
        p("红色", "Red"); p("橙色", "Orange"); p("灰", "Grey");
        p("自动字幕", "Auto subtitles"); p("默认音轨", "Default audio");
        p("音频设置", "Audio"); p("音轨", "Audio track"); p("音轨：", "Audio: "); p("音轨 ", "Audio ");
        p("没有可选音轨", "No audio tracks"); p("读取音轨…", "Loading audio tracks…");
        p("选择音轨", "Choose an audio track"); p("声道", "Channels"); p("音频频道", "Audio channels");
        p("显示设置", "Display"); p("原画", "Original"); p("比例", "Aspect"); p("宽高比", "Aspect ratio");
        p("全屏", "Full screen"); p("自适应", "Fit"); p("填充", "Fill");
        p("倍速播放", "Playback speed"); p("倍速 ", "Speed "); p("设置倍速 ", "Set speed to ");
        p("播放模式", "Play mode"); p("播放模式：", "Play mode: "); p("单片播放", "Single video");
        p("单片循环", "Repeat one"); p("列表播放", "Playlist"); p("列表循环", "Repeat playlist");
        p("播放统计", "Playback stats"); p("播放信息", "Playback info"); p("流媒体信息", "Stream info");
        p("原始媒体信息", "Original media info"); p("流媒体类型", "Stream type");
        p("播放方式", "Playback method"); p("没有可用的统计信息", "No stats available");
        p("视频编解码器", "Video codec"); p("音频编解码器", "Audio codec"); p("视频分辨率", "Video resolution");
        p("视频比特率", "Video bitrate"); p("音频比特率", "Audio bitrate"); p("总码率", "Total bitrate");
        p("帧率", "Frame rate"); p("位深", "Bit depth"); p("采样率", "Sample rate"); p("编码", "Codec");
        p("编码器", "Encoder"); p("解码器", "Decoder"); p("媒体容器", "Container"); p("容器 ", "Container ");
        p("参考帧", "Ref frames"); p("像素格式", "Pixel format"); p("色彩空间", "Colour space");
        p("动态范围", "Dynamic range"); p("HDR 支持", "HDR"); p("隔行", "Interlaced"); p("原生", "Native");
        p("音频输出错误: ", "Audio output error: "); p("音频欠载 buf=", "Audio underrun buf=");
        p("音量", "Volume"); p("播放结束", "Playback finished"); p("空闲", "Idle"); p("关闭", "Off");

        // ---- 播放器提示 ----
        p("这个片源 Exo 加载不动，改用 libVLC", "ExoPlayer can't load this file — switching to libVLC");
        p("这个片源 ExoPlayer 解不了，已临时改用 libVLC", "ExoPlayer can't decode this file — switched to libVLC");
        p("这个编码 FFmpeg 解不了，改用 libVLC", "FFmpeg can't decode this codec — switching to libVLC");
        p("硬解失败 → 改用 FFmpeg 纯软解重试", "Hardware decode failed — retrying with FFmpeg software decode");
        p("硬解失败重试", "Retrying with software decode");
        p("硬解不支持，改用 FFmpeg 软解", "Hardware decode unsupported — using FFmpeg software decode");
        p("FFmpeg 扩展不可用，只有 MediaCodec", "FFmpeg extension unavailable — MediaCodec only");
        p("FFmpeg 扩展已加载: ", "FFmpeg extension loaded: ");
        p("FFmpeg 无此视频解码器 → 回退 libVLC", "FFmpeg has no decoder for this video → falling back to libVLC");
        p("系统播放器解析不到，切到 libVLC 引擎可用", "The system player can't parse it — libVLC can play it");
        p("强制 ExoPlayer 但本片解不了，仍然回退 libVLC", "ExoPlayer was forced but can't decode this — still falling back to libVLC");
        p("卡缓冲 ", "Stalled ");
        p("秒无数据 → 回退 libVLC", "s with no data → falling back to libVLC");
        p("STRM 直链播放", "STRM direct links");
        p("‹  返回", "‹  Back"); p("‹ 返回", "‹ Back"); p("返回", "Back");
        p("可移动", "Move mode"); p("可缩放", "Zoom mode"); p("音量", "Volume");
        p("（播放时可继续切换）", "(you can keep switching during playback)");
        p("再点一次当前选项可切换升序 / 降序", "Press again on the current option to toggle asc/desc");

        // ---- 语言名 ----
        p("中文", "Chinese"); p("简体中文", "Simplified Chinese"); p("繁体中文", "Traditional Chinese");
        p("简中", "SC"); p("繁中", "TC"); p("简体", "Simplified"); p("繁体", "Traditional");
        p("英语", "English"); p("日语", "Japanese"); p("韩语", "Korean"); p("法语", "French");
        p("德语", "German"); p("俄语", "Russian"); p("西班牙语", "Spanish"); p("葡萄牙语", "Portuguese");
        p("意大利语", "Italian"); p("泰语", "Thai"); p("越南语", "Vietnamese"); p("阿拉伯语", "Arabic");
        p("印地语", "Hindi");
        p("跟随系统", "Follow system"); p("跟随全局设置", "Follow global");
        p("全部媒体库改为跟随全局", "Set all libraries to follow global");
        p("只对当前媒体库生效，其它库不受影响", "Affects only this library");
        p("已重置 · 全部跟随全局", "Reset — everything follows global");
        p("已选择：", "Selected: "); p("当前：", "Current: "); p("（默认", " (default");
        p("自动选择（推荐）", "Auto (recommended)");
        p("根据播放器当前自动选择", "Auto-detect from the player");
        p("强制 ExoPlayer 内核", "Force ExoPlayer"); p("强制 libVLC 内核", "Force libVLC");
        p("强制转码 ", "Force transcode ");
        p("硬解优先", "Hardware first");
        p("自动：ExoPlayer 硬解为主，硬解失败转 FFmpeg 软解，容器不支持才切 libVLC，进度自动接续。强制内核始终用指定内核，解不了只提示、不再切换。",
          "Auto: ExoPlayer hardware decode first, FFmpeg software decode on failure, libVLC only when the container isn't supported; progress carries over. Forced kernels are always used, with a hint instead of switching.");
        p("（默认", " (default");

        // ---- 补漏：单位与拼接片段 ----
        p(" 当前=", " now="); p(" 名称=", " name="); p(" 实际=", " actual=");
        p(" 个", ""); p("个", ""); p("个视频", "videos"); p(" 秒", "s"); p("秒", "s");
        p(" 张照片", " photos"); p(" 张图片", " pictures"); p(" 条结果", " results"); p("项", "items");
        p(" 个文件夹 · ", " folders · "); p(" 个视频 · 已载入 ", " videos · loaded ");
        p(" 张照片 · 已载入 ", " photos · loaded "); p(" 个项目", " items"); p(" 集", "");
        p(" 第 ", " · Ep. "); p("· 第 ", "· Ep. "); p("· 图片", "· Pictures"); p("· 字幕 ", "· Subtitles ");
        p("· 总码率 ", "· Total bitrate "); p("· 直连", "· Direct"); p("· 转码", "· Transcode");
        p("排序 · ", "Sort · "); p("本库缩略图 · ", "Library thumbnails · ");
        p("  未登录", "  Not signed in"); p("   正在读取收藏…", "   Loading favorites…");
        p(" 播放", " Play"); p("直接播放", "Play directly"); p("可选", "Optional"); p("删除", "Delete");
        p("已加入收藏", "Added to favorites"); p("已取消收藏", "Removed from favorites");
        p("已加载字幕 · ", "Subtitles loaded · "); p("加载失败 ", "Load failed ");
        p("启动失败：", "Launch failed: "); p("切换到 ", "Switching to "); p("正在连接 ", "Connecting to ");
        p("找到 ", "Found "); p("没有找到 ", "Nothing found for ");
        p("还差两项", "Two fields left"); p("电视没准备好", "The TV isn't ready");
        p("已发送到电视", "Sent to the TV"); p("已标记为已观看", "Marked as watched");
        p("已取消观看标记", "Marked as unwatched");
        p("海报", "Poster"); p("画面：", "Picture: "); p("格式：", "Format: "); p("分辨率 ", "Resolution ");
        p("码率 ", "Bitrate "); p("大小 ", "Size "); p("时长 ", "Duration "); p("版本 ", "Version ");
        p("标题", "Title"); p("隐藏", "Hidden"); p("外部", "External");
        p("字幕 (", "Subtitles ("); p("字幕：", "Subtitles: "); p("字幕 ", "Subtitles "); p("字幕", "Subtitles");
        p("图形字幕 ", "Bitmap subtitles "); p("ASS 字幕 ", "ASS subtitles "); p("内嵌字幕 ", "Embedded ");
        p("同一文件的其他版本", "Other versions of this file"); p("选择版本", "Choose a version");
        p("家庭视频", "Home videos"); p("调试 进入服务器扫描页", "Debug: open the server scan page");
        p("仅直连（不使用代理）", "Direct only (no proxy)"); p("使用 Emby", "Use Emby");
        p("信任所有 SSL 证书（不安全）", "Trust all SSL certificates (unsafe)");
        // 字幕描边/样式
        p("双线", "Double"); p("四角", "Corners"); p("外发光", "Glow"); p("渐变边", "Fade edge");
        p("玻璃遮罩", "Glass mask"); p("虚线", "Dotted"); p("粗描边", "Thick outline"); p("细描边", "Thin outline");
        p("白", "White"); p("橙", "Orange"); p("紫", "Purple"); p("绿", "Green"); p("青", "Cyan"); p("黄", "Yellow");
        p("硬件", "Hardware"); p("软件", "Software"); p("系统", "System"); p("视频", "Video"); p("音频", "Audio");
        p("软件解码", "Software decode");
        // 背景主题
        p("玻璃风", "Glass"); p("玻璃", "Glass"); p("炫酷", "Cool"); p("星空", "Starfield"); p("极光", "Aurora"); p("散景", "Bokeh");
        p("深海", "Deep sea"); p("烟云", "Smoke"); p("霓虹", "Neon"); p("星云", "Nebula");
        p("森林", "Forest"); p("沙丘", "Dunes"); p("暗金", "Dark gold");
        // 日志（不可见，保持结构）
        p("VLC 设置倍速 ", "VLC set speed "); p("VLC 倍速观察 目标=", "VLC speed watch target=");
        p("VLC 内嵌自动测试 已选 id=", "VLC embedded auto-test picked id=");
        p("VLC 内嵌自动测试 轨数=", "VLC embedded auto-test tracks=");
        p("VLC 转码流 seek → 从 ", "VLC transcode seek → from ");
        p("VLC 音轨切换到 idx=", "VLC audio switched to idx=");
        p("内嵌字幕切换到 idx=", "Embedded subtitle switched to idx=");
        p("prepare 已调用 url=", "prepare called url="); p("seek轨迹 t+", "seek trace t+");
        p("sys seek 跳过 t=", "sys seek skipped t="); p("丢帧 dropped=", "dropped frames dropped=");
        p("倍速观察 目标=", "Speed watch target="); p("倍速观察(后段) 目标=", "Speed watch (late) target=");
        p("内嵌自动测试 5s 后 cueView 字幕=", "Embedded auto-test: cueView after 5s =");
        p("内嵌自动测试 已选第 0 条，当前=", "Embedded auto-test picked #0, current=");
        p("内嵌自动测试 轨数=", "Embedded auto-test tracks=");
        p("自动内嵌测试 spu 轨数=", "Embedded auto-test spu tracks=");
        p("自动内嵌测试 已选 id=", "Embedded auto-test picked id=");
        p("区域解码 源=", "Region decode source="); p(" 倍数=", " mult="); p(" 入口按钮=", " entry button=");
        p(" 区域=", " region="); p(" 块=", " blocks="); p(" 屏=", " screens=");
        p(" 当前组行数=", " current group rows="); p(" 目标=", " target="); p(" 类型=", " type=");
        p(" 耗时=", " elapsed="); p(" 顶部=", " top="); p(" 首个=", " first=");
        p(" 通道=", " channels="); p(" L2组=", " L2 group=");
        p("广播发现 数量=", "Broadcast discovery count="); p("广播发现 服务器=", "Broadcast discovery server=");
        p("扫描局域网 完成 命中=", "LAN scan done, hits="); p("扫描局域网 服务器=", "LAN scan server=");
        p("扫描局域网 本机=", "LAN scan local=");
        p("扫描局域网 本机没有局域网 IP", "LAN scan: no local IP");
        p("照片墙 库=", "Photo wall library="); p("照片墙 数量=", "Photo wall count=");
        p("照片墙 没有调试凭证", "Photo wall: no debug token");
        p("照片墙 补全完成 数量=", "Photo wall fill done, count=");
        p("照片墙 载入完成 数量=", "Photo wall load done, count=");
        p("自动看图 没有调试凭证", "Auto preview: no debug token");
        p("自动看图 源=", "Auto preview source=");
        p("自动看图兜底会话已建立", "Auto preview fallback session established");
        p("自动看图失败 photoPv=null", "Auto preview failed, photoPv=null");
        p("统计面板 段=", "Stats panel lines="); p("统计面板尺寸 ", "Stats panel size ");
        p("菜单动作 act=", "Menu action act="); p("菜单已建 L1=", "Menu built L1=");
        p("轨道 type=", "Track type="); p("秒无数据 → 回退 libVLC", "s with no data → falling back to libVLC");
        p("→ FFmpeg 纯软解", "→ FFmpeg software only");
        p("→ MediaCodec 优先 + FFmpeg 兜底", "→ MediaCodec first, FFmpeg fallback");

        // ---- 扫码页 HTML（手机端表单） ----
        p("' placeholder='我的 Emby 服务器'>", "' placeholder='My Emby server'>");
        p("' placeholder='用户名' autocapitalize='off' autocorrect='off'>", "' placeholder='Username' autocapitalize='off' autocorrect='off'>");
        p("<button type='submit'>发送到电视</button>", "<button type='submit'>Send to TV</button>");
        p("<div class='err'>服务器地址和用户名都要填</div>", "<div class='err'>Server address and username are required</div>");
        p("<div class='err'>电视当前不在登录页<br>请先在电视上打开「手动输入」页，再点「发送到电视」</div>", "<div class='err'>The TV isn't on the sign-in page<br>Open \"Enter manually\" on the TV first, then tap \"Send to TV\"</div>");
        p("<div class='lb'>名称（可选）</div>", "<div class='lb'>Name (optional)</div>");
        p("<div class='lb'>密码</div>", "<div class='lb'>Password</div>");
        p("<div class='lb'>服务器地址</div>", "<div class='lb'>Server address</div>");
        p("<div class='lb'>用户名</div>", "<div class='lb'>Username</div>");
        p("<div class='ok'>已发送到电视，登录表单自动填好了<br>", "<div class='ok'>Sent to the TV — the sign-in form is filled in<br>");
        p("<div class='tip'>填好下面几项，点「发送到电视」<br>电视上的登录表单会自动填好，你只需点「保存」</div>", "<div class='tip'>Fill in the fields below and tap \"Send to TV\"<br>The TV form fills itself in — you only tap \"Save\"</div>");
        p("<input type='password' name='pass' placeholder='密码'>", "<input type='password' name='pass' placeholder='Password'>");
        p("<span style='color:#a9a49c;font-size:12px'>在电视上检查一下，点「保存」连接</span></div>", "<span style='color:#a9a49c;font-size:12px'>Check it on the TV, then tap \"Save\" to connect</span></div>");

        // ---- 带前导空格的拼接片段（缩进/空格是原文的一部分） ----
        p(" 声道", " channels"); p(" 个视频", " videos"); p("解码方式=", "decoder=");
        p("   未登录", "   Not signed in");
        p(" · 图片", " · Pictures"); p(" · 字幕 ", " · Subtitles "); p(" · 总码率 ", " · Total bitrate ");
        p(" · 直连", " · Direct"); p(" · 第 ", " · Ep. "); p(" · 转码", " · Transcode");
        p(" → FFmpeg 纯软解", " → FFmpeg software only");
        p(" → MediaCodec 优先 + FFmpeg 兜底", " → MediaCodec first, FFmpeg fallback");
        p(" 秒无数据 → 回退 libVLC", " s with no data → falling back to libVLC");
        p(" 项", " items"); p("(默认", " (default");
        p("编码 ", "Codec "); p("视频  ", "Video  "); p("音频  ", "Audio  ");
        // 常见剩余单词
        p("声道", "Channels"); p("项", "items"); p("个视频", "videos");
        p("界面语言", "Interface language");
        p("从头播放", "Play from start"); p("续播", "Resume");
        p("暂无续播进度", "Nothing to resume"); p("已观看", "Watched");
    }
}
