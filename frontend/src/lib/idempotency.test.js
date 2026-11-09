import { describe, expect, it } from 'vitest'
import { createSubmissionTracker, newIdempotencyKey } from './idempotency'

describe('createSubmissionTracker', () => {
  const counter = () => {
    let n = 0
    return () => `key-${++n}`
  }

  it('reuses the key when the same request is retried after an unknown outcome', () => {
    const tracker = createSubmissionTracker(counter())
    const first = tracker.keyFor({ type: 'withdraw', amount: '10.00' })
    tracker.unknownOutcome()
    expect(tracker.keyFor({ type: 'withdraw', amount: '10.00' })).toBe(first)
  })

  it('uses a new key once the server has answered', () => {
    const tracker = createSubmissionTracker(counter())
    const first = tracker.keyFor({ type: 'deposit', amount: '10.00' })
    tracker.settled()
    expect(tracker.keyFor({ type: 'deposit', amount: '10.00' })).not.toBe(first)
  })

  it('uses a new key when the request changes', () => {
    const tracker = createSubmissionTracker(counter())
    const first = tracker.keyFor({ type: 'deposit', amount: '10.00' })
    tracker.unknownOutcome()
    expect(tracker.keyFor({ type: 'deposit', amount: '11.00' })).not.toBe(first)
  })
})

it('generates keys the backend accepts', () => {
  expect(newIdempotencyKey()).toMatch(/^[\x21-\x7E]{1,100}$/)
  expect(newIdempotencyKey()).not.toBe(newIdempotencyKey())
})
