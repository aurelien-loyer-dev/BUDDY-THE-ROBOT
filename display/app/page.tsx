'use client'

import { useBuddyState, type BuddyState } from './useBuddyState'

const STATUS: Record<BuddyState, string> = {
  idle: 'En veille',
  listening: 'Je t’écoute',
  thinking: 'Je réfléchis',
  speaking: 'Je réponds',
  marker: 'Marqueur détecté',
}

// Typo française : espace insécable avant ? ! : ; » pour éviter une ponctuation seule en début de ligne.
const nb = (s: string) => s.replace(/ ([?!:;»])/g, ' $1').replace(/« /g, '« ')

const GREETING = 'Approche-toi et pose-moi une question sur Epitech, les études d’informatique ou les métiers de la tech !'

export default function Page() {
  const { update, history, marker, connected } = useBuddyState()
  const { state } = update
  const thinking = state === 'thinking'
  const [latest, ...older] = history

  // Pendant la réflexion on montre la nouvelle question ; sinon le dernier échange reste affiché.
  const heard = thinking ? update.heard : latest?.heard
  const response = thinking ? null : (latest?.response ?? GREETING)
  // Pendant la réflexion, le dernier échange quitte la scène : il passe dans l'historique.
  const past = thinking ? history.slice(0, 4) : older.slice(0, 4)

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
            <p className="response" key={latest?.id ?? 'greeting'}>{nb(response)}</p>
          ) : (
            <p className="response pending">
              <span className="dots" aria-label="Buddy réfléchit"><i /><i /><i /></span>
            </p>
          )}
        </section>

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
        <p className="baseline">
          <span className="brace">{'{'}</span>
          <span>&lt;<em className="t1">Tech</em> <em>Together</em> <em>Tomorrow</em>&gt;</span>
          <span className="brace">{'}'}</span>
        </p>
        <p className="cta">Parle fort et clairement, Buddy t’écoute_</p>
      </footer>
    </div>
  )
}
