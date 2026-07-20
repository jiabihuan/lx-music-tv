import { NativeEventEmitter, NativeModules } from 'react-native'

// 原生模块（在 MainApplication.java 中注册的 HttpServerModule）
const HttpServerModule: {
  start(port: number): Promise<number>
  stop(): void
  isRunning(): Promise<boolean>
  getQrCodeBase64(text: string): Promise<string>
} = NativeModules.HttpServerModule

/**
 * 启动 HTTP 服务器
 * @param port 端口号，传 0 由系统自动分配
 * @returns 实际监听的端口号
 */
export const startHttpServer = (port = 0): Promise<number> => {
  return HttpServerModule.start(port)
}

/** 停止 HTTP 服务器 */
export const stopHttpServer = (): void => {
  HttpServerModule.stop()
}

/** 服务器是否正在运行 */
export const isHttpServerRunning = (): Promise<boolean> => {
  return HttpServerModule.isRunning()
}

/**
 * 生成二维码图片（base64 PNG data URI）
 * @param text 二维码内容
 * @returns 形如 "data:image/png;base64,xxxx" 的字符串，可直接作为 Image 的 source.uri
 */
export const getQrCodeBase64 = (text: string): Promise<string> => {
  return HttpServerModule.getQrCodeBase64(text)
}

/**
 * 监听手机扫码推送的 URL
 * @param handler 接收到 URL 时的回调
 * @returns 取消监听函数
 */
export const onHttpServerUrl = (handler: (url: string) => void): (() => void) => {
  // eslint-disable-next-line @typescript-eslint/no-unsafe-argument
  const eventEmitter = new NativeEventEmitter(NativeModules.HttpServerModule)
  const eventListener = eventEmitter.addListener('httpServerUrl', (event: { url: string }) => {
    handler(event.url)
  })

  return () => {
    eventListener.remove()
  }
}
