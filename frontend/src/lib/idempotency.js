// Every money request carries an Idempotency-Key. The backend runs a key at
// most once per user: sending the same key again returns the first result.
//
// The tracker below decides which key a submission uses:
//   - a new submission (new amount, new receiver) gets a new key;
//   - a retry of the same submission after its outcome was unknown (network
//     error, timeout) reuses the key, so the money cannot move twice even if
//     the first request did reach the server.

export function newIdempotencyKey() {
  if (globalThis.crypto?.randomUUID) return globalThis.crypto.randomUUID()
  // Fallback for very old browsers: 128 random bits as hex.
  const bytes = new Uint8Array(16)
  globalThis.crypto.getRandomValues(bytes)
  return Array.from(bytes, (b) => b.toString(16).padStart(2, '0')).join('')
}

export function createSubmissionTracker(generate = newIdempotencyKey) {
  let pending = null // { fingerprint, key } of a submission whose outcome is unknown

  return {
    /** The key to send for this payload. */
    keyFor(payload) {
      const fingerprint = JSON.stringify(payload)
      if (pending && pending.fingerprint === fingerprint) return pending.key
      pending = { fingerprint, key: generate() }
      return pending.key
    },
    /** The server answered (success or refusal): the next submission is new. */
    settled() {
      pending = null
    },
    /** No answer arrived: keep the key for a retry of the same payload. */
    unknownOutcome() {
      // pending stays as it is
    },
  }
}
