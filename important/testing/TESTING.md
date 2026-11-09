# Testing Athena: everything in one place

This folder gathers every testing file you run or read. The automated test
**code** stays where its tools look for it: Maven reads `src/test/java`,
Vitest reads `src/**/*.test.js`, Playwright reads `frontend/e2e`. Moving it
here would stop `mvnw test`, `npm test`, `npx playwright test` and CI from
finding it, so every test file is **linked** below instead.

## What is in this folder

| File | What it is | How to use it |
| --- | --- | --- |
| [TESTING.md](TESTING.md) | This guide: all test levels, every test file, how to run them, the history since Day 1 | Read |
| [TEST_RESULTS.md](TEST_RESULTS.md) | Results of the latest full run, suite by suite and class by class | Read |
| [Athena.postman_collection.json](Athena.postman_collection.json) | 33 API requests with 75 automatic checks, in order | Postman: Import, then Run collection |
| [security-check.mjs](security-check.mjs) | Black-box attacks against a running server: 21 checks, 23 with `--site` | `node important\testing\security-check.mjs <api-url>` |
| [SECURITY_CHECK_REPORT.md](SECURITY_CHECK_REPORT.md) | The last security check report | Read |

## The five levels

| Level | Tool | Count | What it proves | Command (Windows CMD) |
| --- | --- | --- | --- | --- |
| Backend integration | JUnit 6, Spring Boot Test, MockMvc, real PostgreSQL | 159 tests | Money rules, ledger balance, rollback, idempotency, concurrency, audit, every security control in THREATS.md, the carry-over of a database from before the rename | `cd /d E:\athena\backend\athena` then `mvnw test` |
| Frontend unit | Vitest, React Testing Library | 58 tests | Exact money handling, idempotency-key reuse on retry, session expiry and the session check when offline, route protection, forms, account number formats, API address and CSP | `cd /d E:\athena\frontend` then `npm test` |
| API collection | Postman (or `newman`) | 33 requests, 75 checks | Every endpoint by hand, in order | Import `important\testing\Athena.postman_collection.json`, then Run collection |
| End-to-end | Playwright, six setups: desktop, tablet and phone in Chromium (Edge), Firefox, Safari and iPhone in WebKit | 6 journeys in each setup (36), plus 2 screenshot runs | Real browsers on every screen size against the real backend: register, move money, history, statement, roles, expired session, admin freeze | see section 3 |
| Black-box security | `security-check.mjs` (Node, no packages needed) | 21 checks (23 with `--site`) | The attacks of THREATS.md sent over HTTP to a running server | `node important\testing\security-check.mjs http://localhost:8080` |

All of them run in CI on every push (`.github/workflows/ci.yml`).

## Every test file

### Backend: `backend/athena/src/test`

