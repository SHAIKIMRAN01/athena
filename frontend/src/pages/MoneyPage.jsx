import { useMemo, useRef, useState } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import * as athena from '../api/athena'
import { PageHeader } from '../components/AppLayout'
import Dialog from '../components/Dialog'
import { Alert } from '../components/Feedback'
import { formatDateTime } from '../lib/format'
import { createSubmissionTracker } from '../lib/idempotency'
import { compareAmounts, formatMoney, parseAmountInput } from '../lib/money'
import { useAsync } from '../lib/useAsync'

const OPERATIONS = {
  deposit: {
    label: 'Deposit',
    verb: 'Deposit',
    help: 'Cash paid in at a branch. It is added to your balance at once.',
    send: (form, key) => athena.deposit(form.amount, key),
  },
  withdraw: {
    label: 'Withdraw',
    verb: 'Withdraw',
    help: 'Cash paid out. You cannot withdraw more than your balance.',
    send: (form, key) => athena.withdraw(form.amount, key),
  },
  transfer: {
    label: 'Transfer',
    verb: 'Send',
    help: 'Send money to another Athena account. You will be asked to confirm.',
    send: (form, key) => athena.transfer(form.to, form.amount, key),
  },
}

// AT + 12 letters or digits. Accounts opened before the rename to Athena keep
// their LL numbers, so those are accepted too.
const ACCOUNT_NUMBER = /^(AT|LL)[0-9A-Z]{12}$/

