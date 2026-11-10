import { expect, test } from '@playwright/test'
import { PASSWORD, goToSection, moveMoney, openMenu, registerByApi, signIn, signOut, uniqueEmail } from './helpers'

test('a customer registers, moves money, checks history and statement, and signs out', async ({ page, request }) => {
  const bob = await registerByApi(request, 'Bob')
  const email = uniqueEmail('alice')

  // Register through the form.
  await page.goto('/register')
  await page.getByLabel('Full name').fill('Alice Tester')
  await page.getByLabel('Email').fill(email)
  await page.getByLabel('Password', { exact: true }).fill(PASSWORD)
  await page.getByLabel('Repeat password').fill(PASSWORD)
  await page.getByRole('button', { name: 'Create account' }).click()
  await expect(page.getByText('Your account is ready')).toBeVisible()

  await page.getByLabel('Password').fill(PASSWORD)
  await page.getByRole('button', { name: 'Sign in' }).click()
  await expect(page.getByRole('heading', { name: 'Hello, Alice' })).toBeVisible()
  await expect(page.getByTestId('balance')).toHaveText('₹0.00')

  // Deposit, withdraw, transfer (with confirmation).
  await moveMoney(page, 'deposit', '1000')
  await expect(page.getByText('Deposit of ₹1,000.00 completed.')).toBeVisible()

  await moveMoney(page, 'withdraw', '250.50')
  await expect(page.getByText('Withdraw of ₹250.50 completed.')).toBeVisible()

  await moveMoney(page, 'transfer', '300', bob.accountNumber)
  await expect(page.getByRole('dialog', { name: 'Confirm transfer' })).toBeVisible()
  await page.getByRole('button', { name: 'Send ₹300.00' }).click()
  await expect(page.getByText('Transfer of ₹300.00 completed.')).toBeVisible()
  await expect(page.getByTestId('money-balance')).toHaveText('₹449.50')

  // History: 3 transactions, newest first; filters; details with both ledger sides.
  await page.goto('/history')
  await expect(page.locator('tbody tr')).toHaveCount(3)
  await page.getByLabel('Type').selectOption('TRANSFER')
  await page.getByRole('button', { name: 'Apply' }).click()
  await expect(page.locator('tbody tr')).toHaveCount(1)
  await page.locator('tbody tr').first().click()
  const details = page.getByRole('dialog', { name: 'Transaction details' })
  await expect(details.getByText('Ledger entries')).toBeVisible()
  await expect(details.locator('tbody tr')).toHaveCount(2)
  await expect(details.getByText(bob.accountNumber).first()).toBeVisible()
  await details.getByRole('button', { name: 'Close' }).click()

  // Statement for this month, computed from the ledger.
  await page.goto('/statement')
  await expect(page.getByTestId('opening')).toHaveText('₹0.00')
  await expect(page.getByTestId('credits')).toHaveText('+₹1,000.00')
  await expect(page.getByTestId('debits')).toHaveText('−₹550.50')
  await expect(page.getByTestId('closing')).toHaveText('₹449.50')

  // Sign out: protected pages send you back to sign in.
  await signOut(page)
  await expect(page).toHaveURL(/\/login$/)
  await page.goto('/dashboard')
  await expect(page).toHaveURL(/\/login$/)
})

test('a refused request shows why and appears in history as failed', async ({ page, request }) => {
  const carol = await registerByApi(request, 'Carol')
  await signIn(page, carol.email)
  await expect(page.getByTestId('balance')).toHaveText('₹0.00')

  // The page refuses this before sending; the server refuses the next one.
  await moveMoney(page, 'withdraw', '10')
  await expect(page.getByText(/That is more than your balance/)).toBeVisible()

  await moveMoney(page, 'deposit', '5')
  await expect(page.getByText('Deposit of ₹5.00 completed.')).toBeVisible()
  const response = await request.post('/api/auth/login', { data: { email: carol.email, password: PASSWORD } })
  const { accessToken } = await response.json()
  const refused = await request.post('/api/transactions/withdraw', {
    headers: { Authorization: `Bearer ${accessToken}`, 'Idempotency-Key': crypto.randomUUID() },
    data: { amount: 50 },
  })
  expect(refused.status()).toBe(422)

  await page.goto('/history')
  await page.getByLabel('Status').selectOption('FAILED')
  await page.getByRole('button', { name: 'Apply' }).click()
  await expect(page.locator('tbody tr')).toHaveCount(1)
  await expect(page.locator('tbody tr').first()).toContainText('Failed')
})

test('a customer cannot open administrator pages', async ({ page, request }) => {
  const dave = await registerByApi(request, 'Dave')
  await signIn(page, dave.email)
  await expect(page.getByTestId('balance')).toBeVisible()

  await openMenu(page)
  await expect(page.getByRole('navigation', { name: 'Main' }).getByRole('link', { name: 'Users' })).toHaveCount(0)
  await page.goto('/admin/users')
  await expect(page.getByRole('heading', { name: 'No access' })).toBeVisible()
})

test('an expired session sends the user to sign in with an explanation', async ({ page }) => {
  const encode = (object) => Buffer.from(JSON.stringify(object)).toString('base64url')
  const expired = `${encode({ alg: 'HS256' })}.${encode({ sub: 'x@example.test', exp: 1_000_000_000 })}.sig`

  await page.goto('/login')
  await page.evaluate((token) => {
    sessionStorage.setItem('athena.session', JSON.stringify({ token, expiresAt: Date.now() + 60_000 }))
  }, expired)
  await page.goto('/dashboard')

  // The server answers 401 to the token; the app signs out and explains.
  await expect(page).toHaveURL(/\/login$/)
  await expect(page.getByText(/Please sign in again/)).toBeVisible()
})

test('a customer changes their password: the session ends and only the new password works', async ({
  page,
  request,
}) => {
  const carol = await registerByApi(request, 'Carol')
  await signIn(page, carol.email)
  await expect(page.getByRole('heading', { name: 'Hello, Carol' })).toBeVisible()

  await goToSection(page, 'Profile')
  await expect(page.getByText(carol.accountNumber)).toBeVisible()
  await page.getByLabel('Current password').fill(PASSWORD)
  await page.getByLabel('New password', { exact: true }).fill('Brand-new-pass-9')
  await page.getByLabel('Repeat new password').fill('Brand-new-pass-9')
  await page.getByRole('button', { name: 'Change password' }).click()

  await expect(page.getByText('Your password was changed. Sign in with the new one.')).toBeVisible()
  await expect(page).toHaveURL(/\/login$/)

  await signIn(page, carol.email, PASSWORD)
  await expect(page.getByRole('alert')).toHaveText('Email or password is incorrect')
  await signIn(page, carol.email, 'Brand-new-pass-9')
  await expect(page.getByRole('heading', { name: 'Hello, Carol' })).toBeVisible()
})
