// Money is handled as decimal strings, never as JavaScript floats, so
// 0.10 + 0.20 problems and rounding of large balances cannot happen here.
// The API client keeps decimals from the server as their exact text
// (see parseJsonExact in api/client.js).

const AMOUNT_PATTERN = /^\d{1,17}(\.\d{1,2})?$/
const INR_GROUPING = new Intl.NumberFormat('en-IN')

/**
 * Formats an amount exactly, with Indian digit grouping: 1234567.5 -> "₹12,34,567.50".
 * Accepts the exact string from the API ("1234567.50") or a number.
 */
export function formatMoney(value, { sign = false } = {}) {
  if (value === null || value === undefined || value === '') return '—'

  const text = typeof value === 'number' ? value.toFixed(2) : String(value).trim()
  const match = /^(-?)(\d+)(?:\.(\d+))?$/.exec(text)
  if (!match) return String(value)

  const [, minus, integer, fraction = ''] = match
  const cents = (fraction + '00').slice(0, 2)
  const grouped = INR_GROUPING.format(BigInt(integer))
  const isZero = /^0+$/.test(integer) && /^0+$/.test(cents)

  let prefix = ''
  if (minus && !isZero) prefix = '−'
  else if (sign && !isZero) prefix = '+'

  return `${prefix}₹${grouped}.${cents}`
}

/**
 * Checks what a user typed into an amount field.
 * Returns { value: "250.50" } with exactly two decimals, or { error: "..." }.
 */
export function parseAmountInput(input) {
  const text = String(input ?? '').trim().replaceAll(',', '')

  if (text === '') return { error: 'Enter an amount.' }
  if (!AMOUNT_PATTERN.test(text)) {
    return { error: 'Use digits only, with at most 2 decimal places (for example 250.50).' }
  }

  const [integer, fraction = ''] = text.split('.')
  const normalized = `${BigInt(integer)}.${(fraction + '00').slice(0, 2)}`
  if (/^0\.00$/.test(normalized)) return { error: 'The amount must be more than zero.' }

  return { value: normalized }
}

/** Compares two decimal strings with two decimals: -1, 0 or 1. */
export function compareAmounts(a, b) {
  const toCents = (value) => {
    const [integer, fraction = ''] = String(value).split('.')
    return BigInt(integer) * 100n + BigInt((fraction + '00').slice(0, 2))
  }
  const difference = toCents(a) - toCents(b)
  return difference === 0n ? 0 : difference > 0n ? 1 : -1
}
