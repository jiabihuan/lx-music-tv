package com.lx.tv.music;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.UiModeManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.lx.tv.music.network.HttpFetcher;
import com.lx.tv.music.userapi.UserApiCallback;
import com.lx.tv.music.userapi.UserApiEngine;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import okhttp3.Call;
import okhttp3.OkHttpClient;

/**
 * 主界面Activity
 *
 * 布局结构：
 *   - 顶部：搜索框 + Tab切换(搜索/排行/我的)
 *   - 中间：RecyclerView歌曲列表
 *   - 底部：播放控制栏(歌曲名+歌手+进度条+上一首/播放暂停/下一首)
 *
 * 遥控器按键处理：
 *   - D-pad上下：在歌曲列表中移动焦点
 *   - D-pad左右：切换顶部Tab（搜索/排行/我的列表）
 *   - 确定键：播放选中歌曲
 *   - 菜单键：打开设置/音源管理
 *   - 返回键：处理返回逻辑
 *   - 媒体键：播放/暂停/上一首/下一首
 *
 * 作为统一UserApiCallback分发器，将JS音源事件分发到MusicPlayer和MusicSearchManager
 * HTTP请求通过 {@link HttpFetcher} 执行
 */
public class MainActivity extends Activity implements UserApiCallback,
        MusicAdapter.OnItemClickListener, MusicPlayer.PlayerCallback,
        MusicSearchManager.SearchCallback {
    private static final String TAG = "MainActivity";

    private static final String PREFS_NAME = "lx_tv_settings";
    private static final String KEY_QUALITY = "quality";
    private static final String KEY_AUTO_NEXT = "auto_play_next";
    private static final int REQUEST_CODE_SETTINGS = 1001;

    // UI 组件
    private EditText etSearch;
    private TextView tabSearch, tabRank, tabMine, tabSettings;
    private TextView[] tabs;
    private RecyclerView recyclerView;
    private MusicAdapter musicAdapter;
    private ProgressBar progressBar;
    private TextView tvEmpty;
    private TextView tvStatus;
    private ImageView ivSettings;

    // 播放控制栏
    private View playerBar;
    private TextView tvPlayingTitle;
    private TextView tvPlayingArtist;
    private TextView tvCurrentTime;
    private TextView tvTotalTime;
    private SeekBar seekBar;
    private ImageButton btnPrev;
    private ImageButton btnPlayPause;
    private ImageButton btnNext;
    private TextView tvPlayMode;

    // 业务对象
    private UserApiEngine userApiEngine;
    private UserApiScriptManager scriptManager;
    private PlaylistManager playlistManager;
    private MusicPlayer musicPlayer;
    private MusicSearchManager searchManager;
    private RankApiManager rankApiManager;

    // HTTP客户端（用于JS音源的HTTP请求）
    private HttpFetcher httpFetcher;
    private OkHttpClient httpClient;
    private final Map<String, Call> httpCallMap = new ConcurrentHashMap<>();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // 状态
    private int currentTab = 0; // 0=搜索, 1=排行, 2=我的, 3=音源
    private boolean isUserSeeking = false;
    private SharedPreferences settingsPrefs;
    // 设备类型：true 表示 TV 模式（大字体+焦点导航为主），false 表示手机模式（触屏点击为主，同时保留焦点导航以支持蓝牙遥控器）
    private boolean isTvMode = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // 注册全局未捕获异常处理器，记录崩溃日志到文件，避免直接闪退无任何提示
        final Thread.UncaughtExceptionHandler defaultHandler = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Thread t, Throwable e) {
                Log.e(TAG, "Uncaught exception in " + t.getName(), e);
                try {
                    java.io.File logFile = new java.io.File(getFilesDir(), "crash_log.txt");
                    java.io.FileWriter fw = new java.io.FileWriter(logFile, true);
                    java.io.PrintWriter pw = new java.io.PrintWriter(fw);
                    pw.println("==== " + new java.util.Date() + " ====");
                    pw.println("Thread: " + t.getName());
                    e.printStackTrace(pw);
                    pw.println();
                    pw.flush();
                    pw.close();
                } catch (Exception ignored) {
                }
                if (defaultHandler != null) {
                    defaultHandler.uncaughtException(t, e);
                }
            }
        });

        setContentView(R.layout.activity_main);

        settingsPrefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        // 检测当前设备类型（TV / 手机 / 平板）
        isTvMode = isTvMode();

        initViews();
        initBusiness();
        initDefaultScript();
    }

    /**
     * 检测当前是否运行在 TV 模式
     * 通过 UiModeManager 判断设备类型，UI_MODE_TYPE_TELEVISION 表示 TV 设备
     * 手机/平板/模拟器返回 false
     */
    private boolean isTvMode() {
        UiModeManager uiModeManager = (UiModeManager) getSystemService(UI_MODE_SERVICE);
        return uiModeManager != null
                && uiModeManager.getCurrentModeType() == Configuration.UI_MODE_TYPE_TELEVISION;
    }

    private void initViews() {
        etSearch = findViewById(R.id.et_search);
        tabSearch = findViewById(R.id.tab_search);
        tabRank = findViewById(R.id.tab_rank);
        tabMine = findViewById(R.id.tab_mine);
        tabSettings = findViewById(R.id.tab_settings);
        tabs = new TextView[]{tabSearch, tabRank, tabMine, tabSettings};
        ivSettings = findViewById(R.id.iv_settings);

        recyclerView = findViewById(R.id.recycler_view);
        progressBar = findViewById(R.id.progress_bar);
        tvEmpty = findViewById(R.id.tv_empty);
        tvStatus = findViewById(R.id.tv_status);

        // 播放栏（通过 include 引入 view_player_bar.xml，内部控件 ID 可直接 findViewById）
        playerBar = findViewById(R.id.player_bar);
        tvPlayingTitle = findViewById(R.id.tv_playing_title);
        tvPlayingArtist = findViewById(R.id.tv_playing_artist);
        tvCurrentTime = findViewById(R.id.tv_current_time);
        tvTotalTime = findViewById(R.id.tv_total_time);
        seekBar = findViewById(R.id.seek_bar);
        btnPrev = findViewById(R.id.btn_prev);
        btnPlayPause = findViewById(R.id.btn_play_pause);
        btnNext = findViewById(R.id.btn_next);
        tvPlayMode = findViewById(R.id.tv_play_mode);

        // RecyclerView 配置
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        recyclerView.setHasFixedSize(true);
        musicAdapter = new MusicAdapter(this);
        musicAdapter.setOnItemClickListener(this);
        recyclerView.setAdapter(musicAdapter);

        // 焦点设置 - 默认搜索框获得焦点
        etSearch.setFocusable(true);
        etSearch.setFocusableInTouchMode(true);

        // Tab 点击
        tabSearch.setOnClickListener(v -> switchTab(0));
        tabRank.setOnClickListener(v -> switchTab(1));
        tabMine.setOnClickListener(v -> switchTab(2));
        tabSettings.setOnClickListener(v -> switchTab(3));

        // 设置按钮点击
        ivSettings.setOnClickListener(v -> openSettings());

        // 搜索框：回车触发搜索
        etSearch.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                if (actionId == EditorInfo.IME_ACTION_SEARCH
                        || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER
                        && event.getAction() == KeyEvent.ACTION_DOWN)) {
                    doSearch();
                    return true;
                }
                return false;
            }
        });

        // 播放栏按钮
        btnPrev.setOnClickListener(v -> musicPlayer.previous());
        btnNext.setOnClickListener(v -> musicPlayer.next());
        btnPlayPause.setOnClickListener(v -> {
            if (musicPlayer.isPlaying()) {
                musicPlayer.pause();
            } else if (musicPlayer.isPaused()) {
                musicPlayer.resume();
            }
        });

        // SeekBar 进度拖动
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) {
                    tvCurrentTime.setText(formatTime(progress / 1000));
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                isUserSeeking = true;
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                int progress = seekBar.getProgress();
                musicPlayer.seekTo(progress);
                isUserSeeking = false;
            }
        });

        // 让播放栏按钮和Tab支持遥控器焦点
        for (TextView tab : tabs) {
            tab.setFocusable(true);
            tab.setFocusableInTouchMode(true);
        }
        btnPrev.setFocusable(true);
        btnNext.setFocusable(true);
        btnPlayPause.setFocusable(true);

        // 按设备类型调整交互行为（尺寸已通过 dimens.xml 自动适配 sw360dp/sw600dp）
        applyDeviceModeAdjustments();

        updateTabHighlight();
        showEmpty("请输入关键词搜索音乐");
    }

    /**
     * 按当前设备类型调整交互行为
     * - TV 模式：保持焦点导航为主，启动时主动聚焦到第一个 Tab，方便遥控器直接操作
     * - 手机模式：触屏点击为主，避免启动时弹出软键盘；同时保留焦点能力以支持蓝牙遥控器/方向键
     * 注意：所有 clickable + focusable 属性已在 XML 中声明，触屏点击与焦点导航可共存
     */
    private void applyDeviceModeAdjustments() {
        if (isTvMode) {
            // TV：聚焦到首个 Tab，方便遥控器直接切换
            if (tabSearch != null) {
                tabSearch.requestFocus();
            }
            // TV 模式不主动聚焦 EditText，避免弹出输入法
        } else {
            // 手机：RecyclerView 默认已支持触屏滑动与点击；
            // 不主动给 EditText 焦点，避免启动时直接弹出软键盘；
            // 触屏点击列表项由 MusicAdapter.OnItemClickListener 处理
            // 焦点导航仍可用（蓝牙遥控器/方向键），因为所有交互控件都设置了 focusable=true
            if (recyclerView != null) {
                // 触屏模式下增加点击反馈
                recyclerView.setSoundEffectsEnabled(true);
            }
        }
    }

    private void initBusiness() {
        // 初始化OkHttpClient和HttpFetcher（用于JS音源发起的HTTP请求）
        httpClient = new OkHttpClient.Builder()
                .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                .writeTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                .followRedirects(true)
                .build();
        httpFetcher = new HttpFetcher(httpClient);

        // 初始化业务对象
        scriptManager = new UserApiScriptManager(this);
        playlistManager = new PlaylistManager(this);
        userApiEngine = new UserApiEngine(this);
        // 注册MainActivity为统一回调分发器
        userApiEngine.setCallback(this);

        musicPlayer = new MusicPlayer(this, userApiEngine, playlistManager);
        musicPlayer.setCallback(this);
        musicPlayer.setQuality(settingsPrefs.getString(KEY_QUALITY, "128k"));
        musicPlayer.setAutoPlayNext(settingsPrefs.getBoolean(KEY_AUTO_NEXT, true));

        searchManager = new MusicSearchManager(userApiEngine);
        searchManager.setSearchCallback(this);

        // 排行榜API管理器
        rankApiManager = new RankApiManager(httpClient);

        updatePlayModeDisplay();
    }

    /**
     * 初始化默认音源脚本
     */
    private void initDefaultScript() {
        android.os.Bundle activeScript = scriptManager.getActiveScript();
        if (activeScript != null) {
            Log.i(TAG, "Loading active script: " + activeScript.getString("name"));
            userApiEngine.loadScript(activeScript);
        } else {
            showStatus("未导入音源脚本，请按菜单键进入设置导入");
            Toast.makeText(this, "请按菜单键进入设置导入音源", Toast.LENGTH_LONG).show();
        }
    }

    // ============ Tab 切换 ============

    private void switchTab(int index) {
        if (index < 0 || index >= tabs.length) return;
        currentTab = index;
        updateTabHighlight();
        switch (index) {
            case 0: // 搜索
                showSearchResults();
                break;
            case 1: // 排行
                showRankList();
                break;
            case 2: // 我的（播放列表）
                showMyPlaylist();
                break;
            case 3: // 音源设置
                openSettings();
                break;
        }
    }

    private void updateTabHighlight() {
        for (int i = 0; i < tabs.length; i++) {
            if (tabs[i] == null) continue;
            if (i == currentTab) {
                tabs[i].setTextColor(0xFFFFC107);
                tabs[i].setBackgroundResource(R.drawable.focus_background);
            } else {
                tabs[i].setTextColor(0xCCFFFFFF);
                tabs[i].setBackgroundColor(0x00000000);
            }
        }
    }

    private void showSearchResults() {
        List<MusicInfo> results = searchManager.getCurrentResults();
        if (results.isEmpty()) {
            showEmpty("请输入关键词搜索音乐");
        } else {
            musicAdapter.setData(results);
            hideEmpty();
        }
    }

    private void showRankList() {
        // 弹出排行榜选择对话框
        List<RankApiManager.RankInfo> ranks = RankApiManager.getAvailableRanks();
        String[] rankNames = new String[ranks.size()];
        for (int i = 0; i < ranks.size(); i++) {
            rankNames[i] = ranks.get(i).name;
        }
        new AlertDialog.Builder(this)
                .setTitle("选择排行榜")
                .setItems(rankNames, (dialog, which) -> {
                    RankApiManager.RankInfo rank = ranks.get(which);
                    loadRankList(rank);
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void loadRankList(RankApiManager.RankInfo rank) {
        showLoading(true);
        showStatus("正在加载" + rank.name + "...");
        rankApiManager.loadRank(rank, new RankApiManager.RankCallback() {
            @Override
            public void onRankLoadStart(String rankName) {
                mainHandler.post(() -> {
                    showLoading(true);
                    showStatus("正在加载" + rankName + "...");
                });
            }

            @Override
            public void onRankLoadSuccess(List<MusicInfo> songs, String rankName) {
                mainHandler.post(() -> {
                    showLoading(false);
                    if (songs != null && !songs.isEmpty()) {
                        musicAdapter.setData(songs);
                        hideEmpty();
                        showStatus(rankName + " 共 " + songs.size() + " 首");
                        if (recyclerView.getChildCount() > 0) {
                            recyclerView.getChildAt(0).requestFocus();
                        }
                    } else {
                        showEmpty(rankName + " 暂无数据");
                    }
                });
            }

            @Override
            public void onRankLoadError(String errorMessage) {
                mainHandler.post(() -> {
                    showLoading(false);
                    showEmpty("加载失败: " + errorMessage);
                    showStatus("排行榜加载失败: " + errorMessage);
                });
            }
        });
    }

    private void showMyPlaylist() {
        List<MusicInfo> playlist = playlistManager.getPlaylist();
        if (playlist.isEmpty()) {
            showEmpty("播放列表为空");
        } else {
            musicAdapter.setData(playlist);
            hideEmpty();
        }
    }

    // ============ 搜索 ============

    private void doSearch() {
        String keyword = etSearch.getText().toString().trim();
        if (TextUtils.isEmpty(keyword)) {
            Toast.makeText(this, "请输入搜索关键词", Toast.LENGTH_SHORT).show();
            return;
        }
        if (userApiEngine == null || !userApiEngine.isEngineReady()) {
            Toast.makeText(this, "请先在设置-音源中导入音源脚本后再搜索", Toast.LENGTH_LONG).show();
            showStatus("未导入音源脚本，无法搜索");
            return;
        }
        switchTab(0);
        showLoading(true);
        searchManager.search(keyword);
    }

    @Override
    public void onSearchStart(String keyword) {
        mainHandler.post(() -> {
            showLoading(true);
            showStatus("正在搜索: " + keyword);
        });
    }

    @Override
    public void onSearchResult(List<MusicInfo> results, String source) {
        mainHandler.post(() -> {
            if (currentTab == 0) {
                musicAdapter.appendData(results);
                hideEmpty();
            }
        });
    }

    @Override
    public void onSearchComplete(String keyword, List<MusicInfo> allResults) {
        mainHandler.post(() -> {
            showLoading(false);
            showStatus("搜索完成，共 " + allResults.size() + " 首歌曲");
            if (currentTab == 0 && !allResults.isEmpty()) {
                musicAdapter.setData(allResults);
                hideEmpty();
                // 默认聚焦第一项
                if (recyclerView.getChildCount() > 0) {
                    recyclerView.getChildAt(0).requestFocus();
                }
            }
        });
    }

    @Override
    public void onSearchError(String errorMessage) {
        mainHandler.post(() -> {
            showLoading(false);
            showStatus("搜索失败: " + errorMessage);
        });
    }

    // ============ 列表项点击 ============

    @Override
    public void onItemClick(MusicInfo music, int position) {
        if (music == null) return;
        try {
            // 检查音源引擎是否就绪，未导入音源时直接引导用户去设置
            if (userApiEngine == null || !userApiEngine.isEngineReady()) {
                Toast.makeText(this, "请先在设置-音源中导入音源脚本后再播放", Toast.LENGTH_LONG).show();
                showStatus("未导入音源脚本，无法播放");
                return;
            }
            // 添加到播放列表并播放
            if (!playlistManager.contains(music)) {
                playlistManager.addToPlaylist(music);
            }
            // 设置当前播放索引
            int idx = playlistManager.indexOf(music);
            if (idx >= 0) {
                playlistManager.setCurrentIndex(idx);
            }
            musicPlayer.play(music);
            musicAdapter.setPlayingMusic(music);
            Toast.makeText(this, "正在播放: " + music.songName, Toast.LENGTH_SHORT).show();
        } catch (Throwable t) {
            Log.e(TAG, "onItemClick crashed: " + t.getMessage());
            Toast.makeText(this, "播放失败: " + t.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    // ============ PlayerCallback 实现 ============

    @Override
    public void onPlayStart(MusicInfo music) {
        mainHandler.post(() -> {
            tvPlayingTitle.setText(music != null ? music.songName : "");
            tvPlayingArtist.setText(music != null ? music.singer : "");
            btnPlayPause.setImageResource(R.drawable.ic_pause);
            playerBar.setVisibility(View.VISIBLE);
            musicAdapter.setPlayingMusic(music);
        });
    }

    @Override
    public void onPlayPause() {
        mainHandler.post(() -> btnPlayPause.setImageResource(R.drawable.ic_play));
    }

    @Override
    public void onPlayResume() {
        mainHandler.post(() -> btnPlayPause.setImageResource(R.drawable.ic_pause));
    }

    @Override
    public void onPlayComplete(MusicInfo music) {
        // 自动播放下一首由MusicPlayer处理
    }

    @Override
    public void onPlayError(MusicInfo music, String errorMessage) {
        mainHandler.post(() -> {
            Toast.makeText(this, "播放错误: " + errorMessage, Toast.LENGTH_LONG).show();
            btnPlayPause.setImageResource(R.drawable.ic_play);
            showStatus("播放错误: " + errorMessage);
        });
    }

    @Override
    public void onProgressUpdate(int currentSec, int totalSec) {
        mainHandler.post(() -> {
            if (!isUserSeeking) {
                seekBar.setMax(totalSec * 1000);
                seekBar.setProgress(currentSec * 1000);
                tvCurrentTime.setText(formatTime(currentSec));
                tvTotalTime.setText(formatTime(totalSec));
            }
        });
    }

    @Override
    public void onStatusChanged(String status) {
        mainHandler.post(() -> showStatus(status));
    }

    // ============ UserApiCallback 实现（统一分发器） ============

    @Override
    public void onInit(boolean status, String errorMessage, Object sources) {
        Log.i(TAG, "onInit: status=" + status + " msg=" + errorMessage);
        // 分发到 MusicSearchManager（更新音源能力信息）
        if (searchManager != null) {
            searchManager.onInit(status, errorMessage, sources);
        }
        mainHandler.post(() -> {
            if (status) {
                showStatus("音源加载成功");
            } else {
                showStatus("音源加载失败: " + errorMessage);
                Toast.makeText(this, "音源加载失败: " + errorMessage, Toast.LENGTH_LONG).show();
            }
        });
    }

    @Override
    public void onResponse(String requestKey, boolean status, String errorMessage, Object result) {
        // 分发到 MusicPlayer 和 MusicSearchManager
        // 两者各自维护requestKey->listener映射，无监听者的会直接返回
        if (musicPlayer != null) {
            musicPlayer.onResponse(requestKey, status, errorMessage, result);
        }
        if (searchManager != null) {
            searchManager.onResponse(requestKey, status, errorMessage, result);
        }
    }

    @Override
    public void onRequest(String requestKey, String url, Object options) {
        // 使用 HttpFetcher 执行 OkHttp 请求，结果通过 sendAction('response', ...) 返回
        executeHttp(requestKey, url, options);
    }

    @Override
    public void onCancelRequest(String requestKey) {
        // 取消HTTP请求
        Call call = httpCallMap.remove(requestKey);
        if (call != null && !call.isCanceled()) {
            call.cancel();
        }
        // 通知MusicPlayer和MusicSearchManager
        if (musicPlayer != null) musicPlayer.onCancelRequest(requestKey);
        if (searchManager != null) searchManager.onCancelRequest(requestKey);
    }

    @Override
    public void onShowUpdateAlert(String name, String log, String updateUrl) {
        mainHandler.post(() -> {
            String msg = "音源 [" + name + "] 有更新:\n" + log;
            if (!TextUtils.isEmpty(updateUrl)) {
                msg += "\n更新地址: " + updateUrl;
            }
            new AlertDialog.Builder(this)
                    .setTitle("音源更新提示")
                    .setMessage(msg)
                    .setPositiveButton("确定", null)
                    .show();
        });
    }

    @Override
    public void onLog(String type, String log) {
        Log.d(TAG, "[UserApi:" + type + "] " + log);
    }

    // ============ HttpFetcher HTTP 请求执行 ============

    /**
     * 执行JS音源发起的HTTP请求
     * 使用 HttpFetcher 执行 OkHttp 请求，结果通过 sendAction('response', ...) 返回给JS
     *
     * options格式: {method, headers, body, form, formData, timeout, binary, ...}
     * 响应格式（发送回JS）: {error, requestKey, response:{statusCode, headers, body, ...}}
     */
    private void executeHttp(final String requestKey, final String url, final Object options) {
        if (httpFetcher == null || url == null) {
            sendHttpResponse(requestKey, null, "httpFetcher未初始化或URL为空");
            return;
        }
        try {
            JSONObject opts;
            if (options == null) {
                opts = new JSONObject();
            } else if (options instanceof JSONObject) {
                opts = (JSONObject) options;
            } else if (options instanceof String) {
                opts = new JSONObject((String) options);
            } else {
                opts = new JSONObject(options.toString());
            }

            httpFetcher.fetchRequest(url, opts, new HttpFetcher.FetchCallback() {
                @Override
                public void onResult(boolean success, String errorMessage, JSONObject result) {
                    if (success) {
                        sendHttpResponse(requestKey, result, null);
                    } else {
                        sendHttpResponse(requestKey, null, errorMessage);
                    }
                }
            });
            Log.d(TAG, "HTTP request dispatched: " + url);
        } catch (JSONException e) {
            Log.e(TAG, "executeHttp options parse error: " + e.getMessage());
            sendHttpResponse(requestKey, null, "请求选项解析失败: " + e.getMessage());
        } catch (Exception e) {
            Log.e(TAG, "executeHttp failed: " + e.getMessage());
            sendHttpResponse(requestKey, null, e.getMessage());
        }
    }

    /**
     * 将HTTP响应发送回UserApiEngine
     * 响应格式: {error, requestKey, response}
     */
    private void sendHttpResponse(String requestKey, JSONObject response, String error) {
        if (userApiEngine == null) return;
        try {
            JSONObject result = new JSONObject();
            result.put("requestKey", requestKey);
            if (error != null) {
                result.put("error", error);
                result.put("response", JSONObject.NULL);
            } else {
                result.put("error", JSONObject.NULL);
                result.put("response", response != null ? response : JSONObject.NULL);
            }
            userApiEngine.sendAction("response", result.toString());
        } catch (JSONException e) {
            Log.e(TAG, "sendHttpResponse JSON error: " + e.getMessage());
        }
    }

    // ============ 遥控器按键处理 ============

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_MENU:
            case KeyEvent.KEYCODE_SETTINGS: {
                openSettings();
                return true;
            }
            case KeyEvent.KEYCODE_DPAD_LEFT: {
                // 在Tab上时切换Tab
                View focused = getCurrentFocus();
                if (focused != null && isTabView(focused)) {
                    switchTab(currentTab > 0 ? currentTab - 1 : currentTab);
                    return true;
                }
                break;
            }
            case KeyEvent.KEYCODE_DPAD_RIGHT: {
                View focused = getCurrentFocus();
                if (focused != null && isTabView(focused)) {
                    switchTab(currentTab < tabs.length - 1 ? currentTab + 1 : currentTab);
                    return true;
                }
                break;
            }
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE: {
                if (musicPlayer != null) {
                    if (musicPlayer.isPlaying()) musicPlayer.pause();
                    else if (musicPlayer.isPaused()) musicPlayer.resume();
                }
                return true;
            }
            case KeyEvent.KEYCODE_MEDIA_NEXT: {
                if (musicPlayer != null) musicPlayer.next();
                return true;
            }
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS: {
                if (musicPlayer != null) musicPlayer.previous();
                return true;
            }
            case KeyEvent.KEYCODE_MEDIA_PLAY: {
                if (musicPlayer != null && musicPlayer.isPaused()) musicPlayer.resume();
                return true;
            }
            case KeyEvent.KEYCODE_MEDIA_PAUSE: {
                if (musicPlayer != null && musicPlayer.isPlaying()) musicPlayer.pause();
                return true;
            }
        }
        return super.onKeyDown(keyCode, event);
    }

    private boolean isTabView(View v) {
        return v == tabSearch || v == tabRank || v == tabMine || v == tabSettings;
    }

    @Override
    public void onBackPressed() {
        // 如果当前焦点在搜索框以外，先回到搜索框
        if (getCurrentFocus() != etSearch && currentTab != 0) {
            switchTab(0);
            etSearch.requestFocus();
            return;
        }
        // 退出应用
        super.onBackPressed();
    }

    private void openSettings() {
        Intent intent = new Intent(this, SettingsActivity.class);
        startActivityForResult(intent, REQUEST_CODE_SETTINGS);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_CODE_SETTINGS) {
            // 重新加载音源或设置
            if (data != null && data.getBooleanExtra("script_changed", false)) {
                reloadScript();
            }
            // 更新音质设置
            String quality = settingsPrefs.getString(KEY_QUALITY, "128k");
            musicPlayer.setQuality(quality);
            boolean autoNext = settingsPrefs.getBoolean(KEY_AUTO_NEXT, true);
            musicPlayer.setAutoPlayNext(autoNext);
        }
    }

    /**
     * 重新加载当前激活的音源
     */
    private void reloadScript() {
        android.os.Bundle activeScript = scriptManager.getActiveScript();
        if (activeScript != null) {
            showStatus("正在重新加载音源...");
            userApiEngine.loadScript(activeScript);
        } else {
            showStatus("未导入音源脚本");
        }
    }

    // ============ 生命周期 ============

    @Override
    protected void onResume() {
        super.onResume();
        // 刷新"我的"列表
        if (currentTab == 2) {
            showMyPlaylist();
        }
        // 刷新播放模式显示
        updatePlayModeDisplay();
    }

    @Override
    protected void onPause() {
        super.onPause();
        // 不暂停播放，让后台继续
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // 取消所有HTTP请求
        for (Call call : httpCallMap.values()) {
            if (!call.isCanceled()) call.cancel();
        }
        httpCallMap.clear();
        // 销毁播放器
        if (musicPlayer != null) {
            musicPlayer.destroy();
        }
        // 销毁UserApiEngine
        if (userApiEngine != null) {
            userApiEngine.destroy();
        }
    }

    // ============ 工具方法 ============

    private void showLoading(boolean show) {
        progressBar.setVisibility(show ? View.VISIBLE : View.GONE);
        if (show) tvEmpty.setVisibility(View.GONE);
    }

    private void showEmpty(String msg) {
        tvEmpty.setText(msg);
        tvEmpty.setVisibility(View.VISIBLE);
        recyclerView.setVisibility(View.GONE);
    }

    private void hideEmpty() {
        tvEmpty.setVisibility(View.GONE);
        recyclerView.setVisibility(View.VISIBLE);
    }

    private void showStatus(String msg) {
        tvStatus.setText(msg);
        tvStatus.setVisibility(TextUtils.isEmpty(msg) ? View.GONE : View.VISIBLE);
    }

    private void updatePlayModeDisplay() {
        if (playlistManager != null && tvPlayMode != null) {
            tvPlayMode.setText(playlistManager.getPlayModeName());
        }
    }

    /**
     * 格式化时间为 mm:ss
     */
    public static String formatTime(int seconds) {
        if (seconds < 0) seconds = 0;
        int min = seconds / 60;
        int sec = seconds % 60;
        return String.format("%02d:%02d", min, sec);
    }
}
