# Architecture

## 1. System overview

```mermaid
flowchart LR
    subgraph Browser
        UI["React 19 + Vite<br/>React Router, Axios<br/>token in sessionStorage"]
    end

    subgraph Host["Hosting platform (Render / Docker)"]
        Proxy["TLS proxy<br/>HTTPS only"]
        subgraph App["Spring Boot 4 (Java 21), one jar"]
            Static["Static files<br/>built React app"]
            Filters["Filters<br/>rate limit, body size,<br/>JWT authentication"]
            Rules["Spring Security<br/>URL rules + @PreAuthorize"]
            Controllers["REST controllers"]
            Services["Services<br/>TransactionProcessor,<br/>LedgerService, AuditService"]
        end
    end

    DB[("PostgreSQL<br/>constraints, ledger trigger,<br/>append-only triggers")]
    Redis[("Redis<br/>rate-limit counters")]

    UI -- "HTTPS, JSON,<br/>Authorization: Bearer" --> Proxy
    Proxy --> Static
    Proxy --> Filters --> Rules --> Controllers --> Services -- "as athena_app" --> DB
    Filters -- "count, private network" --> Redis
```

* **One origin.** The React app is built into the jar and served by Spring
  Boot, so the browser needs no CORS, and every page gets the backend's
  security headers (CSP, frame blocking).
* **Stateless API.** No server sessions. Each request carries a short-lived
  JWT. The server loads the user and roles from the database on every
  request, and checks the token id against the logout list.
* **The database guards money too.** Constraints (`balance >= 0`,
  `amount > 0`, valid account pairs) and a deferred trigger (debits = credits)
  hold even if the application has a bug. Ledger entries, transactions and
  audit records are append-only: triggers refuse every UPDATE and DELETE.
* **Least privilege.** The app connects as `athena_app`, which can read,
  add rows and change only balances, statuses and passwords. The tables'
  owner runs `schema.sql` at startup and nothing else (section 6).
* **Shared limits.** Rate limits and sign-in throttling are counted in Redis,
  so every instance enforces the same limits; while Redis is unreachable each
  instance counts in memory (section 7).

## 2. Backend layers