export default function MoneyPage() {
  const [params, setParams] = useSearchParams()
  const type = OPERATIONS[params.get('type')] ? params.get('type') : 'deposit'
  const operation = OPERATIONS[type]

  const account = useAsync(() => athena.myAccount(), [])
  const tracker = useRef(createSubmissionTracker())

  const [amountText, setAmountText] = useState('')
  const [toText, setToText] = useState('')
  const [fieldErrors, setFieldErrors] = useState({})
  const [error, setError] = useState(null)
  const [result, setResult] = useState(null)
  const [busy, setBusy] = useState(false)
  const [confirming, setConfirming] = useState(null)

  const balance = account.data?.balance

  function chooseType(next) {
    setParams({ type: next })
    setFieldErrors({})
    setError(null)
    setResult(null)
  }

  /** Checks the form; returns the normalized request or null. */
  function validate() {
    const errors = {}
    const amount = parseAmountInput(amountText)
    if (amount.error) errors.amount = amount.error

    const to = toText.trim().toUpperCase()
    if (type === 'transfer') {
      if (!ACCOUNT_NUMBER.test(to)) errors.to = 'Account numbers look like AT followed by 12 letters or digits.'
      else if (to === account.data?.accountNumber) errors.to = 'You cannot transfer to your own account.'
    }
    if (!errors.amount && type !== 'deposit' && balance !== undefined && compareAmounts(amount.value, balance) > 0) {
      errors.amount = `That is more than your balance of ${formatMoney(balance)}.`
    }

    setFieldErrors(errors)
    return Object.keys(errors).length ? null : { amount: amount.value, to }
  }

  function submit(event) {
    event.preventDefault()
    setError(null)
    setResult(null)
    const form = validate()
    if (!form) return
    if (type === 'transfer') setConfirming(form)
    else send(form)
  }

  async function send(form) {
    setConfirming(null)
    setBusy(true)
    const key = tracker.current.keyFor({ type, ...form })
    try {
      const outcome = await operation.send(form, key)
      tracker.current.settled()
      setResult(outcome)
      setAmountText('')
      setToText('')
      account.reload()
    } catch (failure) {
      if (failure.outcomeUnknown) {
        // Keep the key: pressing the button again for the same request
        // cannot move the money twice.
        tracker.current.unknownOutcome()
      } else {
        tracker.current.settled()
        account.reload()
      }
      setError(failure)
      setFieldErrors(mapFieldErrors(failure.fieldErrors))
    } finally {
      setBusy(false)
    }
  }

  const tabs = useMemo(() => Object.entries(OPERATIONS), [])

  return (
    <>
      <PageHeader
        title="Move money"
        subtitle={
          balance !== undefined ? (
            <>
              Available balance <strong data-testid="money-balance">{formatMoney(balance)}</strong>
            </>
          ) : (
            'Deposit, withdraw or transfer.'
          )
        }
      />

      <div className="tabs" role="tablist" aria-label="Operation">
        {tabs.map(([key, item]) => (
          <button
            key={key}
            type="button"
            role="tab"
            aria-selected={key === type}
            className={`tab ${key === type ? 'tab-active' : ''}`}
            onClick={() => chooseType(key)}
          >
            {item.label}
          </button>
        ))}
      </div>

      <section className="panel panel-narrow">
        <p className="hint">{operation.help}</p>

        {result && (
          <Alert kind={result.replayed ? 'info' : 'success'} title={result.replayed ? 'Already done' : 'Done'}>
            {result.replayed
              ? 'This request had already been processed, so nothing was charged again. Showing the original result.'
              : `${operation.label} of ${formatMoney(result.transaction.amount)} completed.`}{' '}
            Reference <code>{result.transaction.reference}</code> · balance now{' '}
            {formatMoney(result.transaction.balanceAfter)} ·{' '}
            <Link to={`/history?tx=${result.transaction.transactionId}`}>view details</Link>
          </Alert>
        )}

        {error && (
          <Alert error={error} title={error.outcomeUnknown ? 'We could not confirm this request' : 'Not done'} />
        )}
        {error?.outcomeUnknown && (
          <p className="hint">
            Pressing {operation.verb} again with the same details is safe: it reuses this request&apos;s
            Idempotency-Key, so the money cannot move twice.
          </p>
        )}

        <form className="form" onSubmit={submit} noValidate>
          {type === 'transfer' && (
            <label className={`field ${fieldErrors.to ? 'field-invalid' : ''}`}>
              <span>Receiver account number</span>
              <input
                value={toText}
                onChange={(e) => setToText(e.target.value)}
                placeholder="AT0123456789AB"
                maxLength={30}
                autoComplete="off"
                aria-invalid={Boolean(fieldErrors.to)}
              />
              {fieldErrors.to && <small className="field-error">{fieldErrors.to}</small>}
            </label>
          )}

          <label className={`field ${fieldErrors.amount ? 'field-invalid' : ''}`}>
            <span>Amount (₹)</span>
            <input
              inputMode="decimal"
              value={amountText}
              onChange={(e) => setAmountText(e.target.value)}
              placeholder="0.00"
              maxLength={21}
              autoComplete="off"
              aria-invalid={Boolean(fieldErrors.amount)}
            />
            {fieldErrors.amount && <small className="field-error">{fieldErrors.amount}</small>}
          </label>

          <button
            type="submit"
            className="button button-primary button-block"
            disabled={busy || account.data?.status === 'FROZEN'}
          >
            {busy ? 'Working…' : operation.verb}
          </button>
          {account.data?.status === 'FROZEN' && (
            <p className="field-error">Your account is frozen, so money cannot be moved.</p>
          )}
        </form>
      </section>

      <Dialog
        open={Boolean(confirming)}
        title="Confirm transfer"
        onClose={() => setConfirming(null)}
        footer={
          <>
            <button type="button" className="button button-ghost" onClick={() => setConfirming(null)}>
              Cancel
            </button>
            <button type="button" className="button button-primary" onClick={() => send(confirming)}>
              Send {confirming && formatMoney(confirming.amount)}
            </button>
          </>
        }
      >
        {confirming && (
          <dl className="details">
            <dt>To account</dt>
            <dd>
              <code>{confirming.to}</code>
            </dd>
            <dt>Amount</dt>
            <dd>{formatMoney(confirming.amount)}</dd>
            <dt>Balance after</dt>
            <dd>{balance !== undefined ? formatMoney(subtract(balance, confirming.amount)) : '—'}</dd>
            <dt>Requested at</dt>
            <dd>{formatDateTime(new Date().toISOString())}</dd>
          </dl>
        )}
      </Dialog>
    </>
  )
}

function mapFieldErrors(errors = {}) {
  const mapped = {}
  if (errors.amount) mapped.amount = errors.amount
  if (errors.toAccountNumber) mapped.to = errors.toAccountNumber
  return mapped
}

/** a - b for two-decimal strings, exactly. */
function subtract(a, b) {
  const cents = (value) => {
    const [integer, fraction = ''] = String(value).split('.')
    return BigInt(integer) * 100n + BigInt((fraction + '00').slice(0, 2))
  }
  const result = cents(a) - cents(b)
  const negative = result < 0n
  const absolute = negative ? -result : result
  return `${negative ? '-' : ''}${absolute / 100n}.${String(absolute % 100n).padStart(2, '0')}`
}
