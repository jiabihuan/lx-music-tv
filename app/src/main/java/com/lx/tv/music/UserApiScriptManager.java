package com.lx.tv.music;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * 音源脚本管理器
 * 管理用户自定义JS音源脚本的导入、存储、列表、删除
 * 脚本以JSON格式存储在SharedPreferences中（按id索引）
 *
 * 脚本元数据格式（落雪音源协议）：
 * {
 *   "id": "唯一ID",
 *   "name": "音源名称",
 *   "description": "音源描述",
 *   "version": "版本号",
 *   "author": "作者",
 *   "homepage": "主页URL",
 *   "script": "JS脚本内容"
 * }
 */
public class UserApiScriptManager {
    private static final String TAG = "UserApiScriptManager";

    private static final String PREFS_NAME = "lx_tv_user_api_scripts";
    private static final String KEY_SCRIPTS = "scripts";
    private static final String KEY_ACTIVE_ID = "active_script_id";

    /** 订阅URL列表存储键（订阅URL列表，存储为JSON数组） */
    private static final String KEY_SUBSCRIPTIONS = "subscriptions";

    /** 匹配落雪音源脚本头部的元数据块 */
    private static final Pattern META_PATTERN = Pattern.compile(
            "/\\*\\s*@meta[\\s\\S]*?\\*/", Pattern.MULTILINE);

    /** 浏览器UA，避免被服务器拒绝 */
    private static final String USER_AGENT =
            "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 (KHTML, like Gecko) " +
                    "Chrome/120.0.0.0 Mobile Safari/537.36";

    private final Context context;
    private final SharedPreferences prefs;

