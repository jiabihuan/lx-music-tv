import pako from 'pako'

const QQ_KEY = '!@#)(*$%123ZXC!@!@#)(NHL'

const PC1 = [
  57, 49, 41, 33, 25, 17, 9, 1, 58, 50, 42, 34, 26, 18,
  10, 2, 59, 51, 43, 35, 27, 19, 11, 3, 60, 52, 44, 36,
  63, 55, 47, 39, 31, 23, 15, 7, 62, 54, 46, 38, 30, 22,
  14, 6, 61, 53, 45, 37, 29, 21, 13, 5, 28, 20, 12, 4,
]

const PC2 = [
  14, 17, 11, 24, 1, 5, 3, 28, 15, 6, 21, 10, 23, 19,
  12, 4, 26, 8, 16, 7, 27, 20, 13, 2, 41, 52, 31, 37,
  47, 55, 30, 40, 51, 45, 33, 48, 44, 49, 39, 56, 34,
  53, 46, 42, 50, 36, 29, 32,
]

const SHIFTS = [1, 1, 2, 2, 2, 2, 2, 2, 1, 2, 2, 2, 2, 2, 2, 1]

const IP = [
  58, 50, 42, 34, 26, 18, 10, 2, 60, 52, 44, 36, 28, 20,
  12, 4, 62, 54, 46, 38, 30, 22, 14, 6, 64, 56, 48, 40,
  32, 24, 16, 8, 57, 49, 41, 33, 25, 17, 9, 1, 59, 51,
  43, 35, 27, 19, 11, 3, 61, 53, 45, 37, 29, 21, 13, 5,
  63, 55, 47, 39, 31, 23, 15, 7,
]

const FP = [
  40, 8, 48, 16, 56, 24, 64, 32, 39, 7, 47, 15, 55, 23,
  63, 31, 38, 6, 46, 14, 54, 22, 62, 30, 37, 5, 45, 13,
  53, 21, 61, 29, 36, 4, 44, 12, 52, 20, 60, 28, 35, 3,
  43, 11, 51, 19, 59, 27, 34, 2, 42, 10, 50, 18, 58, 26,
  57, 1, 41, 9, 49, 17, 57, 25,
]

const SBOXES = [
  [
    [14, 4, 13, 1, 2, 15, 11, 8, 3, 10, 6, 12, 5, 9, 0, 7],
    [0, 15, 7, 4, 14, 2, 13, 1, 10, 6, 12, 11, 9, 5, 3, 8],
    [4, 1, 14, 8, 13, 6, 2, 11, 15, 12, 9, 7, 3, 10, 5, 0],
    [15, 12, 8, 2, 4, 9, 1, 7, 5, 11, 3, 14, 10, 0, 6, 13],
  ],
  [
    [15, 1, 8, 14, 6, 11, 3, 4, 9, 7, 2, 13, 12, 0, 5, 10],
    [3, 13, 4, 7, 15, 2, 8, 14, 12, 0, 1, 10, 6, 9, 11, 5],
    [0, 14, 7, 11, 10, 4, 13, 1, 5, 8, 12, 6, 9, 3, 2, 15],
    [13, 8, 10, 1, 3, 15, 4, 2, 11, 6, 7, 12, 0, 5, 14, 9],
  ],
  [
    [10, 0, 9, 14, 6, 3, 15, 5, 1, 13, 12, 7, 11, 4, 2, 8],
    [13, 7, 0, 9, 3, 4, 6, 10, 2, 8, 5, 14, 12, 11, 15, 1],
    [13, 6, 4, 9, 8, 15, 3, 0, 11, 1, 2, 12, 5, 10, 14, 7],
    [1, 10, 13, 0, 6, 9, 8, 7, 4, 15, 14, 3, 11, 5, 2, 12],
  ],
  [
    [7, 13, 14, 3, 0, 6, 9, 10, 1, 2, 8, 5, 11, 12, 4, 15],
    [13, 8, 11, 5, 6, 15, 0, 3, 4, 7, 2, 12, 1, 10, 14, 9],
    [10, 6, 9, 0, 12, 11, 7, 13, 15, 1, 3, 14, 5, 2, 8, 4],
    [3, 15, 0, 6, 10, 1, 13, 8, 9, 4, 5, 11, 12, 7, 2, 14],
  ],
  [
    [2, 12, 4, 1, 7, 10, 11, 6, 8, 5, 3, 15, 13, 0, 14, 9],
    [14, 11, 2, 12, 4, 7, 13, 1, 5, 0, 15, 10, 3, 9, 8, 6],
    [4, 2, 1, 11, 10, 13, 7, 8, 15, 9, 12, 5, 6, 3, 0, 14],
    [11, 8, 12, 7, 1, 14, 2, 13, 6, 15, 0, 9, 10, 4, 5, 3],
  ],
  [
    [12, 1, 10, 15, 9, 2, 6, 8, 0, 13, 3, 4, 14, 7, 5, 11],
    [10, 15, 4, 2, 7, 12, 9, 5, 6, 1, 13, 14, 0, 11, 3, 8],
    [9, 14, 15, 5, 2, 8, 12, 3, 7, 0, 4, 10, 1, 13, 11, 6],
    [4, 3, 2, 12, 9, 5, 15, 10, 11, 14, 1, 7, 6, 0, 8, 13],
  ],
  [
    [4, 11, 2, 14, 15, 0, 8, 13, 3, 12, 9, 7, 5, 10, 6, 1],
    [13, 0, 11, 7, 4, 9, 1, 10, 14, 3, 5, 12, 2, 15, 8, 6],
    [1, 4, 11, 13, 12, 3, 7, 14, 10, 15, 6, 8, 0, 5, 9, 2],
    [6, 11, 13, 8, 1, 4, 10, 7, 9, 5, 0, 15, 14, 2, 3, 12],
  ],
  [
    [13, 2, 8, 4, 6, 15, 11, 1, 10, 9, 3, 14, 5, 0, 12, 7],
    [1, 15, 13, 8, 10, 3, 7, 4, 12, 5, 6, 11, 0, 14, 9, 2],
    [7, 11, 4, 1, 9, 12, 14, 2, 0, 6, 10, 13, 15, 3, 5, 8],
    [2, 1, 14, 7, 4, 10, 8, 13, 15, 12, 9, 0, 3, 5, 6, 11],
  ],
]

