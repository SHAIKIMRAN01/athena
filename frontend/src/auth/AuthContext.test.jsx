import { render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '../api/client'
import * as athena from '../api/athena'
import { AuthProvider, useAuth } from './AuthContext'

vi.mock('../api/athena', () => ({ currentUser: vi.fn(), login: vi.fn(), logout: vi.fn() }))

function Probe() {
  const { status, user, notice } = useAuth()
  return <p>{`${status}|${user?.email ?? ''}|${notice ?? ''}`}</p>
}

// The real waits are 1, 2 and 4 seconds; the tests use short ones.
const RETRY_DELAYS = [5, 5, 5]

/** Starts the app the way a page reload does: with a session already in sessionStorage. */
function reloadWithStoredSession() {
  sessionStorage.setItem('athena.session', JSON.stringify({ token: 'h.p.s', expiresAt: Date.now() + 10 * 60_000 }))
  render(
    <AuthProvider retryDelays={RETRY_DELAYS}>
      <Probe />
    </AuthProvider>,
  )
}

const offline = () => new ApiError({ status: 0, code: 'NETWORK_ERROR', message: 'Cannot reach the server.' })

describe('AuthProvider checking a stored session', () => {
  afterEach(() => {
    sessionStorage.clear()
  })

  it('tries again when the server cannot be reached, and keeps the session', async () => {
    athena.currentUser
      .mockRejectedValueOnce(offline())
      .mockResolvedValueOnce({ email: 'alice@example.com', roles: ['USER'] })
    reloadWithStoredSession()

    expect(await screen.findByText('authenticated|alice@example.com|')).toBeInTheDocument()
    expect(athena.currentUser).toHaveBeenCalledTimes(2)
    expect(sessionStorage.getItem('athena.session')).not.toBeNull()
  })

  it('signs out with an explanation when the server stays unreachable', async () => {
    athena.currentUser.mockRejectedValue(offline())
    reloadWithStoredSession()

    expect(await screen.findByText(/^anonymous\|\|We could not confirm your session/)).toBeInTheDocument()
    expect(athena.currentUser).toHaveBeenCalledTimes(RETRY_DELAYS.length + 1)
    expect(sessionStorage.getItem('athena.session')).toBeNull()
  })

  it('asks the user to sign in again when the server refuses the token', async () => {
    athena.currentUser.mockRejectedValue(
      new ApiError({ status: 401, code: 'TOKEN_INVALID', message: 'The access token is not valid.' }),
    )
    reloadWithStoredSession()

    expect(await screen.findByText('anonymous||Please sign in again.')).toBeInTheDocument()
    expect(athena.currentUser).toHaveBeenCalledTimes(1)
  })
})
