import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { expect, it, vi } from 'vitest'
import { ApiError } from '../api/client'
import * as auth from '../auth/AuthContext'
import { LoginPage } from './AuthPages'

vi.mock('../auth/AuthContext', async (importOriginal) => ({ ...(await importOriginal()), useAuth: vi.fn() }))

function renderLogin(overrides = {}) {
  const signIn = vi.fn()
  auth.useAuth.mockReturnValue({
    status: 'anonymous',
    user: null,
    notice: null,
    clearNotice: vi.fn(),
    signIn,
    ...overrides,
  })
  render(
    <MemoryRouter initialEntries={['/login']}>
      <Routes>
        <Route path="/login" element={<LoginPage />} />
        <Route path="/dashboard" element={<p>Dashboard</p>} />
      </Routes>
    </MemoryRouter>,
  )
  return signIn
}

it('signs in and opens the start page for the role', async () => {
  const signIn = renderLogin()
  signIn.mockResolvedValue({ roles: ['USER'] })

  await userEvent.type(screen.getByLabelText('Email'), 'alice@example.com')
  await userEvent.type(screen.getByLabelText('Password'), 'Password123!')
  await userEvent.click(screen.getByRole('button', { name: 'Sign in' }))

  expect(await screen.findByText('Dashboard')).toBeInTheDocument()
  expect(signIn).toHaveBeenCalledWith('alice@example.com', 'Password123!')
})

it('shows the server message and clears the password when sign-in fails', async () => {
  const signIn = renderLogin()
  signIn.mockRejectedValue(
    new ApiError({ status: 401, code: 'INVALID_CREDENTIALS', message: 'Email or password is incorrect' }),
  )

  await userEvent.type(screen.getByLabelText('Email'), 'alice@example.com')
  await userEvent.type(screen.getByLabelText('Password'), 'wrong')
  await userEvent.click(screen.getByRole('button', { name: 'Sign in' }))

  expect(await screen.findByRole('alert')).toHaveTextContent('Email or password is incorrect')
  expect(screen.getByLabelText('Password')).toHaveValue('')
})

it('explains why the user was signed out', () => {
  renderLogin({ notice: 'Your session expired. Please sign in again.' })
  expect(screen.getByText('Your session expired. Please sign in again.')).toBeInTheDocument()
})
