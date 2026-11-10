import { useState } from 'react'
import * as athena from '../api/athena'
import { useAuth } from '../auth/AuthContext'
import { PageHeader } from '../components/AppLayout'
import { RoleBadge } from '../components/Badges'
import { Alert } from '../components/Feedback'
import { validatePasswordChange } from '../lib/validation'

const EMPTY = { currentPassword: '', newPassword: '', confirm: '' }

/**
 * The signed-in user's details and the password change, for every role.
 * Changing the password ends every session, this one included (the server
 * refuses all tokens issued before it), so the user signs in again with the
 * new password, here and on any other device.
 */
export default function ProfilePage() {
  const { user, hasRole, endAfterPasswordChange } = useAuth()
  const isStaff = hasRole('ADMIN', 'AUDITOR')
  const minimum = isStaff ? 12 : 8
  const [form, setForm] = useState(EMPTY)
  const [errors, setErrors] = useState({})
  const [error, setError] = useState(null)
  const [busy, setBusy] = useState(false)

  const update = (field) => (event) => setForm((current) => ({ ...current, [field]: event.target.value }))

  async function submit(event) {
    event.preventDefault()
    setError(null)
    const found = validatePasswordChange(form, minimum)
    setErrors(found)
    if (Object.keys(found).length > 0) return

    setBusy(true)
    try {
      await athena.changePassword({ currentPassword: form.currentPassword, newPassword: form.newPassword })
      endAfterPasswordChange()
    } catch (failure) {
      setError(failure)
      setErrors(failure.fieldErrors ?? {})
      setForm((current) => ({ ...current, currentPassword: '' }))
      setBusy(false)
    }
  }

  const field = (name, label, autoComplete) => (
    <label className={`field ${errors[name] ? 'field-invalid' : ''}`}>
      <span>{label}</span>
      <input
        type="password"
        autoComplete={autoComplete}
        value={form[name]}
        onChange={update(name)}
        aria-invalid={Boolean(errors[name])}
        maxLength={name === 'currentPassword' ? 200 : 72}
      />
      {errors[name] && <small className="field-error">{errors[name]}</small>}
    </label>
  )

  return (
    <>
      <PageHeader title="Profile" subtitle="Your details and your password." />

      <section className="panel panel-narrow">
        <header className="panel-header">
          <h2>Your details</h2>
        </header>
        <dl className="details">
          <dt>Name</dt>
          <dd>{user?.fullName}</dd>
          <dt>Email</dt>
          <dd>{user?.email}</dd>
          <dt>Roles</dt>
          <dd>
            {user?.roles?.map((role) => (
              <RoleBadge key={role} role={role} />
            ))}
          </dd>
          {user?.accountNumber && (
            <>
              <dt>Account number</dt>
              <dd>
                <code>{user.accountNumber}</code>
              </dd>
            </>
          )}
        </dl>
      </section>

      <section className="panel panel-narrow" aria-labelledby="password-heading">
        <header className="panel-header">
          <h2 id="password-heading">Change password</h2>
        </header>
        <p className="hint">
          Afterwards you sign in again with the new password: every session from before the change ends, on this device
          and every other one.
        </p>
        <Alert error={error} />
        <form className="form" onSubmit={submit} noValidate>
          {field('currentPassword', 'Current password', 'current-password')}
          {field('newPassword', 'New password', 'new-password')}
          {field('confirm', 'Repeat new password', 'new-password')}
          <p className="hint">{minimum} to 72 characters, with at least one letter and one digit.</p>
          <button type="submit" className="button button-primary" disabled={busy}>
            {busy ? 'Changing…' : 'Change password'}
          </button>
        </form>
      </section>
    </>
  )
}
