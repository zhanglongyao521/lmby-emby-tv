package com.longyao.anbitv;

import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;

/** Emby REST 客户端（只依赖平台 org.json / HttpURLConnection，兼容 Android 4.2+） */
public class Api {
    public static class Session {
        public String server, token, userId, userName, deviceId;

        public String auth() {
            return "MediaBrowser Client=\"Emby TV\", Device=\"AndroidTV\", DeviceId=\"" + deviceId
                    + "\", Version=\"1.0.0\", Token=\"" + token + "\"";
        }
    }

    public static class Lib {
        public String id, name, type;
    }

    public static class Item {
        public String id, name, series, imgTag, type, parentId, seriesId = "";
        public int width, height, index, year;
        /** 所属季号（剧集条目才有：ParentIndexNumber） */
        public int seasonNo;
        /** 刮削海报的真实宽高比（宽/高），0 表示服务器没给；用它让卡片形状贴合海报 */
        public double posterAspect;
        public long duration;
        public double progress;
        /** 服务器记录的续播位置（毫秒），来自 UserData.PlaybackPositionTicks */
        public long resumeMs;
        public String container = "", path = "", overview = "";
        /** 家长分级，如 PG-13 / 未分级 */
        public String officialRating = "";
        /** 发行日期（原样字符串，本地排序用） */
        public String premiereDate = "";
        public long size;
        public int bitrate;
        public boolean favorite;
        /** 服务器是否已标记为已观看（UserData.Played） */
        public boolean played;
        /** 最近一次播放时间（UserData.LastPlayedDate，ISO8601 UTC，字典序可直接比大小） */
        public String lastPlayed = "";
        /** 横版剧照（fanart）的 tag，空表示这条没有 */
        public String backdropTag = "";
        /** 视频缩略图（抽帧）的 tag，空表示这条没有；有的话集卡/选集条用它，海报仍用 Primary */
        public String thumbTag = "";

        /** 缩略图右上角的播放进度角标：只看真实进度（没看到 100% 就显示，看完返回空串） */
        public String pctBadge() {
            if (resumeMs > 0 && duration > 0) {
                int p = (int) Math.round(100.0 * resumeMs / duration);
                if (p >= 1 && p < 99) return p + "%";
            }
            if (progress >= 1 && progress < 99.5) {
                int p = (int) Math.round(progress);
                if (p < 100) return Math.max(1, p) + "%";
            }
            return "";
        }

        /** 社区评分（IMDb 分），0 表示这条没有评分 */
        public double rating;
        /** 剧集总集数（Series 条目才有，其余为 0） */
        public int childCount;

        public String videoTitle = "", audioTitle = "", genres = "";
        public String videoCodec = "";
        public int resHeight, audioChannels;
        public int audioTracks, subTracks;

        // 详情页「媒体信息」用的明细字段
        public String dateCreated = "";
        public String vProfile = "", vLevel = "", vPixelFormat = "", vRange = "",
                vColorSpace = "", vAspect = "";
        public int vBitRate, vFps, vBitDepth, vRefFrames;
        public boolean vInterlaced;
        public String aCodec = "", aProfile = "";
        public int aBitRate, aSampleRate, aBitDepth;
        public boolean aDefault = true;
        public String subCodec = "", subDisplay = "";
        public boolean subDefault, subForced, subExternal;
        public int sourceCount = 1;

        /** 4K / 1080P / 720P 徽章 */
        public String resLabel() {
            int h = resHeight > 0 ? resHeight : height;
            if (h >= 2000) return "4K";
            if (h >= 1000) return "1080P";
            if (h >= 700) return "720P";
            return h > 0 ? h + "P" : "";
        }

        /** AAC STEREO 这类音频徽章 */
        public String audioChip() {
            String codec = "";
            if (!audioTitle.isEmpty()) {
                for (String w : audioTitle.split(" ")) {
                    if (w.length() <= 4 && w.equals(w.toUpperCase())) { codec = w; break; }
                }
                if (codec.isEmpty()) codec = audioTitle.split(" ")[0].toUpperCase();
            }
            String ch = "";
            if (audioChannels == 2) ch = "STEREO";
            else if (audioChannels == 6) ch = "5.1";
            else if (audioChannels == 8) ch = "7.1";
            else if (audioChannels > 0) ch = audioChannels + "CH";
            return (codec + " " + ch).trim();
        }

        public String aspectString() {
            if (width <= 0 || height <= 0) return "16:9";
            return width + ":" + height;
        }
    }

    public static String normalize(String s) {
        s = s == null ? "" : s.trim();
        if (s.isEmpty()) return s;
        if (!s.startsWith("http://") && !s.startsWith("https://")) s = "http://" + s;
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        return s;
    }

    private static String read(InputStream in) throws Exception {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
        return bo.toString("UTF-8");
    }

    /** 仅直连：不走系统代理（登录页「仅直连」开关） */
    public static boolean DIRECT_ONLY = false;

    private static JSONObject call(String url, String method, String body, String header) throws Exception {
        java.net.URLConnection uc = DIRECT_ONLY
                ? new URL(url).openConnection(java.net.Proxy.NO_PROXY)
                : new URL(url).openConnection();
        HttpURLConnection c = (HttpURLConnection) uc;
        c.setConnectTimeout(10000);
        c.setReadTimeout(25000);
        c.setRequestMethod(method);
        c.setRequestProperty("Accept", "application/json");
        c.setRequestProperty("Content-Type", "application/json;charset=UTF-8");
        if (header != null) c.setRequestProperty("X-Emby-Authorization", header);
        if (body != null) {
            c.setDoOutput(true);
            OutputStream os = c.getOutputStream();
            os.write(body.getBytes("UTF-8"));
            os.flush();
            os.close();
        }
        int code = c.getResponseCode();
        InputStream in = code >= 200 && code < 300 ? c.getInputStream() : c.getErrorStream();
        String text = in == null ? "" : read(in);
        if (code < 200 || code >= 300) throw new Exception("HTTP " + code + (text.isEmpty() ? "" : " " + text));
        return text.isEmpty() ? new JSONObject() : new JSONObject(text);
    }

