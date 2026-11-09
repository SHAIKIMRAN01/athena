#!/usr/bin/env node
// Athena black-box security check.
//
// Sends the attacks listed in THREATS.md to a running Athena server over
// HTTP, the way an outside tester (or a judge) would, and reports PASS/FAIL
// for each. Run it only against your own deployment.
//
//   node important/testing/security-check.mjs                     (http://localhost:8080)
//   node important/testing/security-check.mjs https://your-app.example --report important/testing/SECURITY_CHECK_REPORT.md
//
// When the website is hosted separately from the API (for example the website
// on Vercel and the API on Render), name the API first and the website with
// --site; two more checks then cover the website's headers and CORS:
//
//   node important/testing/security-check.mjs https://your-api.onrender.com --site https://your-site.vercel.app
//
// Optional: AUDITOR_EMAIL and AUDITOR_PASSWORD let the last check read the
// ledger integrity report after all attacks.
//
// It creates a few throwaway users (names start with "Probe"), so run it on a
// test or demo deployment rather than one holding real customers.

import { randomUUID } from 'node:crypto'
import { writeFileSync } from 'node:fs'

const args = process.argv.slice(2)
const OPTIONS = ['--report', '--site']
const optionValueIndexes = new Set(OPTIONS.map((name) => args.indexOf(name)).filter((i) => i >= 0).map((i) => i + 1))
const option = (name) => (args.includes(name) ? args[args.indexOf(name) + 1] : null)
const reportPath = option('--report')
const site = option('--site')?.replace(/\/$/, '') ?? null
const base = (args.find((a, i) => !a.startsWith('--') && !optionValueIndexes.has(i)) ?? 'http://localhost:8080').replace(/\/$/, '')
const PASSWORD = 'Probe-Password-123'
const results = []

// ------------------------------------------------------------------ helpers

async function call(method, path, { token, body, raw, headers = {} } = {}) {
  const response = await fetch(base + path, {
    method,
    headers: {
      ...(body !== undefined || raw !== undefined ? { 'Content-Type': 'application/json' } : {}),
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
      ...headers,
    },
    body: raw ?? (body !== undefined ? JSON.stringify(body) : undefined),
  })
  const text = await response.text()
  let json = null
  try { json = JSON.parse(text) } catch { /* not JSON */ }
  return { status: response.status, headers: response.headers, text, json }
}

async function check(id, title, fn) {
  try {
    const detail = await fn()
    results.push({ id, title, ok: true, detail: detail ?? '' })
    console.log(`  PASS  ${id}  ${title}`)
  } catch (error) {
    results.push({ id, title, ok: false, detail: error.message })
    console.log(`  FAIL  ${id}  ${title}\n          ${error.message}`)
  }
}

function expect(condition, message) {
  if (!condition) throw new Error(message)
}

function statusIs(response, expected, what) {
  const allowed = [].concat(expected)
  expect(allowed.includes(response.status), `${what}: expected ${allowed.join(' or ')}, got ${response.status} ${response.text.slice(0, 160)}`)
}

async function newCustomer(name) {
  const email = `probe.${name.toLowerCase()}.${Date.now()}.${Math.floor(Math.random() * 1e6)}@example.test`
  const registered = await call('POST', '/api/auth/register', { body: { fullName: `Probe ${name}`, email, password: PASSWORD } })
  statusIs(registered, 201, `register ${name}`)
  const login = await call('POST', '/api/auth/login', { body: { email, password: PASSWORD } })
  statusIs(login, 200, `login ${name}`)
  return { email, token: login.json.accessToken, account: registered.json.accountNumber }
}

const money = (path, token, body, key = randomUUID()) =>
  call('POST', `/api/transactions/${path}`, { token, body, headers: { 'Idempotency-Key': key } })

const balance = async (token) => (await call('GET', '/api/accounts/me', { token })).json.balance

const b64url = (object) => Buffer.from(JSON.stringify(object)).toString('base64url')

// ------------------------------------------------------------------- checks

console.log(`Athena security check against ${base}\n`)

const health = await call('GET', '/api/health').catch((error) => ({ status: 0, text: error.message }))
if (health.status !== 200) {
  console.error(`The server is not reachable at ${base} (${health.status} ${health.text})`)
  process.exit(2)
}

const alice = await newCustomer('Alice')
const bob = await newCustomer('Bob')

await check('S1', 'Security headers are set on pages and API responses', async () => {
  for (const path of ['/', '/api/health']) {
    const response = await fetch(base + path)
    const csp = response.headers.get('content-security-policy') ?? ''
    expect(csp.includes("default-src 'self'") && csp.includes("frame-ancestors 'none'"), `${path}: weak or missing CSP`)
    expect(response.headers.get('x-content-type-options') === 'nosniff', `${path}: X-Content-Type-Options missing`)
    expect(response.headers.get('x-frame-options') === 'DENY', `${path}: X-Frame-Options missing`)
  }
})

