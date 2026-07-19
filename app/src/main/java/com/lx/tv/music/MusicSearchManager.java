package com.lx.tv.music;

import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;

import com.lx.tv.music.userapi.UserApiCallback;
import com.lx.tv.music.userapi.UserApiEngine;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 音乐搜索管理器
 * 调用UserApiEngine发送搜索请求，管理搜索结果列表
 *
 * 落雪音源协议说明：
 * - 完整搜索流程需要通过lx.request发起HTTP请求到音源API，解析返回JSON
 * - TV版简化：搜索结果使用测试数据（演示用途），获取播放URL通过音源脚本的musicUrl action
 *
 * 搜索结果数据结构：List<MusicInfo>
 */
public class MusicSearchManager implements UserApiCallback {
    private static final String TAG = "MusicSearchManager";

    /** 默认支持的音源列表 */
    public static final String[] SUPPORTED_SOURCES = {"kw", "kg", "tx", "wy", "mg"};

    /** URL请求超时时间（毫秒） */
    private static final int URL_REQUEST_TIMEOUT = 20000;

    private final UserApiEngine userApiEngine;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Random random = new Random();

    /** requestKey -> UrlRequestListener 映射，用于musicUrl请求 */
    private final Map<String, UrlRequestListener> urlRequestMap = new HashMap<>();
    /** requestKey -> SearchRequestListener 映射，用于搜索请求 */
    private final Map<String, SearchRequestListener> searchRequestMap = new HashMap<>();

    /** 当前已加载的音源能力信息：source -> {actions, qualitys} */
    private final Map<String, SourceCapability> sourceCapabilities = new HashMap<>();

    /** 当前搜索结果 */
    private List<MusicInfo> currentResults = new ArrayList<>();
    private SearchCallback searchCallback;
    private boolean isSearching = false;

    /** 音源能力信息 */
    public static class SourceCapability {
        public String source;
        public String type;
        public List<String> actions = new ArrayList<>();
        public List<String> qualitys = new ArrayList<>();
    }

    /** 搜索结果回调 */
    public interface SearchCallback {
        /** 搜索开始 */
        void onSearchStart(String keyword);
        /** 搜索结果（增量返回，每个音源一次） */
        void onSearchResult(List<MusicInfo> results, String source);
        /** 搜索完成（所有音源都返回） */
        void onSearchComplete(String keyword, List<MusicInfo> allResults);
        /** 搜索失败 */
        void onSearchError(String errorMessage);
    }

    /** URL请求监听器 */
    public interface UrlRequestListener {
        void onUrlSuccess(String url);
        void onUrlError(String errorMessage);
    }

    /** 搜索请求监听器（用于完整搜索流程） */
    public interface SearchRequestListener {
        void onSearchSuccess(List<MusicInfo> results);
        void onSearchError(String errorMessage);
    }

    public MusicSearchManager(UserApiEngine userApiEngine) {
        this.userApiEngine = userApiEngine;
        // 注意：不在此处调用 userApiEngine.setCallback(this)
        // MainActivity 作为统一回调分发器，会将事件分发到 MusicPlayer 和 MusicSearchManager
    }

    public void setSearchCallback(SearchCallback callback) {
        this.searchCallback = callback;
    }

