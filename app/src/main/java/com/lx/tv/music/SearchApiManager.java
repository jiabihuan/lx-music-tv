package com.lx.tv.music;

import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * 搜索API管理器
 * 直接调用各音源的公开搜索API获取搜索结果（与落雪音乐内置搜索逻辑一致）。
 *
 * 支持音源：
 *   - 酷我(kw): http://search.kuwo.cn/r.s?all=xxx&ft=music&...
 *   - 酷狗(kg): http://mobilecdn.kugou.com/api/v3/search/song?keyword=xxx&...
 *   - QQ音乐(tx): https://c.y.qq.com/soso/fcgi-bin/client_search_cp?w=xxx&...
 *   - 网易云(wy): https://music.163.com/api/search/get?s=xxx&...
 *   - 咪咕(mg): https://m.music.migu.cn/migu/remoting/scr_search_tag?keyword=xxx&...
 *
 * 多音源并发搜索，结果合并返回。任一音源失败不影响其他音源。
 */
public class SearchApiManager {
    private static final String TAG = "SearchApiManager";

    /** 音源标识 */
    public static final String SOURCE_KUWO = "kw";
    public static final String SOURCE_KUGOU = "kg";
    public static final String SOURCE_QQ = "tx";
    public static final String SOURCE_WANGYI = "wy";
    public static final String SOURCE_MIGU = "mg";

    /** 所有支持的音源 */
    public static final String[] ALL_SOURCES = {SOURCE_KUWO, SOURCE_KUGOU, SOURCE_QQ, SOURCE_WANGYI, SOURCE_MIGU};

    /** 默认每页结果数 */
    private static final int DEFAULT_PAGE_SIZE = 20;

    /** 浏览器UA */
    private static final String USER_AGENT =
            "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 (KHTML, like Gecko) " +
                    "Chrome/120.0.0.0 Mobile Safari/537.36";

    private final OkHttpClient httpClient;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    /** 搜索回调 */
    public interface SearchCallback {
        /** 搜索开始 */
        void onSearchStart(String keyword);
        /** 单个音源搜索结果（增量返回） */
        void onSourceResult(List<MusicInfo> results, String source);
        /** 所有音源搜索完成 */
        void onSearchComplete(String keyword, List<MusicInfo> allResults);
        /** 搜索失败（全部音源都失败时触发） */
        void onSearchError(String errorMessage);
    }

    public SearchApiManager(OkHttpClient httpClient) {
        this.httpClient = httpClient != null ? httpClient : createDefaultClient();
    }

    private static OkHttpClient createDefaultClient() {
        return new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .writeTimeout(15, TimeUnit.SECONDS)
                .build();
    }

    /**
     * 搜索音乐（并发搜索所有音源）
     * @param keyword 搜索关键词
     * @param callback 回调（主线程）
     */
    public void search(String keyword, final SearchCallback callback) {
        if (TextUtils.isEmpty(keyword)) {
            if (callback != null) callback.onSearchError("关键词为空");
            return;
        }

        final String trimmedKeyword = keyword.trim();
        Log.i(TAG, "search: " + trimmedKeyword);

        if (callback != null) callback.onSearchStart(trimmedKeyword);

        final List<MusicInfo> allResults = new ArrayList<>();
        // 使用AtomicInteger计数已完成的音源数
        final AtomicInteger completedCount = new AtomicInteger(0);
        final int totalSources = ALL_SOURCES.length;
        final boolean[] hasError = {false};

        // 为每个音源发起搜索
        for (final String source : ALL_SOURCES) {
            searchBySource(trimmedKeyword, source, new SourceSearchListener() {
                @Override
                public void onSuccess(List<MusicInfo> results) {
                    Log.i(TAG, "search " + source + " success: " + results.size() + " results");
                    synchronized (allResults) {
                        allResults.addAll(results);
                    }
                    if (callback != null) {
                        final List<MusicInfo> copy = new ArrayList<>(results);
                        mainHandler.post(() -> callback.onSourceResult(copy, source));
                    }
                    checkComplete();
                }

                @Override
                public void onError(String errorMessage) {
                    Log.w(TAG, "search " + source + " failed: " + errorMessage);
                    checkComplete();
                }

                private void checkComplete() {
                    if (completedCount.incrementAndGet() == totalSources) {
                        mainHandler.post(() -> {
                            if (callback != null) {
                                if (allResults.isEmpty()) {
                                    callback.onSearchError("所有音源搜索均无结果");
                                } else {
                                    callback.onSearchComplete(trimmedKeyword,
                                            new ArrayList<>(allResults));
                                }
                            }
                        });
                    }
                }
            });
        }
    }

