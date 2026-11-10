# Glossary: the technical terms behind Athena

Each term has a plain explanation and **where it is used in this project**,
so you can open the file and see it.

## Money and accounting

| Term | Meaning | In Athena |
| --- | --- | --- |
| **Ledger** | The book of record: every money movement written as entries. | Table `ledger_entries`; `LedgerService` is the only code that changes a balance. |
| **Double-entry bookkeeping** | Every movement is written twice: a **debit** on one account and a **credit** of the same amount on another, so the books always balance. | Every completed transaction has exactly 2 entries; see ARCHITECTURE.md, section 3. |
| **Debit / credit** | The two sides of an entry. For a customer account a credit adds money and a debit takes it away; for the bank's cash account it is the other way round. | `EntryType`, `AccountType.getNormalBalance()` |
| **Settlement account** | The bank's own cash account: the other side of every deposit and withdrawal. Its balance always equals all customer money. | `AT-SETTLEMENT`, created by `db/seed.sql` |
| **Reconciliation** | Checking that the records agree with each other. | `LedgerReportService.integrity()`, shown on the auditor's overview |
| **Statement** | A list of an account's entries for a period, with opening and closing balances. | `StatementService`, `/statement` page |
| **Opening / closing balance** | The balance just before / at the end of the period. Closing = opening + credits − debits. | `StatementResponse` |
| **Reference** | A human-readable transaction id for receipts. | `TXN0000000042`, from `FinancialTransaction.getReference()` |
| **BigDecimal** | Java's exact decimal number. `double` cannot represent 0.10 exactly, so money is never a `double`. | All amounts in the backend |
| **NUMERIC(19,2)** | PostgreSQL's exact decimal type: 19 digits, 2 after the point. | `accounts.balance`, `amount` columns |

## Transactions and concurrency

| Term | Meaning | In Athena |
| --- | --- | --- |
| **Database transaction** | A group of changes that all happen or none happen. | `@Transactional` on `TransactionProcessor.process` |
| **ACID** | Atomic, Consistent, Isolated, Durable: the guarantees of a database transaction. | PostgreSQL |
| **Rollback** | Undoing every change of a transaction that failed. | `aFailureAfterPostingRollsBackTheWholeTransaction` |
| **Race condition** | Two requests interleave and produce a wrong result, e.g. both see ₹100 and both withdraw ₹100. | Prevented by row locks |
| **Pessimistic locking / `SELECT … FOR UPDATE`** | Lock the rows you will change, so other transactions wait. | `AccountRepository.findByIdForUpdate` (`FOR NO KEY UPDATE`) |
| **Lock ordering / deadlock** | A deadlock is two transactions each waiting for the other's lock. Always locking in the same order (ascending id) prevents it. | `TransactionProcessor.lockInIdOrder` |
| **Isolation level, REPEATABLE READ** | How much a transaction sees of others' changes. REPEATABLE READ gives one snapshot for all its queries. | `LedgerReportService` |
| **Idempotency** | Doing something twice has the same effect as doing it once. | Money endpoints |
| **Idempotency key** | A unique id the client sends with a request. The server runs each key once and replays the result on repeats. | `Idempotency-Key` header; `uq_transactions_user_idempotency_key` |
| **Request fingerprint (hash)** | A SHA-256 digest of the request, to spot a key reused for a different request. | `MoneyRequest.fingerprint()`, column `request_hash` |
| **Replay** | Answering a repeated request with the stored first result. | `Idempotent-Replayed: true` |

## Database

| Term | Meaning | In Athena |
| --- | --- | --- |
| **Constraint** | A rule the database enforces on every write. | `chk_account_balance`, `chk_transaction_amount`, `chk_transaction_accounts` |
| **Unique / partial index** | Forbids duplicates; a partial index only covers rows that match a condition. | One settlement account; one transaction per user and key |
| **Trigger, deferred constraint trigger** | Code the database runs on a write; *deferred* means at commit time, after all rows are written. | `trg_ledger_entries_balanced` checks debits = credits |
| **Foreign key** | A column that must point at an existing row. | `ledger_entries.transaction_id` and others |
| **JSONB** | PostgreSQL's binary JSON column type. | `audit_logs.details` |
| **ER diagram** | A picture of the tables and their relations. | ARCHITECTURE.md, section 4 |
| **JPA / Hibernate / Spring Data** | Map Java classes to tables and generate queries. | `entity` and `repository` packages |
| **Specification (Criteria API)** | Builds a query from optional filters, with bound parameters. | `TransactionHistoryService`, `OversightService` |

## Security

