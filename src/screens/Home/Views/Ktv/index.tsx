import { useCallback, useEffect, useMemo, useRef, useState, memo } from 'react'
import {
  View,
  FlatList,
  StyleSheet,
  useWindowDimensions,
  DeviceEventEmitter,
  type NativeSyntheticEvent,
  type TextInputSubmitEditingEventData,
} from 'react-native'
import Video, { type VideoRef } from 'react-native-video'
import { FocusableTouchableOpacity as TouchableOpacity } from '@/components/tv/FocusableTouchableOpacity'
import Text from '@/components/common/Text'
import { IconMaterial } from '@/components/common/Icon'
import Image from '@/components/common/Image'
import Input, { type InputType } from '@/components/common/Input'
import { useTheme } from '@/store/theme/hook'
import { createStyle, toast, isHorizontalMode } from '@/utils/tools'
import { scaleSizeW } from '@/utils/pixelRatio'
import { BorderWidths } from '@/theme'
import { useBackHandler } from '@/utils/hooks/useBackHandler'
import { setNavActiveId } from '@/core/common'
import { setFullscreenKeyCapture } from '@/utils/nativeModules/utils'
import { adjustVolume } from '@/utils/nativeModules/utils'
import { mvSingers, mvSongs, mvSearch, mvPlayer } from '@/utils/nativeModules/ktvSpider'
import { getSingerAvatar, preloadSingerAvatars } from '@/utils/ktvAvatarCache'
import { getLyricInfo } from '@/core/music'
import { setLyric, play as playLyric, pause as pauseLyric, useLrcSet, useLrcWords, useLrcPlay } from '@/plugins/lyric'
import { getPosition } from '@/plugins/player/utils'
import LrcWord from '@/screens/PlayDetail/components/LrcWord'
import { type Line } from '@/plugins/lyric'

// ============ LRC歌词叠加层组件 ============
/**
 * LRC歌词叠加层：底部固定区域，显示当前播放的歌词。
 * 卡拉OK风格：当前行放大高亮+逐字渐亮，上下文行缩小灰色。
 */
const LrcOverlay = memo(({
  lines,
  wordLinesMap,
  activeLine,
  theme,
  showControls,
}: {
  lines: Line[]
  wordLinesMap: Map<number, import('@/plugins/lyric').Word[]>
  activeLine: number
  theme: ReturnType<typeof useTheme>
  showControls: boolean
}) => {
  const { width: winW } = useWindowDimensions()
  const baseFontSize = Math.max(14, winW * 0.025)
  const activeFontSize = Math.max(22, winW * 0.04)

  // 计算当前行前后各2行的范围
  const startLine = Math.max(0, activeLine - 2)
  const endLine = Math.min(lines.length - 1, activeLine + 2)

  if (lines.length === 0 || activeLine < 0) return null

  return (
    <View style={[styles.lrcOverlay, { bottom: showControls ? 120 : 80 }]}>
      {Array.from({ length: endLine - startLine + 1 }, (_, i) => startLine + i).map((lineNum) => {
        const line = lines[lineNum]
        if (!line) return null
        const isActive = lineNum === activeLine
        const words = wordLinesMap.get(lineNum) ?? []
        return (
          <View
            key={lineNum}
            style={[styles.lrcLine, isActive && styles.lrcLineActive]}
          >
            {words.length > 0
              ? words.map((word, wi) => (
                  <LrcWord
                    key={wi}
                    word={word}
                    active={isActive}
                    size={isActive ? activeFontSize : baseFontSize}
                    color={isActive ? theme['c-primary'] : '#FFFFFF88'}
                    lineHeight={isActive ? activeFontSize * 1.4 : baseFontSize * 1.4}
                  />
                ))
              : (
                  <Text
                    size={isActive ? activeFontSize : baseFontSize}
                    color={isActive ? theme['c-primary'] : '#FFFFFF88'}
                    style={{ lineHeight: isActive ? activeFontSize * 1.4 : baseFontSize * 1.4 }}
                  >
                    {line.text}
                  </Text>
                )
            }
          </View>
        )
      })}
    </View>
  )
})

// ============ 类型 ============
interface MvSong {
  vod_id: string
  vod_name: string
  vod_pic?: string
  vod_remarks?: string
}

// MV播放时携带的音乐信息（用于获取歌词）
interface MvMusicInfo {
  id: string
  name: string
  singer: string
  source: string
  interval?: string | null
  meta?: any
}

// 一级页面 Tab（男歌手/女歌手独立）
const MAIN_TABS = [
  { id: 'singer', name: '男歌手' },
  { id: 'female', name: '女歌手' },
  { id: 'song', name: '歌曲' },
  { id: 'search', name: '搜索' },
] as const
type MainTab = typeof MAIN_TABS[number]['id']

const GOLD = '#F5BE59'
const ACCENT_RED = '#FD3359'

// ============ 歌手头像 ============
/**
 * 歌手头像组件：内存+磁盘缓存，加载前显示占位图标，失败静默。
 * 使用 React.memo 避免不必要重渲染。
 */
const SingerAvatar = memo(({ name, theme }: {
  name: string
  theme: ReturnType<typeof useTheme>
}) => {
  const [pic, setPic] = useState('')

  useEffect(() => {
    if (!name) return
    let alive = true
    void getSingerAvatar(name).then((url) => {
      if (!alive) return
      if (url) setPic(url)
    })
    return () => { alive = false }
  }, [name])

  return (
    <View style={{ ...styles.singerAvatar, backgroundColor: theme['c-primary-light-900-alpha-200'] }}>
      {pic
        ? <Image url={pic} style={styles.singerAvatarImg} resizeMode="cover" />
        : <IconMaterial name="person" size={40} color={theme['c-primary-light-400-alpha-400']} />}
    </View>
  )
})

