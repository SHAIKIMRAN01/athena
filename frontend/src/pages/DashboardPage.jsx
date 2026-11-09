import { useState } from 'react'
import { Link } from 'react-router-dom'
import * as athena from '../api/athena'
import { useAuth } from '../auth/AuthContext'
import { PageHeader } from '../components/AppLayout'
import { StatusBadge } from '../components/Badges'
import { Alert, EmptyState, Spinner } from '../components/Feedback'
import { CheckIcon, ChevronRightIcon, CopyIcon, DepositIcon, TransferIcon, WithdrawIcon } from '../components/Icons'
import { firstOfMonth, formatDateTime, isoDate, TYPE_LABELS } from '../lib/format'
import { formatMoney } from '../lib/money'
import { useAsync } from '../lib/useAsync'

export default function DashboardPage() {
  const { user } = useAuth()
  const [copyState, setCopyState] = useState('idle') // idle | copied | failed

  const overview = useAsync(
    () =>
      Promise.all([
        athena.myAccount(),
        athena.history({ page: 0, size: 5, sort: 'createdAt,desc' }),
        athena.statement({ from: firstOfMonth(), to: isoDate(new Date()) }),
      ]).then(([account, recent, month]) => ({ account, recent, month })),
    [],
  )

  async function copyAccountNumber() {
    try {
      await navigator.clipboard.writeText(overview.data.account.accountNumber)
      setCopyState('copied')
    } catch {
      setCopyState('failed')
    }
    setTimeout(() => setCopyState('idle'), 2500)
  }

  const firstName = user?.fullName?.split(' ')[0]

  return (
    <>
      <PageHeader title={`Hello, ${firstName ?? 'there'}`} subtitle="Your account at a glance." />

      <Alert error={overview.error} />
      {overview.loading && !overview.data && <Spinner />}

      {overview.data && (
        <>
          {overview.data.account.status !== 'ACTIVE' && (
            <Alert kind="warning" title="Your account is frozen">
              Deposits, withdrawals and transfers are blocked until an administrator unfreezes it.
            </Alert>
          )}

          <section className="cards">
            <article className="card card-balance">
              <span className="card-label">Available balance</span>
              <strong className="card-value" data-testid="balance">
                {formatMoney(overview.data.account.balance)}
              </strong>
              <span className="card-meta card-meta-row">
                <span>
                  Account <code>{overview.data.account.accountNumber}</code>
                </span>
                <button
                  type="button"
                  className="copy-button"
                  onClick={copyAccountNumber}
                  aria-label={`Copy account number ${overview.data.account.accountNumber}`}
                >
                  {copyState === 'copied' ? <CheckIcon size={16} /> : <CopyIcon size={16} />}
                  <span aria-hidden="true">{copyState === 'copied' ? 'Copied' : 'Copy'}</span>
                </button>
                <span className="visually-hidden" role="status" aria-live="polite">
                  {copyState === 'copied' && 'Account number copied'}
                  {copyState === 'failed' && 'Could not copy. Select the account number and copy it manually.'}
                </span>
              </span>
            </article>
            <article className="card">
              <span className="card-label">Money in this month</span>
              <strong className="card-value card-value-positive">
                {formatMoney(overview.data.month.totalCredits)}
              </strong>
              <span className="card-meta">Deposits and transfers received</span>
            </article>
            <article className="card">
              <span className="card-label">Money out this month</span>
              <strong className="card-value">{formatMoney(overview.data.month.totalDebits)}</strong>
              <span className="card-meta">Withdrawals and transfers sent</span>
            </article>
            <article className="card">
              <span className="card-label">Account status</span>
              <strong className="card-value card-value-small">
                <StatusBadge value={overview.data.account.status} />
              </strong>
              <span className="card-meta">Opened {formatDateTime(overview.data.account.createdAt)}</span>
            </article>
          </section>

          <section className="quick-actions" aria-label="Move money">
            <QuickAction
              to="/move-money?type=deposit"
              icon={<DepositIcon />}
              title="Deposit"
              text="Add cash to your account"
            />
            <QuickAction to="/move-money?type=withdraw" icon={<WithdrawIcon />} title="Withdraw" text="Take cash out" />
            <QuickAction
              to="/move-money?type=transfer"
              icon={<TransferIcon />}
              title="Transfer"
              text="Send to another Athena account"
            />
          </section>

          <section className="panel">
            <header className="panel-header">
              <h2>Recent activity</h2>
              <Link to="/history" className="text-link" aria-label="See all transactions">
                See all <ChevronRightIcon size={16} />
              </Link>
            </header>
            {overview.data.recent.content.length === 0 ? (
              <EmptyState title="No transactions yet">Make a deposit to get started.</EmptyState>
            ) : (
              <ul className="activity">
                {overview.data.recent.content.map((item) => (
                  <li key={item.transactionId} className="activity-item">
                    <div>
                      <strong>{TYPE_LABELS[item.type]}</strong>
                      <span className="muted">
                        {formatDateTime(item.createdAt)} · {item.reference}
                      </span>
                    </div>
                    <div className="activity-amount">
                      <span
                        className={
                          item.status === 'FAILED'
                            ? 'amount-failed'
                            : item.direction === 'IN'
                              ? 'amount-in'
                              : 'amount-out'
                        }
                      >
                        {item.direction === 'IN' ? '+' : '−'}
                        {formatMoney(item.amount)}
                      </span>
                      <StatusBadge value={item.status} />
                    </div>
                  </li>
                ))}
              </ul>
            )}
          </section>
        </>
      )}
    </>
  )
}

function QuickAction({ to, icon, title, text }) {
  return (
    <Link to={to} className="quick-action">
      <span className="quick-action-icon">{icon}</span>
      <span className="quick-action-text">
        <strong>{title}</strong>
        <span>{text}</span>
      </span>
      <span className="quick-action-chevron">
        <ChevronRightIcon />
      </span>
    </Link>
  )
}