await check('A1', 'Protected endpoints refuse requests without a token', async () => {
  for (const path of ['/api/accounts/me', '/api/transactions', '/api/users/me', '/api/admin/users', '/api/oversight/audit-logs']) {
    statusIs(await call('GET', path), 401, path)
  }
  statusIs(await money('deposit', null, { amount: 10 }), 401, 'deposit without token')
})

await check('A2', 'Edited, unsigned and malformed tokens are refused', async () => {
  const [header, , signature] = alice.token.split('.')
  const forgedPayload = b64url({ jti: randomUUID(), iss: 'athena', sub: bob.email, exp: 4102444800 })
  statusIs(await call('GET', '/api/accounts/me', { token: `${header}.${forgedPayload}.${signature}` }), 401, 'edited payload')
  statusIs(await call('GET', '/api/accounts/me', { token: `${b64url({ alg: 'none' })}.${forgedPayload}.` }), 401, 'alg none')
  statusIs(await call('GET', '/api/accounts/me', { token: 'not.a.token' }), 401, 'garbage token')
})

await check('A3', 'A signed-out token stops working', async () => {
  const carol = await newCustomer('Carol')
  statusIs(await call('POST', '/api/auth/logout', { token: carol.token }), 204, 'logout')
  const after = await call('GET', '/api/accounts/me', { token: carol.token })
  statusIs(after, 401, 'request with the signed-out token')
  expect(after.json?.code === 'TOKEN_REVOKED', `expected TOKEN_REVOKED, got ${after.json?.code}`)
})

await check('A4', 'Repeated wrong passwords are throttled', async () => {
  const dave = await newCustomer('Dave')
  let throttled = null
  for (let attempt = 1; attempt <= 8 && !throttled; attempt++) {
    const response = await call('POST', '/api/auth/login', { body: { email: dave.email, password: `Wrong-${attempt}-x1` } })
    if (response.status === 429) throttled = attempt
    else statusIs(response, 401, `wrong password #${attempt}`)
  }
  expect(throttled !== null, 'eight wrong passwords in a row were never throttled')
  return `throttled at attempt ${throttled}`
})

await check('A5', 'Failed logins do not reveal which emails exist', async () => {
  const wrongPassword = await call('POST', '/api/auth/login', { body: { email: bob.email, password: 'Wrong-password-1' } })
  const unknownEmail = await call('POST', '/api/auth/login', { body: { email: `nobody.${Date.now()}@example.test`, password: 'Wrong-password-1' } })
  expect(wrongPassword.status === unknownEmail.status && wrongPassword.json?.detail === unknownEmail.json?.detail,
    'different answers for a wrong password and an unknown email')
})

await check('Z1', 'Registration cannot grant a role', async () => {
  const email = `probe.mallory.${Date.now()}@example.test`
  statusIs(await call('POST', '/api/auth/register', {
    body: { fullName: 'Probe Mallory', email, password: PASSWORD, roles: ['ROLE_ADMIN'], role: 'ADMIN' },
  }), 201, 'register with roles')
  const login = await call('POST', '/api/auth/login', { body: { email, password: PASSWORD } })
  expect(JSON.stringify(login.json.roles) === '["USER"]', `roles were ${JSON.stringify(login.json.roles)}`)
  statusIs(await call('GET', '/api/admin/users', { token: login.json.accessToken }), 403, 'admin endpoint')
})

await check('Z2', 'A customer cannot reach admin or auditor endpoints', async () => {
  for (const path of ['/api/admin/users', '/api/oversight/transactions', '/api/oversight/audit-logs', '/api/oversight/integrity']) {
    statusIs(await call('GET', path, { token: alice.token }), 403, path)
  }
  statusIs(await call('PATCH', `/api/admin/accounts/${bob.account}/status`, { token: alice.token, body: { status: 'FROZEN' } }), 403, 'freeze as customer')
})

await check('Z3', "A customer cannot read another customer's transaction", async () => {
  const deposit = await money('deposit', bob.token, { amount: 25.0 })
  statusIs(deposit, 201, 'Bob deposits')
  const peek = await call('GET', `/api/transactions/${deposit.json.transactionId}`, { token: alice.token })
  statusIs(peek, 404, "Alice reads Bob's transaction")
})

await check('Z4', 'The sender is always the signed-in user', async () => {
  const before = await balance(bob.token)
  const response = await money('transfer', alice.token, { fromAccountNumber: bob.account, toAccountNumber: alice.account, amount: 5 })
  statusIs(response, [400, 422], 'transfer naming another sender')
  expect((await balance(bob.token)) === before, "Bob's balance changed")
})