| Term | Meaning | In Athena |
| --- | --- | --- |
| **Authentication** | Proving who you are (email and password, then a token). | `AuthService.login` |
| **Authorization** | Deciding what you may do. | `SecurityConfig`, `@PreAuthorize` |
| **RBAC** | Role-based access control: permissions come from roles. | USER, ADMIN, AUDITOR |
| **Least privilege / separation of duties** | Each role gets only what it needs. Admins manage users but cannot move money; auditors only read. | Role matrix test `eachRoleReachesOnlyItsOwnEndpoints` |
| **401 vs 403** | 401: who are you? (no valid token). 403: you are known but not allowed. | `ProblemJsonAuthenticationEntryPoint`, `AuditingAccessDeniedHandler` |
| **JWT (JSON Web Token)** | A signed token: header.payload.signature. Anyone can read it; only the key holder can create a valid one. | `JwtService` |
| **HS256 / HMAC** | Signing with a shared secret key (here at least 256 bits). | `JWT_SECRET` |
| **Claims: sub, exp, iat, iss, jti** | Subject (user email), expiry, issued-at, issuer, unique token id. | `JwtService.generateToken` |
| **Token revocation** | Making a token invalid before it expires (logout). | Table `revoked_tokens`, `TokenRevocationService` |
| **`alg: none` attack** | A forged token that claims to need no signature. | Refused; test `anUnsignedTokenIsRefused` |
| **BCrypt / salt** | A slow, salted password hash: each password gets a random salt, and guessing is expensive. | `BCryptPasswordEncoder` |
| **Brute force / credential stuffing** | Guessing passwords, or trying leaked passwords from other sites. | `LoginAttemptService` |
| **Rate limiting, 429, Retry-After** | Limiting requests per client per time window; 429 means "too many", and `Retry-After` says when to try again. | `RateLimitFilter`, `FixedWindowRateLimiter` |
| **User enumeration** | Finding out which emails are registered from different error answers. | Same answer for every failed login |
| **IDOR** | Insecure Direct Object Reference: changing an id in the URL to see someone else's data. | Refused with 404; `aCustomerSeesOnlyTheirOwnTransactions` |
| **Mass assignment** | Sending extra fields (e.g. `roles`) hoping the server copies them. | DTOs without those fields; `registrationAlwaysGivesTheUserRoleOnly` |
| **SQL injection** | Smuggling SQL through input. | Only bound parameters; sort whitelist |
| **XSS** | Cross-site scripting: injected JavaScript running in someone else's browser. | React escaping, name validation, CSP |
| **CSP** | Content-Security-Policy: tells the browser which scripts, styles and frames are allowed. | `SecurityConfig.CONTENT_SECURITY_POLICY` |
| **CSRF** | Cross-site request forgery: another site makes your browser send a request with your cookies. | Not possible: no cookies, the token is sent explicitly |
| **CORS** | Browser rules for which other websites may read API responses. | Closed by default (`CORS_ALLOWED_ORIGINS`) |
| **Clickjacking** | Hiding a real page in an invisible frame under a fake button. | `X-Frame-Options: DENY`, `frame-ancestors 'none'` |
| **HSTS** | Tells browsers to always use HTTPS for this site. | Sent over HTTPS |
| **Problem details (RFC 9457)** | A standard JSON shape for API errors. | `GlobalExceptionHandler` |
| **Audit log / non-repudiation** | An append-only record of who did what, so nobody can deny it afterwards. | `audit_logs`, `AuditService` |
| **Threat model, STRIDE** | A structured list of attacks and defences. STRIDE: Spoofing, Tampering, Repudiation, Information disclosure, Denial of service, Elevation of privilege. | THREATS.md |
| **Pentest / black-box test** | Attacking a running system from outside, without reading its code. | `important/testing/security-check.mjs` |
| **Secret management** | Keeping keys and passwords out of code, in environment variables or the host's secret store. | `.env.example`, `render.yaml` (`sync: false`) |

## Frontend

| Term | Meaning | In Athena |
| --- | --- | --- |
| **SPA** | Single-page application: one HTML page, and React swaps the views. | `frontend/src` |
| **React Router, protected route** | Maps URLs to pages; a protected route needs sign-in and a role. | `App.jsx`, `RequireAuth.jsx` |
| **Context (React)** | Shares state such as the signed-in user with all pages. | `AuthContext.jsx` |
| **Axios interceptor** | Code that runs on every request or response: adds the token, handles 401. | `api/client.js` |
| **sessionStorage** | Browser storage that lasts until the tab closes. | `auth/session.js` |
| **Vite** | The build tool and development server for the frontend. | `vite.config.js` |
| **Responsive layout** | One page that rearranges itself for the screen: a sidebar on desktops; a top bar with a Menu button, tables as cards and dialogs that slide up from the bottom on tablets and phones. | `index.css` (`@media` rules), `AppLayout.jsx` |
| **Browser engine** | The part of a browser that draws pages. Chrome, Edge, Opera and Samsung Internet use Chromium (Blink); Firefox uses Gecko; Safari, and every browser on an iPhone or iPad, uses WebKit. Testing the three engines covers them all. | `playwright.config.js` (six setups) |

