import { Navigate, Route, Routes } from 'react-router-dom'
import { homePathFor, useAuth } from './auth/AuthContext'
import RequireAuth from './auth/RequireAuth'
import AppLayout from './components/AppLayout'
import { Spinner } from './components/Feedback'
import AdminUsersPage from './pages/AdminUsersPage'
import { LoginPage, RegisterPage } from './pages/AuthPages'
import DashboardPage from './pages/DashboardPage'
import { NotFoundPage } from './pages/ForbiddenPage'
import HistoryPage from './pages/HistoryPage'
import MoneyPage from './pages/MoneyPage'
import { AuditLogPage, MonitoringPage, OverviewPage } from './pages/OversightPages'
import StatementPage from './pages/StatementPage'

function Home() {
  const { status, user } = useAuth()
  if (status === 'loading') return <Spinner />
  return <Navigate to={status === 'authenticated' ? homePathFor(user) : '/login'} replace />
}

const customer = ['USER']
const staff = ['ADMIN', 'AUDITOR']

export default function App() {
  return (
    <Routes>
      <Route path="/login" element={<LoginPage />} />
      <Route path="/register" element={<RegisterPage />} />
      <Route path="/" element={<Home />} />

      <Route element={<RequireAuth><AppLayout /></RequireAuth>}>
        <Route path="/dashboard" element={<RequireAuth roles={customer}><DashboardPage /></RequireAuth>} />
        <Route path="/move-money" element={<RequireAuth roles={customer}><MoneyPage /></RequireAuth>} />
        <Route path="/history" element={<RequireAuth roles={customer}><HistoryPage /></RequireAuth>} />
        <Route path="/statement" element={<RequireAuth roles={customer}><StatementPage /></RequireAuth>} />
        <Route path="/oversight" element={<RequireAuth roles={staff}><OverviewPage /></RequireAuth>} />
        <Route path="/oversight/transactions" element={<RequireAuth roles={staff}><MonitoringPage /></RequireAuth>} />
        <Route path="/oversight/audit-log" element={<RequireAuth roles={staff}><AuditLogPage /></RequireAuth>} />
        <Route path="/admin/users" element={<RequireAuth roles={['ADMIN']}><AdminUsersPage /></RequireAuth>} />
        <Route path="*" element={<NotFoundPage />} />
      </Route>
    </Routes>
  )
}
