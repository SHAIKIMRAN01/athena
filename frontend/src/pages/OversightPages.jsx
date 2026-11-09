import { useState } from 'react'
import * as athena from '../api/athena'
import { useAuth } from '../auth/AuthContext'
import { PageHeader } from '../components/AppLayout'
import { StatusBadge } from '../components/Badges'
import { Alert, EmptyState, Spinner } from '../components/Feedback'
import Pagination from '../components/Pagination'
import { formatDateTime, humanize, TYPE_LABELS } from '../lib/format'
import { formatMoney } from '../lib/money'
import { useAsync } from '../lib/useAsync'

// ------------------------------------------------------------- overview

export function OverviewPage() {
  const { hasRole } = useAuth()
  const summary = useAsync(() => athena.systemSummary(), [])
  const integrity = useAsync(() => athena.integrityReport(), [])

  return (
    <>
      <PageHeader
        title={hasRole('ADMIN') ? 'Administration' : 'Audit overview'}
        subtitle="System-wide numbers and a live reconciliation of the ledger. Read-only."
      />

      <Alert error={summary.error} />
      {summary.loading && !summary.data && <Spinner />}
      {summary.data && (
        <section className="cards">
          <Tile label="Customers" value={summary.data.customers} meta={`${summary.data.frozenAccounts} frozen · ${summary.data.disabledUsers} disabled users`} />
          <Tile label="Customer money held" value={formatMoney(summary.data.customerBalanceTotal)} meta="Sum of all customer balances" />
          <Tile label="Completed transactions" value={summary.data.completedTransactions} meta={`${summary.data.failedTransactions} refused attempt${summary.data.failedTransactions === 1 ? '' : 's'}`} />
          <Tile label="Deposited" value={formatMoney(summary.data.depositedTotal)} meta={`Withdrawn ${formatMoney(summary.data.withdrawnTotal)}`} />
          <Tile label="Transferred" value={formatMoney(summary.data.transferredTotal)} meta="Between customer accounts" />
          <Tile
            label="Security events (24 h)"
            value={summary.data.accessDeniedLast24h + summary.data.failedLoginsLast24h}
            meta={`${summary.data.failedLoginsLast24h} failed sign-ins · ${summary.data.accessDeniedLast24h} denied requests`}
          />
        </section>
      )}

      <section className="panel">
        <header className="panel-header">
          <h2>Ledger integrity</h2>
          <button type="button" className="button button-ghost" onClick={integrity.reload} disabled={integrity.loading}>
            {integrity.loading ? 'Checking…' : 'Run check again'}
          </button>
        </header>
        <Alert error={integrity.error} />
        {integrity.data && <IntegrityResult report={integrity.data} />}
      </section>
    </>
  )
}

function Tile({ label, value, meta }) {
  return (
    <article className="card">
      <span className="card-label">{label}</span>
      <strong className="card-value">{value}</strong>
      {meta && <span className="card-meta">{meta}</span>}
    </article>
  )
}

function IntegrityResult({ report }) {
  const checks = [
    ['Every transaction has equal debits and credits', report.unbalancedTransactionIds.length === 0, report.unbalancedTransactionIds.join(', ')],
    ['Every balance equals its ledger entries', report.accountsNotMatchingLedger.length === 0, report.accountsNotMatchingLedger.join(', ')],
    ['Settlement (cash) account equals customer money', report.settlementBalance === report.customerBalanceTotal,
      `${formatMoney(report.settlementBalance)} vs ${formatMoney(report.customerBalanceTotal)}`],
    ['Total debits equal total credits', report.totalDebits === report.totalCredits,
      `${formatMoney(report.totalDebits)} vs ${formatMoney(report.totalCredits)}`],
    ['Refused attempts moved no money', report.failedTransactionsWithLedgerEntries === 0, `${report.failedTransactionsWithLedgerEntries} entries`],
    ['Completed transactions have exactly two entries', report.completedTransactionsWithoutTwoEntries === 0, `${report.completedTransactionsWithoutTwoEntries} transactions`],
    ['No negative balances', report.negativeBalances === 0, `${report.negativeBalances} accounts`],
  ]

  return (
    <>
      <Alert kind={report.consistent ? 'success' : 'error'} title={report.consistent ? 'The books balance' : 'Inconsistency found'}>
        Checked {report.transactionsChecked} transactions and {report.ledgerEntriesChecked} ledger entries at{' '}
        {formatDateTime(report.checkedAt)}.
      </Alert>
      <ul className="checklist" data-testid="integrity-checks">
        {checks.map(([label, passed, detail]) => (
          <li key={label} className={passed ? 'check-pass' : 'check-fail'}>
            <span aria-hidden="true">{passed ? '✓' : '✗'}</span> {label}
            {!passed && <span className="muted"> — {detail}</span>}
          </li>
        ))}
      </ul>
    </>
  )
}