    /** 单音源搜索监听器 */
    private interface SourceSearchListener {
        void onSuccess(List<MusicInfo> results);
        void onError(String errorMessage);
    }

    /**
     * 根据音源类型分发搜索请求
     */
    private void searchBySource(String keyword, String source, SourceSearchListener listener) {
        switch (source) {
            case SOURCE_KUWO:
                searchKuwo(keyword, listener);
                break;
            case SOURCE_KUGOU:
                searchKugou(keyword, listener);
                break;
            case SOURCE_QQ:
                searchQQ(keyword, listener);
                break;
            case SOURCE_WANGYI:
                searchWangyi(keyword, listener);
                break;
            case SOURCE_MIGU:
                searchMigu(keyword, listener);
                break;
            default:
                listener.onError("未知音源: " + source);
        }
    }

    // ============ 酷我搜索 ============
    private void searchKuwo(String keyword, SourceSearchListener listener) {
        try {
            String url = "http://search.kuwo.cn/r.s?all=" + URLEncoder.encode(keyword, "UTF-8")
                    + "&ft=music&itemset=web_2013&client=kt&pn=0&rn=" + DEFAULT_PAGE_SIZE
                    + "&rformat=json&encoding=utf8";
            Request request = new Request.Builder()
                    .url(url)
                    .header("User-Agent", USER_AGENT)
                    .header("Referer", "https://www.kuwo.cn/")
                    .header("Accept", "*/*")
                    .get()
                    .build();
            httpClient.newCall(request).enqueue(new SearchCallbackAdapter(listener) {
                @Override
                protected List<MusicInfo> parse(String body) throws JSONException {
                    List<MusicInfo> list = new ArrayList<>();
                    JSONObject json = new JSONObject(body);
                    JSONArray abslist = json.optJSONArray("abslist");
                    if (abslist == null) return list;
                    for (int i = 0; i < abslist.length(); i++) {
                        JSONObject item = abslist.optJSONObject(i);
                        if (item == null) continue;
                        MusicInfo info = new MusicInfo();
                        info.source = SOURCE_KUWO;
                        info.songName = unescapeJson(item.optString("SONGNAME", ""));
                        info.singer = unescapeJson(item.optString("ARTIST", ""));
                        info.album = unescapeJson(item.optString("ALBUM", ""));
                        String rid = item.optString("DC_TARGETID", "");
                        if (TextUtils.isEmpty(rid)) rid = item.optString("MUSICRID", "");
                        if (TextUtils.isEmpty(rid)) rid = item.optString("rid", "");
                        info.songmid = rid;
                        info.id = rid;
                        // 时长（秒）
                        int duration = 0;
                        try {
                            duration = Integer.parseInt(item.optString("DURATION", "0"));
                        } catch (NumberFormatException ignored) {
                        }
                        if (duration > 0) {
                            info.interval = String.format("%02d:%02d", duration / 60, duration % 60);
                        }
                        // 专辑封面
                        String pic = item.optString("web_albumpic_short", "");
                        if (!TextUtils.isEmpty(pic)) {
                            info.picUrl = pic.startsWith("http") ? pic : "https://img1.kuwo.cn/star/albumcover/" + pic;
                        }
                        if (!TextUtils.isEmpty(info.songName)) {
                            list.add(info);
                        }
                    }
                    return list;
                }
            });
        } catch (Exception e) {
            listener.onError("酷我搜索请求构造失败: " + e.getMessage());
        }
    }

