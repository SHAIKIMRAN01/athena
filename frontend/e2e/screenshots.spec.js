import { devices, expect } from '@playwright/test'
import { goToSection, signIn, staff, test } from './helpers'

// Captures the README screenshots into important/screenshots. Run with
// SCREENSHOTS=1 against a backend started with the demo users.
test.skip(!process.env.SCREENSHOTS || !staff.alice.password, 'set SCREENSHOTS=1 and the demo passwords')

const shot = (name) => ({ path: `../important/screenshots/${name}.png`, fullPage: true })

test.use({ viewport: { width: 1360, height: 860 } })

test('screenshots', async ({ newPage }) => {
  const visitor = await newPage()
  await visitor.goto('/login')
  await visitor.screenshot(shot('01-sign-in'))

  const page = await newPage()
  await signIn(page, staff.alice.email, staff.alice.password)
  await expect(page.getByTestId('balance')).toBeVisible()
  await page.screenshot(shot('02-dashboard'))

  await page.goto('/move-money?type=transfer')
  await page.getByLabel('Receiver account number').fill('ATDEMO0000BOB1')
  await page.getByLabel('Amount (₹)').fill('125.00')
  await page.screenshot(shot('03-move-money'))

  await page.goto('/history')
  await expect(page.locator('tbody tr').first()).toBeVisible()
  await page.screenshot(shot('04-history'))
  await page.locator('tbody tr').first().click()
  await expect(page.getByText('Ledger entries')).toBeVisible()
  await page.screenshot(shot('05-transaction-details'))

  await page.goto('/statement')
  await expect(page.getByTestId('closing')).toBeVisible()
  await page.screenshot(shot('06-statement'))

  const auditor = await newPage()
  await signIn(auditor, staff.auditor.email, staff.auditor.password)
  await expect(auditor.getByText('The books balance')).toBeVisible()
  await auditor.screenshot(shot('07-audit-overview'))
  await auditor.goto('/oversight/audit-log')
  await expect(auditor.locator('tbody tr').first()).toBeVisible()
  await auditor.screenshot(shot('08-audit-log'))

  const admin = await newPage()
  await signIn(admin, staff.admin.email, staff.admin.password)
  await expect(admin.getByRole('heading', { name: 'Administration' })).toBeVisible()
  await goToSection(admin, 'Users')
  await expect(admin.locator('tbody tr').first()).toBeVisible()
  await admin.screenshot(shot('09-admin-users'))
  await admin.getByRole('button', { name: 'Add staff member' }).click()
  await expect(admin.getByRole('dialog', { name: 'Add staff member' })).toBeVisible()
  // The window only: the dialog's backdrop covers the window, not the whole page.
  await admin.screenshot({ ...shot('13-add-staff-member'), fullPage: false })
  await admin.getByRole('button', { name: 'Cancel' }).click()

  await page.goto('/profile')
  await expect(page.getByRole('heading', { name: 'Change password' })).toBeVisible()
  await page.screenshot(shot('14-profile'))
})

test('phone screenshots', async ({ browser }) => {
  const phone = await browser.newContext({ ...devices['Pixel 7'] })
  const page = await phone.newPage()
  await signIn(page, staff.alice.email, staff.alice.password)
  await expect(page.getByTestId('balance')).toBeVisible()
  await page.screenshot(shot('10-phone-dashboard'))

  await page.getByRole('button', { name: 'Menu' }).click()
  await expect(page.getByRole('navigation', { name: 'Main' })).toBeVisible()
  await page.screenshot({ path: '../important/screenshots/11-phone-menu.png' })

  await page.goto('/history')
  await expect(page.locator('tbody tr').first()).toBeVisible()
  await page.screenshot(shot('12-phone-history'))
  await phone.close()
})
