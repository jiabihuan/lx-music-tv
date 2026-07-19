package com.lx.tv.music;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.text.TextUtils;
import android.util.Log;

import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.session.MediaSession;

import com.lx.tv.music.userapi.UserApiEngine;
import com.lx.tv.music.userapi.UserApiCallback;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;

/**
 * 音乐播放器
 * 基于 androidx.media3 ExoPlayer 实现，提供更稳定的播放和更好的格式支持。
 *
 * 工作流程：
 * 1. play(MusicInfo) -> 生成requestKey -> sendAction('request', {requestKey, data:{source, action:'musicUrl', info:{type, musicInfo}}})
 * 2. UserApiEngine onRequest回调 -> 由外部HttpExecutor执行HTTP请求 -> sendAction('response', {...})
 * 3. UserApiEngine onResponse回调 -> 获取url -> ExoPlayer.setMediaItem(MediaItem.fromUri(url)) -> prepare -> play
 *
 * 通过UserApiEngine.UserApiCallback接收JS音源回调
 */
public class MusicPlayer implements UserApiCallback {
    private static final String TAG = "MusicPlayer";

    /** 进度更新间隔（毫秒） */
    private static final int PROGRESS_UPDATE_INTERVAL = 500;
    /** 获取播放URL超时（毫秒） */
    private static final int URL_REQUEST_TIMEOUT = 20000;

    private static final int MSG_PROGRESS = 1;
    private static final int MSG_URL_TIMEOUT = 2;

    private final Context context;
    private final UserApiEngine userApiEngine;
    private final PlaylistManager playlistManager;

    private ExoPlayer exoPlayer;
    private MediaSession mediaSession;
    private PlayerCallback callback;

    /** 当前播放歌曲 */
    private MusicInfo currentMusic;
    /** 当前播放URL请求的requestKey */
    private String currentRequestKey;
    /** 当前播放URL */
    private String currentUrl;
    /** 当前音质：128k/320k/flac */
    private String quality = "128k";
    /** 是否正在准备播放器 */
    private boolean isPreparing = false;
    /** 是否正在请求URL */
    private boolean isRequestingUrl = false;
    /** 是否暂停中 */
    private boolean isPaused = false;
    /** 是否正在播放 */
    private boolean isPlaying = false;
    /** 播放完成后是否自动播放下一首 */
    private boolean autoPlayNext = true;

    /** requestKey -> 监听器映射 */
    private final Map<String, UrlRequestListener> urlRequestMap = new HashMap<>();

    private final Random random = new Random();
    private final Handler mainHandler;

    /** URL请求监听器 */
    public interface UrlRequestListener {
        void onUrlSuccess(String url);
        void onUrlError(String errorMessage);
    }

    /** 播放器回调接口 */
    public interface PlayerCallback {
        /** 开始播放 */
        void onPlayStart(MusicInfo music);
        /** 暂停 */
        void onPlayPause();
        /** 恢复播放 */
        void onPlayResume();
        /** 播放完成 */
        void onPlayComplete(MusicInfo music);
        /** 播放错误 */
        void onPlayError(MusicInfo music, String errorMessage);
        /** 进度更新，单位：秒 */
        void onProgressUpdate(int currentSec, int totalSec);
        /** 状态变化通知（加载中/缓冲等） */
        void onStatusChanged(String status);
    }

    public MusicPlayer(Context context, UserApiEngine userApiEngine, PlaylistManager playlistManager) {
        this.context = context.getApplicationContext();
        this.userApiEngine = userApiEngine;
        this.playlistManager = playlistManager;
        this.mainHandler = new Handler(Looper.getMainLooper(), new Handler.Callback() {
            @Override
            public boolean handleMessage(Message msg) {
                switch (msg.what) {
                    case MSG_PROGRESS:
                        updateProgress();
                        return true;
                    case MSG_URL_TIMEOUT:
                        handleUrlTimeout((String) msg.obj);
                        return true;
                }
                return false;
            }
        });
        // 注意：不在此处调用 userApiEngine.setCallback(this)
        // MainActivity 作为统一回调分发器，会将事件分发到 MusicPlayer 和 MusicSearchManager
    }

