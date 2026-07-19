package com.lx.tv.music;

import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;

import org.json.JSONObject;

import java.io.IOException;
import java.net.URLEncoder;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * 歌词直接API解析器
 *
 * 直接调用各音源公开API获取歌词，避免QuickJS引擎崩溃。
 *
 * 支持音源：
 *   - 酷我(kw): http://m.kuwo.cn/newlyric.lrc?id={id}
 *   - 酷狗(kg): http://m.kugou.com/app/i/klyric.php?hash={hash}
 *   - 网易云(wy): https://music.163.com/api/song/lyric?id={id}&lv=1
 *   - QQ音乐(tx): https://c.y.qq.com/lyric/fcgi-bin/fcg_query_lyric_new.fcg?songmid={songmid}
 *   - 咪咕(mg): 暂不支持
 */
public class LyricApiManager {
    private static final String TAG = "LyricApiManager";

    private static final String USER_AGENT =
            "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 (KHTML, like Gecko) " +
                    "Chrome/120.0.0.0 Mobile Safari/537.36";

    private final OkHttpClient httpClient;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public interface LyricCallback {
        void onSuccess(String lyricContent);
        void onError(String errorMessage);
    }

    public LyricApiManager(OkHttpClient httpClient) {
        if (httpClient != null) {
            this.httpClient = httpClient;
        } else {
            this.httpClient = new OkHttpClient.Builder()
                    .connectTimeout(10, TimeUnit.SECONDS)
                    .readTimeout(15, TimeUnit.SECONDS)
                    .build();
        }
    }

    public void getLyric(final MusicInfo music, final LyricCallback callback) {
        if (music == null || TextUtils.isEmpty(music.source)) {
            notifyError(callback, "music or source is null");
            return;
        }
        Log.i(TAG, "getLyric: " + music.songName + " from " + music.source);

        switch (music.source) {
            case SearchApiManager.SOURCE_KUWO:
                getLyricKuwo(music, callback);
                break;
            case SearchApiManager.SOURCE_KUGOU:
                getLyricKugou(music, callback);
                break;
            case SearchApiManager.SOURCE_WANGYI:
                getLyricWangyi(music, callback);
                break;
            case SearchApiManager.SOURCE_QQ:
                getLyricQQ(music, callback);
                break;
            case SearchApiManager.SOURCE_MIGU:
                notifyError(callback, "咪咕暂不支持歌词");
                break;
            default:
                notifyError(callback, "未知音源: " + music.source);
        }
    }

