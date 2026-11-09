import { Link } from 'react-router-dom'
import { homePathFor, useAuth } from '../auth/AuthContext'

export default function ForbiddenPage() {
  const { user } = useAuth()
  return (
    <div className="message-page">
      <h1>No access</h1>
      <p>Your role does not allow this page. The server refuses its data too, and records such attempts in the audit log.</p>
      <Link to={homePathFor(user)} className="button button-primary">Go to your start page</Link>
    </div>
  )
}

export function NotFoundPage() {
  return (
    <div className="message-page">
      <h1>Page not found</h1>
      <p>There is nothing at this address.</p>
      <Link to="/" className="button button-primary">Go home</Link>
    </div>
  )
}
