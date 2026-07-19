import { useImperativeHandle, forwardRef, useMemo, useRef, useState, useEffect, type Ref } from 'react'
import { View, Animated, DeviceEventEmitter, TouchableOpacity } from 'react-native'
import { useWindowSize } from '@/utils/hooks'

import Modal, { type ModalType } from './Modal'

import { createStyle } from '@/utils/tools'
import { useTheme } from '@/store/theme/hook'
import Text from './Text'
import { scaleSizeH, scaleSizeW } from '@/utils/pixelRatio'

const menuItemHeight = scaleSizeH(40)
const menuItemWidth = scaleSizeW(100)

// 遥控器按键码（与 MainActivity.java 中的 KeyEvent.KEYCODE_* 对应）
const KEYCODE_DPAD_UP = 19
const KEYCODE_DPAD_DOWN = 20
const KEYCODE_DPAD_CENTER = 23
const KEYCODE_ENTER = 66

export interface Position { w: number, h: number, x: number, y: number, menuWidth?: number, menuHeight?: number }
export interface MenuSize { width?: number, height?: number }
export type Menus = Readonly<Array<{ action: string, label: string, disabled?: boolean }>>

const styles = createStyle({
  mask: {
    position: 'absolute',
    top: 0,
    bottom: 0,
    left: 0,
    right: 0,
    opacity: 0,
    backgroundColor: 'black',
  },
  menu: {
    position: 'absolute',
    // borderWidth: StyleSheet.hairlineWidth,
    borderColor: 'lightgray',
    borderRadius: 2,
    backgroundColor: 'white',
    elevation: 3,
  },
  menuItem: {
    paddingLeft: 10,
    paddingRight: 10,
    // height: menuItemHeight,
    // width: menuItemWidth,
    // alignItems: 'center',
    justifyContent: 'center',
    // backgroundColor: '#ccc',
  },
  // menuText: {
  //   // textAlign: 'center',
  //   fontSize: 14,
  // },
})

interface Props<M extends Menus = Menus> {
  menus: Readonly<M>
  onPress?: (menu: M[number]) => void
  buttonPosition: Position
  menuSize: MenuSize
  onHide: () => void
  width?: number
  height?: number
  fontSize?: number
  center?: boolean
  activeId?: M[number]['action'] | null
  visible?: boolean
}