| File | Tests | What it covers |
| --- | ---: | --- |
| [AthenaApplicationTests.java](../../backend/athena/src/test/java/com/athena/AthenaApplicationTests.java) | 1 | The application starts |
| [PostgresIntegrationTest.java](../../backend/athena/src/test/java/com/athena/PostgresIntegrationTest.java) | base | Shared setup: empties the `*_test` database, ledger consistency check |
| [ApiIntegrationTest.java](../../backend/athena/src/test/java/com/athena/ApiIntegrationTest.java) | base | Shared HTTP helpers: sign in, send money requests |
| [controller/AuthControllerIntegrationTest.java](../../backend/athena/src/test/java/com/athena/controller/AuthControllerIntegrationTest.java) | 4 | Login answers, disabled users, 403 for admin pages |
| [controller/TransactionControllerIntegrationTest.java](../../backend/athena/src/test/java/com/athena/controller/TransactionControllerIntegrationTest.java) | 23 | Money endpoints: status codes, JSON, validation, tokens |
| [controller/IdempotencyControllerIntegrationTest.java](../../backend/athena/src/test/java/com/athena/controller/IdempotencyControllerIntegrationTest.java) | 4 | Idempotency-Key over HTTP: replays, mismatches |
| [controller/HistoryAndStatementIntegrationTest.java](../../backend/athena/src/test/java/com/athena/controller/HistoryAndStatementIntegrationTest.java) | 20 | History filters, paging, sorting, privacy; statements |
| [service/TransactionServiceIntegrationTest.java](../../backend/athena/src/test/java/com/athena/service/TransactionServiceIntegrationTest.java) | 24 | Deposits, withdrawals, transfers, double entry, rollback, ledger trigger, concurrency |
| [service/IdempotencyIntegrationTest.java](../../backend/athena/src/test/java/com/athena/service/IdempotencyIntegrationTest.java) | 13 | Duplicate requests, per-user keys, simultaneous duplicates |
| [service/AuditLogIntegrationTest.java](../../backend/athena/src/test/java/com/athena/service/AuditLogIntegrationTest.java) | 5 | Who, what, which accounts, when, outcome |
| [security/SessionSecurityIntegrationTest.java](../../backend/athena/src/test/java/com/athena/security/SessionSecurityIntegrationTest.java) | 13 | Token forgery, expiry, logout, disabled users (THREATS T1–T3) |
| [security/AccountSecurityIntegrationTest.java](../../backend/athena/src/test/java/com/athena/security/AccountSecurityIntegrationTest.java) | 9 | Registration and login rules, throttling, enumeration (T4–T7, T20) |
| [security/AccessControlIntegrationTest.java](../../backend/athena/src/test/java/com/athena/security/AccessControlIntegrationTest.java) | 6 | Role matrix, other people's data, sender tampering (T8–T10) |
| [security/AdminAndOversightIntegrationTest.java](../../backend/athena/src/test/java/com/athena/security/AdminAndOversightIntegrationTest.java) | 9 | Admin actions, frozen accounts, monitoring, integrity report (T11, T12) |
| [security/HttpHardeningIntegrationTest.java](../../backend/athena/src/test/java/com/athena/security/HttpHardeningIntegrationTest.java) | 13 | Body size, injection, error leakage, headers, CORS (T13–T17) |
| [security/CorsIntegrationTest.java](../../backend/athena/src/test/java/com/athena/security/CorsIntegrationTest.java) | 4 | Only the listed website (Vercel) may call the API from a browser (T17) |
| [security/RateLimitIntegrationTest.java](../../backend/athena/src/test/java/com/athena/security/RateLimitIntegrationTest.java) | 2 | Request and registration floods (T18, T19) |
| [security/FixedWindowRateLimiterTest.java](../../backend/athena/src/test/java/com/athena/security/FixedWindowRateLimiterTest.java) | 3 | The counting rules behind every limit |
| [config/SinglePageAppRoutingIntegrationTest.java](../../backend/athena/src/test/java/com/athena/config/SinglePageAppRoutingIntegrationTest.java) | 3 | The React app is served; API 404s and path traversal stay closed |
| [config/DemoDataInitializerIntegrationTest.java](../../backend/athena/src/test/java/com/athena/config/DemoDataInitializerIntegrationTest.java) | 1 | Demo users are created once, with a balanced history |
| [config/RenameCarryOverIntegrationTest.java](../../backend/athena/src/test/java/com/athena/config/RenameCarryOverIntegrationTest.java) | 2 | A database from before the rename to Athena keeps exactly one settlement account; the ledger trigger moves to its new function |
| [application-test.properties](../../backend/athena/src/test/resources/application-test.properties) | config | Test database, random JWT key, test limits |
| **Total** | **159** | |

### Frontend unit tests: next to the code they test, in `frontend/src`

| File | What it covers |
| --- | --- |
| [lib/money.test.js](../../frontend/src/lib/money.test.js) | Exact money formatting and parsing, no floats |
| [lib/idempotency.test.js](../../frontend/src/lib/idempotency.test.js) | The same key on a retry, a new key for a new request |
| [lib/csv.test.js](../../frontend/src/lib/csv.test.js) | Statement CSV, protected against spreadsheet formulas |
| [lib/validation.test.js](../../frontend/src/lib/validation.test.js) | Registration rules, same as the backend |
| [api/client.test.js](../../frontend/src/api/client.test.js) | Exact decimals from the server, error messages, unknown outcomes |
| [api/baseUrl.test.js](../../frontend/src/api/baseUrl.test.js) | The API address for Vercel, https only, the matching CSP |
| [auth/session.test.js](../../frontend/src/auth/session.test.js) | Token expiry and sign-out |
| [auth/RequireAuth.test.jsx](../../frontend/src/auth/RequireAuth.test.jsx) | Protected pages by role |
| [auth/AuthContext.test.jsx](../../frontend/src/auth/AuthContext.test.jsx) | Checking a saved session on reload: tries again when the server cannot be reached, explains a sign-out |
| [pages/MoneyPage.test.jsx](../../frontend/src/pages/MoneyPage.test.jsx) | Deposits, safe retries, transfer confirmation, account number formats (AT, and LL from before the rename) |
| [pages/LoginPage.test.jsx](../../frontend/src/pages/LoginPage.test.jsx) | Sign-in, error messages, session-expired notice |
| [test/setup.js](../../frontend/src/test/setup.js) | Shared setup for all frontend tests |

### End-to-end browser tests: `frontend/e2e`

| File | What it covers |
| --- | --- |
| [customer.spec.js](../../frontend/e2e/customer.spec.js) | Register, move money, history, statement, refused request, no admin access, expired session (4 tests) |
| [staff.spec.js](../../frontend/e2e/staff.spec.js) | Auditor overview and audit log; admin freezes and unfreezes an account (2 tests) |
| [screenshots.spec.js](../../frontend/e2e/screenshots.spec.js) | Captures the 12 screenshots in `important/screenshots`, 9 on desktop and 3 on a phone (runs with `SCREENSHOTS=1`) |
| [helpers.js](../../frontend/e2e/helpers.js) | Shared steps: register, sign in, move money, open the menu that phones and tablets fold away |
| [playwright.config.js](../../frontend/playwright.config.js) | The six setups (screen sizes and browser engines), base address, reports |

## 1. Run the backend tests

They use a separate database whose name must end in `_test`. The base test
class refuses any other database, because it empties the tables before each
test.

