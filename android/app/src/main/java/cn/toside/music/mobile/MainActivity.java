package cn.toside.music.mobile;

import android.app.Application;
import android.view.KeyEvent;

import com.facebook.react.ReactApplication;
import com.facebook.react.ReactInstanceManager;
import com.facebook.react.ReactNativeHost;
import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.ReactContext;
import com.facebook.react.bridge.WritableMap;
import com.facebook.react.modules.core.DeviceEventManagerModule;
import com.reactnativenavigation.NavigationActivity;

/**
 * LX Music TV 版主 Activity
 *
 * 在原版 react-native-navigation 的 NavigationActivity 基础上添加遥控器按键处理：
 *  - D-pad 上下左右：React Native 默认已支持焦点导航，这里只做兜底
 *  - 媒体键（播放/暂停/上一首/下一首）：转发到 JS 侧统一处理
 *  - 菜单键(KEYCODE_MENU)：弹出 React Native DevServer 菜单（开发用）
 *  - 返回键：交给 RN 处理（JS 内可拦截）
 *
 * 所有媒体按键事件通过 RCTDeviceEventEmitter 发送到 JS 侧，
 * JS 侧通过 NativeEventEmitter 监听 "tvRemoteKey" 事件。
 */
public class MainActivity extends NavigationActivity {

    /** TV 遥控器按键事件名（JS 侧通过 NativeEventEmitter 监听） */
    private static final String TV_REMOTE_EVENT = "tvRemoteKey";

    /**
     * 拦截遥控器按键事件，转发到 JS 侧统一处理。
     * 返回 true 表示已消费事件，不向下传递；返回 false 走默认处理。
     */
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        // 优先将媒体键转发到 JS 侧
        if (isMediaKey(keyCode)) {
            sendKeyToJS(keyCode, event);
            return true;
        }
        // 菜单键：开发模式下弹出 RN DevServer 菜单
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            ReactInstanceManager rim = getReactInstanceManager();
            if (rim != null) {
                rim.showDevOptionsDialog();
                return true;
            }
        }
        // 其他按键走默认处理（D-pad 焦点导航由 RN 自动处理）
        return super.onKeyDown(keyCode, event);
    }

    /**
     * 长按事件也转发到 JS（如长按 OK 键、长按方向键）
     */
    @Override
    public boolean onKeyLongPress(int keyCode, KeyEvent event) {
        sendKeyToJS(keyCode, event, true);
        return true;
    }

    /**
     * 判断是否是媒体相关按键
     */
    private boolean isMediaKey(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_MEDIA_PLAY:
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case KeyEvent.KEYCODE_MEDIA_NEXT:
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
            case KeyEvent.KEYCODE_MEDIA_STOP:
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
            case KeyEvent.KEYCODE_MEDIA_REWIND:
                return true;
            default:
                return false;
        }
    }

    /**
     * 发送按键事件到 JS 侧
     * JS 侧可通过以下代码监听：
     *   import { NativeEventEmitter, NativeModules } from 'react-native'
     *   const emitter = new NativeEventEmitter(NativeModules.MainApplication)
     *   emitter.addListener('tvRemoteKey', (e) => console.log(e.keyCode, e.action, e.longPress))
     */
    private void sendKeyToJS(int keyCode, KeyEvent event) {
        sendKeyToJS(keyCode, event, false);
    }

    private void sendKeyToJS(int keyCode, KeyEvent event, boolean longPress) {
        try {
            ReactInstanceManager rim = getReactInstanceManager();
            if (rim == null) return;
            ReactContext ctx = rim.getCurrentReactContext();
            if (ctx == null) return;
            WritableMap params = Arguments.createMap();
            params.putInt("keyCode", keyCode);
            params.putString("keyName", KeyEvent.keyCodeToString(keyCode));
            params.putInt("action", event != null ? event.getAction() : KeyEvent.ACTION_DOWN);
            params.putBoolean("longPress", longPress);
            ctx.getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter.class)
                    .emit(TV_REMOTE_EVENT, params);
        } catch (Throwable t) {
            // ignore：JS 侧未就绪或未注册监听器时静默失败
        }
    }

    /**
     * 获取 ReactInstanceManager
     * NavigationActivity 本身不直接提供，通过 Application 的 ReactApplication 接口获取。
     */
    private ReactInstanceManager getReactInstanceManager() {
        try {
            Application app = getApplication();
            if (app instanceof ReactApplication) {
                ReactNativeHost host = ((ReactApplication) app).getReactNativeHost();
                if (host != null) return host.getReactInstanceManager();
            }
        } catch (Throwable t) {
            // ignore
        }
        return null;
    }
}

