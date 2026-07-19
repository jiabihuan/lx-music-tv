package com.lx.tv.music.userapi;

import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.util.Log;

import org.json.JSONObject;
import org.json.JSONTokener;

/**
 * JS 消息处理器：接收来自 QuickJS 线程的消息（init / response / request / cancelRequest /
 * showUpdateAlert / log 等），解析后通过 {@link UserApiCallback} 回调通知 UI 层。
 *
 * 移植自落雪音乐 lx-music-mobile 的 JsHandler，去掉 React Native 的 Arguments/WritableMap
 * 与 UtilsEvent（DeviceEventManagerModule），改为调用 UserApiCallback。
 */
public class JsHandler extends Handler {
  private final UserApiEngine engine;

  JsHandler(Looper looper, UserApiEngine engine) {
    super(looper);
    this.engine = engine;
  }

  private UserApiCallback getCallback() {
    return engine.callback;
  }

  private void sendInitFailedEvent(String errorMessage) {
    UserApiCallback callback = getCallback();
    if (callback == null) return;
    callback.onInit(false, errorMessage, null);
    callback.onLog("error", errorMessage);
  }

  private void sendLogEvent(Object[] data) {
    UserApiCallback callback = getCallback();
    if (callback == null) return;
    callback.onLog((String) data[0], (String) data[1]);
  }

  private void sendActionEvent(String action, String data) {
    UserApiCallback callback = getCallback();
    if (callback == null) return;
    try {
      switch (action) {
        case "init": {
          JSONObject obj = new JSONObject(data);
          boolean status = optBool(obj, "status", false);
          String errorMessage = optString(obj, "errorMessage");
          Object sources = optObject(obj, "info");
          callback.onInit(status, errorMessage, sources);
          break;
        }
        case "response": {
          JSONObject obj = new JSONObject(data);
          String requestKey = optString(obj, "requestKey");
          boolean status = optBool(obj, "status", false);
          String errorMessage = optString(obj, "errorMessage");
          Object result = optObject(obj, "result");
          callback.onResponse(requestKey, status, errorMessage, result);
          break;
        }
        case "request": {
          JSONObject obj = new JSONObject(data);
          String requestKey = optString(obj, "requestKey");
          String url = optString(obj, "url");
          Object options = optObject(obj, "options");
          callback.onRequest(requestKey, url, options);
          break;
        }
        case "cancelRequest": {
          // preload.js 中 nativeCall(NATIVE_EVENTS_NAMES.cancelRequest, requestKey)
          // 经 nativeCall 内 JSON.stringify(data) 后，data 是一个 JSON 字符串字面量（例如 "0.abc123"）
          String requestKey;
          try {
            Object parsed = new JSONTokener(data).nextValue();
            if (parsed instanceof String) {
              requestKey = (String) parsed;
            } else {
              requestKey = data;
            }
          } catch (Exception e) {
            requestKey = data;
          }
          callback.onCancelRequest(requestKey);
          break;
        }
        case "showUpdateAlert": {
          JSONObject obj = new JSONObject(data);
          String name = optString(obj, "name");
          String log = optString(obj, "log");
          String updateUrl = optString(obj, "updateUrl");
          callback.onShowUpdateAlert(name, log, updateUrl);
          break;
        }
        default:
          Log.w("UserApi [api call]", "Unknown action: " + action);
          break;
      }
    } catch (Exception e) {
      Log.e("UserApi [api call]", "Failed to handle action " + action + ": " + e.getMessage());
    }
  }

  @Override
  public void handleMessage(Message msg) {
    switch (msg.what) {
      case HandlerWhat.INIT_SUCCESS: break;
      case HandlerWhat.INIT_FAILED:
        sendInitFailedEvent((String) msg.obj);
        break;
      case HandlerWhat.ACTION:
        Object[] action = (Object[]) msg.obj;
        sendActionEvent((String) action[0], (String) action[1]);
        break;
      case HandlerWhat.LOG:
        sendLogEvent((Object[]) msg.obj);
        break;
      default:
        Log.w("UserApi [api call]", "Unknown message what: " + msg.what);
        break;
    }
  }

  private static String optString(JSONObject obj, String key) {
    if (obj == null) return null;
    try {
      if (obj.has(key) && !obj.isNull(key)) return obj.getString(key);
    } catch (Exception ignored) {}
    return null;
  }

  private static boolean optBool(JSONObject obj, String key, boolean def) {
    if (obj == null) return def;
    try {
      if (obj.has(key) && !obj.isNull(key)) return obj.getBoolean(key);
    } catch (Exception ignored) {}
    return def;
  }

  private static JSONObject optObject(JSONObject obj, String key) {
    if (obj == null) return null;
    try {
      if (obj.has(key) && !obj.isNull(key)) return obj.getJSONObject(key);
    } catch (Exception ignored) {}
    return null;
  }
}