const E = [
  32, 1, 2, 3, 4, 5, 4, 5, 6, 7, 8, 9, 8, 9, 10, 11,
  12, 13, 12, 13, 14, 15, 16, 17, 16, 17, 18, 19, 20, 21,
  20, 21, 22, 23, 24, 25, 24, 25, 26, 27, 28, 29, 28, 29,
  30, 31, 32, 1,
]

const P = [
  16, 7, 20, 21, 29, 12, 28, 17, 1, 15, 23, 26, 5, 18,
  31, 10, 2, 8, 24, 14, 32, 27, 3, 9, 19, 13, 30, 6,
  22, 11, 4, 25,
]

function permute(input, table) {
  const output = new Array(table.length)
  for (let i = 0; i < table.length; i++) {
    output[i] = input[table[i] - 1]
  }
  return output
}

function leftShift(bits, shift) {
  return bits.slice(shift).concat(bits.slice(0, shift))
}

function generateSubkeys(keyBits) {
  const pc1Result = permute(keyBits, PC1)
  let left = pc1Result.slice(0, 28)
  let right = pc1Result.slice(28, 56)
  const subkeys = []
  for (let i = 0; i < 16; i++) {
    left = leftShift(left, SHIFTS[i])
    right = leftShift(right, SHIFTS[i])
    const combined = left.concat(right)
    subkeys.push(permute(combined, PC2))
  }
  return subkeys
}

function xorBits(a, b) {
  const result = new Array(a.length)
  for (let i = 0; i < a.length; i++) {
    result[i] = a[i] ^ b[i]
  }
  return result
}

function sBoxSubstitution(input) {
  const output = []
  for (let i = 0; i < 8; i++) {
    const block = input.slice(i * 6, (i + 1) * 6)
    const row = block[0] * 2 + block[5]
    const col = block[1] * 8 + block[2] * 4 + block[3] * 2 + block[4]
    const val = SBOXES[i][row][col]
    for (let j = 3; j >= 0; j--) {
      output.push((val >> j) & 1)
    }
  }
  return output
}

function feistel(right, subkey) {
  const expanded = permute(right, E)
  const xored = xorBits(expanded, subkey)
  const sboxed = sBoxSubstitution(xored)
  return permute(sboxed, P)
}

function desDecrypt(block, keyBits) {
  const subkeys = generateSubkeys(keyBits)
  let ip = permute(block, IP)
  let left = ip.slice(0, 32)
  let right = ip.slice(32, 64)
  for (let i = 15; i >= 0; i--) {
    const temp = right.slice()
    right = xorBits(left, feistel(right, subkeys[i]))
    left = temp
  }
  const combined = left.concat(right)
  return permute(combined, FP)
}