// ---------------------------------------------------------- transactions

const EMPTY_TX_FILTERS = { type: '', status: '', from: '', to: '', account: '', search: '', sort: 'createdAt,desc' }

export function MonitoringPage() {
  const [draft, setDraft] = useState(EMPTY_TX_FILTERS)
  const [filters, setFilters] = useState(EMPTY_TX_FILTERS)
  const [page, setPage] = useState(0)
  const list = useAsync(() => athena.monitoredTransactions({ ...filters, page, size: 15 }), [filters, page])
  const set = (field) => (event) => setDraft((current) => ({ ...current, [field]: event.target.value }))

  return (
    <>
      <PageHeader title="Transaction monitoring" subtitle="Every customer's transactions, including refused attempts. Read-only." />
      <form
        className="filters"
        onSubmit={(event) => {
          event.preventDefault()
          setPage(0)
          setFilters({ ...draft, account: draft.account.trim(), search: draft.search.trim() })
        }}
      >
        <label className="field"><span>Reference</span><input value={draft.search} onChange={set('search')} placeholder="TXN0000000042" maxLength={100} /></label>
        <label className="field"><span>Account</span><input value={draft.account} onChange={set('account')} placeholder="AT…" maxLength={30} /></label>
        <label className="field">
          <span>Type</span>
          <select value={draft.type} onChange={set('type')}>
            <option value="">All types</option>
            <option value="DEPOSIT">Deposits</option>
            <option value="WITHDRAWAL">Withdrawals</option>
            <option value="TRANSFER">Transfers</option>
          </select>
        </label>
        <label className="field">
          <span>Status</span>
          <select value={draft.status} onChange={set('status')}>
            <option value="">All</option>
            <option value="COMPLETED">Completed</option>
            <option value="FAILED">Failed</option>
          </select>
        </label>
        <label className="field"><span>From</span><input type="date" value={draft.from} onChange={set('from')} /></label>
        <label className="field"><span>To</span><input type="date" value={draft.to} onChange={set('to')} /></label>
        <label className="field">
          <span>Sort</span>
          <select value={draft.sort} onChange={set('sort')}>
            <option value="createdAt,desc">Newest first</option>
            <option value="amount,desc">Largest amount</option>
          </select>
        </label>
        <div className="filters-actions">
          <button type="submit" className="button button-primary">Apply</button>
          <button type="button" className="button button-ghost" onClick={() => { setDraft(EMPTY_TX_FILTERS); setFilters(EMPTY_TX_FILTERS); setPage(0) }}>Reset</button>
        </div>
      </form>

      <Alert error={list.error} />
      <section className="panel">
        {list.loading && !list.data && <Spinner />}
        {list.data?.content.length === 0 && <EmptyState title="No transactions match" />}
        {list.data?.content.length > 0 && (
          <div className="table-wrap">
            <table className="table table-cards">
              <thead>
                <tr><th>Date</th><th>Reference</th><th>Type</th><th>From</th><th>To</th><th>Initiated by</th><th className="numeric">Amount</th><th>Status</th></tr>
              </thead>
              <tbody>
                {list.data.content.map((tx) => (
                  <tr key={tx.transactionId}>
                    <td data-label="Date">{formatDateTime(tx.createdAt)}</td>
                    <td data-label="Reference"><code>{tx.reference}</code></td>
                    <td data-label="Type">{TYPE_LABELS[tx.type]}</td>
                    <td data-label="From">{tx.fromAccountNumber ? <code>{tx.fromAccountNumber}</code> : <span className="muted">Cash</span>}</td>
                    <td data-label="To">{tx.toAccountNumber ? <code>{tx.toAccountNumber}</code> : <span className="muted">Cash</span>}</td>
                    <td data-label="Initiated by">{tx.initiatedBy}</td>
                    <td data-label="Amount" className="numeric">{formatMoney(tx.amount)}</td>
                    <td data-label="Status">
                      <StatusBadge value={tx.status} />
                      {tx.failureCode && <span className="muted small"> {humanize(tx.failureCode)}</span>}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
        <Pagination page={list.data} onPageChange={setPage} />
      </section>
    </>
  )
}

// ------------------------------------------------------------- audit log

const ACTIONS = [
  'DEPOSIT', 'WITHDRAWAL', 'TRANSFER', 'LOGIN', 'LOGIN_LOCKED', 'LOGOUT', 'REGISTER',
  'ACCESS_DENIED', 'USER_DISABLED', 'USER_ENABLED', 'ACCOUNT_FROZEN', 'ACCOUNT_UNFROZEN',
]
const OUTCOMES = ['COMPLETED', 'FAILED', 'REJECTED', 'REPLAYED', 'DENIED', 'ERROR']
const EMPTY_AUDIT_FILTERS = { action: '', outcome: '', actor: '', from: '', to: '' }

export function AuditLogPage() {
  const [draft, setDraft] = useState(EMPTY_AUDIT_FILTERS)
  const [filters, setFilters] = useState(EMPTY_AUDIT_FILTERS)
  const [page, setPage] = useState(0)
  const list = useAsync(() => athena.auditLogs({ ...filters, page, size: 20 }), [filters, page])
  const set = (field) => (event) => setDraft((current) => ({ ...current, [field]: event.target.value }))

  return (
    <>
      <PageHeader title="Audit log" subtitle="Who did what, to which account, when, and how it ended. Records are never changed or deleted." />
      <form
        className="filters"
        onSubmit={(event) => {
          event.preventDefault()
          setPage(0)
          setFilters({ ...draft, actor: draft.actor.trim() })
        }}
      >
        <label className="field">
          <span>Action</span>
          <select value={draft.action} onChange={set('action')}>
            <option value="">All actions</option>
            {ACTIONS.map((action) => <option key={action} value={action}>{humanize(action)}</option>)}
          </select>
        </label>
        <label className="field">
          <span>Outcome</span>
          <select value={draft.outcome} onChange={set('outcome')}>
            <option value="">All outcomes</option>
            {OUTCOMES.map((outcome) => <option key={outcome} value={outcome}>{humanize(outcome)}</option>)}
          </select>
        </label>
        <label className="field"><span>Actor email</span><input type="email" value={draft.actor} onChange={set('actor')} maxLength={150} /></label>
        <label className="field"><span>From</span><input type="date" value={draft.from} onChange={set('from')} /></label>
        <label className="field"><span>To</span><input type="date" value={draft.to} onChange={set('to')} /></label>
        <div className="filters-actions">
          <button type="submit" className="button button-primary">Apply</button>
          <button type="button" className="button button-ghost" onClick={() => { setDraft(EMPTY_AUDIT_FILTERS); setFilters(EMPTY_AUDIT_FILTERS); setPage(0) }}>Reset</button>
        </div>
      </form>

      <Alert error={list.error} />
      <section className="panel">
        {list.loading && !list.data && <Spinner />}
        {list.data?.content.length === 0 && <EmptyState title="No audit records match" />}
        {list.data?.content.length > 0 && (
          <div className="table-wrap">
            <table className="table table-cards">
              <thead>
                <tr><th>When</th><th>Actor</th><th>Action</th><th>Outcome</th><th>Details</th></tr>
              </thead>
              <tbody>
                {list.data.content.map((record) => (
                  <tr key={record.id}>
                    <td data-label="When">{formatDateTime(record.createdAt)}</td>
                    <td data-label="Actor">{record.actorEmail ?? <span className="muted">anonymous</span>}</td>
                    <td data-label="Action">{humanize(record.action)}</td>
                    <td data-label="Outcome"><StatusBadge value={record.outcome} /></td>
                    <td data-label="Details" className="cell-block"><AuditDetails details={record.details} /></td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
        <Pagination page={list.data} onPageChange={setPage} />
      </section>
    </>
  )
}

function AuditDetails({ details }) {
  if (!details) return <span className="muted">—</span>
  return (
    <dl className="kv">
      {Object.entries(details).map(([key, value]) => (
        <div key={key}>
          <dt>{key}</dt>
          <dd>{typeof value === 'object' ? JSON.stringify(value) : String(value)}</dd>
        </div>
      ))}
    </dl>
  )
}
