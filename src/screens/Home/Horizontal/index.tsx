import { useEffect } from 'react'
import { View, BackHandler, ToastAndroid } from 'react-native'
import Aside from './Aside'
import PlayerBar from '@/components/player/PlayerBar'
import StatusBar from '@/components/common/StatusBar'
import Header from './Header'
import Main from './Main'
import { createStyle } from '@/utils/tools'

const styles = createStyle({
  container: {
    flex: 1,
    flexDirection: 'row',
  },
  content: {
    flex: 1,
    overflow: 'hidden',
  },
})

let lastBackPressed = 0

export default () => {
  useEffect(() => {
    const backAction = () => {
      const now = Date.now()
      if (now - lastBackPressed < 2000) {
        BackHandler.exitApp()
        return true
      }
      lastBackPressed = now
      ToastAndroid.show('再按一次退出应用', ToastAndroid.SHORT)
      return true
    }

    const backHandler = BackHandler.addEventListener('hardwareBackPress', backAction)
    return () => backHandler.remove()
  }, [])

  return (
    <>
      <StatusBar />
      <View style={styles.container}>
        <Aside />
        <View style={styles.content}>
          <Header />
          <Main />
          <PlayerBar isHome />
        </View>
      </View>
    </>
  )
}
