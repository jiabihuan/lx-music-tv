import { forwardRef, useState, useMemo } from 'react'
import {
  Pressable,
  type PressableProps,
  type ViewStyle,
  type View,
  StyleSheet,
} from 'react-native'
import { useTheme } from '@/store/theme/hook'

export interface FocusablePressableProps extends PressableProps {
  hasTVPreferredFocus?: boolean
  focusStyle?: ViewStyle
}

/**
 * TV 遥控器可聚焦的 Pressable
 * 聚焦时自动应用高亮背景，完全兼容 Pressable API
 */
const FocusablePressable = forwardRef<View, FocusablePressableProps>(({
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
    <Pressable
      ref={ref}
      hasTVPreferredFocus={hasTVPreferredFocus}
      style={StyleSheet.compose(style as ViewStyle, focusedStyle)}
      onFocus={handleFocus as any}
      onBlur={handleBlur as any}
      {...props}
      {...({ focusable: true } as any)}
    >
      {children}
    </Pressable>
  )
})

FocusablePressable.displayName = 'FocusablePressable'

export { FocusablePressable }
export default FocusablePressable
