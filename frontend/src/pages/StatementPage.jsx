import { useState } from 'react'
import * as athena from '../api/athena'
import { PageHeader } from '../components/AppLayout'
import { Alert, EmptyState, Spinner } from '../components/Feedback'
import { firstOfMonth, formatDate, formatDateTime, isoDate } from '../lib/format'
import { formatMoney } from '../lib/money'
import { useAsync } from '../lib/useAsync'
import { statementCsv } from '../lib/csv'

export default function StatementPage() {
  const [range, setRange] = useState(() => ({ from: firstOfMonth(), to: isoDate(new Date()) }))
  const [draft, setDraft] = useState(range)

  const statement = useAsync(() => athena.statement(range), [range])
  const data = statement.data

  function apply(event) {
    event.preventDefault()
    setRange(draft)
  }

  function download() {
    const blob = new Blob([statementCsv(data)], { type: 'text/csv;charset=utf-8' })
    const url = URL.createObjectURL(blob)
    const link = document.createElement('a')
    link.href = url
    link.download = `athena-statement-${data.accountNumber}-${data.from}-to-${data.to}.csv`
    link.click()
    URL.revokeObjectURL(url)
  }

  return (
    <>
      <PageHeader
        title="Account statement"
        subtitle="Opening and closing balances, money in and out, built from the ledger."
        actions={
          data && (
            <>
              <button type="button" className="button button-ghost" onClick={download}>
                Download CSV
              </button>
              <button type="button" className="button button-ghost" onClick={() => window.print()}>
                Print
              </button>
            </>
          )
        }
      />

      <form className="filters filters-compact no-print" onSubmit={apply}>
        <label className="field">
          <span>From</span>
          <input
            type="date"
            value={draft.from}
            max={draft.to}
            onChange={(e) => setDraft({ ...draft, from: e.target.value })}
            required
          />
        </label>
        <label className="field">
          <span>To</span>
          <input
            type="date"
            value={draft.to}
            min={draft.from}
            onChange={(e) => setDraft({ ...draft, to: e.target.value })}
            required
          />
        </label>
        <div className="filters-actions">
          <button type="submit" className="button button-primary">
            Show
          </button>
        </div>
      </form>

      <Alert error={statement.error} />
      {statement.loading && !data && <Spinner />}

      {data && (
        <section className="panel statement">
          <header className="statement-header">
            <div>
              <span className="card-label">Account</span>
              <strong>
                <code>{data.accountNumber}</code>
              </strong>
            </div>
            <div>
              <span className="card-label">Period</span>
              <strong>
                {formatDate(data.from)} – {formatDate(data.to)}
              </strong>
            </div>
          </header>

          <div className="statement-totals">
            <div>
              <span className="card-label">Opening balance</span>
              <strong data-testid="opening">{formatMoney(data.openingBalance)}</strong>
            </div>
            <div>
              <span className="card-label">Money in</span>
              <strong className="amount-in" data-testid="credits">
                {formatMoney(data.totalCredits, { sign: true })}
              </strong>
            </div>
            <div>
              <span className="card-label">Money out</span>
              <strong className="amount-out" data-testid="debits">
                −{formatMoney(data.totalDebits)}
              </strong>
            </div>
            <div>
              <span className="card-label">Closing balance</span>
              <strong data-testid="closing">{formatMoney(data.closingBalance)}</strong>
            </div>
          </div>

          {data.lines.length === 0 ? (
            <EmptyState title="No activity in this period" />
          ) : (
            <div className="table-wrap">
              <table className="table table-cards">
                <thead>
                  <tr>
                    <th>Date</th>
                    <th>Reference</th>
                    <th>Description</th>
                    <th className="numeric">Money in</th>
                    <th className="numeric">Money out</th>
                    <th className="numeric">Balance</th>
                  </tr>
                </thead>
                <tbody>
                  {data.lines.map((line) => (
                    <tr key={`${line.reference}-${line.balanceAfter}`}>
                      <td data-label="Date">{formatDateTime(line.date)}</td>
                      <td data-label="Reference">
                        <code>{line.reference}</code>
                      </td>
                      <td data-label="Description">{line.description}</td>
                      <td data-label="Money in" className="numeric amount-in">
                        {line.credit ? formatMoney(line.credit) : ''}
                      </td>
                      <td data-label="Money out" className="numeric amount-out">
                        {line.debit ? formatMoney(line.debit) : ''}
                      </td>
                      <td data-label="Balance" className="numeric">
                        {formatMoney(line.balanceAfter)}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
          <p className="hint">Refused attempts move no money, so they never appear on a statement.</p>
        </section>
      )}
    </>
  )
}
