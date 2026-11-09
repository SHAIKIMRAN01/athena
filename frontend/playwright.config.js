import { defineConfig, devices } from '@playwright/test'

// End-to-end tests drive real browsers against the real backend.
// Start the backend first (see important/testing/TESTING.md); Playwright starts Vite.
//
// Every run covers three screen sizes in the Chromium engine (Chrome, Edge):
//   desktop  1280 x 720
//   tablet   Galaxy Tab S4, 712 x 1138, touch: the top bar with a Menu button
//   phone    Pixel 7, 412 x 839, touch: the menu, stacked tables, bottom-sheet dialogs
// Locally the installed Microsoft Edge is used (no browser download); CI sets
// PLAYWRIGHT_CHANNEL=chromium after `npx playwright install chromium`.
//
// E2E_ALL_BROWSERS=1 adds the other two engines, after
// `npx playwright install firefox webkit`:
//   firefox  Desktop Firefox
//   safari   Desktop Safari (WebKit, the engine of every iPhone and iPad browser)
//   iphone   iPhone 13, 390 x 664, touch
const channel = process.env.PLAYWRIGHT_CHANNEL ?? 'msedge'
const chromiumChannel = channel === 'chromium' ? {} : { channel }
const allBrowsers = Boolean(process.env.E2E_ALL_BROWSERS)

// The README screenshots are taken once, by the desktop project.
const notScreenshots = { testIgnore: /screenshots\.spec\.js/ }

export default defineConfig({
  testDir: './e2e',
  timeout: 60_000,
  fullyParallel: false,
  workers: 1,
  // One retry on CI only: a test that passes on its second try is reported as
  // "flaky" in the report, so it stays visible instead of hidden.
  retries: process.env.CI ? 1 : 0,
  reporter: [['list'], ['html', { open: 'never', outputFolder: 'playwright-report' }]],
  use: {
    baseURL: process.env.E2E_BASE_URL ?? 'http://localhost:5173',
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
  },
  projects: [
    {
      name: 'desktop',
      use: channel === 'chromium' ? { ...devices['Desktop Chrome'] } : { ...devices['Desktop Edge'], channel },
    },
    { name: 'tablet', use: { ...devices['Galaxy Tab S4'], ...chromiumChannel }, ...notScreenshots },
    { name: 'phone', use: { ...devices['Pixel 7'], ...chromiumChannel }, ...notScreenshots },
    ...(allBrowsers
      ? [
          { name: 'firefox', use: { ...devices['Desktop Firefox'] }, ...notScreenshots },
          { name: 'safari', use: { ...devices['Desktop Safari'] }, ...notScreenshots },
          { name: 'iphone', use: { ...devices['iPhone 13'] }, ...notScreenshots },
        ]
      : []),
  ],
  webServer: process.env.E2E_BASE_URL
    ? undefined
    : {
        command: 'npm run dev',
        url: 'http://localhost:5173',
        reuseExistingServer: true,
        timeout: 60_000,
      },
})
