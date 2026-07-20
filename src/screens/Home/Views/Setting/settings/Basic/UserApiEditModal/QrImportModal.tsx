import { forwardRef, useImperativeHandle, useRef, useState, useEffect, useCallback } from 'react'
import { View, Image, ActivityIndicator } from 'react-native'
import Overlay, { type OverlayType } from '@/components/common/Overlay'
import Button from '@/components/common/Button'
import Text from '@/components/common/Text'
import { Icon } from '@/components/common/Icon'
import { FocusableTouchableOpacity as TouchableOpacity } from '@/components/tv/FocusableTouchableOpacity'
import { createStyle, toast } from '@/utils/tools'
import { useTheme } from '@/store/theme/hook'
import { useI18n } from '@/lang'
import { getWIFIIPV4Address } from '@/utils/nativeModules/utils'
import {
  startHttpServer,
  stopHttpServer,
  getQrCodeBase64,
  onHttpServerUrl,
} from '@/utils/nativeModules/httpServer'

/** 二维码弹窗显示的手机端访问端口（0 = 由系统自动分配） */
const QR_SERVER_PORT = 0
/** 二维码尺寸（px） */
const QR_IMAGE_SIZE = 280

export interface QrImportModalType {
  show: () => void
  hide: () => void
}

interface QrImportModalProps {
  /** 接收到手机推送的 URL 时回调（弹窗会自动关闭） */
  onUrlReceived: (url: string) => void
}

