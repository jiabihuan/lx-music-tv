import { forwardRef, useState, useMemo, useRef, useEffect } from 'react'
import {
  TouchableOpacity,
  Animated,
  type TouchableOpacityProps,
  type ViewStyle,
  StyleSheet,
} from 'react-native'
import { useTheme } from '@/store/theme/hook'

export interface FocusableTouchableOpacityProps extends TouchableOpacityProps {
  /** 是否在屏幕出现时自动获取焦点（Android TV 专用） */
  hasTVPreferredFocus?: boolean
  /** 聚焦时附加的样式（覆盖默认高亮；其中 transform scale 会被并入缩放动画） */
  focusStyle?: ViewStyle
  /** 是否允许通过 D-pad 导航聚焦（默认 true） */
  focusable?: boolean
  /** 原生视图 ID（TouchableOpacityProps 未声明，这里补充以支持 tv_no_focus_highlight_ 前缀） */
  nativeID?: string
}

/** JS 层自绘焦点样式的组件标记：原生侧（MainActivity）按前缀跳过前景焦点框，避免双重边框 */
export const TV_JS_FOCUS_NATIVE_ID = 'tv_no_focus_highlight_js_focus'

const DEFAULT_SCALE = 1.06

const AnimatedTouchableOpacity = Animated.createAnimatedComponent(TouchableOpacity)

const extractScale = (style?: ViewStyle): number => {
  if (!style?.transform) return 1
  let scale = 1
  for (const t of style.transform as unknown as Array<Record<string, unknown>>) {
    if (typeof t.scale == 'number') scale = t.scale
  }
  return scale
}

const stripScale = (style?: ViewStyle): ViewStyle | undefined => {
  if (!style) return undefined
  if (!style.transform) return style
  const rest = (style.transform as unknown as Array<Record<string, unknown>>).filter(t => !('scale' in t))
  const next = { ...style }
  if (rest.length > 0) {
    next.transform = rest as unknown as ViewStyle['transform']
  } else {
    delete next.transform
  }
  return next
}

/**
 * TV 遥控器可聚焦的 TouchableOpacity（酷狗TV风格焦点效果）
 *
 * - 默认 focusable，D-pad 可导航，OK 键触发 onPress
 * - 聚焦时：弹性放大 + 主题色发光描边 + 主题色淡填充
 * - 自动注入 tv_no_focus_highlight_ 前缀 nativeID，原生侧跳过焦点前景框，避免双重边框
 * - 完全兼容 TouchableOpacity 的 API（onPress/onLongPress/activeOpacity/ref 等）
 * - ref 支持 measure（与原生 TouchableOpacity 一致）
 */
const FocusableTouchableOpacity = forwardRef<TouchableOpacity, FocusableTouchableOpacityProps>(({
  style,
  focusStyle,
  hasTVPreferredFocus,
  focusable = true,
  nativeID,
  onFocus,
  onBlur,
  children,
  ...props
}, ref) => {
  const [isFocused, setIsFocused] = useState(false)
  const theme = useTheme()
  const scale = useRef(new Animated.Value(1)).current

  const focusScale = useMemo(() => extractScale(focusStyle), [focusStyle])

  useEffect(() => {
    Animated.spring(scale, {
      toValue: isFocused ? focusScale || DEFAULT_SCALE : 1,
      useNativeDriver: true,
      speed: 28,
      bounciness: 5,
    }).start()
  }, [isFocused, focusScale, scale])

  const handleFocus = (e: any) => {
    setIsFocused(true)
    // eslint-disable-next-line @typescript-eslint/no-unsafe-argument
    onFocus?.(e)
  }
  const handleBlur = (e: any) => {
    setIsFocused(false)
    // eslint-disable-next-line @typescript-eslint/no-unsafe-argument
    onBlur?.(e)
  }

  const focusedVisual = useMemo<ViewStyle | null>(() => {
    if (!isFocused) return null
    return {
      backgroundColor: theme['c-primary-light-100-alpha-800'],
      borderColor: theme['c-primary'],
      borderWidth: 2.5,
      borderRadius: 8,
      elevation: 6,
      zIndex: 100,
      ...stripScale(focusStyle),
    }
  }, [isFocused, theme, focusStyle])

  const Comp = AnimatedTouchableOpacity as any
  return (
    <Comp
      ref={ref}
      hasTVPreferredFocus={hasTVPreferredFocus}
      nativeID={nativeID ?? TV_JS_FOCUS_NATIVE_ID}
      style={StyleSheet.compose(style, [
        { transform: [{ scale }] },
        focusedVisual,
      ])}
      onFocus={handleFocus as any}
      onBlur={handleBlur as any}
      {...props}
      focusable={focusable}
    >
      {children}
    </Comp>
  )
})

FocusableTouchableOpacity.displayName = 'FocusableTouchableOpacity'

export { FocusableTouchableOpacity }
export default FocusableTouchableOpacity
