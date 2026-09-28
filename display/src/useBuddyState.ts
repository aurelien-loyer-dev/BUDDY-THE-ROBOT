import { useEffect, useRef, useState } from 'react'

export type BuddyState = 'idle' | 'listening' | 'thinking' | 'speaking' | 'marker'

export interface BuddyUpdate {
  state: BuddyState
  heard: string | null
  response: string | null
  marker: number | null
  timestamp: number
}

const INITIAL: BuddyUpdate = {
  state: 'idle',
  heard: null,
  response: null,
  marker: null,
  timestamp: 0,
}

const wsHost = import.meta.env.VITE_WS_HOST || window.location.hostname
const WS_URL = `ws://${wsHost}:8080/ws`
const RECONNECT_DELAY_MS = 2000

/**
 * Se connecte au StateServer embarqué dans l'app Buddy et se reconnecte
 * automatiquement en boucle si la connexion tombe (l'écran doit survivre
 * à un redémarrage de l'app/robot sans intervention manuelle).
 */
export function useBuddyState() {
  const [update, setUpdate] = useState<BuddyUpdate>(INITIAL)
  const [connected, setConnected] = useState(false)
  const socketRef = useRef<WebSocket | null>(null)

  useEffect(() => {
    let cancelled = false
    let reconnectTimer: ReturnType<typeof setTimeout>

    const connect = () => {
      if (cancelled) return
      const socket = new WebSocket(WS_URL)
      socketRef.current = socket

      socket.onopen = () => setConnected(true)

      socket.onmessage = (event) => {
        try {
          setUpdate(JSON.parse(event.data))
        } catch {
          // message malformé, ignoré
        }
      }

      socket.onclose = () => {
        setConnected(false)
        if (!cancelled) reconnectTimer = setTimeout(connect, RECONNECT_DELAY_MS)
      }

      socket.onerror = () => socket.close()
    }

    connect()

    return () => {
      cancelled = true
      clearTimeout(reconnectTimer)
      socketRef.current?.close()
    }
  }, [])

  return { update, connected }
}