    // ============ 酷狗搜索 ============
    private void searchKugou(String keyword, SourceSearchListener listener) {
        try {
            String url = "http://mobilecdn.kugou.com/api/v3/search/song?keyword="
                    + URLEncoder.encode(keyword, "UTF-8")
                    + "&pagesize=" + DEFAULT_PAGE_SIZE + "&page=1";
            Request request = new Request.Builder()
                    .url(url)
                    .header("User-Agent", USER_AGENT)
                    .header("Referer", "https://www.kugou.com/")
                    .header("Accept", "application/json, */*")
                    .get()
                    .build();
            httpClient.newCall(request).enqueue(new SearchCallbackAdapter(listener) {
                @Override
                protected List<MusicInfo> parse(String body) throws JSONException {
                    List<MusicInfo> list = new ArrayList<>();
                    JSONObject json = new JSONObject(body);
                    JSONObject data = json.optJSONObject("data");
                    if (data == null) return list;
                    JSONArray lists = data.optJSONArray("lists");
                    if (lists == null) return list;
                    for (int i = 0; i < lists.length(); i++) {
                        JSONObject item = lists.optJSONObject(i);
                        if (item == null) continue;
                        MusicInfo info = new MusicInfo();
                        info.source = SOURCE_KUGOU;
                        info.songName = unescapeJson(item.optString("SongName", ""));
                        info.singer = unescapeJson(item.optString("SingerName", ""));
                        info.album = unescapeJson(item.optString("AlbumName", ""));
                        String hash = item.optString("FileHash", "");
                        if (TextUtils.isEmpty(hash)) hash = item.optString("hash", "");
                        info.hash = hash;
                        info.songmid = hash;
                        info.id = hash;
                        int duration = item.optInt("Duration", 0);
                        if (duration <= 0) {
                            try {
                                duration = Integer.parseInt(item.optString("duration", "0"));
                            } catch (NumberFormatException ignored) {
                            }
                        }
                        if (duration > 0) {
                            info.interval = String.format("%02d:%02d", duration / 60, duration % 60);
                        }
                        if (!TextUtils.isEmpty(info.songName)) {
                            list.add(info);
                        }
                    }
                    return list;
                }
            });
        } catch (Exception e) {
            listener.onError("酷狗搜索请求构造失败: " + e.getMessage());
        }
    }

    // ============ QQ音乐搜索 ============
    private void searchQQ(String keyword, SourceSearchListener listener) {
        try {
            String url = "https://c.y.qq.com/soso/fcgi-bin/client_search_cp?w="
                    + URLEncoder.encode(keyword, "UTF-8")
                    + "&format=json&n=" + DEFAULT_PAGE_SIZE + "&p=1&cr=1&g_tk=5381";
            Request request = new Request.Builder()
                    .url(url)
                    .header("User-Agent", USER_AGENT)
                    .header("Referer", "https://y.qq.com/")
                    .header("Accept", "application/json, */*")
                    .get()
                    .build();
            httpClient.newCall(request).enqueue(new SearchCallbackAdapter(listener) {
                @Override
                protected List<MusicInfo> parse(String body) throws JSONException {
                    List<MusicInfo> list = new ArrayList<>();
                    // QQ音乐返回可能被 jsonp 包裹，需要提取
                    String jsonStr = extractJsonFromJsonp(body);
                    JSONObject json = new JSONObject(jsonStr);
                    JSONObject data = json.optJSONObject("data");
                    if (data == null) return list;
                    JSONObject song = data.optJSONObject("song");
                    if (song == null) return list;
                    JSONArray songList = song.optJSONArray("list");
                    if (songList == null) return list;
                    for (int i = 0; i < songList.length(); i++) {
                        JSONObject item = songList.optJSONObject(i);
                        if (item == null) continue;
                        MusicInfo info = new MusicInfo();
                        info.source = SOURCE_QQ;
                        info.songName = unescapeJson(item.optString("songname", ""));
                        // singer 是数组
                        JSONArray singers = item.optJSONArray("singer");
                        StringBuilder singerBuf = new StringBuilder();
                        if (singers != null) {
                            for (int j = 0; j < singers.length(); j++) {
                                JSONObject s = singers.optJSONObject(j);
                                if (s != null) {
                                    if (singerBuf.length() > 0) singerBuf.append("/");
                                    singerBuf.append(s.optString("name", ""));
                                }
                            }
                        }
                        info.singer = singerBuf.toString();
                        info.album = unescapeJson(item.optString("albumname", ""));
                        String songmid = item.optString("songmid", "");
                        info.strMediaMid = item.optString("strMediaMid", "");
                        info.songmid = songmid;
                        info.id = songmid;
                        int interval = item.optInt("interval", 0);
                        if (interval > 0) {
                            info.interval = String.format("%02d:%02d", interval / 60, interval % 60);
                        }
                        // 专辑封面
                        String albummid = item.optString("albummid", "");
                        if (!TextUtils.isEmpty(albummid)) {
                            info.picUrl = "https://y.gtimg.cn/music/photo_new/T002R300x300M000" + albummid + ".jpg";
                        }
                        if (!TextUtils.isEmpty(info.songName)) {
                            list.add(info);
                        }
                    }
                    return list;
                }
            });
        } catch (Exception e) {
            listener.onError("QQ音乐搜索请求构造失败: " + e.getMessage());
        }
    }

