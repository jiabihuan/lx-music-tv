import { memo, useMemo, useEffect, useRef, useCallback, useState } from 'react'
import { View, FlatList, Animated, Easing, type FlatListProps, type LayoutChangeEvent, type NativeSyntheticEvent, type NativeScrollEvent } from 'react-native'
import {
  type Line,
  useLrcPlay,
  useLrcSet,
  useLxLyricPlay,
  useLxLyricLines,
  type LxLyricLine,
  type LxLyricProgress,
} from '@/plugins/lyric'
import { createStyle } from '@/utils/tools'
import { useTheme } from '@/store/theme/hook'
import { useSettingValue } from '@/store/setting/hook'
import { AnimatedColorText } from '@/components/common/Text'
import Text from '@/components/common/Text'
import { setSpText } from '@/utils/pixelRatio'
import playerState from '@/store/player/state'
import { scrollTo } from '@/utils/scroll'
import PlayLine, { type PlayLineType } from '../components/PlayLine'

type FlatListType = FlatListProps<Line>

interface LineProps {
  line: Line
  lineNum: number
  activeLine: number
  onLayout: (lineNum: number, height: number, width: number) => void
  lxLyricLine?: LxLyricLine | null
  lxProgress?: LxLyricProgress | null
}
const LrcLine = memo(({ line, lineNum, activeLine, onLayout, lxLyricLine, lxProgress }: LineProps) => {
  const theme = useTheme()
  const lrcFontSize = useSettingValue('playDetail.vertical.style.lrcFontSize')
  const textAlign = useSettingValue('playDetail.style.align')
  const size = lrcFontSize / 10
  const lineHeight = setSpText(size) * 1.3
  const [lineWidth, setLineWidth] = useState(0)
  const progressAnim = useRef(new Animated.Value(0)).current

  const isActive = activeLine == lineNum

  const colors = useMemo(() => {
    return isActive ? [
      theme['c-primary'],
      theme['c-primary-alpha-200'],
      1,
    ] as const : [
      theme['c-350'],
      theme['c-300'],
      0.6,
    ] as const
  }, [isActive, theme])

  // 进度变化时用 Animated.timing 平滑过渡（33ms 内完成，配合 33ms 计算频率实现 60fps 丝滑效果）
  useEffect(() => {
    if (!isActive || !lxProgress || lxProgress.lineIndex !== lineNum) {
      progressAnim.setValue(0)
      return
    }
    Animated.timing(progressAnim, {
      toValue: lxProgress.lineProgress,
      duration: 33,
      useNativeDriver: false,
      easing: Easing.linear,
    }).start()
  }, [isActive, lxProgress?.lineProgress, lxProgress?.lineIndex, lineNum, progressAnim])

  const handleLayout = ({ nativeEvent }: LayoutChangeEvent) => {
    onLayout(lineNum, nativeEvent.layout.height, nativeEvent.layout.width)
  }

  const handleTextLayout = ({ nativeEvent }: LayoutChangeEvent) => {
    if (nativeEvent.layout.width > lineWidth) {
      setLineWidth(nativeEvent.layout.width)
    }
  }

  // 判断是否启用卡拉OK逐字效果：当前行激活 + 有逐字歌词数据 + 逐字进度匹配当前行
  const enableKaraoke = isActive && lxLyricLine && lxLyricLine.words.length > 1
    && lxProgress && lxProgress.lineIndex === lineNum

  // 卡拉OK效果：底层灰色整行 + 顶层主题色整行（Animated 宽度裁剪，平滑过渡）
  const renderKaraokeLine = () => {
    if (!lxProgress) return null

    return (
      <View style={{ position: 'relative', alignSelf: textAlign === 'center' ? 'center' : (textAlign === 'right' ? 'flex-end' : 'flex-start') }}>
        {/* 底层：灰色文本 */}
        <Text
          size={size}
          color={theme['c-300']}
          style={{
            lineHeight,
            opacity: isActive ? 1 : colors[2],
          }}
          textBreakStrategy="simple"
          onLayout={handleTextLayout}
        >
          {line.text}
        </Text>
        {/* 顶层：主题色文本，用 Animated 宽度裁剪实现平滑卡拉OK效果 */}
        {lineWidth > 0 && (
          <Animated.View
            style={{
              position: 'absolute',
              top: 0,
              left: 0,
              width: progressAnim.interpolate({
                inputRange: [0, 1],
                outputRange: [0, lineWidth],
              }),
              height: '100%',
              overflow: 'hidden',
            }}
          >
            <Text
              size={size}
              color={colors[0]}
              style={{
                lineHeight,
                opacity: isActive ? 1 : colors[2],
              }}
              textBreakStrategy="simple"
            >
              {line.text}
            </Text>
          </Animated.View>
        )}
      </View>
    )
  }

  return (
    <View style={styles.line} onLayout={handleLayout}>
      {enableKaraoke ? (
        renderKaraokeLine()
      ) : (
        <AnimatedColorText style={{
          ...styles.lineText,
          textAlign,
          lineHeight,
        }} textBreakStrategy="simple" color={colors[0]} opacity={colors[2]} size={size}>{line.text}</AnimatedColorText>
      )}
      {
        line.extendedLyrics.map((lrc, index) => {
          return (<AnimatedColorText style={{
            ...styles.lineTranslationText,
            textAlign,
            lineHeight: lineHeight * 0.8,
          }} textBreakStrategy="simple" key={index} color={colors[1]} opacity={colors[2]} size={size * 0.8}>{lrc}</AnimatedColorText>)
        })
      }
    </View>
  )
}, (prevProps, nextProps) => {
  if (prevProps.line !== nextProps.line) return false
  if (prevProps.activeLine !== nextProps.activeLine
    && (nextProps.activeLine === nextProps.lineNum || prevProps.activeLine === prevProps.lineNum)) {
    return false
  }
  // 激活行且逐字进度变化时更新（Animated.timing 内部平滑过渡）
  if (prevProps.activeLine === prevProps.lineNum && nextProps.activeLine === nextProps.lineNum) {
    const prevProgress = prevProps.lxProgress
    const nextProgress = nextProps.lxProgress
    if (prevProgress?.lineProgress !== nextProgress?.lineProgress) {
      return false
    }
  }
  return true
})
const wait = async() => new Promise(resolve => setTimeout(resolve, 100))