    public void setCallback(PlayerCallback callback) {
        this.callback = callback;
    }

    public void setQuality(String quality) {
        this.quality = quality;
    }

    public String getQuality() {
        return quality;
    }

    public void setAutoPlayNext(boolean autoPlayNext) {
        this.autoPlayNext = autoPlayNext;
    }

    public boolean isAutoPlayNext() {
        return autoPlayNext;
    }

    /**
     * 播放指定歌曲
     */
    public void play(MusicInfo music) {
        if (music == null) {
            notifyError(null, "music is null");
            return;
        }
        try {
            Log.i(TAG, "play: " + music.songName + " from " + music.getSourceDisplayName());
            // 检查音源引擎是否就绪
            if (userApiEngine == null || !userApiEngine.isEngineReady()) {
                notifyError(music, "未导入音源脚本，请先进入设置-音源导入音源");
                return;
            }
            // 停止当前播放
            stopInternal();
            currentMusic = music;
            isPaused = false;
            isPlaying = false;
            notifyStatus("加载中...");
            try {
                notifyProgress(0, music.getIntervalSeconds());
            } catch (Exception ignored) {
                notifyProgress(0, 0);
            }
            // 请求播放URL
            requestMusicUrl(music, quality, new UrlRequestListener() {
                @Override
                public void onUrlSuccess(String url) {
                    Log.i(TAG, "Got music url: " + url);
                    currentUrl = url;
                    startPlayback(url);
                }

                @Override
                public void onUrlError(String errorMessage) {
                    Log.e(TAG, "Failed to get music url: " + errorMessage);
                    notifyError(currentMusic, "获取播放链接失败: " + errorMessage);
                }
            });
        } catch (Throwable t) {
            Log.e(TAG, "play crashed: " + t.getMessage());
            notifyError(music, "播放失败: " + t.getMessage());
        }
    }

    /**
     * 播放播放列表中指定索引的歌曲
     */
    public void playIndex(int index) {
        if (playlistManager == null) return;
        MusicInfo m = playlistManager.get(index);
        if (m != null) {
            playlistManager.setCurrentIndex(index);
            play(m);
        }
    }

    /**
     * 暂停
     */
    public void pause() {
        if (exoPlayer != null && isPlaying && !isPaused) {
            exoPlayer.pause();
            isPaused = true;
            isPlaying = false;
            stopProgressUpdate();
            if (callback != null) callback.onPlayPause();
            Log.i(TAG, "Paused");
        }
    }

    /**
     * 恢复播放
     */
    public void resume() {
        if (exoPlayer != null && isPaused) {
            exoPlayer.play();
            isPaused = false;
            isPlaying = true;
            startProgressUpdate();
            if (callback != null) callback.onPlayResume();
            Log.i(TAG, "Resumed");
        } else if (exoPlayer == null && currentMusic != null && currentUrl != null) {
            // 重新启动播放
            startPlayback(currentUrl);
        }
    }

    /**
     * 停止播放
     */
    public void stop() {
        stopInternal();
        if (callback != null) callback.onPlayPause();
    }

    /**
     * 播放下一首
     */
    public void next() {
        if (playlistManager == null || playlistManager.isEmpty()) return;
        int nextIndex = playlistManager.moveToNext(true);
        if (nextIndex >= 0) {
            MusicInfo m = playlistManager.get(nextIndex);
            if (m != null) play(m);
        } else {
            stopInternal();
            notifyStatus("播放列表已结束");
        }
    }

    /**
     * 播放上一首
     */
    public void previous() {
        if (playlistManager == null || playlistManager.isEmpty()) return;
        int prevIndex = playlistManager.moveToPrevious();
        if (prevIndex >= 0) {
            MusicInfo m = playlistManager.get(prevIndex);
            if (m != null) play(m);
        }
    }

