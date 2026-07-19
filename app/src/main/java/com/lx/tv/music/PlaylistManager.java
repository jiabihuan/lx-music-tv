package com.lx.tv.music;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * 播放列表管理器
 * 管理当前播放队列，支持顺序/单曲循环/随机三种播放模式
 * 持久化到SharedPreferences（JSON格式）
 */
public class PlaylistManager {
    private static final String TAG = "PlaylistManager";

    private static final String PREFS_NAME = "lx_tv_playlist";
    private static final String KEY_PLAYLIST = "playlist";
    private static final String KEY_CURRENT_INDEX = "current_index";
    private static final String KEY_PLAY_MODE = "play_mode";

    /** 播放模式常量 */
    public static final int PLAY_MODE_SEQUENCE = 0;   // 顺序播放（列表末尾停止）
    public static final int PLAY_MODE_REPEAT_ALL = 1; // 列表循环
    public static final int PLAY_MODE_REPEAT_ONE = 2; // 单曲循环
    public static final int PLAY_MODE_SHUFFLE = 3;    // 随机播放

    private final Context context;
    private final SharedPreferences prefs;
    private final List<MusicInfo> playlist = new ArrayList<>();
    private int currentIndex = -1;
    private int playMode = PLAY_MODE_SEQUENCE;
    private final Random random = new Random();
    private final List<Integer> shuffleOrder = new ArrayList<>();

    public PlaylistManager(Context context) {
        this.context = context.getApplicationContext();
        this.prefs = this.context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        loadFromStorage();
    }

    /**
     * 添加单首歌曲到播放列表末尾
     * @return 该歌曲在列表中的索引
     */
    public int addToPlaylist(MusicInfo music) {
        if (music == null) return -1;
        playlist.add(music);
        if (currentIndex < 0) {
            currentIndex = 0;
        }
        rebuildShuffleOrder();
        saveToStorage();
        return playlist.size() - 1;
    }

    /**
     * 批量添加歌曲到播放列表末尾
     * @param musics 歌曲列表
     * @param startPlayFirst 是否立即播放添加的第一首
     * @return 第一首添加歌曲的索引
     */
    public int addAllToPlaylist(List<MusicInfo> musics, boolean startPlayFirst) {
        if (musics == null || musics.isEmpty()) return -1;
        int startIndex = playlist.size();
        for (MusicInfo m : musics) {
            if (m != null) playlist.add(m);
        }
        if (startPlayFirst && startIndex < playlist.size()) {
            currentIndex = startIndex;
        } else if (currentIndex < 0 && !playlist.isEmpty()) {
            currentIndex = 0;
        }
        rebuildShuffleOrder();
        saveToStorage();
        return startIndex;
    }

    /**
     * 替换整个播放列表
     * @param musics 新的播放列表
     * @param startIndex 起始播放索引
     */
    public void setPlaylist(List<MusicInfo> musics, int startIndex) {
        playlist.clear();
        if (musics != null) {
            for (MusicInfo m : musics) {
                if (m != null) playlist.add(m);
            }
        }
        currentIndex = playlist.isEmpty() ? -1 : Math.max(0, Math.min(startIndex, playlist.size() - 1));
        rebuildShuffleOrder();
        saveToStorage();
    }

    /**
     * 从播放列表移除指定索引的歌曲
     */
    public boolean removeFromPlaylist(int index) {
        if (index < 0 || index >= playlist.size()) return false;
        playlist.remove(index);
        if (playlist.isEmpty()) {
            currentIndex = -1;
        } else if (index < currentIndex) {
            currentIndex--;
        } else if (index == currentIndex) {
            // 删除当前播放的歌曲，索引保持不变（指向下一首），但需检查越界
            if (currentIndex >= playlist.size()) {
                currentIndex = playlist.size() - 1;
            }
        }
        rebuildShuffleOrder();
        saveToStorage();
        return true;
    }

    /**
     * 通过MusicInfo移除（按id+source匹配）
     */
    public boolean removeFromPlaylist(MusicInfo music) {
        if (music == null) return false;
        for (int i = playlist.size() - 1; i >= 0; i--) {
            if (music.equals(playlist.get(i))) {
                return removeFromPlaylist(i);
            }
        }
        return false;
    }

