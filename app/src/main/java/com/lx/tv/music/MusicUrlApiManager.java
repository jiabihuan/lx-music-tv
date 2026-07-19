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
import java.security.MessageDigest;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * 音乐播放URL直接API解析器
 *
 * 直接调用各音源公开API获取播放URL，与落雪音乐JS音源脚本使用的API相同，
 * 但完全用Java实现，避免QuickJS引擎导致的原生崩溃。
 *
 * 支持音源：
 *   - 酷我(kw): http://www.kuwo.cn/api/v1/www/music/playUrl
 *   - 酷狗(kg): http://trackercdn.kugou.com/i/v2/
 *   - 网易云(wy): https://music.163.com/api/song/enhance/player/url
 *   - QQ音乐(tx): 暂不支持（需要复杂的vkey签名）
 *   - 咪咕(mg): 暂不支持（需要复杂的加密签名）
 *
 * 任一音源失败不影响其他功能，用户点击播放时直接异步获取URL。
 */
public class MusicUrlApiManager {
    private static final String TAG = "MusicUrlApiManager";

    /** 浏览器UA */
    private static final String USER_AGENT =
            "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 (KHTML, like Gecko) " +
                    "Chrome/120.0.0.0 Mobile Safari/537.36";

    private final OkHttpClient httpClient;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    /** URL请求回调（回调在主线程） */
    public interface UrlCallback {
        void onSuccess(String url);
        void onError(String errorMessage);
    }

    public MusicUrlApiManager(OkHttpClient httpClient) {
        if (httpClient != null) {
            this.httpClient = httpClient;
        } else {
            this.httpClient = new OkHttpClient.Builder()
                    .connectTimeout(10, TimeUnit.SECONDS)
                    .readTimeout(15, TimeUnit.SECONDS)
                    .writeTimeout(15, TimeUnit.SECONDS)
                    .followRedirects(true)
                    .build();
        }
    }

    /**
     * 获取播放URL
     * @param music 歌曲信息
     * @param quality 音质：128k/320k/flac
     * @param callback 回调（主线程）
     */
    public void getUrl(final MusicInfo music, final String quality, final UrlCallback callback) {
        if (music == null) {
            notifyError(callback, "music is null");
            return;
        }
        if (TextUtils.isEmpty(music.source)) {
            notifyError(callback, "音源为空");
            return;
        }
        Log.i(TAG, "getUrl: " + music.songName + " from " + music.source + " quality=" + quality);

        switch (music.source) {
            case SearchApiManager.SOURCE_KUWO:
                getUrlKuwo(music, quality, callback);
                break;
            case SearchApiManager.SOURCE_KUGOU:
                getUrlKugou(music, quality, callback);
                break;
            case SearchApiManager.SOURCE_WANGYI:
                getUrlWangyi(music, quality, callback);
                break;
            case SearchApiManager.SOURCE_QQ:
                notifyError(callback, "QQ音乐暂不支持直接播放，请尝试其他音源");
                break;
            case SearchApiManager.SOURCE_MIGU:
                notifyError(callback, "咪咕音乐暂不支持直接播放，请尝试其他音源");
                break;
            default:
                notifyError(callback, "未知音源: " + music.source);
        }
    }

