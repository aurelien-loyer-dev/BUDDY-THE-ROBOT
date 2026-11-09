import type { NextConfig } from 'next'

// Export 100% statique : la page est embarquée dans l'APK (assets/web/)
// et servie par StateServer.kt, aucun serveur Node ne tourne au salon.
const nextConfig: NextConfig = {
  output: 'export',
  images: { unoptimized: true },
}

export default nextConfig
