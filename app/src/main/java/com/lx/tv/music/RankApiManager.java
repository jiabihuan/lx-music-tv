package com.lx.tv.music;

import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * 排行榜API管理器
 * 调用酷狗和酷我的公开排行榜API获取排行榜歌曲列表。
 *
 * 酷狗排行榜API（公开接口）：
 *   - 排行榜列表：http://mobilecdn.kugou.com/api/v3/rank/list
 *   - 排行榜歌曲：http://mobilecdn.kugou.com/api/v3/rank/song?rankid=8888&pagesize=30&page=1
 *   - 常用rankid：8888(酷狗飙升榜), 23784(酷狗TOP500), 31313(酷狗网络红歌榜)
 *   - 返回格式：{status, info, data:{rank:{list:[{songname, singername, album_name, hash, ...}]}}}
 *
 * 酷我排行榜API（公开接口）：
 *   - 酷我榜单API：http://kbangserver.kuwo.cn/ksong.s?from=pc&fmt=json&type=bang&data=content&id=93&pn=1&rn=30
 *   - 常用id：93(酷我飙升榜), 17(酷我热歌榜), 16(酷我新歌榜)
 *   - 返回格式：{musiclist:[{name, artist, album, rid, ...}]}
 *
 * 网络请求使用OkHttp异步执行，回调通过Handler回到主线程。
 */
public class RankApiManager {
    private static final String TAG = "RankApiManager";

    // ============ 酷狗排行榜rankid ============
    /** 酷狗飙升榜 */
    public static final int KUGOU_RANK_SOARING = 8888;
    /** 酷狗TOP500 */
    public static final int KUGOU_RANK_TOP500 = 23784;
    /** 酷狗网络红歌榜 */
    public static final int KUGOU_RANK_HOT_NETWORK = 31313;

    // ============ 酷我排行榜id ============
    /** 酷我飙升榜 */
    public static final int KUWO_RANK_SOARING = 93;
    /** 酷我热歌榜 */
    public static final int KUWO_RANK_HOT = 17;
    /** 酷我新歌榜 */
    public static final int KUWO_RANK_NEW = 16;

    /** 音源标识：酷狗 */
    public static final String SOURCE_KUGOU = "kg";
    /** 音源标识：酷我 */
    public static final String SOURCE_KUWO = "kw";

    /** 酷狗排行榜歌曲API基础URL */
    private static final String KUGOU_RANK_SONG_URL =
            "http://mobilecdn.kugou.com/api/v3/rank/song";
    /** 酷我排行榜API基础URL */
    private static final String KUWO_RANK_URL =
            "http://kbangserver.kuwo.cn/ksong.s";

    /** 默认每页歌曲数 */
    private static final int DEFAULT_PAGE_SIZE = 30;
    /** 默认页码 */
    private static final int DEFAULT_PAGE = 1;

    /** 浏览器UA，避免被服务器拒绝 */
    private static final String USER_AGENT =
            "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 (KHTML, like Gecko) " +
                    "Chrome/120.0.0.0 Mobile Safari/537.36";

    private final OkHttpClient httpClient;
    /** 主线程Handler，用于回调UI线程 */
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    /**
     * 构造方法
     * @param httpClient OkHttp客户端（由外部统一管理）
     */
    public RankApiManager(OkHttpClient httpClient) {
        this.httpClient = httpClient != null ? httpClient : createDefaultClient();
    }

    /**
     * 创建默认的OkHttpClient（当外部未提供时使用）
     */
    private static OkHttpClient createDefaultClient() {
        return new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .writeTimeout(20, TimeUnit.SECONDS)
                .build();
    }

    /**
     * 排行榜加载回调
     */
    public interface RankCallback {
        /** 开始加载排行榜 */
        void onRankLoadStart(String rankName);
        /** 加载成功，返回歌曲列表 */
        void onRankLoadSuccess(List<MusicInfo> songs, String rankName);
        /** 加载失败 */
        void onRankLoadError(String errorMessage);
    }

