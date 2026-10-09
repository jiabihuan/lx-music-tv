package cn.toside.music.mobile;

import android.app.Application;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.FocusFinder;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;

import android.content.Intent;

import cn.toside.music.mobile.aiSharjeck.AISharjeckService;
import cn.toside.music.mobile.aiSharjeck.AISharjeckUtils;
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

    /** 可调节控件（滑块/进度条）的 nativeID 前缀，命中则 D-pad 左右键被 JS 消费 */
    private static final String TV_ADJUSTABLE_PREFIX = "tv_adjustable_";

    /** 无需系统焦点高亮前景的 View 的 nativeID 前缀（如 KTV 全屏播放的透明焦点锚点） */
    private static final String TV_NO_FOCUS_HIGHLIGHT_PREFIX = "tv_no_focus_highlight_";

    /**
     * 全屏按键拦截开关（KTV 全屏播放用）：
     * 开启时，D-pad 上下键 / OK / Enter 键被转发到 JS 侧统一处理（暂停播放、呼出控制条），
     * 不参与系统焦点导航。JS 侧在 KTV 全屏且控制条隐藏时开启，控制条显示时关闭恢复系统导航。
     */
    private static boolean fullscreenKeyCapture = false;

    public static void setFullscreenKeyCapture(boolean enabled) {
        fullscreenKeyCapture = enabled;
    }

    public static boolean isFullscreenKeyCapture() {
        return fullscreenKeyCapture;
    }

    /** 用户选择的焦点框边框颜色（默认酷狗TV蓝） */
    private static final int DEFAULT_FOCUS_BORDER_COLOR = 0xFF2CA3F3;
    private static int focusBorderColor = DEFAULT_FOCUS_BORDER_COLOR;

    /**
     * 动态设置焦点框颜色，由 JS 侧通过 UtilsModule 调用
     * @param colorStr 十六进制颜色字符串，如 "#2CA3F3" 或 "#FFFFFF"，空字符串则重置为默认蓝
     */
    public void setFocusBorderColor(String colorStr) {
        if (colorStr == null || colorStr.isEmpty()) {
            focusBorderColor = DEFAULT_FOCUS_BORDER_COLOR;
        } else {
            try {
                String hex = colorStr.startsWith("#") ? colorStr.substring(1) : colorStr;
                focusBorderColor = Color.parseColor("#" + hex);
            } catch (NumberFormatException e) {
                focusBorderColor = DEFAULT_FOCUS_BORDER_COLOR;
            }
        }
        // 应用新颜色到所有已设置焦点的 View
        applyFocusSelectorToTree(contentRootView);
    }

    public static int getFocusBorderColor() {
        return focusBorderColor;
    }

    /** Overlay 根容器 nativeID（JS 侧 Overlay.tsx 设置），用于弹窗焦点守门 */
    private static final String TV_OVERLAY_ROOT_ID = "tv_overlay_root";
    /** Overlay 遮罩层 nativeID（JS 侧 Overlay.tsx 设置），用于弹窗焦点守门 */
    private static final String TV_OVERLAY_MASK_ID = "tv_overlay_mask";

    /** 焦点选择器资源 ID（在 onCreate 中解析） */
    private int focusSelectorResId = 0;
    /** 标记 View 已应用焦点选择器的 tag ID */
    private int focusAppliedTagId = 0;
    /** 标记当前焦点 View 的 tag ID（用于追踪当前聚焦元素） */
    private int focusedTagId = 0;
    /** 主线程 Handler，用于延迟检查焦点 */
    private Handler mainHandler;

    /** Activity 内容根 View（android.R.id.content），用于区分 Dialog/Modal 独立 Window */
    private View contentRootView;
    /** 布局防抖标志：频繁布局变化时合并为一次全量处理，避免每帧全树遍历拖慢 UI */
    private boolean layoutRefreshPending = false;
    /** 缓存的焦点选择器 drawable 模板。只用于克隆（newDrawable），
     *  不得直接把同一实例 setForeground 给多个 View：StateListDrawable 的状态
     *  是共享的，一个 View 聚焦会改写 state 导致所有共享该实例的 View 同时显示白框 */
    private Drawable focusSelectorDrawable;

    /** 视图树全局焦点变化监听器，确保动态新增的 View 也能被适配 */
    private ViewTreeObserver.OnGlobalFocusChangeListener focusListener;
    /** 全局布局变化监听器，确保每次新页面/新弹窗出现时都能重新应用焦点高亮 */
    private ViewTreeObserver.OnGlobalLayoutListener layoutListener;
    /** 当前活跃的 Overlay 根 View（弹窗焦点守门用），避免每次按键全量查找 */
    private View activeOverlayRoot;

    /** 触摸点击聚焦：手机/触摸操作时，点击可聚焦元素后把白色光标移到点击位置 */
    private float touchDownX;
    private float touchDownY;
    private boolean touchMoved;
    private int touchSlop = 24;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 初始化夏杰语音对接：注册音乐应用、启动接收服务、动态注册接收器
        initAISharjeck();

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

        contentRootView = getWindow().getDecorView().findViewById(android.R.id.content);
        focusSelectorDrawable = focusSelectorResId != 0 ? getResources().getDrawable(focusSelectorResId) : null;
        touchSlop = ViewConfiguration.get(getApplicationContext()).getScaledTouchSlop();

        final View rootView = contentRootView;

        // 注册全局焦点变化监听
        focusListener = new ViewTreeObserver.OnGlobalFocusChangeListener() {
            @Override
            public void onGlobalFocusChanged(View oldFocus, View newFocus) {
                // 不做整棵树遍历（否则每次移动焦点都扫全树，界面会卡）；
                // 各 View 的焦点前景是持久标记（focusAppliedTagId），新挂载的 View 由下方
                // 防抖的布局监听器统一补齐，这里只处理当前焦点本身。
                //
                // 注意：必须手动强制刷新新旧焦点 View 的 drawable state。
                // 原生 StateListDrawable 理论上会随 View 焦点变化自动刷新，但部分 RN View
                // 的刷新并不可靠（曾出现白框残留在旧焦点上、不跟随新焦点的问题），
                // 因此失焦后显式刷新旧焦点（白框立即消失）、聚焦后显式刷新新焦点（白框立即出现）。
                if (oldFocus != null && oldFocus != newFocus) {
                    oldFocus.refreshDrawableState();
                }
                if (newFocus != null) {
                    applyFocusSelectorToView(newFocus);
                    newFocus.refreshDrawableState();
                }
                // Modal / Dialog（如播放页设置弹窗）是独立 Window，Activity content 树遍历不到，
                // 需基于当前焦点向上找到 Dialog 根并全量遍历，保证弹窗内控件也能被应用焦点高亮
                if (newFocus != null) {
                    applyFocusToCurrentFocusView();
                }
                // 弹窗焦点守门：焦点被 Overlay 遮罩捕获或落在弹窗外时，强制拉回弹窗内容
                guardOverlayFocus(newFocus);
            }
        };
        rootView.getViewTreeObserver().addOnGlobalFocusChangeListener(focusListener);

        // 注册全局布局监听（新页面、弹窗出现时会触发），带防抖合并
        layoutListener = new ViewTreeObserver.OnGlobalLayoutListener() {
            @Override
            public void onGlobalLayout() {
                if (layoutRefreshPending) return;
                layoutRefreshPending = true;
                mainHandler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        layoutRefreshPending = false;
                        applyFocusSelectorToTree(rootView);
                        // 布局变化后重新探测活跃 Overlay，弹窗打开/关闭时更新缓存
                        updateActiveOverlayRoot();
                    }
                }, 120);
            }
        };
        rootView.getViewTreeObserver().addOnGlobalLayoutListener(layoutListener);

        // 初始延迟遍历一次，等 RN 把视图挂上去
        rootView.postDelayed(new Runnable() {
            @Override
            public void run() {
                applyFocusSelectorToTree(rootView);
                updateActiveOverlayRoot();
            }
        }, 1000);
    }

    @Override
    protected void onDestroy() {
        AISharjeckUtils.unregisterDynamicReceiver(this);
        if (focusListener != null || layoutListener != null) {
            View rootView = contentRootView;
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
        // 全屏播放拦截按键时（KTV 全屏画面，无菜单/控制条），整棵树都不绘制焦点框，直接返回
        if (fullscreenKeyCapture) return;
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
     * 判断 View 是否无需系统焦点高亮前景。
     * 沿祖先链查找带以下前缀 nativeID 的 View：
     *  - `tv_no_focus_highlight_`：无需任何焦点高亮（如 KTV 全屏播放的透明焦点锚点）
     *  - `tv_adjustable_`：滑块/进度条等可调节控件，JS 侧自绘焦点高亮，
     *    原生不再叠加前景框，避免出现双重边框
     * 命中则跳过应用焦点前景。
     */
    private boolean isNoFocusHighlightView(View view) {
        View v = view;
        while (v != null) {
            Object tag = v.getTag(com.facebook.react.R.id.view_tag_native_id);
            if (tag instanceof String) {
                String nativeId = (String) tag;
                if (nativeId.startsWith(TV_NO_FOCUS_HIGHLIGHT_PREFIX)
                        || nativeId.startsWith(TV_ADJUSTABLE_PREFIX)) {
                    return true;
                }
            }
            Object parent = v.getParent();
            if (!(parent instanceof View)) break;
            v = (View) parent;
        }
        return false;
    }

    /**
     * 创建独立的焦点选择器实例。
     * 每个 View 必须持有独立 state 的实例：getConstantState().newDrawable() 生成
     * 一个共享底层资源数据但互不影响状态的 drawable，再 mutate() 确保其 state 可写，
     * 否则多个 View 共用同一个 StateListDrawable 会互相覆盖焦点状态。
     */
    private Drawable createFocusSelectorInstance() {
        if (focusSelectorDrawable == null) return null;
        try {
            Drawable d = focusSelectorDrawable.getConstantState() != null
                ? focusSelectorDrawable.getConstantState().newDrawable()
                : focusSelectorDrawable;
            return d.mutate();
        } catch (Throwable t) {
            return focusSelectorDrawable.mutate();
        }
    }

    /**
     * 创建带用户自定义颜色的焦点选择器实例
     * 当颜色与默认白色不同时，通过 ShapeDrawable 动态生成
     */
    private Drawable createCustomFocusSelectorInstance() {
        try {
            GradientDrawable focusedShape = new GradientDrawable();
            focusedShape.setShape(GradientDrawable.RECTANGLE);
            focusedShape.setCornerRadius(10f);
            focusedShape.setColor(0x0DFFFFFF);
            focusedShape.setStroke(2, focusBorderColor);

            GradientDrawable pressedShape = new GradientDrawable();
            pressedShape.setShape(GradientDrawable.RECTANGLE);
            pressedShape.setCornerRadius(10f);
            pressedShape.setColor(0x24FFFFFF);

            ColorStateList colorStateList = ColorStateList.valueOf(focusBorderColor);

            StateListDrawable sld = new StateListDrawable();
            sld.addState(new int[] { android.R.attr.state_focused }, focusedShape);
            sld.addState(new int[] { android.R.attr.state_pressed }, pressedShape);
            sld.addState(new int[] {}, new GradientDrawable());

            return sld.mutate();
        } catch (Throwable t) {
            return createFocusSelectorInstance();
        }
    }

    /**
     * 给单个 View 设置焦点前景选择器
     * 对所有可点击或已可聚焦的 View 应用焦点高亮前景
     */
    private void applyFocusSelectorToView(View view) {
        if (view == null || focusSelectorDrawable == null) return;
        // 全屏播放拦截按键时（KTV 全屏画面，无菜单/控制条），不绘制任何系统焦点框，
        // 确保播放画面四周始终无白框；菜单/控制条打开时恢复焦点框。
        if (fullscreenKeyCapture) return;
        // 非可交互 View（不可点击且不可聚焦，如普通文本/容器）直接跳过，
        // 避免整树遍历时对每个节点都沿祖先链查 nativeID 拖慢 UI
        if (!(view.isClickable() || view.isFocusable())) return;
        // 用 id tag 标记已设置过，避免与 RN 内部使用的 tag 冲突；已标记的直接跳过（快路径）
        if (focusAppliedTagId != 0 && view.getTag(focusAppliedTagId) != null) return;
        // 无需焦点高亮前景的 View（如 KTV 全屏播放的透明焦点锚点）跳过，避免画面四周出现白框
        if (isNoFocusHighlightView(view)) return;

        try {
            if (!view.isFocusable()) {
                view.setFocusable(true);
                view.setFocusableInTouchMode(false);
            }
            // 如果用户自定义了焦点颜色，使用自定义 drawable；否则使用默认
            Drawable selector = (focusBorderColor != 0xFFFFFFFF)
                    ? createCustomFocusSelectorInstance()
                    : createFocusSelectorInstance();
            if (selector != null) {
                view.setForeground(selector);
            }
            if (focusAppliedTagId != 0) view.setTag(focusAppliedTagId, true);
            view.setClipToOutline(false);
        } catch (Throwable t) {
            // 忽略，不影响正常运行
        }
    }

    /**
     * 触摸点击聚焦（手机/触摸设备）：
     * 触摸模式下 View 的 focusableInTouchMode 为 false，点击不会自动移动焦点，
     * 导致白色光标停留在上一个焦点位置。因此在一次「点击」（按下到抬起无明显位移）
     * 结束后，把焦点移动到被点击位置的可聚焦 View，让光标跟随点击位置。
     */
    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                touchDownX = ev.getRawX();
                touchDownY = ev.getRawY();
                touchMoved = false;
                break;
            case MotionEvent.ACTION_MOVE:
                if (!touchMoved) {
                    if (Math.abs(ev.getRawX() - touchDownX) > touchSlop
                        || Math.abs(ev.getRawY() - touchDownY) > touchSlop) {
                        touchMoved = true;
                    }
                }
                break;
            case MotionEvent.ACTION_UP:
                // 仅无位移的「点击」才聚焦；滚动/滑动结束不移动焦点，避免列表跳动
                if (!touchMoved) {
                    final float x = ev.getRawX();
                    final float y = ev.getRawY();
                    // 延迟到 RN 触摸处理（onPress 切换页面等）完成后，避免焦点被后续渲染影响
                    mainHandler.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            focusViewAtLocation(x, y);
                        }
                    }, 120);
                }
                break;
            default:
                break;
        }
        return super.dispatchTouchEvent(ev);
    }

    /**
     * 把焦点移动到屏幕坐标 (x, y) 处可聚焦的 View。
     */
    private void focusViewAtLocation(float x, float y) {
        try {
            if (contentRootView == null || !contentRootView.isShown()) return;
            View target = findFocusableAt(contentRootView, x, y);
            if (target != null && !target.isFocused()) {
                // 确保点击位置有白框 foreground
                applyFocusSelectorToView(target);
                // 触摸模式下 focusableInTouchMode=false 的 View 会被系统拒绝 requestFocus
                // （checkFocus 检查触摸模式），必须允许触摸模式聚焦后才能把原生焦点移过来，
                // 白色光标（foreground selector 跟随真实焦点）才会移动到点击位置。
                target.setFocusableInTouchMode(true);
                target.requestFocus();
            }
        } catch (Throwable t) {
            // 忽略，不影响正常运行
        }
    }

    /**
     * 从 view 树中查找屏幕坐标 (x, y) 处可聚焦的 View。
     * 优先匹配最深层的子 View（与点击命中最上层子元素一致）。
     */
    private View findFocusableAt(View view, float x, float y) {
        if (view == null || view.getVisibility() != View.VISIBLE) return null;
        if (view instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) view;
            for (int i = vg.getChildCount() - 1; i >= 0; i--) {
                View found = findFocusableAt(vg.getChildAt(i), x, y);
                if (found != null) return found;
            }
        }
        // 可聚焦或可点击（点击命中的交互元素，即使尚未被应用过焦点框）
        if ((view.isFocusable() || view.isClickable()) && view.getWidth() > 0 && view.getHeight() > 0) {
            int[] loc = new int[2];
            view.getLocationOnScreen(loc);
            if (x >= loc[0] && x <= loc[0] + view.getWidth()
                && y >= loc[1] && y <= loc[1] + view.getHeight()) {
                return view;
            }
        }
        return null;
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
        // KTV 全屏播放：上下/OK/Enter/菜单键转发 JS（暂停播放、上下曲、呼出歌曲菜单），不参与系统焦点导航
        if (fullscreenKeyCapture && isFullscreenCaptureKey(keyCode)) {
            sendKeyToJS(keyCode, event);
            return true;
        }
        // KTV 全屏播放：音量键转发 JS 调节音量，不参与系统音量调节
        if (fullscreenKeyCapture && isVolumeKey(keyCode)) {
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
            // 若焦点位于活跃 Overlay 弹窗内，将 D-pad 焦点导航限制在弹窗子树内，
            // 防止遥控器焦点穿透弹窗落到底层页面
            if (navigateFocusInsideOverlay(keyCode)) {
                return true;
            }
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
        }

        // D-pad 左右键：若焦点位于可调节控件（滑块/进度条）内，拦截并转发 JS 处理步进
        if (isAdjustableDpadKey(keyCode)) {
            View currentFocus = getCurrentFocus();
            if (currentFocus != null) {
                String adjustableId = findAdjustableNativeId(currentFocus);
                if (adjustableId != null) {
                    sendKeyToJS(keyCode, event, false, adjustableId);
                    return true;
                }
            }
        }

        // 其他按键走默认处理（D-pad 焦点导航由 RN 自动处理）
        return super.onKeyDown(keyCode, event);
    }

    /**
     * 判断是否是 D-pad 左右方向键
     */
    private boolean isAdjustableDpadKey(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT;
    }

    /**
     * 沿焦点 View 的祖先链查找带 `tv_adjustable_` 前缀 nativeID 的可调节控件。
     * nativeID 由 JS 侧 View 的 nativeID prop 映射到 tag（R.id.view_tag_native_id）。
     */
    private String findAdjustableNativeId(View view) {
        View v = view;
        while (v != null) {
            Object tag = v.getTag(com.facebook.react.R.id.view_tag_native_id);
            if (tag instanceof String && ((String) tag).startsWith(TV_ADJUSTABLE_PREFIX)) {
                return (String) tag;
            }
            Object parent = v.getParent();
            if (!(parent instanceof View)) break;
            v = (View) parent;
        }
        return null;
    }

    /**
     * 全屏播放时由 JS 消费的按键：
     *   OK/Enter  → 暂停/播放
     *   DPAD_UP   → 上一曲
     *   DPAD_DOWN → 下一曲
     *   DPAD_LEFT/RIGHT → 快退/快进（seek）
     *   MENU      → 呼出歌曲选择菜单
     */
    private boolean isFullscreenCaptureKey(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
            case KeyEvent.KEYCODE_MENU:
                return true;
            default:
                return false;
        }
    }

    /**
     * 判断是否是音量键
     */
    private boolean isVolumeKey(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_VOLUME_UP:
            case KeyEvent.KEYCODE_VOLUME_DOWN:
            case KeyEvent.KEYCODE_VOLUME_MUTE:
                return true;
            default:
                return false;
        }
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
                // 仅当焦点位于独立 Window（Dialog/Modal，根不是 Activity content 树）时才全量遍历，
                // Activity 内容树由防抖的布局监听器统一处理，避免每次按键都重复扫全树
                if (root != null && root != contentRootView) {
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
     * 探测当前屏幕上活跃的 Overlay 根容器并缓存。
     * Overlay（弹窗）打开/关闭时会触发全局布局变化，这里重新查找，
     * 供 guardOverlayFocus 使用，避免每次按键都全量遍历视图树。
     */
    private void updateActiveOverlayRoot() {
        try {
            View content = getWindow().getDecorView().findViewById(android.R.id.content);
            if (content == null) {
                activeOverlayRoot = null;
                return;
            }
            View newRoot = findOverlayRoot(content);
            View oldRoot = activeOverlayRoot;
            activeOverlayRoot = newRoot;

            // 弹窗刚打开时（新的 Overlay 出现），若当前焦点仍在弹窗外，
            // 强制把焦点拉进弹窗内容，确保 D-pad 可以立刻操作弹窗。
            // 注意：只有内容可聚焦时才拉入，避免纯展示弹窗抢焦点。
            if (newRoot != null && newRoot != oldRoot && hasFocusableContent(newRoot)) {
                final View overlay = newRoot;
                final View anchor = findFirstFocusableInOverlay(overlay);
                if (anchor != null && anchor != getCurrentFocus() && !isInsideOverlay(getCurrentFocus())) {
                    // 延迟到布局稳定后再请求焦点，确保锚点 View 已 attached
                    mainHandler.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            try {
                                if (overlay.isShown() && !anchor.hasFocus() && !isInsideOverlay(getCurrentFocus())) {
                                    anchor.requestFocus();
                                }
                            } catch (Throwable t) {
                                // ignore
                            }
                        }
                    }, 80);
                }
            }
        } catch (Throwable t) {
            activeOverlayRoot = null;
        }
    }

    /**
     * 在视图树中查找最顶层的 Overlay 根容器（nativeID == tv_overlay_root）。
     * Overlay 嵌套时返回最内层（最后挂载的）那个。
     */
    private View findOverlayRoot(View view) {
        if (view == null) return null;
        Object tag = view.getTag(com.facebook.react.R.id.view_tag_native_id);
        View found = null;
        if (TV_OVERLAY_ROOT_ID.equals(tag)) {
            found = view;
        }
        if (view instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) view;
            int count = vg.getChildCount();
            for (int i = 0; i < count; i++) {
                View child = findOverlayRoot(vg.getChildAt(i));
                if (child != null) found = child;
            }
        }
        return found;
    }

    /**
     * 判断给定 View 是否在某个 Overlay 根容器内。
     */
    private boolean isInsideOverlay(View view) {
        if (view == null || activeOverlayRoot == null) return false;
        if (activeOverlayRoot == view) return true;
        View v = view;
        while (v.getParent() != null && v.getParent() instanceof View) {
            v = (View) v.getParent();
            if (v == activeOverlayRoot) return true;
        }
        return false;
    }

    /**
     * 判断给定 View 是否是 Overlay 遮罩层（nativeID == tv_overlay_mask）。
     */
    private boolean isOverlayMask(View view) {
        if (view == null) return false;
        Object tag = view.getTag(com.facebook.react.R.id.view_tag_native_id);
        return TV_OVERLAY_MASK_ID.equals(tag);
    }

    /**
     * 将 D-pad 方向键的焦点导航限制在活跃 Overlay 弹窗子树内。
     *
     * 当焦点位于弹窗内容内时，Android 默认的 focusSearch 基于几何位置，
     * 弹窗内容较小（如排行榜下拉菜单）时，方向键可能直接跳到弹窗外的元素，
     * 导致焦点穿透。这里用 FocusFinder 限定在 overlay 子树内查找下一个
     * 可聚焦元素，找不到则保持当前焦点（不穿透）。
     *
     * @return true 表示已消费该按键（焦点位于弹窗内时）
     */
    private boolean navigateFocusInsideOverlay(int keyCode) {
        try {
            if (activeOverlayRoot == null || !activeOverlayRoot.isShown()) return false;
            View currentFocus = getCurrentFocus();
            if (currentFocus == null || !isInsideOverlay(currentFocus)) return false;

            int direction = 0;
            switch (keyCode) {
                case KeyEvent.KEYCODE_DPAD_UP:
                    direction = View.FOCUS_UP;
                    break;
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    direction = View.FOCUS_DOWN;
                    break;
                case KeyEvent.KEYCODE_DPAD_LEFT:
                    direction = View.FOCUS_LEFT;
                    break;
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    direction = View.FOCUS_RIGHT;
                    break;
                default:
                    // OK / Enter 键不参与焦点导航
                    return false;
            }

            // 在 overlay 子树内查找下一个可聚焦元素
            View next = FocusFinder.getInstance().findNextFocus((ViewGroup) activeOverlayRoot, currentFocus, direction);
            if (next != null && next != currentFocus && !isOverlayMask(next)) {
                next.requestFocus();
            }
            // 无论是否找到，都消费掉 D-pad 按键，防止系统 focusSearch 把焦点带出弹窗
            return true;
        } catch (Throwable t) {
            // ignore：不影响正常运行
            return false;
        }
    }

    /**
     * 弹窗焦点守门：防止 D-pad 焦点穿透 Overlay 弹窗到底层页面。
     *
     * 当焦点落在 Overlay 遮罩层上时，说明焦点试图离开弹窗内容，
     * 此时需要把焦点移回弹窗内第一个可聚焦元素（锚点）。
     *
     * 注意：JS 侧 Overlay 遮罩的 onFocus 中通过 focusAnchorRef 回移锚点，
     * 但 RN 0.73 中普通 View 的 ref.focus() 只走 TextInput 命令、对非输入框无效，
     * 因此必须在原生层强制 requestFocus，才能可靠地把焦点拉回弹窗内容。
     */
    private void guardOverlayFocus(View newFocus) {
        try {
            if (newFocus == null) return;
            if (activeOverlayRoot == null || !activeOverlayRoot.isShown()) return;

            // 焦点落在遮罩层上：拉回弹窗内第一个可聚焦元素
            if (isOverlayMask(newFocus)) {
                View anchor = findFirstFocusableInOverlay(activeOverlayRoot);
                if (anchor != null && anchor != newFocus) {
                    anchor.requestFocus();
                }
                return;
            }

            // 焦点离开 Overlay（穿透到底层页面）：拉回弹窗内容
            // 注意：仅当弹窗内容自身没有任何可聚焦元素时才允许穿透，
            // 避免焦点永远困在弹窗里（如纯展示弹窗）。
            if (!isInsideOverlay(newFocus) && hasFocusableContent(activeOverlayRoot)) {
                View anchor = findFirstFocusableInOverlay(activeOverlayRoot);
                if (anchor != null && anchor != newFocus) {
                    anchor.requestFocus();
                }
            }
        } catch (Throwable t) {
            // ignore：不影响正常运行
        }
    }

    /**
     * 判断 Overlay 内容区域（不含遮罩层）是否有可聚焦元素。
     * 遮罩层即使嵌套在 TouchableWithoutFeedback 内也会被排除。
     */
    private boolean hasFocusableContent(View view) {
        if (view == null) return false;
        // 遮罩层不算内容
        if (isOverlayMask(view)) return false;
        if (view.isFocusable()) return true;
        if (view instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) view;
            int count = vg.getChildCount();
            for (int i = 0; i < count; i++) {
                if (hasFocusableContent(vg.getChildAt(i))) return true;
            }
        }
        return false;
    }

    /**
     * 在 Overlay 内查找第一个可聚焦元素（跳过遮罩层）。
     */
    private View findFirstFocusableInOverlay(View overlayRoot) {
        if (overlayRoot == null) return null;
        ViewGroup vg = (ViewGroup) overlayRoot;
        int count = vg.getChildCount();
        for (int i = 0; i < count; i++) {
            View child = vg.getChildAt(i);
            if (isOverlayMask(child)) continue;
            View found = findFirstFocusable(child);
            if (found != null) return found;
        }
        return null;
    }

    private View findFirstFocusable(View view) {
        if (view == null) return null;
        // 遮罩层本身是可聚焦的，但要排除，避免把焦点拉回遮罩
        if (isOverlayMask(view)) return null;
        if (view.isFocusable()) return view;
        if (view instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) view;
            int count = vg.getChildCount();
            for (int i = 0; i < count; i++) {
                View found = findFirstFocusable(vg.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
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
        sendKeyToJS(keyCode, event, false, null);
    }

    private void sendKeyToJS(int keyCode, KeyEvent event, boolean longPress) {
        sendKeyToJS(keyCode, event, longPress, null);
    }

    private void sendKeyToJS(int keyCode, KeyEvent event, boolean longPress, String nativeId) {
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
            if (nativeId != null) params.putString("nativeId", nativeId);
            ctx.getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter.class)
                    .emit(TV_REMOTE_EVENT, params);
        } catch (Throwable t) {
            // ignore：JS 侧未就绪或未注册监听器时静默失败
        }
    }

    /**
     * 初始化夏杰语音对接：
     *  - 向夏杰语音注册音乐应用
     *  - 启动指令接收服务
     *  - 动态注册广播接收器（应用运行时接收 register.require）
     */
    private void initAISharjeck() {
        try {
            AISharjeckUtils.registerApp(this);
            AISharjeckUtils.registerDynamicReceiver(this);
            Intent serviceIntent = new Intent(this, AISharjeckService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent);
            } else {
                startService(serviceIntent);
            }
        } catch (Exception e) {
            // 忽略：夏杰语音未安装或注册失败不影响主流程
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
