import { memo, useCallback, useEffect, useRef, useState } from 'react'
import { View, PanResponder } from 'react-native'
import { useDrag } from '@/utils/hooks'
import { createStyle } from '@/utils/tools'
import { useTheme } from '@/store/theme/hook'
import { useTvSeek } from '@/utils/hooks/useTvSeek'
// import { scaleSizeW } from '@/utils/pixelRatio'
// import { AppColors } from '@/theme'


const DefaultBar = memo(() => {
  // const theme = useTheme()

  return <View style={{
    ...styles.progressBar,
    // backgroundColor: theme['c-primary-light-200-alpha-900'],
    position: 'absolute',
    width: '100%',
    left: 0,
    top: 0,
  }}></View>
})

const BufferedBar = memo(({ progress }: { progress: number }) => {
  const theme = useTheme()
  return <View style={{ ...styles.progressBar, backgroundColor: theme['c-primary-light-600-alpha-900'], position: 'absolute', width: `${progress * 100}%`, left: 0, top: 0 }}></View>
})

const PreassBar = memo(({ onDragState, setDragProgress, onSetProgress }: {
  onDragState: (drag: boolean) => void
  setDragProgress: (progress: number) => void
  onSetProgress: (progress: number) => void
}) => {
  const {
    onLayout,
    onDragStart,
    onDragEnd,
    onDrag,
  } = useDrag(onSetProgress, onDragState, setDragProgress)
  // const handlePress = useCallback((event: GestureResponderEvent) => {
  //   onPress(event.nativeEvent.locationX)
  // }, [onPress])

  const panResponder = useRef(
    PanResponder.create({
      onStartShouldSetPanResponderCapture: (evt, gestureState) => true,
      onMoveShouldSetPanResponderCapture: (evt, gestureState) => true,

      // onMoveShouldSetPanResponder: () => true,
      onPanResponderMove: (evt, gestureState) => {
        onDrag(gestureState.dx)
      },
      onPanResponderGrant: (evt, gestureState) => {
        onDragStart(gestureState.dx, evt.nativeEvent.locationX)
      },
      onPanResponderRelease: () => {
        onDragEnd()
      },
      // onPanResponderTerminate: (evt, gestureState) => {
      //   onDragEnd()
      // },
    }),
  ).current

  return <View onLayout={onLayout} style={styles.pressBar} {...panResponder.panHandlers} />
})


export const ProgressPlain = ({ progress, duration, buffered, paddingTop }: {
  progress: number
  duration: number
  buffered: number
  paddingTop?: number
}) => {
  // const { progress } = usePlayTimeBuffer()
  const theme = useTheme()
  const progressStr: `${number}%` = `${progress * 100}%`

  const durationRef = useRef(duration)
  useEffect(() => {
    durationRef.current = duration
  }, [duration])

  return (
    <View style={{ ...styles.progress, paddingTop }}>
      <View style={{ flex: 1 }}>
        <DefaultBar />
        <BufferedBar progress={buffered} />
        <View style={{ ...styles.progressBar, backgroundColor: theme['c-primary-alpha-900'], width: progressStr, position: 'absolute', left: 0, top: 0 }} />
      </View>
      <View style={styles.pressBar} />
    </View>
  )
}

const Progress = ({ progress, duration, buffered, paddingTop }: {
  progress: number
  duration: number
  buffered: number
  paddingTop?: number
}) => {
  // const { progress } = usePlayTimeBuffer()
  const theme = useTheme()
  const [draging, setDraging] = useState(false)
  const [dragProgress, setDragProgress] = useState(0)
  const [isFocused, setIsFocused] = useState(false)
  const progressStr: `${number}%` = `${progress * 100}%`

  const durationRef = useRef(duration)
  const progressRef = useRef(progress)
  useEffect(() => {
    durationRef.current = duration
  }, [duration])
  useEffect(() => {
    progressRef.current = progress
  }, [progress])
  const onSetProgress = useCallback((progress: number) => {
    global.app_event.setProgress(progress * durationRef.current)
  }, [])
  const nativeID = useTvSeek(durationRef, progressRef)

  return (
    <View style={{ ...styles.progress, paddingTop }}>
      <View style={{ flex: 1 }}>
        <DefaultBar />
        <BufferedBar progress={buffered} />
        {
          draging
            ? (
                <>
                  <View style={{ ...styles.progressBar, backgroundColor: theme['c-primary-light-200-alpha-900'], width: progressStr, position: 'absolute', left: 0, top: 0 }} />
                  <View style={{ ...styles.progressBar, backgroundColor: theme['c-primary-light-100-alpha-800'], width: `${dragProgress * 100}%`, position: 'absolute', left: 0, top: 0 }} />
                </>
              ) : (
                <View style={{ ...styles.progressBar, backgroundColor: theme['c-primary-alpha-900'], width: progressStr, position: 'absolute', left: 0, top: 0 }} />
              )
        }
      </View>
      <PreassBar onDragState={setDraging} setDragProgress={setDragProgress} onSetProgress={onSetProgress} />
      {/* TV 遥控器：进度条可聚焦，左右方向键步进播放进度，聚焦时显示高亮块 */}
      { isFocused ? <View style={[styles.tvFocusBg, { backgroundColor: theme['c-primary-light-100-alpha-700'], borderColor: theme['c-primary'] }]} /> : null }
      <View
        nativeID={nativeID}
        focusable={true}
        onFocus={() => { setIsFocused(true) }}
        onBlur={() => { setIsFocused(false) }}
        style={styles.tvKeyArea}
      />
      {/* <View style={{ ...styles.progressBar, height: '100%', width: progressStr }}><Pressable style={styles.progressDot}></Pressable></View> */}
    </View>
  )
}


// const progressContentPadding = 9
// const progressHeight = 3
const styles = createStyle({
  progress: {
    flex: 1,
    // backgroundColor: 'rgba(0,0,0,0.2)',
    zIndex: 1,
  },
  progressBar: {
    height: '100%',
    borderRadius: 3,
  },
  pressBar: {
    position: 'absolute',
    // backgroundColor: 'rgba(0,0,0,0.5)',
    left: 0,
    top: 0,
    // height: progressContentPadding * 2 + progressHeight,
    height: '100%',
    width: '100%',
    zIndex: 2,
  },
  tvKeyArea: {
    position: 'absolute',
    left: 0,
    top: 0,
    height: '100%',
    width: '100%',
  },
  tvFocusBg: {
    position: 'absolute',
    left: -6,
    right: -6,
    top: -6,
    bottom: -6,
    borderRadius: 6,
    borderWidth: 2,
    zIndex: 99,
  },
})

export default Progress
