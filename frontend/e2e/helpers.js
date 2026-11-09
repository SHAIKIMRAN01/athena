import { expect } from '@playwright/test'

export const PASSWORD = 'Password123!'

/** A unique email per test run, so the suite can run again and again. */
export function uniqueEmail(name) {
  return `${name}.${Date.now()}.${Math.floor(Math.random() * 1e6)}@example.test`
}

/** Registers a customer through the API and returns { email, accountNumber }. */
export async function registerByApi(request, name) {
  const email = uniqueEmail(name.toLowerCase())
  const response = await request.post('/api/auth/register', {
    data: { fullName: `${name} Tester`, email, password: PASSWORD },
  })
  expect(response.status()).toBe(201)
  const body = await response.json()
  return { email, accountNumber: body.accountNumber }
}

export async function signIn(page, email, password = PASSWORD) {
  await page.goto('/login')
  await page.getByLabel('Email').fill(email)
  await page.getByLabel('Password').fill(password)
  await page.getByRole('button', { name: 'Sign in' }).click()
}

export async function moveMoney(page, type, amount, toAccount) {
  await page.goto(`/move-money?type=${type}`)
  if (toAccount) await page.getByLabel('Receiver account number').fill(toAccount)
  await page.getByLabel('Amount (₹)').fill(amount)
  const buttons = { deposit: 'Deposit', withdraw: 'Withdraw', transfer: 'Send' }
  await page.getByRole('button', { name: buttons[type], exact: true }).click()
}

/**
 * Makes the main menu visible. On tablets and phones it is folded behind the
 * Menu button in the top bar; on wide screens it is always shown.
 */
export async function openMenu(page) {
  const toggle = page.getByRole('button', { name: 'Menu' })
  if ((await toggle.isVisible()) && (await toggle.getAttribute('aria-expanded')) !== 'true') {
    await toggle.click()
  }
  await expect(page.getByRole('navigation', { name: 'Main' })).toBeVisible()
}

/** Opens a page from the main menu, the way a person would on any screen size. */
export async function goToSection(page, name) {
  await openMenu(page)
  await page.getByRole('navigation', { name: 'Main' }).getByRole('link', { name, exact: true }).click()
}

export async function signOut(page) {
  await openMenu(page)
  await page.getByRole('button', { name: 'Sign out' }).click()
}

export const staff = {
  admin: { email: 'admin@athena.demo', password: process.env.E2E_ADMIN_PASSWORD },
  auditor: { email: 'auditor@athena.demo', password: process.env.E2E_AUDITOR_PASSWORD },
  alice: { email: 'alice@athena.demo', password: process.env.E2E_USER_PASSWORD },
  bob: { email: 'bob@athena.demo', password: process.env.E2E_USER_PASSWORD },
}
