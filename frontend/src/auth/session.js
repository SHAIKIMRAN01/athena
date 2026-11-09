// Where the access token lives in the browser.
//
// sessionStorage, not localStorage: the token is gone when the tab is closed
// and is not shared with other tabs. It is not a cookie, so other websites
// cannot make the browser send it (no CSRF). The strict Content-Security-Policy
// from the backend blocks injected scripts that could read it (XSS).

const STORAGE_KEY = 'athena.session'

/** Seconds since 1970 when the token expires, read from its payload (not verified: the server does that). */
export function tokenExpiry(token) {
  try {
    const payload = token.split('.')[1]
    const json = atob(payload.replaceAll('-', '+').replaceAll('_', '/'))
    const { exp } = JSON.parse(json)
    return typeof exp === 'number' ? exp : null
  } catch {
    return null
  }
}

export function saveSession(token) {
  const exp = tokenExpiry(token)
  const session = { token, expiresAt: exp ? exp * 1000 : Date.now() + 15 * 60 * 1000 }
  sessionStorage.setItem(STORAGE_KEY, JSON.stringify(session))
  return session
}

/** The stored session, or null if there is none or it has expired. */
export function loadSession(now = Date.now()) {
  try {
    const session = JSON.parse(sessionStorage.getItem(STORAGE_KEY))
    if (!session?.token || !session.expiresAt) return null
    if (session.expiresAt <= now) {
      clearSession()
      return { expired: true }
    }
    return session
  } catch {
    clearSession()
    return null
  }
}

export function clearSession() {
  sessionStorage.removeItem(STORAGE_KEY)
}