## Engineering and delivery

| Term | Meaning | In Athena |
| --- | --- | --- |
| **Integration test** | Tests several layers together, here with a real database. | `backend/athena/src/test` |
| **Unit test** | Tests one small piece alone. | `FixedWindowRateLimiterTest`, `frontend/src/**/*.test.js` |
| **End-to-end (E2E) test** | Drives a real browser through the real system. | `frontend/e2e` (Playwright) |
| **Mutation testing** | Planting a bug on purpose to check that a test fails. | PROMPTS.md, section 3.4 |
| **CI (continuous integration)** | Every push builds and tests the project automatically. | `.github/workflows/ci.yml` |
| **CodeQL / Dependabot** | Static security analysis / automatic dependency update requests. | `.github/workflows/codeql.yml`, `.github/dependabot.yml` |
| **Docker image, multi-stage build** | A packaged, runnable app; built in stages so the final image has only the JRE and the jar. | `Dockerfile` |
| **Health check** | A URL the host polls to know the app is alive. | `GET /api/health` |
| **Vercel** | A hosting platform for websites: builds the frontend and serves it from a global network with HTTPS. | `frontend/vercel.json`, important/STEP_3_GITHUB_TO_VERCEL_AND_SECURITY.md |
| **Render** | A hosting platform that runs long-lived servers and managed databases. | `render.yaml` (API and PostgreSQL) |
| **CDN** | Content delivery network: copies of the website's files on servers around the world, close to each user. | Vercel serves the built website this way |
| **Build-time vs run-time variable** | A build-time variable is baked into the files when they are built (change it, rebuild); a run-time variable is read when the server starts. | `VITE_API_BASE_URL` (build time, Vercel) vs `JWT_SECRET` (run time, Render) |
| **CORS allowlist / preflight** | The exact website addresses the API lets browsers call it from; a preflight is the browser's OPTIONS request asking first. | `CORS_ALLOWED_ORIGINS`, `CorsIntegrationTest` |
| **Reverse proxy, X-Forwarded-For** | The host's front server that terminates HTTPS and passes on the client IP. | `FORWARD_HEADERS_STRATEGY` |

## BalanceVault additions

| Term | Meaning | In Athena |
| --- | --- | --- |
| **BalanceVault** | The new name of the LedgerLine challenge, with added requirements. | Athena is the entry; see BALANCEVAULT.md. |
| **Append-only** | Rows can be added but never changed or deleted. | Triggers on `ledger_entries`, `financial_transactions` and `audit_logs` (`athena_refuse_change()`). |
| **Least privilege** | Give each part only the rights it needs, so a mistake or a leak can do little harm. | The app connects as `athena_app`; the owner only creates the tables. |
| **Database role** | PostgreSQL's name for a user or a group of rights. | `athena_writer` (the group with the app's grants) and `athena_app` (the app's login). |
| **GRANT / REVOKE** | SQL that gives or takes away a right on a table. | Section 11 of `schema.sql`. |
| **SQLSTATE 42501** | PostgreSQL's code for "insufficient privilege". | What `athena_app` gets for every forbidden statement. |
| **Redis** | A fast in-memory key-value store. | Holds the rate-limit and sign-in counters, shared by every instance. Render calls its version "Key Value". |
| **Lua script (in Redis)** | A small program Redis runs in one step, so nothing else happens in between. | Increments a counter and sets its expiry together (`RedisCounterStore`). |
| **Failover** | Switching to a stand-in when something fails. | `FailoverCounterStore` counts in memory while Redis is unreachable. |
| **Bootstrap (first administrator)** | Creating the very first administrator, before anyone can sign in to create one. | `scripts/bootstrap-admin`, `AdminBootstrap`; works only while there is no administrator. |
| **Advisory lock** | A lock in PostgreSQL that the app names itself, not tied to a row. | Lets one server at a time create the first administrator. |
| **Password stamp** | The time of the last password change, carried in each token. | A token whose stamp differs from the user's is refused, so a password change ends every session. |
| **Staff** | Administrators and auditors: no customer account, cannot move money. | Added by an administrator (`POST /api/admin/staff`). |
| **Health check** (per dependency) | The health answer names each part it depends on. | `{"status","database","redis"}` |
| **Prisma** | An ORM for Node.js named in the BalanceVault FAQ. | Not used: the backend is Java, so Hibernate (JPA) plays that part. |
