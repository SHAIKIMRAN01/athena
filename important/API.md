# API reference

Base path `/api`. JSON in and out. Authenticated endpoints need
`Authorization: Bearer <accessToken>` from `POST /api/auth/login`.
A ready-made Postman collection (75 checks) is in
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
| 400 | `VALIDATION_FAILED` (with an `errors` map per field), `MALFORMED_REQUEST`, `INVALID_PARAMETER`, `IDEMPOTENCY_KEY_REQUIRED`, `INVALID_IDEMPOTENCY_KEY`, `SAME_ACCOUNT_TRANSFER`, `INVALID_AMOUNT`, `INVALID_SORT`, `INVALID_PAGE`, `INVALID_PAGE_SIZE`, `INVALID_SEARCH`, `INVALID_DATE_RANGE`, `STATEMENT_RANGE_TOO_LONG`, `PASSWORD_TOO_LONG` |
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
| `GET /api/health` | – | `200 {"status":"UP"}` or `503 {"status":"DOWN"}` |
| `POST /api/auth/register` | `{"fullName","email","password"}` | `201 {"userId","fullName","email","accountNumber","message"}` |
| `POST /api/auth/login` | `{"email","password"}` | `200 {"accessToken","tokenType":"Bearer","expiresIn":900,"email","roles":["USER"]}` |

Registration rules: name of letters, spaces and `.'-` (up to 100); a valid
email (up to 150); a password of 8-72 characters, at most 72 bytes, with a
letter and a digit. Every new user gets the USER role and an account
number `AT` + 12 letters or digits. (Accounts opened before the rename to
Athena keep their `LL` numbers; they work the same.)

## Any signed-in user

| Method and path | Answer |
| --- | --- |
| `GET /api/users/me` | `{"id","fullName","email","roles":["USER"],"accountNumber"}` (`accountNumber` is null for staff) |
| `POST /api/auth/logout` | `204`; the token is revoked at once (`TOKEN_REVOKED` afterwards) |

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

All changes are audited (`USER_DISABLED`, `USER_ENABLED`, `ACCOUNT_FROZEN`,
`ACCOUNT_UNFROZEN`) with before and after values.

## Administrator or auditor (roles ADMIN, AUDITOR): read-only

| Method and path | Answer |
| --- | --- |
| `GET /api/oversight/summary` | Customers, frozen accounts, disabled users, completed and failed counts, volumes, customer money, security events in the last 24 h |
| `GET /api/oversight/integrity` | Full ledger reconciliation (see ARCHITECTURE.md, section 3), `consistent: true/false` |
| `GET /api/oversight/transactions?type=&status=&from=&to=&account=&search=&page=&size=&sort=` | Every customer's transactions, with who initiated them |
| `GET /api/oversight/audit-logs?action=&outcome=&actor=&from=&to=&page=&size=` | Audit records, newest first, with actor email and JSON details |

Audit actions: `DEPOSIT`, `WITHDRAWAL`, `TRANSFER`, `REGISTER`, `LOGIN`,
`LOGIN_LOCKED`, `LOGOUT`, `ACCESS_DENIED`, `USER_DISABLED`, `USER_ENABLED`,
`ACCOUNT_FROZEN`, `ACCOUNT_UNFROZEN`. Outcomes: `COMPLETED`, `FAILED`,
`REJECTED`, `REPLAYED`, `DENIED`, `ERROR`.