// ============ 主组件 ============
export default () => {
  const theme = useTheme()
  const { width: winW, height: winH } = useWindowDimensions()
  const horizontalMode = isHorizontalMode(winW, winH)

  // ===== 界面状态 =====
  const [activeTab, setActiveTab] = useState<MainTab>('singer')
  const [singers, setSingers] = useState<MvSong[]>([])
  const [songs, setSongs] = useState<MvSong[]>([])
  const [keyword, setKeyword] = useState('')
  const [searchResult, setSearchResult] = useState<MvSong[]>([])
  const [status, setStatus] = useState<'idle' | 'loading' | 'error'>('idle')
  const [errorMsg, setErrorMsg] = useState('')

  // ===== 二级（歌手全部 MV 列表页） =====
  const [playingSinger, setPlayingSinger] = useState('')
  const [mvList, setMvList] = useState<MvSong[]>([])

  // ===== 全屏播放 =====
  const [fullScreen, setFullScreen] = useState(false)
  const [currentIndex, setCurrentIndex] = useState(-1)
  const [player, setPlayer] = useState<{ url: string, name: string, pic?: string } | null>(null)
  const [paused, setPaused] = useState(false)
  const [progress, setProgress] = useState({ time: 0, duration: 0 })
  const [showControls, setShowControls] = useState(false)
  const [lastCtrlIndex, setLastCtrlIndex] = useState(1)
  const [menuVisible, setMenuVisible] = useState(false)
  // 视频分辨率（onLoad 时取 naturalSize，暂停时左上角展示）
  const [videoSize, setVideoSize] = useState<{ w: number, h: number } | null>(null)
  // 歌词相关
  const musicInfo = useRef<MvMusicInfo | null>(null)
  const lrcLines = useLrcSet()
  const wordLinesMap = useLrcWords()
  const lrcInfo = useLrcPlay()
  const activeLine = lrcInfo.line

  const searchInputRef = useRef<InputType>(null)
  const videoRef = useRef<VideoRef | null>(null)
  const loadingRef = useRef(false)
  const [singerGridW, setSingerGridW] = useState(0)
  const [mvGridW, setMvGridW] = useState(0)

  // 进入搜索 Tab 时，把焦点交给输入框（弹出光标/软键盘），而非外层容器的焦点框
  useEffect(() => {
    if (activeTab == 'search') {
      const t = setTimeout(() => { searchInputRef.current?.focus() }, 150)
      return () => { clearTimeout(t) }
    }
  }, [activeTab])

  // 控制条焦点索引 ref（用于回调中取最新值）
  const lastCtrlIndexRef = useRef(lastCtrlIndex)
  lastCtrlIndexRef.current = lastCtrlIndex

  // 进度 ref（用于 seek 回调中取最新值）
  const progressRef = useRef(progress)
  progressRef.current = progress

  // 左右键快进/快退（秒）
  const SEEK_STEP = 10
  const seekBy = useCallback((delta: number) => {
    const cur = progressRef.current
    const duration = cur.duration || 0
    const target = Math.max(0, Math.min(duration, cur.time + delta))
    if (videoRef.current) videoRef.current.seek(target)
    setProgress(p => ({ ...p, time: target }))
  }, [])

  const gender = activeTab == 'female' ? 2 : 1

  // ===== 歌手列表 =====
  const loadSingers = useCallback(async(g: number) => {
    setStatus('loading')
    try {
      const json = JSON.parse(await mvSingers(g))
      const arr: MvSong[] = Array.isArray(json.list) ? json.list : []
      setSingers(arr)
      setStatus('idle')
      if (arr.length > 0) {
        // 只预取首屏可见的歌手头像（约前 30 个），避免对全部上千歌手并发发起
        // 头像请求拖垮网络与线程池、导致整个软件卡顿；其余按需加载。
        const names = arr.slice(0, 30).map(i => i.vod_name).filter(Boolean)
        // 先入栈预取，再延迟一次确保 UI 线程空闲
        setTimeout(() => { void preloadSingerAvatars(names) }, 50)
      }
    } catch (err) {
      setStatus('error')
      setErrorMsg((err as Error).message ?? String(err))
    }
  }, [])

  useEffect(() => { void loadSingers(gender) }, [gender, loadSingers])

  // ===== 歌曲列表 =====
  const loadSongs = useCallback(async() => {
    setStatus('loading')
    try {
      const json = JSON.parse(await mvSongs('', 1))
      const arr: MvSong[] = Array.isArray(json.list) ? json.list : []
      setSongs(arr)
      setStatus('idle')
    } catch (err) {
      setStatus('error')
      setErrorMsg((err as Error).message ?? String(err))
    }
  }, [])

  // ===== 搜索 =====
  const doSearch = useCallback(async(kw: string) => {
    const k = kw.trim()
    if (!k) return
    setStatus('loading')
    try {
      const json = JSON.parse(await mvSearch(k))
      const arr: MvSong[] = Array.isArray(json.list) ? json.list : []
      setSearchResult(arr)
      setStatus('idle')
    } catch (err) {
      setStatus('error')
      setErrorMsg((err as Error).message ?? String(err))
    }
  }, [])

  const onSubmitSearch = (e: NativeSyntheticEvent<TextInputSubmitEditingEventData>) => {
    void doSearch(e.nativeEvent.text)
  }

  const onSearchBtnPress = () => {
    void doSearch(keyword)
  }

  // ===== 加载歌手全部 MV =====
  const loadMvListForSinger = useCallback(async(singer: string): Promise<MvSong[]> => {
    try {
      const json = JSON.parse(await mvSongs(singer, 1))
      const arr: MvSong[] = Array.isArray(json.list) ? json.list : []
      return arr
    } catch (err) {
      toast(`加载失败：${(err as Error).message ?? err}`)
      return []
    }
  }, [])

  // 从名称提取歌手名
  const getSingerFromName = useCallback((name: string): string => {
    const m = name.split(' - ')[0]?.trim()
    if (m && m.length > 0 && m != name) return m
    return name.split(' ')[0]?.trim() ?? ''
  }, [])

  // 点击歌手 → 进入二级（不自动播放）
  const openSinger = useCallback(async(singer: string) => {
    setPlayingSinger(singer)
    setMvList([])
    setFullScreen(false)
    setPlayer(null)
    setCurrentIndex(-1)
    setPaused(false)
    setProgress({ time: 0, duration: 0 })
    setShowControls(false)
    setMenuVisible(false)
    setVideoSize(null)
    const arr = await loadMvListForSinger(singer)
    setMvList(arr)
  }, [loadMvListForSinger])

  // 播放指定 index
  const playAt = useCallback(async(list: MvSong[], index: number) => {
    if (index < 0 || index >= list.length) return
    if (loadingRef.current) return
    loadingRef.current = true
    setCurrentIndex(index)
    const item = list[index]
    try {
      const json = JSON.parse(await mvPlayer(item.vod_id))
      const url: string = json.url ?? ''
      if (!url) { toast('无法获取播放地址'); return }
      setPaused(false)
      setProgress({ time: 0, duration: 0 })
      setPlayer({ url, name: item.vod_name, pic: item.vod_pic })
      setFullScreen(true)
      setShowControls(false)
      // 加载歌词
      const singerName = getSingerFromName(item.vod_name)
      const info: MvMusicInfo = {
        id: item.vod_id,
        name: item.vod_name,
        singer: singerName,
        source: 'kg',
      }
      musicInfo.current = info
      void getLyricInfo({ musicInfo: info as any }).then((lyricInfo) => {
        const lyric = lyricInfo.lyric ?? ''
        const tlyric = lyricInfo.tlyric ?? ''
        const lxlyric = lyricInfo.lxlyric ?? ''
        setLyric(lyric, tlyric, '', lxlyric)
      }).catch(() => {})
    } catch (err) {
      toast(`播放失败：${(err as Error).message ?? err}`)
    } finally {
      loadingRef.current = false
    }
  }, [getSingerFromName])

  // 点击 MV（二级列表或搜索/歌曲结果）→ 加载该歌手全部 MV 并播放
  const openMv = useCallback(async(song: MvSong) => {
    const singerName = getSingerFromName(song.vod_name)
    if (!singerName) return
    setPlayingSinger(singerName)
    setMvList([])
    const arr = await loadMvListForSinger(singerName)
    setMvList(arr)
    const idx = arr.findIndex(i => i.vod_id === song.vod_id)
    void playAt(arr, Math.max(0, idx))
  }, [loadMvListForSinger, playAt, getSingerFromName])

  const playNext = useCallback(() => { playAt(mvList, currentIndex + 1).catch(() => {}) }, [mvList, currentIndex, playAt])
  const playPrev = useCallback(() => { playAt(mvList, currentIndex - 1).catch(() => {}) }, [mvList, currentIndex, playAt])

  const onEnd = useCallback(() => { playNext() }, [playNext])

  // 返回键：菜单 → 全屏 → 二级 → 退出 Ktv 回主页
  const handleBack = useCallback((): boolean => {
    if (menuVisible) { setMenuVisible(false); return true }
    if (fullScreen) { setFullScreen(false); setPlayer(null); setShowControls(false); setVideoSize(null); return true }
    if (playingSinger) { setPlayingSinger(''); setMvList([]); return true }
    setNavActiveId('nav_search')
    return true
  }, [menuVisible, fullScreen, playingSinger])

  useBackHandler(useCallback(() => handleBack(), [handleBack]))

  // ===== 全屏播放遥控器按键拦截 =====
  // 交互：
  //   - OK/Enter → 暂停/播放（控制条隐藏时）
  //   - 上键     → 上一曲（控制条隐藏时，有上一首才响应）
  //   - 下键     → 下一曲（控制条隐藏时，有下一首才响应）
  //   - 菜单键   → 呼出歌曲选择菜单
  //   - 音量键   → 调节系统音量
  //   - 控制条显示时关闭拦截，恢复系统焦点导航
  const keyCaptureOn = fullScreen && !menuVisible && !showControls
  useEffect(() => {
    setFullscreenKeyCapture(keyCaptureOn)
    return () => { setFullscreenKeyCapture(false) }
  }, [keyCaptureOn])

  useEffect(() => {
    if (!keyCaptureOn) return
    const listener = DeviceEventEmitter.addListener('tvRemoteKey', (_event: any) => {
      const event = typeof _event === 'object' ? _event : {}
      const code = event.keyCode
      if (code === 23 || code === 66) {
        // OK / Enter → 暂停/播放（无论控制条是否显示）
        setPaused(p => !p)
      } else if (code === 82) {
        // MENU → 切换歌曲选择菜单
        setMenuVisible(v => !v)
      } else if (code === 19) {
        // DPAD_UP → 上一曲（有上一首才响应）
        if (currentIndex > 0) playPrev()
      } else if (code === 20) {
        // DPAD_DOWN → 下一曲（有下一首才响应）
        if (currentIndex < mvList.length - 1) playNext()
      } else if (code === 21) {
        // DPAD_LEFT → 快退 10s
        seekBy(-SEEK_STEP)
      } else if (code === 22) {
        // DPAD_RIGHT → 快进 10s
        seekBy(SEEK_STEP)
      } else if (code === 24 || code === 25 || code === 164) {
        // 音量键：向上调大，向下调小，MUTE静音切换
        if (code === 24) adjustVolume(1)
        else if (code === 25) adjustVolume(-1)
        else {
          // MUTE键：切换静音
          adjustVolume(0)
        }
      }
    })
    return () => { listener.remove() }
  }, [keyCaptureOn, currentIndex, mvList.length, playPrev, playNext, seekBy])

  const videoSource = useMemo(() => {
    if (!player) return null
    return { uri: player.url }
  }, [player])

  const progressPct = progress.duration > 0 ? Math.min(100, (progress.time / progress.duration) * 100) : 0
  const fmt = (s: number) => {
    s = Math.max(0, Math.floor(s))
    return `${Math.floor(s / 60)}:${String(s % 60).padStart(2, '0')}`
  }

  // ============ 网格列数（自适应） ============
  const calcCols = useCallback((containerWidth: number, cardMinW: number): number => {
    if (containerWidth <= 0) return horizontalMode ? 6 : 3
    return Math.max(1, Math.floor(containerWidth / cardMinW))
  }, [horizontalMode])

  // 歌手宫格固定 6 列
  const SINGER_COLS = 6
  const singerCols = useMemo(() => SINGER_COLS, [])
  const singerItemWidth = useMemo(() => {
    if (singerGridW <= 0) return scaleSizeW(120)
    return Math.floor((singerGridW - 10) / SINGER_COLS)
  }, [singerGridW])

  const mvCols = useMemo(() => calcCols(mvGridW, scaleSizeW(200)), [calcCols, mvGridW])
  const mvItemWidth = useMemo(() => {
    if (mvGridW <= 0) return scaleSizeW(200)
    return Math.floor((mvGridW - 12) / mvCols)
  }, [mvGridW, mvCols])

  // ============ 全屏播放页 ============
  const renderFullScreen = () => (
    <View style={styles.fullScreenContainer}>
      {player && videoSource ? (
        <Video
          key={player.url}
          ref={videoRef}
          source={videoSource}
          style={StyleSheet.absoluteFill}
          resizeMode="contain"
          controls={false}
          paused={paused}
          onLoad={(e: any) => {
            setProgress({ time: 0, duration: e?.duration ?? 0 })
            const ns = e?.naturalSize
            setVideoSize(ns && ns.width > 0 && ns.height > 0 ? { w: ns.width, h: ns.height } : null)
          }}
          onProgress={(e: any) => { setProgress(p => ({ ...p, time: e?.currentTime ?? p.time })) }}
          onEnd={onEnd}
          onError={(e: any) => { toast(`播放出错：${e?.error?.localizedDescription ?? e?.error ?? ''}`) }}
          onPlaybackStateChanged={(e: any) => {
            const isPlaying = e?.isPlaying
            if (isPlaying !== undefined) {
              setPaused(!isPlaying)
              if (isPlaying) playLyric(progress.time)
              else pauseLyric()
            }
          }}
        />
      ) : (
        <View style={StyleSheet.absoluteFill}>
          <View style={styles.videoPlaceholder}>
            <IconMaterial name="video-library" size={64} color="#FFFFFF44" />
            <Text style={styles.videoPlaceholderText} size={16} color="#FFFFFF66">加载中…</Text>
          </View>
        </View>
      )}

      {/* 全屏透明焦点锚点：播放时承载焦点，无任何视觉；OK 键切换暂停
          nativeID 标记后原生侧(MainActivity)跳过为其设置白色焦点前景框 */}
      <TouchableOpacity
        style={StyleSheet.absoluteFill}
        nativeID="tv_no_focus_highlight_fs_anchor"
        focusStyle={styles.fsAnchorFocus}
        onPress={() => { setPaused(p => !p); setShowControls(false) }}
        onFocus={() => { setShowControls(false) }}
      />

      {/* 暂停时居中播放按钮：控制条隐藏时作唯一焦点目标 */}
      {paused && (
        <TouchableOpacity
          style={styles.fsCenterBtn}
          focusStyle={styles.fsCenterFocus}
          hasTVPreferredFocus={!showControls}
          onPress={() => { setPaused(false); setShowControls(false) }}
          onFocus={() => { setShowControls(false) }}
        >
          <IconMaterial name="play-arrow" size={72} color="#FFFFFF" />
        </TouchableOpacity>
      )}

      {/* LRC歌词叠加层：卡拉OK风格，底部固定显示 */}
      {fullScreen && lrcLines.length > 0 && (
        <LrcOverlay
          lines={lrcLines}
          wordLinesMap={wordLinesMap}
          activeLine={activeLine}
          theme={theme}
          showControls={showControls}
        />
      )}

      {/* 暂停时左上角显示视频分辨率 */}
      {paused && videoSize && (
        <View style={styles.fsInfoTag}>
          <IconMaterial name="fullscreen" size={13} color="#FFFFFFBB" />
          <Text style={styles.fsInfoText} size={12} color="#FFFFFF">{videoSize.w}×{videoSize.h}</Text>
        </View>
      )}

      {/* 顶栏：控制条显示时出现 */}
      {showControls && (
        <View style={styles.fsTopBar}>
          <IconMaterial name="person" size={18} color={ACCENT_RED} />
          <Text style={styles.fsSinger} size={15} color="#FFFFFFCC" numberOfLines={1}>{playingSinger}</Text>
          <Text style={styles.fsTitle} size={15} color="#FFFFFF" numberOfLines={1}>{player?.name}</Text>
        </View>
      )}

      {/* 进度条：控制条显示时出现 */}
      {showControls && player && (
        <View style={styles.progressTrack}>
          <View style={{ ...styles.progressFill, width: `${progressPct}%` }} />
        </View>
      )}

      {/* 控制条：始终渲染（opacity 控制显隐），保持布局稳定避免跳变 */}
      <View style={[styles.fsControls, { opacity: showControls ? 1 : 0 }]}>
        {[
          { key: 'prev', icon: 'skip-previous', label: '上一首', onPress: () => { playPrev() } },
          {
            key: 'pause',
            icon: paused ? 'play-arrow' : 'pause',
            label: paused ? '播放' : '暂停',
            onPress: () => { setPaused(p => !p) },
          },
          { key: 'next', icon: 'skip-next', label: '下一首', onPress: () => { playNext() } },
          { key: 'menu', icon: 'queue-music', label: '菜单', onPress: () => { setMenuVisible(true); setShowControls(false) }, menu: true },
        ].map((btn, index) => (
          <TouchableOpacity
            key={btn.key}
            style={{ ...styles.ctrlBtn, ...(btn.menu ? styles.ctrlBtnMenu : {}) }}
            onPress={btn.onPress}
            onFocus={() => { setShowControls(true); setLastCtrlIndex(index) }}
            onBlur={() => { setShowControls(false) }}
            hasTVPreferredFocus={showControls && index == lastCtrlIndex}
            focusable={showControls}
            focusStyle={styles.ctrlFocus}
          >
            <IconMaterial name={btn.icon as any} size={index == 1 ? 30 : 24} color="#FFFFFF" />
            <Text style={styles.ctrlText} size={13}>{btn.label}</Text>
          </TouchableOpacity>
        ))}
        <Text style={styles.ctrlTime} size={13} color="#FFFFFFAA">{fmt(progress.time)}{progress.duration > 0 ? ` / ${fmt(progress.duration)}` : ''}</Text>
      </View>

      {/* 歌曲选择菜单：全屏覆盖，显示当前歌手全部 MV，高亮正在播放的 */}
      {menuVisible && (
        <View style={styles.menuFullPanel}>
          <View style={styles.menuHeader}>
            <IconMaterial name="queue-music" size={18} color={GOLD} />
            <Text style={styles.menuTitle} size={16} color="#FFFFFF">{playingSinger} 的全部歌曲</Text>
            <Text style={styles.menuHint} size={12} color="#FFFFFF77">返回键退出</Text>
          </View>
          <FlatList
            style={styles.menuList}
            data={mvList}
            keyExtractor={(item, index) => `${item.vod_id}_${index}`}
            renderItem={({ item, index }) => {
              const active = index == currentIndex
              return (
                <TouchableOpacity
                  style={{ ...styles.menuRow, ...(active ? styles.menuRowActive : {}) }}
                  focusStyle={styles.rowFocus}
                  onPress={() => { void (async() => { await playAt(mvList, index); setMenuVisible(false) })() }}
                  hasTVPreferredFocus={index == currentIndex || index == 0}
                >
                  <Text style={styles.menuIdx} size={12} color={active ? GOLD : '#FFFFFF77'}>{index + 1}</Text>
                  <Text style={styles.menuRowName} size={14} color={active ? GOLD : '#FFFFFF'} numberOfLines={1}>{item.vod_name}</Text>
                  {item.vod_remarks ? <Text style={styles.menuRowDur} size={12} color="#FFFFFF77">{item.vod_remarks}</Text> : null}
                  {active ? <IconMaterial name="equalizer" size={14} color={GOLD} /> : null}
                </TouchableOpacity>
              )
            }}
            maxToRenderPerBatch={12}
            windowSize={8}
            initialNumToRender={20}
          />
        </View>
      )}
    </View>
  )

  // ============ 二级界面（歌手全部 MV 列表） ============
  const renderPlayPage = () => (
    <View style={styles.container}>
      <View style={styles.topBar}>
        <IconMaterial name="person" size={20} color={ACCENT_RED} />
        <Text style={styles.brand} size={18} numberOfLines={1}>{playingSinger}</Text>
        <View style={styles.orderBtn}><Text style={styles.orderBtnText} size={13} color="#FFFFFF">共 {mvList.length} 首</Text></View>
      </View>
      <View style={styles.body}>
        <FlatList
          key={`mv_${mvCols}`}
          style={styles.gridList}
          data={mvList}
          numColumns={mvCols}
          keyExtractor={(item, index) => `${item.vod_id}_${index}`}
          renderItem={({ item, index }) => (
            <View style={{ ...styles.mvCard, width: mvItemWidth }}>
              <TouchableOpacity style={styles.mvCardTouch} focusStyle={styles.cardFocus} onPress={() => { void playAt(mvList, index) }} hasTVPreferredFocus={index == 0}>
                <View style={{ ...styles.mvThumb, backgroundColor: theme['c-primary-light-900-alpha-200'] }}>
                  {item.vod_pic ? <Image url={item.vod_pic} style={styles.mvThumbImg} resizeMode="cover" /> : <IconMaterial name="movie" size={30} color={theme['c-primary-light-400-alpha-300']} />}
                  {item.vod_remarks ? <Text style={styles.mvDuration} size={11} color="#FFFFFF">{item.vod_remarks}</Text> : null}
                </View>
                <Text style={styles.mvName} size={12} color={theme['c-font']} numberOfLines={1}>{item.vod_name}</Text>
              </TouchableOpacity>
            </View>
          )}
          columnWrapperStyle={styles.gridRow}
          maxToRenderPerBatch={6}
          windowSize={6}
          removeClippedSubviews={true}
          onLayout={(e) => { setMvGridW(e.nativeEvent.layout.width) }}
          initialNumToRender={12}
          ListEmptyComponent={<Text style={styles.tip} size={14} color="#FFFFFF77">暂无 MV</Text>}
        />
      </View>
    </View>
  )

  // ============ 歌手卡片 ============
  const renderSingerItem = ({ item }: { item: MvSong }) => (
    <View style={{ ...styles.singerCard, width: singerItemWidth }}>
      <TouchableOpacity
        style={styles.singerCardTouch}
        focusStyle={styles.cardFocus}
        onPress={() => { void openSinger(item.vod_name) }}
      >
        <SingerAvatar name={item.vod_name} theme={theme} />
        <Text style={styles.singerName} size={12} color={theme['c-font']} numberOfLines={1}>{item.vod_name}</Text>
      </TouchableOpacity>
    </View>
  )

  const renderSingerTab = () => (
    <View style={styles.contentCol}>
      <View style={styles.listWrap}>
        <FlatList
          key={`singer_${gender}_${singerCols}`}
          style={styles.gridList}
          data={singers}
          numColumns={singerCols}
          keyExtractor={(item, index) => `${item.vod_id}_${index}`}
          renderItem={renderSingerItem}
          columnWrapperStyle={styles.gridRow}
          maxToRenderPerBatch={12}
          windowSize={8}
          removeClippedSubviews={true}
          onLayout={(e) => { setSingerGridW(e.nativeEvent.layout.width) }}
          initialNumToRender={24}
          ListEmptyComponent={<Text style={styles.tip} size={14} color="#FFFFFF77">暂无歌手</Text>}
        />
      </View>
    </View>
  )

  // ============ 歌曲 Tab ============
  const renderSongTab = () => (
    <View style={styles.contentCol}>
      <View style={styles.listWrap}>
        <FlatList
          key={`songs_${mvCols}`}
          style={styles.gridList}
          data={songs}
          numColumns={mvCols}
          keyExtractor={(item, index) => `${item.vod_id}_${index}`}
          renderItem={({ item, index }) => (
            <View style={{ ...styles.mvCard, width: mvItemWidth }}>
              <TouchableOpacity style={styles.mvCardTouch} focusStyle={styles.cardFocus} onPress={() => { void openMv(item) }}>
                <View style={{ ...styles.mvThumb, backgroundColor: theme['c-primary-light-900-alpha-200'] }}>
                  {item.vod_pic ? <Image url={item.vod_pic} style={styles.mvThumbImg} resizeMode="cover" /> : <IconMaterial name="movie" size={30} color={theme['c-primary-light-400-alpha-300']} />}
                  {item.vod_remarks ? <Text style={styles.mvDuration} size={11} color="#FFFFFF">{item.vod_remarks}</Text> : null}
                </View>
                <Text style={styles.mvName} size={12} color={theme['c-font']} numberOfLines={1}>{item.vod_name}</Text>
              </TouchableOpacity>
            </View>
          )}
          columnWrapperStyle={styles.gridRow}
          maxToRenderPerBatch={6}
          windowSize={6}
          removeClippedSubviews={true}
          onLayout={(e) => { setMvGridW(e.nativeEvent.layout.width) }}
          initialNumToRender={12}
          ListEmptyComponent={<Text style={styles.tip} size={14} color="#FFFFFF77">暂无 MV</Text>}
        />
      </View>
    </View>
  )

  // ============ 搜索 Tab ============
  const renderSearchTab = () => (
    <View style={styles.contentCol}>
      <View style={styles.searchBar}>
        <Input
          ref={searchInputRef}
          style={styles.searchInput}
          placeholder="输入歌手或歌名"
          placeholderTextColor="#FFFFFF88"
          value={keyword}
          onChangeText={setKeyword}
          onSubmitEditing={onSubmitSearch}
          returnKeyType="search"
          hasTVPreferredFocus
          clearBtn
        />
        <TouchableOpacity style={styles.searchBtn} onPress={onSearchBtnPress} focusStyle={styles.subTabFocus}>
          <IconMaterial name="search" size={18} color="#FFFFFF" />
          <Text style={styles.tabText} size={13} color="#FFFFFF">搜索</Text>
        </TouchableOpacity>
      </View>
      <View style={styles.listWrap}>
        <FlatList
          style={styles.gridList}
          data={searchResult}
          keyExtractor={(item, index) => `${item.vod_id}_${index}`}
          renderItem={({ item, index }) => (
            <TouchableOpacity style={styles.searchRow} focusStyle={styles.rowFocus} onPress={() => { void openMv(item) }}>
              <IconMaterial name="music-note" size={16} color={GOLD} />
              <Text style={styles.searchRowText} size={14} color="#FFFFFF" numberOfLines={1}>{item.vod_name}</Text>
              <IconMaterial name="chevron-right" size={16} color="#FFFFFF66" />
            </TouchableOpacity>
          )}
          maxToRenderPerBatch={10}
          windowSize={8}
          removeClippedSubviews={true}
          ListEmptyComponent={<Text style={styles.tip} size={14} color="#FFFFFF77">输入关键词搜索歌手 / MV</Text>}
        />
      </View>
    </View>
  )

  // ============ 一级界面 ============
  const renderHome = () => (
    <View style={styles.container}>
      <View style={styles.topBar}>
        <IconMaterial name="video-library" size={22} color={ACCENT_RED} />
        <Text style={styles.brand} size={20}>MV</Text>
      </View>

      <View style={styles.tabBar}>
        {MAIN_TABS.map(({ id, name }, tabIndex) => (
          <TouchableOpacity
            key={id}
            style={{ ...styles.tabItem, ...(activeTab == id ? styles.tabItemActive : {}) }}
            onPress={() => {
              setActiveTab(id)
              if (id == 'song' && songs.length == 0) void loadSongs()
            }}
            hasTVPreferredFocus={tabIndex == 0}
            focusStyle={styles.tabFocus}
          >
            <Text style={styles.tabText} size={14} color={activeTab == id ? '#FFFFFF' : '#FFFFFFAA'}>{name}</Text>
          </TouchableOpacity>
        ))}
      </View>

      <View style={styles.body}>
        {status == 'loading' && <Text style={styles.tip} size={15} color="#FFFFFFAA">加载中…</Text>}
        {status == 'error' && <Text style={styles.tip} size={15} color="#FF6B6B">加载失败：{errorMsg}</Text>}
        {status == 'idle' && (
          <>
            {(activeTab == 'singer' || activeTab == 'female') && renderSingerTab()}
            {activeTab == 'song' && renderSongTab()}
            {activeTab == 'search' && renderSearchTab()}
          </>
        )}
      </View>
    </View>
  )

  return fullScreen
    ? renderFullScreen()
    : (playingSinger ? renderPlayPage() : renderHome())
}

