# BalanceVault: every requirement, where it is met, and the proof

The LedgerLine challenge was renamed **BalanceVault**. Athena is this
project's entry: it was built as LedgerLine, renamed Athena, and extended for
BalanceVault rather than rebuilt, as the organisers asked. Every original
requirement still holds; the new ones are added below them.

Each row names the code that meets the requirement and the automated test
(or CI step) that fails if it stops being met. Test classes are in
`backend/athena/src/test/java/com/athena`; browser tests in `frontend/e2e`.

## Original requirements

| Requirement | Where it is met | Proof |
| --- | --- | --- |
| Double-entry ledger | Every movement posts a debit and a credit against the settlement (cash) account ([`LedgerService`](../backend/athena/src/main/java/com/athena/service/LedgerService.java)); a deferred trigger refuses to commit unbalanced postings ([`schema.sql`](../backend/athena/src/main/resources/db/schema.sql), `trg_ledger_entries_balanced`). | `TransactionServiceIntegrationTest.theDatabaseRejectsAnUnbalancedLedger`; `assertLedgerIsConsistent()` after every money test; `/api/oversight/integrity` (security check **L1**). |
| Idempotency | `Idempotency-Key` required on every money request, unique per user in the database, with a SHA-256 fingerprint of the request; checked after the row locks. | `IdempotencyIntegrationTest.simultaneousDuplicatesMoveMoneyOnlyOnce`, `reusingAKeyForADifferentRequestIsRejected`; `IdempotencyControllerIntegrationTest`; security checks **M3**, **M5**. |
| Concurrency safety | Account rows locked with `SELECT … FOR NO KEY UPDATE` in ascending id order (no double spending, no deadlocks); one database transaction per request. | `concurrentWithdrawalsCannotOverdrawAnAccount`, `concurrentTransfersFromOneAccountCannotOverdrawIt`, `opposingConcurrentTransfersFinishWithoutDeadlock`, `balancesStayConsistentUnderConcurrentMixedActivity`; security check **M4**. |
| Overdraft prevention | The balance is checked on the locked row; `CHECK (balance >= 0)` backs it up in the database. | `withdrawalBeyondTheBalanceIsRejectedAndChangesNothing` and the concurrency tests above; security check **M2**. |
| Append-only audit ledger | Every money request, sign-in, refusal and admin change is audited. **New:** the database refuses every UPDATE and DELETE of ledger entries, transactions and audit records (triggers), and the app's role has no right to either. | `AuditLogIntegrationTest`; `AppendOnlyRecordsIntegrationTest` (all three tables, even as the owner); `DatabaseRolesIntegrationTest.theAppsRoleIsRefused`; CI runs `DELETE FROM audit_logs` as the app's role against the running app and expects a refusal. |

## New mandatory requirements

