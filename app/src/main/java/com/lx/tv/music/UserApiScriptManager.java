package com.lx.tv.music;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

    /** 匹配落雪音源脚本头部的元数据块 */
    private static final Pattern META_PATTERN = Pattern.compile(
            "/\\*\\s*@meta[\\s\\S]*?\\*/", Pattern.MULTILINE);

    private final Context context;
    private final SharedPreferences prefs;

    public UserApiScriptManager(Context context) {
        this.context = context.getApplicationContext();
        this.prefs = this.context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
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
}
