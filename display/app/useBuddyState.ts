'use client'

import { useEffect, useState } from 'react'

export type BuddyState = 'idle' | 'listening' | 'thinking' | 'speaking' | 'marker'

/** Message diffusé par StateServer.kt (voir broadcastState dans MainActivity). */
export interface BuddyUpdate {
  state: BuddyState
  heard: string | null
  response: string | null
  marker: number | null
  timestamp: number
}

export interface Exchange {
  id: number
  heard: string | null
  response: string
}

const INITIAL: BuddyUpdate = { state: 'idle', heard: null, response: null, marker: null, timestamp: 0 }
const RECONNECT_DELAY_MS = 2000
const HISTORY_SIZE = 6
const MARKER_VISIBLE_MS = 6000

/**
 * Se connecte au StateServer embarqué dans l'app Buddy, garde l'historique
 * des derniers échanges et se reconnecte en boucle si la connexion tombe
 * (l'écran doit survivre à un redémarrage de l'app/robot sans intervention).
 */
export function useBuddyState() {
  const [update, setUpdate] = useState<BuddyUpdate>(INITIAL)
  const [history, setHistory] = useState<Exchange[]>([])
  const [marker, setMarker] = useState<number | null>(null)
  const [connected, setConnected] = useState(false)

  useEffect(() => {
    const host = process.env.NEXT_PUBLIC_WS_HOST || window.location.hostname
    const url = `ws://${host}:8080/ws`
    let socket: WebSocket | null = null
    let reconnectTimer: ReturnType<typeof setTimeout> | undefined
    let cancelled = false

    const connect = () => {
      if (cancelled) return
      socket = new WebSocket(url)
      socket.onopen = () => setConnected(true)
      socket.onerror = () => socket?.close()
      socket.onclose = () => {
        setConnected(false)
        if (!cancelled) reconnectTimer = setTimeout(connect, RECONNECT_DELAY_MS)
      }
      socket.onmessage = (event) => {
        let next: BuddyUpdate
        try {
          next = JSON.parse(event.data)
        } catch {
          return // message malformé, ignoré
        }
        setUpdate(next)
        if (next.marker !== null) setMarker(next.marker)
        const response = next.response?.trim()
        if (next.state === 'speaking' && response) {
          // Dédoublonné par timestamp : le serveur renvoie le dernier état à chaque reconnexion.
          setHistory((h) =>
            h.some((e) => e.id === next.timestamp)
              ? h
              : [{ id: next.timestamp, heard: next.heard, response }, ...h].slice(0, HISTORY_SIZE),
          )
        }
      }
    }

    connect()
    return () => {
      cancelled = true
      clearTimeout(reconnectTimer)
      socket?.close()
    }
  }, [])

  // Le marqueur n'est diffusé qu'un instant : on le garde affiché quelques secondes.
  useEffect(() => {
    if (marker === null) return
    const t = setTimeout(() => setMarker(null), MARKER_VISIBLE_MS)
    return () => clearTimeout(t)
  }, [marker, update.timestamp])

  return { update, history, marker, connected }
}
