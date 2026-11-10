import { useEffect, useState } from 'react'
import { NavLink, Outlet } from 'react-router-dom'
import { useAuth } from '../auth/AuthContext'
import { RoleBadge } from './Badges'
import BrandMark from './BrandMark'
import { CloseIcon, MenuIcon } from './Icons'

const NAVIGATION = [
  { to: '/dashboard', label: 'Dashboard', roles: ['USER'] },
  { to: '/move-money', label: 'Move money', roles: ['USER'] },
  { to: '/history', label: 'History', roles: ['USER'] },
  { to: '/statement', label: 'Statement', roles: ['USER'] },
  { to: '/oversight', label: 'Overview', roles: ['ADMIN', 'AUDITOR'], end: true },
  { to: '/oversight/transactions', label: 'Transactions', roles: ['ADMIN', 'AUDITOR'] },
  { to: '/oversight/audit-log', label: 'Audit log', roles: ['ADMIN', 'AUDITOR'] },
  { to: '/admin/users', label: 'Users', roles: ['ADMIN'] },
  { to: '/profile', label: 'Profile', roles: ['USER', 'ADMIN', 'AUDITOR'] },
]

function useMinutesLeft(expiresAt) {
  const [now, setNow] = useState(() => Date.now())
  useEffect(() => {
    const timer = setInterval(() => setNow(Date.now()), 30_000)
    return () => clearInterval(timer)
  }, [])
  return expiresAt ? Math.max(0, Math.ceil((expiresAt - now) / 60_000)) : null
}

/**
 * The signed-in frame. On wide screens the menu is a sidebar; on tablets and
 * phones it folds into a top bar with a Menu button (see index.css).
 */
export default function AppLayout() {
  const { user, hasRole, signOut, expiresAt } = useAuth()
  const minutesLeft = useMinutesLeft(expiresAt)
  const [menuOpen, setMenuOpen] = useState(false)

  // Escape closes the phone menu, as it closes dialogs.
  useEffect(() => {
    if (!menuOpen) return undefined
    const onKey = (event) => {
      if (event.key === 'Escape') setMenuOpen(false)
    }
    document.addEventListener('keydown', onKey)
    return () => document.removeEventListener('keydown', onKey)
  }, [menuOpen])

  const links = NAVIGATION.filter((item) => hasRole(...item.roles))

  return (
    <div className="shell">
      <a className="skip-link" href="#main">
        Skip to content
      </a>
      <aside className={`sidebar ${menuOpen ? 'sidebar-open' : ''}`}>
        <div className="brand">
          <BrandMark size={32} />
          <span className="brand-name">Athena</span>
          <button
            type="button"
            className="menu-toggle"
            aria-expanded={menuOpen}
            aria-controls="main-menu"
            aria-label="Menu"
            onClick={() => setMenuOpen((open) => !open)}
          >
            {menuOpen ? <CloseIcon size={22} /> : <MenuIcon size={22} />}
          </button>
        </div>

        <div className="sidebar-menu" id="main-menu">
          <nav className="nav" aria-label="Main">
            {links.map((item) => (
              <NavLink
                key={item.to}
                to={item.to}
                end={item.end}
                className={({ isActive }) => `nav-link ${isActive ? 'nav-link-active' : ''}`}
                onClick={() => setMenuOpen(false)}
              >
                {item.label}
              </NavLink>
            ))}
          </nav>

          <div className="sidebar-footer">
            <div className="user-card">
              <span className="user-name">{user?.fullName}</span>
              <span className="user-email">{user?.email}</span>
              <span className="user-roles">
                {user?.roles?.map((role) => (
                  <RoleBadge key={role} role={role} />
                ))}
              </span>
            </div>
            {minutesLeft !== null && <p className="session-note">Session ends in {minutesLeft} min</p>}
            <button type="button" className="button button-ghost button-block" onClick={signOut}>
              Sign out
            </button>
          </div>
        </div>
      </aside>

      <main className="content" id="main" tabIndex={-1}>
        <Outlet />
      </main>
    </div>
  )
}

export function PageHeader({ title, subtitle, actions }) {
  return (
    <header className="page-header">
      <div>
        <h1>{title}</h1>
        {subtitle && <p className="page-subtitle">{subtitle}</p>}
      </div>
      {actions && <div className="page-actions">{actions}</div>}
    </header>
  )
}
