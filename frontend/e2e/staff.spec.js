import { expect } from '@playwright/test'
import { goToSection, openMenu, signIn, staff, test, uniqueEmail } from './helpers'

// Needs the demo users: start the backend with DEMO_DATA_ENABLED=true and pass
// the same passwords as E2E_ADMIN_PASSWORD / E2E_AUDITOR_PASSWORD / E2E_USER_PASSWORD.
test.skip(!staff.admin.password || !staff.auditor.password || !staff.alice.password, 'demo user passwords not set')

test('an auditor sees the books balance and the audit log, and cannot manage users', async ({ page }) => {
  await signIn(page, staff.auditor.email, staff.auditor.password)
  await expect(page.getByRole('heading', { name: 'Audit overview' })).toBeVisible()
  await expect(page.getByText('The books balance')).toBeVisible()
  await expect(page.getByTestId('integrity-checks').locator('li.check-pass')).toHaveCount(7)

  await goToSection(page, 'Audit log')
  await expect(page.getByRole('heading', { name: 'Audit log' })).toBeVisible()
  await expect(page.locator('tbody tr').first()).toBeVisible()

  await goToSection(page, 'Transactions')
  await expect(page.locator('tbody tr').first()).toBeVisible()

  await openMenu(page)
  await expect(page.getByRole('navigation', { name: 'Main' }).getByRole('link', { name: 'Users' })).toHaveCount(0)
  await page.goto('/admin/users')
  await expect(page.getByRole('heading', { name: 'No access' })).toBeVisible()
})

test('an administrator freezes an account, the customer is blocked, then it is unfrozen', async ({ newPage }) => {
  const adminPage = await newPage()
  await signIn(adminPage, staff.admin.email, staff.admin.password)
  await expect(adminPage.getByRole('heading', { name: 'Administration' })).toBeVisible()

  await goToSection(adminPage, 'Users')
  await adminPage.getByLabel('Search').fill('bob@athena.demo')
  await adminPage.getByRole('button', { name: 'Search' }).click()
  await expect(adminPage.locator('tbody tr')).toHaveCount(1)

  await adminPage.getByRole('button', { name: 'Freeze' }).click()
  await adminPage.getByLabel('Reason (kept in the audit log)').fill('End-to-end test')
  await adminPage.getByRole('button', { name: 'Confirm' }).click()
  await expect(adminPage.getByText(/was frozen/)).toBeVisible()

  const customerPage = await newPage()
  await signIn(customerPage, staff.bob.email, staff.bob.password)
  await expect(customerPage.getByText('Your account is frozen')).toBeVisible()

  await adminPage.getByRole('button', { name: 'Unfreeze' }).click()
  await adminPage.getByRole('button', { name: 'Confirm' }).click()
  await expect(adminPage.getByText(/was unfrozen/)).toBeVisible()

  await customerPage.reload()
  await expect(customerPage.getByText('Your account is frozen')).toHaveCount(0)

  await goToSection(adminPage, 'Audit log')
  await adminPage.getByLabel('Action').selectOption('ACCOUNT_FROZEN')
  await adminPage.getByRole('button', { name: 'Apply' }).click()
  await expect(adminPage.locator('tbody tr').first()).toContainText('End-to-end test')
})

test('an administrator adds an auditor, who signs in with the initial password and changes it', async ({ newPage }) => {
  // The longest journey: two people, about thirty steps. WebKit runs it
  // close to the default 60 s on slower machines, so it gets three times that.
  test.slow()
  const adminPage = await newPage()
  await signIn(adminPage, staff.admin.email, staff.admin.password)
  await expect(adminPage.getByRole('heading', { name: 'Administration' })).toBeVisible()

  await goToSection(adminPage, 'Users')
  await adminPage.getByRole('button', { name: 'Add staff member' }).click()
  const dialog = adminPage.getByRole('dialog', { name: 'Add staff member' })
  const email = uniqueEmail('auditor')
  await dialog.getByLabel('Full name').fill('Audrey Auditor')
  await dialog.getByLabel('Email').fill(email)
  await dialog.getByLabel('Role').selectOption('AUDITOR')
  await dialog.getByRole('button', { name: 'Generate password' }).click()
  const initialPassword = await dialog.getByLabel('Initial password').inputValue()
  await dialog.getByRole('button', { name: 'Add', exact: true }).click()
  await expect(adminPage.getByText(/Audrey Auditor was added as an auditor/)).toBeVisible()

  const auditorPage = await newPage()
  await signIn(auditorPage, email, initialPassword)
  await expect(auditorPage.getByRole('heading', { name: 'Audit overview' })).toBeVisible()
  await goToSection(auditorPage, 'Profile')
  await auditorPage.getByLabel('Current password').fill(initialPassword)
  await auditorPage.getByLabel('New password', { exact: true }).fill('Auditor-own-pass-2026')
  await auditorPage.getByLabel('Repeat new password').fill('Auditor-own-pass-2026')
  await auditorPage.getByRole('button', { name: 'Change password' }).click()
  await expect(auditorPage.getByText('Your password was changed. Sign in with the new one.')).toBeVisible()
  await signIn(auditorPage, email, 'Auditor-own-pass-2026')
  await expect(auditorPage.getByRole('heading', { name: 'Audit overview' })).toBeVisible()

  await goToSection(adminPage, 'Audit log')
  await adminPage.getByLabel('Action').selectOption('STAFF_CREATED')
  await adminPage.getByRole('button', { name: 'Apply' }).click()
  await expect(adminPage.locator('tbody tr').first()).toContainText(email)
})