    // ============ 网易云搜索 ============
    private void searchWangyi(String keyword, SourceSearchListener listener) {
        try {
            // 网易云搜索API，使用GET请求
            String url = "https://music.163.com/api/search/get?s="
                    + URLEncoder.encode(keyword, "UTF-8")
                    + "&type=1&offset=0&limit=" + DEFAULT_PAGE_SIZE;
            Request request = new Request.Builder()
                    .url(url)
                    .header("User-Agent", USER_AGENT)
                    .header("Referer", "https://music.163.com/")
                    .header("Accept", "application/json, */*")
                    .get()
                    .build();
            httpClient.newCall(request).enqueue(new SearchCallbackAdapter(listener) {
                @Override
                protected List<MusicInfo> parse(String body) throws JSONException {
                    List<MusicInfo> list = new ArrayList<>();
                    JSONObject json = new JSONObject(body);
                    JSONObject result = json.optJSONObject("result");
                    if (result == null) return list;
                    JSONArray songs = result.optJSONArray("songs");
                    if (songs == null) return list;
                    for (int i = 0; i < songs.length(); i++) {
                        JSONObject item = songs.optJSONObject(i);
                        if (item == null) continue;
                        MusicInfo info = new MusicInfo();
                        info.source = SOURCE_WANGYI;
                        info.songName = unescapeJson(item.optString("name", ""));
                        // artists 数组
                        JSONArray artists = item.optJSONArray("artists");
                        StringBuilder singerBuf = new StringBuilder();
                        if (artists != null) {
                            for (int j = 0; j < artists.length(); j++) {
                                JSONObject a = artists.optJSONObject(j);
                                if (a != null) {
                                    if (singerBuf.length() > 0) singerBuf.append("/");
                                    singerBuf.append(a.optString("name", ""));
                                }
                            }
                        }
                        info.singer = singerBuf.toString();
                        JSONObject album = item.optJSONObject("album");
                        if (album != null) {
                            info.album = unescapeJson(album.optString("name", ""));
                            String pic = album.optString("picUrl", "");
                            if (!TextUtils.isEmpty(pic)) {
                                info.picUrl = pic;
                            }
                        }
                        String id = item.optString("id", "");
                        info.songmid = id;
                        info.id = id;
                        // duration 是毫秒
                        long duration = item.optLong("duration", 0);
                        if (duration > 0) {
                            long sec = duration / 1000;
                            info.interval = String.format("%02d:%02d", sec / 60, sec % 60);
                        }
                        if (!TextUtils.isEmpty(info.songName)) {
                            list.add(info);
                        }
                    }
                    return list;
                }
            });
        } catch (Exception e) {
            listener.onError("网易云搜索请求构造失败: " + e.getMessage());
        }
    }