```bat
"C:\Program Files\PostgreSQL\18\bin\psql.exe" -U postgres -c "CREATE DATABASE athena_test"
cd /d E:\athena\backend\athena
set DB_PASSWORD=your-postgres-password
mvnw test
```

The test profile generates its own JWT key, so no `JWT_SECRET` is needed.
Another server: `set TEST_DB_URL=jdbc:postgresql://host:5432/athena_test`.

**Proving the tests can fail.** On Day 3 three bugs were planted on purpose,
one at a time: skip the idempotency replay, check the key before locking,
show refused transfers to the receiver. Each was caught by a test, then
removed. PROMPTS.md lists the tests that caught them.

## 2. Run the frontend unit tests

```bat
cd /d E:\athena\frontend
npm install
npm test
npm run lint
```

## 3. Run the end-to-end tests and the security check

Both need the whole app running. The simplest way is the production layout,
with the React app inside the jar:

```bat
cd /d E:\athena\frontend
npm run build
xcopy /E /I /Y dist ..\backend\athena\target\classes\static

cd /d E:\athena\backend\athena
set DB_PASSWORD=your-postgres-password
set JWT_SECRET=your-base64-secret
set DEMO_DATA_ENABLED=true
set DEMO_ADMIN_PASSWORD=choose-a-strong-one-1
set DEMO_AUDITOR_PASSWORD=choose-a-strong-one-2
set DEMO_USER_PASSWORD=choose-a-strong-one-3
mvnw spring-boot:run
```

In a second CMD window:

```bat
cd /d E:\athena\frontend
set E2E_BASE_URL=http://localhost:8080
set E2E_ADMIN_PASSWORD=choose-a-strong-one-1
set E2E_AUDITOR_PASSWORD=choose-a-strong-one-2
set E2E_USER_PASSWORD=choose-a-strong-one-3
npx playwright test

cd /d E:\athena
set AUDITOR_EMAIL=auditor@athena.demo
set AUDITOR_PASSWORD=choose-a-strong-one-2
node important\testing\security-check.mjs http://localhost:8080 --report important\testing\SECURITY_CHECK_REPORT.md
```

`npx playwright test` runs every journey three times in the installed
Microsoft Edge, so there is no browser download: on a desktop screen, on a
tablet (Galaxy Tab S4, 712 x 1138, touch) and on a phone (Pixel 7, 412 x 839,
touch). To add the two other browser engines, download them once and switch
them on:

```bat
npx playwright install firefox webkit
set E2E_ALL_BROWSERS=1
npx playwright test
```

That adds Firefox, Safari (WebKit, the engine of every browser on iPhone and
iPad) and an iPhone 13 screen: six setups in all, the same as CI. Each run
registers about 30 test customers; for repeated runs, start the API with
`set REGISTRATION_LIMIT_PER_HOUR=500` so the per-network limit (50 an hour)
does not refuse them.

Add `set SCREENSHOTS=1` to refresh the screenshots in `important\screenshots`.

When the website is hosted apart from the API (as on Vercel), add
`--site <website-url>` for two more checks: the website's security headers,
CSP and page routes (S2), and that the API accepts it through CORS (C2). To
try this locally, build with `set VITE_API_BASE_URL=http://localhost:8080`,
run `npm run preview` (port 4173, same headers as `vercel.json`), and start
the API with `set CORS_ALLOWED_ORIGINS=http://localhost:4173`.

The security check creates a few throwaway users (names start with "Probe").
Run it only against your own test or demo deployment.

**Try it on your own phone.** Start the backend as above, then run the
website with `npm run dev -- --host` in `frontend`. Vite prints a "Network"
address such as `http://192.168.1.20:5173`; open it on a phone on the same
Wi-Fi (allow Node.js through the Windows firewall if asked). In Chrome or
Edge on the computer, F12 then Ctrl+Shift+M shows the same page at any phone
or tablet size.

## 4. Testing history since Day 1

| Day | Commit | What was tested | Result |
| --- | --- | --- | --- |
| Day 1: sign-in, roles, accounts | `e561d35` | The application starts; sign-in and roles were checked by hand through a temporary test endpoint | 1 automated test |
| Day 2: money and the double-entry ledger | `c5a5599` | 50 integration tests on PostgreSQL; the first Postman collection (19 requests, 37 checks) | 50 / 50. Testing found 5 errors, all fixed (listed in the commit message) |
| Day 3: idempotency, locking, audit, history, statements | `ce82be0` | 94 tests; the Postman collection rebuilt with Idempotency-Keys (30 requests, 70 checks); 3 planted bugs, all caught | 94 / 94, green on the first full run |
| Day 4: frontend, hardening, delivery, the rename to Athena, phones and all browsers | not yet committed | 159 backend, 58 frontend unit, browser tests in six setups (38), Postman 33 requests and 75 checks, security check 21 (23 with the website on its own origin) | All passing; see [TEST_RESULTS.md](TEST_RESULTS.md) |

Every error found along the way, and how it was fixed, is in
[PROMPTS.md](../../PROMPTS.md), section 3.
