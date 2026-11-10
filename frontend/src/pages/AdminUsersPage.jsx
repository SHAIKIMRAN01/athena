import { useState } from 'react'
import * as athena from '../api/athena'
import { useAuth } from '../auth/AuthContext'
import { PageHeader } from '../components/AppLayout'
import { RoleBadge, StatusBadge } from '../components/Badges'
import Dialog from '../components/Dialog'
import { Alert, EmptyState, Spinner } from '../components/Feedback'
import Pagination from '../components/Pagination'
import { formatDateTime } from '../lib/format'
import { formatMoney } from '../lib/money'
import { useAsync } from '../lib/useAsync'
import { generatePassword, validateStaff } from '../lib/validation'

const ROLE_LABELS = { ADMIN: 'an administrator', AUDITOR: 'an auditor' }

export default function AdminUsersPage() {
  const { user: me } = useAuth()
  const [draft, setDraft] = useState('')
  const [search, setSearch] = useState('')
  const [page, setPage] = useState(0)
  const [pendingAction, setPendingAction] = useState(null)
  const [reason, setReason] = useState('')
  const [actionError, setActionError] = useState(null)
  const [message, setMessage] = useState(null)
  const [busy, setBusy] = useState(false)
  const [addingStaff, setAddingStaff] = useState(false)

  const users = useAsync(() => athena.adminUsers({ search, page, size: 15 }), [search, page])

  function ask(action) {
    setActionError(null)
    setReason('')
    setPendingAction(action)
  }

  async function confirm() {
    setBusy(true)
    setActionError(null)
    try {
      const { kind, target } = pendingAction
      if (kind === 'user') await athena.setUserEnabled(target.id, !target.enabled)
      else
        await athena.setAccountStatus(
          target.accountNumber,
          target.accountStatus === 'FROZEN' ? 'ACTIVE' : 'FROZEN',
          reason.trim(),
        )
      setMessage(describe(pendingAction))
      setPendingAction(null)
      users.reload()
    } catch (failure) {
      setActionError(failure)
    } finally {
      setBusy(false)
    }
  }

  return (
    <>
      <PageHeader
        title="Users"
        subtitle="Add staff, enable or disable users, and freeze or unfreeze accounts. Every change is audited."
        actions={
          <button type="button" className="button button-primary" onClick={() => setAddingStaff(true)}>
            Add staff member
          </button>
        }
      />

      <form
        className="filters filters-compact"
        onSubmit={(event) => {
          event.preventDefault()
          setPage(0)
          setSearch(draft.trim())
        }}
      >
        <label className="field field-grow">
          <span>Search</span>
          <input
            value={draft}
            onChange={(e) => setDraft(e.target.value)}
            placeholder="Name, email or account number"
            maxLength={100}
          />
        </label>
        <div className="filters-actions">
          <button type="submit" className="button button-primary">
            Search
          </button>
        </div>
      </form>

      {message && (
        <Alert kind="success" onDismiss={() => setMessage(null)}>
          {message}
        </Alert>
      )}
      <Alert error={users.error} />

      <section className="panel">
        {users.loading && !users.data && <Spinner />}
        {users.data?.content.length === 0 && <EmptyState title="No users match" />}
        {users.data?.content.length > 0 && (
          <div className="table-wrap">
            <table className="table table-cards">
              <thead>
                <tr>
                  <th>Name</th>
                  <th>Roles</th>
                  <th>User</th>
                  <th>Account</th>
                  <th className="numeric">Balance</th>
                  <th>Created</th>
                  <th>Actions</th>
                </tr>
              </thead>
              <tbody>
                {users.data.content.map((user) => (
                  <tr key={user.id}>
                    <td data-label="Name" className="cell-title">
                      <strong>{user.fullName}</strong>
                      <br />
                      <span className="muted small">{user.email}</span>
                    </td>
                    <td data-label="Roles">
                      {user.roles.map((role) => (
                        <RoleBadge key={role} role={role} />
                      ))}
                    </td>
                    <td data-label="User">
                      <StatusBadge value={user.enabled ? 'ENABLED' : 'DISABLED'} />
                    </td>
                    <td data-label="Account">
                      {user.accountNumber ? (
                        <>
                          <code>{user.accountNumber}</code> <StatusBadge value={user.accountStatus} />
                        </>
                      ) : (
                        <span className="muted">No account</span>
                      )}
                    </td>
                    <td data-label="Balance" className="numeric">
                      {user.balance === null ? '—' : formatMoney(user.balance)}
                    </td>
                    <td data-label="Created">{formatDateTime(user.createdAt)}</td>
                    <td data-label="Actions" className="actions">
                      {user.id !== me?.id && (
                        <button
                          type="button"
                          className="button button-small button-ghost"
                          onClick={() => ask({ kind: 'user', target: user })}
                        >
                          {user.enabled ? 'Disable user' : 'Enable user'}
                        </button>
                      )}
                      {user.accountNumber && user.accountStatus !== 'CLOSED' && (
                        <button
                          type="button"
                          className="button button-small button-ghost"
                          onClick={() => ask({ kind: 'account', target: user })}
                        >
                          {user.accountStatus === 'FROZEN' ? 'Unfreeze' : 'Freeze'}
                        </button>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
        <Pagination page={users.data} onPageChange={setPage} />
      </section>

      <Dialog
        open={Boolean(pendingAction)}
        title={pendingAction ? describe(pendingAction, true) : ''}
        onClose={() => setPendingAction(null)}
        footer={
          <>
            <button type="button" className="button button-ghost" onClick={() => setPendingAction(null)}>
              Cancel
            </button>
            <button type="button" className="button button-danger" onClick={confirm} disabled={busy}>
              {busy ? 'Saving…' : 'Confirm'}
            </button>
          </>
        }
      >
        <Alert error={actionError} />
        {pendingAction?.kind === 'user' && (
          <p>
            {pendingAction.target.enabled
              ? `${pendingAction.target.fullName} will not be able to sign in, and any open session stops working at once.`
              : `${pendingAction.target.fullName} will be able to sign in again.`}
          </p>
        )}
        {pendingAction?.kind === 'account' && (
          <>
            <p>
              {pendingAction.target.accountStatus === 'FROZEN'
                ? `Account ${pendingAction.target.accountNumber} will be able to move money again.`
                : `Account ${pendingAction.target.accountNumber} will not be able to send, receive, deposit or withdraw money.`}
            </p>
            <label className="field">
              <span>Reason (kept in the audit log)</span>
              <input value={reason} onChange={(e) => setReason(e.target.value)} maxLength={200} />
            </label>
          </>
        )}
      </Dialog>

      <AddStaffDialog
        open={addingStaff}
        onClose={() => setAddingStaff(false)}
        onAdded={(created, role) => {
          setAddingStaff(false)
          setMessage(
            `${created.fullName} was added as ${ROLE_LABELS[role]}. Give them the initial password privately;` +
              ' they can change it on their Profile page.',
          )
          users.reload()
        }}
      />
    </>
  )
}

const NEW_STAFF = { fullName: '', email: '', role: 'AUDITOR', initialPassword: '' }

/**
 * Staff join through here once the first administrator exists: another
 * administrator, or an auditor who can only look. Neither has a customer
 * account or can move money. The initial password is shown on request, so
 * the administrator can pass it on.
 */
function AddStaffDialog({ open, onClose, onAdded }) {
  const [form, setForm] = useState(NEW_STAFF)
  const [errors, setErrors] = useState({})
  const [error, setError] = useState(null)
  const [busy, setBusy] = useState(false)
  const [showPassword, setShowPassword] = useState(false)

  const update = (field) => (event) => setForm((current) => ({ ...current, [field]: event.target.value }))

  function reset() {
    setForm(NEW_STAFF)
    setErrors({})
    setError(null)
    setShowPassword(false)
  }

  function close() {
    reset()
    onClose()
  }

  async function submit(event) {
    event?.preventDefault()
    setError(null)
    const found = validateStaff(form)
    setErrors(found)
    if (Object.keys(found).length > 0) return

    setBusy(true)
    try {
      const created = await athena.createStaff({
        ...form,
        fullName: form.fullName.trim(),
        email: form.email.trim(),
      })
      reset()
      onAdded(created, form.role)
    } catch (failure) {
      setError(failure)
      setErrors(failure.fieldErrors ?? {})
    } finally {
      setBusy(false)
    }
  }

  const field = (name, label, props) => (
    <label className={`field ${errors[name] ? 'field-invalid' : ''}`}>
      <span>{label}</span>
      <input value={form[name]} onChange={update(name)} aria-invalid={Boolean(errors[name])} {...props} />
      {errors[name] && <small className="field-error">{errors[name]}</small>}
    </label>
  )

  return (
    <Dialog
      open={open}
      title="Add staff member"
      onClose={close}
      footer={
        <>
          <button type="button" className="button button-ghost" onClick={close}>
            Cancel
          </button>
          <button type="button" className="button button-primary" onClick={submit} disabled={busy}>
            {busy ? 'Adding…' : 'Add'}
          </button>
        </>
      }
    >
      <p>
        An administrator manages users; an auditor can only look at the books and the audit log. Neither has a customer
        account or can move money.
      </p>
      <Alert error={error} />
      <form className="form" onSubmit={submit} noValidate>
        {field('fullName', 'Full name', { autoComplete: 'off', maxLength: 100 })}
        {field('email', 'Email', { type: 'email', autoComplete: 'off', maxLength: 150 })}
        <label className={`field ${errors.role ? 'field-invalid' : ''}`}>
          <span>Role</span>
          <select value={form.role} onChange={update('role')}>
            <option value="AUDITOR">Auditor (read only)</option>
            <option value="ADMIN">Administrator</option>
          </select>
          {errors.role && <small className="field-error">{errors.role}</small>}
        </label>
        {field('initialPassword', 'Initial password', {
          type: showPassword ? 'text' : 'password',
          autoComplete: 'new-password',
          spellCheck: false,
          maxLength: 72,
        })}
        <div className="page-actions">
          <button
            type="button"
            className="button button-small button-ghost"
            onClick={() => {
              setForm((current) => ({ ...current, initialPassword: generatePassword() }))
              setShowPassword(true)
            }}
          >
            Generate password
          </button>
          <button
            type="button"
            className="button button-small button-ghost"
            aria-pressed={showPassword}
            onClick={() => setShowPassword((shown) => !shown)}
          >
            {showPassword ? 'Hide password' : 'Show password'}
          </button>
        </div>
        <p className="hint">
          At least 12 characters with a letter and a digit. Give it to the new staff member privately; they can change
          it on their Profile page.
        </p>
      </form>
    </Dialog>
  )
}

function describe({ kind, target }, asQuestion = false) {
  if (kind === 'user') {
    const verb = target.enabled ? 'Disable' : 'Enable'
    return asQuestion
      ? `${verb} ${target.fullName}?`
      : `${target.fullName} was ${target.enabled ? 'disabled' : 'enabled'}.`
  }
  const verb = target.accountStatus === 'FROZEN' ? 'Unfreeze' : 'Freeze'
  return asQuestion
    ? `${verb} account ${target.accountNumber}?`
    : `Account ${target.accountNumber} was ${target.accountStatus === 'FROZEN' ? 'unfrozen' : 'frozen'}.`
}