    /**
     * 清空播放列表
     */
    public void clearList() {
        playlist.clear();
        currentIndex = -1;
        shuffleOrder.clear();
        saveToStorage();
    }

    /**
     * 获取当前播放歌曲
     */
    public MusicInfo getCurrent() {
        if (currentIndex < 0 || currentIndex >= playlist.size()) return null;
        return playlist.get(currentIndex);
    }

    /**
     * 获取当前索引
     */
    public int getCurrentIndex() {
        return currentIndex;
    }

    /**
     * 设置当前索引
     */
    public void setCurrentIndex(int index) {
        if (index >= 0 && index < playlist.size()) {
            currentIndex = index;
            saveToStorage();
        }
    }

    /**
     * 获取下一首歌曲（不改变当前索引）
     * @param autoFromComplete 是否为播放完成后自动获取下一首（影响循环模式行为）
     * @return 下一首歌曲，若没有下一首返回null
     */
    public MusicInfo getNext(boolean autoFromComplete) {
        if (playlist.isEmpty()) return null;
        if (playMode == PLAY_MODE_REPEAT_ONE && autoFromComplete) {
            return getCurrent();
        }
        int nextIndex = getNextIndex(autoFromComplete);
        if (nextIndex < 0) return null;
        return playlist.get(nextIndex);
    }

    /**
     * 获取下一首索引并移动当前索引
     */
    public int moveToNext(boolean autoFromComplete) {
        if (playlist.isEmpty()) return -1;
        if (playMode == PLAY_MODE_REPEAT_ONE && autoFromComplete) {
            return currentIndex;
        }
        int nextIndex = getNextIndex(autoFromComplete);
        if (nextIndex < 0) return -1;
        currentIndex = nextIndex;
        saveToStorage();
        return currentIndex;
    }

    private int getNextIndex(boolean autoFromComplete) {
        if (playlist.isEmpty()) return -1;
        switch (playMode) {
            case PLAY_MODE_SHUFFLE:
                if (shuffleOrder.isEmpty()) rebuildShuffleOrder();
                int shuffleIdx = shuffleOrder.indexOf(currentIndex);
                if (shuffleIdx < 0 || shuffleIdx >= shuffleOrder.size() - 1) {
                    if (playMode == PLAY_MODE_SEQUENCE && autoFromComplete) {
                        return -1; // 顺序播放结束
                    }
                    // 重新洗牌
                    rebuildShuffleOrder();
                    return shuffleOrder.isEmpty() ? -1 : shuffleOrder.get(0);
                }
                return shuffleOrder.get(shuffleIdx + 1);
            case PLAY_MODE_REPEAT_ONE:
                // 显式调用下一首时，进入下一首
                if (currentIndex + 1 < playlist.size()) {
                    return currentIndex + 1;
                }
                return playMode == PLAY_MODE_SEQUENCE ? -1 : 0;
            case PLAY_MODE_SEQUENCE:
                if (currentIndex + 1 < playlist.size()) {
                    return currentIndex + 1;
                }
                return -1;
            case PLAY_MODE_REPEAT_ALL:
            default:
                return (currentIndex + 1) % playlist.size();
        }
    }

    /**
     * 获取上一首歌曲
     */
    public MusicInfo getPrevious() {
        int prevIndex = getPreviousIndex();
        if (prevIndex < 0) return null;
        return playlist.get(prevIndex);
    }

    /**
     * 移动到上一首
     */
    public int moveToPrevious() {
        int prevIndex = getPreviousIndex();
        if (prevIndex < 0) return -1;
        currentIndex = prevIndex;
        saveToStorage();
        return currentIndex;
    }

    private int getPreviousIndex() {
        if (playlist.isEmpty()) return -1;
        switch (playMode) {
            case PLAY_MODE_SHUFFLE:
                int shuffleIdx = shuffleOrder.indexOf(currentIndex);
                if (shuffleIdx <= 0) {
                    return shuffleOrder.isEmpty() ? -1 : shuffleOrder.get(shuffleOrder.size() - 1);
                }
                return shuffleOrder.get(shuffleIdx - 1);
            case PLAY_MODE_SEQUENCE:
            case PLAY_MODE_REPEAT_ONE:
            case PLAY_MODE_REPEAT_ALL:
            default:
                if (currentIndex - 1 >= 0) return currentIndex - 1;
                return playlist.size() - 1;
        }
    }

