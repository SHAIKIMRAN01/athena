# Athena threat model

This file lists what an attacker could try against Athena, what stops it,
and **which test proves that it is stopped**. Every threat has an id (T1, T2…).
The same ids appear in the Javadoc of the backend security tests, and each
row also names the black-box check (S1, A1, Z1…) in
[`important/testing/security-check.mjs`](important/testing/security-check.mjs), which sends the
attack over HTTP to a running server.

> How to verify everything below yourself:
> `mvnw test` (backend, 159 tests) and
> `node important/testing/security-check.mjs <your-url>` (21 black-box checks).
> The last local run is in [important/testing/SECURITY_CHECK_REPORT.md](important/testing/SECURITY_CHECK_REPORT.md).

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
| Someone with database access | Edit rows directly, bypassing the application. |
| Attacker on another website | Make a victim's browser send requests (CSRF, CORS) or embed our pages (clickjacking). |

## 3. Trust boundaries

```
Browser ──HTTPS──▶ hosting proxy ──▶ Spring Boot (filters ▶ security rules ▶ controllers ▶ services) ──JDBC──▶ PostgreSQL
  untrusted          trusted for client IP only        every request re-checked here                 constraints + trigger
```

Nothing that arrives from the browser is trusted: not the account to debit,
not the roles, not the amount format, not the request size. The database is
the last line of defence and has its own rules (constraints and a trigger)
that hold even if the application has a bug.

## 4. Threats, defences and the tests that prove them

STRIDE category: **S**poofing, **T**ampering, **R**epudiation,
**I**nformation disclosure, **D**enial of service, **E**levation of privilege.

### Authentication and sessions

| Id | Threat (STRIDE) | Attack | Defence | Proven by | Residual risk |
| --- | --- | --- | --- | --- | --- |
| T1 | Forged or edited token (S, E) | Change the email in the JWT payload, sign with another key, send `alg: none`, or a token from another issuer. | HS256 signature checked with a 256+-bit key; issuer `athena` and a token id required; jjwt refuses unsigned tokens. The app will not start with a missing or short key. [`JwtService`](backend/athena/src/main/java/com/athena/security/JwtService.java) | `SessionSecurityIntegrationTest`: `aTokenWhosePayloadWasEditedIsRefused`, `aTokenSignedWithAnotherKeyIsRefused`, `anUnsignedTokenIsRefused`, `aTokenFromAnotherIssuerOrWithoutAnIdIsRefused`, `theServerRefusesToStartWithAWeakSigningKey`. Check **A2**. | Anyone holding JWT_SECRET can mint tokens: it lives only in the host's secret store. |
| T2 | Stolen or signed-out token reused (S) | Copy a token from a shared computer and use it after the owner signs out. | Tokens expire after 15 minutes. Sign-out stores the token id in `revoked_tokens` and the filter refuses it (`TOKEN_REVOKED`). | `logoutEndsThatSessionOnly`, `anExpiredTokenIsRefusedAsExpired`. Check **A3**. | A stolen token works until its owner signs out or it expires (at most 15 minutes). |
| T3 | Disabled user keeps access (E) | An admin disables a user who still holds a valid token. | The user is loaded from the database on every request; a disabled user gets 401 `ACCOUNT_DISABLED` at once. | `disablingAUserStopsTokensTheyAlreadyHold`, `disablingAUserBlocksTheirSignInAndIsAudited`, `disabledUsersCannotLogIn`. | – |
| T4 | Password guessing (S) | Try thousands of passwords against one account, or one password against many accounts. | BCrypt hashes. Failed logins are throttled per email+IP (5 in 15 minutes), per IP (30) and per email from any IP (50), answered with 429 and `Retry-After`. [`LoginAttemptService`](backend/athena/src/main/java/com/athena/security/LoginAttemptService.java) | `repeatedFailuresFromOneNetworkAreThrottled`, `aSuccessfulLoginClearsEarlierFailures`, `FixedWindowRateLimiterTest`. Check **A4**. | Counters are in memory: reset by a restart, and not shared between several instances (see section 6). |
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
| T12 | Silent tampering in the database (T, R) | Someone with database access edits a balance directly. | The integrity report (`/api/oversight/integrity`) reconciles every balance with its ledger entries and the settlement account with all customer money, in one snapshot. | `theIntegrityReportCatchesABalanceChangedOutsideTheLedger`, `theIntegrityReportPassesAfterNormalActivity`. Check **L1**. | Detection, not prevention: database credentials must stay secret. |
| T11b | Frozen account still moving money (E) | Withdraw from, or transfer to, a frozen account. | Account status checked on the locked row for both sides. | `aFrozenAccountCanNeitherSendNorReceive`, `frozenAccountsCannotSendOrReceiveMoney`. | – |

