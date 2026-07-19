package com.lx.tv.music.network;

import android.util.Base64;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.Iterator;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.FormBody;
import okhttp3.Headers;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * 原生 HTTP 请求模块，提供给 JS 音源的 lx.request 使用。
 *
 * 流程：JS 调用 lx.request -> UserApiCallback.onRequest -> UI 层调用
 * {@link #fetchRequest(String, JSONObject, FetchCallback)} -> 回调结果 ->
 * UserApiEngine.sendAction("response", ...) 回传给 JS。
 *
 * options 结构（与 preload.js 的 lx.request 对应）：
 *   - method:    GET/POST/PUT/DELETE/PATCH 等，默认 GET
 *   - body:      原始请求体（字符串）
 *   - form:      表单（JSONObject，url-encoded）
 *   - formData:  多部分表单（JSONObject，multipart/form-data）
 *   - headers:   请求头（JSONObject）
 *   - timeout:   超时（毫秒），上限 60s
 *   - binary:    是否以二进制方式返回 body（true 时 body 为 base64）
 *
 * 返回结果结构：{ statusCode, statusMessage, headers, body }
 */
public class HttpFetcher {
  private static final String TAG = "HttpFetcher";

  private final OkHttpClient client;

  /** 异步请求回调。success 为 true 时 result 可用，为 false 时 errorMessage 可用。 */
  public interface FetchCallback {
    void onResult(boolean success, String errorMessage, JSONObject result);
  }

  public HttpFetcher() {
    this.client = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build();
  }

  public HttpFetcher(OkHttpClient client) {
    this.client = client;
  }

  /** 异步发起请求。 */
  public void fetchRequest(String url, JSONObject options, FetchCallback callback) {
    final boolean binary = optBool(options, "binary", false);
    try {
      OkHttpClient requestClient = applyTimeout(options);
      Request request = buildRequest(url, options);
      requestClient.newCall(request).enqueue(new Callback() {
        @Override
        public void onFailure(Call call, IOException e) {
          Log.e(TAG, "Request failed: " + e.getMessage());
          if (callback != null) {
            callback.onResult(false, e.getMessage(), null);
          }
        }

        @Override
        public void onResponse(Call call, Response response) throws IOException {
          try {
            JSONObject result = buildResponse(response, binary);
            if (callback != null) {
              callback.onResult(true, null, result);
            }
          } finally {
            response.close();
          }
        }
      });
    } catch (Exception e) {
      Log.e(TAG, "Build request failed: " + e.getMessage());
      if (callback != null) {
        callback.onResult(false, e.getMessage(), null);
      }
    }
  }

  /** 同步发起请求，返回结果 JSON。 */
  public JSONObject fetchRequestSync(String url, JSONObject options) throws IOException {
    boolean binary = optBool(options, "binary", false);
    OkHttpClient requestClient = applyTimeout(options);
    Request request = buildRequest(url, options);
    try (Response response = requestClient.newCall(request).execute()) {
      return buildResponse(response, binary);
    }
  }

  private OkHttpClient applyTimeout(JSONObject options) {
    int timeout = optInt(options, "timeout", 0);
    if (timeout <= 0) return client;
    // 上限 60s（与 preload.js 的 Math.min(timeout, 60_000) 一致）
    long ms = Math.min(timeout, 60_000);
    return client.newBuilder()
            .callTimeout(ms, TimeUnit.MILLISECONDS)
            .build();
  }

  private Request buildRequest(String url, JSONObject options) {
    if (options == null) options = new JSONObject();
    String method = optString(options, "method", "GET").toUpperCase();
    JSONObject headers = optObject(options, "headers");

    Request.Builder builder = new Request.Builder().url(url);

    if (headers != null) {
      Iterator<String> keys = headers.keys();
      while (keys.hasNext()) {
        String key = keys.next();
        String value = optString(headers, key);
        if (value != null) builder.addHeader(key, value);
      }
    }

    RequestBody body = buildBody(options, headers);
    if (body != null) {
      builder.method(method, body);
    } else if ("POST".equals(method) || "PUT".equals(method) || "PATCH".equals(method) || "DELETE".equals(method)) {
      builder.method(method, RequestBody.create("", null));
    } else {
      builder.method(method, null);
    }

    return builder.build();
  }

  private RequestBody buildBody(JSONObject options, JSONObject headers) {
    // formData (multipart)
    JSONObject formData = optObject(options, "formData");
    if (formData != null) {
      MultipartBody.Builder builder = new MultipartBody.Builder().setType(MultipartBody.FORM);
      Iterator<String> keys = formData.keys();
      while (keys.hasNext()) {
        String key = keys.next();
        String value = optString(formData, key);
        if (value != null) builder.addFormDataPart(key, value);
      }
      return builder.build();
    }
    // form (url-encoded)
    JSONObject form = optObject(options, "form");
    if (form != null) {
      FormBody.Builder builder = new FormBody.Builder();
      Iterator<String> keys = form.keys();
      while (keys.hasNext()) {
        String key = keys.next();
        String value = optString(form, key);
        if (value != null) builder.add(key, value);
      }
      return builder.build();
    }
    // body (raw)
    if (options.has("body") && !options.isNull("body")) {
      String bodyStr;
      try {
        Object bodyObj = options.get("body");
        if (bodyObj instanceof String) {
          bodyStr = (String) bodyObj;
        } else {
          bodyStr = bodyObj.toString();
        }
      } catch (JSONException e) {
        return null;
      }
      String contentType = optString(headers, "Content-Type");
      if (contentType == null) contentType = optString(headers, "content-type");
      if (contentType == null) contentType = "text/plain; charset=utf-8";
      MediaType mediaType = MediaType.parse(contentType);
      return RequestBody.create(bodyStr, mediaType);
    }
    return null;
  }

  private JSONObject buildResponse(Response response, boolean binary) throws IOException {
    JSONObject result = new JSONObject();
    try {
      result.put("statusCode", response.code());
      result.put("statusMessage", response.message());

      JSONObject headers = new JSONObject();
      Headers responseHeaders = response.headers();
      for (int i = 0; i < responseHeaders.size(); i++) {
        headers.put(responseHeaders.name(i), responseHeaders.value(i));
      }
      result.put("headers", headers);

      ResponseBody responseBody = response.body();
      if (responseBody != null) {
        if (binary) {
          byte[] bytes = responseBody.bytes();
          result.put("body", Base64.encodeToString(bytes, Base64.NO_WRAP));
        } else {
          result.put("body", responseBody.string());
        }
      } else {
        result.put("body", "");
      }
    } catch (JSONException e) {
      Log.e(TAG, "Build response failed: " + e.getMessage());
    }
    return result;
  }

  private static String optString(JSONObject obj, String key, String def) {
    if (obj == null) return def;
    try {
      if (obj.has(key) && !obj.isNull(key)) return obj.getString(key);
    } catch (Exception ignored) {}
    return def;
  }

  private static String optString(JSONObject obj, String key) {
    return optString(obj, key, null);
  }

  private static boolean optBool(JSONObject obj, String key, boolean def) {
    if (obj == null) return def;
    try {
      if (obj.has(key) && !obj.isNull(key)) return obj.getBoolean(key);
    } catch (Exception ignored) {}
    return def;
  }

  private static int optInt(JSONObject obj, String key, int def) {
    if (obj == null) return def;
    try {
      if (obj.has(key) && !obj.isNull(key)) return obj.getInt(key);
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
