import { api } from './client'

// One function per backend endpoint. See important/API.md for the full reference.

const json = { 'Content-Type': 'application/json' }

function clean(params) {
  return Object.fromEntries(
    Object.entries(params).filter(([, value]) => value !== undefined && value !== null && value !== ''),
  )
}

// ----------------------------------------------------------------- auth

export async function register({ fullName, email, password }) {
  const { data } = await api.post('/auth/register', { fullName, email, password })
  return data
}

export async function login({ email, password }) {
  const { data } = await api.post('/auth/login', { email, password })
  return data
}

export async function logout() {
  await api.post('/auth/logout')
}

export async function currentUser() {
  const { data } = await api.get('/users/me')
  return data
}

// -------------------------------------------------------------- customer

export async function myAccount() {
  const { data } = await api.get('/accounts/me')
  return data
}

/**
 * Money requests send the amount as written ("250.50"), never through a
 * JavaScript number. `amount` must come from parseAmountInput.
 */
async function moneyRequest(path, body, idempotencyKey) {
  const response = await api.post(path, body, {
    headers: { ...json, 'Idempotency-Key': idempotencyKey },
  })
  return { transaction: response.data, replayed: response.headers['idempotent-replayed'] === 'true' }
}

export function deposit(amount, idempotencyKey) {
  return moneyRequest('/transactions/deposit', `{"amount": ${amount}}`, idempotencyKey)
}

export function withdraw(amount, idempotencyKey) {
  return moneyRequest('/transactions/withdraw', `{"amount": ${amount}}`, idempotencyKey)
}

export function transfer(toAccountNumber, amount, idempotencyKey) {
  return moneyRequest(
    '/transactions/transfer',
    `{"toAccountNumber": ${JSON.stringify(toAccountNumber)}, "amount": ${amount}}`,
    idempotencyKey,
  )
}

export async function history(params) {
  const { data } = await api.get('/transactions', { params: clean(params) })
  return data
}

export async function transactionDetail(id) {
  const { data } = await api.get(`/transactions/${encodeURIComponent(id)}`)
  return data
}

export async function statement({ from, to }) {
  const { data } = await api.get('/accounts/me/statement', { params: clean({ from, to }) })
  return data
}

// ----------------------------------------------------- admin and auditor

export async function adminUsers(params) {
  const { data } = await api.get('/admin/users', { params: clean(params) })
  return data
}

export async function setUserEnabled(userId, enabled) {
  const { data } = await api.patch(`/admin/users/${encodeURIComponent(userId)}/status`, { enabled })
  return data
}

export async function setAccountStatus(accountNumber, status, reason) {
  const { data } = await api.patch(`/admin/accounts/${encodeURIComponent(accountNumber)}/status`, {
    status,
    reason: reason || undefined,
  })
  return data
}

export async function monitoredTransactions(params) {
  const { data } = await api.get('/oversight/transactions', { params: clean(params) })
  return data
}

export async function auditLogs(params) {
  const { data } = await api.get('/oversight/audit-logs', { params: clean(params) })
  return data
}

export async function integrityReport() {
  const { data } = await api.get('/oversight/integrity')
  return data
}

export async function systemSummary() {
  const { data } = await api.get('/oversight/summary')
  return data
}