function tripleDesDecrypt(block, key1Bits, key2Bits, key3Bits) {
  let result = desDecrypt(block, key1Bits)
  result = desEncrypt(result, key2Bits)
  result = desDecrypt(result, key3Bits)
  return result
}

function desEncrypt(block, keyBits) {
  const subkeys = generateSubkeys(keyBits)
  let ip = permute(block, IP)
  let left = ip.slice(0, 32)
  let right = ip.slice(32, 64)
  for (let i = 0; i < 16; i++) {
    const temp = right.slice()
    right = xorBits(left, feistel(right, subkeys[i]))
    left = temp
  }
  const combined = left.concat(right)
  return permute(combined, FP)
}

function bytesToBits(bytes) {
  const bits = []
  for (let i = 0; i < bytes.length; i++) {
    for (let j = 7; j >= 0; j--) {
      bits.push((bytes[i] >> j) & 1)
    }
  }
  return bits
}

function bitsToBytes(bits) {
  const bytes = new Uint8Array(bits.length / 8)
  for (let i = 0; i < bits.length; i += 8) {
    let byte = 0
    for (let j = 0; j < 8; j++) {
      byte = (byte << 1) | bits[i + j]
    }
    bytes[i / 8] = byte
  }
  return bytes
}

function hexToBytes(hex) {
  const bytes = new Uint8Array(hex.length / 2)
  for (let i = 0; i < hex.length; i += 2) {
    bytes[i / 2] = parseInt(hex.substr(i, 2), 16)
  }
  return bytes
}

function pkcs5Unpad(data) {
  const padLen = data[data.length - 1]
  if (padLen > 0 && padLen <= 8) {
    return data.slice(0, data.length - padLen)
  }
  return data
}

export function decryptQrc(encryptedHex) {
  try {
    const encryptedBytes = hexToBytes(encryptedHex)
    const keyBytes = new TextEncoder().encode(QQ_KEY)
    const key1Bits = bytesToBits(keyBytes.slice(0, 8))
    const key2Bits = bytesToBits(keyBytes.slice(8, 16))
    const key3Bits = bytesToBits(keyBytes.slice(16, 24))

    const decryptedBlocks = []
    for (let i = 0; i < encryptedBytes.length; i += 8) {
      const block = encryptedBytes.slice(i, i + 8)
      const blockBits = bytesToBits(block)
      const decryptedBits = tripleDesDecrypt(blockBits, key1Bits, key2Bits, key3Bits)
      const decryptedBlock = bitsToBytes(decryptedBits)
      decryptedBlocks.push(decryptedBlock)
    }

    let decryptedBytes
    if (decryptedBlocks.length === 1) {
      decryptedBytes = decryptedBlocks[0]
    } else {
      decryptedBytes = new Uint8Array(encryptedBytes.length)
      let offset = 0
      for (const block of decryptedBlocks) {
        decryptedBytes.set(block, offset)
        offset += block.length
      }
    }

    let decompressed
    try {
      decompressed = pako.inflate(decryptedBytes)
    } catch (e) {
      try {
        decompressed = pako.inflateRaw(decryptedBytes)
      } catch (e2) {
        const unpadded = pkcs5Unpad(decryptedBytes)
        try {
          decompressed = pako.inflate(unpadded)
        } catch (e3) {
          decompressed = pako.inflateRaw(unpadded)
        }
      }
    }

    return new TextDecoder('utf-8').decode(decompressed)
  } catch (e) {
    console.warn('QRC decrypt failed:', e)
    return null
  }
}

export function parseQrcXml(xmlText) {
  const lines = []
  const lineRegex = /<line[^>]*>([\s\S]*?)<\/line>/g
  const charRegex = /<char[^>]*begin="(\d+)"[^>]*end="(\d+)"[^>]*>([^<]*)<\/char>/g

  let lineMatch
  while ((lineMatch = lineRegex.exec(xmlText)) !== null) {
    const lineContent = lineMatch[1]
    const words = []
    let fullText = ''
    let lineStartTime = null

    let charMatch
    while ((charMatch = charRegex.exec(lineContent)) !== null) {
      const begin = parseInt(charMatch[1], 10)
      const end = parseInt(charMatch[2], 10)
      const text = charMatch[3] || ''
      if (lineStartTime === null) lineStartTime = begin
      const offset = begin - lineStartTime
      const duration = end - begin
      words.push({ text, offset, duration })
      fullText += text
    }

    if (words.length > 0 && lineStartTime !== null) {
      lines.push({
        time: lineStartTime,
        words,
        text: fullText,
      })
    }
  }

  return lines
}