    /**
     * 重新生成随机播放顺序
     */
    public void shuffle() {
        rebuildShuffleOrder();
        if (playMode != PLAY_MODE_SHUFFLE) {
            setPlayMode(PLAY_MODE_SHUFFLE);
        }
    }

    private void rebuildShuffleOrder() {
        shuffleOrder.clear();
        for (int i = 0; i < playlist.size(); i++) {
            shuffleOrder.add(i);
        }
        Collections.shuffle(shuffleOrder, random);
        // 将当前歌曲移到洗牌序列首位
        if (currentIndex >= 0 && currentIndex < shuffleOrder.size()) {
            int idx = shuffleOrder.indexOf(currentIndex);
            if (idx > 0) {
                shuffleOrder.remove(idx);
                shuffleOrder.add(0, currentIndex);
            }
        }
    }

    /**
     * 设置播放模式
     */
    public void setPlayMode(int mode) {
        if (mode < PLAY_MODE_SEQUENCE || mode > PLAY_MODE_SHUFFLE) {
            Log.w(TAG, "Invalid play mode: " + mode);
            return;
        }
        this.playMode = mode;
        if (mode == PLAY_MODE_SHUFFLE) {
            rebuildShuffleOrder();
        }
        saveToStorage();
    }

    public int getPlayMode() {
        return playMode;
    }

    /**
     * 获取播放模式显示名称
     */
    public String getPlayModeName() {
        switch (playMode) {
            case PLAY_MODE_SEQUENCE: return "顺序播放";
            case PLAY_MODE_REPEAT_ALL: return "列表循环";
            case PLAY_MODE_REPEAT_ONE: return "单曲循环";
            case PLAY_MODE_SHUFFLE: return "随机播放";
            default: return "未知";
        }
    }

    /**
     * 获取播放列表副本
     */
    public List<MusicInfo> getPlaylist() {
        return new ArrayList<>(playlist);
    }

    public int size() {
        return playlist.size();
    }

    public boolean isEmpty() {
        return playlist.isEmpty();
    }

    public MusicInfo get(int index) {
        if (index < 0 || index >= playlist.size()) return null;
        return playlist.get(index);
    }

    /**
     * 检查歌曲是否已在播放列表中
     */
    public boolean contains(MusicInfo music) {
        if (music == null) return false;
        for (MusicInfo m : playlist) {
            if (music.equals(m)) return true;
        }
        return false;
    }

    // ====== 持久化 ======

    private void saveToStorage() {
        try {
            JSONArray array = new JSONArray();
            for (MusicInfo m : playlist) {
                array.put(m.toStorageJson());
            }
            prefs.edit()
                    .putString(KEY_PLAYLIST, array.toString())
                    .putInt(KEY_CURRENT_INDEX, currentIndex)
                    .putInt(KEY_PLAY_MODE, playMode)
                    .apply();
        } catch (Exception e) {
            Log.e(TAG, "saveToStorage failed: " + e.getMessage());
        }
    }

    private void loadFromStorage() {
        try {
            String json = prefs.getString(KEY_PLAYLIST, "");
            if (TextUtils.isEmpty(json)) return;
            JSONArray array = new JSONArray(json);
            playlist.clear();
            for (int i = 0; i < array.length(); i++) {
                JSONObject obj = array.getJSONObject(i);
                MusicInfo m = MusicInfo.fromStorageJson(obj);
                if (m != null) playlist.add(m);
            }
            currentIndex = prefs.getInt(KEY_CURRENT_INDEX, -1);
            playMode = prefs.getInt(KEY_PLAY_MODE, PLAY_MODE_SEQUENCE);
            if (currentIndex >= playlist.size()) {
                currentIndex = playlist.isEmpty() ? -1 : 0;
            }
            if (playMode == PLAY_MODE_SHUFFLE) {
                rebuildShuffleOrder();
            }
        } catch (JSONException e) {
            Log.e(TAG, "loadFromStorage failed: " + e.getMessage());
        }
    }
}
