package com.lx.tv.music;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.TextUtils;
import android.util.Log;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * 设置界面Activity
 *
 * 功能：
 *   - 音源管理：显示已导入的音源列表，支持导入/删除
 *   - 音质选择：128k/320k/flac
 *   - 播放设置：自动播放下一首
 *   - 循环模式：顺序/列表循环/单曲循环/随机
 *   - 关于页面
 *
 * 遥控器导航：所有设置项均支持D-pad焦点导航
 */
public class SettingsActivity extends Activity {
    private static final String TAG = "SettingsActivity";

    private static final String PREFS_NAME = "lx_tv_settings";
    private static final String KEY_QUALITY = "quality";
    private static final String KEY_AUTO_NEXT = "auto_play_next";
    private static final String KEY_PLAY_MODE = "play_mode";

    private static final int REQUEST_CODE_IMPORT_FILE = 2001;

    private SharedPreferences prefs;
    private UserApiScriptManager scriptManager;
    private PlaylistManager playlistManager;

    // 音源管理
    private ListView lvScripts;
    private ScriptListAdapter scriptAdapter;
    private Button btnImportFile;
    private Button btnImportUrl;
    private Button btnBack;

    // 手动添加音源 & 订阅管理
    private Button btnAddManual;          // 手动添加音源按钮
    private Button btnAddSubscription;    // 添加订阅URL按钮
    private Button btnRefreshSubscriptions; // 刷新所有订阅按钮
    private ListView lvSubscriptions;     // 订阅URL列表
    private ArrayAdapter<String> subscriptionAdapter;

    /** 主线程Handler（用于异步回调更新UI） */
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // 音质选择
    private RadioGroup rgQuality;
    private RadioButton rb128k;
    private RadioButton rb320k;
    private RadioButton rbFlac;

    // 播放设置
    private Switch swAutoNext;

    // 循环模式
    private RadioGroup rgPlayMode;
    private RadioButton rbSequence;
    private RadioButton rbRepeatAll;
    private RadioButton rbRepeatOne;
    private RadioButton rbShuffle;

    // 关于
    private TextView tvAbout;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        scriptManager = new UserApiScriptManager(this);
        playlistManager = new PlaylistManager(this);