export default forwardRef<QrImportModalType, QrImportModalProps>(({
  onUrlReceived,
}, ref) => {
  const t = useI18n()
  const theme = useTheme()
  const overlayRef = useRef<OverlayType>(null)
  const closeBtnRef = useRef<TouchableOpacity>(null)
  const unsubscribeUrlRef = useRef<(() => void) | null>(null)

  const [loading, setLoading] = useState(true)
  const [qrUri, setQrUri] = useState<string | null>(null)
  const [address, setAddress] = useState<string>('')
  const [error, setError] = useState<string>('')

  const cleanup = useCallback(() => {
    if (unsubscribeUrlRef.current) {
      unsubscribeUrlRef.current()
      unsubscribeUrlRef.current = null
    }
    stopHttpServer()
  }, [])

  const handleHide = useCallback(() => {
    cleanup()
    setQrUri(null)
    setAddress('')
    setError('')
    setLoading(true)
    overlayRef.current?.setVisible(false)
  }, [cleanup])

  const startServer = async() => {
    setLoading(true)
    setError('')
    try {
      // 1. 启动 HTTP 服务器
      const port = await startHttpServer(QR_SERVER_PORT)
      // 2. 获取局域网 IP
      const ip = await getWIFIIPV4Address()
      if (!ip || ip === '0.0.0.0') {
        throw new Error(t('user_api_qr_no_wifi'))
      }
      const url = `http://${ip}:${port}/`
      setAddress(url)
      // 3. 生成二维码
      const uri = await getQrCodeBase64(url)
      setQrUri(uri)
      // 4. 监听手机推送的 URL
      unsubscribeUrlRef.current = onHttpServerUrl((receivedUrl) => {
        // 校验 URL 合法性
        let url = (receivedUrl || '').trim()
        const urlMatch = url.match(/https?:\/\/[^\s"'<>]+/)
        if (urlMatch) url = urlMatch[0]
        if (!/^https?:\/\//.test(url)) {
          toast(t('user_api_qr_invalid_url'), 'long')
          return
        }
        toast(t('user_api_qr_received'), 'short')
        onUrlReceived(url)
        handleHide()
      })
      setLoading(false)
      // 自动聚焦关闭按钮，方便用户用遥控器关闭
      setTimeout(() => {
        const ref = closeBtnRef.current as any
        ref?.focus?.()
      }, 200)
    } catch (err: any) {
      setError(err?.message ?? String(err))
      setLoading(false)
    }
  }

  useImperativeHandle(ref, () => ({
    show() {
      overlayRef.current?.setVisible(true)
      // 延迟启动服务器，等 Overlay 渲染完成
      requestAnimationFrame(() => {
        void startServer()
      })
    },
    hide() {
      handleHide()
    },
  }))

  // 组件卸载时清理
  useEffect(() => {
    return () => {
      cleanup()
    }
  }, [cleanup])

  return (
    <Overlay
      ref={overlayRef}
      onHide={handleHide}
      bgHide={false}
      keyHide={true}
      bgColor='rgba(0,0,0,0.85)'
    >
      <View style={styles.container}>
        <View style={{ ...styles.dialog, backgroundColor: theme['c-content-background'] }}>
          {/* 标题栏 */}
          <View style={{ ...styles.header, backgroundColor: theme['c-primary-light-100-alpha-100'] }}>
            <Text style={styles.title} size={15} color={theme['c-primary-light-1000']}>{t('user_api_qr_title')}</Text>
            <TouchableOpacity
              ref={closeBtnRef}
              style={styles.closeBtn}
              hasTVPreferredFocus
              onPress={handleHide}
            >
              <Icon name='close' color={theme['c-primary-dark-500-alpha-500']} size={14} />
            </TouchableOpacity>
          </View>

          {/* 内容区 */}
          <View style={styles.content}>
            {loading ? (
              <View style={styles.loadingWrap}>
                <ActivityIndicator size='large' color={theme['c-primary']} />
                <Text style={styles.loadingText} color={theme['c-font-label']} size={13}>{t('user_api_qr_loading')}</Text>
              </View>
            ) : error ? (
              <View style={styles.errorWrap}>
                <Icon name='help' color={theme['c-font-label']} size={40} />
                <Text style={styles.errorText} color={theme['c-font-label']} size={14}>{error}</Text>
                <Button style={{ ...styles.retryBtn, backgroundColor: theme['c-button-background'] }} onPress={() => { void startServer() }}>
                  <Text color={theme['c-button-font']}>{t('user_api_qr_retry')}</Text>
                </Button>
              </View>
            ) : (
              <>
                <View style={styles.qrWrap}>
                  {qrUri ? (
                    <Image
                      source={{ uri: qrUri }}
                      style={styles.qrImage}
                      resizeMode='contain'
                    />
                  ) : null}
                </View>
                <Text style={styles.tipTitle} size={15} color={theme['c-primary-font']}>{t('user_api_qr_tip_title')}</Text>
                <Text style={styles.tip} size={13} color={theme['c-font-label']}>{t('user_api_qr_tip')}</Text>
                <View style={styles.addressWrap}>
                  <Text size={12} color={theme['c-font-label']}>{t('user_api_qr_address')}</Text>
                  <Text style={styles.address} size={13} color={theme['c-primary']}>{address}</Text>
                </View>
              </>
            )}
          </View>

          {/* 底部按钮 */}
          <View style={styles.footer}>
            <Button
              style={{ ...styles.footerBtn, backgroundColor: theme['c-button-background'] }}
              onPress={handleHide}
            >
              <Text color={theme['c-button-font']}>{t('close')}</Text>
            </Button>
          </View>
        </View>
      </View>
    </Overlay>
  )
})


const styles = createStyle({
  container: {
    flex: 1,
    justifyContent: 'center',
    alignItems: 'center',
  },
  dialog: {
    width: 420,
    maxWidth: '90%',
    borderRadius: 8,
    elevation: 6,
    overflow: 'hidden',
  },
  header: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    height: 38,
    paddingHorizontal: 12,
  },
  title: {
    fontWeight: 'bold',
  },
  closeBtn: {
    width: 28,
    height: 28,
    borderRadius: 14,
    justifyContent: 'center',
    alignItems: 'center',
  },
  content: {
    padding: 20,
    alignItems: 'center',
    minHeight: 360,
    justifyContent: 'center',
  },
  loadingWrap: {
    alignItems: 'center',
    paddingVertical: 40,
  },
  loadingText: {
    marginTop: 14,
  },
  errorWrap: {
    alignItems: 'center',
    paddingVertical: 20,
  },
  errorText: {
    marginTop: 14,
    marginBottom: 18,
    textAlign: 'center',
    lineHeight: 22,
  },
  retryBtn: {
    paddingVertical: 8,
    paddingHorizontal: 20,
    borderRadius: 4,
  },
  qrWrap: {
    padding: 10,
    backgroundColor: '#fff',
    borderRadius: 4,
    marginBottom: 16,
  },
  qrImage: {
    width: QR_IMAGE_SIZE,
    height: QR_IMAGE_SIZE,
  },
  tipTitle: {
    marginBottom: 8,
    fontWeight: 'bold',
  },
  tip: {
    textAlign: 'center',
    lineHeight: 22,
    marginBottom: 16,
  },
  addressWrap: {
    flexDirection: 'row',
    alignItems: 'center',
    paddingTop: 12,
    borderTopWidth: 1,
    borderTopColor: 'rgba(128,128,128,0.2)',
    flexWrap: 'wrap',
    justifyContent: 'center',
  },
  address: {
    marginLeft: 6,
    fontWeight: 'bold',
  },
  footer: {
    flexDirection: 'row',
    padding: 16,
    paddingTop: 0,
  },
  footerBtn: {
    flex: 1,
    paddingVertical: 12,
    alignItems: 'center',
    borderRadius: 4,
  },
})
