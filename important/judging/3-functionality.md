# 3. Functionality (15 marks)

> "The ten requirements in the problem statement, working."

> **Action for the author:** the problem statement's own list of ten
> requirements was not available when this page was written. The table below
> follows the project plan's acceptance criteria. If your list differs, map
> each of its ten items to a row here (or add rows) before judging.

| # | Requirement (from the project plan) | Working where | Proven by |
| --- | --- | --- | --- |
| 1 | User registration and login, with BCrypt passwords | Sign-in and sign-up pages; `POST /api/auth/register`, `/login` | `AuthControllerIntegrationTest`, `AccountSecurityIntegrationTest`, E2E `customer.spec.js` |
| 2 | JWT authentication and roles (USER, ADMIN, AUDITOR) | Every API call; menus by role | `SessionSecurityIntegrationTest`, `AccessControlIntegrationTest` |
| 3 | Account management: an account per user, own account only | Dashboard; `GET /api/accounts/me`; admin freeze/unfreeze and enable/disable | `AdminAndOversightIntegrationTest`, E2E `staff.spec.js` |
| 4 | Deposits and withdrawals with balance checks | Move money page; `POST /deposit`, `/withdraw` | `TransactionServiceIntegrationTest` |
| 5 | Transfers between accounts | Move money → Transfer, with confirmation | `transferDebitsTheSenderAndCreditsTheReceiver` |
| 6 | Double-entry ledger | Transaction details show both entries | `theDatabaseRejectsAnUnbalancedLedger`, integrity report |
| 7 | Atomic transactions and rollback | Every money request is one database transaction | `aFailureAfterPostingRollsBackTheWholeTransaction` |
| 8 | Idempotency and concurrency protection | `Idempotency-Key` on every money request; row locks | `IdempotencyIntegrationTest`, concurrency tests, security checks M3–M5 |
| 9 | Audit logs | Auditor's audit log page; `GET /api/oversight/audit-logs` | `AuditLogIntegrationTest` |
| 10 | Transaction history and statements | History (search, filters, details), Statement (CSV, print) | `HistoryAndStatementIntegrationTest`, E2E `customer.spec.js` |
| + | React frontend and API integration, with loading, validation and error states and expired-JWT handling | Every page | Frontend unit tests (60), browser tests in six setups (38) |

## Show it

Follow the 7-minute script in [important/DEMO.md](../DEMO.md): it touches all ten
rows in order. If time is short, run the Postman collection (75 checks), then
open the app.