    /** 用于订阅请求的OkHttpClient（懒加载） */
    private OkHttpClient subscriptionHttpClient;
    /** 用于回调主线程的Handler */
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public UserApiScriptManager(Context context) {
        this.context = context.getApplicationContext();
        this.prefs = this.context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    /**
     * 获取（懒加载）订阅请求专用的OkHttpClient
     */
    private synchronized OkHttpClient getSubscriptionHttpClient() {
        if (subscriptionHttpClient == null) {
            subscriptionHttpClient = new OkHttpClient.Builder()
                    .connectTimeout(15, TimeUnit.SECONDS)
                    .readTimeout(20, TimeUnit.SECONDS)
                    .writeTimeout(20, TimeUnit.SECONDS)
                    .build();
        }
        return subscriptionHttpClient;
    }

    /**
     * 获取所有已存储的音源脚本（不含script字段大内容，仅元数据）
     * 注意：返回结果包含script字段的简短摘要，避免内存占用
     */
    public List<Bundle> getAllScripts() {
        List<Bundle> list = new ArrayList<>();
        String json = prefs.getString(KEY_SCRIPTS, "");
        if (json.isEmpty()) {
            return list;
        }
        try {
            JSONArray array = new JSONArray(json);
            for (int i = 0; i < array.length(); i++) {
                JSONObject obj = array.getJSONObject(i);
                list.add(jsonToBundle(obj));
            }
        } catch (JSONException e) {
            Log.e(TAG, "Failed to parse scripts JSON: " + e.getMessage());
        }
        return list;
    }

    /**
     * 获取指定ID的音源脚本（含完整script内容）
     */
    public Bundle getScript(String id) {
        if (TextUtils.isEmpty(id)) return null;
        String json = prefs.getString(KEY_SCRIPTS, "");
        if (json.isEmpty()) return null;
        try {
            JSONArray array = new JSONArray(json);
            for (int i = 0; i < array.length(); i++) {
                JSONObject obj = array.getJSONObject(i);
                if (id.equals(obj.optString("id"))) {
                    return jsonToBundle(obj);
                }
            }
        } catch (JSONException e) {
            Log.e(TAG, "Failed to get script: " + e.getMessage());
        }
        return null;
    }

    /**
     * 添加音源脚本
     * @param scriptInfo 包含id,name,description,version,author,homepage,script字段的Bundle
     * @return 添加成功返回true，重复返回false
     */
    public boolean addScript(Bundle scriptInfo) {
        if (scriptInfo == null) return false;
        String id = scriptInfo.getString("id");
        if (TextUtils.isEmpty(id)) {
            id = "script_" + UUID.randomUUID().toString().substring(0, 8);
            scriptInfo.putString("id", id);
        }
        List<Bundle> scripts = getAllScripts();
        for (Bundle s : scripts) {
            if (id.equals(s.getString("id"))) {
                Log.w(TAG, "Script already exists: " + id);
                return false;
            }
        }
        scripts.add(scriptInfo);
        saveScripts(scripts);
        Log.i(TAG, "Added script: " + scriptInfo.getString("name") + " (id=" + id + ")");
        return true;
    }

    /**
     * 更新音源脚本
     */
    public boolean updateScript(Bundle scriptInfo) {
        if (scriptInfo == null) return false;
        String id = scriptInfo.getString("id");
        if (TextUtils.isEmpty(id)) return false;
        List<Bundle> scripts = getAllScripts();
        for (int i = 0; i < scripts.size(); i++) {
            if (id.equals(scripts.get(i).getString("id"))) {
                scripts.set(i, scriptInfo);
                saveScripts(scripts);
                Log.i(TAG, "Updated script: " + id);
                return true;
            }
        }
        return false;
    }

    /**
     * 删除音源脚本
     */
    public boolean removeScript(String id) {
        if (TextUtils.isEmpty(id)) return false;
        List<Bundle> scripts = getAllScripts();
        boolean removed = false;
        for (int i = scripts.size() - 1; i >= 0; i--) {
            if (id.equals(scripts.get(i).getString("id"))) {
                scripts.remove(i);
                removed = true;
            }
        }
        if (removed) {
            saveScripts(scripts);
            // 如果删除的是当前激活的脚本，清除激活状态
            if (id.equals(getActiveScriptId())) {
                setActiveScriptId(scripts.isEmpty() ? null : scripts.get(0).getString("id"));
            }
            Log.i(TAG, "Removed script: " + id);
        }
        return removed;
    }

    /**
     * 从文件URI导入音源脚本
     * 文件应为.js文件，包含落雪音源协议的元数据块
     */
    public Bundle importFromFile(Uri uri) {
        if (uri == null) return null;
        try {
            InputStream is = context.getContentResolver().openInputStream(uri);
            if (is == null) {
                Log.e(TAG, "Cannot open input stream for: " + uri);
                return null;
            }
            String scriptContent = readStream(is);
            return importFromContent(scriptContent);
        } catch (Exception e) {
            Log.e(TAG, "importFromFile failed: " + e.getMessage());
            return null;
        }
    }

    /**
     * 从URL在线导入音源脚本
     * 注意：必须在子线程调用
     */
    public Bundle importFromUrl(String urlStr) {
        if (TextUtils.isEmpty(urlStr)) return null;
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(15000);
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", "LxMusicTV/1.0");
            int code = conn.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) {
                Log.e(TAG, "importFromUrl HTTP code: " + code);
                return null;
            }
            String scriptContent = readStream(conn.getInputStream());
            return importFromContent(scriptContent);
        } catch (Exception e) {
            Log.e(TAG, "importFromUrl failed: " + e.getMessage());
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /**
     * 从JS脚本字符串解析元数据并导入
     */
    public Bundle importFromContent(String scriptContent) {
        if (TextUtils.isEmpty(scriptContent)) return null;
        Bundle info = parseScriptMeta(scriptContent);
        if (info == null) {
            Log.e(TAG, "Invalid script: meta block not found");
            return null;
        }
        info.putString("script", scriptContent);
        if (addScript(info)) {
            return info;
        }
        // 已存在则更新
        if (updateScript(info)) {
            return info;
        }
        return null;
    }

    /**
     * 解析JS脚本头部的元数据块
     * 落雪音源协议元数据格式：
     * /**
     *  * @name 音源名称
     *  * @description 描述
     *  * @version 1.0.0
     *  * @author 作者
     *  * @homepage https://...
     *  *\/
     */
    public Bundle parseScriptMeta(String scriptContent) {
        if (TextUtils.isEmpty(scriptContent)) return null;
        Bundle info = new Bundle();
        Matcher metaMatcher = META_PATTERN.matcher(scriptContent);
        String metaBlock = null;
        if (metaMatcher.find()) {
            metaBlock = metaMatcher.group();
        }
        if (metaBlock == null) {
            // 兜底：尝试整体作为脚本（不带元数据）
            info.putString("id", "script_" + UUID.randomUUID().toString().substring(0, 8));
            info.putString("name", "未命名音源");
            info.putString("description", "");
            info.putString("version", "1.0.0");
            info.putString("author", "");
            info.putString("homepage", "");
            return info;
        }

        String name = extractMetaField(metaBlock, "name");
        String description = extractMetaField(metaBlock, "description");
        String version = extractMetaField(metaBlock, "version");
        String author = extractMetaField(metaBlock, "author");
        String homepage = extractMetaField(metaBlock, "homepage");

        if (TextUtils.isEmpty(name)) name = "未命名音源";
        if (TextUtils.isEmpty(version)) version = "1.0.0";

        info.putString("id", "script_" + UUID.randomUUID().toString().substring(0, 8));
        info.putString("name", name);
        info.putString("description", description != null ? description : "");
        info.putString("version", version);
        info.putString("author", author != null ? author : "");
        info.putString("homepage", homepage != null ? homepage : "");
        return info;
    }

    private String extractMetaField(String metaBlock, String fieldName) {
        Pattern p = Pattern.compile("@\\s*" + fieldName + "\\s*([^\\n\\r]*)");
        Matcher m = p.matcher(metaBlock);
        if (m.find()) {
            return m.group(1).trim();
        }
        return null;
    }

    /**
     * 获取当前激活的音源脚本ID
     */
    public String getActiveScriptId() {
        return prefs.getString(KEY_ACTIVE_ID, null);
    }

    /**
     * 设置当前激活的音源脚本ID
     */
    public void setActiveScriptId(String id) {
        prefs.edit().putString(KEY_ACTIVE_ID, id).apply();
    }

    /**
     * 获取当前激活的音源脚本完整内容
     */
    public Bundle getActiveScript() {
        String id = getActiveScriptId();
        if (TextUtils.isEmpty(id)) {
            List<Bundle> scripts = getAllScripts();
            if (!scripts.isEmpty()) {
                Bundle first = scripts.get(0);
                setActiveScriptId(first.getString("id"));
                return getScript(first.getString("id"));
            }
            return null;
        }
        return getScript(id);
    }

    private void saveScripts(List<Bundle> scripts) {
        JSONArray array = new JSONArray();
        for (Bundle b : scripts) {
            array.put(bundleToJson(b));
        }
        prefs.edit().putString(KEY_SCRIPTS, array.toString()).apply();
    }

    private JSONObject bundleToJson(Bundle bundle) {
        JSONObject json = new JSONObject();
        try {
            json.put("id", bundle.getString("id", ""));
            json.put("name", bundle.getString("name", ""));
            json.put("description", bundle.getString("description", ""));
            json.put("version", bundle.getString("version", ""));
            json.put("author", bundle.getString("author", ""));
            json.put("homepage", bundle.getString("homepage", ""));
            json.put("script", bundle.getString("script", ""));
        } catch (JSONException e) {
            Log.e(TAG, "bundleToJson failed: " + e.getMessage());
        }
        return json;
    }

    private Bundle jsonToBundle(JSONObject json) {
        Bundle bundle = new Bundle();
        try {
            bundle.putString("id", json.optString("id", ""));
            bundle.putString("name", json.optString("name", ""));
            bundle.putString("description", json.optString("description", ""));
            bundle.putString("version", json.optString("version", ""));
            bundle.putString("author", json.optString("author", ""));
            bundle.putString("homepage", json.optString("homepage", ""));
            bundle.putString("script", json.optString("script", ""));
        } catch (Exception e) {
            Log.e(TAG, "jsonToBundle failed: " + e.getMessage());
        }
        return bundle;
    }

    private String readStream(InputStream is) throws Exception {
        BufferedReader reader = new BufferedReader(new InputStreamReader(is, "UTF-8"));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            sb.append(line).append('\n');
        }
        reader.close();
        return sb.toString();
    }