    /**
     * 跳转到指定位置
     * @param position 毫秒
     */
    public void seekTo(int position) {
        if (exoPlayer != null) {
            try {
                exoPlayer.seekTo(position);
            } catch (Exception e) {
                Log.e(TAG, "seekTo failed: " + e.getMessage());
            }
        }
    }

    public boolean isPlaying() {
        return isPlaying;
    }

    public boolean isPaused() {
        return isPaused;
    }

    public MusicInfo getCurrentMusic() {
        return currentMusic;
    }

    public int getCurrentPosition() {
        if (exoPlayer != null && (isPlaying || isPaused)) {
            try {
                return (int) exoPlayer.getCurrentPosition();
            } catch (Exception e) {
                return 0;
            }
        }
        return 0;
    }

    public int getDuration() {
        if (exoPlayer != null && (isPlaying || isPaused)) {
            try {
                return (int) exoPlayer.getDuration();
            } catch (Exception e) {
                return currentMusic != null ? currentMusic.getIntervalSeconds() * 1000 : 0;
            }
        }
        return currentMusic != null ? currentMusic.getIntervalSeconds() * 1000 : 0;
    }

    /**
     * 切换播放模式
     */
    public void cyclePlayMode() {
        if (playlistManager == null) return;
        int mode = playlistManager.getPlayMode();
        int nextMode;
        switch (mode) {
            case PlaylistManager.PLAY_MODE_SEQUENCE:
                nextMode = PlaylistManager.PLAY_MODE_REPEAT_ALL;
                break;
            case PlaylistManager.PLAY_MODE_REPEAT_ALL:
                nextMode = PlaylistManager.PLAY_MODE_REPEAT_ONE;
                break;
            case PlaylistManager.PLAY_MODE_REPEAT_ONE:
                nextMode = PlaylistManager.PLAY_MODE_SHUFFLE;
                break;
            case PlaylistManager.PLAY_MODE_SHUFFLE:
            default:
                nextMode = PlaylistManager.PLAY_MODE_SEQUENCE;
                break;
        }
        playlistManager.setPlayMode(nextMode);
    }

    // ============ 内部实现 ============

    private void stopInternal() {
        stopProgressUpdate();
        releaseExoPlayer();
        isPlaying = false;
        isPaused = false;
        isPreparing = false;
        // 取消待处理的URL请求
        if (currentRequestKey != null) {
            mainHandler.removeMessages(MSG_URL_TIMEOUT, currentRequestKey);
            urlRequestMap.remove(currentRequestKey);
            currentRequestKey = null;
        }
        isRequestingUrl = false;
    }

    /**
     * 请求播放URL
     * 通过UserApiEngine发送musicUrl请求
     */
    private void requestMusicUrl(MusicInfo music, String quality, UrlRequestListener listener) {
        if (userApiEngine == null) {
            listener.onUrlError("UserApiEngine未初始化");
            return;
        }
        if (!userApiEngine.isEngineReady()) {
            listener.onUrlError("音源引擎未就绪，请先导入音源脚本");
            return;
        }
        final String requestKey = "req_" + System.currentTimeMillis() + "_" + random.nextInt(10000);
        currentRequestKey = requestKey;
        isRequestingUrl = true;
        urlRequestMap.put(requestKey, listener);

        try {
            JSONObject requestData = new JSONObject();
            requestData.put("requestKey", requestKey);

            JSONObject data = new JSONObject();
            data.put("source", music.source != null ? music.source : "");
            data.put("action", "musicUrl");

            JSONObject info = new JSONObject();
            info.put("type", quality != null ? quality : "128k");
            info.put("musicInfo", music.toJson());
            data.put("info", info);

            requestData.put("data", data);

            boolean sent = userApiEngine.sendAction("request", requestData.toString());
            if (!sent) {
                urlRequestMap.remove(requestKey);
                isRequestingUrl = false;
                currentRequestKey = null;
                listener.onUrlError("音源引擎未就绪，请先导入音源");
                return;
            }

            // 超时处理
            Message timeoutMsg = mainHandler.obtainMessage(MSG_URL_TIMEOUT, requestKey);
            mainHandler.sendMessageDelayed(timeoutMsg, URL_REQUEST_TIMEOUT);
            Log.i(TAG, "Request music url: key=" + requestKey + " source=" + music.source + " quality=" + quality);
        } catch (JSONException e) {
            Log.e(TAG, "requestMusicUrl JSON error: " + e.getMessage());
            listener.onUrlError("请求构造失败: " + e.getMessage());
            urlRequestMap.remove(requestKey);
            isRequestingUrl = false;
            currentRequestKey = null;
        } catch (Throwable t) {
            Log.e(TAG, "requestMusicUrl crashed: " + t.getMessage());
            listener.onUrlError("请求异常: " + t.getMessage());
            urlRequestMap.remove(requestKey);
            isRequestingUrl = false;
            currentRequestKey = null;
        }
    }