        initViews();
        loadSettings();
        refreshScriptList();
        refreshSubscriptionList();
    }

    private void initViews() {
        btnBack = findViewById(R.id.btn_back);
        btnImportFile = findViewById(R.id.btn_import_file);
        btnImportUrl = findViewById(R.id.btn_import_url);
        lvScripts = findViewById(R.id.lv_scripts);

        // 手动添加音源 & 订阅管理控件（布局由另一任务添加，缺失时findViewById返回null）
        btnAddManual = findViewById(R.id.btn_add_manual);
        btnAddSubscription = findViewById(R.id.btn_add_subscription);
        btnRefreshSubscriptions = findViewById(R.id.btn_refresh_subscriptions);
        lvSubscriptions = findViewById(R.id.lv_subscriptions);

        rgQuality = findViewById(R.id.rg_quality);
        rb128k = findViewById(R.id.rb_128k);
        rb320k = findViewById(R.id.rb_320k);
        rbFlac = findViewById(R.id.rb_flac);

        swAutoNext = findViewById(R.id.sw_auto_next);

        rgPlayMode = findViewById(R.id.rg_play_mode);
        rbSequence = findViewById(R.id.rb_sequence);
        rbRepeatAll = findViewById(R.id.rb_repeat_all);
        rbRepeatOne = findViewById(R.id.rb_repeat_one);
        rbShuffle = findViewById(R.id.rb_shuffle);

        tvAbout = findViewById(R.id.tv_about);

        scriptAdapter = new ScriptListAdapter();
        lvScripts.setAdapter(scriptAdapter);

        // 初始化订阅列表适配器
        if (lvSubscriptions != null) {
            subscriptionAdapter = new ArrayAdapter<>(this,
                    android.R.layout.simple_list_item_1, new ArrayList<>());
            lvSubscriptions.setAdapter(subscriptionAdapter);
            // 长按订阅项删除
            lvSubscriptions.setOnItemLongClickListener((parent, view, position, id) -> {
                String url = subscriptionAdapter.getItem(position);
                if (url != null) {
                    showRemoveSubscriptionDialog(url);
                }
                return true;
            });
        }

        // 返回按钮
        btnBack.setOnClickListener(v -> finish());
        // 导入文件
        btnImportFile.setOnClickListener(v -> openFileChooser());
        // 导入URL
        btnImportUrl.setOnClickListener(v -> showImportUrlDialog());

        // 手动添加音源（直接粘贴JS脚本内容）
        if (btnAddManual != null) {
            btnAddManual.setOnClickListener(v -> showAddManualScriptDialog());
        }
        // 添加订阅URL
        if (btnAddSubscription != null) {
            btnAddSubscription.setOnClickListener(v -> showAddSubscriptionDialog());
        }
        // 刷新所有订阅
        if (btnRefreshSubscriptions != null) {
            btnRefreshSubscriptions.setOnClickListener(v -> refreshAllSubscriptions());
        }

        // 音质选择
        rgQuality.setOnCheckedChangeListener((group, checkedId) -> {
            String quality = "128k";
            if (checkedId == R.id.rb_128k) quality = "128k";
            else if (checkedId == R.id.rb_320k) quality = "320k";
            else if (checkedId == R.id.rb_flac) quality = "flac";
            prefs.edit().putString(KEY_QUALITY, quality).apply();
            showToast("音质设置为: " + quality);
        });

        // 自动播放下一首
        swAutoNext.setOnCheckedChangeListener((buttonView, isChecked) -> {
            prefs.edit().putBoolean(KEY_AUTO_NEXT, isChecked).apply();
            showToast(isChecked ? "已开启自动播放下一首" : "已关闭自动播放下一首");
        });

        // 循环模式
        rgPlayMode.setOnCheckedChangeListener((group, checkedId) -> {
            int mode = PlaylistManager.PLAY_MODE_SEQUENCE;
            if (checkedId == R.id.rb_sequence) mode = PlaylistManager.PLAY_MODE_SEQUENCE;
            else if (checkedId == R.id.rb_repeat_all) mode = PlaylistManager.PLAY_MODE_REPEAT_ALL;
            else if (checkedId == R.id.rb_repeat_one) mode = PlaylistManager.PLAY_MODE_REPEAT_ONE;
            else if (checkedId == R.id.rb_shuffle) mode = PlaylistManager.PLAY_MODE_SHUFFLE;
            prefs.edit().putInt(KEY_PLAY_MODE, mode).apply();
            playlistManager.setPlayMode(mode);
            showToast("播放模式: " + playlistManager.getPlayModeName());
        });

        // 关于
        tvAbout.setText("落雪音乐 TV版 v1.0.0\n" +
                "基于落雪音乐JS音源协议\n" +
                "支持酷我、酷狗、QQ音乐、网易云、咪咕音源\n" +
                "本应用仅供学习交流使用");
    }

    private void loadSettings() {
        // 音质
        String quality = prefs.getString(KEY_QUALITY, "128k");
        if ("320k".equals(quality)) rb320k.setChecked(true);
        else if ("flac".equals(quality)) rbFlac.setChecked(true);
        else rb128k.setChecked(true);

        // 自动播放下一首
        swAutoNext.setChecked(prefs.getBoolean(KEY_AUTO_NEXT, true));

        // 播放模式
        int mode = prefs.getInt(KEY_PLAY_MODE, playlistManager.getPlayMode());
        switch (mode) {
            case PlaylistManager.PLAY_MODE_SEQUENCE: rbSequence.setChecked(true); break;
            case PlaylistManager.PLAY_MODE_REPEAT_ALL: rbRepeatAll.setChecked(true); break;
            case PlaylistManager.PLAY_MODE_REPEAT_ONE: rbRepeatOne.setChecked(true); break;
            case PlaylistManager.PLAY_MODE_SHUFFLE: rbShuffle.setChecked(true); break;
        }
    }

    // ============ 音源管理 ============

    private void refreshScriptList() {
        scriptAdapter.notifyDataSetChanged();
        // 更新标题
        TextView tvTitle = findViewById(R.id.tv_scripts_title);
        if (tvTitle != null) {
            int count = scriptManager.getAllScripts().size();
            tvTitle.setText("已导入音源 (" + count + ")");
        }
    }

    private void openFileChooser() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("application/javascript");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        // 兼容性：尝试 .js 扩展名
        try {
            startActivityForResult(Intent.createChooser(intent, "选择音源脚本文件"), REQUEST_CODE_IMPORT_FILE);
        } catch (Exception e) {
            // 兜底：使用 ACTION_OPEN_DOCUMENT
            Intent fallback = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            fallback.setType("*/*");
            fallback.addCategory(Intent.CATEGORY_OPENABLE);
            startActivityForResult(fallback, REQUEST_CODE_IMPORT_FILE);
        }
    }

    private void showImportUrlDialog() {
        final EditText etUrl = new EditText(this);
        etUrl.setHint("输入音源脚本URL (https://...)");
        etUrl.setSingleLine();
        LinearLayout container = new LinearLayout(this);
        container.setPadding(40, 20, 40, 20);
        container.addView(etUrl, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        new AlertDialog.Builder(this)
                .setTitle("从URL导入音源")
                .setView(container)
                .setPositiveButton("导入", (dialog, which) -> {
                    String url = etUrl.getText().toString().trim();
                    if (TextUtils.isEmpty(url)) {
                        showToast("请输入URL");
                        return;
                    }
                    importFromUrl(url);
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void importFromUrl(final String url) {
        showToast("正在导入...");
        // 在子线程执行网络请求
        new Thread(() -> {
            android.os.Bundle result = scriptManager.importFromUrl(url);
            runOnUiThread(() -> {
                if (result != null) {
                    showToast("导入成功: " + result.getString("name"));
                    refreshScriptList();
                    notifyScriptChanged();
                } else {
                    showToast("导入失败，请检查URL");
                }
            });
        }).start();
    }

    private void showDeleteScriptDialog(final String id, final String name) {
        new AlertDialog.Builder(this)
                .setTitle("删除音源")
                .setMessage("确定要删除音源 [" + name + "] 吗？")
                .setPositiveButton("删除", (dialog, which) -> {
                    if (scriptManager.removeScript(id)) {
                        showToast("已删除: " + name);
                        refreshScriptList();
                        notifyScriptChanged();
                    } else {
                        showToast("删除失败");
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void showSetActiveDialog(final String id, final String name) {
        new AlertDialog.Builder(this)
                .setTitle("切换音源")
                .setMessage("确定切换到音源 [" + name + "] 吗？将重新加载。")
                .setPositiveButton("切换", (dialog, which) -> {
                    scriptManager.setActiveScriptId(id);
                    showToast("已切换音源: " + name);
                    notifyScriptChanged();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // ============ 手动添加音源 & 订阅管理 ============

    /**
     * 刷新订阅列表显示
     */
    private void refreshSubscriptionList() {
        if (subscriptionAdapter == null) return;
        subscriptionAdapter.clear();
        subscriptionAdapter.addAll(scriptManager.getSubscriptions());
        subscriptionAdapter.notifyDataSetChanged();
        // 更新订阅标题
        TextView tvSubTitle = findViewById(R.id.tv_subscriptions_title);
        if (tvSubTitle != null) {
            tvSubTitle.setText("音源订阅 (" + subscriptionAdapter.getCount() + ")");
        }
    }

    /**
     * 显示"手动添加音源"对话框
     * 用户可以直接粘贴JS脚本内容，并指定音源名称
     */
    private void showAddManualScriptDialog() {
        // 音源名称输入框
        final EditText etName = new EditText(this);
        etName.setHint("音源名称（脚本不含@meta时使用）");
        etName.setSingleLine();

        // JS脚本内容输入框（多行）
        final EditText etContent = new EditText(this);
        etContent.setHint("粘贴JS脚本内容（含 @meta 块）");
        etContent.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        etContent.setMinLines(8);
        etContent.setGravity(android.view.Gravity.TOP | android.view.Gravity.START);

        // 垂直布局容器
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(40, 20, 40, 20);
        container.addView(etName, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        // 间距
        View spacer = new View(this);
        spacer.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 20));
        container.addView(spacer);
        // JS内容区域用ScrollView包裹，避免内容过长撑爆对话框
        ScrollView scrollView = new ScrollView(this);
        scrollView.addView(etContent);
        container.addView(scrollView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        new AlertDialog.Builder(this)
                .setTitle("手动添加音源")
                .setView(container)
                .setPositiveButton("添加", (dialog, which) -> {
                    String name = etName.getText().toString().trim();
                    String content = etContent.getText().toString();
                    if (TextUtils.isEmpty(content)) {
                        showToast("请输入JS脚本内容");
                        return;
                    }
                    addManualScript(name, content);
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /**
     * 调用scriptManager添加手动音源脚本
     * 注意：addManualScript本身是同步操作（不涉及网络），直接在主线程执行
     */
    private void addManualScript(final String name, final String content) {
        // addManualScript只做本地存储操作，但可能包含解析（CPU开销小），直接执行
        android.os.Bundle result = scriptManager.addManualScript(name, content);
        if (result != null) {
            showToast("已添加音源: " + result.getString("name"));
            refreshScriptList();
            notifyScriptChanged();
        } else {
            showToast("添加失败，请检查脚本格式");
        }
    }

    /**
     * 显示"添加订阅URL"对话框
     * 添加后自动刷新该订阅
     */
    private void showAddSubscriptionDialog() {
        final EditText etUrl = new EditText(this);
        etUrl.setHint("输入订阅URL (https://...)");
        etUrl.setSingleLine();
        LinearLayout container = new LinearLayout(this);
        container.setPadding(40, 20, 40, 20);
        container.addView(etUrl, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        new AlertDialog.Builder(this)
                .setTitle("添加音源订阅")
                .setView(container)
                .setPositiveButton("添加并拉取", (dialog, which) -> {
                    String url = etUrl.getText().toString().trim();
                    if (TextUtils.isEmpty(url)) {
                        showToast("请输入订阅URL");
                        return;
                    }
                    if (scriptManager.addSubscription(url)) {
                        showToast("已添加订阅");
                        refreshSubscriptionList();
                        // 自动刷新刚添加的订阅
                        fetchSingleSubscription(url);
                    } else {
                        showToast("订阅已存在");
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /**
     * 显示删除订阅确认对话框
     */
    private void showRemoveSubscriptionDialog(final String url) {
        new AlertDialog.Builder(this)
                .setTitle("删除订阅")
                .setMessage("确定要删除订阅？\n" + url)
                .setPositiveButton("删除", (dialog, which) -> {
                    if (scriptManager.removeSubscription(url)) {
                        showToast("已删除订阅");
                        refreshSubscriptionList();
                    } else {
                        showToast("删除失败");
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /**
     * 拉取单个订阅（通过scriptManager.fetchSubscription异步执行）
     * 回调通过Handler回到主线程更新UI
     */
    private void fetchSingleSubscription(final String url) {
        showToast("正在拉取订阅...");
        scriptManager.fetchSubscription(url, new UserApiScriptManager.SubscriptionFetchListener() {
            @Override
            public void onFetchStart(String u) {
                mainHandler.post(() -> showToast("开始拉取: " + u));
            }

            @Override
            public void onScriptImported(Bundle scriptInfo) {
                mainHandler.post(() -> {
                    if (scriptInfo != null) {
                        showToast("已导入音源: " + scriptInfo.getString("name"));
                        refreshScriptList();
                        notifyScriptChanged();
                    }
                });
            }

            @Override
            public void onFetchComplete(String u, int successCount, int failCount) {
                mainHandler.post(() -> {
                    showToast("拉取完成: 成功" + successCount + "个, 失败" + failCount + "个");
                    refreshScriptList();
                });
            }

            @Override
            public void onFetchError(String u, String errorMessage) {
                mainHandler.post(() -> showToast("拉取失败: " + errorMessage));
            }
        });
    }

    /**
     * 刷新所有订阅
     */
    private void refreshAllSubscriptions() {
        List<String> subs = scriptManager.getSubscriptions();
        if (subs.isEmpty()) {
            showToast("暂无订阅");
            return;
        }
        showToast("正在刷新所有订阅...");
        scriptManager.refreshAllSubscriptions(
                new UserApiScriptManager.SubscriptionFetchListener() {
                    @Override
                    public void onFetchStart(String u) {
                        mainHandler.post(() -> showToast("开始拉取: " + u));
                    }

                    @Override
                    public void onScriptImported(Bundle scriptInfo) {
                        mainHandler.post(() -> {
                            if (scriptInfo != null) {
                                showToast("已导入音源: " + scriptInfo.getString("name"));
                                refreshScriptList();
                                notifyScriptChanged();
                            }
                        });
                    }

                    @Override
                    public void onFetchComplete(String u, int successCount, int failCount) {
                        mainHandler.post(() -> {
                            showToast("[" + u + "] 完成: 成功" + successCount + " 失败" + failCount);
                            refreshScriptList();
                        });
                    }

                    @Override
                    public void onFetchError(String u, String errorMessage) {
                        mainHandler.post(() -> showToast("[" + u + "] 失败: " + errorMessage));
                    }
                });
    }

    private void notifyScriptChanged() {
        Intent data = new Intent();
        data.putExtra("script_changed", true);
        setResult(RESULT_OK, data);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_CODE_IMPORT_FILE && resultCode == RESULT_OK && data != null) {
            Uri uri = data.getData();
            if (uri != null) {
                android.os.Bundle result = scriptManager.importFromFile(uri);
                if (result != null) {
                    showToast("导入成功: " + result.getString("name"));
                    refreshScriptList();
                    notifyScriptChanged();
                } else {
                    showToast("导入失败，请检查文件格式");
                }
            }
        }
    }

    // ============ 按键处理 ============

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            finish();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    private void showToast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    // ============ 音源列表适配器 ============

    private class ScriptListAdapter extends BaseAdapter {

        @Override
        public int getCount() {
            return scriptManager.getAllScripts().size();
        }

        @Override
        public android.os.Bundle getItem(int position) {
            return scriptManager.getAllScripts().get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            if (convertView == null) {
                convertView = LayoutInflater.from(SettingsActivity.this)
                        .inflate(R.layout.item_music, parent, false);
            }
            android.os.Bundle script = getItem(position);
            TextView tvName = convertView.findViewById(R.id.tv_song_name);
            TextView tvSinger = convertView.findViewById(R.id.tv_singer);
            TextView tvSource = convertView.findViewById(R.id.tv_source);
            TextView tvAlbum = convertView.findViewById(R.id.tv_album);
            TextView tvInterval = convertView.findViewById(R.id.tv_interval);

            String name = script.getString("name", "未命名");
            String version = script.getString("version", "");
            String author = script.getString("author", "");
            String description = script.getString("description", "");
            String id = script.getString("id", "");

            tvName.setText(name + (TextUtils.isEmpty(version) ? "" : " v" + version));
            tvSinger.setText("作者: " + (TextUtils.isEmpty(author) ? "未知" : author));
            tvAlbum.setText(TextUtils.isEmpty(description) ? "" : description);
            tvAlbum.setVisibility(TextUtils.isEmpty(description) ? View.GONE : View.VISIBLE);
            tvSource.setText("音源");
            tvInterval.setVisibility(View.GONE);

            // 当前激活的音源显示标记
            String activeId = scriptManager.getActiveScriptId();
            if (id.equals(activeId)) {
                tvSource.setBackgroundColor(0xFFFFC107);
                tvSource.setText("当前");
            } else {
                tvSource.setBackgroundColor(0xFF607D8B);
                tvSource.setText("音源");
            }

            // 设置遥控器焦点
            convertView.setFocusable(true);
            convertView.setFocusableInTouchMode(true);
            convertView.setBackgroundResource(R.drawable.focus_background);

            convertView.setOnClickListener(v -> showSetActiveDialog(id, name));
            convertView.setOnLongClickListener(v -> {
                showDeleteScriptDialog(id, name);
                return true;
            });

            return convertView;
        }
    }
}