    // ========================================================================
    // ============ 以下为音源订阅管理功能（追加，不修改原有代码） ============
    // ========================================================================

    /**
     * 订阅拉取回调监听器
     */
    public interface SubscriptionFetchListener {
        /** 单个订阅URL开始拉取 */
        void onFetchStart(String url);
        /** 单个JS脚本导入成功（每导入一个脚本回调一次） */
        void onScriptImported(Bundle scriptInfo);
        /** 单个订阅URL拉取完成 */
        void onFetchComplete(String url, int successCount, int failCount);
        /** 单个订阅URL拉取出错 */
        void onFetchError(String url, String errorMessage);
    }

    /**
     * 获取所有订阅URL列表
     * @return List<String> 订阅URL列表（不会返回null，可能为空列表）
     */
    public List<String> getSubscriptions() {
        List<String> list = new ArrayList<>();
        String json = prefs.getString(KEY_SUBSCRIPTIONS, "");
        if (json.isEmpty()) {
            return list;
        }
        try {
            JSONArray array = new JSONArray(json);
            for (int i = 0; i < array.length(); i++) {
                String url = array.optString(i, null);
                if (!TextUtils.isEmpty(url)) {
                    list.add(url);
                }
            }
        } catch (JSONException e) {
            Log.e(TAG, "Failed to parse subscriptions JSON: " + e.getMessage());
        }
        return list;
    }

