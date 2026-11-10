import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, expect, it, vi } from 'vitest'
import * as athena from '../api/athena'
import { ApiError } from '../api/client'
import * as auth from '../auth/AuthContext'
import ProfilePage from './ProfilePage'

vi.mock('../auth/AuthContext', async (importOriginal) => ({ ...(await importOriginal()), useAuth: vi.fn() }))
vi.mock('../api/athena', () => ({ changePassword: vi.fn() }))

const alice = {
  id: 7,
  fullName: 'Alice Tester',
  email: 'alice@example.test',
  roles: ['USER'],
  accountNumber: 'AT0000000007',
}

function renderProfile(user = alice) {
  const endAfterPasswordChange = vi.fn()
  auth.useAuth.mockReturnValue({
    user,
    hasRole: (...roles) => user.roles.some((role) => roles.includes(role)),
    endAfterPasswordChange,
  })
  render(<ProfilePage />)
  return endAfterPasswordChange
}

async function fillPasswords(current, next, again = next) {
  await userEvent.type(screen.getByLabelText('Current password'), current)
  await userEvent.type(screen.getByLabelText('New password'), next)
  await userEvent.type(screen.getByLabelText('Repeat new password'), again)
  await userEvent.click(screen.getByRole('button', { name: 'Change password' }))
}

beforeEach(() => {
  athena.changePassword.mockReset()
})

it('shows who is signed in', () => {
  renderProfile()
  expect(screen.getByText('alice@example.test')).toBeInTheDocument()
  expect(screen.getByText('AT0000000007')).toBeInTheDocument()
})

it('changes the password, then ends the session so the user signs in again', async () => {
  athena.changePassword.mockResolvedValue(undefined)
  const endAfterPasswordChange = renderProfile()

  await fillPasswords('Password123!', 'Brand-new-pass-9')

  expect(athena.changePassword).toHaveBeenCalledWith({
    currentPassword: 'Password123!',
    newPassword: 'Brand-new-pass-9',
  })
  expect(endAfterPasswordChange).toHaveBeenCalledTimes(1)
})

it('checks the new password before sending anything', async () => {
  const endAfterPasswordChange = renderProfile()

  await fillPasswords('Password123!', 'Brand-new-pass-9', 'Brand-new-pass-8')
  expect(screen.getByText('The passwords do not match')).toBeInTheDocument()

  await userEvent.clear(screen.getByLabelText('New password'))
  await userEvent.type(screen.getByLabelText('New password'), 'lettersonly')
  await userEvent.click(screen.getByRole('button', { name: 'Change password' }))
  expect(screen.getByText('Include at least one letter and one digit')).toBeInTheDocument()

  expect(athena.changePassword).not.toHaveBeenCalled()
  expect(endAfterPasswordChange).not.toHaveBeenCalled()
})

it('shows the server message and clears the current password when it is wrong', async () => {
  athena.changePassword.mockRejectedValue(
    new ApiError({ status: 400, code: 'CURRENT_PASSWORD_INCORRECT', message: 'Your current password is not correct' }),
  )
  const endAfterPasswordChange = renderProfile()

  await fillPasswords('Wrong-guess-1', 'Brand-new-pass-9')

  expect(await screen.findByRole('alert')).toHaveTextContent('Your current password is not correct')
  expect(screen.getByLabelText('Current password')).toHaveValue('')
  expect(endAfterPasswordChange).not.toHaveBeenCalled()
})

it('asks staff for at least 12 characters', async () => {
  renderProfile({ id: 1, fullName: 'Ada Admin', email: 'ada@example.test', roles: ['ADMIN'], accountNumber: null })

  await fillPasswords('Initial-pass-2026', 'Short-pass1')

  expect(screen.getByText('Use 12 to 72 characters')).toBeInTheDocument()
  expect(athena.changePassword).not.toHaveBeenCalled()
})
