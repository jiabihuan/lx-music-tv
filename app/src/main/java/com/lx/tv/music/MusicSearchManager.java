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

import okhttp3.OkHttpClient;

/**
 * 音乐搜索管理器
 * 通过 SearchApiManager 调用各音源公开搜索API获取真实搜索结果，
 * 通过 UserApiEngine 获取播放URL和歌词。
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

    /** 真实搜索API管理器（调用各音源公开搜索接口） */
    private SearchApiManager searchApiManager;

    /** requestKey -> UrlRequestListener 映射，用于musicUrl请求 */
    private final Map<String, UrlRequestListener> urlRequestMap = new HashMap<>();

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

    public MusicSearchManager(UserApiEngine userApiEngine) {
        this.userApiEngine = userApiEngine;
        // 注意：不在此处调用 userApiEngine.setCallback(this)
        // MainActivity 作为统一回调分发器，会将事件分发到 MusicPlayer 和 MusicSearchManager
    }

    /**
     * 设置OkHttpClient用于搜索API请求
     */
    public void setSearchHttpClient(OkHttpClient httpClient) {
        this.searchApiManager = new SearchApiManager(httpClient);
    }

    public void setSearchCallback(SearchCallback callback) {
        this.searchCallback = callback;
    }

    /**
     * 搜索音乐
     * 通过 SearchApiManager 并发调用各音源（酷我/酷狗/QQ/网易云/咪咕）的公开搜索API，
     * 获取真实搜索结果并合并返回。任一音源失败不影响其他音源。
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
        if (searchApiManager == null) {
            if (searchCallback != null) searchCallback.onSearchError("搜索引擎未初始化");
            return;
        }
        isSearching = true;
        currentResults = new ArrayList<>();
        if (searchCallback != null) searchCallback.onSearchStart(keyword);

        // 调用真实搜索API，并发搜索所有音源
        searchApiManager.search(keyword, new SearchApiManager.SearchCallback() {
            @Override
            public void onSearchStart(String kw) {
                // 已在上面通知过
            }

            @Override
            public void onSourceResult(List<MusicInfo> results, String source) {
                synchronized (currentResults) {
                    currentResults.addAll(results);
                }
                if (searchCallback != null) {
                    searchCallback.onSearchResult(results, source);
                }
            }

            @Override
            public void onSearchComplete(String kw, List<MusicInfo> allResults) {
                isSearching = false;
                if (searchCallback != null) {
                    searchCallback.onSearchComplete(kw, allResults);
                }
            }

            @Override
            public void onSearchError(String errorMessage) {
                isSearching = false;
                if (searchCallback != null) {
                    searchCallback.onSearchError(errorMessage);
                }
            }
        });
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
    }

    @Override
    public void onShowUpdateAlert(String name, String log, String updateUrl) {
        Log.i(TAG, "Update alert: " + name + " - " + log);
    }

    @Override
    public void onLog(String type, String log) {
        Log.d(TAG, "[UserApi:" + type + "] " + log);
    }
}
