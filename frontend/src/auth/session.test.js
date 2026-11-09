import { expect, it } from 'vitest'
import { clearSession, loadSession, saveSession, tokenExpiry } from './session'

function tokenExpiringAt(seconds) {
  const encode = (object) => btoa(JSON.stringify(object)).replaceAll('=', '').replaceAll('+', '-').replaceAll('/', '_')
  return `${encode({ alg: 'HS256' })}.${encode({ sub: 'alice@example.com', exp: seconds })}.signature`
}

it('reads the expiry from the token', () => {
  expect(tokenExpiry(tokenExpiringAt(2_000_000_000))).toBe(2_000_000_000)
  expect(tokenExpiry('garbage')).toBeNull()
})

it('keeps a session until its token expires', () => {
  const exp = Math.floor(Date.now() / 1000) + 600
  saveSession(tokenExpiringAt(exp))

  expect(loadSession().token).toBe(tokenExpiringAt(exp))
  expect(loadSession(exp * 1000 + 1)).toEqual({ expired: true })
  expect(loadSession()).toBeNull()
})

it('clears the session', () => {
  saveSession(tokenExpiringAt(Math.floor(Date.now() / 1000) + 600))
  clearSession()
  expect(loadSession()).toBeNull()
})
