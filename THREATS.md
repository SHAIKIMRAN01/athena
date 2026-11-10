# Athena threat model

This file lists what an attacker could try against Athena, what stops it,
and **which test proves that it is stopped**. Every threat has an id (T1, T2…).
The same ids appear in the Javadoc of the backend security tests, and each
row also names the black-box check (S1, A1, Z1…) in
[`important/testing/security-check.mjs`](important/testing/security-check.mjs), which sends the
attack over HTTP to a running server.

> How to verify everything below yourself:
> `mvnw test` (backend, 235 tests) and
> `node important/testing/security-check.mjs <your-url>` (21 black-box checks).
> The last local run is in [important/testing/SECURITY_CHECK_REPORT.md](important/testing/SECURITY_CHECK_REPORT.md).
>
> BalanceVault additions (T35 to T41): the database itself refuses to change
> history, the app connects as a restricted database role, rate limits are
> shared through Redis, the first administrator comes from a bootstrap that
> works only once, and a password change ends every session.

## 1. What we protect

| Asset | Why it matters |
| --- | --- |
| Account balances and the ledger | Money must never be created, lost or moved twice. |
| Who may move which money | Only the owner may move money out of an account. |
| Credentials and access tokens | A stolen password or token is a stolen account. |
| The audit log | Without it, nobody can prove what happened. |
| Personal data (names, emails, history) | Customers must not see each other's data. |
| Availability | A bank that is down cannot pay anyone. |

## 2. Who might attack

| Actor | Can do |
| --- | --- |
| Anonymous internet user | Call any URL, send any body and header, run scripts, many requests at once. |
| Registered customer | Everything above, with a valid token for their own account. |
| Malicious or careless insider | An ADMIN or AUDITOR account. |
| Someone with database access | Edit rows directly, bypassing the application: with the app's own credentials (leaked, or through a bug), or with the tables' owner's. |
| Attacker on another website | Make a victim's browser send requests (CSRF, CORS) or embed our pages (clickjacking). |

## 3. Trust boundaries

```
Browser ──HTTPS──▶ hosting proxy ──▶ Spring Boot (filters ▶ security rules ▶ controllers ▶ services) ──JDBC as athena_app──▶ PostgreSQL
  untrusted          trusted for client IP only        every request re-checked here        │                       constraints, ledger trigger,
                                                                                            │                       append-only triggers, grants
                                                                                            └──private network──▶ Redis (rate-limit counters only)
```

Nothing that arrives from the browser is trusted: not the account to debit,
not the roles, not the amount format, not the request size. The database is
the last line of defence and has its own rules (constraints, a balancing
trigger, append-only triggers on history and a restricted role for the app)
that hold even if the application has a bug. Redis holds nothing but
counters, under hashed keys, so losing or reading it exposes no money and no
personal data.

## 4. Threats, defences and the tests that prove them

STRIDE category: **S**poofing, **T**ampering, **R**epudiation,
**I**nformation disclosure, **D**enial of service, **E**levation of privilege.

### Authentication and sessions

