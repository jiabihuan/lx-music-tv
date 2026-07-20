import { useEffect, useState } from 'react'
import Lyric, { type Lines } from 'lrc-file-parser'
// import { getStore, subscribe } from '@/store'
export type Line = Lines[number]
type PlayHook = (line: number, text: string) => void
type SetLyricHook = (lines: Lines) => void

/** 单个字的信息 */
export interface LxLyricWord {
  /** 字的内容 */
  text: string
  /** 相对于行开始的偏移（毫秒） */
  offset: number
  /** 持续时间（毫秒） */
  duration: number
}

/** 逐字歌词行 */
export interface LxLyricLine {
  /** 行开始时间（毫秒） */
  time: number
  /** 该行的所有字 */
  words: LxLyricWord[]
  /** 整行文本（用于 fallback 显示） */
  text: string
}

type LxLyricPlayHook = (progress: LxLyricProgress) => void

/** 逐字歌词播放进度 */
export interface LxLyricProgress {
  /** 当前播放时间（毫秒） */
  currentTime: number
  /** 当前行索引 */
  lineIndex: number
  /** 当前行内字的索引（正在播放的字） */
  wordIndex: number
  /** 当前字的播放进度（0~1） */
  wordProgress: number
  /** 当前行的整体播放进度（0~1），用于卡拉OK效果 */
  lineProgress: number
}

