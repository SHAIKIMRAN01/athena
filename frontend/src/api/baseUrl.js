// Where the API lives, from VITE_API_BASE_URL at build time.
//
//   (not set)                              -> "/api" on the same origin
//                                             (Vite proxy in development, or the
//                                             Spring Boot server serving this app)
//   https://athena-api.onrender.com    -> "https://athena-api.onrender.com/api"
//   https://athena-api.onrender.com/api   (the same; "/api" is added if missing)
//
// Used by the API client at runtime and by vite.config.js to write the
// Content-Security-Policy, so both always agree on the API origin.

export function apiBaseUrl(value) {
  const text = String(value ?? '').trim()
  if (!text) return '/api'
  const base = text.replace(/\/+$/, '')
  return base.endsWith('/api') ? base : `${base}/api`
}

/** The API's origin ("https://host:port"), or null when it is on this site's own origin. */
export function apiOrigin(value) {
  const base = apiBaseUrl(value)
  return /^https?:\/\//i.test(base) ? new URL(base).origin : null
}

/**
 * Refuses an API address the browser would block or that would leak tokens:
 * anything but https, except http on this computer for local testing.
 */
export function checkApiBaseUrl(value) {
  const origin = apiOrigin(value)
  if (!origin) return
  const url = new URL(origin)
  const local = ['localhost', '127.0.0.1', '[::1]'].includes(url.hostname)
  if (url.protocol !== 'https:' && !local) {
    throw new Error(`VITE_API_BASE_URL must use https:// (got ${origin}). Tokens must never travel unencrypted.`)
  }
}
