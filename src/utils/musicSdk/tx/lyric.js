import { httpFetch } from '../../request'
import { b64DecodeUnicode, decodeName } from '../../index'

const parseQrc = qrcStr => {
  qrcStr = qrcStr.replace(/\r/g, '')
  const lines = qrcStr.split('\n')
  const lxlyricLines = []
  const lrcLines = []

  const lineTimeRxp = /^\[(\d+),(\d+)\]/
  const wordRxp = /([^(]+)\((\d+),(\d+)\)/g

  for (let line of lines) {
    line = line.trim()
    if (!line) continue
    if (line.startsWith('[ti:') || line.startsWith('[ar:') || line.startsWith('[al:') || line.startsWith('[by:') || line.startsWith('[offset:')) {
      lxlyricLines.push(line)
      lrcLines.push(line)
      continue
    }

    const result = lineTimeRxp.exec(line)
    if (!result) continue

    const startMs = parseInt(result[1])
    let ms = startMs % 1000
    let time = startMs / 1000
    let m = parseInt(time / 60).toString().padStart(2, '0')
    time %= 60
    let s = parseInt(time).toString().padStart(2, '0')
    const timeTag = `[${m}:${s}.${ms}]`

    const content = line.replace(lineTimeRxp, '')

    const words = []
    let pureText = ''
    let m2
    wordRxp.lastIndex = 0
    while ((m2 = wordRxp.exec(content)) !== null) {
      const text = m2[1] || ''
      const absOffset = parseInt(m2[2])
      const duration = parseInt(m2[3])
      const offset = absOffset - startMs
      words.push({ offset, duration, text })
      pureText += text
    }

    if (pureText) lrcLines.push(`${timeTag}${pureText}`)

    if (words.length > 0) {
      const lxlyricContent = words.map(w => `<${w.offset},${w.duration}>${w.text}`).join('')
      lxlyricLines.push(`${timeTag}${lxlyricContent}`)
    }
  }

  return {
    lyric: lrcLines.join('\n'),
    lxlyric: lxlyricLines.join('\n'),
  }
}

const getLyricFallback = songmid => {
  const requestObj = httpFetch(`https://c.y.qq.com/lyric/fcgi-bin/fcg_query_lyric_new.fcg?songmid=${songmid}&g_tk=5381&loginUin=0&hostUin=0&format=json&inCharset=utf8&outCharset=utf-8&platform=yqq`, {
    headers: {
      Referer: 'https://y.qq.com/portal/player.html',
    },
  })
  requestObj.promise = requestObj.promise.then(({ body }) => {
    if (body.code != 0 || !body.lyric) return Promise.reject(new Error('Get lyric failed'))
    return {
      lyric: decodeName(b64DecodeUnicode(body.lyric)),
      tlyric: body.trans ? decodeName(b64DecodeUnicode(body.trans)) : '',
      rlyric: '',
      lxlyric: '',
    }
  })
  return requestObj
}

export default {
  regexps: {
    matchLrc: /.+"lyric":"([\w=+/]*)".+/,
  },
  getLyric(songmid) {
    const requestObj = httpFetch(`https://www.oiapi.net/api/QQMusicLyric?id=${songmid}&format=qrc&type=json`, {
      headers: {
        'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36',
      },
    })
    requestObj.promise = requestObj.promise.then(({ body }) => {
      if (body.code != 1 || !body.data?.content) {
        return getLyricFallback(songmid).promise
      }
      const qrcContent = body.data.content
      const { lyric, lxlyric } = parseQrc(qrcContent)
      if (!lyric) return getLyricFallback(songmid).promise
      return {
        lyric: decodeName(lyric),
        tlyric: '',
        rlyric: '',
        lxlyric: decodeName(lxlyric),
      }
    }).catch(() => getLyricFallback(songmid).promise)
    return requestObj
  },
  getQrcByKeyword(name, singer) {
    const keyword = encodeURIComponent(`${name} ${singer}`)
    const requestObj = httpFetch(`https://www.oiapi.net/api/QQMusicLyric?keyword=${keyword}&format=qrc&type=json&n=1`, {
      headers: {
        'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36',
      },
    })
    requestObj.promise = requestObj.promise.then(({ body }) => {
      if (body.code != 1 || !body.data?.content) {
        return Promise.reject(new Error('Get QRC lyric failed'))
      }
      const qrcContent = body.data.content
      const { lyric, lxlyric } = parseQrc(qrcContent)
      if (!lyric || !lxlyric) {
        return Promise.reject(new Error('Parse QRC lyric failed'))
      }
      return {
        lyric: decodeName(lyric),
        tlyric: '',
        rlyric: '',
        lxlyric: decodeName(lxlyric),
      }
    })
    return requestObj
  },
}