    // ============ 酷我 ============
    private void getUrlKuwo(final MusicInfo music, final String quality, final UrlCallback callback) {
        try {
            final String rid = getFirstNonEmpty(music.songmid, music.id);
            if (TextUtils.isEmpty(rid)) {
                notifyError(callback, "酷我歌曲ID为空");
                return;
            }
            // 酷我播放URL API
            String br = "320kmp3";
            if ("128k".equals(quality)) br = "128kmp3";
            else if ("flac".equals(quality)) br = "flac";
            String url = "http://www.kuwo.cn/api/v1/www/music/playUrl?id=" + rid
                    + "&type=music&br=" + br;
            // 生成随机token
            String token = randomToken(8);
            Request request = new Request.Builder()
                    .url(url)
                    .header("User-Agent", USER_AGENT)
                    .header("Referer", "https://www.kuwo.cn/play_detail/" + rid)
                    .header("Cookie", "kw_token=" + token)
                    .header("csrf", token)
                    .header("Accept", "application/json, text/plain, */*")
                    .get()
                    .build();
            httpClient.newCall(request).enqueue(new Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                    notifyError(callback, "酷我请求失败: " + e.getMessage());
                }

                @Override
                public void onResponse(Call call, Response response) throws IOException {
                    String body = readBody(response);
                    if (response.code() == 200 && !TextUtils.isEmpty(body)) {
                        try {
                            JSONObject json = new JSONObject(body);
                            int code = json.optInt("code", 0);
                            JSONObject data = json.optJSONObject("data");
                            if (code == 200 && data != null) {
                                String playUrl = data.optString("url", "");
                                if (!TextUtils.isEmpty(playUrl) && playUrl.startsWith("http")) {
                                    notifySuccess(callback, playUrl);
                                    return;
                                }
                            }
                            notifyError(callback, "酷我返回数据无效: code=" + code);
                        } catch (JSONException e) {
                            notifyError(callback, "酷我响应解析失败: " + e.getMessage());
                        }
                    } else {
                        notifyError(callback, "酷我HTTP错误: " + response.code());
                    }
                }
            });
        } catch (Exception e) {
            notifyError(callback, "酷我请求异常: " + e.getMessage());
        }
    }

    // ============ 酷狗 ============
    private void getUrlKugou(final MusicInfo music, final String quality, final UrlCallback callback) {
        try {
            final String hash = getFirstNonEmpty(music.hash, music.songmid, music.id);
            if (TextUtils.isEmpty(hash)) {
                notifyError(callback, "酷狗hash为空");
                return;
            }
            // 酷狗播放URL API
            String br = "hq";
            if ("128k".equals(quality)) br = "sq";
            else if ("flac".equals(quality)) br = "sq";
            String key = md5(hash.toLowerCase() + "kgcloudv2");
            String url = "http://trackercdn.kugou.com/i/v2/?key=" + key
                    + "&hash=" + hash.toLowerCase()
                    + "&br=" + br + "&appid=1005&pid=2&cmd=25&behavior=play&album_id=";
            Request request = new Request.Builder()
                    .url(url)
                    .header("User-Agent", USER_AGENT)
                    .header("Referer", "https://www.kugou.com/")
                    .header("Accept", "application/json, */*")
                    .get()
                    .build();
            httpClient.newCall(request).enqueue(new Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                    notifyError(callback, "酷狗请求失败: " + e.getMessage());
                }

                @Override
                public void onResponse(Call call, Response response) throws IOException {
                    String body = readBody(response);
                    if (response.code() == 200 && !TextUtils.isEmpty(body)) {
                        try {
                            JSONObject json = new JSONObject(body);
                            JSONArray urls = json.optJSONArray("url");
                            if (urls != null && urls.length() > 0) {
                                String playUrl = urls.optString(0);
                                if (!TextUtils.isEmpty(playUrl) && playUrl.startsWith("http")) {
                                    notifySuccess(callback, playUrl);
                                    return;
                                }
                            }
                            // 备用：尝试url字段
                            String singleUrl = json.optString("url", "");
                            if (!TextUtils.isEmpty(singleUrl) && singleUrl.startsWith("http")) {
                                notifySuccess(callback, singleUrl);
                                return;
                            }
                            notifyError(callback, "酷狗未返回有效URL（可能需要VIP）");
                        } catch (JSONException e) {
                            notifyError(callback, "酷狗响应解析失败: " + e.getMessage());
                        }
                    } else {
                        notifyError(callback, "酷狗HTTP错误: " + response.code());
                    }
                }
            });
        } catch (Exception e) {
            notifyError(callback, "酷狗请求异常: " + e.getMessage());
        }
    }

    // ============ 网易云 ============
    private void getUrlWangyi(final MusicInfo music, final String quality, final UrlCallback callback) {
        try {
            final String id = getFirstNonEmpty(music.songmid, music.id);
            if (TextUtils.isEmpty(id)) {
                notifyError(callback, "网易云歌曲ID为空");
                return;
            }
            // 网易云播放URL API
            long br = 320000;
            if ("128k".equals(quality)) br = 128000;
            else if ("flac".equals(quality)) br = 999000;
            String url = "https://music.163.com/api/song/enhance/player/url?id=" + id
                    + "&br=" + br + "&vv=";
            Request request = new Request.Builder()
                    .url(url)
                    .header("User-Agent", USER_AGENT)
                    .header("Referer", "https://music.163.com/")
                    .header("Cookie", "os=pc; osver=Microsoft-Windows-10-Professional-build-22631-64bit; appver=2.10.6; MUSIC_U=00")
                    .header("Accept", "application/json, */*")
                    .get()
                    .build();
            httpClient.newCall(request).enqueue(new Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                    notifyError(callback, "网易云请求失败: " + e.getMessage());
                }

                @Override
                public void onResponse(Call call, Response response) throws IOException {
                    String body = readBody(response);
                    if (response.code() == 200 && !TextUtils.isEmpty(body)) {
                        try {
                            JSONObject json = new JSONObject(body);
                            JSONArray data = json.optJSONArray("data");
                            if (data != null && data.length() > 0) {
                                JSONObject item = data.optJSONObject(0);
                                if (item != null) {
                                    String playUrl = item.optString("url", "");
                                    int code = item.optInt("code", 0);
                                    if (code == 200 && !TextUtils.isEmpty(playUrl) && playUrl.startsWith("http")) {
                                        notifySuccess(callback, playUrl);
                                        return;
                                    }
                                }
                            }
                            // 备用：使用外链URL（302重定向到实际MP3）
                            String directUrl = "https://music.163.com/song/media/outer/url?id=" + id + ".mp3";
                            notifySuccess(callback, directUrl);
                        } catch (JSONException e) {
                            notifyError(callback, "网易云响应解析失败: " + e.getMessage());
                        }
                    } else {
                        notifyError(callback, "网易云HTTP错误: " + response.code());
                    }
                }
            });
        } catch (Exception e) {
            notifyError(callback, "网易云请求异常: " + e.getMessage());
        }
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

    private static String md5(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] bytes = md.digest(s.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte b : bytes) {
                sb.append(String.format("%02x", b & 0xff));
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private static String randomToken(int len) {
        StringBuilder sb = new StringBuilder();
        String chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
        java.util.Random rnd = new java.util.Random();
        for (int i = 0; i < len; i++) {
            sb.append(chars.charAt(rnd.nextInt(chars.length())));
        }
        return sb.toString();
    }

    private void notifySuccess(final UrlCallback callback, final String url) {
        if (callback == null) return;
        mainHandler.post(() -> callback.onSuccess(url));
    }

    private void notifyError(final UrlCallback callback, final String msg) {
        Log.e(TAG, "getUrl error: " + msg);
        if (callback == null) return;
        mainHandler.post(() -> callback.onError(msg));
    }
}
