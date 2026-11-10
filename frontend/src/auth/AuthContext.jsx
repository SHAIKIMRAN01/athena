import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState } from 'react'
import { configureAuth } from '../api/client'
import * as athena from '../api/athena'
import { clearSession, loadSession, saveSession } from './session'

const AuthContext = createContext(null)

const ENDED_MESSAGES = {
  TOKEN_EXPIRED: 'Your session expired. Please sign in again.',
  expired: 'Your session expired. Please sign in again.',
  TOKEN_REVOKED: 'You were signed out. Please sign in again.',
  ACCOUNT_DISABLED: 'Your user has been disabled. Contact an administrator.',
  CHECK_FAILED: 'We could not confirm your session with the server. Please sign in again.',
  PASSWORD_CHANGED: 'Your password was changed. Sign in with the new one.',
}

// A stored session is checked with the server when the page loads. When the
// server cannot be reached (a phone between networks, a page reloaded while
// the check was under way), the check is tried again before giving up, so a
// short drop-out never signs anyone out or clears their session.
const SESSION_CHECK_RETRY_DELAYS_MS = [1000, 2000, 4000]

/**
 * Holds the signed-in user and their token.
 *
 * - The token is attached to every API call (see api/client.js).
 * - It is dropped when it expires (a timer fires at its exp time), when
 *   the server answers 401 to it, or on sign-out (which also revokes it on
 *   the server, so a copy of it stops working too).
 * - Roles come from GET /api/users/me, so the menu matches what the server
 *   allows. Hiding a page is only convenience: the server checks every request.
 */
export function AuthProvider({ children, retryDelays = SESSION_CHECK_RETRY_DELAYS_MS }) {
  const initial = useMemo(() => loadSession(), [])
  const [session, setSession] = useState(initial?.token ? initial : null)
  const [user, setUser] = useState(null)
  const [status, setStatus] = useState(initial?.token ? 'loading' : 'anonymous')
  const [notice, setNotice] = useState(initial?.expired ? ENDED_MESSAGES.expired : null)
  // The interceptor reads the token from this ref, so it always sees the
  // latest one, even between a sign-in and the re-render that follows it.
  const sessionRef = useRef(initial?.token ? initial : null)

  const endSession = useCallback((reason) => {
    clearSession()
    sessionRef.current = null
    setSession(null)
    setUser(null)
    setStatus('anonymous')
    setNotice(ENDED_MESSAGES[reason] ?? (reason ? 'Please sign in again.' : null))
  }, [])

  useEffect(() => {
    configureAuth({
      getToken: () => sessionRef.current?.token ?? null,
      onSessionEnded: (code) => {
        if (sessionRef.current) endSession(code)
      },
    })
  }, [endSession])

  // Load the user for a token found in sessionStorage (page reload).
  useEffect(() => {
    if (!session || user) return undefined
    let cancelled = false
    let retryTimer = null

    const check = (attempt) => {
      athena
        .currentUser()
        .then((me) => {
          if (cancelled) return
          setUser(me)
          setStatus('authenticated')
        })
        .catch((failure) => {
          // A 401 has usually ended the session already (see api/client.js).
          if (cancelled || !sessionRef.current) return
          if (failure?.status === 0 && attempt < retryDelays.length) {
            retryTimer = setTimeout(() => check(attempt + 1), retryDelays[attempt])
            return
          }
          endSession(failure?.status === 401 ? failure.code : 'CHECK_FAILED')
        })
    }

    check(0)
    return () => {
      cancelled = true
      clearTimeout(retryTimer)
    }
  }, [session, user, endSession, retryDelays])

  // Sign out the moment the token expires, even if the user does nothing.
  useEffect(() => {
    if (!session) return
    const remaining = session.expiresAt - Date.now()
    const timer = setTimeout(() => endSession('expired'), Math.max(0, remaining))
    return () => clearTimeout(timer)
  }, [session, endSession])

  const signIn = useCallback(async (email, password) => {
    const result = await athena.login({ email, password })
    const stored = saveSession(result.accessToken)
    sessionRef.current = stored
    const me = await athena.currentUser()
    setNotice(null)
    setSession(stored)
    setUser(me)
    setStatus('authenticated')
    return me
  }, [])

  const signOut = useCallback(async () => {
    try {
      await athena.logout()
    } catch {
      // Already invalid on the server; clearing it here is all that is left.
    }
    endSession(null)
  }, [endSession])

  const value = useMemo(
    () => ({
      user,
      status,
      notice,
      expiresAt: session?.expiresAt ?? null,
      signIn,
      signOut,
      // After a password change the server refuses this token (and every
      // other one from before), so the session ends here with a clear note.
      endAfterPasswordChange: () => endSession('PASSWORD_CHANGED'),
      clearNotice: () => setNotice(null),
      hasRole: (...roles) => Boolean(user?.roles?.some((role) => roles.includes(role))),
    }),
    [user, status, notice, session, signIn, signOut, endSession],
  )

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>
}

// eslint-disable-next-line react/only-export-components
export function useAuth() {
  const context = useContext(AuthContext)
  if (!context) throw new Error('useAuth must be used inside <AuthProvider>')
  return context
}

/** Where each role starts after signing in. */
// eslint-disable-next-line react/only-export-components
export function homePathFor(user) {
  if (!user) return '/login'
  if (user.roles?.includes('USER')) return '/dashboard'
  if (user.roles?.includes('ADMIN') || user.roles?.includes('AUDITOR')) return '/oversight'
  return '/login'
}