    // ============ 咪咕搜索 ============
    private void searchMigu(String keyword, SourceSearchListener listener) {
        try {
            String url = "https://m.music.migu.cn/migu/remoting/scr_search_tag?keyword="
                    + URLEncoder.encode(keyword, "UTF-8")
                    + "&type=2&rows=" + DEFAULT_PAGE_SIZE + "&pgc=1";
            Request request = new Request.Builder()
                    .url(url)
                    .header("User-Agent", USER_AGENT)
                    .header("Referer", "https://m.music.migu.cn/")
                    .header("Accept", "application/json, */*")
                    .get()
                    .build();
            httpClient.newCall(request).enqueue(new SearchCallbackAdapter(listener) {
                @Override
                protected List<MusicInfo> parse(String body) throws JSONException {
                    List<MusicInfo> list = new ArrayList<>();
                    JSONObject json = new JSONObject(body);
                    JSONArray musics = json.optJSONArray("musics");
                    if (musics == null) {
                        // 部分接口返回在 musics 同级
                        JSONObject musicsObj = json.optJSONObject("musics");
                        if (musicsObj != null) {
                            musics = musicsObj.optJSONArray("list");
                        }
                    }
                    if (musics == null) return list;
                    for (int i = 0; i < musics.length(); i++) {
                        JSONObject item = musics.optJSONObject(i);
                        if (item == null) continue;
                        MusicInfo info = new MusicInfo();
                        info.source = SOURCE_MIGU;
                        info.songName = unescapeJson(item.optString("songName", ""));
                        info.singer = unescapeJson(item.optString("singer", ""));
                        info.album = unescapeJson(item.optString("albumName", ""));
                        String copyrightId = item.optString("copyrightId", "");
                        if (TextUtils.isEmpty(copyrightId)) copyrightId = item.optString("id", "");
                        info.copyrightId = copyrightId;
                        info.songmid = copyrightId;
                        info.id = copyrightId;
                        // 时长
                        String durationStr = item.optString("duration", "");
                        if (!TextUtils.isEmpty(durationStr)) {
                            try {
                                int duration = Integer.parseInt(durationStr);
                                if (duration > 0) {
                                    info.interval = String.format("%02d:%02d", duration / 60, duration % 60);
                                }
                            } catch (NumberFormatException ignored) {
                            }
                        }
                        // 封面
                        String cover = item.optString("cover", "");
                        if (TextUtils.isEmpty(cover)) cover = item.optString("albumPic", "");
                        if (!TextUtils.isEmpty(cover)) {
                            info.picUrl = cover;
                        }
                        if (!TextUtils.isEmpty(info.songName)) {
                            list.add(info);
                        }
                    }
                    return list;
                }
            });
        } catch (Exception e) {
            listener.onError("咪咕搜索请求构造失败: " + e.getMessage());
        }
    }

    // ============ 工具方法 ============

    /**
     * OkHttp回调适配器，统一处理网络错误和JSON解析
     */
    private abstract class SearchCallbackAdapter implements Callback {
        private final SourceSearchListener listener;

        SearchCallbackAdapter(SourceSearchListener listener) {
            this.listener = listener;
        }

        protected abstract List<MusicInfo> parse(String body) throws JSONException;

        @Override
        public void onFailure(Call call, IOException e) {
            listener.onError("网络请求失败: " + e.getMessage());
        }

        @Override
        public void onResponse(Call call, Response response) throws IOException {
            final String body;
            try {
                if (!response.isSuccessful()) {
                    listener.onError("HTTP " + response.code());
                    return;
                }
                ResponseBody responseBody = response.body();
                body = responseBody != null ? responseBody.string() : "";
            } finally {
                response.close();
            }
            try {
                List<MusicInfo> list = parse(body);
                listener.onSuccess(list);
            } catch (Exception e) {
                listener.onError("解析失败: " + e.getMessage());
            }
        }
    }

    /**
     * 解码JSON字符串中的Unicode转义（backslash-u-XXXX 形式）
     */
    private static String unescapeJson(String s) {
        if (s == null || s.isEmpty()) return s;
        // 检测是否包含 Unicode 转义前缀（反斜杠+u）
        char backslash = 0x5C;
        char uChar = 0x75;
        if (s.indexOf(backslash) < 0) return s;
        boolean hasUnicodeEscape = false;
        for (int i = 0; i < s.length() - 1; i++) {
            if (s.charAt(i) == backslash && s.charAt(i + 1) == uChar) {
                hasUnicodeEscape = true;
                break;
            }
        }
        if (!hasUnicodeEscape) return s;
        try {
            // 利用JSON解析来解码Unicode转义
            return new JSONObject().put("v", s).optString("v");
        } catch (JSONException e) {
            return s;
        }
    }

    /**
     * 从JSONP响应中提取JSON字符串
     * 例如：callback({"code":0,...}) -> {"code":0,...}
     */
    private static String extractJsonFromJsonp(String body) {
        if (body == null) return "{}";
        body = body.trim();
        if (body.startsWith("{")) return body;
        int start = body.indexOf("(");
        int end = body.lastIndexOf(")");
        if (start > 0 && end > start) {
            return body.substring(start + 1, end);
        }
        return body;
    }
}
