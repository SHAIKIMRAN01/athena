import { useState } from 'react'
import { useSearchParams } from 'react-router-dom'
import * as athena from '../api/athena'
import { PageHeader } from '../components/AppLayout'
import { StatusBadge } from '../components/Badges'
import Dialog from '../components/Dialog'
import { Alert, EmptyState, Spinner } from '../components/Feedback'
import Pagination from '../components/Pagination'
import { formatDateTime, humanize, TYPE_LABELS } from '../lib/format'
import { formatMoney } from '../lib/money'
import { useAsync } from '../lib/useAsync'

const EMPTY_FILTERS = { type: '', status: '', from: '', to: '', search: '', sort: 'createdAt,desc' }

export default function HistoryPage() {
  const [params, setParams] = useSearchParams()
  const [draft, setDraft] = useState(EMPTY_FILTERS)
  const [filters, setFilters] = useState(EMPTY_FILTERS)
  const [page, setPage] = useState(0)

  const selectedId = params.get('tx')

  const list = useAsync(
    () => athena.history({ ...filters, page, size: 10 }),
    [filters, page],
  )

  function apply(event) {
    event.preventDefault()
    setPage(0)
    setFilters({ ...draft, search: draft.search.trim() })
  }

  function reset() {
    setDraft(EMPTY_FILTERS)
    setFilters(EMPTY_FILTERS)
    setPage(0)
  }

  const set = (field) => (event) => setDraft((current) => ({ ...current, [field]: event.target.value }))

  return (
    <>
      <PageHeader title="Transaction history" subtitle="Everything that moved money in or out of your account, and attempts that were refused." />

      <form className="filters" onSubmit={apply} aria-label="Filters">
        <label className="field">
          <span>Search</span>
          <input value={draft.search} onChange={set('search')} placeholder="TXN0000000042 or AT… account" maxLength={100} />
        </label>
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
        <label className="field">
          <span>From</span>
          <input type="date" value={draft.from} onChange={set('from')} />
        </label>
        <label className="field">
          <span>To</span>
          <input type="date" value={draft.to} onChange={set('to')} />
        </label>
        <label className="field">
          <span>Sort</span>
          <select value={draft.sort} onChange={set('sort')}>
            <option value="createdAt,desc">Newest first</option>
            <option value="createdAt,asc">Oldest first</option>
            <option value="amount,desc">Largest amount</option>
            <option value="amount,asc">Smallest amount</option>
          </select>
        </label>
        <div className="filters-actions">
          <button type="submit" className="button button-primary">Apply</button>
          <button type="button" className="button button-ghost" onClick={reset}>Reset</button>
        </div>
      </form>

      <Alert error={list.error} />

      <section className="panel">
        {list.loading && !list.data && <Spinner />}
        {list.data && list.data.content.length === 0 && (
          <EmptyState title="Nothing found">Try other filters, or make your first deposit.</EmptyState>
        )}
        {list.data && list.data.content.length > 0 && (
          <div className="table-wrap">
            <table className="table table-cards">
              <thead>
                <tr>
                  <th>Date</th>
                  <th>Reference</th>
                  <th>Type</th>
                  <th>Counterparty</th>
                  <th className="numeric">Amount</th>
                  <th>Status</th>
                </tr>
              </thead>
              <tbody>
                {list.data.content.map((item) => (
                  <tr
                    key={item.transactionId}
                    className="table-row-link"
                    tabIndex={0}
                    onClick={() => setParams({ tx: item.transactionId })}
                    onKeyDown={(e) => e.key === 'Enter' && setParams({ tx: item.transactionId })}
                  >
                    <td data-label="Date">{formatDateTime(item.createdAt)}</td>
                    <td data-label="Reference"><code>{item.reference}</code></td>
                    <td data-label="Type">{TYPE_LABELS[item.type]}</td>
                    <td data-label="Counterparty">{item.counterpartyAccountNumber ? <code>{item.counterpartyAccountNumber}</code> : <span className="muted">Cash</span>}</td>
                    <td data-label="Amount" className={`numeric ${item.status === 'FAILED' ? 'amount-failed' : item.direction === 'IN' ? 'amount-in' : 'amount-out'}`}>
                      {item.direction === 'IN' ? '+' : '−'}
                      {formatMoney(item.amount)}
                    </td>
                    <td data-label="Status"><StatusBadge value={item.status} /></td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
        <Pagination page={list.data} onPageChange={setPage} />
      </section>

      <TransactionDetailDialog id={selectedId} onClose={() => setParams({})} />
    </>
  )
}

function TransactionDetailDialog({ id, onClose }) {
  const detail = useAsync(() => (id ? athena.transactionDetail(id) : Promise.resolve(null)), [id])
  const item = detail.data?.transaction

  return (
    <Dialog open={Boolean(id)} title="Transaction details" onClose={onClose}>
      {detail.loading && <Spinner />}
      <Alert error={detail.error} />
      {item && (
        <>
          <dl className="details">
            <dt>Reference</dt>
            <dd><code>{item.reference}</code></dd>
            <dt>Type</dt>
            <dd>{TYPE_LABELS[item.type]} ({item.direction === 'IN' ? 'money in' : 'money out'})</dd>
            <dt>Amount</dt>
            <dd>{formatMoney(item.amount)}</dd>
            <dt>Status</dt>
            <dd><StatusBadge value={item.status} /></dd>
            <dt>Counterparty</dt>
            <dd>{item.counterpartyAccountNumber ? <code>{item.counterpartyAccountNumber}</code> : 'Cash (settlement account)'}</dd>
            <dt>Requested</dt>
            <dd>{formatDateTime(item.createdAt)}</dd>
            <dt>Completed</dt>
            <dd>{formatDateTime(item.completedAt)}</dd>
            {item.failureCode && (
              <>
                <dt>Refused because</dt>
                <dd>{item.failureReason} <span className="muted">({humanize(item.failureCode)})</span></dd>
              </>
            )}
          </dl>
          <h3 className="section-title">Ledger entries</h3>
          {detail.data.ledgerEntries.length === 0 ? (
            <p className="muted">No entries: this attempt was refused, so no money moved.</p>
          ) : (
            <table className="table table-compact">
              <thead>
                <tr><th>Entry</th><th>Account</th><th className="numeric">Amount</th></tr>
              </thead>
              <tbody>
                {detail.data.ledgerEntries.map((line) => (
                  <tr key={`${line.entryType}-${line.accountNumber}`}>
                    <td>
                      <span className={`badge badge-${line.entryType === 'CREDIT' ? 'success' : 'muted'}`}>
                        {humanize(line.entryType)}
                      </span>
                    </td>
                    <td><code>{line.accountNumber}</code></td>
                    <td className="numeric">{formatMoney(line.amount)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
          <p className="hint">Every completed transaction has one debit and one credit of the same amount.</p>
        </>
      )}
    </Dialog>
  )
}