| Id | Threat (STRIDE) | Attack | Defence | Proven by | Residual risk |
| --- | --- | --- | --- | --- | --- |
| T1 | Forged or edited token (S, E) | Change the email in the JWT payload, sign with another key, send `alg: none`, or a token from another issuer. | HS256 signature checked with a 256+-bit key; issuer `athena` and a token id required; jjwt refuses unsigned tokens. The app will not start with a missing or short key. [`JwtService`](backend/athena/src/main/java/com/athena/security/JwtService.java) | `SessionSecurityIntegrationTest`: `aTokenWhosePayloadWasEditedIsRefused`, `aTokenSignedWithAnotherKeyIsRefused`, `anUnsignedTokenIsRefused`, `aTokenFromAnotherIssuerOrWithoutAnIdIsRefused`, `theServerRefusesToStartWithAWeakSigningKey`. Check **A2**. | Anyone holding JWT_SECRET can mint tokens: it lives only in the host's secret store. |
| T2 | Stolen or signed-out token reused (S) | Copy a token from a shared computer and use it after the owner signs out, or after they change their password. | Tokens expire after 15 minutes. Sign-out stores the token id in `revoked_tokens` and the filter refuses it (`TOKEN_REVOKED`). Each token also carries the password stamp it was issued with; after a password change the stamp no longer matches, so every older token is refused, however close in time it was issued. | `logoutEndsThatSessionOnly`, `anExpiredTokenIsRefusedAsExpired`, `PasswordChangeIntegrationTest.theNewPasswordWorksAndEverySessionFromBeforeEnds`. Check **A3**. | A stolen token works until its owner signs out, changes the password, or it expires (at most 15 minutes). |
| T3 | Disabled user keeps access (E) | An admin disables a user who still holds a valid token. | The user is loaded from the database on every request; a disabled user gets 401 `ACCOUNT_DISABLED` at once. | `disablingAUserStopsTokensTheyAlreadyHold`, `disablingAUserBlocksTheirSignInAndIsAudited`, `disabledUsersCannotLogIn`. | – |
| T4 | Password guessing (S) | Try thousands of passwords against one account, or one password against many accounts. | BCrypt hashes. Failed logins are throttled per email+IP (5 in 15 minutes), per IP (30) and per email from any IP (50), answered with 429 and `Retry-After`. [`LoginAttemptService`](backend/athena/src/main/java/com/athena/security/LoginAttemptService.java) | `repeatedFailuresFromOneNetworkAreThrottled`, `aSuccessfulLoginClearsEarlierFailures`, `FixedWindowRateLimiterTest`. Check **A4**. | With `REDIS_URL` (production) the counters are shared by every instance and survive restarts (T37); without it they are per instance (section 6). |
| T5 | Finding out which emails are registered (I) | Compare login answers for a wrong password, an unknown email and a disabled user. | All three answer the same 401 `INVALID_CREDENTIALS` body; BCrypt runs for unknown emails too, so timing is similar. | `failedLoginsLookTheSameWhateverTheReason`. Check **A5**. | Registration answers 409 for a taken email (needed for usability); it is rate-limited (T19). A disabled user's failed login skips BCrypt, so it answers measurably faster. |
| T6 | Registering as an administrator (E) | Add `"roles": ["ROLE_ADMIN"]` to the registration body. | The request DTO has no role field; registration always assigns USER only. | `registrationAlwaysGivesTheUserRoleOnly`. Check **Z1**. | – |
| T20 | Weak or oversized passwords (S, D) | Register with `12345678`; send a 10 000-character or 80-byte emoji password to make BCrypt fail. | 8-72 characters with a letter and a digit; more than 72 bytes is refused at registration and treated as a normal failure at login (no 500). | `weakPasswordsAreRefused`, `aVeryLongPasswordAtLoginIsAnOrdinaryFailure`. | No check against lists of breached passwords. |

### Authorization and data access

| Id | Threat (STRIDE) | Attack | Defence | Proven by | Residual risk |
| --- | --- | --- | --- | --- | --- |
| T8 | Reading someone else's data (I) | Request `/api/transactions/{id}` with another customer's id; look for their refused attempts. | There is no endpoint that takes an account id. A transaction is visible only to its parties; refused attempts only to their author. Not-yours and missing both answer 404. | `aCustomerSeesOnlyTheirOwnTransactions`, `someoneElsesRefusedAttemptStaysPrivate`, `receiversSeeIncomingTransfersButNotOtherPeoplesFailedAttempts`. Check **Z3**. | Account numbers of transfer counterparties are visible to both parties (as on a bank statement). |
| T9 | Using a role you do not have (E) | A customer calls `/api/admin/users`; an auditor freezes an account; an admin moves money. | URL rules in `SecurityConfig` plus `@PreAuthorize` on every controller (two independent checks). Every 403 is written to the audit log. | `eachRoleReachesOnlyItsOwnEndpoints`, `anAuditorCannotChangeAnything`, `everyRefusedRoleCheckIsAudited`, `rolesWrittenIntoATokenAreIgnored`, `usersWithoutTheUserRoleCannotMoveMoney`. Check **Z2**. | – |
| T10 | Moving someone else's money (T, E) | Send `"fromAccountNumber": "<victim>"` in a transfer. | The debited account always comes from the token's user; the request has no sender field. | `theSenderIsAlwaysTheSignedInUser`. Check **Z4**. | – |
| T11 | Insider misuse (E, R) | An admin disables themselves or the system user, freezes the bank's settlement account, or hides their actions. | Admins cannot change their own status or the system user/account; every admin action is audited with before/after values and a reason; admins and auditors cannot move money. | `anAdminCannotChangeTheirOwnStatusOrTheSystemUser`, `aFrozenAccountCanNeitherSendNorReceive`, `onlyActiveAndFrozenAreAllowedStatuses`. | A rogue admin can freeze customers; the audit log shows who did it. |