const Menu = ({
  buttonPosition,
  menuSize,
  menus,
  width,
  height,
  onPress = () => {},
  onHide,
  activeId,
  fontSize = 15,
  center = false,
  visible = false,
}: Props) => {
  const theme = useTheme()
  const windowSize = useWindowSize()
  // 当前高亮的菜单项索引（不依赖原生 onFocus，自行管理）
  const [activeIndex, setActiveIndex] = useState(0)

  // 每次 Menu 显示时（buttonPosition 变化），重置 activeIndex 到第一项
  useEffect(() => {
    setActiveIndex(0)
  }, [buttonPosition])

  // 监听原生转发的遥控器按键事件，自行管理菜单项高亮
  // （因为 Menu 在 Modal/Dialog 中，原生层的 foreground 焦点高亮不生效）
  useEffect(() => {
    if (!visible) return
    const subscription = DeviceEventEmitter.addListener('tvRemoteKey', (event) => {
      if (!event) return
      const keyCode = event.keyCode
      // 获取当前可用的菜单项索引列表（跳过 disabled 项）
      const enabledIndexes = menus
        .map((m, i) => m.disabled ? -1 : i)
        .filter(i => i >= 0)
      if (enabledIndexes.length === 0) return

      if (keyCode === KEYCODE_DPAD_UP) {
        setActiveIndex(prev => {
          const curPos = enabledIndexes.indexOf(prev)
          const newPos = curPos <= 0 ? enabledIndexes.length - 1 : curPos - 1
          return enabledIndexes[newPos]
        })
      } else if (keyCode === KEYCODE_DPAD_DOWN) {
        setActiveIndex(prev => {
          const curPos = enabledIndexes.indexOf(prev)
          const newPos = curPos < 0 || curPos >= enabledIndexes.length - 1 ? 0 : curPos + 1
          return enabledIndexes[newPos]
        })
      } else if (keyCode === KEYCODE_DPAD_CENTER || keyCode === KEYCODE_ENTER) {
        // OK 键触发当前高亮项的点击
        const currentMenu = menus[activeIndex]
        if (currentMenu && !currentMenu.disabled) {
          onPress(currentMenu)
          onHide()
        }
      }
    })
    return () => subscription.remove()
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [menus, activeIndex, onPress, onHide, visible])

  const menuItemStyle = useMemo(() => {
    return {
      width: width ?? menuSize.width ?? menuItemWidth,
      height: height ?? menuSize.height ?? menuItemHeight,
    }
  }, [menuSize, width, height])

  const menuStyle = useMemo(() => {
    let menuHeight = menus.length * menuItemStyle.height
    const topHeight = buttonPosition.y - 20
    const bottomHeight = windowSize.height - buttonPosition.y - buttonPosition.h - 20
    if (menuHeight > topHeight && menuHeight > bottomHeight) menuHeight = Math.max(topHeight, bottomHeight)

    const menuWidth = menuItemStyle.width
    const bottomSpace = windowSize.height - buttonPosition.y - buttonPosition.h - 20
    const rightSpace = windowSize.width - buttonPosition.x - menuWidth
    const showInBottom = bottomSpace >= menuHeight
    const showInRight = rightSpace >= menuWidth
    const frameStyle: {
      height: number
      width: number
      top: number
      left?: number
      right?: number
    } = {
      height: menuHeight,
      top: showInBottom ? buttonPosition.y + buttonPosition.h : buttonPosition.y - menuHeight,
      width: menuWidth,
    }
    if (showInRight) {
      frameStyle.left = buttonPosition.x
    } else {
      frameStyle.right = windowSize.width - buttonPosition.x - buttonPosition.w
    }
    return frameStyle
  }, [menus.length, menuItemStyle, buttonPosition, windowSize])

  const menuPress = (menu: Menus[number]) => {
    // if (menu.disabled) return
    onPress(menu)
    onHide()
  }

  // console.log('render menu')
  // console.log(activeId)
  // console.log(menuStyle)
  // console.log(menuItemStyle)
  return (
    <View
      style={{ ...styles.menu, ...menuStyle, backgroundColor: theme['c-content-background'] }}
      focusable={true}
      hasTVPreferredFocus={true}
    >
      <Animated.ScrollView keyboardShouldPersistTaps={'always'}>
        {
          menus.map((menu, index) => {
            const isActive = index === activeIndex
            return menu.disabled
              ? (
                  <View
                    key={menu.action}
                    style={{ ...styles.menuItem, width: menuItemStyle.width, height: menuItemStyle.height, opacity: 0.4 }}
                  >
                    <Text style={{ textAlign: center ? 'center' : 'left' }} size={fontSize} numberOfLines={1}>{menu.label}</Text>
                  </View>
                )
              : (
                    <TouchableOpacity
                      key={menu.action}
                      style={{
                        ...styles.menuItem,
                        width: menuItemStyle.width,
                        height: menuItemStyle.height,
                        backgroundColor: isActive ? theme['c-primary-background-active'] : 'transparent',
                        borderLeftWidth: isActive ? 4 : 0,
                        borderLeftColor: isActive ? theme['c-primary'] : 'transparent',
                      }}
                      onPress={() => { menuPress(menu) }}
                    >
                      <Text style={{ textAlign: center ? 'center' : 'left' }} color={menu.action == activeId ? theme['c-primary-font-active'] : (isActive ? theme['c-primary-font'] : undefined)} size={fontSize} numberOfLines={1}>{menu.label}</Text>
                    </TouchableOpacity>
                  )
          })
        }
      </Animated.ScrollView>
    </View>
  )
}

export interface MenuProps<M extends Menus = Menus> {
  menus: M
  onPress: (menu: M[number]) => void
  onHide?: () => void
  width?: number
  height?: number
  fontSize?: number
  center?: boolean
  activeId?: M[number]['action'] | null
}

export interface MenuType {
  show: (position: Position, menuSize?: MenuSize) => void
  hide: () => void
}

const Component = <M extends Menus>({ menus, width, height, activeId, onHide, onPress, fontSize, center }: MenuProps<M>, ref: Ref<MenuType>) => {
  // console.log(visible)
  const modalRef = useRef<ModalType>(null)
  const [position, setPosition] = useState<Position>({ w: 0, h: 0, x: 0, y: 0 })
  const [menuSize, setMenuSize] = useState<MenuSize>({ })
  const [visible, setVisible] = useState(false)
  const hide = () => {
    modalRef.current?.setVisible(false)
    setVisible(false)
  }
  useImperativeHandle(ref, () => ({
    show(newPosition, menuSize) {
      setPosition(newPosition)
      if (menuSize) setMenuSize(menuSize)
      modalRef.current?.setVisible(true)
      setVisible(true)
    },
    hide() {
      hide()
    },
  }))

  return (
    <Modal onHide={onHide} ref={modalRef}>
      <Menu menus={menus} width={width} height={height} activeId={activeId} buttonPosition={position} menuSize={menuSize} onPress={onPress} onHide={hide} fontSize={fontSize} center={center} visible={visible} />
    </Modal>
  )
}

// export default forwardRef(Component) as ForwardRefFn<MenuType>
export default forwardRef(Component) as <M extends Menus>(p: MenuProps<M> & { ref?: Ref<MenuType> }) => JSX.Element | null
