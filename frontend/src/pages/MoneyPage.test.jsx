import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '../api/client'
import * as athena from '../api/athena'
import MoneyPage from './MoneyPage'

vi.mock('../api/athena', () => ({
  myAccount: vi.fn(),
  deposit: vi.fn(),
  withdraw: vi.fn(),
  transfer: vi.fn(),
}))

const ACCOUNT = {
  accountNumber: 'ATAAAAAAAAAAAA',
  balance: '500.00',
  status: 'ACTIVE',
  createdAt: '2026-11-01T10:00:00',
}

function transaction(amount, balanceAfter) {
  return {
    transaction: { transactionId: 7, reference: 'TXN0000000007', amount, balanceAfter },
    replayed: false,
  }
}

function renderPage(type = 'deposit') {
  render(
    <MemoryRouter initialEntries={[`/move-money?type=${type}`]}>
      <MoneyPage />
    </MemoryRouter>,
  )
}

describe('MoneyPage', () => {
  beforeEach(() => {
    athena.myAccount.mockResolvedValue(ACCOUNT)
  })

  it('sends the amount exactly as typed, with an Idempotency-Key', async () => {
    athena.deposit.mockResolvedValue(transaction('250.50', '750.50'))
    renderPage('deposit')

    await userEvent.type(screen.getByLabelText('Amount (₹)'), '250.5')
    await userEvent.click(screen.getByRole('button', { name: 'Deposit' }))

    await screen.findByText(/completed/)
    const [amount, key] = athena.deposit.mock.calls[0]
    expect(amount).toBe('250.50')
    expect(key).toMatch(/^[0-9a-f-]{36}$/)
  })

  it('reuses the same key when retrying after a network failure, so money cannot move twice', async () => {
    athena.withdraw
      .mockRejectedValueOnce(new ApiError({ status: 0, code: 'NETWORK_ERROR', message: 'Cannot reach the server.' }))
      .mockResolvedValueOnce(transaction('100.00', '400.00'))
    renderPage('withdraw')

    await userEvent.type(screen.getByLabelText('Amount (₹)'), '100')
    await userEvent.click(screen.getByRole('button', { name: 'Withdraw' }))
    await screen.findByText('We could not confirm this request')

    await userEvent.click(screen.getByRole('button', { name: 'Withdraw' }))
    await screen.findByText(/completed/)

    const keys = athena.withdraw.mock.calls.map(([, key]) => key)
    expect(keys).toHaveLength(2)
    expect(keys[1]).toBe(keys[0])
  })

  it('uses a new key after the server refused a request', async () => {
    athena.withdraw
      .mockRejectedValueOnce(new ApiError({ status: 422, code: 'INSUFFICIENT_FUNDS', message: 'Insufficient funds' }))
      .mockResolvedValueOnce(transaction('100.00', '400.00'))
    renderPage('withdraw')

    await userEvent.type(screen.getByLabelText('Amount (₹)'), '100')
    await userEvent.click(screen.getByRole('button', { name: 'Withdraw' }))
    await screen.findByText('Insufficient funds')
    await userEvent.click(screen.getByRole('button', { name: 'Withdraw' }))
    await screen.findByText(/completed/)

    const keys = athena.withdraw.mock.calls.map(([, key]) => key)
    expect(keys[1]).not.toBe(keys[0])
  })

  it('refuses amounts above the balance before calling the server', async () => {
    renderPage('withdraw')
    await waitFor(() => expect(athena.myAccount).toHaveBeenCalled())
    await screen.findByTestId('money-balance')

    await userEvent.type(screen.getByLabelText('Amount (₹)'), '500.01')
    await userEvent.click(screen.getByRole('button', { name: 'Withdraw' }))

    expect(await screen.findByText(/more than your balance/, { selector: 'small' })).toBeInTheDocument()
    expect(athena.withdraw).not.toHaveBeenCalled()
  })

  it('asks for confirmation before a transfer', async () => {
    athena.transfer.mockResolvedValue(transaction('40.00', '460.00'))
    renderPage('transfer')

    await userEvent.type(screen.getByLabelText('Receiver account number'), 'atbbbbbbbbbbbb')
    await userEvent.type(screen.getByLabelText('Amount (₹)'), '40')
    await userEvent.click(screen.getByRole('button', { name: 'Send' }))

    expect(screen.getByRole('dialog', { name: 'Confirm transfer' })).toBeInTheDocument()
    expect(athena.transfer).not.toHaveBeenCalled()

    await userEvent.click(screen.getByRole('button', { name: 'Send ₹40.00' }))
    await screen.findByText(/completed/)
    expect(athena.transfer.mock.calls[0].slice(0, 2)).toEqual(['ATBBBBBBBBBBBB', '40.00'])
  })

  it('still accepts account numbers opened before the rename to Athena (LL...)', async () => {
    athena.transfer.mockResolvedValue(transaction('5.00', '495.00'))
    renderPage('transfer')

    await userEvent.type(screen.getByLabelText('Receiver account number'), 'LL0123456789AB')
    await userEvent.type(screen.getByLabelText('Amount (₹)'), '5')
    await userEvent.click(screen.getByRole('button', { name: 'Send' }))
    await userEvent.click(screen.getByRole('button', { name: 'Send ₹5.00' }))

    await screen.findByText(/completed/)
    expect(athena.transfer.mock.calls[0][0]).toBe('LL0123456789AB')
  })

  it('refuses an account number in the wrong format', async () => {
    renderPage('transfer')
    await screen.findByTestId('money-balance')

    await userEvent.type(screen.getByLabelText('Receiver account number'), 'XX0123456789AB')
    await userEvent.type(screen.getByLabelText('Amount (₹)'), '5')
    await userEvent.click(screen.getByRole('button', { name: 'Send' }))

    expect(
      await screen.findByText('Account numbers look like AT followed by 12 letters or digits.'),
    ).toBeInTheDocument()
    expect(athena.transfer).not.toHaveBeenCalled()
  })

  it('refuses a transfer to your own account', async () => {
    renderPage('transfer')
    await screen.findByTestId('money-balance')

    await userEvent.type(screen.getByLabelText('Receiver account number'), ACCOUNT.accountNumber)
    await userEvent.type(screen.getByLabelText('Amount (₹)'), '1')
    await userEvent.click(screen.getByRole('button', { name: 'Send' }))

    expect(await screen.findByText('You cannot transfer to your own account.')).toBeInTheDocument()
  })
})