export default () => {
  const lyricLines = useLrcSet()
  const { line } = useLrcPlay()
  const lxLyricLines = useLxLyricLines()
  const lxProgress = useLxLyricPlay()
  const flatListRef = useRef<FlatList>(null)
  const playLineRef = useRef<PlayLineType>(null)
  const isPauseScrollRef = useRef(true)
  const scrollTimoutRef = useRef<NodeJS.Timeout | null>(null)
  const delayScrollTimeout = useRef<NodeJS.Timeout | null>(null)
  const lineRef = useRef({ line: 0, prevLine: 0 })
  const isFirstSetLrc = useRef(true)
  const scrollInfoRef = useRef<NativeSyntheticEvent<NativeScrollEvent>['nativeEvent'] | null>(null)
  const listLayoutInfoRef = useRef<{ spaceHeight: number, lineHeights: number[] }>({ spaceHeight: 0, lineHeights: [] })
  const scrollCancelRef = useRef<(() => void) | null>(null)
  const isShowLyricProgressSetting = useSettingValue('playDetail.isShowLyricProgressSetting')

  const handleScrollToActive = (index = lineRef.current.line) => {
    if (index < 0) return
    if (flatListRef.current) {
      if (scrollInfoRef.current && lineRef.current.line - lineRef.current.prevLine == 1) {
        let offset = listLayoutInfoRef.current.spaceHeight
        for (let line = 0; line < index; line++) {
          offset += listLayoutInfoRef.current.lineHeights[line]
        }
        offset += (listLayoutInfoRef.current.lineHeights[line] ?? 0) / 2
        try {
          scrollCancelRef.current = scrollTo(flatListRef.current, scrollInfoRef.current, offset - scrollInfoRef.current.layoutMeasurement.height * 0.42, 600, () => {
            scrollCancelRef.current = null
          })
        } catch {}
      } else {
        if (scrollCancelRef.current) {
          scrollCancelRef.current()
          scrollCancelRef.current = null
        }
        try {
          flatListRef.current.scrollToIndex({
            index,
            animated: true,
            viewPosition: 0.42,
          })
        } catch {}
      }
    }
  }

  const handleScroll = ({ nativeEvent }: NativeSyntheticEvent<NativeScrollEvent>) => {
    scrollInfoRef.current = nativeEvent
    if (isPauseScrollRef.current) {
      playLineRef.current?.updateScrollInfo(nativeEvent)
    }
  }
  const handleScrollBeginDrag = () => {
    isPauseScrollRef.current = true
    playLineRef.current?.setVisible(true)
    if (delayScrollTimeout.current) {
      clearTimeout(delayScrollTimeout.current)
      delayScrollTimeout.current = null
    }
    if (scrollTimoutRef.current) {
      clearTimeout(scrollTimoutRef.current)
      scrollTimoutRef.current = null
    }
    if (scrollCancelRef.current) {
      scrollCancelRef.current()
      scrollCancelRef.current = null
    }
  }

  const onScrollEndDrag = () => {
    if (!isPauseScrollRef.current) return
    if (scrollTimoutRef.current) clearTimeout(scrollTimoutRef.current)
    scrollTimoutRef.current = setTimeout(() => {
      playLineRef.current?.setVisible(false)
      scrollTimoutRef.current = null
      isPauseScrollRef.current = false
      if (!playerState.isPlay) return
      handleScrollToActive()
    }, 3000)
  }


  useEffect(() => {
    return () => {
      if (delayScrollTimeout.current) {
        clearTimeout(delayScrollTimeout.current)
        delayScrollTimeout.current = null
      }
      if (scrollTimoutRef.current) {
        clearTimeout(scrollTimoutRef.current)
        scrollTimoutRef.current = null
      }
    }
  }, [])

  useEffect(() => {
    listLayoutInfoRef.current.lineHeights = []
    lineRef.current.prevLine = 0
    lineRef.current.line = 0
    if (!flatListRef.current) return
    flatListRef.current.scrollToOffset({
      offset: 0,
      animated: false,
    })
    if (!lyricLines.length) return
    playLineRef.current?.updateLyricLines(lyricLines)
    requestAnimationFrame(() => {
      if (isFirstSetLrc.current) {
        isFirstSetLrc.current = false
        setTimeout(() => {
          isPauseScrollRef.current = false
          handleScrollToActive()
        }, 100)
      } else {
        if (delayScrollTimeout.current) clearTimeout(delayScrollTimeout.current)
        delayScrollTimeout.current = setTimeout(() => {
          handleScrollToActive(0)
        }, 100)
      }
    })
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [lyricLines])

  useEffect(() => {
    if (line < 0) return
    lineRef.current.prevLine = lineRef.current.line
    lineRef.current.line = line
    if (!flatListRef.current || isPauseScrollRef.current) return

    if (line - lineRef.current.prevLine != 1) {
      handleScrollToActive()
      return
    }

    delayScrollTimeout.current = setTimeout(() => {
      delayScrollTimeout.current = null
      handleScrollToActive()
    }, 600)
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [line])

  useEffect(() => {
    requestAnimationFrame(() => {
      playLineRef.current?.updateLayoutInfo(listLayoutInfoRef.current)
      playLineRef.current?.updateLyricLines(lyricLines)
    })
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [isShowLyricProgressSetting])

  const handleScrollToIndexFailed: FlatListType['onScrollToIndexFailed'] = (info) => {
    void wait().then(() => {
      handleScrollToActive(info.index)
    })
  }

  const handleLineLayout = useCallback<LineProps['onLayout']>((lineNum, height) => {
    listLayoutInfoRef.current.lineHeights[lineNum] = height
    playLineRef.current?.updateLayoutInfo(listLayoutInfoRef.current)
  }, [])

  const handleSpaceLayout = useCallback(({ nativeEvent }: LayoutChangeEvent) => {
    listLayoutInfoRef.current.spaceHeight = nativeEvent.layout.height
    playLineRef.current?.updateLayoutInfo(listLayoutInfoRef.current)
  }, [])

  const handlePlayLine = useCallback((time: number) => {
    playLineRef.current?.setVisible(false)
    global.app_event.setProgress(time)
  }, [])

  const renderItem: FlatListType['renderItem'] = ({ item, index }) => {
    return (
      <LrcLine
        line={item}
        lineNum={index}
        activeLine={line}
        onLayout={handleLineLayout}
        lxLyricLine={lxLyricLines[index] || null}
        lxProgress={lxProgress}
      />
    )
  }
  const getkey: FlatListType['keyExtractor'] = (item, index) => `${index}${item.text}`

  const spaceComponent = useMemo(() => (
    <View style={styles.space} onLayout={handleSpaceLayout}></View>
  ), [handleSpaceLayout])

  return (
    <>
      <FlatList
        data={lyricLines}
        renderItem={renderItem}
        keyExtractor={getkey}
        style={styles.container}
        ref={flatListRef}
        showsVerticalScrollIndicator={false}
        ListHeaderComponent={spaceComponent}
        ListFooterComponent={spaceComponent}
        onScrollBeginDrag={handleScrollBeginDrag}
        onScrollEndDrag={onScrollEndDrag}
        fadingEdgeLength={100}
        initialNumToRender={Math.max(line + 10, 10)}
        onScrollToIndexFailed={handleScrollToIndexFailed}
        onScroll={handleScroll}
        extraData={lxProgress}
      />
      { isShowLyricProgressSetting ? <PlayLine ref={playLineRef} onPlayLine={handlePlayLine} /> : null }
    </>
  )
}

const styles = createStyle({
  container: {
    flex: 1,
    paddingLeft: 20,
    paddingRight: 20,
  },
  space: {
    paddingTop: '100%',
  },
  line: {
    paddingTop: 10,
    paddingBottom: 10,
  },
  lineText: {
    textAlign: 'center',
  },
  lineTranslationText: {
    textAlign: 'center',
    paddingTop: 5,
  },
})