| Layer | Package | Responsibility |
| --- | --- | --- |
| Filters | `security` | `RateLimitFilter`, `RequestSizeLimitFilter`, `JwtAuthenticationFilter`, problem-JSON 401/403 |
| Configuration | `security`, `config` | `SecurityConfig` (rules, headers, CORS), `SpaWebConfig`, `DemoDataInitializer`, `TimeConfig`, `DatabaseRolesProvisioner` (the app's role), `RateLimitStoreConfig` (Redis or memory), `AdminBootstrap` (first administrator) |
| Controllers | `controller` | HTTP only: validate input, take the user from the token, call one service method |
| Services | `service` | Business rules and transactions |
| Repositories | `repository` | Spring Data JPA queries (bound parameters only) |
| Entities | `entity` | JPA mappings of the tables; `LedgerEntry`, `AuditLog`, `RevokedToken` are immutable, and the database enforces it for ledger, transaction and audit rows |
| DTOs | `dto` | Request and response shapes; never expose entities or password hashes |
| Errors | `exception` | `BusinessException` subclasses carry an HTTP status and a stable `code`; `GlobalExceptionHandler` writes RFC 9457 problem details |

### Money request flow (transfer)

```mermaid
sequenceDiagram
    autonumber
    participant B as Browser
    participant F as Filters
    participant C as TransactionController
    participant S as TransactionService (facade)
    participant P as TransactionProcessor (@Transactional)
    participant L as LedgerService
    participant DB as PostgreSQL

    B->>F: POST /api/transactions/transfer<br/>Bearer token, Idempotency-Key, {toAccountNumber, amount}
    F->>F: rate limit, body size, verify JWT, load user (enabled?)
    F->>C: authenticated USER
    C->>S: transfer(userId from token, to, amount, key)
    S->>P: process(...)
    P->>DB: SELECT ... FOR NO KEY UPDATE (both accounts, ascending id)
    P->>DB: earlier transaction with this user + key?
    alt key seen before
        P-->>S: original result (replayed) or 422 if the payload differs
    else business rule broken (funds, frozen, limit)
        P->>DB: INSERT financial_transactions (FAILED) + audit FAILED
        P-->>S: refusal (committed, then thrown: 422 with reference)
    else allowed
        P->>DB: INSERT financial_transactions (COMPLETED)
        P->>L: post(debit sender, credit receiver)
        L->>DB: INSERT 2 ledger_entries, UPDATE 2 balances
        P->>DB: INSERT audit_logs (COMPLETED)
        DB-->>P: COMMIT (trigger checks debits = credits)
    end
    S-->>C: result
    C-->>B: 201 + Idempotent-Replayed: false|true
```

If anything throws inside the processor, the whole database transaction
rolls back: no balance, ledger entry or transaction row is left behind. The
facade then writes a REJECTED or ERROR audit record in a new transaction, so
even failed requests are recorded.

## 3. Double-entry model

The bank's view: customer accounts are liabilities (credit-normal), and the
single `AT-SETTLEMENT` account is the bank's cash (debit-normal).

| Operation | Debit | Credit | Effect |
| --- | --- | --- | --- |
| Deposit 100 | AT-SETTLEMENT | customer | cash +100, customer +100 |
| Withdrawal 40 | customer | AT-SETTLEMENT | customer −40, cash −40 |
| Transfer 25 | sender | receiver | sender −25, receiver +25 |

So at every moment: **settlement balance = sum of all customer balances**,
and **total debits = total credits**. `/api/oversight/integrity` checks both,
plus every balance against its own entries.

## 4. Database (ER diagram)

```mermaid
erDiagram
    roles ||--o{ user_roles : "granted in"
    app_users ||--o{ user_roles : has
    app_users ||--|| accounts : owns
    app_users ||--o{ financial_transactions : "created_by"
    accounts ||--o{ financial_transactions : "from_account_id"
    accounts ||--o{ financial_transactions : "to_account_id"
    financial_transactions ||--o{ ledger_entries : "posted as"
    accounts ||--o{ ledger_entries : "affects"
    app_users ||--o{ audit_logs : "actor_id"
    app_users ||--o{ revoked_tokens : "signed out"

    roles {
        bigint id PK
        varchar name UK "ROLE_USER, ROLE_ADMIN, ROLE_AUDITOR"
    }
    app_users {
        bigint id PK
        varchar full_name
        varchar email UK
        varchar password_hash "BCrypt"
        boolean enabled
        timestamp created_at
        timestamptz password_changed_at "older tokens are refused"
    }
    user_roles {
        bigint user_id PK, FK
        bigint role_id PK, FK
    }
    accounts {
        bigint id PK
        bigint user_id FK, UK
        varchar account_number UK
        numeric balance "NUMERIC(19,2), CHECK >= 0"
        varchar status "ACTIVE, FROZEN, CLOSED"
        varchar account_type "CUSTOMER or the single SETTLEMENT"
        timestamp created_at
    }
    financial_transactions {
        bigint id PK
        varchar transaction_type "DEPOSIT, WITHDRAWAL, TRANSFER"
        varchar status "COMPLETED, FAILED"
        bigint from_account_id FK
        bigint to_account_id FK
        numeric amount "CHECK > 0"
        varchar idempotency_key "unique per created_by"
        varchar request_hash "SHA-256 of the request"
        bigint created_by FK
        timestamp created_at
        timestamp completed_at
        varchar failure_code
        text failure_reason
    }
    ledger_entries {
        bigint id PK
        bigint transaction_id FK
        bigint account_id FK
        varchar entry_type "DEBIT or CREDIT"
        numeric amount "CHECK > 0"
        timestamp created_at
    }
    audit_logs {
        bigint id PK
        bigint actor_id FK
        varchar action
        varchar entity_type
        bigint entity_id
        varchar outcome "COMPLETED ... DENIED, ERROR"
        jsonb details
        timestamp created_at
    }
    revoked_tokens {
        varchar jti PK
        bigint user_id FK
        timestamp expires_at
        timestamp revoked_at
    }
```

Database rules that protect money (see `backend/athena/src/main/resources/db/schema.sql`):

* `chk_account_balance`: no negative balance, ever.
* `chk_transaction_accounts`: a deposit has only a receiver, a withdrawal
  only a sender, a transfer two different accounts.
* `uq_accounts_single_settlement`: exactly one settlement account.
* `uq_transactions_user_idempotency_key`: one transaction per user and key.
* `trg_ledger_entries_balanced`: a deferred constraint trigger that refuses
  to commit a transaction whose debits and credits differ.
* `trg_ledger_entries_append_only`, `trg_financial_transactions_append_only`,
  `trg_audit_logs_append_only`: refuse every UPDATE and DELETE of a written
  row, whoever asks (`athena_refuse_change()`). `TRUNCATE`, which only the
  owner may run, still works, for test databases.

## 5. Frontend structure

| Folder | Contents |
| --- | --- |
| `src/api` | `client.js` (Axios: token header, 401 handling, exact decimal parsing, error normalization); `athena.js` (one function per endpoint) |
| `src/auth` | `AuthContext` (sign-in, sign-out, expiry timer, roles), `RequireAuth` (protected routes), `session.js` (sessionStorage) |
| `src/pages` | Sign-in and registration, dashboard, move money, history with details, statement, overview, monitoring, audit log, users |
| `src/components` | Layout and navigation by role, dialogs, badges, alerts, pagination |
| `src/lib` | Exact money formatting and parsing, idempotency keys, CSV export, validation |
| `e2e` | Playwright browser tests |

Money is never a JavaScript float in the frontend. Amounts typed by the user
are checked as text and sent exactly as typed. Amounts from the server are
parsed as exact text (`parseJsonExact`) and formatted with `BigInt`.

## 6. Database roles (least privilege)

| Role | Used for | Rights |
| --- | --- | --- |
| Owner (`DB_OWNER_USERNAME`: `postgres` on a PC, `athena` on Render and in Compose) | Startup only: `schema.sql`, `seed.sql`, creating the app's role | Owns every table; must be allowed to create roles |
| `athena_writer` (group, cannot sign in) | Holds the app's grants, set by `schema.sql` on every start | SELECT on every table; INSERT on every table but `roles`; UPDATE on `app_users` and `accounts` only; DELETE on `revoked_tokens` only; sequences |
| `athena_app` (`DB_USERNAME`) | Everything the app does at runtime | Member of `athena_writer`, nothing else: not a superuser, cannot create databases or roles, owns nothing |

`DatabaseRolesProvisioner` runs before anything else touches the database.
When the owner and the app role differ, it connects as the owner, creates
`athena_writer` if missing, creates `athena_app` with `DB_PASSWORD` (or
resets its password), and refuses to continue if that role is a superuser.
Spring then runs `schema.sql` as the owner (`spring.sql.init.username`) and
connects the app as `athena_app`. Without `DB_OWNER_*`, one user does
everything, as before.

What the app's role cannot do, each checked by `DatabaseRolesIntegrationTest`
with SQLSTATE `42501`: UPDATE or DELETE ledger entries, transactions or audit
records; TRUNCATE; DELETE accounts or users; change roles; ALTER, DROP or
CREATE tables; switch triggers off; create roles.

## 7. Rate limits and Redis

```mermaid
flowchart LR
    L["FixedWindowRateLimiter<br/>api, register, login x3"] --> F["FailoverCounterStore"]
    F -- "Redis reachable" --> R["RedisCounterStore<br/>atomic Lua: INCR + PEXPIRE<br/>keys: athena:limit:SHA-256"]
    F -- "unreachable: memory,<br/>retry every 5 s" --> M["InMemoryCounterStore"]
```

* Each limiter counts events per key in fixed windows. The counters live in a
  `CounterStore`: Redis when `REDIS_URL` is set, memory otherwise.
* One increment is one Lua script, so the count and the window's expiry are
  set together and two instances can never both see the same count.
* Keys are SHA-256 hashes of "limiter:subject", so Redis holds no email or
  IP address.
* When Redis fails, `FailoverCounterStore` logs it once, counts in memory and
  tries Redis again every 5 seconds. Reading a count takes the higher of the
  two, so a lockout from an outage still holds afterwards.
* `GET /api/health` reports `redis` as `UP`, `DOWN` or `NOT_CONFIGURED`.