// ============ 样式 ============
const styles = createStyle({
  container: {
    flex: 1,
    flexDirection: 'column',
    backgroundColor: '#0A0C10',
  },
  topBar: {
    flexDirection: 'row',
    alignItems: 'center',
    paddingHorizontal: 20,
    paddingVertical: 14,
    borderBottomWidth: BorderWidths.normal,
    borderBottomColor: '#FFFFFF14',
  },
  brand: {
    marginLeft: 10,
    fontWeight: 'bold',
    color: '#FFFFFF',
    flexShrink: 1,
  },
  orderBtn: {
    flexDirection: 'row',
    alignItems: 'center',
    marginLeft: 'auto',
    paddingHorizontal: 14,
    height: 34,
    borderRadius: 6,
    backgroundColor: '#2A6BE0',
  },
  orderBtnText: {},
  tabBar: {
    flexDirection: 'row',
    alignItems: 'center',
    paddingHorizontal: 20,
    paddingTop: 14,
    paddingBottom: 6,
  },
  tabItem: {
    paddingHorizontal: 22,
    paddingVertical: 9,
    borderRadius: 8,
    marginRight: 12,
    backgroundColor: '#22262F',
  },
  tabItemActive: {
    backgroundColor: ACCENT_RED,
  },
  tabFocus: {
    backgroundColor: '#2A6BE0',
    borderWidth: 0,
    transform: [{ scale: 1.06 }],
  },
  subTabFocus: {
    backgroundColor: '#2A6BE0',
    borderWidth: 0,
    transform: [{ scale: 1.06 }],
  },
  tabText: {},
  body: {
    flex: 1,
    paddingHorizontal: 20,
    paddingTop: 10,
  },
  contentCol: {
    flex: 1,
    flexDirection: 'column',
  },
  listWrap: {
    flex: 1,
  },
  tip: {
    paddingVertical: 30,
    textAlign: 'center',
  },
  gridList: {
    flex: 1,
  },
  gridRow: {
    justifyContent: 'flex-start',
  },
  singerCard: {
    paddingHorizontal: 5,
    paddingBottom: 10,
  },
  singerCardTouch: {
    alignItems: 'center',
    padding: 6,
  },
  cardFocus: {
    backgroundColor: 'transparent',
    borderColor: '#2A6BE0',
    borderWidth: 2.5,
    borderRadius: 12,
    transform: [{ scale: 1.07 }],
  },
  singerAvatar: {
    width: '100%',
    aspectRatio: 1,
    borderRadius: 8,
    alignItems: 'center',
    justifyContent: 'center',
    overflow: 'hidden',
  },
  singerAvatarImg: {
    width: '100%',
    height: '100%',
  },
  singerName: {
    marginTop: 4,
    textAlign: 'center',
  },
  mvCard: {
    paddingHorizontal: 6,
    paddingBottom: 14,
  },
  mvCardTouch: {
    width: '100%',
  },
  mvThumb: {
    width: '100%',
    aspectRatio: 16 / 9,
    borderRadius: 8,
    alignItems: 'center',
    justifyContent: 'center',
    overflow: 'hidden',
  },
  mvThumbImg: {
    width: '100%',
    height: '100%',
  },
  mvName: {
    marginTop: 6,
  },
  mvDuration: {
    position: 'absolute',
    right: 4,
    bottom: 4,
    backgroundColor: 'rgba(0,0,0,0.55)',
    borderRadius: 3,
    paddingHorizontal: 4,
    paddingVertical: 1,
    overflow: 'hidden',
  },
  searchBar: {
    flexDirection: 'row',
    alignItems: 'center',
    marginBottom: 10,
  },
  searchInput: {
    height: 40,
    borderRadius: 8,
    backgroundColor: '#22262F',
    paddingHorizontal: 12,
    color: '#FFFFFF',
    fontSize: 15,
  },
  searchBtn: {
    flexDirection: 'row',
    alignItems: 'center',
    marginLeft: 10,
    paddingHorizontal: 16,
    height: 40,
    borderRadius: 8,
    backgroundColor: '#2A6BE0',
  },
  searchRow: {
    flexDirection: 'row',
    alignItems: 'center',
    paddingVertical: 13,
    paddingHorizontal: 14,
    borderRadius: 8,
    marginBottom: 8,
    backgroundColor: '#161A22',
  },
  rowFocus: {
    backgroundColor: '#2A6BE0',
    borderWidth: 0,
    transform: [{ scale: 1.02 }],
  },
  searchRowText: {
    flexGrow: 1,
    flexShrink: 1,
    marginLeft: 10,
    marginRight: 10,
  },
  fullScreenContainer: {
    flex: 1,
    backgroundColor: '#000000',
  },
  videoPlaceholder: {
    flex: 1,
    alignItems: 'center',
    justifyContent: 'center',
  },
  videoPlaceholderText: {
    marginTop: 12,
  },
  fsTopBar: {
    position: 'absolute',
    top: 0,
    left: 0,
    right: 0,
    flexDirection: 'row',
    alignItems: 'center',
    paddingHorizontal: 18,
    paddingTop: 14,
    paddingBottom: 10,
    backgroundColor: 'rgba(0,0,0,0.55)',
  },
  fsInfoTag: {
    position: 'absolute',
    top: 14,
    left: 18,
    flexDirection: 'row',
    alignItems: 'center',
    paddingHorizontal: 10,
    paddingVertical: 5,
    borderRadius: 6,
    backgroundColor: 'rgba(0,0,0,0.55)',
  },
  fsInfoText: {
    marginLeft: 6,
  },
  fsSinger: {
    marginLeft: 8,
    marginRight: 14,
  },
  fsTitle: {
    flexShrink: 1,
    fontWeight: 'bold',
  },
  fsCenterBtn: {
    position: 'absolute',
    alignSelf: 'center',
    top: '42%',
    width: 90,
    height: 90,
    borderRadius: 45,
    alignItems: 'center',
    justifyContent: 'center',
    backgroundColor: 'rgba(0,0,0,0.45)',
    borderWidth: 2,
    borderColor: '#FFFFFF55',
  },
  fsCenterFocus: {
    backgroundColor: 'rgba(42,107,224,0.85)',
    borderColor: '#FFFFFF',
    borderWidth: 3,
    borderRadius: 45,
    transform: [{ scale: 1.1 }],
  },
  fsAnchorFocus: {
    backgroundColor: 'transparent',
    borderColor: 'transparent',
    borderWidth: 0,
    elevation: 0,
    transform: [{ scale: 1 }],
  },
  progressTrack: {
    position: 'absolute',
    left: 0,
    right: 0,
    bottom: 58,
    height: 3,
    backgroundColor: '#FFFFFF33',
  },
  progressFill: {
    height: 3,
    backgroundColor: GOLD,
  },
  fsControls: {
    position: 'absolute',
    bottom: 0,
    left: 0,
    right: 0,
    flexDirection: 'row',
    alignItems: 'center',
    paddingHorizontal: 14,
    paddingVertical: 10,
    backgroundColor: 'rgba(0,0,0,0.7)',
  },
  ctrlBtn: {
    flexDirection: 'row',
    alignItems: 'center',
    paddingHorizontal: 14,
    paddingVertical: 8,
    borderRadius: 6,
    marginRight: 10,
    backgroundColor: '#161F2A',
  },
  ctrlBtnMenu: {
    marginLeft: 'auto',
  },
  ctrlFocus: {
    backgroundColor: '#2A6BE0',
    borderWidth: 0,
    transform: [{ scale: 1.06 }],
  },
  ctrlText: {
    marginLeft: 6,
    color: '#FFFFFF',
  },
  ctrlTime: {
    marginLeft: 8,
  },
  menuFullPanel: {
    ...StyleSheet.absoluteFillObject,
    zIndex: 10,
    backgroundColor: 'rgba(10,12,16,0.97)',
    flexDirection: 'column',
  },
  menuHeader: {
    flexDirection: 'row',
    alignItems: 'center',
    paddingHorizontal: 24,
    paddingVertical: 16,
    borderBottomWidth: BorderWidths.normal,
    borderBottomColor: '#FFFFFF14',
  },
  menuTitle: {
    flexGrow: 1,
    flexShrink: 1,
    marginLeft: 10,
    fontWeight: 'bold',
  },
  menuHint: {
    marginRight: 4,
  },
  menuList: {
    flex: 1,
    paddingHorizontal: 24,
    paddingVertical: 12,
  },
  menuRow: {
    flexDirection: 'row',
    alignItems: 'center',
    paddingVertical: 11,
    paddingHorizontal: 12,
    borderRadius: 6,
    marginBottom: 4,
    backgroundColor: '#161A22',
  },
  menuRowActive: {
    backgroundColor: '#1E2A3A',
  },
  menuIdx: {
    width: 28,
    textAlign: 'center',
  },
  menuRowName: {
    flexGrow: 1,
    flexShrink: 1,
    marginLeft: 6,
    marginRight: 8,
  },
  menuRowDur: {
    marginRight: 8,
  },
  // LRC歌词叠加层样式
  lrcOverlay: {
    position: 'absolute',
    bottom: 120,
    left: 0,
    right: 0,
    alignItems: 'center',
    zIndex: 10,
  },
  lrcLine: {
    marginBottom: 4,
    paddingHorizontal: 16,
  },
  lrcLineActive: {
    marginBottom: 6,
  },
})