### Money and the ledger

| Id | Threat (STRIDE) | Attack | Defence | Proven by | Residual risk |
| --- | --- | --- | --- | --- | --- |
| T21 | Double spending through a race (T) | Send 10 withdrawals of the whole balance at the same moment. | Each request locks the account rows (`SELECT … FOR NO KEY UPDATE`) in ascending id order, then checks the balance. A `CHECK (balance >= 0)` constraint backs it up. | `concurrentWithdrawalsCannotOverdrawAnAccount`, `concurrentTransfersFromOneAccountCannotOverdrawIt`, `balancesStayConsistentUnderConcurrentMixedActivity`, `opposingConcurrentTransfersFinishWithoutDeadlock`. Check **M4**. | – |
| T22 | Duplicate or replayed request (T) | Click Pay twice, retry after a timeout, or replay a captured request. | Every money request needs an `Idempotency-Key`; keys are unique per user in the database; a repeat returns the first result (`Idempotent-Replayed: true`). The key is checked after taking the locks, so simultaneous duplicates queue. | `repeatingARequestWithTheSameKeyReturnsTheFirstResult`, `simultaneousDuplicatesMoveMoneyOnlyOnce`, `simultaneousDuplicateTransfersMoveMoneyOnlyOnce`, `keysBelongToOneUser`. Checks **M3**, **M5**. | A replay with a *new* key is a new payment, as at any bank: the key is the client's statement of intent. |
| T23 | Key reused for a different payment (T) | Reuse a key that paid 10 to pay 10 000. | The request's SHA-256 fingerprint is stored with the key; a different payload gets 422 `IDEMPOTENCY_KEY_REUSED`. | `reusingAKeyForADifferentRequestIsRejected`, `reusingAKeyForADifferentRequestReturns422`. Check **M3**. | – |
| T24 | Invalid amounts (T) | 0, -5, 10.001, 1e30, `"abc"`, a float rounding error. | Bean Validation (positive, at most 17+2 digits), `BigDecimal` everywhere, `NUMERIC(19,2)` in the database, a `CHECK (amount > 0)` constraint. The frontend sends the typed digits, never a float. | `zeroNegativeAndOverPreciseAmountsAreRejected`, `invalidAmountsReturn400`, `amountsAreExactDecimalsNotFloatingPoint`. Check **M1**. | – |
| T25 | Overdraft, self-transfer, transfer to the bank's own account (T) | Withdraw more than the balance; transfer to yourself or to `AT-SETTLEMENT`. | Business rules checked on the locked rows; the settlement account cannot receive transfers. | `withdrawalBeyondTheBalanceIsRejectedAndChangesNothing`, `transferToYourOwnAccountIsRejected`, `transferToTheSettlementAccountIsRejected`. Check **M2**. | – |
| T26 | Half-finished transfer (T) | A crash after debiting the sender but before crediting the receiver. | One database transaction per money request: everything commits or nothing does. | `aFailureAfterPostingRollsBackTheWholeTransaction`. | – |
| T27 | Unbalanced ledger from a bug (T) | A coding error writes a debit without its credit. | A deferred PostgreSQL constraint trigger refuses any commit where a transaction's debits differ from its credits. | `theDatabaseRejectsAnUnbalancedLedger`, `theLedgerStaysBalancedAcrossMixedActivity`. | – |
| T12 | Silent tampering in the database (T, R) | Someone with database access edits a balance, rewrites a ledger entry or deletes an audit record. | History cannot be edited: triggers refuse every UPDATE and DELETE on `ledger_entries`, `financial_transactions` and `audit_logs`, whoever asks (T35), and the app's own role has no right to either (T36). A balance changed directly is caught by the integrity report (`/api/oversight/integrity`), which reconciles every balance with its ledger entries and the settlement account with all customer money, in one snapshot. | `AppendOnlyRecordsIntegrationTest`, `DatabaseRolesIntegrationTest.theAppsRoleIsRefused`, `theIntegrityReportCatchesABalanceChangedOutsideTheLedger`, `theIntegrityReportPassesAfterNormalActivity`. Check **L1**. | Balances themselves must stay changeable by the app, so a direct balance edit is detected, not prevented. |
| T11b | Frozen account still moving money (E) | Withdraw from, or transfer to, a frozen account. | Account status checked on the locked row for both sides. | `aFrozenAccountCanNeitherSendNorReceive`, `frozenAccountsCannotSendOrReceiveMoney`. | – |

