# 1. Integrity and security (35 marks)

> "What a judge can break on your deployed system. The same attempts for everyone."

Judges will attack the **deployed** app. They will forge tokens, read other
people's data, overdraw accounts, double-spend with parallel requests,
replay requests, inject SQL or scripts, flood endpoints and look for leaked
errors. Here is what each attempt meets.

## What a judge will try, and what happens

| A judge tries to… | What happens | Where it is enforced |
| --- | --- | --- |
| Use the API without signing in | 401 with a reason code | `SecurityConfig`, `ProblemJsonAuthenticationEntryPoint` |
| Edit their JWT (another email, `alg: none`, another key) | 401 `TOKEN_INVALID` | `JwtService.parse` |
| Reuse a token after signing out | 401 `TOKEN_REVOKED` | `TokenRevocationService` |
| Become admin at registration (`"roles": ["ROLE_ADMIN"]`) | Ignored; the user is USER | `RegisterRequest` has no role field |
| Call admin or auditor endpoints as a customer | 403, recorded in the audit log | URL rules + `@PreAuthorize` |
| Read someone else's transaction by id | 404 (same as "does not exist") | `TransactionHistoryService.detail` |
| Debit someone else's account (`fromAccountNumber`) | Ignored: the sender is always the token's user | `TransactionController` |
| Deposit 0, -5, 10.001, 1e30, "abc" | 400 | Bean Validation, `MoneyRequest` |
| Withdraw more than the balance | 422, recorded as FAILED, balance unchanged | `TransactionProcessor.checkRules` |
| Send 10 withdrawals of the whole balance at once | Exactly 1 succeeds | Row locks in id order, `CHECK (balance >= 0)` |
| Click Pay twice / replay a request | One transaction; the repeat says `Idempotent-Replayed: true` | Idempotency keys, unique index |
| Reuse a key for a bigger amount | 422 `IDEMPOTENCY_KEY_REUSED` | Request fingerprint |
| Guess passwords | 429 after 5 failures from one network | `LoginAttemptService` |
| Inject SQL in `sort`, `type`, `search` | 400 or no match, never 500 | Bound parameters, sort whitelist |
| Store a script in their name | 400 | Name pattern, React escaping, CSP |
| Send a huge body | 413 | `RequestSizeLimitFilter` |
| Flood the API | 429 after 600 requests per minute | `RateLimitFilter` |
| Read stack traces in errors | Only `code`, `detail` and an `errorId` | `GlobalExceptionHandler` |
| Frame the site, or call it from another site | Blocked by CSP / X-Frame-Options / closed CORS | Security headers |
| Edit a balance in the database | The auditor's integrity check turns red | `LedgerReportService` |
| Rewrite or delete a ledger, transaction or audit row in the database | `ERROR: … is append-only`, even as the owner | Triggers in `schema.sql`, section 10 |
| Use the app's own database login to do damage | `permission denied` (42501): no UPDATE or DELETE of history, no TRUNCATE, DROP or ALTER | Role `athena_app` in `athena_writer`, `DatabaseRolesProvisioner` |
| Spread password guesses over several app instances | The same limits everywhere: the counters are shared in Redis | `RedisCounterStore` |
| Stop Redis to switch the limits off | Limits keep working in each instance's memory; health shows `redis: DOWN` | `FailoverCounterStore` |
| Create or reset an administrator with the bootstrap settings or script | Nothing happens once an administrator exists | `AdminBootstrap` |
| Add an administrator as a customer or an auditor | 403, audited | `POST /api/admin/staff`, ADMIN only |
| Keep using a session after the owner changed the password | 401 `TOKEN_REVOKED` | The password stamp in each token |

## Show it

```bat
node important\testing\security-check.mjs https://your-deployed-url
```

21 checks, each named after a threat in THREATS.md. With the website on
Vercel, add `--site https://your-site.vercel.app` for 23. Show the report
(`important/testing/SECURITY_CHECK_REPORT.md`), then sign in as the auditor and
show **"The books balance"**: the ledger is still consistent after every
attack.

## Evidence in tests

* `backend/athena/src/test/java/com/athena/security/`: 86 tests
  aimed at attacks, including Redis, staff onboarding and password changes.
* `AppendOnlyRecordsIntegrationTest`, `DatabaseRolesIntegrationTest`: the
  database refuses to rewrite history, and the app's role cannot try.
* `AdminBootstrapIntegrationTest`: the first-admin bootstrap cannot be
  abused.
* `TransactionServiceIntegrationTest`: concurrency and rollback.
* `IdempotencyIntegrationTest`: duplicates and replays.

## Integrity by design (worth saying aloud)

1. **Three layers guard money.** Application rules on locked rows, then
   database constraints, then a commit-time trigger that refuses an
   unbalanced ledger. History is append-only in the database itself, and
   the app connects with a role that could not rewrite it anyway.
2. **No floats anywhere.** BigDecimal in Java, NUMERIC(19,2) in PostgreSQL,
   decimal strings in the browser.
3. **Every refusal is recorded.** A refused withdrawal is a FAILED
   transaction plus an audit record; a 403 is an audit record.

## Before judging

* Deploy with HTTPS and a fresh `JWT_SECRET`.
* Run the security check against the deployed URL.
* Keep `DEMO_*_PASSWORD` values long and private.