    /**
     * 添加订阅URL
     * @param url 订阅URL
     * @return 添加成功返回true，已存在或为空返回false
     */
    public boolean addSubscription(String url) {
        if (TextUtils.isEmpty(url)) return false;
        url = url.trim();
        List<String> subs = getSubscriptions();
        if (subs.contains(url)) {
            Log.w(TAG, "Subscription already exists: " + url);
            return false;
        }
        subs.add(url);
        saveSubscriptions(subs);
        Log.i(TAG, "Added subscription: " + url);
        return true;
    }

    /**
     * 移除订阅URL
     * @param url 订阅URL
     * @return 移除成功返回true，不存在返回false
     */
    public boolean removeSubscription(String url) {
        if (TextUtils.isEmpty(url)) return false;
        List<String> subs = getSubscriptions();
        boolean removed = subs.remove(url);
        if (removed) {
            saveSubscriptions(subs);
            Log.i(TAG, "Removed subscription: " + url);
        }
        return removed;
    }

    /**
     * 保存订阅URL列表到SharedPreferences
     */
    private void saveSubscriptions(List<String> subs) {
        JSONArray array = new JSONArray();
        for (String s : subs) {
            array.put(s);
        }
        prefs.edit().putString(KEY_SUBSCRIPTIONS, array.toString()).apply();
    }

