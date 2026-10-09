import { memo, useCallback, useEffect, useRef, useState } from 'react'
import { View } from 'react-native'

import Slider, { type SliderProps as _SliderProps } from '@react-native-community/slider'
import { createStyle } from '@/utils/tools'
import { useTheme } from '@/store/theme/hook'
import { useTvAdjustable } from '@/utils/hooks/useTvAdjustable'

export type SliderProps = Pick<_SliderProps,
'value'
| 'minimumValue'
| 'maximumValue'
| 'onSlidingStart'
| 'onSlidingComplete'
| 'onValueChange'
| 'step'
>

export default memo(({ value, minimumValue, maximumValue, onSlidingStart, onSlidingComplete, onValueChange, step }: SliderProps) => {
  const theme = useTheme()
  const [isFocused, setIsFocused] = useState(false)

  const valueRef = useRef(value)
  useEffect(() => {
    valueRef.current = value
  }, [value])

  const handleValueChange = (value: number) => {
    // 修复当value小于等于minimumValue时，首次调用会传入0的问题
    if (onValueChange && minimumValue != null) onValueChange(Math.max(value, minimumValue))
  }

  // TV 遥控器：左右方向键按 step 步进滑块（原生拦截 D-pad 后经 tvRemoteKey 转发）
  const handleStep = useCallback((direction: 1 | -1) => {
    const min = minimumValue ?? 0
    const max = maximumValue ?? 1
    const stepValue = step ?? 1
    let newValue = valueRef.current ?? 0
    newValue += direction * stepValue
    newValue = Math.min(max, Math.max(min, newValue))
    if (newValue == valueRef.current) return
    valueRef.current = newValue
    onSlidingStart?.(newValue)
    onValueChange?.(newValue)
    onSlidingComplete?.(newValue)
  }, [minimumValue, maximumValue, step, onSlidingStart, onValueChange, onSlidingComplete])

  const nativeID = useTvAdjustable(handleStep)

  return (
    <View style={styles.focusWrap}>
      { isFocused ? <View style={[styles.tvFocusBg, { backgroundColor: theme['c-primary-light-100-alpha-700'], borderColor: theme['c-primary'] }]} /> : null }
      <View nativeID={nativeID} focusable={true} onFocus={() => { setIsFocused(true) }} onBlur={() => { setIsFocused(false) }} style={styles.focusArea}>
        <Slider
          value={value}
          style={styles.slider}
          minimumValue={minimumValue}
          maximumValue={maximumValue}
          minimumTrackTintColor={theme['c-primary-alpha-500']}
          maximumTrackTintColor={theme['c-primary-alpha-500']}
          thumbTintColor={theme['c-primary']}
          onSlidingStart={onSlidingStart}
          onSlidingComplete={onSlidingComplete}
          onValueChange={handleValueChange}
          step={step}
          focusable={false}
        />
      </View>
    </View>
  )
})


const styles = createStyle({
  focusWrap: {
    flexShrink: 0,
    flexGrow: 1,
    justifyContent: 'center',
    paddingHorizontal: 6,
  },
  focusArea: {
    flexShrink: 0,
    flexGrow: 1,
    justifyContent: 'center',
  },
  slider: {
    flexShrink: 0,
    flexGrow: 1,
    // width: '100%',
    // maxWidth: 300,
    height: 40,
    // backgroundColor: '#eee',
  },
  tvFocusBg: {
    position: 'absolute',
    left: 0,
    right: 0,
    top: -6,
    bottom: -6,
    borderRadius: 6,
    borderWidth: 2,
    zIndex: 99,
  },
})
