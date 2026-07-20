/**
 * QR 码扫描工具：从本地图片文件解码二维码
 *
 * 实现说明：
 * - 使用 react-native-fs 读取图片为 base64
 * - 使用 jpeg-js / pngjs 解码为 RGBA 像素数据
 * - 使用 jsqr 解析二维码
 *
 * 注意：TV 设备通常没有摄像头，所以采用"选择图片"的方式让用户
 * 从本地存储中选择包含二维码的图片进行扫描。
 */

import RNFS from 'react-native-fs'
import jsQR from 'jsqr'
import jpeg from 'jpeg-js'
import { Buffer } from '@craftzdog/react-native-buffer'

/**
 * 将 base64 字符串解码为 Uint8Array
 */
const base64ToUint8Array = (base64: string): Uint8Array => {
  const buffer = Buffer.from(base64, 'base64')
  return new Uint8Array(buffer.buffer, buffer.byteOffset, buffer.byteLength)
}

/**
 * 简易 PNG 解码器（仅支持最常见的 PNG 格式：8-bit RGBA 或 RGB）
 * 对于复杂 PNG（如调色板、16-bit、隔行扫描）会抛出错误
 */
const decodePng = (data: Uint8Array): { width: number, height: number, data: Uint8ClampedArray } => {
  // PNG 签名：89 50 4E 47 0D 0A 1A 0A
  if (data[0] !== 0x89 || data[1] !== 0x50 || data[2] !== 0x4e || data[3] !== 0x47) {
    throw new Error('Not a PNG file')
  }

  let offset = 8
  let width = 0
  let height = 0
  let bitDepth = 0
  let colorType = 0
  let interlaceMethod = 0
  let idatData: number[] = []

  while (offset < data.length) {
    const length = (data[offset] << 24) | (data[offset + 1] << 16) | (data[offset + 2] << 8) | data[offset + 3]
    offset += 4
    const type = String.fromCharCode(data[offset], data[offset + 1], data[offset + 2], data[offset + 3])
    offset += 4

    if (type === 'IHDR') {
      width = (data[offset] << 24) | (data[offset + 1] << 16) | (data[offset + 2] << 8) | data[offset + 3]
      height = (data[offset + 4] << 24) | (data[offset + 5] << 16) | (data[offset + 6] << 8) | data[offset + 7]
      bitDepth = data[offset + 8]
      colorType = data[offset + 9]
      interlaceMethod = data[offset + 12]
    } else if (type === 'IDAT') {
      for (let i = 0; i < length; i++) {
        idatData.push(data[offset + i])
      }
    } else if (type === 'IEND') {
      break
    }

    offset += length + 4 // 跳过数据和 CRC
  }

  if (interlaceMethod !== 0) {
    throw new Error('Interlaced PNG not supported, please use JPEG')
  }
  if (bitDepth !== 8) {
    throw new Error('Only 8-bit PNG supported, please use JPEG')
  }

  // 解压 IDAT 数据（zlib）
  // 使用 pako 解压
  // eslint-disable-next-line @typescript-eslint/no-var-requires
  const pako = require('pako')
  const decompressed = pako.inflate(new Uint8Array(idatData))

  // 计算每个像素的字节数
  let channels = 4
  if (colorType === 0) channels = 1 // 灰度
  else if (colorType === 2) channels = 3 // RGB
  else if (colorType === 6) channels = 4 // RGBA
  else throw new Error(`Unsupported PNG color type: ${colorType}`)

  const rowSize = width * channels + 1 // +1 for filter byte
  const pixels = new Uint8ClampedArray(width * height * 4)

  // 处理滤波器并提取像素
  const prevRow = new Uint8Array(width * channels)
  for (let y = 0; y < height; y++) {
    const rowStart = y * rowSize
    const filter = decompressed[rowStart]
    const row = new Uint8Array(width * channels)

    for (let x = 0; x < width * channels; x++) {
      const cur = decompressed[rowStart + 1 + x]
      const left = x >= channels ? row[x - channels] : 0
      const up = prevRow[x]
      const upLeft = x >= channels ? prevRow[x - channels] : 0

      let val = cur
      switch (filter) {
        case 1: val = (cur + left) & 0xff; break // Sub
        case 2: val = (cur + up) & 0xff; break // Up
        case 3: val = (cur + ((left + up) >> 1)) & 0xff; break // Average
        case 4: val = (cur + paeth(left, up, upLeft)) & 0xff; break // Paeth
      }
      row[x] = val
    }

    // 转换为 RGBA
    for (let x = 0; x < width; x++) {
      const dstIdx = (y * width + x) * 4
      const srcIdx = x * channels
      if (channels === 1) {
        pixels[dstIdx] = pixels[dstIdx + 1] = pixels[dstIdx + 2] = row[srcIdx]
        pixels[dstIdx + 3] = 255
      } else if (channels === 3) {
        pixels[dstIdx] = row[srcIdx]
        pixels[dstIdx + 1] = row[srcIdx + 1]
        pixels[dstIdx + 2] = row[srcIdx + 2]
        pixels[dstIdx + 3] = 255
      } else { // channels === 4
        pixels[dstIdx] = row[srcIdx]
        pixels[dstIdx + 1] = row[srcIdx + 1]
        pixels[dstIdx + 2] = row[srcIdx + 2]
        pixels[dstIdx + 3] = row[srcIdx + 3]
      }
    }

    prevRow.set(row)
  }

  return { width, height, data: pixels }
}

const paeth = (a: number, b: number, c: number): number => {
  const p = a + b - c
  const pa = Math.abs(p - a)
  const pb = Math.abs(p - b)
  const pc = Math.abs(p - c)
  if (pa <= pb && pa <= pc) return a
  if (pb <= pc) return b
  return c
}

/**
 * 从图片文件路径解码二维码
 * @param filePath 本地图片文件路径
 * @returns 解码出的字符串，失败返回 null
 */
export const decodeQrFromFile = async(filePath: string): Promise<string | null> => {
  // 读取文件为 base64
  const base64 = await RNFS.readFile(filePath, 'base64')
  const bytes = base64ToUint8Array(base64)

  let imageData: { width: number, height: number, data: Uint8ClampedArray } | null = null

  // 尝试 JPEG 解码
  try {
    // jpeg-js 需要原始字节数据，不是 base64
    const rawBuffer = Buffer.from(base64, 'base64')
    imageData = jpeg.decode(rawBuffer, { useTArrayArray: true }) as any
  } catch (err) {
    // 不是 JPEG，尝试 PNG
    try {
      imageData = decodePng(bytes)
    } catch (err2) {
      throw new Error('Unsupported image format. Please use JPEG or PNG.')
    }
  }

  if (!imageData) {
    throw new Error('Failed to decode image')
  }

  // 使用 jsqr 解析二维码
  const code = jsQR(imageData.data, imageData.width, imageData.height, {
    inversionAttempts: 'attemptBoth',
  })

  return code?.data ?? null
}
