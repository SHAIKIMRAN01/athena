# Latest full test run

Run on 2026-11-09, after the production-readiness work (query batching, caching,
compression, timeouts, stricter lint). Re-create it with the commands in [TESTING.md](TESTING.md).

| Suite | Result |
| --- | --- |
| Backend (JUnit, real PostgreSQL) | 165 / 165 passed |
| Frontend unit (Vitest) | 60 / 60 passed |
| Browser tests (Playwright, six setups) | 38 / 38 passed: desktop, tablet and phone in Chromium (Edge), Firefox, Safari and iPhone in WebKit |
| API collection (Postman) | 75 / 75 checks passed |
| Security check (black-box attacks) | 21 / 21 passed ([report](SECURITY_CHECK_REPORT.md)) |
| Website on its own origin, as on Vercel (run 2026-11-09) | 36 / 36 browser tests in the six setups and 23 / 23 security checks passed |

## Backend, by test class

| Test class | Tests | Failures | Errors | Seconds |
| --- | ---: | ---: | ---: | ---: |
| AthenaApplicationTests | 1 | 0 | 0 | 10.7 |
| config.DemoDataInitializerIntegrationTest | 1 | 0 | 0 | 4.6 |
| config.ProductionSettingsIntegrationTest | 3 | 0 | 0 | 0.4 |
| config.RenameCarryOverIntegrationTest | 2 | 0 | 0 | 0.3 |
| config.SinglePageAppRoutingIntegrationTest | 5 | 0 | 0 | 3.9 |
| controller.AuthControllerIntegrationTest | 4 | 0 | 0 | 1.4 |
| controller.HistoryAndStatementIntegrationTest | 20 | 0 | 0 | 10.1 |
| controller.IdempotencyControllerIntegrationTest | 4 | 0 | 0 | 1.8 |
| controller.ListQueryCountIntegrationTest | 1 | 0 | 0 | 5.9 |
| controller.TransactionControllerIntegrationTest | 23 | 0 | 0 | 9.2 |
| security.AccessControlIntegrationTest | 6 | 0 | 0 | 4.8 |
| security.AccountSecurityIntegrationTest | 9 | 0 | 0 | 4.7 |
| security.AdminAndOversightIntegrationTest | 9 | 0 | 0 | 6.7 |
| security.CorsIntegrationTest | 4 | 0 | 0 | 2.0 |
| security.FixedWindowRateLimiterTest | 3 | 0 | 0 | 0.0 |
| security.HttpHardeningIntegrationTest | 13 | 0 | 0 | 5.7 |
| security.RateLimitIntegrationTest | 2 | 0 | 0 | 1.8 |
| security.SessionSecurityIntegrationTest | 13 | 0 | 0 | 4.2 |
| service.AuditLogIntegrationTest | 5 | 0 | 0 | 1.7 |
| service.IdempotencyIntegrationTest | 13 | 0 | 0 | 6.0 |
| service.TransactionServiceIntegrationTest | 24 | 0 | 0 | 20.5 |
| **Total** | **165** | **0** | **0** | **106.6** |

## Frontend unit tests, by file

| File | Passed | Failed |
| --- | ---: | ---: |
| src/api/baseUrl.test.js | 6 | 0 |
| src/api/client.test.js | 5 | 0 |
| src/auth/AuthContext.test.jsx | 3 | 0 |
| src/auth/RequireAuth.test.jsx | 4 | 0 |
| src/auth/session.test.js | 3 | 0 |
| src/components/ErrorBoundary.test.jsx | 2 | 0 |
| src/lib/csv.test.js | 2 | 0 |
| src/lib/idempotency.test.js | 4 | 0 |
| src/lib/money.test.js | 18 | 0 |
| src/lib/validation.test.js | 2 | 0 |
| src/pages/LoginPage.test.jsx | 3 | 0 |
| src/pages/MoneyPage.test.jsx | 8 | 0 |
| **Total** | **60** | **0** |

## Browser tests, by setup

| File | Journey | desktop | tablet | phone | firefox | safari | iphone |
| --- | --- | :---: | :---: | :---: | :---: | :---: | :---: |
| customer.spec.js | a customer registers, moves money, checks history and statement, and signs out | passed | passed | passed | passed | passed | passed |
| customer.spec.js | a refused request shows why and appears in history as failed | passed | passed | passed | passed | passed | passed |
| customer.spec.js | a customer cannot open administrator pages | passed | passed | passed | passed | passed | passed |
| customer.spec.js | an expired session sends the user to sign in with an explanation | passed | passed | passed | passed | passed | passed |
| screenshots.spec.js | screenshots | passed | – | – | – | – | – |
| screenshots.spec.js | phone screenshots | passed | – | – | – | – | – |
| staff.spec.js | an auditor sees the books balance and the audit log, and cannot manage users | passed | passed | passed | passed | passed | passed |
| staff.spec.js | an administrator freezes an account, the customer is blocked, then it is unfrozen | passed | passed | passed | passed | passed | passed |

– means not run in that setup: the README screenshots are taken once, on desktop.

## Security checks

| Id | Check | Result |
| --- | --- | --- |
| S1 | Security headers are set on pages and API responses | passed |
| A1 | Protected endpoints refuse requests without a token | passed |
| A2 | Edited, unsigned and malformed tokens are refused | passed |
| A3 | A signed-out token stops working | passed |
| A4 | Repeated wrong passwords are throttled | passed |
| A5 | Failed logins do not reveal which emails exist | passed |
| Z1 | Registration cannot grant a role | passed |
| Z2 | A customer cannot reach admin or auditor endpoints | passed |
| Z3 | A customer cannot read another customer's transaction | passed |
| Z4 | The sender is always the signed-in user | passed |
| M1 | Invalid amounts are refused | passed |
| M2 | Overdrafts, self-transfers and transfers to the bank account are refused | passed |
| M3 | A repeated request with the same Idempotency-Key moves money once | passed |
| M4 | Simultaneous withdrawals cannot overdraw an account | passed |
| M5 | Simultaneous duplicates with one key create one transaction | passed |
| I1 | Hostile query parameters never cause a server error | passed |
| I2 | Markup in names is refused | passed |
| I3 | Oversized request bodies are refused | passed |
| I4 | Error responses do not reveal internals | passed |
| C1 | Other websites cannot call the API from a browser (CORS) | passed |
| L1 | After all of the above, the ledger still balances | passed |