const lrcTools = {
  isInited: false,
  lrc: null as Lyric | null,
  currentLineData: { line: 0, text: '' },
  currentLines: [] as Lines,
  playHooks: [] as PlayHook[],
  setLyricHooks: [] as SetLyricHook[],
  isPlay: false,
  isShowTranslation: false,
  isShowRoma: false,
  lyricText: '',
  translationText: '' as string | null | undefined,
  romaText: '' as string | null | undefined,

  // 逐字歌词
  lxlrcText: '' as string,
  lxLyricLines: [] as LxLyricLine[],
  lxLyricPlayHooks: [] as LxLyricPlayHook[],
  lxLyricProgress: {
    currentTime: 0,
    lineIndex: 0,
    wordIndex: 0,
    wordProgress: 0,
    lineProgress: 0,
  } as LxLyricProgress,
  lxLyricRafId: null as number | null,
  lxLyricStartTime: 0,
  lxLyricStartProgress: 0,
  lxLyricLastNotifyTime: 0,
  playbackRate: 1,

  init() {
    if (this.isInited) return
    this.isInited = true
    this.lrc = new Lyric({
      onPlay: this.onPlay.bind(this),
      onSetLyric: this.onSetLyric.bind(this),
      offset: 100, // offset time(ms), default is 150 ms
    })
  },
  onPlay(line: number, text: string) {
    this.currentLineData.line = line
    this.currentLineData.text = text
    for (const hook of this.playHooks) hook(line, text)
  },
  onSetLyric(lines: Lines) {
    this.currentLines = lines
    this.currentLineData.line = 0
    this.currentLineData.text = ''
    for (const hook of this.playHooks) hook(-1, '')
    for (const hook of this.setLyricHooks) hook(lines)
  },
  addPlayHook(hook: PlayHook) {
    this.playHooks.push(hook)
    hook(this.currentLineData.line, this.currentLineData.text)
  },
  removePlayHook(hook: PlayHook) {
    this.playHooks.splice(this.playHooks.indexOf(hook), 1)
  },
  addSetLyricHook(hook: SetLyricHook) {
    this.setLyricHooks.push(hook)
    hook(this.currentLines)
  },
  removeSetLyricHook(hook: SetLyricHook) {
    this.setLyricHooks.splice(this.setLyricHooks.indexOf(hook), 1)
  },
  setLyric() {
    const extendedLyrics = [] as string[]
    if (this.isShowTranslation && this.translationText) extendedLyrics.push(this.translationText)
    if (this.isShowRoma && this.romaText) extendedLyrics.push(this.romaText)
    this.lrc!.setLyric(this.lyricText, extendedLyrics)
  },

  // 解析 LXLyric 格式
  parseLxLyric(lxlrc: string): LxLyricLine[] {
    if (!lxlrc) return []
    const lines = lxlrc.split('\n')
    const result: LxLyricLine[] = []

    const timeTagRxp = /^\[([\d:.]+)\]/
    const wordRxp = /<(\d+),(\d+)>([^<]+)/g

    for (const line of lines) {
      const trimmed = line.trim()
      if (!trimmed) continue
      // 跳过 offset 等元数据行
      if (trimmed.startsWith('[offset')) continue

      const match = timeTagRxp.exec(trimmed)
      if (!match) continue

      const timeStr = match[1]
      const time = this.parseTimeTag(timeStr)
      if (time < 0) continue

      const content = trimmed.replace(timeTagRxp, '')
      const words: LxLyricWord[] = []
      let fullText = ''

      let m: RegExpExecArray | null
      while ((m = wordRxp.exec(content)) !== null) {
        const offset = parseInt(m[1], 10)
        const duration = parseInt(m[2], 10)
        const text = m[3]
        words.push({ text, offset, duration })
        fullText += text
      }

      if (words.length === 0) {
        // 没有逐字信息的行，整行作为一个字
        words.push({ text: content, offset: 0, duration: 0 })
        fullText = content
      }

      result.push({ time, words, text: fullText })
    }

    return result
  },

  parseTimeTag(tag: string): number {
    if (!tag) return -1
    const parts = tag.split(/:|\./)
    while (parts.length < 3) parts.unshift('0')
    const [m, s, ms] = parts
    return parseInt(m, 10) * 60000 + parseInt(s, 10) * 1000 + parseInt(ms, 10)
  },

  // 设置逐字歌词
  setLxLyric(lxlrc: string) {
    this.lxlrcText = lxlrc
    this.lxLyricLines = this.parseLxLyric(lxlrc)
    this.lxLyricProgress = {
      currentTime: 0,
      lineIndex: 0,
      wordIndex: 0,
      wordProgress: 0,
      lineProgress: 0,
    }
    // 通知所有 hook
    for (const hook of this.lxLyricPlayHooks) hook(this.lxLyricProgress)
  },

  // 计算指定时间点的逐字进度
  calcLxLyricProgress(time: number): LxLyricProgress {
    const lines = this.lxLyricLines
    if (lines.length === 0) {
      return { currentTime: time, lineIndex: 0, wordIndex: 0, wordProgress: 0 }
    }

    // 找到当前行
    let lineIndex = 0
    for (let i = lines.length - 1; i >= 0; i--) {
      if (time >= lines[i].time) {
        lineIndex = i
        break
      }
    }

    const line = lines[lineIndex]
    const lineTime = time - line.time
    const words = line.words

    // 找到当前字
    let wordIndex = 0
    let wordProgress = 0

    if (words.length > 0) {
      for (let i = words.length - 1; i >= 0; i--) {
        if (lineTime >= words[i].offset) {
          wordIndex = i
          break
        }
      }

      const word = words[wordIndex]
      if (word.duration > 0) {
        const wordElapsed = lineTime - word.offset
        wordProgress = Math.min(Math.max(wordElapsed / word.duration, 0), 1)
      } else {
        wordProgress = 1
      }

      // 超出最后一个字的时间，进度设为1
      if (wordIndex === words.length - 1 && word.duration > 0) {
        const totalDuration = word.offset + word.duration
        if (lineTime >= totalDuration) {
          wordProgress = 1
        }
      }
    }

    // 计算行级进度（用于卡拉OK效果）
    let lineProgress = 0
    if (words.length > 0) {
      const lastWord = words[words.length - 1]
      const totalDuration = lastWord.offset + lastWord.duration
      if (totalDuration > 0) {
        lineProgress = Math.min(Math.max(lineTime / totalDuration, 0), 1)
      } else {
        lineProgress = 1
      }
    }

    return {
      currentTime: time,
      lineIndex,
      wordIndex,
      wordProgress,
      lineProgress,
    }
  },

  // 启动逐字歌词播放追踪
  startLxLyricPlay(baseTime: number) {
    if (!this.lxLyricLines.length) return

    const startProgress = this.calcLxLyricProgress(baseTime)
    this.lxLyricProgress = startProgress
    this.lxLyricStartTime = Date.now()
    this.lxLyricStartProgress = baseTime
    this.lxLyricLastNotifyTime = 0

    const tick = () => {
      const elapsed = (Date.now() - this.lxLyricStartTime) * this.playbackRate
      const currentTime = this.lxLyricStartProgress + elapsed
      const progress = this.calcLxLyricProgress(currentTime)
      this.lxLyricProgress = progress
      // 降低通知频率到每 50ms 一次，减少 setState 次数，避免旧设备卡顿
      if (elapsed - this.lxLyricLastNotifyTime >= 50) {
        this.lxLyricLastNotifyTime = elapsed
        for (const hook of this.lxLyricPlayHooks) hook(progress)
      }
      this.lxLyricRafId = requestAnimationFrame(tick)
    }

    if (this.lxLyricRafId !== null) {
      cancelAnimationFrame(this.lxLyricRafId)
    }
    this.lxLyricRafId = requestAnimationFrame(tick)
  },

  // 暂停逐字歌词播放追踪
  pauseLxLyricPlay() {
    if (this.lxLyricRafId !== null) {
      cancelAnimationFrame(this.lxLyricRafId)
      this.lxLyricRafId = null
    }
  },

  addLxLyricPlayHook(hook: LxLyricPlayHook) {
    this.lxLyricPlayHooks.push(hook)
    hook(this.lxLyricProgress)
  },

  removeLxLyricPlayHook(hook: LxLyricPlayHook) {
    const idx = this.lxLyricPlayHooks.indexOf(hook)
    if (idx >= 0) this.lxLyricPlayHooks.splice(idx, 1)
  },

  setPlaybackRate(rate: number) {
    // 更新播放速率，同时更新起始时间以保持进度连续
    if (this.lxLyricRafId !== null) {
      const elapsed = (Date.now() - this.lxLyricStartTime) * this.playbackRate
      this.lxLyricStartProgress += elapsed
      this.lxLyricStartTime = Date.now()
    }
    this.playbackRate = rate
  },
}


