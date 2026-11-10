# Athena: technical write-up

Athena is a financial transaction simulator, entered for **BalanceVault**
(the renamed LedgerLine challenge). Customers deposit, withdraw and transfer
money; auditors and administrators watch and manage. This write-up explains
how it is built, the problems that mattered most, how each was solved and
proven, and what was deliberately left out.

* Requirements and proof, row by row: [BALANCEVAULT.md](BALANCEVAULT.md)
* Threat model: [THREATS.md](../THREATS.md) · API: [API.md](API.md) ·
  Diagrams: [ARCHITECTURE.md](ARCHITECTURE.md) · AI usage: [PROMPTS.md](../PROMPTS.md)

## 1. Shape of the system

| Part | Technology | Job |
| --- | --- | --- |
| Website | React 19, React Router 7, Vite 8 | Pages per role; money as exact text, never a float |
| API | Java 21, Spring Boot 4.1, Spring Security 7, Hibernate 7 | Every rule that matters is enforced here, on every request |
| Database | PostgreSQL | The ledger and its invariants; the last line of defence |
| Shared counters | Redis (Jedis 7) | Rate limits and sign-in throttling, shared by every instance |
| Delivery | Docker, Docker Compose, Render blueprint, Vercel for the website, GitHub Actions | One image; CI starts the whole stack on every push |

One jar serves both the API and the built website, so in the simplest
deployment there is one origin, no CORS and one set of security headers. The
website can also live on Vercel, with the API on Render; CORS then admits only
that exact address.

## 2. Getting money right

**Double entry.** Customer accounts are the bank's liabilities and one
settlement account is its cash. A deposit debits cash and credits the
customer, a withdrawal the reverse, and a transfer debits the sender and
credits the receiver. So at every moment the cash balance equals the sum of
customer balances, and debits equal credits. A deferred PostgreSQL constraint
trigger refuses to commit any transaction whose entries do not balance, so
even a coding error cannot leave the books wrong. `/api/oversight/integrity`
recomputes everything in one REPEATABLE READ snapshot.

**Exact amounts.** `BigDecimal` in Java, `NUMERIC(19,2)` in the database,
at most two decimals and 17 integer digits, `CHECK (amount > 0)`. The
website validates the typed text and sends it unchanged, and parses answers
as exact text; no amount ever passes through a JavaScript number.

**Concurrency.** A money request locks the account rows it touches with
`SELECT … FOR NO KEY UPDATE`, always in ascending id order, then checks the
balance and posts. Two withdrawals of a whole balance at the same instant
therefore run one after the other, and opposing transfers cannot deadlock.
`CHECK (balance >= 0)` backs up the overdraft rule in the database. Tests
release 8 to 60 requests at once on separate threads, behind one latch, and
then check every invariant.

**Idempotency.** Every money request carries an `Idempotency-Key`. Keys are
unique per user in the database, and the request's SHA-256 fingerprint is
stored with the key. The key is looked up *after* the row locks are taken, so
simultaneous duplicates queue behind each other: one moves the money, the
others replay its answer (`Idempotent-Replayed: true`). The same key with a
different body is refused (422).

**Atomicity and refusals.** One database transaction per request: all or
nothing. A refusal after the locks (insufficient funds, a frozen account) is
committed as a FAILED transaction with no ledger entries, so it appears in the
customer's history and the audit log, and replays like a success would.

## 3. History that cannot be rewritten

The ledger, the transactions and the audit log are append-only at three
levels:

1. **Code:** their entities have no setters, and nearly every column is
   `updatable = false`; a transaction is built complete (COMPLETED or FAILED)
   before it is saved.
2. **Database triggers:** `athena_refuse_change()` runs `BEFORE UPDATE OR
   DELETE` on each row of `ledger_entries`, `financial_transactions` and
   `audit_logs`, and raises `… is append-only`. This holds for everyone,
   including the tables' owner. `TRUNCATE`, which is not a row operation and
   which only the owner may run, still empties test databases.
3. **Grants:** the app's own role has no UPDATE or DELETE right on those
   tables in the first place (section 5).

A transaction is written once, already COMPLETED or FAILED, so the app
never needs to update one. `AppendOnlyRecordsIntegrationTest` proves that
UPDATE and DELETE fail on all three tables while inserts work; the one test
that back-dates ledger rows does it the only way left, switching the trigger
off for one statement inside one transaction, as the owner.