### Input, output and the browser

| Id | Threat (STRIDE) | Attack | Defence | Proven by | Residual risk |
| --- | --- | --- | --- | --- | --- |
| T14 | Injection through parameters (T, I) | `sort=amount;DROP TABLE accounts`, `type=DEPOSIT' OR '1'='1`, `search=' OR 1=1 --`. | Only bound parameters reach SQL; `sort` is checked against a whitelist; enum and date parameters are type-checked; LIKE wildcards are escaped. | `hostileQueryParametersAreRefusedOrMatchNothing`, `invalidHistoryQueriesReturn400`, `searchMatchesNameEmailOrAccountAndTreatsWildcardsAsText`. Check **I1**. | – |
| T7 | Stored script in a name (T) | Register as `<img src=x onerror=alert(1)>` so an admin page runs it. | Names may contain only letters, spaces, `.'-`; React escapes all output; the CSP forbids inline scripts. | `namesWithMarkupAreRefused`. Check **I2**. | – |
| T16 | Script injection and clickjacking (T, I) | Run injected JavaScript to steal the token; frame our page under a fake button. | Content-Security-Policy `script-src 'self'`, `frame-ancestors 'none'`, `X-Frame-Options: DENY`, `nosniff`, `Referrer-Policy: no-referrer`. The token is kept in `sessionStorage` (gone when the tab closes), never in a cookie. | `responsesCarrySecurityHeaders`, `appRoutesServeTheApp`, `frontend/src/api/baseUrl.test.js` (CSP names exactly the API). Checks **S1**, **S2** (website on Vercel: `vercel.json` headers plus a CSP meta tag). | A successful XSS could still read `sessionStorage`; the CSP is what prevents it. |
| T17 | Cross-site requests (CSRF, CORS) (T) | A malicious website makes the victim's browser call the API. | No cookies, so the browser never adds credentials by itself. CORS is closed unless `CORS_ALLOWED_ORIGINS` lists an origin. | `otherWebsitesCannotCallTheApiFromABrowser`, `CorsIntegrationTest` (only the listed website, e.g. on Vercel, and its preview addresses). Checks **C1**, **C2**. | Listing `https://*.vercel.app` would admit every Vercel site: list exact addresses. |
| T15 | Error messages leaking internals (I) | Send malformed JSON, wrong types, unknown paths, and read the errors. | Every error is problem-detail JSON with a `code`; no stack traces, SQL or class names; an unexpected error shows only a random `errorId` that matches the server log. | `errorResponsesDoNotRevealInternals`, `theHealthCheckSaysOnlyUpOrDownPerDependency`. Check **I4**. | – |
| T13 | Oversized requests (D) | Send a 50 MB JSON body, or a chunked body without a length. | Bodies over 16 KB are refused with 413 before parsing; chunked bodies are cut off at the limit. | `anOversizedBodyIsRefusedBeforeItIsRead`. Check **I3**. | – |
| T28 | Path traversal to server files (I) | Request `/../application.properties`. | Static files are served by Spring's resolver, which refuses paths outside the static folder. | `pathTraversalDoesNotEscapeTheStaticFolder`. | – |
| T29 | Spreadsheet formula injection in exported statements (T) | A value starting with `=` runs as a formula when the CSV is opened. | `csvCell` quotes every cell and prefixes `= + - @` with an apostrophe. | `frontend/src/lib/csv.test.js`. | – |

### Availability, accountability and supply chain

