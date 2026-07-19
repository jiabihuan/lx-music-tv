package com.lx.tv.music;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * 音乐信息数据模型
 * 对应落雪音乐协议中的 MusicInfoOnline 结构
 * 支持的音源: kw(酷我), kg(酷狗), tx(QQ音乐), wy(网易云), mg(咪咕)
 */
public class MusicInfo {
    /** 歌曲唯一ID（落雪协议中的id） */
    public String id;
    /** 歌曲名 */
    public String songName;
    /** 歌手名 */
    public String singer;
    /** 专辑名 */
    public String album;
    /** 音源: kw, kg, tx, wy, mg */
    public String source;
    /** 歌曲在音源中的ID（songmid） */
    public String songmid;
    /** 歌曲时长（格式化字符串，例如 "03:55"） */
    public String interval;
    /** 歌曲图片URL */
    public String picUrl;
    /** kg源专用hash */
    public String hash;
    /** tx源专用strMediaMid */
    public String strMediaMid;
    /** mg源专用copyrightId */
    public String copyrightId;

    public MusicInfo() {
    }

    public MusicInfo(String id, String songName, String singer, String source) {
        this.id = id;
        this.songName = songName;
        this.singer = singer;
        this.source = source;
    }

    /**
     * 转换为JSON对象，用于sendAction请求中的musicInfo字段
     */
    public JSONObject toJson() {
        JSONObject json = new JSONObject();
        try {
            json.put("id", id != null ? id : "");
            json.put("name", songName != null ? songName : "");
            json.put("singer", singer != null ? singer : "");
            json.put("source", source != null ? source : "");
            json.put("interval", interval != null ? interval : "");
            json.put("songmid", songmid != null ? songmid : "");

            JSONObject meta = new JSONObject();
            meta.put("songId", songmid != null ? songmid : (id != null ? id : ""));
            meta.put("albumName", album != null ? album : "");
            if (picUrl != null) meta.put("picUrl", picUrl);
            if (hash != null) meta.put("hash", hash);
            if (strMediaMid != null) meta.put("strMediaMid", strMediaMid);
            if (copyrightId != null) meta.put("copyrightId", copyrightId);
            json.put("meta", meta);
        } catch (JSONException e) {
            e.printStackTrace();
        }
        return json;
    }

    /**
     * 从JSON对象解析MusicInfo
     */
    public static MusicInfo fromJson(JSONObject json) {
        if (json == null) return null;
        MusicInfo info = new MusicInfo();
        info.id = json.optString("id", "");
        info.songName = json.optString("name", json.optString("songName", ""));
        info.singer = json.optString("singer", "");
        info.source = json.optString("source", "");
        info.interval = json.optString("interval", "");
        info.songmid = json.optString("songmid", "");

        JSONObject meta = json.optJSONObject("meta");
        if (meta != null) {
            info.album = meta.optString("albumName", "");
            info.picUrl = meta.optString("picUrl", null);
            info.hash = meta.optString("hash", null);
            info.strMediaMid = meta.optString("strMediaMid", null);
            info.copyrightId = meta.optString("copyrightId", null);
            if (info.songmid == null || info.songmid.isEmpty()) {
                info.songmid = meta.optString("songId", "");
            }
        } else {
            info.album = json.optString("album", "");
        }
        return info;
    }

    /**
     * 创建用于持久化的简化JSON（不含嵌套meta）
     */
    public JSONObject toStorageJson() {
        JSONObject json = new JSONObject();
        try {
            json.put("id", id != null ? id : "");
            json.put("songName", songName != null ? songName : "");
            json.put("singer", singer != null ? singer : "");
            json.put("album", album != null ? album : "");
            json.put("source", source != null ? source : "");
            json.put("songmid", songmid != null ? songmid : "");
            json.put("interval", interval != null ? interval : "");
            json.put("picUrl", picUrl != null ? picUrl : "");
            if (hash != null) json.put("hash", hash);
            if (strMediaMid != null) json.put("strMediaMid", strMediaMid);
            if (copyrightId != null) json.put("copyrightId", copyrightId);
        } catch (JSONException e) {
            e.printStackTrace();
        }
        return json;
    }

    public static MusicInfo fromStorageJson(JSONObject json) {
        if (json == null) return null;
        MusicInfo info = new MusicInfo();
        info.id = json.optString("id", "");
        info.songName = json.optString("songName", "");
        info.singer = json.optString("singer", "");
        info.album = json.optString("album", "");
        info.source = json.optString("source", "");
        info.songmid = json.optString("songmid", "");
        info.interval = json.optString("interval", "");
        info.picUrl = json.optString("picUrl", null);
        info.hash = json.optString("hash", null);
        info.strMediaMid = json.optString("strMediaMid", null);
        info.copyrightId = json.optString("copyrightId", null);
        return info;
    }

    /**
     * 获取音源显示名称
     */
    public String getSourceDisplayName() {
        if (source == null) return "未知";
        switch (source) {
            case "kw": return "酷我";
            case "kg": return "酷狗";
            case "tx": return "QQ音乐";
            case "wy": return "网易云";
            case "mg": return "咪咕";
            default: return source;
        }
    }

    /**
     * 获取interval解析后的秒数
     */
    public int getIntervalSeconds() {
        if (interval == null || interval.isEmpty()) return 0;
        try {
            String[] parts = interval.split(":");
            if (parts.length == 2) {
                return Integer.parseInt(parts[0]) * 60 + Integer.parseInt(parts[1]);
            } else if (parts.length == 3) {
                return Integer.parseInt(parts[0]) * 3600 + Integer.parseInt(parts[1]) * 60 + Integer.parseInt(parts[2]);
            }
        } catch (NumberFormatException e) {
            return 0;
        }
        return 0;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof MusicInfo)) return false;
        MusicInfo that = (MusicInfo) o;
        if (id != null && !id.isEmpty() && that.id != null && !that.id.isEmpty()) {
            return id.equals(that.id) && (source == null || source.equals(that.source));
        }
        return (songName != null ? songName.equals(that.songName) : that.songName == null)
                && (singer != null ? singer.equals(that.singer) : that.singer == null)
                && (source != null ? source.equals(that.source) : that.source == null);
    }

    @Override
    public int hashCode() {
        int result = id != null ? id.hashCode() : 0;
        result = 31 * result + (source != null ? source.hashCode() : 0);
        return result;
    }

    @Override
    public String toString() {
        return "MusicInfo{" +
                "id='" + id + '\'' +
                ", songName='" + songName + '\'' +
                ", singer='" + singer + '\'' +
                ", source='" + source + '\'' +
                '}';
    }
}
