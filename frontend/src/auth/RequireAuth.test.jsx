import { render, screen } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { describe, expect, it, vi } from 'vitest'
import RequireAuth from './RequireAuth'
import * as auth from './AuthContext'

vi.mock('./AuthContext', async (importOriginal) => ({ ...(await importOriginal()), useAuth: vi.fn() }))

function renderAt(path, authState) {
  auth.useAuth.mockReturnValue({
    hasRole: (...roles) => Boolean(authState.user?.roles?.some((role) => roles.includes(role))),
    ...authState,
  })

  render(
    <MemoryRouter initialEntries={[path]}>
      <Routes>
        <Route path="/login" element={<p>Sign-in page</p>} />
        <Route path="/admin/users" element={<RequireAuth roles={['ADMIN']}><p>Admin area</p></RequireAuth>} />
      </Routes>
    </MemoryRouter>,
  )
}

describe('RequireAuth', () => {
  it('sends signed-out visitors to the sign-in page', () => {
    renderAt('/admin/users', { status: 'anonymous', user: null })
    expect(screen.getByText('Sign-in page')).toBeInTheDocument()
  })

  it('shows a no-access page to users without the role', () => {
    renderAt('/admin/users', { status: 'authenticated', user: { roles: ['USER'] } })
    expect(screen.getByRole('heading', { name: 'No access' })).toBeInTheDocument()
    expect(screen.queryByText('Admin area')).not.toBeInTheDocument()
  })

  it('shows the page to users with the role', () => {
    renderAt('/admin/users', { status: 'authenticated', user: { roles: ['ADMIN'] } })
    expect(screen.getByText('Admin area')).toBeInTheDocument()
  })
})

describe('homePathFor', () => {
  it('starts each role on its own page', () => {
    expect(auth.homePathFor({ roles: ['USER'] })).toBe('/dashboard')
    expect(auth.homePathFor({ roles: ['AUDITOR'] })).toBe('/oversight')
    expect(auth.homePathFor({ roles: ['ADMIN'] })).toBe('/oversight')
    expect(auth.homePathFor(null)).toBe('/login')
  })
})