    /** 把图片下载到本地文件（缓存用户头像，下次启动不依赖 token） */
    public static boolean downloadFile(String url, String header, java.io.File out) throws Exception {
        java.net.URLConnection uc = DIRECT_ONLY
                ? new URL(url).openConnection(java.net.Proxy.NO_PROXY)
                : new URL(url).openConnection();
        HttpURLConnection c = (HttpURLConnection) uc;
        c.setConnectTimeout(10000);
        c.setReadTimeout(20000);
        if (header != null) c.setRequestProperty("X-Emby-Authorization", header);
        if (c.getResponseCode() != 200) return false;
        InputStream in = c.getInputStream();
        java.io.FileOutputStream fo = new java.io.FileOutputStream(out);
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) fo.write(buf, 0, n);
        fo.flush();
        fo.close();
        in.close();
        return true;
    }

    public static Session login(String server, String user, String pass, String deviceId) throws Exception {
        Session s = new Session();
        s.server = normalize(server);
        s.userName = user;
        s.deviceId = deviceId;
        String h = "MediaBrowser Client=\"Emby TV\", Device=\"AndroidTV\", DeviceId=\"" + deviceId
                + "\", Version=\"1.0.0\"";
        JSONObject body = new JSONObject();
        body.put("Username", user);
        body.put("Pw", pass);
        JSONObject res = call(s.server + "/Users/AuthenticateByName", "POST", body.toString(), h);
        s.token = res.optString("AccessToken");
        JSONObject u = res.optJSONObject("User");
        if (u != null) {
            s.userId = u.optString("Id");
            s.userName = u.optString("Name", user);
        }
        if (s.token == null || s.token.isEmpty() || s.userId == null || s.userId.isEmpty())
            throw new Exception("登录返回异常");
        return s;
    }

    /**
     * Emby Connect 登录：
     * 1) 用 Connect 账号（邮箱）向 connect.emby.media 换取 ExchangeToken
     * 2) 拿 token 回本地服务器 POST /Users/Connect 换正式的 AccessToken
     */
    public static Session connectLogin(String server, String email, String pass, String deviceId) throws Exception {
        Session s = new Session();
        s.server = normalize(server);
        s.deviceId = deviceId;
        String h = "MediaBrowser Client=\"Emby TV\", Device=\"AndroidTV\", DeviceId=\"" + deviceId
                + "\", Version=\"1.0.0\"";

        String token = null;
        String connectId = null;
        String connectName = email;
        try {
            String q = "?username=" + java.net.URLEncoder.encode(email, "UTF-8")
                    + "&password=" + java.net.URLEncoder.encode(pass, "UTF-8");
            JSONObject r = call("https://connect.emby.media/service/User/authenticate" + q, "GET", null, null);
            token = r.optString("ExchangeToken", "");
            connectId = r.optString("Id", "");
            JSONObject uu = r.optJSONObject("User");
            if (uu != null) {
                if (token == null || token.isEmpty()) token = uu.optString("ExchangeToken", "");
                if (connectId == null || connectId.isEmpty()) connectId = uu.optString("Id", "");
                String nm = uu.optString("Name", "");
                if (nm != null && !nm.isEmpty()) connectName = nm;
            }
        } catch (Exception e) {
            String raw = e.getMessage() == null ? "" : e.getMessage();
            if (raw.startsWith("HTTP 401")) throw new Exception("Emby Connect 账号或密码错误");
            if (raw.contains("UnknownHost") || raw.contains("timed out") || raw.contains("timeout"))
                throw new Exception("连不上 Emby Connect 服务，请检查网络");
            throw new Exception("Emby Connect 登录失败: " + raw);
        }
        if (token == null || token.isEmpty()) throw new Exception("Emby Connect 没有返回凭证，请先在网页端登录一次");

        JSONObject body = new JSONObject();
        body.put("ConnectUserName", email);
        body.put("Password", token);
        if (connectId != null && !connectId.isEmpty()) body.put("ConnectUserId", connectId);

        JSONObject res;
        try {
            res = call(s.server + "/Users/Connect", "POST", body.toString(), h);
        } catch (Exception e) {
            String raw = e.getMessage() == null ? "" : e.getMessage();
            if (raw.startsWith("HTTP 400") || raw.startsWith("HTTP 401"))
                throw new Exception("服务器拒绝了 Connect 登录，请确认服务器已开启 Emby Connect 且账号已关联");
            throw e;
        }
        s.token = res.optString("AccessToken");
        JSONObject u = res.optJSONObject("User");
        if (u != null) {
            s.userId = u.optString("Id");
            s.userName = u.optString("Name", connectName);
        }
        if (s.token == null || s.token.isEmpty() || s.userId == null || s.userId.isEmpty())
            throw new Exception("Connect 登录返回异常");
        return s;
    }

    public static List<Lib> libraries(Session s) throws Exception {
        List<Lib> out = new ArrayList<Lib>();
        JSONObject res = call(s.server + "/Users/" + s.userId + "/Views", "GET", null, s.auth());
        JSONArray arr = res.optJSONArray("Items");
        if (arr == null) return out;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.getJSONObject(i);
            String type = o.optString("CollectionType", "");
            if ("playlists".equals(type)) continue;
            Lib l = new Lib();
            l.id = o.optString("Id");
            l.name = o.optString("Name");
            l.type = type;
            out.add(l);
        }
        return out;
    }

    private static Item toItem(JSONObject o) {
        Item it = new Item();
        it.id = o.optString("Id");
        it.name = o.optString("Name");
        it.type = o.optString("Type");
        it.parentId = o.optString("ParentId");
        it.width = o.optInt("Width", 0);
        it.height = o.optInt("Height", 0);
        it.duration = o.optLong("RunTimeTicks", 0) / 10000L;
        it.series = o.optString("SeriesName", "");
        if ("null".equals(it.series)) it.series = "";
        it.index = o.optInt("IndexNumber", 0);
        it.seasonNo = o.optInt("ParentIndexNumber", 0);
        it.year = o.optInt("ProductionYear", 0);
        it.rating = o.optDouble("CommunityRating", 0);
        it.childCount = o.optInt("RecursiveItemCount", 0);
        if (it.childCount <= 0) it.childCount = o.optInt("ChildCount", 0);
        JSONObject tag = o.optJSONObject("ImageTags");
        it.imgTag = tag == null ? "" : tag.optString("Primary", "");
        it.thumbTag = tag == null ? "" : tag.optString("Thumb", "");
        if ("null".equals(it.thumbTag)) it.thumbTag = "";
        it.posterAspect = o.optDouble("PrimaryImageAspectRatio", 0);
        JSONArray bd = o.optJSONArray("BackdropImageTags");
        it.backdropTag = (bd == null || bd.length() == 0) ? "" : bd.optString(0, "");
        it.overview = o.optString("Overview", "");
        String sid = o.optString("SeriesId", "");
        if (!"null".equals(sid) && !"".equals(sid)) it.seriesId = sid;
        if ("null".equals(it.overview)) it.overview = "";
        JSONObject ud = o.optJSONObject("UserData");
        if (ud != null) {
            it.progress = ud.optDouble("PlayedPercentage", 0);
            it.resumeMs = ud.optLong("PlaybackPositionTicks", 0L) / 10000L;
            it.played = ud.optBoolean("Played", false);
            it.favorite = ud.optBoolean("IsFavorite", false);
            String lp = ud.optString("LastPlayedDate", "");
            if (!"null".equals(lp)) it.lastPlayed = lp;
        }
        JSONArray ms = o.optJSONArray("MediaSources");
        if (ms != null && ms.length() > 0) {
            JSONObject m = ms.optJSONObject(0);
            if (m != null) {
                it.size = m.optLong("Size", 0);
                it.bitrate = m.optInt("Bitrate", 0);
                it.container = m.optString("Container", "");
                String pth = m.optString("Path", "");
                if (!"null".equals(pth)) it.path = pth;
                String dc = m.optString("DateCreated", "");
                if (!"null".equals(dc)) it.dateCreated = dc;
            }
            it.sourceCount = ms.length();
        }
        if (it.container.isEmpty()) it.container = o.optString("Container", "");
        String or = o.optString("OfficialRating", "");
        if (!"null".equals(or)) it.officialRating = or;
        String pd = o.optString("PremiereDate", "");
        if (!"null".equals(pd)) it.premiereDate = pd;
        JSONArray gs = o.optJSONArray("Genres");
        if (gs != null) {
            StringBuilder gb = new StringBuilder();
            for (int i = 0; i < gs.length() && i < 2; i++) {
                if (gb.length() > 0) gb.append(" · ");
                gb.append(gs.optString(i));
            }
            it.genres = gb.toString();
        }
        parseStreams(it, o.optJSONArray("MediaStreams"));
        if (ms != null && ms.length() > 0) {
            JSONObject m0 = ms.optJSONObject(0);
            if (m0 != null) parseStreams(it, m0.optJSONArray("MediaStreams"));
        }
        return it;
    }

    private static void parseStreams(Item it, JSONArray st) {
        if (st == null) return;
        for (int i = 0; i < st.length(); i++) {
            JSONObject s2 = st.optJSONObject(i);
            if (s2 == null) continue;
            String type = s2.optString("Type", "");
            if ("Video".equals(type)) {
                String dt = s2.optString("DisplayTitle", "");
                if (!dt.isEmpty() && !"null".equals(dt)) it.videoTitle = dt;
                int hh = s2.optInt("Height", 0);
                if (hh > it.resHeight) it.resHeight = hh;
                String cod = s2.optString("Codec", "");
                if (!cod.isEmpty() && !"null".equals(cod)) it.videoCodec = cod.toUpperCase();
                it.vProfile = s2.optString("Profile", "");
                it.vLevel = s2.optString("Level", "");
                it.vBitRate = s2.optInt("BitRate", 0);
                double fps = s2.optDouble("RealFrameRate", 0);
                if (fps <= 0) fps = s2.optDouble("AverageFrameRate", 0);
                it.vFps = (int) Math.round(fps);
                if (it.vFps <= 0) it.vFps = s2.optInt("FrameRate", 0);
                it.vBitDepth = s2.optInt("BitDepth", 0);
                it.vPixelFormat = s2.optString("PixelFormat", "");
                it.vRange = s2.optString("VideoRange", "");
                it.vColorSpace = s2.optString("ColorSpace", "");
                it.vAspect = s2.optString("AspectRatio", "");
                it.vRefFrames = s2.optInt("RefFrames", 0);
                it.vInterlaced = s2.optBoolean("IsInterlaced", false);
            } else if ("Subtitle".equals(type)) {
                if (it.subTracks == 0) {
                    it.subCodec = s2.optString("Codec", "").toUpperCase();
                    String sdt = s2.optString("DisplayTitle", "");
                    it.subDisplay = "null".equals(sdt) ? "" : sdt;
                    it.subDefault = s2.optBoolean("Default", false);
                    it.subForced = s2.optBoolean("Forced", false);
                    it.subExternal = s2.optBoolean("IsExternal", false);
                }
                it.subTracks++;
            } else if ("Audio".equals(type)) {
                String dt = s2.optString("DisplayTitle", "");
                if (!dt.isEmpty() && !"null".equals(dt)) it.audioTitle = dt;
                it.audioTracks++;
                int ch = s2.optInt("Channels", 0);
                if (ch > it.audioChannels) it.audioChannels = ch;
                if (it.audioTracks == 1) {
                    it.aCodec = s2.optString("Codec", "").toUpperCase();
                    it.aProfile = s2.optString("Profile", "");
                    it.aBitRate = s2.optInt("BitRate", 0);
                    it.aSampleRate = s2.optInt("SampleRate", 0);
                    it.aBitDepth = s2.optInt("BitDepth", 0);
                    it.aDefault = s2.optBoolean("Default", true);
                }
            }
        }
    }

    /** 分页结果：本页条目 + 服务器上的总数（用于"先出首屏、再补其余"） */
    public static class Page {
        public List<Item> items = new ArrayList<Item>();
        public int total;
    }

    /** recursive=true 取视频；false 取直接子节点（文件夹）。start/limit 支持分页，先拉首屏再补全 */
    public static Page page(Session s, String parentId, boolean recursive, int start, int limit)
            throws Exception {
        return page(s, parentId, recursive, start, limit, null, false);
    }

    /** 带排序的版本：sortBy=null 时按名称；desc 控制升降序 */
    public static Page page(Session s, String parentId, boolean recursive, int start, int limit,
                            String sortBy, boolean desc) throws Exception {
        return page(s, parentId, recursive, start, limit, sortBy, desc, null);
    }

    /** extraFields 非空时追加到 Fields，用于按容器 / 文件大小 / 分级这类本地排序要用的字段 */
    public static Page page(Session s, String parentId, boolean recursive, int start, int limit,
                            String sortBy, boolean desc, String extraFields) throws Exception {
        return page(s, parentId, recursive, start, limit, sortBy, desc, extraFields, null);
    }

    /** itemTypes 非空时覆盖默认的 IncludeItemTypes（剧集库要只取 Series 就靠它） */
    public static Page page(Session s, String parentId, boolean recursive, int start, int limit,
                            String sortBy, boolean desc, String extraFields, String itemTypes)
            throws Exception {
        StringBuilder u = new StringBuilder(s.server).append("/Users/").append(s.userId).append("/Items?");
        u.append("ParentId=").append(Uri.encode(parentId)).append("&Recursive=").append(recursive);
        String extra = (extraFields == null || extraFields.isEmpty()) ? "" : "," + extraFields;
        if (recursive) {
            String types = (itemTypes == null || itemTypes.isEmpty())
                    ? "Movie,Episode,Video,MusicVideo,TvChannel" : itemTypes;
            u.append("&IncludeItemTypes=").append(types)
             .append("&Fields=Width,Height,RunTimeTicks,SeriesName,IndexNumber,ProductionYear,"
                     + "ImageTags,BackdropImageTags,UserData,ParentId,Type,PremiereDate,DateCreated,"
                     + "CommunityRating,OfficialRating,RecursiveItemCount,ChildCount,PrimaryImageAspectRatio").append(extra);
        } else {
            u.append("&IncludeItemTypes=Folder,CollectionFolder,Series,Season,BoxSet,MusicAlbum&Fields=ChildCount,ImageTags,UserData,ParentId,Type,PrimaryImageAspectRatio").append(extra);
        }
        if (sortBy != null && !sortBy.isEmpty()) {
            u.append("&SortBy=").append(Uri.encode(sortBy));
            // Random 由服务器直接打乱，不能再叠 SortOrder
            if (!"Random".equalsIgnoreCase(sortBy)) {
                u.append("&SortOrder=").append(desc ? "Descending" : "Ascending");
            }
        } else {
            u.append("&SortBy=SortName&SortOrder=Ascending");
        }
        u.append("&StartIndex=").append(start < 0 ? 0 : start).append("&Limit=").append(limit);
        JSONObject res = call(u.toString(), "GET", null, s.auth());
        Page pg = new Page();
        pg.total = res.optInt("TotalRecordCount", 0);
        JSONArray arr = res.optJSONArray("Items");
        if (arr == null) return pg;
        for (int i = 0; i < arr.length(); i++) pg.items.add(toItem(arr.getJSONObject(i)));
        if (pg.total <= 0) pg.total = pg.items.size();
        return pg;
    }

    /**
     * 服务端 DisplayPreferences：Emby 官方 App 就把「排序 / 视图」这类偏好存在这里，
     * 按 用户 + 媒体库 各存一份，键是 SortBy / SortOrder，所以同一账号换设备能继承。
     */
    private static String prefUrl(Session s, String itemId) {
        String id = s.userId + "_" + itemId;
        return s.server + "/DisplayPreferences/" + Uri.encode(id)
                + "?userId=" + Uri.encode(s.userId) + "&client=embyatv";
    }

    /** 读该库的排序偏好（SortBy / SortOrder），拿不到返回空对象 */
    public static JSONObject displayPrefs(Session s, String itemId) throws Exception {
        JSONObject res = call(prefUrl(s, itemId), "GET", null, s.auth());
        JSONObject cp = res.optJSONObject("CustomPrefs");
        return cp == null ? new JSONObject() : cp;
    }

    /** 写排序偏好：先读回再合并，避免把官方 App 存的 PosterSize / ImageType 等键冲掉 */
    public static void saveSortPrefs(Session s, String itemId, String sortBy, boolean desc)
            throws Exception {
        JSONObject cp;
        try { cp = displayPrefs(s, itemId); } catch (Exception e) { cp = new JSONObject(); }
        cp.put("SortBy", sortBy == null ? "" : sortBy);
        cp.put("SortOrder", desc ? "d" : "a");
        JSONObject body = new JSONObject();
        body.put("Id", s.userId + "_" + itemId);
        body.put("CustomPrefs", cp);
        body.put("SortOrder", desc ? "Descending" : "Ascending");
        body.put("Client", "embyatv");
        call(prefUrl(s, itemId), "POST", body.toString(), s.auth());
    }

    /** 分页把整库拉全（本地排序用）：一批 500 条，直到拿满 TotalRecordCount */
    public static List<Item> pageAll(Session s, String parentId, boolean recursive,
                                     String extraFields) throws Exception {
        return pageAll(s, parentId, recursive, extraFields, null);
    }

    /** 同上，可指定条目类型（剧集库只取 Series） */
    public static List<Item> pageAll(Session s, String parentId, boolean recursive,
                                     String extraFields, String itemTypes) throws Exception {
        List<Item> out = new ArrayList<Item>();
        Page first = page(s, parentId, recursive, 0, 500, null, false, extraFields, itemTypes);
        out.addAll(first.items);
        int total = first.total;
        while (out.size() < total) {
            Page next = page(s, parentId, recursive, out.size(), 500, null, false, extraFields, itemTypes);
            if (next.items.isEmpty()) break;
            out.addAll(next.items);
        }
        return out;
    }

    /** 照片库分页（一页 400 条），照片墙先用它出首屏 */
    public static Page photosPage(Session s, String parentId, int start, int limit) throws Exception {
        StringBuilder u = new StringBuilder(s.server).append("/Users/").append(s.userId).append("/Items?");
        u.append("ParentId=").append(Uri.encode(parentId)).append("&Recursive=true");
        u.append("&IncludeItemTypes=Photo,Image");
        u.append("&Fields=Width,Height,Path,ImageTags,DateCreated,SortName,ProductionYear");
        u.append("&SortBy=DateCreated&SortOrder=Descending");
        u.append("&StartIndex=").append(start < 0 ? 0 : start).append("&Limit=").append(limit);
        JSONObject res = call(u.toString(), "GET", null, s.auth());
        Page pg = new Page();
        pg.total = res.optInt("TotalRecordCount", 0);
        JSONArray arr = res.optJSONArray("Items");
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) pg.items.add(toItem(arr.getJSONObject(i)));
        }
        if (pg.total <= 0) pg.total = pg.items.size();
        return pg;
    }

    /** 拉某个媒体库里的全部照片（照片类型媒体库的"照片"入口用），按拍摄/加入时间倒序 */
    public static List<Item> photos(Session s, String parentId) throws Exception {
        List<Item> out = new ArrayList<Item>();
        Page first = photosPage(s, parentId, 0, 400);
        out.addAll(first.items);
        int total = first.total;
        while (out.size() < total && out.size() < 20000) {
            Page next = photosPage(s, parentId, out.size(), 400);
            if (next.items.isEmpty()) break;
            out.addAll(next.items);
        }
        return out;
    }

    /** 当前目录的直属子项（不递归）：既有子文件夹，也有直接放在这一层的视频 */
    public static List<Item> childrenMixed(Session s, String parentId, int limit) throws Exception {
        return childrenMixed(s, parentId, limit, null, false);
    }

    /** 同上，但可以带排序：sortBy=null 按名称；desc 控制升降序 */
    public static List<Item> childrenMixed(Session s, String parentId, int limit,
                                           String sortBy, boolean desc) throws Exception {
        StringBuilder u = new StringBuilder(s.server).append("/Users/").append(s.userId).append("/Items?");
        u.append("ParentId=").append(Uri.encode(parentId)).append("&Recursive=false");
        u.append("&IncludeItemTypes=Folder,CollectionFolder,Series,Season,BoxSet,MusicAlbum,"
                + "Movie,Episode,Video,MusicVideo,Photo,PhotoAlbum");
        u.append("&Fields=Width,Height,RunTimeTicks,ChildCount,SeriesName,IndexNumber,ProductionYear,"
                + "ImageTags,PrimaryImageAspectRatio,UserData,ParentId,Type,PremiereDate,DateCreated,CommunityRating,OfficialRating,MediaSources");
        if (sortBy != null && !sortBy.isEmpty()) {
            u.append("&SortBy=").append(Uri.encode(sortBy))
             .append("&SortOrder=").append(desc ? "Descending" : "Ascending");
        } else {
            u.append("&SortBy=SortName");
        }
        u.append("&StartIndex=0&Limit=").append(limit);
        List<Item> out = new ArrayList<Item>();
        JSONObject res = call(u.toString(), "GET", null, s.auth());
        JSONArray arr = res.optJSONArray("Items");
        if (arr == null) return out;
        for (int i = 0; i < arr.length(); i++) out.add(toItem(arr.getJSONObject(i)));
        return out;
    }


    /** 全库搜索：片名 / 文件夹名，返回可直接展示的条目（视频与图片） */
    public static List<Item> search(Session s, String term, int limit) throws Exception {
        StringBuilder u = new StringBuilder(s.server).append("/Users/").append(s.userId).append("/Items?");
        u.append("SearchTerm=").append(Uri.encode(term));
        u.append("&Recursive=true&IncludeItemTypes=Movie,Video,MusicVideo,Photo,Series,BoxSet");
        u.append("&Fields=Width,Height,RunTimeTicks,SeriesName,IndexNumber,ProductionYear,");
        u.append("ImageTags,PrimaryImageAspectRatio,UserData,ParentId,Type,SeriesId,");
        u.append("ChildCount,RecursiveItemCount,CommunityRating");
        u.append("&SortBy=SortName&StartIndex=0&Limit=").append(limit);
        List<Item> out = new ArrayList<Item>();
        JSONObject res = call(u.toString(), "GET", null, s.auth());
        JSONArray arr = res.optJSONArray("Items");
        if (arr == null) return out;
        for (int i = 0; i < arr.length(); i++) out.add(toItem(arr.getJSONObject(i)));
        return out;
    }

    // ==================== 字幕 ====================

    /** 一条字幕流（内嵌或外挂） */
    public static class SubStream {
        public int index = 0;
        public String lang = "";
        public String langName = "";  // 语言的中文名
        public String title = "";
        public String codec = "";
        public String url = "";      // 外挂字幕的完整下载地址（含 api_key）
        public String fileName = ""; // 字幕文件名（外挂字幕靠它区分是哪一条）
        public boolean external = false;
        public boolean isDefault = false;
        public boolean isForced = false;

        /** 右侧那行说明：语言 · 格式 · 默认/强制/外挂（标题里已有的不重复写） */
        public String desc() {
            StringBuilder b = new StringBuilder();
            String t = title == null ? "" : title.toLowerCase();
            if (langName != null && !langName.isEmpty() && !t.contains(langName.toLowerCase())) {
                b.append(langName);
            }
            String c = codec == null ? "" : codec.toUpperCase();
            if (!c.isEmpty() && !"NULL".equals(c) && !t.contains(c.toLowerCase())) {
                b.append(b.length() > 0 ? " · " : "").append(c);
            }
            if (isDefault) b.append(b.length() > 0 ? " · " : "").append(L.t("默认"));
            if (isForced) b.append(b.length() > 0 ? " · " : "").append(L.t("强制"));
            if (external) b.append(b.length() > 0 ? " · " : "").append(L.t("外挂"));
            return b.toString();
        }
    }

    /** "(ASS)"、"（SRT）" 这种只有格式、没有名字的串，算作没有名字 */
    public static boolean isFormatOnly(String s) {
        if (s == null) return true;
        String t = s.toLowerCase().replace(" ", "").replace("(", "").replace(")", "")
                .replace("（", "").replace("）", "").replace("[", "").replace("]", "");
        return t.isEmpty() || "ass".equals(t) || "srt".equals(t) || "ssa".equals(t)
                || "pgs".equals(t) || "vtt".equals(t) || "sub".equals(t) || "sup".equals(t)
                || "text".equals(t) || "subrip".equals(t);
    }

    /** 从字幕文件名里猜语言：xxx.chs.ass / xxx.zh-cn.srt 这类 */
    public static String langFromFile(String n) {
        String t = n == null ? "" : n.toLowerCase();
        if (t.contains("chs") || t.contains("zh-cn") || t.contains("zh_cn") || t.contains(".sc.")
                || t.contains("简体") || t.contains("简中") || t.contains("chs&eng")) return "简体中文";
        if (t.contains("cht") || t.contains("zh-tw") || t.contains("zh_tw") || t.contains(".tc.")
                || t.contains("繁体") || t.contains("繁中") || t.contains("big5")) return "繁体中文";
        if (t.contains(".eng") || t.contains(".en.") || t.contains("english")) return "英语";
        if (t.contains(".jpn") || t.contains(".ja.") || t.contains("japanese")) return "日语";
        if (t.contains(".kor") || t.contains(".ko.") || t.contains("korean")) return "韩语";
        return "";
    }

    /** 语言代码转中文名，认不出来的原样返回 */
    public static String langName(String code) {
        if (code == null) return "";
        String c = code.toLowerCase();
        if (c.startsWith("zh") || c.startsWith("chi") || c.equals("zho") || c.startsWith("chs")) return "中文";
        if (c.startsWith("cht")) return "繁体中文";
        if (c.startsWith("en") || c.equals("eng")) return "英语";
        if (c.startsWith("ja") || c.equals("jpn")) return "日语";
        if (c.startsWith("ko") || c.equals("kor")) return "韩语";
        if (c.startsWith("fr") || c.equals("fre") || c.equals("fra")) return "法语";
        if (c.startsWith("de") || c.equals("ger") || c.equals("deu")) return "德语";
        if (c.startsWith("es") || c.equals("spa")) return "西班牙语";
        if (c.startsWith("ru") || c.equals("rus")) return "俄语";
        if (c.startsWith("th") || c.equals("tha")) return "泰语";
        if (c.startsWith("vi") || c.equals("vie")) return "越南语";
        if (c.startsWith("pt") || c.equals("por")) return "葡萄牙语";
        if (c.startsWith("it") || c.equals("ita")) return "意大利语";
        if (c.startsWith("ar") || c.equals("ara")) return "阿拉伯语";
        if (c.startsWith("hi") || c.equals("hin")) return "印地语";
        if (c.equals("und") || c.isEmpty()) return "";
        return code;
    }

    /** 音轨清单：[标题, 副标题] */
    public static java.util.List<String[]> audioTracks(Session s, String itemId) {
        java.util.List<String[]> out = new java.util.ArrayList<String[]>();
        try {
            String u = s.server + "/Users/" + s.userId + "/Items/" + itemId + "?Fields=MediaSources,UserData";
            JSONObject o = call(u, "GET", null, s.auth());
            JSONArray srcs = o.optJSONArray("MediaSources");
            if (srcs == null) return out;
            for (int i = 0; i < srcs.length(); i++) {
                JSONObject m = srcs.optJSONObject(i);
                if (m == null) continue;
                JSONArray st = m.optJSONArray("MediaStreams");
                if (st == null) continue;
                for (int j = 0; j < st.length(); j++) {
                    JSONObject so = st.optJSONObject(j);
                    if (so == null || !"Audio".equals(so.optString("Type"))) continue;
                    String dt = so.optString("DisplayTitle", "");
                    if ("null".equals(dt) || dt.isEmpty()) dt = "音轨 " + (out.size() + 1);
                    StringBuilder sub = new StringBuilder();
                    String codec = so.optString("Codec", "").toUpperCase();
                    if (!codec.isEmpty()) sub.append(codec);
                    int ch = so.optInt("Channels", 0);
                    if (ch > 0) sub.append(sub.length() > 0 ? " · " : "").append(ch).append(L.t(" 声道"));
                    if (so.optBoolean("Default", false)) sub.append(sub.length() > 0 ? " · " : "").append(L.t("默认"));
                    out.add(new String[]{dt, sub.toString()});
                }
            }
        } catch (Exception ignored) { }
        return out;
    }

    /** 内嵌文本字幕服务器能导出成 srt；图形字幕（PGS / DVDSub / VobSub）导不出来 */
    public static boolean isTextCodec(String codec) {
        if (codec == null) return false;
        String c = codec.toLowerCase();
        if (c.isEmpty() || "null".equals(c)) return false;
        if (c.contains("pgs") || c.contains("dvd") || c.contains("dvbsub") || c.contains("vobsub")) return false;
        return c.contains("subrip") || c.contains("srt") || c.contains("ass") || c.contains("ssa")
                || c.contains("mov_text") || c.contains("text") || c.contains("webvtt")
                || c.contains("vtt") || c.contains("smi") || c.contains("sami")
                || c.contains("microdvd") || c.contains("subviewer") || c.contains("ttml");
    }

    /** 抓这个条目的字幕流清单：外挂的带可下载 URL，内嵌文本的也能按索引导出 srt */
    public static List<SubStream> subtitleStreams(Session s, String itemId) {
        List<SubStream> out = new ArrayList<SubStream>();
        try {
            String u = s.server + "/Users/" + s.userId + "/Items/" + itemId + "?Fields=MediaSources,UserData";
            JSONObject o = call(u, "GET", null, s.auth());
            JSONArray srcs = o.optJSONArray("MediaSources");
            if (srcs == null) return out;
            for (int i = 0; i < srcs.length(); i++) {
                JSONObject m = srcs.optJSONObject(i);
                if (m == null) continue;
                JSONArray st = m.optJSONArray("MediaStreams");
                if (st == null) continue;
                for (int j = 0; j < st.length(); j++) {
                    JSONObject so = st.optJSONObject(j);
                    if (so == null || !"Subtitle".equals(so.optString("Type"))) continue;
                    SubStream sb = new SubStream();
                    sb.index = so.optInt("Index", 0);
                    sb.lang = so.optString("Language", "");
                    if ("null".equals(sb.lang)) sb.lang = "";
                    sb.langName = langName(sb.lang);
                    sb.isDefault = so.optBoolean("Default", false);
                    sb.isForced = so.optBoolean("Forced", false);
                    // 名字优先级：真实名字 → 字幕文件名 → 语言中文名 → 序号
                    // DisplayTitle 常常只有 "(ASS)" 这种纯格式，没信息量，直接跳过
                    String dt = so.optString("DisplayTitle", "");
                    String tt = so.optString("Title", "");
                    if ("null".equals(dt)) dt = "";
                    if ("null".equals(tt)) tt = "";
                    String p = so.optString("Path", "");
                    if ("null".equals(p)) p = "";
                    if (!p.isEmpty()) {
                        int sl = Math.max(p.lastIndexOf('/'), p.lastIndexOf('\\'));
                        sb.fileName = sl >= 0 ? p.substring(sl + 1) : p;
                    }
                    if (sb.langName.isEmpty() && !sb.fileName.isEmpty()) {
                        sb.langName = langFromFile(sb.fileName);
                    }
                    // 名字优先级：真实名字 → 语言中文名 → 序号
                    // 文件名是视频名（比如整部片名），太长又不能区分字幕，不拿来当标题
                    boolean ext = so.optBoolean("IsExternal", false);
                    String nm = !dt.isEmpty() && !isFormatOnly(dt) ? dt : tt;
                    // "(默认 EIA_608)" 这种只写了格式，Title 有值就用 Title（如 Closed Captions 1）
                    if (nm.startsWith("(默认") || nm.startsWith("（默认")) {
                        if (!tt.isEmpty()) nm = tt;
                    }
                    if (nm.isEmpty() || isFormatOnly(nm)) {
                        nm = sb.langName.isEmpty()
                                ? ((ext ? L.t("外挂字幕 ") : L.t("字幕 ")) + (sb.index + 1))
                                : sb.langName;
                    }
                    sb.title = nm;
                    sb.codec = so.optString("Codec", "");
                    if ("null".equals(sb.codec)) sb.codec = "";
                    sb.external = so.optBoolean("IsExternal", false);
                    String du = so.optString("DeliveryUrl", "");
                    String mId = m.optString("Id", "");
                    if (!"null".equals(du) && !du.isEmpty()) {
                        sb.external = true;
                        sb.url = s.server + du + (du.indexOf('?') >= 0 ? "&" : "?") + "api_key=" + s.token;
                    } else if (!"null".equals(mId) && !mId.isEmpty() && (sb.external || isTextCodec(sb.codec))) {
                        // 外挂字幕没给 DeliveryUrl、或内嵌文本字幕：都按 Emby 的索引导出接口拼地址
                        sb.url = s.server + "/Videos/" + itemId + "/" + mId + "/Subtitles/" + sb.index
                                + "/Stream.srt?api_key=" + s.token;
                    }
                    out.add(sb);
                }
            }
        } catch (Exception ignored) { }
        return out;
    }

    /** 是否中文字幕 */
    public static boolean isChineseSub(String lang) {
        String l = lang == null ? "" : lang.toLowerCase();
        return l.startsWith("zh") || l.startsWith("chi") || l.startsWith("cht")
                || l.startsWith("chs") || l.startsWith("zhe");
    }

    /** 下载字幕（服务器给的是 gz 包，自动解压成 .srt 文本） */
    public static String downloadSubtitle(String url) {
        HttpURLConnection c = null;
        try {
            String fixed = url.replace("https://", "http://");
            c = (HttpURLConnection) new URL(fixed).openConnection();
            c.setRequestProperty("User-Agent", "TemporaryUserAgent");
            c.setRequestProperty("X-User-Agent", "TemporaryUserAgent");
            c.setConnectTimeout(12000);
            c.setReadTimeout(25000);
            InputStream in = new BufferedInputStream(c.getInputStream());
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            in.close();
            byte[] raw = bo.toByteArray();
            if (raw.length > 2 && (raw[0] & 0xFF) == 0x1F && (raw[1] & 0xFF) == 0x8B) {
                java.util.zip.GZIPInputStream gz = new java.util.zip.GZIPInputStream(
                        new java.io.ByteArrayInputStream(raw));
                ByteArrayOutputStream srt = new ByteArrayOutputStream();
                while ((n = gz.read(buf)) > 0) srt.write(buf, 0, n);
                gz.close();
                return new String(srt.toByteArray(), "UTF-8");
            }
            return new String(raw, "UTF-8");
        } catch (Exception e) {
            return null;
        } finally { if (c != null) c.disconnect(); }
    }

    public static List<Item> children(Session s, String parentId, boolean recursive) throws Exception {
        return page(s, parentId, recursive, 0, recursive ? 3000 : 1000).items;
    }

    /** 单条详情（含体积/码率/容器/路径/简介/收藏态） */
    public static Item detail(Session s, String id) throws Exception {
        String u = s.server + "/Users/" + s.userId + "/Items/" + id
                + "?Fields=Width,Height,RunTimeTicks,SeriesName,IndexNumber,ProductionYear,"
                + "ImageTags,UserData,ParentId,Type,Overview,Path,MediaSources,Container,"
                + "Genres,MediaStreams,OfficialRating,Chapters,SeriesId,ParentIndexNumber";
        return toItem(call(u, "GET", null, s.auth()));
    }

    /** 剧集列表：给详情页「选集」面板用 */
    public static List<Item> episodes(Session s, String seriesId) throws Exception {
        List<Item> out = new ArrayList<Item>();
        String u = s.server + "/Shows/" + seriesId + "/Episodes?UserId=" + s.userId
                + "&Fields=Width,Height,RunTimeTicks,SeriesName,IndexNumber,ParentIndexNumber,"
                + "ImageTags,UserData,Overview,SeriesId&Limit=300";
        JSONArray arr = call(u, "GET", null, s.auth()).optJSONArray("Items");
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) out.add(toItem(arr.getJSONObject(i)));
        }
        if (!out.isEmpty()) return out;
        // 兜底：个别服务端 /Shows/{id}/Episodes 返回空（或在花絮/特辑结构下拿不到集），
        // 用递归 Items 查询再捞一次，避免详情页「选集」整块消失
        try {
            String u2 = s.server + "/Users/" + s.userId + "/Items?ParentId=" + seriesId
                    + "&Recursive=true&IncludeItemTypes=Episode&Limit=300&Fields="
                    + "Width,Height,RunTimeTicks,SeriesName,IndexNumber,ParentIndexNumber,"
                    + "ImageTags,UserData,Overview,SeriesId";
            JSONArray a2 = call(u2, "GET", null, s.auth()).optJSONArray("Items");
            if (a2 != null) {
                for (int i = 0; i < a2.length(); i++) out.add(toItem(a2.getJSONObject(i)));
            }
        } catch (Throwable ignored) { }
        return out;
    }

    /** 这部剧「下一集该看哪一集」：官方继续观看口径（NextUp 第一项就是最后看到一半的那集） */
    public static List<Item> nextUp(Session s, String seriesId) throws Exception {
        List<Item> out = new ArrayList<Item>();
        String u = s.server + "/Shows/NextUp?UserId=" + s.userId + "&SeriesId=" + seriesId
                + "&Limit=3&Fields=Width,Height,RunTimeTicks,SeriesName,IndexNumber,ParentIndexNumber,"
                + "ImageTags,UserData,Overview,SeriesId";
        JSONArray arr = call(u, "GET", null, s.auth()).optJSONArray("Items");
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) out.add(toItem(arr.getJSONObject(i)));
        }
        return out;
    }

    /** 相似条目（详情页底部「更多类似」） */
    /** 继续观看（首页「最近观看」横排） */
    public static List<Item> resume(Session s, int limit) {
        List<Item> out = new ArrayList<Item>();
        try {
            String u = s.server + "/Users/" + s.userId + "/Items/Resume?Limit=" + limit
                    + "&MediaTypes=Video&Fields=Width,Height,RunTimeTicks,SeriesName,IndexNumber,"
                    + "ProductionYear,ImageTags,UserData,ParentId,Type,SeriesId";
            JSONArray arr = call(u, "GET", null, s.auth()).optJSONArray("Items");
            if (arr == null) return out;
            for (int i = 0; i < arr.length(); i++) out.add(toItem(arr.getJSONObject(i)));
        } catch (Exception ignored) { }
        return out;
    }

    /** 单个条目的续播位置（毫秒）：最近观看里有些条目（本机历史）没带进度，播放前现查一次 */
    public static long positionMs(Session s, String itemId) {
        try {
            if (s == null || itemId == null || itemId.isEmpty()) return 0;
            String u = s.server + "/Users/" + s.userId + "/Items/" + itemId + "?Fields=UserData";
            JSONObject o = call(u, "GET", null, s.auth());
            JSONObject ud = o.optJSONObject("UserData");
            if (ud == null) return 0;
            return ud.optLong("PlaybackPositionTicks", 0L) / 10000L;
        } catch (Exception e) {
            return 0;
        }
    }

    /** 已播放过的条目（看完的片子不会出现在 Resume 里，用这个补齐「最近观看」） */
    public static List<Item> played(Session s, int limit) {
        List<Item> out = new ArrayList<Item>();
        try {
            String u = s.server + "/Users/" + s.userId + "/Items?Filters=IsPlayed&Recursive=true"
                    + "&IncludeItemTypes=Movie,Episode,Video,MusicVideo"
                    + "&SortBy=DatePlayed&SortOrder=Descending&Limit=" + limit
                    + "&Fields=Width,Height,RunTimeTicks,SeriesName,IndexNumber,ProductionYear,"
                    + "ImageTags,PrimaryImageAspectRatio,UserData,ParentId,Type,SeriesId";
            JSONArray arr = call(u, "GET", null, s.auth()).optJSONArray("Items");
            if (arr == null) return out;
            for (int i = 0; i < arr.length(); i++) out.add(toItem(arr.getJSONObject(i)));
        } catch (Exception ignored) { }
        return out;
    }

    /** 搜索页默认展示：跨所有媒体库随机抽一批（SortBy=Random，不能叠 SortOrder） */
    public static List<Item> randomPick(Session s, int limit) throws Exception {
        List<Item> out = new ArrayList<Item>();
        String u = s.server + "/Users/" + s.userId + "/Items?Recursive=true"
                + "&IncludeItemTypes=Movie,Series,Video,MusicVideo"
                + "&SortBy=Random&Limit=" + limit
                + "&Fields=Width,Height,RunTimeTicks,SeriesName,IndexNumber,ProductionYear,"
                + "ImageTags,PrimaryImageAspectRatio,UserData,ParentId,Type,ChildCount,"
                + "RecursiveItemCount,CommunityRating";
        JSONArray arr = call(u, "GET", null, s.auth()).optJSONArray("Items");
        if (arr == null) return out;
        for (int i = 0; i < arr.length(); i++) out.add(toItem(arr.getJSONObject(i)));
        return out;
    }

    /** 收藏（首页「收藏」页签） */
    public static List<Item> favorites(Session s, int limit) {        List<Item> out = new ArrayList<Item>();
        try {
            String u = s.server + "/Users/" + s.userId + "/Items?Filters=IsFavorite&Recursive=true"
                    // 收藏可以是任何类型：剧集/合集/季/图片 以前都被这个白名单过滤掉，
                    // 表现就是「明明收藏了，收藏页里却找不到」
                    + "&IncludeItemTypes=Movie,Episode,Video,MusicVideo,Series,Season,BoxSet,Photo,PhotoAlbum"
                    + "&SortBy=DateCreated"
                    + "&SortOrder=Descending&Limit=" + limit
                    + "&Fields=Width,Height,RunTimeTicks,SeriesName,IndexNumber,ProductionYear,"
                    + "ImageTags,PrimaryImageAspectRatio,UserData,ParentId,Type,ChildCount,"
                    + "RecursiveItemCount,CommunityRating";
            JSONArray arr = call(u, "GET", null, s.auth()).optJSONArray("Items");
            if (arr == null) return out;
            for (int i = 0; i < arr.length(); i++) out.add(toItem(arr.getJSONObject(i)));
        } catch (Exception ignored) { }
        return out;
    }

    /** 某个媒体库里最新加入的条目（首页「最新 XX」模块） */
    public static List<Item> latest(Session s, String parentId, String types, int limit) {
        List<Item> out = new ArrayList<Item>();
        try {
            String u = s.server + "/Users/" + s.userId + "/Items?Recursive=true"
                    + "&IncludeItemTypes=" + types
                    + "&SortBy=DateCreated&SortOrder=Descending&Limit=" + limit
                    + "&Fields=Width,Height,RunTimeTicks,SeriesName,IndexNumber,ProductionYear,"
                    + "ImageTags,PrimaryImageAspectRatio,UserData,ParentId,Type,ChildCount,CommunityRating,SeriesId"
                    + (parentId == null || parentId.isEmpty() ? "" : "&ParentId=" + parentId);
            JSONArray arr = call(u, "GET", null, s.auth()).optJSONArray("Items");
            if (arr == null) return out;
            for (int i = 0; i < arr.length(); i++) out.add(toItem(arr.getJSONObject(i)));
        } catch (Exception ignored) { }
        return out;
    }

    public static List<Item> similar(Session s, String itemId, int limit) {
        List<Item> out = new ArrayList<Item>();
        try {
            String u = s.server + "/Items/" + itemId + "/Similar?UserId=" + s.userId
                    + "&Limit=" + limit
                    + "&Fields=Width,Height,RunTimeTicks,SeriesName,IndexNumber,ProductionYear,"
                    + "ImageTags,PrimaryImageAspectRatio,UserData,ParentId,Type";
            JSONArray arr = call(u, "GET", null, s.auth()).optJSONArray("Items");
            if (arr == null) return out;
            for (int i = 0; i < arr.length(); i++) out.add(toItem(arr.getJSONObject(i)));
        } catch (Exception ignored) { }
        return out;
    }

    /** 演职人员：返回 name / 角色 / 头像地址（详情页「演职人员」横排用） */
    public static List<String[]> people(Session s, String itemId, int limit) {
        List<String[]> out = new ArrayList<String[]>();
        try {
            String u = s.server + "/Items/" + itemId + "/People?UserId=" + s.userId + "&Limit=" + limit;
            JSONArray arr = call(u, "GET", null, s.auth()).optJSONArray("People");
            if (arr == null) return out;
            for (int i = 0; i < arr.length(); i++) {
                org.json.JSONObject o = arr.getJSONObject(i);
                String name = o.optString("Name", "");
                if (name.isEmpty()) continue;
                String role = o.optString("Role", "");
                String type = o.optString("Type", "");
                if (role.isEmpty() && !"Actor".equals(type)) {
                    if ("Director".equals(type)) role = "导演";
                    else if ("Writer".equals(type)) role = "编剧";
                    else if ("Producer".equals(type)) role = "制片";
                    else if ("GuestStar".equals(type)) role = "客串";
                }
                String pid = o.optString("Id", "");
                String tag = o.optString("PrimaryImageTag", "");
                String img = (pid.isEmpty() || tag.isEmpty()) ? ""
                        : (s.server + "/Items/" + pid + "/Images/Primary?maxWidth=240&tag=" + tag + "&quality=90");
                out.add(new String[]{name, role, img});
            }
        } catch (Exception ignored) { }
        return out;
    }

    /** 收藏 / 取消收藏 */
    public static boolean favorite(Session s, String itemId, boolean on) {
        try {
            String u = s.server + "/Users/" + s.userId + "/FavoriteItems/" + itemId;
            call(u, on ? "POST" : "DELETE", null, s.auth());
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** 删除服务器上的条目（需要账号有删除权限） */
    public static String deleteItem(Session s, String itemId) {
        try {
            call(s.server + "/Items/" + itemId, "DELETE", null, s.auth());
            return null;
        } catch (Exception e) {
            String m = e.getMessage();
            return m == null ? L.t("删除失败") : m;
        }
    }

    /** 标记已播放 / 未播放 */
    public static boolean markPlayed(Session s, String itemId, boolean played) {
        try {
            String u = s.server + "/Users/" + s.userId + "/PlayedItems/" + itemId;
            if (played) call(u, "POST", "{}", s.auth());
            else call(u, "DELETE", null, s.auth());
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** 刷新元数据 */
    public static boolean refreshItem(Session s, String itemId) {
        try {
            call(s.server + "/Items/" + itemId + "/RefreshMetadata?Recursive=true", "POST", "{}", s.auth());
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public static String imageUrl(Session s, String id, String tag, int h) {
        // 统一回原图：服务端每次按尺寸缩放要 60–140ms（弱 CPU 的 NAS 上就是列表慢的主因），
        // 原图本身只有 30–220KB，直出几乎不耗 CPU，客户端用 inSampleSize 自己采样，
        // 而且同一张图在所有页面共用一个 URL、一份磁盘缓存，命中率大幅提高。
        // h 参数保留只为兼容调用点，不再影响 URL。
        String q = "?quality=92";
        return s.server + "/Items/" + id + "/Images/Primary" + q
                + (tag == null || tag.isEmpty() ? "" : "&tag=" + tag);
    }

    /** 横版剧照（fanart）：banner / 背景墙用这个，比竖版缩略图清楚得多 */
    public static String backdropUrl(Session s, String id, String tag, int w) {
        String q = w > 0 ? ("?maxWidth=" + w + "&quality=94") : "?quality=94";
        return s.server + "/Items/" + id + "/Images/Backdrop" + q
                + (tag == null || tag.isEmpty() ? "" : "&tag=" + tag);
    }

    /** 视频缩略图（Emby 抽帧出来的 Thumb）：集卡和播放器选集条用，跟刮削海报分开 */
    public static String thumbUrl(Session s, String id, String tag, int w) {
        String q = w > 0 ? ("?maxWidth=" + w + "&quality=90") : "?quality=94";
        return s.server + "/Items/" + id + "/Images/Thumb" + q
                + (tag == null || tag.isEmpty() ? "" : "&tag=" + tag);
    }

    public static String streamUrl(Session s, String itemId) {
        return s.server + "/Videos/" + itemId + "/stream?static=true&api_key="
                + URLEncoder.encode(s.token == null ? "" : s.token) + "&DeviceId=" + s.deviceId;
    }

    /** 让服务器转码播放（h264 + aac，可限分辨率 / 码率） */
    public static String transcodeUrl(Session s, String itemId, int maxHeight, int kbps) {
        StringBuilder sb = new StringBuilder();
        sb.append(s.server).append("/Videos/").append(itemId).append("/stream.mp4")
                .append("?VideoCodec=h264&AudioCodec=aac&TranscodingProtocol=http&Static=false")
                .append("&api_key=").append(URLEncoder.encode(s.token == null ? "" : s.token));
        if (maxHeight > 0) {
            sb.append("&MaxHeight=").append(maxHeight);
            sb.append("&MaxWidth=").append(maxHeight * 16 / 9);
        }
        if (kbps > 0) sb.append("&VideoBitrate=").append(kbps * 1000);
        // 不声明转码原因时，Emby 会认为 h264/aac 客户端能直放而忽略 MaxHeight / 码率限制
        if (maxHeight > 0 || kbps > 0) {
            sb.append("&TranscodeReasons=VideoResolutionNotSupported,VideoBitrateNotSupported");
        }
        return sb.toString();
    }

    /** 带起点的转码地址：seek 时用 StartTimeTicks 让服务器从指定毫秒开始重转 */
    public static String transcodeUrlFrom(Session s, String itemId, int maxHeight, int kbps, long startMs) {
        String base = transcodeUrl(s, itemId, maxHeight, kbps);
        if (startMs <= 0) return base;
        return base + "&StartTimeTicks=" + (startMs * 10000L);
    }

    /** 片长（毫秒），拿不到返回 0 */
    public static long runtimeMs(Session s, String itemId) {
        try {
            String u = s.server + "/Users/" + s.userId + "/Items/" + itemId + "?Fields=RunTimeTicks";
            JSONObject o = call(u, "GET", null, s.auth());
            long ticks = o.optLong("RunTimeTicks", 0);
            return ticks > 0 ? ticks / 10000L : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    /** 原始媒体信息（服务器侧媒体流参数），供「播放统计」面板用 */
    public static class MediaInfo {
        public String container = "";
        public String videoCodec = "", audioCodec = "", fps = "", channels = "", range = "";
        public int width = 0, height = 0;
        public long videoBitrate = 0, audioBitrate = 0, totalBitrate = 0, size = 0;
    }

    public static MediaInfo mediaInfo(Session s, String itemId) {
        MediaInfo mi = new MediaInfo();
        try {
            String u = s.server + "/Users/" + s.userId + "/Items/" + itemId
                    + "?Fields=MediaSources,MediaStreams,Path";
            JSONObject o = call(u, "GET", null, s.auth());
            JSONArray srcs = o.optJSONArray("MediaSources");
            if (srcs == null || srcs.length() == 0) return mi;
            JSONObject m = srcs.optJSONObject(0);
            if (m == null) return mi;
            mi.container = m.optString("Container", "");
            mi.totalBitrate = m.optLong("Bitrate", 0);
            mi.size = m.optLong("Size", 0);
            JSONArray st = m.optJSONArray("MediaStreams");
            if (st != null) {
                for (int i = 0; i < st.length(); i++) {
                    JSONObject so = st.optJSONObject(i);
                    if (so == null) continue;
                    String type = so.optString("Type", "");
                    if ("Video".equals(type)) {
                        mi.videoCodec = so.optString("Codec", "");
                        mi.width = so.optInt("Width", 0);
                        mi.height = so.optInt("Height", 0);
                        mi.videoBitrate = so.optLong("BitRate", 0);
                        String fr = so.optString("FrameRate", "");
                        if (fr.isEmpty() || "null".equals(fr) || "0".equals(fr)) fr = so.optString("AverageFrameRate", "");
                        if (fr.isEmpty() || "null".equals(fr) || "0".equals(fr)) fr = so.optString("RealFrameRate", "");
                        if (!fr.isEmpty() && !"null".equals(fr) && !"0".equals(fr)) mi.fps = fr;
                        mi.range = so.optString("VideoRange", "");
                    } else if ("Audio".equals(type)) {
                        mi.audioCodec = so.optString("Codec", "");
                        mi.audioBitrate = so.optLong("BitRate", 0);
                        mi.channels = channelName(so.optInt("Channels", 0));
                    }
                }
            }
        } catch (Exception ignored) { }
        return mi;
    }

    /** 当前这次播放的真实情况（来自 Emby 会话）：
     *  {PlayMethod, 宽, 高, 码率bps, 视频codec, 音频codec}
     *  PlayMethod = DirectPlay / DirectStream / Transcode；没有转码信息时后五项为空串 */
    public static String[] liveSession(Session s, String itemId, String playSessionId) {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(s.server + "/Sessions").openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(12000);
            c.setRequestProperty("Accept", "application/json");
            c.setRequestProperty("X-Emby-Authorization", s.auth());
            if (c.getResponseCode() != 200) return null;
            String text = read(c.getInputStream());
            JSONArray arr = new JSONArray(text);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                JSONObject ps = o.optJSONObject("PlayState");
                String psid = ps == null ? "" : ps.optString("PlaySessionId", "");
                JSONObject np = o.optJSONObject("NowPlayingItem");
                String npId = np == null ? "" : np.optString("Id", "");
                boolean match = (itemId != null && !itemId.isEmpty() && npId.equals(itemId))
                        || (playSessionId != null && !playSessionId.isEmpty() && psid.equals(playSessionId));
                if (!match) continue;
                String pm = ps == null ? "" : ps.optString("PlayMethod", "");
                JSONObject ti = o.optJSONObject("TranscodingInfo");
                if (ti == null) return new String[]{pm, "", "", "", "", ""};
                int w = ti.optInt("Width", 0), h = ti.optInt("Height", 0);
                return new String[]{pm, w > 0 ? "" + w : "", h > 0 ? "" + h : "",
                        "" + ti.optInt("Bitrate", 0),
                        ti.optString("VideoCodec", ""), ti.optString("AudioCodec", "")};
            }
        } catch (Throwable ignored) { }
        return null;
    }

    public static String channelName(int ch) {
        if (ch <= 0) return "";
        if (ch == 1) return "mono";
        if (ch == 2) return "stereo";
        if (ch == 6) return "5.1";
        if (ch == 8) return "7.1";
        return ch + L.t(" 声道");
    }

    /** 播放统计：容器 / 码率 / 视频 / 音频（拿不到返回空串） */
    public static String statsText(Session s, String itemId) {
        try {
            String u = s.server + "/Users/" + s.userId + "/Items/" + itemId
                    + "?Fields=MediaSources,MediaStreams,Path";
            JSONObject o = call(u, "GET", null, s.auth());
            JSONArray srcs = o.optJSONArray("MediaSources");
            if (srcs == null || srcs.length() == 0) return "";
            JSONObject m = srcs.optJSONObject(0);
            if (m == null) return "";
            StringBuilder sb = new StringBuilder();
            sb.append(L.t("容器 ")).append(m.optString("Container", "?").toUpperCase());
            long size = m.optLong("Size", 0);
            if (size > 0) sb.append(" · ").append(size / 1024 / 1024).append(" MB");
            long br = m.optLong("Bitrate", 0);
            if (br > 0) sb.append(L.t(" · 总码率 ")).append(br / 1000).append(" kbps");
            JSONArray st = m.optJSONArray("MediaStreams");
            if (st != null) {
                for (int i = 0; i < st.length(); i++) {
                    JSONObject so = st.optJSONObject(i);
                    if (so == null) continue;
                    String type = so.optString("Type", "");
                    if ("Video".equals(type)) {
                        sb.append("\n视频 ").append(so.optString("Codec", "?").toUpperCase());
                        int w = so.optInt("Width", 0), h = so.optInt("Height", 0);
                        if (w > 0) sb.append(" · ").append(w).append("×").append(h);
                        String fr = so.optString("FrameRate", "");
                        if (!fr.isEmpty() && !"null".equals(fr)) sb.append(" · ").append(fr).append(" fps");
                        long vb = so.optLong("BitRate", 0);
                        if (vb > 0) sb.append(" · ").append(vb / 1000).append(" kbps");
                    } else if ("Audio".equals(type)) {
                        sb.append("\n音频 ").append(so.optString("Codec", "?").toUpperCase());
                        int ch = so.optInt("Channels", 0);
                        if (ch > 0) sb.append(" · ").append(ch).append(L.t(" 声道"));
                        long ab = so.optLong("BitRate", 0);
                        if (ab > 0) sb.append(" · ").append(ab / 1000).append(" kbps");
                        String al = so.optString("Language", "");
                        if (!al.isEmpty() && !"null".equals(al)) sb.append(" · ").append(al);
                    }
                }
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    /** 播放上报统一串行执行：保证 Start 先于 Progress，并且不在主线程做网络请求 */
    private static final java.util.concurrent.ExecutorService REP =
            java.util.concurrent.Executors.newSingleThreadExecutor();

    public static void reportStart(final Session s, final String itemId, final String playSessionId) {
        REP.submit(new Runnable() { @Override public void run() {
            try {
                JSONObject b = new JSONObject();
                b.put("ItemId", itemId);
                b.put("CanSeek", true);
                b.put("PlayMethod", "DirectStream");
                if (playSessionId != null && !playSessionId.isEmpty()) {
                    b.put("PlaySessionId", playSessionId);
                }
                call(s.server + "/Sessions/Playing", "POST", b.toString(), s.auth());
                android.util.Log.e("LMBY", "reportStart ok item=" + itemId + " psid=" + playSessionId);
            } catch (Exception e) {
                android.util.Log.e("LMBY", "reportStart fail item=" + itemId + " " + e);
            }
        }});
    }

    public static void reportStart(Session s, String itemId) {
        reportStart(s, itemId, null);
    }

    /** 取 PlaySessionId：Emby 的进度上报要靠它关联会话 */
    public static String playSession(Session s, String itemId) {
        try {
            String u = s.server + "/Items/" + itemId + "/PlaybackInfo?UserId=" + s.userId;
            JSONObject o = call(u, "GET", null, s.auth());
            String id = o.optString("PlaySessionId", "");
            return "null".equals(id) ? "" : id;
        } catch (Exception e) {
            return "";
        }
    }

    public static void reportStop(Session s, String itemId, long posMs) {
        reportStop(s, itemId, posMs, null);
    }

    public static void reportStop(final Session s, final String itemId, final long posMs,
                                  final String playSessionId) {
        REP.submit(new Runnable() { @Override public void run() {
            try {
                JSONObject b = new JSONObject();
                b.put("ItemId", itemId);
                b.put("PositionTicks", posMs * 10000L);
                if (playSessionId != null && !playSessionId.isEmpty()) {
                    b.put("PlaySessionId", playSessionId);
                }
                call(s.server + "/Sessions/Playing/Stopped", "POST", b.toString(), s.auth());
                android.util.Log.e("LMBY", "reportStop ok item=" + itemId + " pos=" + posMs);
            } catch (Exception e) {
                android.util.Log.e("LMBY", "reportStop fail item=" + itemId + " pos=" + posMs + " " + e);
            }
        }});
    }

    public static void reportProgress(Session s, String itemId, long posMs, boolean paused) {
        reportProgress(s, itemId, posMs, paused, null);
    }

    public static void reportProgress(final Session s, final String itemId, final long posMs,
                                      final boolean paused, final String playSessionId) {
        REP.submit(new Runnable() { @Override public void run() {
            try {
                JSONObject b = new JSONObject();
                b.put("ItemId", itemId);
                b.put("PositionTicks", posMs * 10000L);
                b.put("IsPaused", paused);
                b.put("CanSeek", true);
                if (playSessionId != null && !playSessionId.isEmpty()) {
                    b.put("PlaySessionId", playSessionId);
                }
                call(s.server + "/Sessions/Playing/Progress", "POST", b.toString(), s.auth());
                android.util.Log.e("LMBY", "reportProgress ok item=" + itemId + " pos=" + posMs + " paused=" + paused);
            } catch (Exception e) {
                android.util.Log.e("LMBY", "reportProgress fail item=" + itemId + " pos=" + posMs + " " + e);
            }
        }});
    }

    /** 局域网广播发现 Emby 服务器（查 7359 端口），返回 {名称, 地址} */
    public static java.util.List<String[]> discoverServers() {
        java.util.List<String[]> out = new java.util.ArrayList<String[]>();
        java.net.DatagramSocket sock = null;
        try {
            sock = new java.net.DatagramSocket();
            sock.setBroadcast(true);
            sock.setSoTimeout(500);
            byte[] q = "who is EmbyServer?".getBytes("UTF-8");
            // 全局广播 + 每个网段的定向广播（很多路由器只放行后者）
            java.util.Set<String> bcs = new java.util.LinkedHashSet<String>();
            bcs.add("255.255.255.255");
            for (String ip : localIps()) {
                bcs.add(ip.substring(0, ip.lastIndexOf('.') + 1) + "255");
            }
            for (String bc : bcs) {
                try {
                    java.net.DatagramPacket send = new java.net.DatagramPacket(q, q.length,
                            java.net.InetAddress.getByName(bc), 7359);
                    sock.send(send);
                } catch (Exception ignored) { }
            }
            long end = System.currentTimeMillis() + 2200;
            while (System.currentTimeMillis() < end) {
                byte[] buf = new byte[2048];
                java.net.DatagramPacket rec = new java.net.DatagramPacket(buf, buf.length);
                try { sock.receive(rec); } catch (Exception e) { break; }
                String s = new String(buf, 0, rec.getLength(), "UTF-8");
                String name = field(s, "Name");
                String addr = field(s, "Address");
                if (name != null && addr != null) {
                    boolean dup = false;
                    for (String[] o : out) if (o[1].equals(addr)) dup = true;
                    if (!dup) out.add(new String[]{name, addr});
                }
            }
        } catch (Exception ignored) {
        } finally {
            if (sock != null) sock.close();
        }
        android.util.Log.e("LMBY", "广播发现 数量=" + out.size());
        for (String[] o : out) android.util.Log.e("LMBY", "广播发现 服务器=" + o[0] + " " + o[1]);
        return out;
    }

    private static String field(String json, String key) {
        String k = "\"" + key + "\":\"";
        int i = json.indexOf(k);
        if (i < 0) return null;
        int st = i + k.length();
        int en = json.indexOf('"', st);
        return en > st ? json.substring(st, en) : null;
    }

    /** 不登录也能查到的用户列表：GET /Users/Public，返回 {用户名, userId} */
    public static java.util.List<String[]> publicUsers(String server) {
        java.util.List<String[]> out = new java.util.ArrayList<String[]>();
        try {
            String body = quickProbe(server + "/Users/Public");
            int i = 0;
            while (true) {
                int n = body.indexOf("\"Name\":\"", i);
                if (n < 0) break;
                int ns = n + 8, ne = body.indexOf('"', ns);
                if (ne < 0) break;
                String nm = body.substring(ns, ne);
                int k = body.indexOf("\"Id\":\"", ne);
                if (k < 0) break;
                int ks = k + 6, ke = body.indexOf('"', ks);
                if (ke < 0) break;
                out.add(new String[]{nm, body.substring(ks, ke)});
                i = ke;
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    /** 本机在局域网里的 IPv4 地址 */
    /** 本机局域网 IPv4 列表（可能有多个：WiFi、有线、热点、虚拟网卡） */
    public static java.util.List<String> localIps() {
        java.util.List<String> out = new java.util.ArrayList<String>();
        try {
            java.util.Enumeration<java.net.NetworkInterface> en = java.net.NetworkInterface.getNetworkInterfaces();
            while (en.hasMoreElements()) {
                java.net.NetworkInterface ni = en.nextElement();
                if (!ni.isUp() || ni.isLoopback()) continue;
                java.util.Enumeration<java.net.InetAddress> addrs = ni.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    java.net.InetAddress ia = addrs.nextElement();
                    if (ia instanceof java.net.Inet4Address && !ia.isLoopbackAddress()) {
                        String s = ia.getHostAddress();
                        if (s == null) continue;
                        if (s.startsWith("192.168.") || s.startsWith("10.") || s.startsWith("172.")) {
                            if (!out.contains(s)) out.add(s);
                        }
                    }
                }
            }
        } catch (Exception ignored) { }
        return out;
    }

    private static String localIp() {
        java.util.List<String> l = localIps();
        return l.isEmpty() ? null : l.get(0);
    }

    /** 逐个探测同网段 8096/8097，读 /System/Info/Public 拿服务器名（UDP 广播被禁用时也能发现） */
    /** 取某台 Emby 的服务器名（用于补回被存成地址的老条目） */
    public static String serverName(String base) {
        try {
            String body = quickProbe(base + "/System/Info/Public");
            if (body == null) return null;
            String nm = field(body, "ServerName");
            return nm == null || nm.isEmpty() ? null : nm;
        } catch (Exception e) {
            return null;
        }
    }

    public static java.util.List<String[]> scanLan() {
        final long t0 = System.currentTimeMillis();
        final java.util.List<String[]> out =
                java.util.Collections.synchronizedList(new java.util.ArrayList<String[]>());
        final java.util.List<String> local = localIps();
        if (local.isEmpty()) {
            android.util.Log.e("LMBY", "扫描局域网 本机没有局域网 IP");
            return out;
        }

        // 分两组：常见家用网段（192.168 / 10）优先，Docker、虚拟网卡（172.x）放后面
        final java.util.List<String> first = new java.util.ArrayList<String>();
        final java.util.List<String> rest = new java.util.ArrayList<String>();
        java.util.Set<String> prefixes = new java.util.LinkedHashSet<String>();
        for (String ip : local) {
            String p = ip.substring(0, ip.lastIndexOf('.') + 1);
            if (prefixes.add(p)) {
                boolean main = p.startsWith("192.168.") || p.startsWith("10.");
                for (int i = 1; i <= 254; i++) {
                    String h = p + i;
                    if (local.contains(h)) continue;
                    (main ? first : rest).add(h);
                }
            }
        }

        android.util.Log.e("LMBY", "扫描局域网 本机=" + local + " 目标=" + (first.size() + rest.size()));
        // 第一轮：常见家用网段的高并发 8096
        scanPorts(first, new int[]{8096}, 128, 6000, out);
        // 其它网段（Docker/虚拟网卡）只在主网段没结果时才扫
        if (out.isEmpty() && !rest.isEmpty()) scanPorts(rest, new int[]{8096}, 128, 6000, out);
        // 第三轮：还没找到，用更长的超时把常见端口再扫一遍（自建服务器响应可能偏慢）
        if (out.isEmpty()) {
            scanPorts(first.isEmpty() ? rest : first,
                    new int[]{8096, 8097, 8098, 8920, 8090}, 96, 24000, out);
        }
        android.util.Log.e("LMBY", "扫描局域网 完成 命中=" + out.size()
                + L.t(" 耗时=") + (System.currentTimeMillis() - t0) + "ms");
        for (String[] o : out) android.util.Log.e("LMBY", "扫描局域网 服务器=" + o[0] + " " + o[1]);
        return out;
    }

    private static void scanPorts(java.util.List<String> targets, final int[] ports, int nt,
                                  long joinMs, final java.util.List<String[]> out) {
        final java.util.concurrent.atomic.AtomicInteger idx =
                new java.util.concurrent.atomic.AtomicInteger(0);
        Thread[] pool = new Thread[nt];
        for (int t = 0; t < pool.length; t++) {
            pool[t] = new Thread(new Runnable() {
                @Override public void run() {
                    while (true) {
                        int i = idx.getAndIncrement();
                        if (i >= targets.size()) return;
                        String host = targets.get(i);
                        for (int port : ports) {
                            String base = "http://" + host + ":" + port;
                            try {
                                String body = quickProbe(base + "/System/Info/Public");
                                if (body == null) continue;
                                // 飞牛等自建 Emby 可能没设 ServerName，不能因此丢弃：只要确实是 Emby 就收
                                String nm = field(body, "ServerName");
                                if (body.indexOf("ServerName") < 0 && body.indexOf("Emby") < 0
                                        && body.indexOf("ProductName") < 0) continue;
                                if (nm == null || nm.isEmpty()) nm = host;
                                boolean dup = false;
                                for (String[] o : out) if (o[1].equals(base)) dup = true;
                                if (!dup) out.add(new String[]{nm, base});
                                break;
                            } catch (Exception ignored) { }
                        }
                    }
                }
            });
            pool[t].start();
        }
        for (Thread th : pool) {
            try { th.join(joinMs); } catch (Exception ignored) { }
        }
    }


    /** 快速探测：短超时读一小段 JSON，局域网扫描专用 */
    private static String quickProbe(String url) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new java.net.URL(url).openConnection();
            c.setConnectTimeout(420);
            c.setReadTimeout(900);
            c.setRequestProperty("Accept", "application/json");
            int code = c.getResponseCode();
            if (code < 200 || code >= 300) return null;
            java.io.InputStream in = c.getInputStream();
            byte[] buf = new byte[4096];
            int n = in.read(buf);
            in.close();
            return n > 0 ? new String(buf, 0, n, "UTF-8") : null;
        } catch (Exception e) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /** 本机局域网 IP（扫码页面展示用） */
    public static String localIpAddress() {
        return localIp();
    }
}