| Requirement | Where it is met | Proof |
| --- | --- | --- |
| Account management and user onboarding | Customers sign up and get an account number at once. Every user has a **Profile** page; `POST /api/users/me/password` changes the password, needs the current one (guesses throttled like sign-ins) and ends every session. Administrators enable or disable users and freeze accounts, and **add staff** (`POST /api/admin/staff`, Users page, "Add staff member"). | `PasswordChangeIntegrationTest` (7), `StaffOnboardingIntegrationTest` (9), `AccountSecurityIntegrationTest`, `AdminAndOversightIntegrationTest`; `ProfilePage.test.jsx`, `AdminUsersPage.test.jsx`; Playwright: "a customer changes their password…", "an administrator adds an auditor, who signs in with the initial password and changes it" (six browser setups). |
| RBAC | Roles USER, AUDITOR, ADMIN, loaded from the database on every request; URL rules plus `@PreAuthorize` (two independent checks); staff have no account and cannot move money; every refusal audited. | `AccessControlIntegrationTest.eachRoleReachesOnlyItsOwnEndpoints`, `SessionSecurityIntegrationTest.rolesWrittenIntoATokenAreIgnored`, `TransactionControllerIntegrationTest.usersWithoutTheUserRoleCannotMoveMoney`; `StaffOnboardingIntegrationTest.onlyAnAdministratorMayAddStaff`; Playwright `staff.spec.js`; security check **Z2**. |
| PostgreSQL / Prisma | PostgreSQL (17 in CI and Compose, 18 locally). Prisma is a Node.js ORM and this backend is Java, so **Hibernate (JPA)** plays its part: typed entities, repositories with bound parameters only, and `ddl-auto=validate`, which stops the app if the entities and the schema disagree. The schema is versioned in `schema.sql` and upgrades itself idempotently at startup. | Every integration test runs against a real PostgreSQL (`PostgresIntegrationTest`); CI uses a `postgres:17` service; `RenameCarryOverIntegrationTest` upgrades an older database. |
| Redis rate limiting | API, registration and three sign-in limits count in Redis when `REDIS_URL` is set: atomic Lua scripts, hashed keys, shared by every instance. While Redis is down, limits keep working in memory and Redis is retried every 5 seconds. Compose has a `redis` service; `render.yaml` a Key Value instance. | `RedisCounterStoreTest` (5), `RedisRateLimitIntegrationTest` (the whole app on Redis: sign-in throttling counted in Redis, health `redis: UP`), `FailoverCounterStoreTest` (4); CI end-to-end job runs on a real Redis and checks the health answer. |
| First-admin bootstrap script | `scripts/bootstrap-admin.cmd` (Windows) and `scripts/bootstrap-admin.sh` (Linux, macOS, Docker Compose, the jar): ask for the email, name and password, run the app once in a one-off mode and exit. It acts only while no administrator exists, never resets one, is audited (`ADMIN_BOOTSTRAPPED`), and two servers at once still create one. `BOOTSTRAP_ADMIN_*` does the same for hosts without a shell (Render free plan). | `AdminBootstrapIntegrationTest` (13); CI starts the Compose stack, runs `sh scripts/bootstrap-admin.sh docker` and signs in as the new administrator; the Windows script was run by hand against a scratch database: weak password exit 1, valid exit 0, again exit 0 with nothing changed. |
| Database roles | The tables' owner runs `schema.sql` at startup only; the app connects as `athena_app`, a member of `athena_writer`, which may read, add rows and update only `app_users` and `accounts`. [`DatabaseRolesProvisioner`](../backend/athena/src/main/java/com/athena/config/DatabaseRolesProvisioner.java) creates the role; `scripts\setup-local.cmd`, Compose, Render and CI all use it. | `DatabaseRolesIntegrationTest` (23: the whole app runs as a fresh restricted role; 14 forbidden statements fail with SQLSTATE `42501`); CI checks that the running app holds connections as `athena_app` (end-to-end job and Compose stack). |
| Deployment health checks | `GET /api/health` answers `{"status","database","redis"}`, `UP`/`DOWN` only; 503 when the database is down. Render's `healthCheckPath`; Compose health checks for PostgreSQL and Redis, with the app waiting for both. | `HttpHardeningIntegrationTest.theHealthCheckSaysOnlyUpOrDownPerDependency`, `RedisRateLimitIntegrationTest.theHealthCheckReportsRedisUp`; CI waits for the health check and asserts `redis: UP` in both the end-to-end job and the Compose stack. |

## Deliverables

| Deliverable | Where |
| --- | --- |
| THREATS.md | [../THREATS.md](../THREATS.md): 41 threats with defences, proving tests and residual risk; T35 to T41 are the BalanceVault additions. |
| PROMPTS.md | [../PROMPTS.md](../PROMPTS.md): the prompts that steered the work and every AI mistake, including the BalanceVault ones. |
| Automated concurrency and idempotency tests | `IdempotencyIntegrationTest`, `TransactionServiceIntegrationTest`, `IdempotencyControllerIntegrationTest`; also `AdminBootstrapIntegrationTest.serversStartingTogetherCreateOneAdministrator`. All run in CI on every push. |
| API documentation | [API.md](API.md) and the Postman collection [testing/Athena.postman_collection.json](testing/Athena.postman_collection.json) (folder 7 covers the BalanceVault endpoints). |
| Demo video | Shot list and script in [DEMO.md](DEMO.md), "Demo video". The recording itself is made by the author. |
| Technical write-up | [TECHNICAL_WRITEUP.md](TECHNICAL_WRITEUP.md). |

## Check it yourself in five minutes

```bat
cd /d E:\athena\backend\athena
mvnw test
cd /d E:\athena\frontend
npm test
```

Then, with the app running (`scripts\start-local.cmd`):

```bat
curl http://localhost:8080/api/health
node E:\athena\important\testing\security-check.mjs http://localhost:8080
```

The last full run of everything, with numbers, is in
[testing/TEST_RESULTS.md](testing/TEST_RESULTS.md).