    /**
     * 从订阅URL拉取音源脚本
     * 订阅URL返回的格式应为：JSON数组，每个元素是一个音源脚本的URL
     * 例如：["https://example.com/source1.js", "https://example.com/source2.js"]
     * 或直接返回单个JS脚本（Content-Type: application/javascript）
     *
     * 网络请求在OkHttp的工作线程执行，回调通过Handler回到主线程。
     *
     * @param url 订阅URL
     * @param listener 回调监听器（可为null）
     */
    public void fetchSubscription(final String url, final SubscriptionFetchListener listener) {
        if (TextUtils.isEmpty(url)) {
            if (listener != null) {
                mainHandler.post(() -> listener.onFetchError(url, "订阅URL为空"));
            }
            return;
        }

        // 回调主线程：开始拉取
        if (listener != null) {
            mainHandler.post(() -> listener.onFetchStart(url));
        }

        // 使用OkHttp异步请求订阅URL
        Request request = new Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json, application/javascript, text/javascript, */*")
                .get()
                .build();

        getSubscriptionHttpClient().newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                String errMsg = e.getMessage();
                Log.e(TAG, "fetchSubscription failed: " + url + " - " + errMsg);
                if (listener != null) {
                    mainHandler.post(() -> listener.onFetchError(url,
                            "网络请求失败: " + (errMsg != null ? errMsg : "unknown")));
                }
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                // 读取响应体（finally中关闭）
                final String body;
                final String contentType;
                try {
                    if (!response.isSuccessful()) {
                        final String msg = "HTTP " + response.code() + " " + response.message();
                        Log.e(TAG, "fetchSubscription HTTP error: " + url + " - " + msg);
                        if (listener != null) {
                            mainHandler.post(() -> listener.onFetchError(url, msg));
                        }
                        return;
                    }
                    ResponseBody responseBody = response.body();
                    if (responseBody == null) {
                        if (listener != null) {
                            mainHandler.post(() -> listener.onFetchError(url, "响应体为空"));
                        }
                        return;
                    }
                    body = responseBody.string();
                    contentType = response.header("Content-Type", "");
                } finally {
                    response.close();
                }

                // 在工作线程中解析响应内容，判断是JSON数组还是JS脚本
                final int[] counters = {0, 0}; // {success, fail}
                try {
                    if (isJsonArray(body)) {
                        // 格式1：JSON数组，包含多个JS脚本URL
                        JSONArray arr = new JSONArray(body);
                        Log.i(TAG, "Subscription returned JSON array, size=" + arr.length());
                        for (int i = 0; i < arr.length(); i++) {
                            String scriptUrl = arr.optString(i, null);
                            if (TextUtils.isEmpty(scriptUrl)) continue;
                            Bundle imported = importFromUrlSync(scriptUrl);
                            if (imported != null) {
                                counters[0]++;
                                if (listener != null) {
                                    final Bundle info = imported;
                                    mainHandler.post(() -> listener.onScriptImported(info));
                                }
                            } else {
                                counters[1]++;
                                Log.w(TAG, "Import script failed from: " + scriptUrl);
                            }
                        }
                    } else if (looksLikeJavaScript(body, contentType)) {
                        // 格式2：直接是JS脚本内容
                        Bundle imported = importFromContent(body);
                        if (imported != null) {
                            counters[0]++;
                            if (listener != null) {
                                final Bundle info = imported;
                                mainHandler.post(() -> listener.onScriptImported(info));
                            }
                        } else {
                            counters[1]++;
                            Log.w(TAG, "Import script content failed for subscription: " + url);
                        }
                    } else {
                        // 未知格式：尝试当作JS脚本兜底处理
                        Log.w(TAG, "Unknown subscription content type, try as JS: " + url);
                        Bundle imported = importFromContent(body);
                        if (imported != null) {
                            counters[0]++;
                            if (listener != null) {
                                final Bundle info = imported;
                                mainHandler.post(() -> listener.onScriptImported(info));
                            }
                        } else {
                            counters[1]++;
                            if (listener != null) {
                                mainHandler.post(() -> listener.onFetchError(url,
                                        "无法识别的订阅返回格式（既不是JSON数组也不是JS脚本）"));
                            }
                        }
                    }
                } catch (JSONException e) {
                    Log.e(TAG, "Parse subscription body failed: " + e.getMessage());
                    if (listener != null) {
                        mainHandler.post(() -> listener.onFetchError(url,
                                "解析订阅内容失败: " + e.getMessage()));
                    }
                    return;
                }

                // 回调主线程：拉取完成
                if (listener != null) {
                    final int success = counters[0];
                    final int fail = counters[1];
                    mainHandler.post(() -> listener.onFetchComplete(url, success, fail));
                }
            }
        });
    }

    /**
     * 刷新所有订阅（拉取并更新所有订阅的音源脚本）
     * 依次拉取每个订阅URL（避免并发请求过多被限流）。
     *
     * @param listener 回调监听器（可为null）
     */
    public void refreshAllSubscriptions(final SubscriptionFetchListener listener) {
        List<String> subs = getSubscriptions();
        if (subs.isEmpty()) {
            Log.i(TAG, "No subscriptions to refresh");
            return;
        }
        Log.i(TAG, "Refreshing " + subs.size() + " subscriptions...");
        // 串行刷新所有订阅
        refreshSubscriptionsSequentially(subs, 0, listener);
    }

    /**
     * 递归串行刷新订阅列表（避免并发请求过多）
     */
    private void refreshSubscriptionsSequentially(final List<String> subs, final int index,
                                                  final SubscriptionFetchListener listener) {
        if (index >= subs.size()) {
            Log.i(TAG, "All subscriptions refreshed.");
            return;
        }
        final String url = subs.get(index);
        // 包一层监听器，等当前订阅完成后再处理下一个
        SubscriptionFetchListener wrapper = new SubscriptionFetchListener() {
            @Override
            public void onFetchStart(String u) {
                if (listener != null) listener.onFetchStart(u);
            }
            @Override
            public void onScriptImported(Bundle scriptInfo) {
                if (listener != null) listener.onScriptImported(scriptInfo);
            }
            @Override
            public void onFetchComplete(String u, int successCount, int failCount) {
                if (listener != null) listener.onFetchComplete(u, successCount, failCount);
                // 继续下一个订阅
                refreshSubscriptionsSequentially(subs, index + 1, listener);
            }
            @Override
            public void onFetchError(String u, String errorMessage) {
                if (listener != null) listener.onFetchError(u, errorMessage);
                // 即使出错也继续下一个订阅
                refreshSubscriptionsSequentially(subs, index + 1, listener);
            }
        };
        fetchSubscription(url, wrapper);
    }

    /**
     * 手动添加音源脚本（直接粘贴JS内容）
     * 如果脚本包含 @meta 块，解析元数据；如果不包含，使用用户提供的name作为音源名称。
     * 内部调用 {@link #importFromContent(String)} 进行导入。
     *
     * @param name 音源名称（当脚本不包含@meta时使用）
     * @param scriptContent JS脚本内容
     * @return 添加成功返回Bundle（含id,name等元数据），失败返回null
     */
    public Bundle addManualScript(String name, String scriptContent) {
        if (TextUtils.isEmpty(scriptContent)) {
            Log.w(TAG, "addManualScript: script content is empty");
            return null;
        }
        // 先尝试按含@meta解析
        Bundle info = parseScriptMeta(scriptContent);
        if (info == null) {
            // 兜底：构造默认元数据
            info = new Bundle();
            info.putString("id", "script_" + UUID.randomUUID().toString().substring(0, 8));
            info.putString("name", TextUtils.isEmpty(name) ? "未命名音源" : name);
            info.putString("description", "");
            info.putString("version", "1.0.0");
            info.putString("author", "手动添加");
            info.putString("homepage", "");
        } else {
            // 若脚本不包含@meta（parseScriptMeta会返回兜底元数据，name为"未命名音源"），
            // 则使用用户提供的name覆盖
            String parsedName = info.getString("name", "");
            if (TextUtils.isEmpty(parsedName) || "未命名音源".equals(parsedName)) {
                if (!TextUtils.isEmpty(name)) {
                    info.putString("name", name);
                }
            }
            // 若author为空，标记为手动添加
            String author = info.getString("author", "");
            if (TextUtils.isEmpty(author)) {
                info.putString("author", "手动添加");
            }
        }
        info.putString("script", scriptContent);

        // 调用addScript（重复时尝试更新）
        if (addScript(info)) {
            Log.i(TAG, "Manual script added: " + info.getString("name"));
            return info;
        }
        if (updateScript(info)) {
            Log.i(TAG, "Manual script updated: " + info.getString("name"));
            return info;
        }
        return null;
    }

    /**
     * 同步从URL导入音源脚本（用于订阅中拉取子脚本URL，需在子线程调用）
     * 复用 {@link #importFromUrl(String)} 实现。
     */
    private Bundle importFromUrlSync(String urlStr) {
        return importFromUrl(urlStr);
    }

    /**
     * 判断字符串是否像JSON数组（以 [ 开头）
     */
    private boolean isJsonArray(String body) {
        if (body == null) return false;
        String trimmed = body.trim();
        return trimmed.startsWith("[");
    }

    /**
     * 判断响应内容是否是JavaScript脚本
     * 综合判断依据：Content-Type 或 内容特征
     */
    private boolean looksLikeJavaScript(String body, String contentType) {
        if (contentType != null) {
            String ct = contentType.toLowerCase();
            if (ct.contains("javascript") || ct.contains("ecmascript")) {
                return true;
            }
        }
        if (body == null) return false;
        String trimmed = body.trim();
        if (trimmed.isEmpty()) return false;
        // JS脚本通常包含这些关键字
        return trimmed.contains("function") || trimmed.contains("=>")
                || trimmed.contains("var ") || trimmed.contains("let ")
                || trimmed.contains("const ") || trimmed.contains("module.exports")
                || trimmed.contains("require(") || trimmed.contains("@meta");
    }
}
