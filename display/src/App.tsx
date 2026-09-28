import { useBuddyState } from './useBuddyState'
import './index.css'

const STATE_LABELS: Record<string, string> = {
  idle: 'Prêt',
  listening: 'Je vous écoute…',
  thinking: 'Je réfléchis…',
  speaking: 'Buddy',
  marker: 'Marqueur scanné',
}

function App() {
  const { update, connected } = useBuddyState()
  const { state, heard, response, marker } = update

  return (
    <div className="screen">
      <header className="header">
        <span className="brand">BUDDY</span>
        <span className="subbrand">Epitech La Réunion</span>
        <span className={`dot ${connected ? 'dot-on' : 'dot-off'}`} />
      </header>

      <main className="main">
        <div className={`status status-${state}`}>
          <span className="status-pulse" />
          {STATE_LABELS[state] ?? 'Prêt'}
        </div>

        {marker !== null && state === 'marker' && (
          <div className="marker-badge">Marqueur n°{marker}</div>
        )}

        {heard && <p className="heard">« {heard} »</p>}

        <p className="response">{response ?? 'Posez-moi une question !'}</p>
      </main>
    </div>
  )
}

export default App
