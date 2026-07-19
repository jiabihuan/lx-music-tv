import { forwardRef, useState, useMemo } from 'react'
import {
  TouchableOpacity,
  type TouchableOpacityProps,
  type ViewStyle,
  StyleSheet,
} from 'react-native'
import { useTheme } from '@/store/theme/hook'

export interface FocusableTouchableOpacityProps extends TouchableOpacityProps {
  /** 是否在屏幕出现时自动获取焦点（Android TV 专用） */
  hasTVPreferredFocus?: boolean
  /** 聚焦时附加的样式（覆盖默认高亮） */
  focusStyle?: ViewStyle
}

/**
 * TV 遥控器可聚焦的 TouchableOpacity
 *
 * - 默认 focusable，D-pad 可导航，OK 键触发 onPress
 * - 聚焦时自动应用高亮背景（使用主题 c-primary-background-active）
 * - 完全兼容 TouchableOpacity 的 API（onPress/onLongPress/activeOpacity/ref 等）
 * - ref 支持 measure（与原生 TouchableOpacity 一致）
 */
const FocusableTouchableOpacity = forwardRef<TouchableOpacity, FocusableTouchableOpacityProps>(({
  style,
  focusStyle,
  hasTVPreferredFocus,
  onFocus,
  onBlur,
  children,
  ...props
}, ref) => {
  const [isFocused, setIsFocused] = useState(false)
  const theme = useTheme()

  const handleFocus = (e: any) => {
    setIsFocused(true)
    onFocus?.(e)
  }
  const handleBlur = (e: any) => {
    setIsFocused(false)
    onBlur?.(e)
  }

  const focusedStyle = useMemo<ViewStyle | null>(() => {
    if (!isFocused) return null
    return {
      // 醒目焦点高亮：粗白边框 + 实色绿背景 + 缩放放大 + 阴影
      backgroundColor: theme['c-primary'],
      borderColor: '#FFFFFF',
      borderWidth: 3,
      borderRadius: 6,
      elevation: 8,
      zIndex: 999,
      transform: [{ scale: 1.08 }],
      ...focusStyle,
    }
  }, [isFocused, theme, focusStyle])

  return (
    <TouchableOpacity
      ref={ref}
      hasTVPreferredFocus={hasTVPreferredFocus}
      style={StyleSheet.compose(style as ViewStyle, focusedStyle)}
      onFocus={handleFocus as any}
      onBlur={handleBlur as any}
      {...props}
      {...({ focusable: true } as any)}
    >
      {children}
    </TouchableOpacity>
  )
})

FocusableTouchableOpacity.displayName = 'FocusableTouchableOpacity'

export { FocusableTouchableOpacity }
export default FocusableTouchableOpacity