## 4. Identity, sessions and roles

* **Passwords:** BCrypt; 8 to 72 characters (at most 72 bytes, BCrypt's limit)
  with a letter and a digit; 12 or more for staff.
* **Tokens:** HS256 JWTs signed with a 256+-bit key that the app checks at
  startup; 15 minutes; issuer and token id required. Logout stores the token
  id; the filter refuses it afterwards.
* **Ending every session on a password change.** JWT issue times have whole
  seconds, so comparing them with the change time would let a token issued
  in the same second survive. Each token instead carries the user's password
  stamp (the change time in milliseconds) from when it was issued; the filter
  refuses any token whose stamp differs from the user's current one. Tokens
  from before stamps existed count as 0, which matches users who never
  changed their password.
* **Guessing through a stolen session:** the current password is required,
  and wrong guesses count as failed sign-ins, so the sixth waits like a login.
* **Sign-in throttling:** 5 failures per email and IP in 15 minutes, 30 per
  IP, 50 per email from anywhere; answers 429 with `Retry-After`.
* **Roles:** USER moves their own money; AUDITOR reads everything and changes
  nothing; ADMIN manages users and staff and cannot move money. Roles come
  from the database on every request (never from the token); every
  controller is guarded twice, by URL rules and `@PreAuthorize`; every 403 is
  audited.

**Onboarding.** Customers sign up themselves and get an account number at
once; the sign-up form cannot grant a role. Staff never come from it. The
first administrator comes from the bootstrap (section 6); administrators then
add other administrators and auditors through `POST /api/admin/staff`, with an
initial password they pass on privately. Every staff creation is audited with
the administrator as actor.

## 5. Least privilege in the database

| Role | Rights |
| --- | --- |
| Owner (`DB_OWNER_USERNAME`) | Owns the tables. Used at startup only, to run `schema.sql` and create the app's role. |
| `athena_writer` (no login) | SELECT everything; INSERT everywhere but `roles`; UPDATE `app_users` and `accounts` only; DELETE `revoked_tokens` only. Set by `schema.sql` on every start (REVOKE ALL, then GRANT). |
| `athena_app` (`DB_USERNAME`) | Member of `athena_writer` and nothing else: not a superuser, owns nothing, cannot create roles or databases. |

`DatabaseRolesProvisioner` is a bean post-processor on the DataSource, so it
runs before anything touches the database. When the owner and the app's role
differ, it connects as the owner, creates `athena_writer` if missing, creates
`athena_app` with `DB_PASSWORD` or resets its password, and refuses a
superuser. Role names are checked against a strict pattern, and identifiers
and the password are quoted by PostgreSQL itself (`quote_ident`,
`quote_literal`). Spring then runs `schema.sql` as the owner and connects the
pool as `athena_app`.

`DatabaseRolesIntegrationTest` runs the **whole application** as a fresh
restricted role. Every operation works within the grants: register, sign in,
deposit, replay, refusal, transfer, freeze, the integrity report, password
change and logout. Then 14 statements fail with SQLSTATE `42501`, among them
UPDATE and DELETE of history, TRUNCATE, DROP, ALTER, disabling a trigger,
CREATE TABLE and CREATE ROLE. CI repeats `DELETE FROM audit_logs` as
`athena_app` against the running app.

What it does not cover: the app's process holds the owner's password to
upgrade the schema at startup. DEPLOYMENT.md, "Stricter", shows how to run
the schema step separately and start the app with no owner credentials at
all.

## 6. Redis rate limiting

Limits are fixed windows per key: API requests per IP per minute,
registrations per IP per hour, and three sign-in limits. The limiter talks to
a `CounterStore`:

* `RedisCounterStore`: one Lua script per increment (`INCR`, then `PEXPIRE`
  on the first count), so the count and the window's expiry are set together
  and two instances can never both see the same count. Keys are
  `athena:limit:` plus a SHA-256 of the subject, so Redis holds no email or
  IP address. The URL may carry a user, a password and `rediss://` for TLS.
* `InMemoryCounterStore`: the same rules in one process.
* `FailoverCounterStore`: Redis first. When Redis fails it logs once, counts
  in memory and leaves Redis alone for 5 seconds, so an outage adds no network
  timeout to every request. A read takes the higher of the two counts, so a
  lockout that started during an outage still holds afterwards.

With no `REDIS_URL` (a developer's PC) the app uses memory and says so at
startup. The tests use jedis-mock, a Redis protocol server that runs inside
the test, including Lua: `RedisCounterStoreTest` (expiry, two instances
sharing a count, hashed keys), `RedisRateLimitIntegrationTest` (the whole app
on Redis), `FailoverCounterStoreTest` (outage, retry interval, recovery). CI
runs the end-to-end suite against a real Redis.

## 7. The first administrator

`scripts/bootstrap-admin.cmd` (Windows) and `scripts/bootstrap-admin.sh`
(Linux, macOS, Docker Compose, a built jar) ask for the email, name and
password, typing the password hidden and twice. They pass the values to one
run of the app through its environment, never a file or the command line.
The app prepares the database as at every start, runs `AdminBootstrap` first
among the startup tasks, and exits.

* It acts only while no administrator exists. Afterwards the settings change
  nothing, so they can never take over or reset an administrator.
* An email that belongs to a user is refused, not promoted.
* A PostgreSQL advisory lock makes the check and the insert one step, so four
  servers starting together create one administrator (tested).
* The name and email follow the sign-up rules, and the password needs 12+
  characters. Error messages name the setting and never repeat the password.
* The creation is audited as `ADMIN_BOOTSTRAPPED`.

Hosts without a shell, such as Render's free plan, use the same code: set
`BOOTSTRAP_ADMIN_EMAIL` and `BOOTSTRAP_ADMIN_PASSWORD`, deploy once, then
delete them.

## 8. Health and operations

`GET /api/health` answers `{"status","database","redis"}` with `UP`, `DOWN`
or, for Redis on a PC, `NOT_CONFIGURED`; never versions or addresses. Only
the database decides the status: 503 makes Render keep traffic away from an
instance that cannot work. Redis being down is reported but not fatal,
because limits keep working.

* **Docker Compose:** PostgreSQL and Redis with health checks; the app starts
  only when both are healthy, connects as `athena_app`, and neither store
  publishes a port.
* **Render:** the blueprint creates the web service, PostgreSQL and a Key
  Value instance, both stores closed to the internet. It wires the owner,
  `REDIS_URL` and a generated password for the app's role.
* **CI on every push:**
  * the backend suite against PostgreSQL;
  * frontend lint, formatting, unit tests, build and audit;
  * end to end against the built jar with a real Redis and the restricted
    role (security check and Playwright in six browser setups);
  * the Docker image built and scanned by Trivy;
  * the Compose stack started for real, with the bootstrap script run
    against it.

## 9. How it is tested

| Suite | Count | What it proves |
| --- | --- | --- |
| Backend (JUnit 6, Spring Boot test, real PostgreSQL) | 235 tests in 29 classes | Ledger invariants after every money test, concurrency and idempotency under real threads, every role boundary, sessions, the append-only triggers, the restricted role, Redis, the bootstrap |
| Frontend unit (Vitest, Testing Library) | 74 tests | Money parsing and formatting, validation mirroring the backend, session handling, the Profile page, the staff dialog, dialogs keeping focus |
| Browser (Playwright) | every test in six setups: desktop, tablet and phone in Chromium, then Firefox, WebKit and an iPhone screen | Whole journeys, including the password change and adding an auditor |
| Security check (`security-check.mjs`) | 21 black-box attacks (23 with the website on its own origin) | The same attacks a tester would send, against a running server |
| Postman collection | folders 1 to 7 | Every endpoint, by hand or with Run collection |

The tests were themselves tested: on Day 3 three bugs were planted on purpose
and each was caught (PROMPTS.md, 3.4).

## 10. Trade-offs and limits

* **Fixed windows**, not sliding ones: simple and atomic in one script. The
  cost is that up to twice the limit can pass around a window boundary.
* **Balances stay updatable** by the app's role, because the app must update
  them. A balance edited directly is caught by the integrity report rather
  than prevented. Ledger rows themselves cannot be edited.
* **Redis outages** make each instance count on its own until Redis returns.
* **No multi-factor authentication or email password reset:** not in the
  problem statement. Encryption at rest is left to the managed database.
* **Free hosting tiers** sleep when idle, and Render's free Key Value keeps no
  data on disk. That is acceptable for counters that expire anyway.
