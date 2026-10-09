import { memo, useCallback, useEffect, useRef } from 'react'
import { Animated, Easing, View } from 'react-native'
import { FocusableTouchableOpacity as TouchableOpacity } from '@/components/tv/FocusableTouchableOpacity'
import { navigations } from '@/navigation'
import { usePlayerMusicInfo, useIsPlay } from '@/store/player/hook'
import commonState from '@/store/common/state'
import playerState from '@/store/player/state'
import { LIST_IDS } from '@/config/constant'
import Image from '@/components/common/Image'
import { setLoadErrorPicUrl, setMusicInfo } from '@/core/player/playInfo'
import { createStyle } from '@/utils/tools'
import { useTheme } from '@/store/theme/hook'

const PIC_MARGIN = 4

export default memo(({ panelWidth }: { panelWidth: number }) => {
  const theme = useTheme()
  const musicInfo = usePlayerMusicInfo()
  const isPlay = useIsPlay()

  const imgWidth = panelWidth > 0 ? Math.round(panelWidth * 0.6) : 0

  // 旋转动画：播放时旋转，暂停时停止
  // 每次 isPlay 变为 true 时重新创建动画并启动，避免复用已 stop 的动画实例导致旋转不稳定
  const rotateAnim = useRef(new Animated.Value(0)).current
  useEffect(() => {
    if (!isPlay) return
    rotateAnim.setValue(0)
    const animation = Animated.loop(
      Animated.timing(rotateAnim, {
        toValue: 1,
        duration: 20000,
        easing: Easing.linear,
        useNativeDriver: true,
      }),
    )
    animation.start()
    return () => {
      animation.stop()
    }
  }, [isPlay, rotateAnim])

  const rotate = rotateAnim.interpolate({
    inputRange: [0, 1],
    outputRange: ['0deg', '360deg'],
  })

  const handlePress = () => {
    if (!musicInfo.id) return
    navigations.pushPlayDetailScreen(commonState.componentIds.home!)
  }

  const handleLongPress = () => {
    const listId = playerState.playMusicInfo.listId
    if (!listId || listId == LIST_IDS.DOWNLOAD) return
    global.app_event.jumpListPosition()
  }

  const handleError = useCallback((url: string | number) => {
    setLoadErrorPicUrl(url as string)
    setMusicInfo({ pic: null })
  }, [])

  return (
    <View style={styles.container}>
      <TouchableOpacity
        style={{ ...styles.imageWrap, borderColor: theme['c-primary-light-200-alpha-400'] }}
        focusStyle={{ borderRadius: 999, backgroundColor: 'transparent', borderColor: theme['c-primary'] }}
        onLongPress={handleLongPress}
        onPress={handlePress}
        activeOpacity={0.7}
      >
        {
          imgWidth > 0
            ? (
                <Animated.View style={{ width: imgWidth, height: imgWidth, transform: [{ rotate }] }}>
                  <Image
                    url={musicInfo.pic}
                    style={{ width: imgWidth, height: imgWidth, borderRadius: imgWidth / 2 }}
                    onError={handleError}
                  />
                </Animated.View>
              )
            : null
        }
      </TouchableOpacity>
    </View>
  )
})

const styles = createStyle({
  container: {
    flexShrink: 0,
    flexGrow: 0,
    alignItems: 'center',
    justifyContent: 'center',
    paddingVertical: PIC_MARGIN,
  },
  imageWrap: {
    borderRadius: 999,
    borderWidth: 1,
    padding: PIC_MARGIN,
  },
})
