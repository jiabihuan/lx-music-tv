import { createContext, useContext, useState, useCallback, useRef, useEffect, type ReactNode } from 'react'
import { View, StyleSheet } from 'react-native'

interface PortalItem {
  id: number
  children: ReactNode
}

interface PortalContextValue {
  addPortal: (children: ReactNode) => number
  removePortal: (id: number) => void
  updatePortal: (id: number, children: ReactNode) => void
}

const PortalContext = createContext<PortalContextValue | null>(null)

export const usePortal = () => {
  const ctx = useContext(PortalContext)
  if (!ctx) throw new Error('usePortal must be used within PortalProvider')
  return ctx
}

export const PortalProvider = ({ children }: { children: ReactNode }) => {
  const [portals, setPortals] = useState<PortalItem[]>([])
  const idRef = useRef(0)

  const addPortal = useCallback((children: ReactNode) => {
    const id = ++idRef.current
    setPortals(prev => [...prev, { id, children }])
    return id
  }, [])

  const removePortal = useCallback((id: number) => {
    setPortals(prev => prev.filter(p => p.id !== id))
  }, [])

  const updatePortal = useCallback((id: number, children: ReactNode) => {
    setPortals(prev => prev.map(p => p.id === id ? { ...p, children } : p))
  }, [])

  return (
    <PortalContext.Provider value={{ addPortal, removePortal, updatePortal }}>
      {children}
      <View style={StyleSheet.absoluteFill} pointerEvents="box-none">
        {portals.map(p => (
          <View key={p.id} style={StyleSheet.absoluteFill} pointerEvents="box-none">
            {p.children}
          </View>
        ))}
      </View>
    </PortalContext.Provider>
  )
}
