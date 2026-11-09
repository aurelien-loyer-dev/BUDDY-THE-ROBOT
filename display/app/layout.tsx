import type { Metadata, Viewport } from 'next'
import { Anton, IBM_Plex_Sans, Space_Mono } from 'next/font/google'
import './globals.css'

// Charte Epitech : Anton (titres), IBM Plex Sans (texte), Space Mono (tech).
// next/font les embarque dans l'export : rien à télécharger le jour du salon.
const anton = Anton({ weight: '400', subsets: ['latin'], variable: '--font-title' })
const plex = IBM_Plex_Sans({ weight: ['400', '500', '600'], subsets: ['latin'], variable: '--font-body' })
const mono = Space_Mono({ weight: ['400', '700'], subsets: ['latin'], variable: '--font-mono' })

export const metadata: Metadata = {
  title: 'Buddy — Epitech La Réunion',
  description: 'Transcription en direct des échanges avec Buddy, le robot du stand Epitech.',
}

export const viewport: Viewport = { themeColor: '#013afb' }

export default function RootLayout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="fr" className={`${anton.variable} ${plex.variable} ${mono.variable}`}>
      <body>{children}</body>
    </html>
  )
}
