'use client'

import type { Game } from './useBuddyState'

const NAMES = { profile: 'Profil tech', quiz: 'Vrai ou Faux' }

/** Vue d'une partie de jeu à marqueurs (remplace la conversation à gauche de l'écran). */
export function GameView({ game, waiting, speech }: { game: Game; waiting: boolean; speech: string | null }) {
  const { type, phase, step, total, question, options, answer, correct, score, best } = game
  const quiz = type === 'quiz'

  return (
    <section className="current game" data-type={type} data-phase={phase} aria-live="polite">
      <div className="game-head">
        <h1 className="title game-title">{NAMES[type]}<span className="cursor">_</span></h1>
        <p className="game-meta">
          {phase !== 'result' && <span>&lt;Question {step}/{total}/&gt;</span>}
          {quiz && score !== null && phase !== 'result' && <span>Score {score}</span>}
          {quiz && !!best && <span>Record du jour {best}</span>}
        </p>
      </div>

      {phase === 'result' ? (
        <div className="game-result" key="result">
          <p className="tag">{quiz ? 'Score final' : 'Ton profil'}</p>
          <p className="result-title">{game.resultTitle}</p>
          <p className="result-text">{game.resultText}</p>
        </div>
      ) : (
        <>
          <p className="game-q" key={`q-${step}`}>{question}</p>
          <ul className={`cards ${quiz ? 'two' : 'four'}`}>
            {options.map((o) => (
              <li key={o.card} data-card={o.card} className={answer === o.card ? 'picked' : answer ? 'dim' : ''}>
                <b>{o.card}</b>
                {!quiz && <span>{o.label}</span>}
              </li>
            ))}
          </ul>
          <p className="game-hint">
            {phase === 'feedback' && quiz
              ? (correct ? 'Bonne réponse_' : 'Raté_')
              : waiting ? `<Montre ta carte ${quiz ? 'VRAI ou FAUX' : 'A, B, C ou D'}/>` : ' '}
          </p>
          {phase === 'feedback' && quiz && speech && <p className="game-explain">{speech}</p>}
        </>
      )}
    </section>
  )
}
