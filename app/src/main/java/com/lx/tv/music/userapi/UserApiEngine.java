package com.lx.tv.music.userapi;

import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.util.Log;

/**
 * 音源引擎统一管理类。替代落雪音乐 lx-music-mobile 的 React Native 模块（UserApiModule）。
 *
 * 职责：
 *  - 加载 JS 音源脚本（{@link #loadScript(Bundle)}）
 *  - 发送动作到 JS（{@link #sendAction(String, String)}），例如 response / request 回调
 *  - 通过 {@link UserApiCallback} 接收 JS 的回调（init / response / request / cancelRequest /
 *    showUpdateAlert / log）并转发给 UI 层
 *
 * 内部使用 {@link JavaScriptThread} 在独立线程执行 QuickJS，{@link JsHandler} 在主线程
 * 处理 JS 侧发来的消息。
 *
 * 回调接口 {@link UserApiCallback} 为同包下的顶级接口，由调用方（如 MusicPlayer /
 * MusicSearchManager / MainActivity）实现并经 {@link #setCallback(UserApiCallback)} 注册。
 */
public class UserApiEngine {
  private static final String TAG = "UserApi";

  private JavaScriptThread javaScriptThread;
  private final Context context;
  // 包内可见，供 JsHandler 直接读取最新回调
  UserApiCallback callback;

  public UserApiEngine(Context context) {
    this.context = context.getApplicationContext();
  }

  /**
   * 设置回调。建议在 {@link #loadScript(Bundle)} 之前调用；JsHandler 通过引擎引用读取该字段，
   * 因此运行期更新回调也会立即生效。
   */
  public void setCallback(UserApiCallback callback) {
    this.callback = callback;
  }

  /**
   * 加载并初始化 JS 音源脚本。
   *
   * @param info Bundle，包含字段：id, name, description, version, author, homepage, script（JS 源码）
   */
  public void loadScript(Bundle info) {
    if (this.javaScriptThread != null) destroy();
    this.javaScriptThread = new JavaScriptThread(this.context, info);
    this.javaScriptThread.prepareHandler(new JsHandler(this.context.getMainLooper(), this));
    this.javaScriptThread.getHandler().sendEmptyMessage(HandlerWhat.INIT);
    this.javaScriptThread.setUncaughtExceptionHandler((thread, ex) -> {
      Handler jsHandler = javaScriptThread.getHandler();
      Message message = jsHandler.obtainMessage();
      message.what = HandlerWhat.LOG;
      message.obj = new Object[]{"error", "Uncaught exception in JavaScriptThread: " + ex.getMessage()};
      jsHandler.sendMessage(message);
      Log.e(TAG, "Uncaught exception in JavaScriptThread: " + ex.getMessage());
    });
    Log.d(TAG, "Engine Thread id: " + Thread.currentThread().getId());
  }

  /**
   * 向 JS 发送动作（如 response / request / cancelRequest）。
   *
   * @param action 动作名，对应 preload.js 的 jsCall 分支
   * @param info   动作数据（JSON 字符串）
   * @return true 表示已投递到 JS 线程；false 表示引擎未加载脚本
   */
  public boolean sendAction(String action, String info) {
    JavaScriptThread javaScriptThread = this.javaScriptThread;
    if (javaScriptThread == null) return false;
    Handler jsHandler = javaScriptThread.getHandler();
    Message message = jsHandler.obtainMessage();
    message.what = HandlerWhat.ACTION;
    message.obj = new Object[]{action, info};
    jsHandler.sendMessage(message);
    return true;
  }

  /**
   * 销毁引擎，释放 QuickJS 上下文与线程。
   */
  public void destroy() {
    JavaScriptThread javaScriptThread = this.javaScriptThread;
    if (javaScriptThread == null) return;
    javaScriptThread.getHandler().sendEmptyMessage(HandlerWhat.DESTROY);
    javaScriptThread.stopThread();
    this.javaScriptThread = null;
  }
}