await check('M1', 'Invalid amounts are refused', async () => {
  for (const amount of [0, -5, 10.001, 1e30, '"abc"', 'null']) {
    const raw = `{"amount": ${amount}}`
    statusIs(await call('POST', '/api/transactions/deposit', { token: alice.token, raw, headers: { 'Idempotency-Key': randomUUID() } }), 400, `amount ${amount}`)
  }
})

await check('M2', 'Overdrafts, self-transfers and transfers to the bank account are refused', async () => {
  statusIs(await money('deposit', alice.token, { amount: 100.0 }), 201, 'deposit 100')
  statusIs(await money('withdraw', alice.token, { amount: 100.01 }), 422, 'withdraw 100.01')
  statusIs(await money('transfer', alice.token, { toAccountNumber: alice.account, amount: 1 }), 400, 'transfer to self')
  statusIs(await money('transfer', alice.token, { toAccountNumber: 'AT-SETTLEMENT', amount: 1 }), 404, 'transfer to settlement')
  expect((await balance(alice.token)) === 100, 'balance should still be 100.00')
})

await check('M3', 'A repeated request with the same Idempotency-Key moves money once', async () => {
  const key = randomUUID()
  const first = await money('deposit', alice.token, { amount: 10 }, key)
  const second = await money('deposit', alice.token, { amount: 10 }, key)
  statusIs(first, 201, 'first')
  statusIs(second, 201, 'second')
  expect(first.json.transactionId === second.json.transactionId, 'two different transactions were created')
  expect(second.headers.get('idempotent-replayed') === 'true', 'second response not marked as replayed')
  statusIs(await money('deposit', alice.token, { amount: 99 }, key), 422, 'same key, different amount')
})

await check('M4', 'Simultaneous withdrawals cannot overdraw an account', async () => {
  const erin = await newCustomer('Erin')
  statusIs(await money('deposit', erin.token, { amount: 100 }), 201, 'deposit 100')
  const attempts = await Promise.all(Array.from({ length: 10 }, () => money('withdraw', erin.token, { amount: 100 })))
  const succeeded = attempts.filter((r) => r.status === 201).length
  expect(succeeded === 1, `${succeeded} of 10 simultaneous withdrawals of the whole balance succeeded`)
  expect(attempts.every((r) => [201, 422].includes(r.status)), `unexpected statuses ${attempts.map((r) => r.status)}`)
  expect((await balance(erin.token)) === 0, 'balance is not 0.00')
  return '1 of 10 succeeded, balance 0.00'
})

await check('M5', 'Simultaneous duplicates with one key create one transaction', async () => {
  const key = randomUUID()
  const attempts = await Promise.all(Array.from({ length: 6 }, () => money('deposit', bob.token, { amount: 7 }, key)))
  const ids = new Set(attempts.filter((r) => r.status === 201).map((r) => r.json.transactionId))
  expect(ids.size === 1, `${ids.size} different transactions for one key`)
})

await check('I1', 'Hostile query parameters never cause a server error', async () => {
  const probes = [
    "/api/transactions?sort=amount;DROP%20TABLE%20accounts",
    "/api/transactions?type=DEPOSIT'%20OR%20'1'='1",
    "/api/transactions?search='%20OR%201=1%20--",
    '/api/transactions?size=100000',
    '/api/transactions/1%20OR%201=1',
  ]
  for (const path of probes) {
    const response = await call('GET', path, { token: alice.token })
    expect(response.status < 500, `${path} answered ${response.status}`)
  }
})

await check('I2', 'Markup in names is refused', async () => {
  statusIs(await call('POST', '/api/auth/register', {
    body: { fullName: '<img src=x onerror=alert(1)>', email: `probe.x.${Date.now()}@example.test`, password: PASSWORD },
  }), 400, 'name with markup')
})

await check('I3', 'Oversized request bodies are refused', async () => {
  const raw = JSON.stringify({ amount: 1, padding: 'x'.repeat(50_000) })
  statusIs(await call('POST', '/api/transactions/deposit', { token: alice.token, raw, headers: { 'Idempotency-Key': randomUUID() } }), 413, 'large body')
})

await check('I4', 'Error responses do not reveal internals', async () => {
  const errors = [
    await call('POST', '/api/transactions/deposit', { token: alice.token, raw: '{"amount": ', headers: { 'Idempotency-Key': randomUUID() } }),
    await call('GET', '/api/transactions?type=NOPE', { token: alice.token }),
    await call('GET', '/api/does-not-exist', { token: alice.token }),
  ]
  for (const response of errors) {
    expect(!/Exception|org\.springframework|java\.|SQL|\bat com\./.test(response.text), `internal details in: ${response.text.slice(0, 200)}`)
  }
})