export const init = async() => {
  lrcTools.init()
}

export const setLyric = (lyric: string, translation?: string, romalrc?: string, lxlrc?: string) => {
  lrcTools.isPlay = false
  lrcTools.lyricText = lyric
  lrcTools.translationText = translation
  lrcTools.romaText = romalrc
  // 先设置逐字歌词，确保 setLyricHooks 触发时 lxLyricLines 已是最新
  lrcTools.setLxLyric(lxlrc || '')
  lrcTools.setLyric()
  lrcTools.pauseLxLyricPlay()
}
export const setPlaybackRate = (playbackRate: number) => {
  lrcTools.lrc!.setPlaybackRate(playbackRate)
  lrcTools.setPlaybackRate(playbackRate)
}
export const toggleTranslation = (isShow: boolean) => {
  lrcTools.isShowTranslation = isShow
  if (!lrcTools.lyricText) return
  lrcTools.setLyric()
}
export const toggleRoma = (isShow: boolean) => {
  lrcTools.isShowRoma = isShow
  if (!lrcTools.lyricText) return
  lrcTools.setLyric()
}
export const play = (time: number) => {
  lrcTools.isPlay = true
  lrcTools.lrc!.play(time)
  lrcTools.startLxLyricPlay(time)
}
export const pause = () => {
  lrcTools.isPlay = false
  lrcTools.lrc!.pause()
  lrcTools.pauseLxLyricPlay()
}

// on lyric play hook
export const useLrcPlay = (autoUpdate = true) => {
  const [lrcInfo, setLrcInfo] = useState(lrcTools.currentLineData)
  useEffect(() => {
    if (!autoUpdate) return
    const setLrcCallback: SetLyricHook = () => {
      setLrcInfo({ line: 0, text: '' })
    }
    const playCallback: PlayHook = (line, text) => {
      setLrcInfo({ line, text })
    }
    lrcTools.addSetLyricHook(setLrcCallback)
    lrcTools.addPlayHook(playCallback)
    setLrcInfo(lrcTools.currentLineData)
    return () => {
      lrcTools.removeSetLyricHook(setLrcCallback)
      lrcTools.removePlayHook(playCallback)
    }
  }, [autoUpdate])

  return lrcInfo
}

// on lyric set hook
export const useLrcSet = () => {
  const [lines, setLines] = useState<Lines>(lrcTools.currentLines)
  useEffect(() => {
    const callback = (lines: Lines) => {
      setLines(lines)
    }
    lrcTools.addSetLyricHook(callback)
    return () => { lrcTools.removeSetLyricHook(callback) }
  }, [])

  return lines
}

/**
 * 逐字歌词播放进度 hook
 * @returns 当前逐字歌词进度，如果没有逐字歌词则返回 null
 */
export const useLxLyricPlay = (): LxLyricProgress | null => {
  const [progress, setProgress] = useState<LxLyricProgress | null>(
    lrcTools.lxLyricLines.length > 0 ? lrcTools.lxLyricProgress : null
  )
  useEffect(() => {
    const hook: LxLyricPlayHook = (p) => {
      setProgress({ ...p })
    }
    lrcTools.addLxLyricPlayHook(hook)
    // 如果有逐字歌词，立即返回当前进度
    if (lrcTools.lxLyricLines.length > 0) {
      setProgress({ ...lrcTools.lxLyricProgress })
    }
    return () => {
      lrcTools.removeLxLyricPlayHook(hook)
    }
  }, [])

  return progress
}

/**
 * 获取逐字歌词行数据
 * @returns 逐字歌词行数组，如果没有逐字歌词返回空数组
 */
export const useLxLyricLines = (): LxLyricLine[] => {
  const [lines, setLines] = useState<LxLyricLine[]>(lrcTools.lxLyricLines)
  useEffect(() => {
    // 监听普通歌词设置事件作为逐字歌词更新的信号
    // （setLxLyric 和 setLyric 是同步调用的）
    const callback: SetLyricHook = () => {
      setLines(lrcTools.lxLyricLines)
    }
    lrcTools.addSetLyricHook(callback)
    // 初始调用一次
    setLines(lrcTools.lxLyricLines)
    return () => { lrcTools.removeSetLyricHook(callback) }
  }, [])

  return lines
}