### Input, output and the browser

| Id | Threat (STRIDE) | Attack | Defence | Proven by | Residual risk |
| --- | --- | --- | --- | --- | --- |
| T14 | Injection through parameters (T, I) | `sort=amount;DROP TABLE accounts`, `type=DEPOSIT' OR '1'='1`, `search=' OR 1=1 --`. | Only bound parameters reach SQL; `sort` is checked against a whitelist; enum and date parameters are type-checked; LIKE wildcards are escaped. | `hostileQueryParametersAreRefusedOrMatchNothing`, `invalidHistoryQueriesReturn400`, `searchMatchesNameEmailOrAccountAndTreatsWildcardsAsText`. Check **I1**. | – |
| T7 | Stored script in a name (T) | Register as `<img src=x onerror=alert(1)>` so an admin page runs it. | Names may contain only letters, spaces, `.'-`; React escapes all output; the CSP forbids inline scripts. | `namesWithMarkupAreRefused`. Check **I2**. | – |
| T16 | Script injection and clickjacking (T, I) | Run injected JavaScript to steal the token; frame our page under a fake button. | Content-Security-Policy `script-src 'self'`, `frame-ancestors 'none'`, `X-Frame-Options: DENY`, `nosniff`, `Referrer-Policy: no-referrer`. The token is kept in `sessionStorage` (gone when the tab closes), never in a cookie. | `responsesCarrySecurityHeaders`, `appRoutesServeTheApp`, `frontend/src/api/baseUrl.test.js` (CSP names exactly the API). Checks **S1**, **S2** (website on Vercel: `vercel.json` headers plus a CSP meta tag). | A successful XSS could still read `sessionStorage`; the CSP is what prevents it. |
| T17 | Cross-site requests (CSRF, CORS) (T) | A malicious website makes the victim's browser call the API. | No cookies, so the browser never adds credentials by itself. CORS is closed unless `CORS_ALLOWED_ORIGINS` lists an origin. | `otherWebsitesCannotCallTheApiFromABrowser`, `CorsIntegrationTest` (only the listed website, e.g. on Vercel, and its preview addresses). Checks **C1**, **C2**. | Listing `https://*.vercel.app` would admit every Vercel site: list exact addresses. |
| T15 | Error messages leaking internals (I) | Send malformed JSON, wrong types, unknown paths, and read the errors. | Every error is problem-detail JSON with a `code`; no stack traces, SQL or class names; an unexpected error shows only a random `errorId` that matches the server log. | `errorResponsesDoNotRevealInternals`, `theHealthCheckSaysOnlyUpOrDown`. Check **I4**. | – |
| T13 | Oversized requests (D) | Send a 50 MB JSON body, or a chunked body without a length. | Bodies over 16 KB are refused with 413 before parsing; chunked bodies are cut off at the limit. | `anOversizedBodyIsRefusedBeforeItIsRead`. Check **I3**. | – |
| T28 | Path traversal to server files (I) | Request `/../application.properties`. | Static files are served by Spring's resolver, which refuses paths outside the static folder. | `pathTraversalDoesNotEscapeTheStaticFolder`. | – |
| T29 | Spreadsheet formula injection in exported statements (T) | A value starting with `=` runs as a formula when the CSV is opened. | `csvCell` quotes every cell and prefixes `= + - @` with an apostrophe. | `frontend/src/lib/csv.test.js`. | – |

