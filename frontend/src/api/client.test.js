import { describe, expect, it } from 'vitest'
import { parseJsonExact, toApiError } from './client'

describe('parseJsonExact', () => {
  it('keeps decimals as their exact text and whole numbers as numbers', () => {
    const parsed = parseJsonExact('{"transactionId": 42, "amount": 1000.00, "balance": 99999999999999999.99}')
    expect(parsed.transactionId).toBe(42)
    expect(parsed.amount).toBe('1000.00')
    expect(parsed.balance).toBe('99999999999999999.99')
  })

  it('leaves non-JSON bodies alone', () => {
    expect(parseJsonExact('')).toBe('')
    expect(parseJsonExact('<html>')).toBe('<html>')
  })
})

describe('toApiError', () => {
  it('reads problem-detail responses', () => {
    const error = toApiError({
      response: {
        status: 422,
        headers: {},
        data: { detail: 'Insufficient funds for this transaction', code: 'INSUFFICIENT_FUNDS', reference: 'TXN0000000007' },
      },
    })
    expect(error.status).toBe(422)
    expect(error.code).toBe('INSUFFICIENT_FUNDS')
    expect(error.message).toBe('Insufficient funds for this transaction')
    expect(error.data.reference).toBe('TXN0000000007')
    expect(error.outcomeUnknown).toBe(false)
  })

  it('treats a missing response as an unknown outcome', () => {
    const error = toApiError({ code: 'ERR_NETWORK' })
    expect(error.status).toBe(0)
    expect(error.outcomeUnknown).toBe(true)
  })

  it('mentions the wait for throttled requests', () => {
    const error = toApiError({ response: { status: 429, headers: { 'retry-after': '600' }, data: { detail: 'Too many failed sign-in attempts.', code: 'LOGIN_THROTTLED' } } })
    expect(error.retryAfter).toBe(600)
    expect(error.message).toContain('10 min')
  })
})
