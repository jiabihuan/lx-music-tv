import { memo, useEffect } from 'react'
import { View } from 'react-native'

import Text from '@/components/common/Text'
import CheckBox from '@/components/common/CheckBox'
import { useSettingValue } from '@/store/setting/hook'
import { useTheme } from '@/store/theme/hook'
import { updateSetting } from '@/core/common'
import { useI18n } from '@/lang'
import { createStyle } from '@/utils/tools'
import { setFocusBorderColor } from '@/utils/nativeModules/utils'

export const FOCUS_BORDER_COLOR_LIST = [
  'auto',
  'pink',
  'white',
  'black',
  'red',
  'orange',
  'yellow',
  'green',
  'cyan',
  'blue',
] as const

export type FocusBorderColor = typeof FOCUS_BORDER_COLOR_LIST[number]

const FOCUS_BORDER_COLORS: Record<Exclude<FocusBorderColor, 'auto'>, string> = {
  pink: '#FF69B4',
  white: '#FFFFFF',
  black: '#000000',
  red: '#E53935',
  orange: '#FB8C00',
  yellow: '#FDD835',
  green: '#43A047',
  cyan: '#00BCD4',
  blue: '#1E88E5',
}

const useActive = (id: FocusBorderColor) => {
  const x = useSettingValue('theme.focusBorderColor')
  if (id === 'auto') return x === ''
  return x == FOCUS_BORDER_COLORS[id]
}

const Item = ({ id, change }: {
  id: FocusBorderColor
  change: (id: FocusBorderColor) => void
  key?: React.Key
}) => {
  const t = useI18n()
  const theme = useTheme()
  const isActive = useActive(id)
  return (
    <View style={styles.item}>
      <View
        style={{
          width: 18,
          height: 18,
          borderRadius: 9,
          borderWidth: 1.5,
          borderColor: isActive ? theme['c-primary'] : theme['c-border-background'],
          backgroundColor: id == 'auto' ? theme['c-primary'] : FOCUS_BORDER_COLORS[id],
        }}
      />
      {/* eslint-disable-next-line @typescript-eslint/no-unsafe-argument */}
      <CheckBox marginBottom={3} check={isActive} label={t(`setting_theme_focus_border_color_${id}` as any)} onChange={() => { change(id) }} need />
    </View>
  )
}

export default memo(() => {
  const t = useI18n()
  const color = useSettingValue('theme.focusBorderColor')
  const theme = useTheme()
  useEffect(() => {
    if (color) setFocusBorderColor(color)
  }, [color])
  const setColor = (id: FocusBorderColor) => {
    const c = id == 'auto' ? '' : FOCUS_BORDER_COLORS[id]
    updateSetting({ 'theme.focusBorderColor': c })
    setFocusBorderColor(c || theme['c-primary'])
  }

  return (
    <View style={styles.content}>
      <Text size={14}>{t('setting_theme_focus_border_color')}</Text>
      <View style={styles.list}>
        {
          FOCUS_BORDER_COLOR_LIST.map(id => <Item id={id} key={id} change={setColor} />)
        }
      </View>
    </View>
  )
})

const styles = createStyle({
  content: {
    marginTop: 10,
    marginBottom: 15,
  },
  list: {
    marginTop: 8,
  },
  item: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 8,
  },
})