### Availability, accountability and supply chain

| Id | Threat (STRIDE) | Attack | Defence | Proven by | Residual risk |
| --- | --- | --- | --- | --- | --- |
| T18 | Request flood (D) | One machine sends thousands of requests per second. | 600 API requests per minute per client IP (429 with `Retry-After`), checked before authentication; page size at most 100; statements at most 366 days. | `oneNetworkFloodingTheApiIsSlowedDownAndOthersAreNot`, `invalidHistoryQueriesReturn400`, `invalidStatementQueriesReturn400`. | A distributed attack from many IPs needs the hosting platform's DDoS protection. |
| T19 | Mass registration (D) | Create thousands of accounts. | 50 registrations per hour per IP. | `oneNetworkCannotRegisterUsersWithoutLimit`. | – |
| T30 | Denying an action afterwards (R) | "I never made that transfer", "I never froze that account". | Every money request (completed, refused, replayed, errored), sign-in, sign-out, registration, 403 and admin change is in an append-only audit log with actor, time, IP and details. | `AuditLogIntegrationTest` (5 tests), `failedLoginsAreAuditedWithoutThePassword`, `everyRefusedRoleCheckIsAudited`. | The audit table is append-only in the application; a database administrator could still edit it. |
| T31 | Secrets in the repository (I) | Read the GitHub repository for passwords or keys. | All secrets come from environment variables; `.env` is git-ignored; demo passwords come only from `DEMO_*_PASSWORD`; CI generates throwaway secrets per run and masks them. | Pre-commit secret scans (see PROMPTS.md). | – |
| T32 | Vulnerable dependency (T, I) | A known CVE in a library. | CI runs `npm audit --audit-level=high`; Dependabot proposes updates weekly; CodeQL scans the code. | `.github/workflows/ci.yml`, `.github/workflows/codeql.yml`. | New CVEs appear between scans. |
| T33 | Faked client IP to dodge rate limits (S) | Send `X-Forwarded-For: 1.2.3.4`. | Forwarded headers are ignored unless `FORWARD_HEADERS_STRATEGY=native`, and then Tomcat trusts them only from private-network proxies. | Configuration (`application.properties`); `oneNetworkFloodingTheApiIsSlowedDownAndOthersAreNot` uses the real remote address. | – |
| T34 | Tokens or passwords in logs (I) | Read the server logs. | Tokens and passwords are never logged (the Day 1 debug prints were removed); failed-login audit records store the email and reason, not the password. | `failedLoginsAreAuditedWithoutThePassword`. | – |

## 5. Things deliberately out of scope

* **Multi-factor authentication** and password reset by email: not in the problem statement.
* **Encryption at rest**: left to the managed PostgreSQL service.
* **Real payment rails**: deposits and withdrawals are simulated cash movements.

## 6. Known limits (honest list)

1. Rate-limit and login counters live in memory: fine for one instance,
   reset on restart, and not shared if the app is scaled to several
   instances. A shared store (Redis) would fix it.
2. Revoked token ids are stored in PostgreSQL and cleaned up when they expire.
   A database outage therefore also blocks authentication. That is the
   safe failure: it locks people out rather than letting revoked tokens in.
3. One judge or attacker who knows a customer's email can block *their own*
   network's sign-in to it for 15 minutes. They cannot block the customer's
   network.
4. CI generates its secrets; the deployed app's secrets must be set by hand in
   the hosting dashboard (see [important/DEPLOYMENT.md](important/DEPLOYMENT.md)).