    private void handleUrlTimeout(String requestKey) {
        UrlRequestListener listener = urlRequestMap.remove(requestKey);
        if (listener != null) {
            listener.onUrlError("获取播放链接超时");
        }
        if (requestKey.equals(currentRequestKey)) {
            currentRequestKey = null;
            isRequestingUrl = false;
        }
    }

    /**
     * 使用ExoPlayer开始播放URL
     */
    private void startPlayback(String url) {
        if (TextUtils.isEmpty(url)) {
            notifyError(currentMusic, "播放链接为空");
            return;
        }
        try {
            // 释放旧实例
            releaseExoPlayer();
            // 创建ExoPlayer实例
            exoPlayer = new ExoPlayer.Builder(context).build();
            exoPlayer.setPlayWhenReady(true);
            exoPlayer.addListener(new Player.Listener() {
                @Override
                public void onPlaybackStateChanged(int playbackState) {
                    switch (playbackState) {
                        case Player.STATE_READY:
                            isPreparing = false;
                            isPlaying = true;
                            isPaused = false;
                            startProgressUpdate();
                            notifyStatus("正在播放");
                            if (callback != null && currentMusic != null) {
                                callback.onPlayStart(currentMusic);
                            }
                            Log.i(TAG, "Playback started");
                            break;
                        case Player.STATE_BUFFERING:
                            if (!isPlaying) {
                                notifyStatus("缓冲中...");
                            }
                            break;
                        case Player.STATE_ENDED:
                            Log.i(TAG, "Playback completed");
                            isPlaying = false;
                            isPaused = false;
                            stopProgressUpdate();
                            if (callback != null && currentMusic != null) {
                                callback.onPlayComplete(currentMusic);
                            }
                            if (autoPlayNext) {
                                next();
                            }
                            break;
                        case Player.STATE_IDLE:
                            isPlaying = false;
                            isPreparing = false;
                            stopProgressUpdate();
                            break;
                    }
                }

                @Override
                public void onPlayerError(PlaybackException error) {
                    Log.e(TAG, "ExoPlayer error: " + error.getMessage());
                    isPlaying = false;
                    isPreparing = false;
                    stopProgressUpdate();
                    notifyError(currentMusic, "播放错误: " + error.getMessage());
                }
            });
            isPreparing = true;
            notifyStatus("缓冲中...");
            MediaItem mediaItem = MediaItem.fromUri(url);
            exoPlayer.setMediaItem(mediaItem);
            exoPlayer.prepare();
            // 创建MediaSession，支持媒体键和系统媒体控制
            releaseMediaSession();
            try {
                mediaSession = new MediaSession.Builder(context, exoPlayer).build();
            } catch (Exception e) {
                Log.w(TAG, "MediaSession creation failed: " + e.getMessage());
            }
        } catch (Throwable e) {
            Log.e(TAG, "startPlayback failed: " + e.getMessage());
            isPreparing = false;
            releaseExoPlayer();
            notifyError(currentMusic, "播放失败: " + e.getMessage());
        }
    }

    /**
     * 释放ExoPlayer实例
     */
    private void releaseExoPlayer() {
        releaseMediaSession();
        if (exoPlayer != null) {
            try {
                exoPlayer.release();
            } catch (Exception ignored) {
            }
            exoPlayer = null;
        }
    }

