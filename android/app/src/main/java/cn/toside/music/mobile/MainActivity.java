package cn.toside.music.mobile;

import android.app.Application;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;

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
 * 在原版 react-native-navigation 的 NavigationActivity 基础上添加：
 *  - 遥控器按键处理（媒体键转发到 JS）
 *  - TV 焦点高亮：给所有可聚焦 View 设置 foreground 焦点选择器，
 *    确保遥控器焦点在电视屏幕上清晰可见
 */
public class MainActivity extends NavigationActivity {

    /** TV 遥控器按键事件名（JS 侧通过 NativeEventEmitter 监听） */
    private static final String TV_REMOTE_EVENT = "tvRemoteKey";

    /** 焦点选择器资源 ID（在 onCreate 中解析） */
    private int focusSelectorResId = 0;
    /** 标记 View 已应用焦点选择器的 tag ID */
    private int focusAppliedTagId = 0;
    /** 标记当前焦点 View 的 tag ID（用于追踪当前聚焦元素） */
    private int focusedTagId = 0;
    /** 主线程 Handler，用于延迟检查焦点 */
    private Handler mainHandler;

    /** 视图树全局焦点变化监听器，确保动态新增的 View 也能被适配 */
    private ViewTreeObserver.OnGlobalFocusChangeListener focusListener;
    /** 全局布局变化监听器，确保每次新页面/新弹窗出现时都能重新应用焦点高亮 */
    private ViewTreeObserver.OnGlobalLayoutListener layoutListener;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 获取焦点选择器资源 ID
        focusSelectorResId = getResources().getIdentifier(
                "tv_focus_selector", "drawable", getPackageName());
        focusAppliedTagId = getResources().getIdentifier(
                "tv_focus_applied", "id", getPackageName());
        focusedTagId = getResources().getIdentifier(
                "tv_focused_view", "id", getPackageName());

        mainHandler = new Handler(Looper.getMainLooper());

        // 关闭系统默认焦点高亮，避免与我们自定义的 foreground 选择器重叠
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getWindow().getDecorView().setDefaultFocusHighlightEnabled(false);
        }

        final View rootView = getWindow().getDecorView().findViewById(android.R.id.content);

        // 注册全局焦点变化监听
        focusListener = new ViewTreeObserver.OnGlobalFocusChangeListener() {
            @Override
            public void onGlobalFocusChanged(View oldFocus, View newFocus) {
                // 每次焦点变化都全量遍历一次（处理新增 View）
                applyFocusSelectorToTree(rootView);
            }
        };
        rootView.getViewTreeObserver().addOnGlobalFocusChangeListener(focusListener);

        // 注册全局布局监听（新页面、弹窗出现时会触发）
        layoutListener = new ViewTreeObserver.OnGlobalLayoutListener() {
            @Override
            public void onGlobalLayout() {
                applyFocusSelectorToTree(rootView);
            }
        };
        rootView.getViewTreeObserver().addOnGlobalLayoutListener(layoutListener);

        // 初始延迟遍历一次，等 RN 把视图挂上去
        rootView.postDelayed(new Runnable() {
            @Override
            public void run() {
                applyFocusSelectorToTree(rootView);
            }
        }, 1000);
    }

    @Override
    protected void onDestroy() {
        if (focusListener != null || layoutListener != null) {
            View rootView = getWindow().getDecorView().findViewById(android.R.id.content);
            if (rootView != null) {
                ViewTreeObserver vto = rootView.getViewTreeObserver();
                if (focusListener != null) {
                    vto.removeOnGlobalFocusChangeListener(focusListener);
                    focusListener = null;
                }
                if (layoutListener != null) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
                        vto.removeOnGlobalLayoutListener(layoutListener);
                    } else {
                        vto.removeGlobalOnLayoutListener(layoutListener);
                    }
                    layoutListener = null;
                }
            }
        }
        super.onDestroy();
    }

    /**
     * 遍历视图树，给所有可聚焦的 View 设置焦点前景选择器
     */
    private void applyFocusSelectorToTree(View view) {
        if (view == null) return;
        applyFocusSelectorToView(view);
        if (view instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) view;
            int count = vg.getChildCount();
            for (int i = 0; i < count; i++) {
                applyFocusSelectorToTree(vg.getChildAt(i));
            }
        }
    }

    /**
     * 给单个 View 设置焦点前景选择器
     * 对所有可点击或已可聚焦的 View 应用焦点高亮前景
     */
    private void applyFocusSelectorToView(View view) {
        if (view == null || focusSelectorResId == 0) return;
        // 用 id tag 标记已设置过，避免与 RN 内部使用的 tag 冲突
        if (focusAppliedTagId != 0 && view.getTag(focusAppliedTagId) != null) return;

        // 可点击的 View（有交互能力）或已经可聚焦的 View（如 FlatList/ScrollView）
        if (view.isClickable() || view.isFocusable()) {
            try {
                if (!view.isFocusable()) {
                    view.setFocusable(true);
                    view.setFocusableInTouchMode(false);
                }
                Drawable selector = getResources().getDrawable(focusSelectorResId);
                if (selector != null) {
                    view.setForeground(selector);
                    if (focusAppliedTagId != 0) view.setTag(focusAppliedTagId, true);
                    view.setClipToOutline(false);
                }
            } catch (Throwable t) {
                // 忽略，不影响正常运行
            }
        }
    }

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

        // D-pad / OK / Enter 键：多次延迟检查当前焦点（覆盖 Modal/Dialog 中的 View）
        if (isDpadOrOkKey(keyCode)) {
            // 多次检查，确保 Dialog/Modal 中的 View 也能被捕获
            // （Dialog 有独立 Window，Activity 的视图树遍历不到）
            long[] delays = { 30, 80, 150, 300 };
            for (long delay : delays) {
                mainHandler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        applyFocusToCurrentFocusView();
                    }
                }, delay);
            }
            // 同时转发到 JS 侧，让 Menu 等组件可以自行处理焦点高亮
            // （不消费事件，系统焦点导航仍正常工作）
            sendKeyToJS(keyCode, event);
        }

        // 其他按键走默认处理（D-pad 焦点导航由 RN 自动处理）
        return super.onKeyDown(keyCode, event);
    }

    /**
     * 判断是否是 D-pad 或 OK/Enter 键
     */
    private boolean isDpadOrOkKey(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                return true;
            default:
                return false;
        }
    }

    /**
     * 给当前获得焦点的 View 应用焦点高亮前景
     * 使用 getCurrentFocus() 可以获取到 Dialog/Modal 中的焦点 View
     * 向上遍历找到 Dialog 的根视图后全量遍历，确保弹窗内所有
     * 可交互元素都被标记
     */
    private void applyFocusToCurrentFocusView() {
        try {
            View currentFocus = getCurrentFocus();
            if (currentFocus != null) {
                applyFocusSelectorToView(currentFocus);
                // 向上找到最顶层的 ViewGroup（可能是 Dialog 的根视图）
                View root = currentFocus;
                while (root.getParent() != null && root.getParent() instanceof View) {
                    root = (View) root.getParent();
                }
                // 全量遍历 Dialog/Modal 的视图树
                if (root != null && root != currentFocus) {
                    applyFocusSelectorToTree(root);
                }
            }
        } catch (Throwable t) {
            // ignore
        }
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

