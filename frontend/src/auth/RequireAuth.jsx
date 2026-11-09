import { Navigate, useLocation } from 'react-router-dom'
import { useAuth } from './AuthContext'
import { Spinner } from '../components/Feedback'
import ForbiddenPage from '../pages/ForbiddenPage'

/**
 * Shows its children only to signed-in users with one of `roles` (any
 * signed-in user if no roles are given). Anyone else is sent to the
 * sign-in page, and comes back here after signing in.
 */
export default function RequireAuth({ roles, children }) {
  const { status, hasRole } = useAuth()
  const location = useLocation()

  if (status === 'loading') return <Spinner label="Checking your session…" />
  if (status !== 'authenticated') {
    return <Navigate to="/login" replace state={{ from: location.pathname + location.search }} />
  }
  if (roles && !hasRole(...roles)) return <ForbiddenPage />

  return children
}