    /**
     * 释放MediaSession
     */
    private void releaseMediaSession() {
        if (mediaSession != null) {
            try {
                mediaSession.release();
            } catch (Exception ignored) {
            }
            mediaSession = null;
        }
    }

    private void startProgressUpdate() {
        mainHandler.removeMessages(MSG_PROGRESS);
        mainHandler.sendEmptyMessageDelayed(MSG_PROGRESS, PROGRESS_UPDATE_INTERVAL);
    }

    private void stopProgressUpdate() {
        mainHandler.removeMessages(MSG_PROGRESS);
    }

    private void updateProgress() {
        if (exoPlayer != null && isPlaying) {
            try {
                int current = (int) exoPlayer.getCurrentPosition() / 1000;
                int total = (int) exoPlayer.getDuration() / 1000;
                notifyProgress(current, total);
            } catch (Exception e) {
                // ignore
            }
            mainHandler.sendEmptyMessageDelayed(MSG_PROGRESS, PROGRESS_UPDATE_INTERVAL);
        }
    }

    private void notifyProgress(int currentSec, int totalSec) {
        if (callback != null) {
            callback.onProgressUpdate(currentSec, totalSec);
        }
    }

    private void notifyError(MusicInfo music, String message) {
        if (callback != null) callback.onPlayError(music, message);
    }

    private void notifyStatus(String status) {
        if (callback != null) callback.onStatusChanged(status);
    }

    // ============ UserApiCallback 实现 ============

    @Override
    public void onInit(boolean status, String errorMessage, Object sources) {
        // 由MainActivity处理
    }

    @Override
    public void onResponse(String requestKey, boolean status, String errorMessage, Object result) {
        Log.i(TAG, "onResponse: key=" + requestKey + " status=" + status + " result=" + result);
        UrlRequestListener listener = urlRequestMap.remove(requestKey);
        if (requestKey.equals(currentRequestKey)) {
            mainHandler.removeMessages(MSG_URL_TIMEOUT, requestKey);
            currentRequestKey = null;
            isRequestingUrl = false;
        }
        if (listener == null) {
            return;
        }
        if (!status) {
            listener.onUrlError(errorMessage != null ? errorMessage : "获取播放链接失败");
            return;
        }
        try {
            String url = null;
            if (result != null) {
                // result格式: {source, action, data:{type, url}}
                JSONObject json;
                if (result instanceof JSONObject) {
                    json = (JSONObject) result;
                } else if (result instanceof String) {
                    json = new JSONObject((String) result);
                } else {
                    json = new JSONObject(result.toString());
                }
                JSONObject data = json.optJSONObject("data");
                if (data != null) {
                    url = data.optString("url", null);
                }
                if (url == null) {
                    url = json.optString("url", null);
                }
            }
            if (TextUtils.isEmpty(url)) {
                listener.onUrlError("播放链接为空");
            } else {
                listener.onUrlSuccess(url);
            }
        } catch (JSONException e) {
            listener.onUrlError("解析响应失败: " + e.getMessage());
        }
    }

    @Override
    public void onRequest(String requestKey, String url, Object options) {
        // 由MainActivity的HttpExecutor处理（OkHttp）
        // 这里不处理HTTP请求，由外部统一处理
    }

    @Override
    public void onCancelRequest(String requestKey) {
        urlRequestMap.remove(requestKey);
        if (requestKey.equals(currentRequestKey)) {
            mainHandler.removeMessages(MSG_URL_TIMEOUT, requestKey);
            currentRequestKey = null;
            isRequestingUrl = false;
        }
    }

    @Override
    public void onShowUpdateAlert(String name, String log, String updateUrl) {
        // 由MainActivity处理
    }

    @Override
    public void onLog(String type, String log) {
        Log.d(TAG, "[UserApi:" + type + "] " + log);
    }

    /**
     * 销毁播放器，释放资源
     */
    public void destroy() {
        stopInternal();
        urlRequestMap.clear();
    }
}
