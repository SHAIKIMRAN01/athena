import { describe, expect, it } from 'vitest'
import { compareAmounts, formatMoney, parseAmountInput } from './money'

describe('formatMoney', () => {
  it('formats exact decimal strings with Indian grouping', () => {
    expect(formatMoney('1234567.50')).toBe('₹12,34,567.50')
    expect(formatMoney('0.10')).toBe('₹0.10')
  })

  it('keeps balances too large for a JavaScript number exact', () => {
    // As a float this would print 99999999999999999.99 as 100000000000000000.00
    expect(formatMoney('99999999999999999.99')).toBe('₹99,99,99,99,99,99,99,999.99')
  })

  it('adds two decimals and an optional sign', () => {
    expect(formatMoney('5')).toBe('₹5.00')
    expect(formatMoney(12.5)).toBe('₹12.50')
    expect(formatMoney('10.00', { sign: true })).toBe('+₹10.00')
    expect(formatMoney('-3.40')).toBe('−₹3.40')
  })

  it('shows a dash for missing values', () => {
    expect(formatMoney(null)).toBe('—')
  })
})

describe('parseAmountInput', () => {
  it.each([
    ['250.5', '250.50'],
    ['1,000', '1000.00'],
    [' 0.01 ', '0.01'],
    ['007', '7.00'],
  ])('accepts %s as %s', (input, expected) => {
    expect(parseAmountInput(input)).toEqual({ value: expected })
  })

  it.each(['', '0', '0.00', '-5', '10.001', '1e3', 'abc', '12.3.4', '123456789012345678'])('refuses %j', (input) => {
    expect(parseAmountInput(input).error).toBeTruthy()
  })
})

describe('compareAmounts', () => {
  it('compares exactly in cents', () => {
    expect(compareAmounts('0.30', '0.3')).toBe(0)
    expect(compareAmounts('100.01', '100.00')).toBe(1)
    expect(compareAmounts('99999999999999999.98', '99999999999999999.99')).toBe(-1)
  })
})