await check('C1', 'Other websites cannot call the API from a browser (CORS)', async () => {
  const response = await fetch(`${base}/api/accounts/me`, { headers: { Origin: 'https://attacker.example', Authorization: `Bearer ${alice.token}` } })
  expect(!response.headers.get('access-control-allow-origin'), 'Access-Control-Allow-Origin was sent to a foreign origin')
})

if (site) {
  await check('S2', 'The separately hosted website sends security headers and a strict CSP', async () => {
    const response = await fetch(site + '/')
    expect(response.status === 200, `website home answered ${response.status}`)
    const html = await response.text()
    expect(response.headers.get('x-frame-options') === 'DENY', 'website: X-Frame-Options missing (vercel.json headers not applied?)')
    expect(response.headers.get('x-content-type-options') === 'nosniff', 'website: X-Content-Type-Options missing')
    expect((response.headers.get('content-security-policy') ?? '').includes("frame-ancestors 'none'"), 'website: CSP header without frame-ancestors')
    const meta = html.match(/<meta http-equiv="Content-Security-Policy" content="([^"]*)"/)
    expect(meta, 'website: index.html has no Content-Security-Policy meta tag')
    const policy = meta[1].replaceAll('&#39;', "'").replaceAll('&apos;', "'")
    expect(policy.includes("script-src 'self'") && !policy.includes('unsafe-inline'), `website: weak script policy: ${policy}`)
    const apiOrigin = new URL(base).origin
    expect(policy.includes(apiOrigin) || new URL(site).origin === apiOrigin, `website: connect-src does not allow the API at ${apiOrigin} (rebuild with VITE_API_BASE_URL)`)
    const deepLink = await fetch(site + '/dashboard')
    expect(deepLink.status === 200 && (await deepLink.text()).includes('id="root"'), 'website: /dashboard does not serve the app (SPA rewrite missing)')
  })

  if (new URL(site).origin !== new URL(base).origin) {
    await check('C2', 'The API accepts browser calls from the website', async () => {
      const origin = new URL(site).origin
      const preflight = await fetch(`${base}/api/transactions/transfer`, {
        method: 'OPTIONS',
        headers: { Origin: origin, 'Access-Control-Request-Method': 'POST', 'Access-Control-Request-Headers': 'authorization,content-type,idempotency-key' },
      })
      expect(preflight.status === 200 && preflight.headers.get('access-control-allow-origin') === origin,
        `preflight from ${origin} answered ${preflight.status}: set CORS_ALLOWED_ORIGINS=${origin} on the API`)
      const call = await fetch(`${base}/api/accounts/me`, { headers: { Origin: origin, Authorization: `Bearer ${alice.token}` } })
      expect(call.status === 200 && call.headers.get('access-control-allow-origin') === origin, 'an authenticated call from the website cannot be read')
    })
  }
}

if (process.env.AUDITOR_EMAIL && process.env.AUDITOR_PASSWORD) {
  await check('L1', 'After all of the above, the ledger still balances', async () => {
    const login = await call('POST', '/api/auth/login', { body: { email: process.env.AUDITOR_EMAIL, password: process.env.AUDITOR_PASSWORD } })
    statusIs(login, 200, 'auditor login')
    const report = await call('GET', '/api/oversight/integrity', { token: login.json.accessToken })
    statusIs(report, 200, 'integrity report')
    expect(report.json.consistent === true, `integrity report says inconsistent: ${report.text.slice(0, 300)}`)
    return `${report.json.transactionsChecked} transactions, settlement ${report.json.settlementBalance} = customers ${report.json.customerBalanceTotal}`
  })
}

// ------------------------------------------------------------------- report

const failed = results.filter((r) => !r.ok)
console.log(`\n${results.length - failed.length} passed, ${failed.length} failed`)

if (reportPath) {
  const lines = [
    '# Athena security check report',
    '',
    `Target: \`${base}\`${site ? ` (website: \`${site}\`)` : ''}  `,
    `Run at: ${new Date().toISOString()}  `,
    `Result: **${results.length - failed.length} passed, ${failed.length} failed**`,
    '',
    'Generated by `node important/testing/security-check.mjs`. Check ids match THREATS.md.',
    '',
    '| Id | Check | Result | Detail |',
    '| --- | --- | --- | --- |',
    ...results.map((r) => `| ${r.id} | ${r.title} | ${r.ok ? 'PASS' : '**FAIL**'} | ${r.detail.replaceAll('|', '\\|')} |`),
    '',
  ]
  writeFileSync(reportPath, lines.join('\n'))
  console.log(`Report written to ${reportPath}`)
}

process.exit(failed.length ? 1 : 0)