    // ============ 酷我 ============
    private void getLyricKuwo(MusicInfo music, final LyricCallback callback) {
        String id = getFirstNonEmpty(music.songmid, music.id);
        if (TextUtils.isEmpty(id)) {
            notifyError(callback, "酷我歌曲ID为空");
            return;
        }
        String url = "http://m.kuwo.cn/newlyric.lrc?id=" + id;
        Request request = new Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .header("Referer", "https://m.kuwo.cn/")
                .get()
                .build();
        httpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                notifyError(callback, "酷我歌词请求失败: " + e.getMessage());
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                String body = readBody(response);
                if (!TextUtils.isEmpty(body)) {
                    // 酷我可能返回编码后的lrc，过滤非LRC行
                    String lrc = filterLrc(body);
                    if (!TextUtils.isEmpty(lrc)) {
                        notifySuccess(callback, lrc);
                        return;
                    }
                }
                notifyError(callback, "酷我无歌词");
            }
        });
    }

    // ============ 酷狗 ============
    private void getLyricKugou(MusicInfo music, final LyricCallback callback) {
        String hash = getFirstNonEmpty(music.hash, music.songmid, music.id);
        if (TextUtils.isEmpty(hash)) {
            notifyError(callback, "酷狗hash为空");
            return;
        }
        String url = "http://m.kugou.com/app/i/klyric.php?keyword="
                + URLEncoder.encode(music.songName + " " + music.singer)
                + "&hash=" + hash + "&cmd=100&timelength=0&ver=10";
        Request request = new Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .header("Referer", "https://m.kugou.com/")
                .get()
                .build();
        httpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                notifyError(callback, "酷狗歌词请求失败: " + e.getMessage());
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                String body = readBody(response);
                if (!TextUtils.isEmpty(body)) {
                    try {
                        JSONObject json = new JSONObject(body);
                        String content = json.optString("content", "");
                        if (!TextUtils.isEmpty(content)) {
                            // 酷狗歌词是base64编码
                            String decoded = decodeBase64(content);
                            if (!TextUtils.isEmpty(decoded)) {
                                notifySuccess(callback, decoded);
                                return;
                            }
                        }
                    } catch (Exception ignored) {
                    }
                    // 直接当LRC
                    String lrc = filterLrc(body);
                    if (!TextUtils.isEmpty(lrc)) {
                        notifySuccess(callback, lrc);
                        return;
                    }
                }
                notifyError(callback, "酷狗无歌词");
            }
        });
    }

    // ============ 网易云 ============
    private void getLyricWangyi(MusicInfo music, final LyricCallback callback) {
        String id = getFirstNonEmpty(music.songmid, music.id);
        if (TextUtils.isEmpty(id)) {
            notifyError(callback, "网易云歌曲ID为空");
            return;
        }
        String url = "https://music.163.com/api/song/lyric?id=" + id + "&lv=1&tv=-1&kv=-1";
        Request request = new Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .header("Referer", "https://music.163.com/")
                .header("Cookie", "os=pc; osver=Microsoft-Windows-10-Professional-build-22631-64bit; appver=2.10.6")
                .get()
                .build();
        httpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                notifyError(callback, "网易云歌词请求失败: " + e.getMessage());
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                String body = readBody(response);
                if (!TextUtils.isEmpty(body)) {
                    try {
                        JSONObject json = new JSONObject(body);
                        JSONObject lrc = json.optJSONObject("lrc");
                        if (lrc != null) {
                            String lyric = lrc.optString("lyric", "");
                            if (!TextUtils.isEmpty(lyric)) {
                                notifySuccess(callback, lyric);
                                return;
                            }
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "网易云歌词解析失败: " + e.getMessage());
                    }
                }
                notifyError(callback, "网易云无歌词");
            }
        });
    }

    // ============ QQ音乐 ============
    private void getLyricQQ(MusicInfo music, final LyricCallback callback) {
        String songmid = getFirstNonEmpty(music.songmid, music.id);
        if (TextUtils.isEmpty(songmid)) {
            notifyError(callback, "QQ音乐songmid为空");
            return;
        }
        String url = "https://c.y.qq.com/lyric/fcgi-bin/fcg_query_lyric_new.fcg?songmid="
                + songmid + "&pcachetime=" + System.currentTimeMillis() + "&g_tk=5381&format=json";
        Request request = new Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .header("Referer", "https://y.qq.com/")
                .get()
                .build();
        httpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                notifyError(callback, "QQ音乐歌词请求失败: " + e.getMessage());
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                String body = readBody(response);
                if (!TextUtils.isEmpty(body)) {
                    try {
                        JSONObject json = new JSONObject(body);
                        String lyric = json.optString("lyric", "");
                        if (!TextUtils.isEmpty(lyric)) {
                            String decoded = decodeBase64(lyric);
                            if (!TextUtils.isEmpty(decoded)) {
                                notifySuccess(callback, decoded);
                                return;
                            }
                        }
                    } catch (Exception ignored) {
                    }
                }
                notifyError(callback, "QQ音乐无歌词");
            }
        });
    }

    // ============ 工具方法 ============

    private static String getFirstNonEmpty(String... values) {
        if (values == null) return null;
        for (String v : values) {
            if (!TextUtils.isEmpty(v)) return v;
        }
        return null;
    }

    private static String readBody(Response response) throws IOException {
        ResponseBody body = response.body();
        if (body == null) return "";
        return body.string();
    }

    private static String filterLrc(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder();
        String[] lines = s.split("\n");
        for (String line : lines) {
            String trimmed = line.trim();
            // LRC行以[开头，如[00:01.23]歌词内容
            if (trimmed.startsWith("[") && trimmed.contains("]")) {
                sb.append(trimmed).append("\n");
            }
        }
        return sb.toString();
    }

    private static String decodeBase64(String s) {
        try {
            return android.util.Base64.encodeToString(
                    android.util.Base64.decode(s, android.util.Base64.DEFAULT),
                    android.util.Base64.NO_PADDING);
        } catch (Exception e) {
            return "";
        }
    }

    private void notifySuccess(final LyricCallback callback, final String lyric) {
        if (callback == null) return;
        mainHandler.post(() -> callback.onSuccess(lyric));
    }

    private void notifyError(final LyricCallback callback, final String msg) {
        Log.w(TAG, "getLyric: " + msg);
        if (callback == null) return;
        mainHandler.post(() -> callback.onError(msg));
    }
}
