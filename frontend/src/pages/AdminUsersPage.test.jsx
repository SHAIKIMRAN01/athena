import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, expect, it, vi } from 'vitest'
import * as athena from '../api/athena'
import { ApiError } from '../api/client'
import * as auth from '../auth/AuthContext'
import AdminUsersPage from './AdminUsersPage'

vi.mock('../auth/AuthContext', async (importOriginal) => ({ ...(await importOriginal()), useAuth: vi.fn() }))
vi.mock('../api/athena', () => ({
  adminUsers: vi.fn(),
  createStaff: vi.fn(),
  setUserEnabled: vi.fn(),
  setAccountStatus: vi.fn(),
}))

const noUsers = { content: [], page: 0, size: 15, totalElements: 0, totalPages: 0 }

beforeEach(() => {
  vi.clearAllMocks()
  auth.useAuth.mockReturnValue({ user: { id: 1, roles: ['ADMIN'] } })
  athena.adminUsers.mockResolvedValue(noUsers)
})

async function openAddStaff() {
  render(<AdminUsersPage />)
  await userEvent.click(screen.getByRole('button', { name: 'Add staff member' }))
  return screen.getByRole('dialog', { name: 'Add staff member' })
}

it('adds an auditor with a generated initial password', async () => {
  athena.createStaff.mockResolvedValue({ id: 9, fullName: 'Audrey Auditor', roles: ['AUDITOR'] })
  const dialog = await openAddStaff()

  await userEvent.type(within(dialog).getByLabelText('Full name'), 'Audrey Auditor')
  await userEvent.type(within(dialog).getByLabelText('Email'), 'audrey@example.test')
  await userEvent.click(within(dialog).getByRole('button', { name: 'Generate password' }))

  const password = within(dialog).getByLabelText('Initial password')
  expect(password).toHaveAttribute('type', 'text')
  expect(password.value).toMatch(/^(?=.*\p{L})(?=.*\d)[A-Za-z0-9]{16}$/u)

  await userEvent.click(within(dialog).getByRole('button', { name: 'Add' }))

  expect(athena.createStaff).toHaveBeenCalledWith({
    fullName: 'Audrey Auditor',
    email: 'audrey@example.test',
    role: 'AUDITOR',
    initialPassword: password.value,
  })
  expect(await screen.findByText(/Audrey Auditor was added as an auditor/)).toBeInTheDocument()
  expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  expect(athena.adminUsers).toHaveBeenCalledTimes(2)
})

it('checks the details before sending, and staff passwords need 12 characters', async () => {
  const dialog = await openAddStaff()

  await userEvent.type(within(dialog).getByLabelText('Full name'), 'Bea Admin')
  await userEvent.type(within(dialog).getByLabelText('Email'), 'not-an-email')
  await userEvent.selectOptions(within(dialog).getByLabelText('Role'), 'ADMIN')
  await userEvent.type(within(dialog).getByLabelText('Initial password'), 'Short-pass1')
  await userEvent.click(within(dialog).getByRole('button', { name: 'Add' }))

  expect(within(dialog).getByText('Enter a valid email')).toBeInTheDocument()
  expect(within(dialog).getByText('Use 12 to 72 characters')).toBeInTheDocument()
  expect(athena.createStaff).not.toHaveBeenCalled()
})

it('shows the server message when the email is taken', async () => {
  athena.createStaff.mockRejectedValue(
    new ApiError({
      status: 409,
      code: 'EMAIL_ALREADY_REGISTERED',
      message: 'An account with this email already exists',
    }),
  )
  const dialog = await openAddStaff()

  await userEvent.type(within(dialog).getByLabelText('Full name'), 'Alice Again')
  await userEvent.type(within(dialog).getByLabelText('Email'), 'alice@example.test')
  await userEvent.type(within(dialog).getByLabelText('Initial password'), 'Initial-pass-2026')
  await userEvent.click(within(dialog).getByRole('button', { name: 'Add' }))

  expect(await within(dialog).findByRole('alert')).toHaveTextContent('An account with this email already exists')
  expect(screen.getByRole('dialog', { name: 'Add staff member' })).toBeInTheDocument()
})
