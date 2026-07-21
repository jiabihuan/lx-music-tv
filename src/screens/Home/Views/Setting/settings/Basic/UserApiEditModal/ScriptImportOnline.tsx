import { useRef, useImperativeHandle, forwardRef, useState } from 'react'
import ConfirmAlert, { type ConfirmAlertType } from '@/components/common/ConfirmAlert'
import Text from '@/components/common/Text'
import { View } from 'react-native'
import Input, { type InputType } from '@/components/common/Input'
import Button from '@/components/common/Button'
import { createStyle, toast, TEMP_FILE_PATH } from '@/utils/tools'
import { useTheme } from '@/store/theme/hook'
import { useI18n } from '@/lang'
import { httpFetch } from '@/utils/request'
import { handleImportScript } from './action'
import { selectFile, unlink } from '@/utils/fs'
import { decodeQrFromFile } from '@/utils/qrDecode'

interface UrlInputType {
  setText: (text: string) => void
  getText: () => string
  focus: () => void
}
const UrlInput = forwardRef<UrlInputType, {}>((props, ref) => {
  const theme = useTheme()
  const [text, setText] = useState('')
  const [placeholder, setPlaceholder] = useState('')
  const inputRef = useRef<InputType>(null)

  useImperativeHandle(ref, () => ({
    getText() {
      return text.trim()
    },
    setText(text) {
      setText(text)
      setPlaceholder(global.i18n.t('user_api_btn_import_online_input_tip'))
    },
    focus() {
      inputRef.current?.focus()
    },
  }))

  return (
    <Input
      ref={inputRef}
      placeholder={placeholder}
      value={text}
      onChangeText={setText}
      style={{ ...styles.input, backgroundColor: theme['c-primary-input-background'] }}
    />
  )
})


export interface ScriptImportOnlineType {
  show: () => void
}


export default forwardRef<ScriptImportOnlineType, {}>((props, ref) => {
  const t = useI18n()
  const theme = useTheme()
  const alertRef = useRef<ConfirmAlertType>(null)
  const urlInputRef = useRef<UrlInputType>(null)
  const [visible, setVisible] = useState(false)
  const [btn, setBtn] = useState({ disabled: false, text: t('user_api_btn_import_online_input_confirm') })

  const handleShow = () => {
    alertRef.current?.setVisible(true)
    setBtn({ disabled: false, text: t('user_api_btn_import_online_input_confirm') })
    requestAnimationFrame(() => {
      urlInputRef.current?.setText('')
      setTimeout(() => {
        urlInputRef.current?.focus()
      }, 300)
    })
  }
  useImperativeHandle(ref, () => ({
    show() {
      if (visible) handleShow()
      else {
        setVisible(true)
        requestAnimationFrame(() => {
          handleShow()
        })
      }
    },
  }))

  const doImport = async(url: string) => {
    setBtn({ disabled: true, text: t('user_api_btn_import_online_input_loading') })
    let script: string
    try {
      script = await httpFetch(url).promise.then(resp => resp.body) as string
    } catch (err: any) {
      toast(t('user_api_import_failed_tip', { message: err.message }), 'long')
      return
    } finally {
      setBtn({ disabled: false, text: t('user_api_btn_import_online_input_confirm') })
    }
    if (script.length > 9_000_000) {
      toast(t('user_api_import_failed_tip', { message: 'Too large script' }), 'long')
      return
    }
    void handleImportScript(script)

    alertRef.current?.setVisible(false)
  }

  const handleImport = async() => {
    let url = urlInputRef.current?.getText() ?? ''
    if (!/^https?:\/\//.test(url)) {
      url = ''
      urlInputRef.current?.setText('')
    }
    if (!url.length) return
    await doImport(url)
  }

  // 本地图片识别二维码（TV 设备没有摄像头时可以用其他设备截图后导入）
  const handleLocalImageQr = async() => {
    try {
      const file = await selectFile({
        extTypes: ['jpg', 'jpeg', 'png', 'webp', 'bmp'],
        toPath: TEMP_FILE_PATH + '_qr',
      })
      if (!file?.data) return
      try {
        const decoded = await decodeQrFromFile(file.data)
        if (!decoded) {
          toast(t('user_api_btn_import_online_qr_scan_no_url'), 'long')
          return
        }
        let url = decoded.trim()
        const urlMatch = url.match(/https?:\/\/[^\s"'<>]+/)
        if (urlMatch) url = urlMatch[0]
        if (!/^https?:\/\//.test(url)) {
          toast(t('user_api_btn_import_online_qr_scan_no_url'), 'long')
          return
        }
        urlInputRef.current?.setText(url)
        toast(t('user_api_btn_import_online_qr_scan_success'), 'short')
      } catch (err: any) {
        toast(t('user_api_btn_import_online_qr_scan_failed', { message: err.message ?? String(err) }), 'long')
      } finally {
        void unlink(file.data).catch(() => {})
      }
    } catch (err: any) {
      // 用户取消选择文件，不提示
    }
  }

  return (
    <>
      {visible
        ? <ConfirmAlert
            ref={alertRef}
            onConfirm={handleImport}
            disabledConfirm={btn.disabled}
            confirmText={btn.text}
          >
            <View style={styles.reurlContent}>
              <Text style={{ marginBottom: 5 }}>{ t('user_api_btn_import_online')}</Text>
              <UrlInput ref={urlInputRef} />
              <View style={styles.btnRow}>
                <Button
                  style={{ ...styles.qrBtn, backgroundColor: theme['c-button-background'], borderColor: theme['c-primary-light-300-alpha-400'] }}
                  onPress={handleLocalImageQr}
                >
                  <Text size={13} color={theme['c-button-font']}>{t('user_api_btn_import_online_qr_local')}</Text>
                </Button>
              </View>
            </View>
          </ConfirmAlert>
        : null}
    </>
  )
})


const styles = createStyle({
  reurlContent: {
    flexGrow: 1,
    flexShrink: 1,
    flexDirection: 'column',
  },
  btnRow: {
    flexDirection: 'row',
    marginTop: 8,
    gap: 8,
  },
  qrBtn: {
    flex: 1,
    padding: 6,
    alignItems: 'center',
    borderRadius: 4,
    borderWidth: 1,
  },
  input: {
    flexGrow: 1,
    flexShrink: 1,
    minWidth: 290,
    borderRadius: 4,
  },
})
