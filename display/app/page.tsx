'use client'

import { useEffect, useLayoutEffect, useRef, useState } from 'react'
import { useBuddyState, type BuddyState } from './useBuddyState'
import { GameView } from './GameView'

const STATUS: Record<BuddyState, string> = {
  idle: 'En veille',
  listening: 'Je t’écoute',
  thinking: 'Je réfléchis',
  speaking: 'Je réponds',
  marker: 'Marqueur détecté',
  playing: 'À toi de jouer',
}

// Typo française : espace insécable avant ? ! : ; » pour éviter une ponctuation seule en début de ligne.
const nb = (s: string) => s.replace(/ ([?!:;»])/g, ' $1').replace(/« /g, '« ')

// Baseline : les accolades alternent entre les trois couleurs triptyques.
const VARIANTS = ['tech', 'together', 'tomorrow'] as const
const VARIANT_MS = 5000

/**
 * Réduit la taille du texte jusqu'à ce qu'il tienne dans sa boîte (les réponses
 * de Buddy font de 1 à 4 phrases). La taille max vient du CSS, MIN_PX est le plancher.
 */
const MIN_PX = 20
function useFitText(text: string | null) {
  const ref = useRef<HTMLParagraphElement>(null)
  useLayoutEffect(() => {
    const el = ref.current
    if (!el) return
    const fit = () => {
      el.style.fontSize = ''
      let size = parseFloat(getComputedStyle(el).fontSize)
      while (el.scrollHeight > el.clientHeight + 1 && size > MIN_PX) {
        size = Math.max(MIN_PX, size * 0.92)
        el.style.fontSize = `${size}px`
      }
    }
    fit()
    document.fonts.ready.then(fit)
    window.addEventListener('resize', fit)
    return () => window.removeEventListener('resize', fit)
  }, [text])
  return ref
}

const GREETING = 'Approche-toi et pose-moi une question sur Epitech, les études d’informatique ou les métiers de la tech !'

export default function Page() {
  const { update, history, marker, connected } = useBuddyState()
  const { state } = update
  const thinking = state === 'thinking'
  const [latest, ...older] = history

  // Réflexion : la nouvelle question. Parole : ce que Buddy dit en ce moment (réponse,
  // annonce de jeu…). Sinon : le dernier échange reste affiché.
  const live = state === 'speaking' && !!update.response
  const heard = thinking || live ? update.heard : latest?.heard
  const response = thinking ? null : live ? update.response : (latest?.response ?? GREETING)
  // Pendant la réflexion, le dernier échange quitte la scène : il passe dans l'historique.
  const past = thinking ? history.slice(0, 4) : older.slice(0, 4)
  const responseRef = useFitText(response)

  const [variant, setVariant] = useState(0)
  useEffect(() => {
    const t = setInterval(() => setVariant((v) => (v + 1) % VARIANTS.length), VARIANT_MS)
    return () => clearInterval(t)
  }, [])

  return (
    <div className="screen" data-state={state}>
      <div className="pixels" aria-hidden>
        <i /><i /><i /><i />
      </div>

      <header className="top">
        {/* Logo officiel, non modifié (charte : version blanche sur fond bleu). */}
        <img className="logo" src="/epitech-logo.svg" alt="Epitech" />
        <span className="org">La Réunion</span>
        <span className="event">&lt;Salon de l’Étudiant/&gt;</span>
        <span className={`live ${connected ? 'on' : ''}`}>{connected ? 'En direct' : 'Connexion…'}</span>
      </header>

      <main className="stage">
        {update.game ? (
          <GameView game={update.game} waiting={state === 'playing'} speech={update.response} />
        ) : (
        <section className="current" aria-live="polite">
          <h1 className="title">
            Buddy<span className="cursor">_</span>
          </h1>

          <div className="status-row">
            <div className="status">
              <span className="meter" aria-hidden><b /><b /><b /></span>
              &lt;{STATUS[state]}/&gt;
            </div>
            {marker !== null && <div className="marker">Marqueur n°{marker}_</div>}
          </div>

          {heard && (
            <p className="heard" key={`h-${heard}`}>
              <span className="tag">Question</span>«&nbsp;{nb(heard)}&nbsp;»
            </p>
          )}

          {response ? (
            <p className="response" key={live ? update.timestamp : (latest?.id ?? 'greeting')} ref={responseRef}>{nb(response)}</p>
          ) : (
            <p className="response pending">
              <span className="dots" aria-label="Buddy réfléchit"><i /><i /><i /></span>
            </p>
          )}
        </section>
        )}

        <aside className="history">
          <h2>Historique_</h2>
          {past.length === 0 ? (
            <p className="empty">&lt;Les échanges précédents s’afficheront ici/&gt;</p>
          ) : (
            <ol>
              {past.map((e) => (
                <li key={e.id}>
                  {e.heard && <p className="q">«&nbsp;{nb(e.heard)}&nbsp;»</p>}
                  <p className="a">{nb(e.response)}</p>
                </li>
              ))}
            </ol>
          )}
        </aside>
      </main>

      <footer className="bottom">
        <p className="baseline" data-v={VARIANTS[variant]} aria-label="Tech Together Tomorrow">
          <span className="brace open" aria-hidden>{'{'}</span>
          <span className="lines" aria-hidden>
            <span>&lt;<em data-w="tech">Tech</em></span>
            <span><em data-w="together">Together</em></span>
            <span><em data-w="tomorrow">Tomorrow</em>&gt;</span>
          </span>
          <span className="brace close" aria-hidden>{'}'}</span>
        </p>
        <p className="cta">Parle à Buddy, ou montre-lui une carte JOUER_</p>
      </footer>
    </div>
  )
}
