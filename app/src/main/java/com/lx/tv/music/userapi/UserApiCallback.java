package com.lx.tv.music.userapi;

/**
 * UserApi音源回调接口
 * 由调用方（如MusicPlayer/MainActivity）实现并注册到UserApiEngine
 *
 * 回调时机：
 * - onInit: UserApiEngine加载并初始化JS音源脚本后回调，返回音源能力信息
 * - onResponse: JS音源处理完请求后回调，返回musicUrl/lyric/pic等结果
 * - onRequest: JS音源需要发起HTTP请求时回调，由调用方执行HTTP并通过sendAction('response', ...)返回
 * - onCancelRequest: JS音源取消某个HTTP请求
 * - onShowUpdateAlert: JS音源检测到新版本时回调
 * - onLog: JS音源输出日志
 */
public interface UserApiCallback {

    /**
     * 音源初始化结果回调
     * @param status 是否成功
     * @param errorMessage 失败时的错误信息
     * @param sources 音源能力信息，格式: {sources: {kw: {type, actions, qualitys}, kg:..., tx:..., wy:..., mg:...}}
     *                 type为"music"表示音乐源；actions包含'musicUrl'/'lyric'/'pic'等；qualitys为支持的音质列表
     */
    void onInit(boolean status, String errorMessage, Object sources);

    /**
     * JS音源返回请求结果
     * @param requestKey 请求标识，与sendAction('request', ...)中的requestKey对应
     * @param status 是否成功
     * @param errorMessage 失败时的错误信息
     * @param result 结果数据，格式: {source, action, data}。其中musicUrl的data为{type, url}
     */
    void onResponse(String requestKey, boolean status, String errorMessage, Object result);

    /**
     * JS音源请求发起HTTP，需用OkHttp执行后通过sendAction('response', {...})返回
     * @param requestKey 请求标识
     * @param url 请求URL
     * @param options 请求选项，包含method/headers/body/form等
     */
    void onRequest(String requestKey, String url, Object options);

    /**
     * 取消HTTP请求
     * @param requestKey 请求标识
     */
    void onCancelRequest(String requestKey);

    /**
     * 显示更新提示弹窗
     * @param name 音源名称
     * @param log 更新日志
     * @param updateUrl 更新URL（可能为null）
     */
    void onShowUpdateAlert(String name, String log, String updateUrl);

    /**
     * 日志输出
     * @param type 日志类型：log/info/warn/error
     * @param log 日志内容
     */
    void onLog(String type, String log);
}