    /**
     * 搜索音乐
     * 简化实现：使用测试数据演示。完整实现需要通过音源脚本发送搜索HTTP请求。
     *
     * @param keyword 搜索关键词
     */
    public void search(final String keyword) {
        if (TextUtils.isEmpty(keyword)) {
            if (searchCallback != null) searchCallback.onSearchError("关键词为空");
            return;
        }
        if (isSearching) {
            Log.w(TAG, "Search already in progress");
            return;
        }
        isSearching = true;
        currentResults = new ArrayList<>();
        if (searchCallback != null) searchCallback.onSearchStart(keyword);

        // 简化版：使用测试数据模拟搜索结果
        // 完整实现：对每个支持的音源发送搜索HTTP请求并解析
        mainHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                List<MusicInfo> testResults = generateTestResults(keyword);
                currentResults.addAll(testResults);
                if (searchCallback != null) {
                    searchCallback.onSearchResult(testResults, "kw");
                    searchCallback.onSearchComplete(keyword, currentResults);
                }
                isSearching = false;
            }
        }, 300);
    }

    /**
     * 通过UserApiEngine获取播放URL
     * 流程：
     * 1. 生成requestKey
     * 2. sendAction('request', {requestKey, data:{source, action:'musicUrl', info:{type:'128k', musicInfo}}})
     * 3. 在onResponse回调中获取url
     *
     * @param music 歌曲信息
     * @param quality 音质：128k/320k/flac
     * @param listener 监听器
     */
    public void getMusicUrl(MusicInfo music, String quality, final UrlRequestListener listener) {
        if (music == null) {
            if (listener != null) listener.onUrlError("music is null");
            return;
        }
        if (userApiEngine == null) {
            if (listener != null) listener.onUrlError("UserApiEngine未初始化");
            return;
        }
        final String requestKey = "search_url_" + System.currentTimeMillis() + "_" + random.nextInt(10000);
        urlRequestMap.put(requestKey, listener);

        try {
            JSONObject requestData = new JSONObject();
            requestData.put("requestKey", requestKey);

            JSONObject data = new JSONObject();
            data.put("source", music.source);
            data.put("action", "musicUrl");

            JSONObject info = new JSONObject();
            info.put("type", quality != null ? quality : "128k");
            info.put("musicInfo", music.toJson());
            data.put("info", info);

            requestData.put("data", data);

            userApiEngine.sendAction("request", requestData.toString());
            Log.i(TAG, "Request music url: key=" + requestKey + " source=" + music.source);

            // 超时处理
            mainHandler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    UrlRequestListener l = urlRequestMap.remove(requestKey);
                    if (l != null) {
                        l.onUrlError("获取播放链接超时");
                    }
                }
            }, URL_REQUEST_TIMEOUT);
        } catch (JSONException e) {
            Log.e(TAG, "getMusicUrl JSON error: " + e.getMessage());
            urlRequestMap.remove(requestKey);
            if (listener != null) listener.onUrlError("请求构造失败: " + e.getMessage());
        }
    }

    /**
     * 通过UserApiEngine获取歌词
     */
    public void getLyric(MusicInfo music, final LyricRequestListener listener) {
        if (music == null || userApiEngine == null) {
            if (listener != null) listener.onLyricError("参数错误");
            return;
        }
        final String requestKey = "lyric_" + System.currentTimeMillis() + "_" + random.nextInt(10000);
        // 复用UrlRequestListener的字段，但data中是lyric内容
        urlRequestMap.put(requestKey, new UrlRequestListener() {
            @Override
            public void onUrlSuccess(String url) {
                // 这里实际是lyric内容
                if (listener != null) listener.onLyricSuccess(url);
            }
            @Override
            public void onUrlError(String errorMessage) {
                if (listener != null) listener.onLyricError(errorMessage);
            }
        });

        try {
            JSONObject requestData = new JSONObject();
            requestData.put("requestKey", requestKey);

            JSONObject data = new JSONObject();
            data.put("source", music.source);
            data.put("action", "lyric");

            JSONObject info = new JSONObject();
            info.put("musicInfo", music.toJson());
            data.put("info", info);

            requestData.put("data", data);

            userApiEngine.sendAction("request", requestData.toString());
        } catch (JSONException e) {
            urlRequestMap.remove(requestKey);
            if (listener != null) listener.onLyricError("请求构造失败: " + e.getMessage());
        }
    }

    /** 歌词请求监听器 */
    public interface LyricRequestListener {
        void onLyricSuccess(String lyricContent);
        void onLyricError(String errorMessage);
    }

    /**
     * 获取当前搜索结果
     */
    public List<MusicInfo> getCurrentResults() {
        return new ArrayList<>(currentResults);
    }

    public boolean isSearching() {
        return isSearching;
    }

    /**
     * 取消所有请求
     */
    public void cancelAllRequests() {
        urlRequestMap.clear();
        searchRequestMap.clear();
    }

    /**
     * 获取已加载的音源能力
     */
    public Map<String, SourceCapability> getSourceCapabilities() {
        return sourceCapabilities;
    }

    /**
     * 检查指定音源是否支持某action
     */
    public boolean isActionSupported(String source, String action) {
        SourceCapability cap = sourceCapabilities.get(source);
        if (cap == null) return false;
        return cap.actions.contains(action);
    }

    // ============ UserApiCallback 实现 ============

    @Override
    public void onInit(boolean status, String errorMessage, Object sources) {
        Log.i(TAG, "onInit: status=" + status + " msg=" + errorMessage);
        if (!status || sources == null) {
            return;
        }
        try {
            JSONObject json;
            if (sources instanceof JSONObject) {
                json = (JSONObject) sources;
            } else if (sources instanceof String) {
                json = new JSONObject((String) sources);
            } else {
                json = new JSONObject(sources.toString());
            }
            JSONObject sourcesObj = json.optJSONObject("sources");
            if (sourcesObj == null) return;
            sourceCapabilities.clear();
            for (String source : SUPPORTED_SOURCES) {
                JSONObject cap = sourcesObj.optJSONObject(source);
                if (cap == null) continue;
                SourceCapability sc = new SourceCapability();
                sc.source = source;
                sc.type = cap.optString("type", "");
                if (!"music".equals(sc.type)) continue;
                JSONArray actions = cap.optJSONArray("actions");
                if (actions != null) {
                    for (int i = 0; i < actions.length(); i++) {
                        sc.actions.add(actions.optString(i));
                    }
                }
                JSONArray qualitys = cap.optJSONArray("qualitys");
                if (qualitys != null) {
                    for (int i = 0; i < qualitys.length(); i++) {
                        sc.qualitys.add(qualitys.optString(i));
                    }
                }
                sourceCapabilities.put(source, sc);
                Log.i(TAG, "Source " + source + " supports: " + sc.actions);
            }
        } catch (JSONException e) {
            Log.e(TAG, "onInit parse error: " + e.getMessage());
        }
    }

    @Override
    public void onResponse(String requestKey, boolean status, String errorMessage, Object result) {
        Log.d(TAG, "onResponse: key=" + requestKey + " status=" + status);
        UrlRequestListener listener = urlRequestMap.remove(requestKey);
        if (listener == null) return;
        if (!status) {
            listener.onUrlError(errorMessage != null ? errorMessage : "请求失败");
            return;
        }
        try {
            String data = null;
            if (result != null) {
                JSONObject json;
                if (result instanceof JSONObject) {
                    json = (JSONObject) result;
                } else if (result instanceof String) {
                    json = new JSONObject((String) result);
                } else {
                    json = new JSONObject(result.toString());
                }
                JSONObject dataObj = json.optJSONObject("data");
                if (dataObj != null) {
                    // musicUrl: data = {type, url}
                    // lyric: data = {lyric, tlyric, ...}
                    String url = dataObj.optString("url", null);
                    if (!TextUtils.isEmpty(url)) {
                        data = url;
                    } else {
                        String lyric = dataObj.optString("lyric", null);
                        if (!TextUtils.isEmpty(lyric)) {
                            data = lyric;
                        } else {
                            data = dataObj.toString();
                        }
                    }
                }
                if (data == null) {
                    data = json.optString("url", null);
                }
            }
            if (TextUtils.isEmpty(data)) {
                listener.onUrlError("响应数据为空");
            } else {
                listener.onUrlSuccess(data);
            }
        } catch (JSONException e) {
            listener.onUrlError("解析响应失败: " + e.getMessage());
        }
    }

    @Override
    public void onRequest(String requestKey, String url, Object options) {
        // 由MainActivity的HttpExecutor处理OkHttp请求
    }

    @Override
    public void onCancelRequest(String requestKey) {
        urlRequestMap.remove(requestKey);
        searchRequestMap.remove(requestKey);
    }

    @Override
    public void onShowUpdateAlert(String name, String log, String updateUrl) {
        Log.i(TAG, "Update alert: " + name + " - " + log);
    }

    @Override
    public void onLog(String type, String log) {
        Log.d(TAG, "[UserApi:" + type + "] " + log);
    }

    // ============ 测试数据生成 ============

    /**
     * 生成测试搜索结果（简化版搜索）
     * 实际项目中应通过音源脚本发送搜索HTTP请求并解析返回的JSON
     */
    private List<MusicInfo> generateTestResults(String keyword) {
        List<MusicInfo> results = new ArrayList<>();
        // 模拟5个音源的搜索结果
        String[] songSuffixes = {"", " (Live)", " (Remix)", " (DJ版)", " (伴奏)"};
        String[] singers = {"周杰伦", "林俊杰", "邓紫棋", "薛之谦", "毛不易",
                "华晨宇", "李荣浩", "田馥甄", "陈奕迅", "张学友"};
        String[] albums = {"范特西", "第二天堂", "新的心跳", "初学者", "平凡的一天",
                "新世界", "模特", "小幸运", "Stranger Under My Skin", "True"};

        for (int sourceIdx = 0; sourceIdx < SUPPORTED_SOURCES.length; sourceIdx++) {
            String source = SUPPORTED_SOURCES[sourceIdx];
            int count = 3 + random.nextInt(3); // 每个音源3-5首
            for (int i = 0; i < count; i++) {
                MusicInfo info = new MusicInfo();
                info.source = source;
                info.songName = keyword + " - " + songsForSource(source, i);
                info.singer = singers[random.nextInt(singers.length)];
                info.album = albums[random.nextInt(albums.length)];
                info.songmid = source + "_sm_" + System.currentTimeMillis() + "_" + i;
                info.id = source + "_" + info.songmid;
                int totalSec = 180 + random.nextInt(180);
                info.interval = String.format("%02d:%02d", totalSec / 60, totalSec % 60);
                // 各源专用字段
                if ("kg".equals(source)) {
                    info.hash = "kg_hash_" + i + "_" + random.nextInt(100000);
                } else if ("tx".equals(source)) {
                    info.strMediaMid = "tx_mid_" + i + "_" + random.nextInt(100000);
                } else if ("mg".equals(source)) {
                    info.copyrightId = "mg_ci_" + i + "_" + random.nextInt(100000);
                }
                results.add(info);
            }
        }
        return results;
    }

    private String songsForSource(String source, int idx) {
        String[] songs = {"晴天", "稻香", "青花瓷", "夜曲", "七里香",
                "红豆", "童话", "遇见", "后来", "勇气"};
        return songs[idx % songs.length];
    }
}
