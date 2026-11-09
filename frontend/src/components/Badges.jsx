import { humanize } from '../lib/format'

const TONES = {
  COMPLETED: 'success',
  ACTIVE: 'success',
  ENABLED: 'success',
  REPLAYED: 'info',
  PENDING: 'info',
  FAILED: 'danger',
  REJECTED: 'warning',
  DENIED: 'danger',
  ERROR: 'danger',
  FROZEN: 'warning',
  DISABLED: 'danger',
  CLOSED: 'muted',
  IN: 'success',
  OUT: 'muted',
}

export function StatusBadge({ value }) {
  if (!value) return null
  return <span className={`badge badge-${TONES[value] ?? 'muted'}`}>{humanize(value)}</span>
}

export function RoleBadge({ role }) {
  return <span className={`badge badge-role badge-role-${String(role).toLowerCase()}`}>{role}</span>
}