| Id | Threat (STRIDE) | Attack | Defence | Proven by | Residual risk |
| --- | --- | --- | --- | --- | --- |
| T18 | Request flood (D) | One machine sends thousands of requests per second. | 600 API requests per minute per client IP (429 with `Retry-After`), checked before authentication; page size at most 100; statements at most 366 days. | `oneNetworkFloodingTheApiIsSlowedDownAndOthersAreNot`, `invalidHistoryQueriesReturn400`, `invalidStatementQueriesReturn400`. | A distributed attack from many IPs needs the hosting platform's DDoS protection. |
| T19 | Mass registration (D) | Create thousands of accounts. | 50 registrations per hour per IP. | `oneNetworkCannotRegisterUsersWithoutLimit`. | – |
| T30 | Denying an action afterwards (R) | "I never made that transfer", "I never froze that account". | Every money request (completed, refused, replayed, errored), sign-in, sign-out, registration, 403 and admin change is in an append-only audit log with actor, time, IP and details. | `AuditLogIntegrationTest` (5 tests), `failedLoginsAreAuditedWithoutThePassword`, `everyRefusedRoleCheckIsAudited`, `AppendOnlyRecordsIntegrationTest`. | The audit log is append-only in the database too (T35). Only the tables' owner can switch the trigger off, which is a visible schema change. |
| T31 | Secrets in the repository (I) | Read the GitHub repository for passwords or keys. | All secrets come from environment variables; `.env` is git-ignored; demo passwords come only from `DEMO_*_PASSWORD`; CI generates throwaway secrets per run and masks them. | Pre-commit secret scans (see PROMPTS.md). | – |
| T32 | Vulnerable dependency (T, I) | A known CVE in a library. | CI runs `npm audit --audit-level=high`; Dependabot proposes updates weekly; CodeQL scans the code. | `.github/workflows/ci.yml`, `.github/workflows/codeql.yml`. | New CVEs appear between scans. |
| T33 | Faked client IP to dodge rate limits (S) | Send `X-Forwarded-For: 1.2.3.4`. | Forwarded headers are ignored unless `FORWARD_HEADERS_STRATEGY=native`, and then Tomcat trusts them only from private-network proxies. | Configuration (`application.properties`); `oneNetworkFloodingTheApiIsSlowedDownAndOthersAreNot` uses the real remote address. | – |
| T34 | Tokens or passwords in logs (I) | Read the server logs. | Tokens and passwords are never logged (the Day 1 debug prints were removed); failed-login audit records store the email and reason, not the password. | `failedLoginsAreAuditedWithoutThePassword`. | – |

### BalanceVault: database, Redis and staff onboarding

