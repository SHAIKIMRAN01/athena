const DATE_TIME = new Intl.DateTimeFormat('en-IN', {
  day: '2-digit',
  month: 'short',
  year: 'numeric',
  hour: '2-digit',
  minute: '2-digit',
})

const DATE = new Intl.DateTimeFormat('en-IN', { day: '2-digit', month: 'short', year: 'numeric' })

/** The API sends local date-times without a zone, e.g. "2026-11-08T10:15:30.123456". */
export function formatDateTime(value) {
  if (!value) return '—'
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? String(value) : DATE_TIME.format(date)
}

export function formatDate(value) {
  if (!value) return '—'
  const date = new Date(String(value).length === 10 ? `${value}T00:00:00` : value)
  return Number.isNaN(date.getTime()) ? String(value) : DATE.format(date)
}

/** "2026-11-08" for <input type="date">, in the browser's time zone. */
export function isoDate(date) {
  const pad = (n) => String(n).padStart(2, '0')
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`
}

export function firstOfMonth(date = new Date()) {
  return isoDate(new Date(date.getFullYear(), date.getMonth(), 1))
}

export const TYPE_LABELS = { DEPOSIT: 'Deposit', WITHDRAWAL: 'Withdrawal', TRANSFER: 'Transfer' }

/** "ACCOUNT_NOT_ACTIVE" -> "Account not active" */
export function humanize(code) {
  if (!code) return ''
  const text = String(code).toLowerCase().replaceAll('_', ' ')
  return text.charAt(0).toUpperCase() + text.slice(1)
}
