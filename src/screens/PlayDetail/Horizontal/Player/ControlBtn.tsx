import { FocusableTouchableOpacity as TouchableOpacity } from '@/components/tv/FocusableTouchableOpacity'
import {StyleSheet, View} from 'react-native'
import { Icon } from '@/components/common/Icon'
import { useTheme } from '@/store/theme/hook'
import { playNext, playPrev, togglePlay } from '@/core/player/player'
import { useIsPlay } from '@/store/player/hook'
import { useLayout } from '@/utils/hooks'
import { marginLeft } from '../constant'
import { BTN_WIDTH } from '../MoreBtn/Btn'

const PrevBtn = ({ size }: { size: number }) => {
  const theme = useTheme()
  const handlePlayPrev = () => {
    void playPrev()
  }
  return (
    <TouchableOpacity style={{ ...styles.cotrolBtn, width: size, height: size }} activeOpacity={0.5} onPress={handlePlayPrev}>
      <Icon name='prevMusic' color={theme['c-button-font']} rawSize={size * 0.7} />
    </TouchableOpacity>
  )
}
const NextBtn = ({ size }: { size: number }) => {
  const theme = useTheme()
  const handlePlayNext = () => {
    void playNext()
  }
  return (
    <TouchableOpacity style={{ ...styles.cotrolBtn, width: size, height: size }} activeOpacity={0.5} onPress={handlePlayNext}>
      <Icon name='nextMusic' color={theme['c-button-font']} rawSize={size * 0.7} />
    </TouchableOpacity>
  )
}

const TogglePlayBtn = ({ size }: { size: number }) => {
  const theme = useTheme()
  const isPlay = useIsPlay()
  return (
    <TouchableOpacity style={{ ...styles.cotrolBtn, width: size, height: size }} activeOpacity={0.5} onPress={togglePlay}>
      <Icon name={isPlay ? 'pause' : 'play'} color={theme['c-button-font']} rawSize={size * 0.7} />
    </TouchableOpacity>
  )
}

const MIN_SIZE = BTN_WIDTH * 1.1
export default () => {
  const { onLayout, height, width } = useLayout()
  const size = Math.max(Math.min(height * 0.65, (width - marginLeft) * 0.52 * 0.3) * global.lx.fontSize, MIN_SIZE)
  return (
    <View style={{ ...styles.content, gap: size * 0.5 }} onLayout={onLayout}>
      <PrevBtn size={size} />
      <TogglePlayBtn size={size}/>
      <NextBtn size={size} />
    </View>
  )
}

const styles = StyleSheet.create({
  content: {
    flexGrow: 1,
    flexShrink: 1,
    flexDirection: 'row',
    gap: 22,
    alignItems: 'center',
    justifyContent: 'center',
  },
  cotrolBtn: {
    justifyContent: 'center',
    alignItems: 'center',

    // backgroundColor: '#ccc',
    shadowOpacity: 1,
    textShadowRadius: 1,
  },
})