| Id | Threat (STRIDE) | Attack | Defence | Proven by | Residual risk |
| --- | --- | --- | --- | --- | --- |
| T35 | Rewriting history (T, R) | `UPDATE ledger_entries SET amount = …`, `DELETE FROM audit_logs`, mark a refused transaction COMPLETED: through a bug, a SQL prompt or stolen credentials. | Row triggers `BEFORE UPDATE OR DELETE` on `ledger_entries`, `financial_transactions` and `audit_logs` raise `… is append-only` for everyone, the owner included. New rows are written as before. [`schema.sql`](backend/athena/src/main/resources/db/schema.sql), section 10 | `AppendOnlyRecordsIntegrationTest` (UPDATE and DELETE refused on all three tables, as the owner; inserts still work). CI checks it against the running app too. | The owner can disable a trigger (`ALTER TABLE … DISABLE TRIGGER`): a deliberate, visible schema change, impossible for the app's role (T36). |
| T36 | Leaked or abused app database credentials (T, E) | Use the app's database login to delete records, empty a table, drop or alter the schema, switch triggers off or create a new role. | The app connects as `athena_app`, a member of `athena_writer`, which may SELECT everything, INSERT, UPDATE only `app_users` and `accounts`, and DELETE only expired `revoked_tokens`. It owns nothing and is not a superuser. The owner (`DB_OWNER_USERNAME`) only runs `schema.sql`. [`DatabaseRolesProvisioner`](backend/athena/src/main/java/com/athena/config/DatabaseRolesProvisioner.java) | `DatabaseRolesIntegrationTest`: the whole app runs as a fresh restricted role, every operation succeeds within the grants, and 14 statements (UPDATE/DELETE of history, TRUNCATE, DROP, ALTER, CREATE TABLE, CREATE ROLE…) fail with `42501`. CI repeats `DELETE FROM audit_logs` as `athena_app` against the running app. | The app's process also holds the owner's password, to upgrade the schema at startup. For a stricter split, run the schema step once per deploy with the owner and start the app with `SPRING_SQL_INIT_MODE=never` and no `DB_OWNER_*` (DEPLOYMENT.md). |
| T37 | Password guessing spread over several instances (S, D) | Send guesses to every instance behind the load balancer, so each one sees only a few. | With `REDIS_URL`, all rate limits and sign-in counters live in Redis, shared by every instance and kept across restarts. Each count is one atomic Lua script (count and expiry together). Keys are SHA-256 hashes, so Redis holds no email or IP address. [`RedisCounterStore`](backend/athena/src/main/java/com/athena/security/RedisCounterStore.java) | `RedisRateLimitIntegrationTest.failedSignInsAreCountedInRedisAndThrottled`, `RedisCounterStoreTest.twoServersShareOneCount`, `redisHoldsNoEmailOrIpAddressInPlainText`. | – |
| T38 | Redis outage (D) | Redis stops or becomes unreachable, hoping the limits switch off. | Limits never switch off: each instance counts in its own memory until Redis is back, retrying it every 5 seconds (so an outage adds no per-request timeout). A lockout that started during the outage still holds afterwards. The health check reports `redis: DOWN`. [`FailoverCounterStore`](backend/athena/src/main/java/com/athena/security/FailoverCounterStore.java) | `FailoverCounterStoreTest` (4 tests, one against a real unreachable address). | During the outage each instance counts separately, so the effective limit is multiplied by the number of instances. |
| T39 | Taking over through the first-admin bootstrap (E) | Set `BOOTSTRAP_ADMIN_*` (or rerun `scripts/bootstrap-admin`) on a running system to create an administrator or reset one's password; start two servers at once to get two. | The bootstrap acts only while no administrator exists; afterwards it changes nothing, never resets a password and refuses an email that belongs to a user. A database lock lets one server at a time create the administrator. The password needs 12+ characters, is never logged, and the creation is audited as `ADMIN_BOOTSTRAPPED`. [`AdminBootstrap`](backend/athena/src/main/java/com/athena/config/AdminBootstrap.java) | `AdminBootstrapIntegrationTest` (13 tests: created once, later settings ignored, existing email refused, invalid settings refused without echoing the password, four simultaneous servers create one). CI runs the script against Docker Compose. | Whoever controls the settings before the first administrator exists chooses it: remove the settings after the first start. |
| T40 | Staff onboarding misuse (E, R) | A customer or auditor adds an administrator; an administrator adds one quietly or with a weak password. | `POST /api/admin/staff` is ADMIN-only (URL rule plus `@PreAuthorize`); roles are ADMIN or AUDITOR only; staff get no account and cannot move money; initial passwords need 12+ characters (and staff keep that bar when they change them); every creation is audited as `STAFF_CREATED` with the administrator as actor. | `StaffOnboardingIntegrationTest` (9 tests), `PasswordChangeIntegrationTest.staffNeedTwelveCharacters`. | A rogue administrator can add others; the audit log shows who did it and when. |
| T41 | Password change with a stolen token (S, E) | Use a stolen session to set a new password and lock the owner out, or to guess the current password. | The current password is required. Wrong guesses are refused, audited (`PASSWORD_CHANGED`, FAILED) and count as failed sign-ins, so the sixth attempt waits like a login (429). | `PasswordChangeIntegrationTest.wrongCurrentPasswordsAreRefusedAuditedAndThrottledLikeSignIns`. | – |

## 5. Things deliberately out of scope

* **Multi-factor authentication** and password reset by email: not in the problem statement.
* **Encryption at rest**: left to the managed PostgreSQL service.
* **Real payment rails**: deposits and withdrawals are simulated cash movements.

## 6. Known limits (honest list)

1. Rate-limit and login counters are shared through Redis when `REDIS_URL`
   is set (Render and Docker Compose set it). Without it, on a developer's
   PC, they live in each instance's memory and reset on restart; the log
   says so at startup. During a Redis outage the same applies until it is
   back (T38).
2. The app's database role can change balances and account or user status,
   because the app must. A direct balance edit is caught by the integrity
   report rather than prevented (T12). The app also holds the owner's
   password to upgrade the schema at startup; see T36 for the stricter split.
3. Revoked token ids are stored in PostgreSQL and cleaned up when they expire.
   A database outage therefore also blocks authentication. That is the
   safe failure: it locks people out rather than letting revoked tokens in.
4. One judge or attacker who knows a customer's email can block *their own*
   network's sign-in to it for 15 minutes. They cannot block the customer's
   network.
5. CI generates its secrets; the deployed app's secrets must be set by hand in
   the hosting dashboard (see [important/DEPLOYMENT.md](important/DEPLOYMENT.md)).
   Render generates the app role's password itself (`generateValue`).