    /**
     * 排行榜信息（用于UI展示可用排行榜列表）
     */
    public static class RankInfo {
        /** 排行榜名称 */
        public String name;
        /** 音源：SOURCE_KUGOU 或 SOURCE_KUWO */
        public String source;
        /** 排行榜ID（酷狗rankid或酷我id） */
        public int rankId;

        public RankInfo() {
        }

        public RankInfo(String name, String source, int rankId) {
            this.name = name;
            this.source = source;
            this.rankId = rankId;
        }
    }

    /**
     * 加载酷狗排行榜
     * 调用 http://mobilecdn.kugou.com/api/v3/rank/song?rankid=xxx&pagesize=30&page=1
     *
     * @param rankId 排行榜ID（如 KUGOU_RANK_SOARING）
     * @param rankName 排行榜名称（用于显示）
     * @param callback 回调（回调在主线程执行）
     */
    public void loadKugouRank(int rankId, String rankName, final RankCallback callback) {
        final String name = TextUtils.isEmpty(rankName) ? ("酷狗排行榜#" + rankId) : rankName;
        // 构造请求URL
        String url = KUGOU_RANK_SONG_URL
                + "?rankid=" + rankId
                + "&pagesize=" + DEFAULT_PAGE_SIZE
                + "&page=" + DEFAULT_PAGE;
        Log.i(TAG, "loadKugouRank: " + name + " url=" + url);

        if (callback != null) {
            mainHandler.post(() -> callback.onRankLoadStart(name));
        }

        Request request = new Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json, */*")
                .header("Referer", "https://www.kugou.com/")
                .get()
                .build();

        httpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                String errMsg = e.getMessage();
                Log.e(TAG, "loadKugouRank failed: " + errMsg);
                if (callback != null) {
                    final String msg = "网络请求失败: " + (errMsg != null ? errMsg : "unknown");
                    mainHandler.post(() -> callback.onRankLoadError(msg));
                }
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                final String body;
                try {
                    if (!response.isSuccessful()) {
                        final String msg = "HTTP " + response.code() + " " + response.message();
                        Log.e(TAG, "loadKugouRank HTTP error: " + msg);
                        if (callback != null) {
                            mainHandler.post(() -> callback.onRankLoadError(msg));
                        }
                        return;
                    }
                    ResponseBody responseBody = response.body();
                    body = responseBody != null ? responseBody.string() : "";
                } finally {
                    response.close();
                }

                // 在工作线程解析JSON
                final List<MusicInfo> songs = new ArrayList<>();
                final String[] error = {null};
                try {
                    JSONObject json = new JSONObject(body);
                    int status = json.optInt("status", -1);
                    if (status != 1) {
                        String info = json.optString("info", "未知错误");
                        error[0] = "酷狗API返回错误: status=" + status + " info=" + info;
                        Log.e(TAG, "loadKugouRank API error: " + error[0]);
                    } else {
                        JSONObject data = json.optJSONObject("data");
                        if (data != null) {
                            JSONObject rank = data.optJSONObject("rank");
                            if (rank != null) {
                                JSONArray list = rank.optJSONArray("list");
                                if (list != null) {
                                    for (int i = 0; i < list.length(); i++) {
                                        JSONObject item = list.optJSONObject(i);
                                        if (item == null) continue;
                                        MusicInfo info = parseKugouSong(item);
                                        if (info != null) {
                                            songs.add(info);
                                        }
                                    }
                                }
                            }
                        }
                    }
                } catch (JSONException e) {
                    error[0] = "解析酷狗排行榜JSON失败: " + e.getMessage();
                    Log.e(TAG, "loadKugouRank parse error: " + e.getMessage());
                }

                // 回调主线程
                if (callback != null) {
                    if (error[0] != null && songs.isEmpty()) {
                        final String msg = error[0];
                        mainHandler.post(() -> callback.onRankLoadError(msg));
                    } else {
                        final List<MusicInfo> result = songs;
                        mainHandler.post(() -> callback.onRankLoadSuccess(result, name));
                    }
                }
            }
        });
    }

    /**
     * 加载酷我排行榜
     * 调用 http://kbangserver.kuwo.cn/ksong.s?from=pc&fmt=json&type=bang&data=content&id=xxx&pn=1&rn=30
     *
     * @param rankId 排行榜ID（如 KUWO_RANK_SOARING）
     * @param rankName 排行榜名称（用于显示）
     * @param callback 回调（回调在主线程执行）
     */
    public void loadKuwoRank(int rankId, String rankName, final RankCallback callback) {
        final String name = TextUtils.isEmpty(rankName) ? ("酷我排行榜#" + rankId) : rankName;
        // 构造请求URL
        String url = KUWO_RANK_URL
                + "?from=pc&fmt=json&type=bang&data=content"
                + "&id=" + rankId
                + "&pn=" + DEFAULT_PAGE
                + "&rn=" + DEFAULT_PAGE_SIZE;
        Log.i(TAG, "loadKuwoRank: " + name + " url=" + url);

        if (callback != null) {
            mainHandler.post(() -> callback.onRankLoadStart(name));
        }

        Request request = new Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json, */*")
                .header("Referer", "https://www.kuwo.cn/")
                .get()
                .build();

        httpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                String errMsg = e.getMessage();
                Log.e(TAG, "loadKuwoRank failed: " + errMsg);
                if (callback != null) {
                    final String msg = "网络请求失败: " + (errMsg != null ? errMsg : "unknown");
                    mainHandler.post(() -> callback.onRankLoadError(msg));
                }
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                final String body;
                try {
                    if (!response.isSuccessful()) {
                        final String msg = "HTTP " + response.code() + " " + response.message();
                        Log.e(TAG, "loadKuwoRank HTTP error: " + msg);
                        if (callback != null) {
                            mainHandler.post(() -> callback.onRankLoadError(msg));
                        }
                        return;
                    }
                    ResponseBody responseBody = response.body();
                    body = responseBody != null ? responseBody.string() : "";
                } finally {
                    response.close();
                }

                // 在工作线程解析JSON
                final List<MusicInfo> songs = new ArrayList<>();
                final String[] error = {null};
                try {
                    JSONObject json = new JSONObject(body);
                    // 酷我返回格式：{musiclist:[{name, artist, album, rid, ...}]}
                    JSONArray musiclist = json.optJSONArray("musiclist");
                    if (musiclist == null) {
                        // 部分接口可能将内容包在 data 字段中
                        JSONObject data = json.optJSONObject("data");
                        if (data != null) {
                            musiclist = data.optJSONArray("musiclist");
                        }
                    }
                    if (musiclist != null) {
                        for (int i = 0; i < musiclist.length(); i++) {
                            JSONObject item = musiclist.optJSONObject(i);
                            if (item == null) continue;
                            MusicInfo info = parseKuwoSong(item);
                            if (info != null) {
                                songs.add(info);
                            }
                        }
                    } else {
                        error[0] = "酷我API返回数据中未找到musiclist字段";
                        Log.w(TAG, "loadKuwoRank: " + error[0]);
                    }
                } catch (JSONException e) {
                    error[0] = "解析酷我排行榜JSON失败: " + e.getMessage();
                    Log.e(TAG, "loadKuwoRank parse error: " + e.getMessage());
                }

                // 回调主线程
                if (callback != null) {
                    if (error[0] != null && songs.isEmpty()) {
                        final String msg = error[0];
                        mainHandler.post(() -> callback.onRankLoadError(msg));
                    } else {
                        final List<MusicInfo> result = songs;
                        mainHandler.post(() -> callback.onRankLoadSuccess(result, name));
                    }
                }
            }
        });
    }

    /**
     * 解析酷狗单首歌曲JSON
     * 字段：songname, singername, album_name, hash
     */
    private MusicInfo parseKugouSong(JSONObject item) {
        try {
            String songname = item.optString("songname", "");
            String singername = item.optString("singername", "");
            String albumName = item.optString("album_name", "");
            String hash = item.optString("hash", "");
            if (TextUtils.isEmpty(songname) && TextUtils.isEmpty(hash)) {
                return null;
            }
            MusicInfo info = new MusicInfo();
            info.songName = songname;
            info.singer = singername;
            info.album = albumName;
            info.hash = hash;
            // 酷狗用hash作为唯一标识
            info.songmid = hash;
            info.id = hash;
            info.source = SOURCE_KUGOU;
            // 时长字段：duration（秒）—— 转为 mm:ss 格式
            int duration = item.optInt("duration", 0);
            if (duration > 0) {
                info.interval = String.format("%02d:%02d", duration / 60, duration % 60);
            }
            return info;
        } catch (Exception e) {
            Log.w(TAG, "parseKugouSong failed: " + e.getMessage());
            return null;
        }
    }

    /**
     * 解析酷我单首歌曲JSON
     * 字段：name, artist, album, rid
     */
    private MusicInfo parseKuwoSong(JSONObject item) {
        try {
            String name = item.optString("name", "");
            String artist = item.optString("artist", "");
            String album = item.optString("album", "");
            String rid = item.optString("rid", "");
            if (TextUtils.isEmpty(rid)) {
                // 部分接口可能使用 id 字段
                rid = item.optString("id", "");
            }
            if (TextUtils.isEmpty(name) && TextUtils.isEmpty(rid)) {
                return null;
            }
            MusicInfo info = new MusicInfo();
            info.songName = name;
            info.singer = artist;
            info.album = album;
            // 酷我用rid作为唯一标识
            info.songmid = rid;
            info.id = rid;
            info.source = SOURCE_KUWO;
            // 时长字段：duration（秒，部分接口是字符串）
            String durationStr = item.optString("duration", "");
            int duration = 0;
            if (!TextUtils.isEmpty(durationStr)) {
                try {
                    duration = Integer.parseInt(durationStr);
                } catch (NumberFormatException ignored) {
                }
            } else {
                duration = item.optInt("duration", 0);
            }
            if (duration > 0) {
                info.interval = String.format("%02d:%02d", duration / 60, duration % 60);
            }
            // 尝试获取专辑图片
            String pic = item.optString("pic", null);
            if (TextUtils.isEmpty(pic)) {
                pic = item.optString("albumpic", null);
            }
            if (!TextUtils.isEmpty(pic)) {
                info.picUrl = pic;
            }
            return info;
        } catch (Exception e) {
            Log.w(TAG, "parseKuwoSong failed: " + e.getMessage());
            return null;
        }
    }

    /**
     * 获取可用的排行榜列表（用于UI展示可选排行榜）
     * 包含酷狗和酷我的常用排行榜。
     *
     * @return List<RankInfo> 排行榜信息列表
     */
    public static List<RankInfo> getAvailableRanks() {
        List<RankInfo> list = new ArrayList<>();
        // 酷狗排行榜
        list.add(new RankInfo("酷狗飙升榜", SOURCE_KUGOU, KUGOU_RANK_SOARING));
        list.add(new RankInfo("酷狗TOP500", SOURCE_KUGOU, KUGOU_RANK_TOP500));
        list.add(new RankInfo("酷狗网络红歌榜", SOURCE_KUGOU, KUGOU_RANK_HOT_NETWORK));
        // 酷我排行榜
        list.add(new RankInfo("酷我飙升榜", SOURCE_KUWO, KUWO_RANK_SOARING));
        list.add(new RankInfo("酷我热歌榜", SOURCE_KUWO, KUWO_RANK_HOT));
        list.add(new RankInfo("酷我新歌榜", SOURCE_KUWO, KUWO_RANK_NEW));
        return list;
    }

    /**
     * 统一的排行榜加载方法（根据source自动分发到酷狗/酷我）
     *
     * @param rank     排行榜信息
     * @param callback 回调
     */
    public void loadRank(RankInfo rank, RankCallback callback) {
        if (rank == null) {
            if (callback != null) callback.onRankLoadError("排行榜信息为空");
            return;
        }
        if (SOURCE_KUGOU.equals(rank.source)) {
            loadKugouRank(rank.rankId, rank.name, callback);
        } else if (SOURCE_KUWO.equals(rank.source)) {
            loadKuwoRank(rank.rankId, rank.name, callback);
        } else {
            if (callback != null) callback.onRankLoadError("未知音源: " + rank.source);
        }
    }
}
