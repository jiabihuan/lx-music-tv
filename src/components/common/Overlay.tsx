import { useImperativeHandle, forwardRef, useState, useEffect, useMemo } from 'react'
import { View, TouchableWithoutFeedback, BackHandler, StyleSheet } from 'react-native'
import { useStatusbarHeight } from '@/store/common/hook'

/**
 * Overlay 组件：用绝对定位的 View 替代原生 Modal
 *
 * 为什么需要 Overlay？
 * React Native 的 Modal 在 Android 上会创建独立的 Window（Dialog），
 * 导致 D-pad 遥控器焦点导航失效（无法用方向键移动到弹窗内的菜单项）。
 * 改用绝对定位的 View 后，弹窗内容仍处于主 Activity 的 Window 中，
 * D-pad 焦点导航可正常工作。
 *
 * 注意：父容器需要是全屏布局（flex: 1），否则 Overlay 只会覆盖父容器范围。
 */
export interface OverlayProps {
  onHide?: () => void
  /** 按返回键是否隐藏 */
  keyHide?: boolean
  /** 点击背景是否隐藏 */
  bgHide?: boolean
  /** 背景颜色 */
  bgColor?: string
  /** 是否填充状态栏 */
  statusBarPadding?: boolean
  /** z-index 层级，默认 9999 */
  zIndex?: number
}

export interface OverlayType {
  setVisible: (visible: boolean) => void
}

export default forwardRef<OverlayType, OverlayProps>(({
  onHide = () => {},
  keyHide = true,
  bgHide = true,
  bgColor = 'rgba(0,0,0,0)',
  statusBarPadding = true,
  zIndex = 9999,
  children,
}: OverlayProps, ref) => {
  const [visible, setVisible] = useState(false)
  const statusBarHeight = useStatusbarHeight()

  const handleBgClose = () => {
    if (bgHide) {
      setVisible(false)
      onHide()
    }
  }

  useImperativeHandle(ref, () => ({
    setVisible(_visible) {
      if (visible == _visible) return
      setVisible(_visible)
      if (!_visible) onHide()
    },
  }))

  // 监听返回键，模拟 Modal 的 onRequestClose 行为
  useEffect(() => {
    if (!visible) return
    const subscription = BackHandler.addEventListener('hardwareBackPress', () => {
      if (keyHide) {
        setVisible(false)
        onHide()
        return true
      }
      return false
    })
    return () => subscription.remove()
  }, [visible, keyHide, onHide])

  const memoChildren = useMemo(() => children, [children])

  if (!visible) return null

  return (
    <View style={[StyleSheet.absoluteFill, { zIndex, elevation: zIndex, backgroundColor: bgColor }]}>
      {/* 背景点击层：独立一层，不影响 children 的绝对定位参照物 */}
      <TouchableWithoutFeedback onPress={handleBgClose}>
        <View style={{ flex: 1, paddingTop: statusBarPadding ? statusBarHeight : 0 }} />
      </TouchableWithoutFeedback>
      {/* 内容层：直接放在全屏 View 内，绝对定位参照物是屏幕 */}
      {memoChildren}
    </View>
  )
})
