# API reference

Base path `/api`. JSON in and out. Authenticated endpoints need
`Authorization: Bearer <accessToken>` from `POST /api/auth/login`.
A ready-made Postman collection (41 requests, 88 checks) is in
[`important/testing/Athena.postman_collection.json`](testing/Athena.postman_collection.json).

## Errors

Every error is an [RFC 9457](https://www.rfc-editor.org/rfc/rfc9457) problem
detail with a stable `code`:

```json
{
  "type": "about:blank",
  "title": "Unprocessable Content",
  "status": 422,
  "detail": "Insufficient funds for this transaction",
  "code": "INSUFFICIENT_FUNDS",
  "transactionId": 19,
  "reference": "TXN0000000019"
}
```

| Status | Typical codes |
| --- | --- |
| 400 | `VALIDATION_FAILED` (with an `errors` map per field), `MALFORMED_REQUEST`, `INVALID_PARAMETER`, `IDEMPOTENCY_KEY_REQUIRED`, `INVALID_IDEMPOTENCY_KEY`, `SAME_ACCOUNT_TRANSFER`, `INVALID_AMOUNT`, `INVALID_SORT`, `INVALID_PAGE`, `INVALID_PAGE_SIZE`, `INVALID_SEARCH`, `INVALID_DATE_RANGE`, `STATEMENT_RANGE_TOO_LONG`, `PASSWORD_TOO_LONG`, `CURRENT_PASSWORD_INCORRECT`, `PASSWORD_UNCHANGED`, `PASSWORD_TOO_WEAK` |
| 401 | `UNAUTHORIZED`, `INVALID_CREDENTIALS`, `TOKEN_EXPIRED`, `TOKEN_INVALID`, `TOKEN_REVOKED`, `ACCOUNT_DISABLED` |
| 403 | `FORBIDDEN` (also written to the audit log) |
| 404 | `ACCOUNT_NOT_FOUND`, `TRANSACTION_NOT_FOUND`, `USER_NOT_FOUND`, `NOT_FOUND` |
| 409 | `EMAIL_ALREADY_REGISTERED`, `DATA_CONFLICT` |
| 413 | `PAYLOAD_TOO_LARGE` (bodies over 16 KB) |
| 422 | `INSUFFICIENT_FUNDS`, `ACCOUNT_NOT_ACTIVE`, `BALANCE_LIMIT_EXCEEDED`, `IDEMPOTENCY_KEY_REUSED`, `CANNOT_CHANGE_OWN_STATUS`, `ACCOUNT_CLOSED` |
| 429 | `LOGIN_THROTTLED`, `RATE_LIMITED`, `REGISTRATION_RATE_LIMITED` (with a `Retry-After` header) |
| 500 | `INTERNAL_ERROR` with an `errorId` that matches the server log; no internals |

## Public

| Method and path | Body | Answer |
| --- | --- | --- |
| `GET /api/health` | – | `200 {"status":"UP","database":"UP","redis":"UP"}`; `503` with `"status":"DOWN"` when the database is down |
| `POST /api/auth/register` | `{"fullName","email","password"}` | `201 {"userId","fullName","email","accountNumber","message"}` |
| `POST /api/auth/login` | `{"email","password"}` | `200 {"accessToken","tokenType":"Bearer","expiresIn":900,"email","roles":["USER"]}` |

Registration rules: name of letters, spaces and `.'-` (up to 100); a valid
email (up to 150); a password of 8-72 characters, at most 72 bytes, with a
letter and a digit. Every new user gets the USER role and an account
number `AT` + 12 letters or digits. (Accounts opened before the rename to
Athena keep their `LL` numbers; they work the same.)

The health check is for the hosting platform and says only `UP` or `DOWN`
per dependency, never versions or addresses. `redis` is `UP`, `DOWN` or
`NOT_CONFIGURED` (no `REDIS_URL`, a developer's PC). Redis being down keeps
the answer at 200: rate limits then count in each server's memory, and only
the database decides whether the app can serve.

Staff never come from the sign-up form. The first administrator is created
by `scripts/bootstrap-admin` (or the `BOOTSTRAP_ADMIN_*` settings), only
while no administrator exists; after that, administrators add staff with
`POST /api/admin/staff` below.

## Any signed-in user

| Method and path | Answer |
| --- | --- |
| `GET /api/users/me` | `{"id","fullName","email","roles":["USER"],"accountNumber"}` (`accountNumber` is null for staff) |
| `POST /api/auth/logout` | `204`; the token is revoked at once (`TOKEN_REVOKED` afterwards) |
| `POST /api/users/me/password` with `{"currentPassword","newPassword"}` | `204`. Every token issued before the change, this one included, is refused from then on (`TOKEN_REVOKED`), so every session ends and the user signs in again. |

Password change rules: the new password follows the registration rules
(staff: at least 12 characters), and must differ from the current one
(`PASSWORD_UNCHANGED`). A wrong current password is `400
CURRENT_PASSWORD_INCORRECT`, written to the audit log, and counts as a failed
sign-in: after five, the change waits like a login does (`429
LOGIN_THROTTLED`), so a stolen token cannot be used to guess the password.

## Customer (role USER)

### Money

Every money request needs an **`Idempotency-Key`** header (1-100 visible
ASCII characters, for example a UUID). Repeating a request with the same key
returns the first result with `Idempotent-Replayed: true` and moves no money
again. The same key with a different body gets 422 `IDEMPOTENCY_KEY_REUSED`.

| Method and path | Body | Answer |
| --- | --- | --- |
| `POST /api/transactions/deposit` | `{"amount": 1000.00}` | `201` transaction |
| `POST /api/transactions/withdraw` | `{"amount": 250.50}` | `201` transaction |
| `POST /api/transactions/transfer` | `{"toAccountNumber": "AT0123456789AB", "amount": 300.00}` | `201` transaction |

Amounts: positive, at most 2 decimals and 17 integer digits. Transaction
response:

```json
{
  "transactionId": 3, "reference": "TXN0000000003", "type": "TRANSFER",
  "status": "COMPLETED", "amount": 300.00,
  "fromAccountNumber": "AT9A3DAC3E3274", "toAccountNumber": "AT0123456789AB",
  "balanceAfter": 449.50,
  "createdAt": "2026-11-08T11:23:37.123456", "completedAt": "2026-11-08T11:23:37.123456"
}
```

A refusal after the accounts are locked (insufficient funds, frozen account,
balance limit) is saved as a FAILED transaction. The 422 body carries its
`transactionId` and `reference`.

### Account, history, statement

| Method and path | Answer |
| --- | --- |
| `GET /api/accounts/me` | `{"accountNumber","balance","status","createdAt"}` |
| `GET /api/transactions?type=&status=&from=&to=&search=&page=0&size=20&sort=createdAt,desc` | Page of `{"transactionId","reference","type","status","direction":"IN|OUT","amount","counterpartyAccountNumber","createdAt","completedAt","failureCode","failureReason"}` |
| `GET /api/transactions/{id}` | `{"transaction": {...}, "ledgerEntries": [{"entryType","accountNumber","amount"}]}`; 404 if it is not yours |
| `GET /api/accounts/me/statement?from=2026-11-01&to=2026-11-30` | `{"accountNumber","from","to","openingBalance","totalCredits","totalDebits","closingBalance","lines":[{"date","reference","type","description","credit","debit","balanceAfter"}]}` |

History filters: `type` DEPOSIT/WITHDRAWAL/TRANSFER, `status`
COMPLETED/FAILED, `from`/`to` ISO dates, `search` a reference (`TXN…`) or the
other party's account number. `sort` is `createdAt` or `amount`, optionally
followed by `,asc` or `,desc`. Pages are `{"content","page","size","totalElements","totalPages"}`,
with `size` 1-100. Statements default to the current month and cover at most
366 days.

## Administrator (role ADMIN)

| Method and path | Body | Answer |
| --- | --- | --- |
| `GET /api/admin/users?search=&page=&size=` | – | Page of `{"id","fullName","email","roles","enabled","accountNumber","accountStatus","balance","createdAt"}` |
| `PATCH /api/admin/users/{id}/status` | `{"enabled": false}` | The updated user. Not your own user (422), not the system user (404). |
| `PATCH /api/admin/accounts/{accountNumber}/status` | `{"status": "FROZEN", "reason": "…"}` | The updated user. `ACTIVE` or `FROZEN` only; customer accounts only. |
| `POST /api/admin/staff` | `{"fullName","email","role":"AUDITOR","initialPassword"}` | `201` the new user. `role` is `ADMIN` or `AUDITOR`; the initial password needs 12-72 characters with a letter and a digit. Staff have no customer account and cannot move money. `409 EMAIL_ALREADY_REGISTERED` if the email is taken. |

All changes are audited (`USER_DISABLED`, `USER_ENABLED`, `ACCOUNT_FROZEN`,
`ACCOUNT_UNFROZEN`, `STAFF_CREATED`) with before and after values.

## Administrator or auditor (roles ADMIN, AUDITOR): read-only

| Method and path | Answer |
| --- | --- |
| `GET /api/oversight/summary` | Customers, frozen accounts, disabled users, completed and failed counts, volumes, customer money, security events in the last 24 h |
| `GET /api/oversight/integrity` | Full ledger reconciliation (see ARCHITECTURE.md, section 3), `consistent: true/false` |
| `GET /api/oversight/transactions?type=&status=&from=&to=&account=&search=&page=&size=&sort=` | Every customer's transactions, with who initiated them |
| `GET /api/oversight/audit-logs?action=&outcome=&actor=&from=&to=&page=&size=` | Audit records, newest first, with actor email and JSON details |

Audit actions: `DEPOSIT`, `WITHDRAWAL`, `TRANSFER`, `REGISTER`, `LOGIN`,
`LOGIN_LOCKED`, `LOGOUT`, `PASSWORD_CHANGED`, `ACCESS_DENIED`,
`ADMIN_BOOTSTRAPPED`, `STAFF_CREATED`, `USER_DISABLED`, `USER_ENABLED`,
`ACCOUNT_FROZEN`, `ACCOUNT_UNFROZEN`. Outcomes: `COMPLETED`, `FAILED`,
`REJECTED`, `REPLAYED`, `DENIED`, `ERROR`.

The audit log, the ledger and the transactions are append-only in the
database itself: a trigger refuses every UPDATE and DELETE of their rows,
and the app's own database role has no right to either (see
ARCHITECTURE.md).
