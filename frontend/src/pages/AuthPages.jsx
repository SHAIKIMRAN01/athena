import { useState } from 'react'
import { Link, Navigate, useLocation, useNavigate } from 'react-router-dom'
import { homePathFor, useAuth } from '../auth/AuthContext'
import * as athena from '../api/athena'
import BrandMark from '../components/BrandMark'
import { Alert } from '../components/Feedback'
import { validateRegistration } from '../lib/validation'

function AuthCard({ title, subtitle, children }) {
  return (
    <div className="auth-page">
      <section className="auth-hero" aria-hidden="true">
        <BrandMark size={56} />
        <h2>Athena</h2>
        <p>Every transaction accurate. Every movement authorized. Every record traceable.</p>
        <ul>
          <li>Double-entry ledger: every rupee has two sides</li>
          <li>Idempotent payments: a retry never pays twice</li>
          <li>Full audit trail for every action</li>
        </ul>
      </section>
      <section className="auth-card">
        {/* On tablets and phones the panel above is hidden; keep the name visible. */}
        <div className="auth-brand" aria-hidden="true">
          <BrandMark size={36} />
          <span className="brand-name">Athena</span>
        </div>
        <h1>{title}</h1>
        <p className="page-subtitle">{subtitle}</p>
        {children}
      </section>
    </div>
  )
}

export function LoginPage() {
  const { status, user, signIn, notice, clearNotice } = useAuth()
  const navigate = useNavigate()
  const location = useLocation()
  const [email, setEmail] = useState(location.state?.email ?? '')
  const [password, setPassword] = useState('')
  const [error, setError] = useState(null)
  const [busy, setBusy] = useState(false)

  if (status === 'authenticated') return <Navigate to={homePathFor(user)} replace />

  async function submit(event) {
    event.preventDefault()
    setError(null)
    clearNotice()
    setBusy(true)
    try {
      const me = await signIn(email.trim(), password)
      const from = location.state?.from
      navigate(from && from !== '/login' ? from : homePathFor(me), { replace: true })
    } catch (failure) {
      setError(failure)
      setPassword('')
    } finally {
      setBusy(false)
    }
  }

  return (
    <AuthCard title="Sign in" subtitle="Use the email and password you registered with.">
      {notice && <Alert kind="warning">{notice}</Alert>}
      {location.state?.registered && !error && (
        <Alert kind="success">Your account is ready. Sign in to continue.</Alert>
      )}
      <Alert error={error} />
      <form className="form" onSubmit={submit} noValidate>
        <label className="field">
          <span>Email</span>
          <input type="email" autoComplete="username" value={email} onChange={(e) => setEmail(e.target.value)} required maxLength={150} />
        </label>
        <label className="field">
          <span>Password</span>
          <input type="password" autoComplete="current-password" value={password} onChange={(e) => setPassword(e.target.value)} required maxLength={200} />
        </label>
        <button type="submit" className="button button-primary button-block" disabled={busy || !email || !password}>
          {busy ? 'Signing in…' : 'Sign in'}
        </button>
      </form>
      <p className="auth-switch">
        New to Athena? <Link to="/register">Create an account</Link>
      </p>
    </AuthCard>
  )
}

export function RegisterPage() {
  const { status, user } = useAuth()
  const navigate = useNavigate()
  const [form, setForm] = useState({ fullName: '', email: '', password: '', confirm: '' })
  const [errors, setErrors] = useState({})
  const [error, setError] = useState(null)
  const [busy, setBusy] = useState(false)

  if (status === 'authenticated') return <Navigate to={homePathFor(user)} replace />

  const update = (field) => (event) => setForm((current) => ({ ...current, [field]: event.target.value }))

  async function submit(event) {
    event.preventDefault()
    setError(null)
    const found = validateRegistration(form)
    setErrors(found)
    if (Object.keys(found).length > 0) return

    setBusy(true)
    try {
      await athena.register({
        fullName: form.fullName.trim(),
        email: form.email.trim(),
        password: form.password,
      })
      navigate('/login', { replace: true, state: { registered: true, email: form.email.trim() } })
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
    <AuthCard title="Create your account" subtitle="You get a Athena account number straight away.">
      <Alert error={error} />
      <form className="form" onSubmit={submit} noValidate>
        {field('fullName', 'Full name', { autoComplete: 'name', maxLength: 100 })}
        {field('email', 'Email', { type: 'email', autoComplete: 'email', maxLength: 150 })}
        {field('password', 'Password', { type: 'password', autoComplete: 'new-password', maxLength: 72 })}
        {field('confirm', 'Repeat password', { type: 'password', autoComplete: 'new-password', maxLength: 72 })}
        <p className="hint">8 to 72 characters, with at least one letter and one digit.</p>
        <button type="submit" className="button button-primary button-block" disabled={busy}>
          {busy ? 'Creating account…' : 'Create account'}
        </button>
      </form>
      <p className="auth-switch">
        Already registered? <Link to="/login">Sign in</Link>
      </p>
    </AuthCard>
  )
}
