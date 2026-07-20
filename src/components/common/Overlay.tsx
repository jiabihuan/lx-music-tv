import { useImperativeHandle, forwardRef, useState, useEffect, useMemo, useRef } from 'react'
import { View, TouchableWithoutFeedback, BackHandler, StyleSheet } from 'react-native'
import { useStatusbarHeight } from '@/store/common/hook'
import { usePortal } from './Portal'

/**
 * Overlay 组件：用 Portal + 绝对定位 View 替代原生 Modal
 *
 * 为什么需要 Overlay + Portal？
 * 1. React Native 的 Modal 在 Android 上会创建独立的 Window（Dialog），
 *    导致 D-pad 遥控器焦点导航失效（无法用方向键移动到弹窗内的菜单项）。
 * 2. 直接用 absoluteFill 的 View 作为 Overlay，如果父组件不是全屏，
 *    Overlay 也不会是全屏的，导致子组件的绝对定位坐标错误。
 * 3. 使用 Portal 把 Overlay 内容渲染到 Screen 根层级的 PortalHost 中，
 *    确保 Overlay 是真正全屏的，子组件的屏幕坐标计算正确。
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
  const { addPortal, removePortal, updatePortal } = usePortal()
  const portalIdRef = useRef<number | null>(null)

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

  // Portal 渲染
  useEffect(() => {
    if (!visible) {
      if (portalIdRef.current != null) {
        removePortal(portalIdRef.current)
        portalIdRef.current = null
      }
      return
    }

    const portalContent = (
      <View style={[StyleSheet.absoluteFill, { zIndex, elevation: zIndex, backgroundColor: bgColor }]}>
        <TouchableWithoutFeedback onPress={handleBgClose}>
          <View style={{ flex: 1, paddingTop: statusBarPadding ? statusBarHeight : 0 }} />
        </TouchableWithoutFeedback>
        {memoChildren}
      </View>
    )

    if (portalIdRef.current == null) {
      portalIdRef.current = addPortal(portalContent)
    } else {
      updatePortal(portalIdRef.current, portalContent)
    }
  }, [visible, memoChildren, bgColor, zIndex, statusBarHeight, statusBarPadding, addPortal, removePortal, updatePortal])

  useEffect(() => {
    return () => {
      if (portalIdRef.current != null) {
        removePortal(portalIdRef.current)
      }
    }
  }, [removePortal])

  return null
})
