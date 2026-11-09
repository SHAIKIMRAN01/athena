# Demo guide

## Demo accounts

Start the backend with `DEMO_DATA_ENABLED=true` (see DEPLOYMENT.md). Four
users are created, with passwords taken from environment variables. No
password is stored in the repository.

| Email | Role | Password from | Starts with |
| --- | --- | --- | --- |
| `admin@athena.demo` | ADMIN | `DEMO_ADMIN_PASSWORD` | No account; user management and monitoring |
| `auditor@athena.demo` | AUDITOR | `DEMO_AUDITOR_PASSWORD` | No account; read-only monitoring and audit log |
| `alice@athena.demo` | USER | `DEMO_USER_PASSWORD` | ₹3,929.50 (deposit 5,000, transfer 750 to Bob, withdrawal 320.50) |
| `bob@athena.demo` | USER | `DEMO_USER_PASSWORD` | ₹1,950.00 (deposit 1,200, received 750) |

You can also register new customers on the sign-up page.

## A 7-minute demo script

1. **Sign in as Alice.** The dashboard shows the balance, money in and out
   this month, and recent activity.
2. **Move money → Transfer** ₹125 to Bob's account number. A confirmation
   shows the balance after the transfer; confirm it. The success message
   gives the reference (TXN…).
3. **Try to break it.** Withdraw more than the balance: the page refuses
   before sending. To show the server refusing too, use the Postman request
   "Withdraw more than the balance": you get 422 `INSUFFICIENT_FUNDS` with a
   reference, and the attempt appears in History as *Failed*.
4. **History.** Filter by Transfer, then open a row: the details show both
   ledger entries (debit Alice, credit Bob) of the same amount.
5. **Statement.** Opening balance + money in − money out = closing balance.
   Download the CSV or print it.
6. **Idempotency.** In Postman, folder "3 - Idempotency", send the same
   deposit twice with one key. The second answer is the same transaction,
   with `Idempotent-Replayed: true`, and the balance is unchanged. Then
   change the amount with the same key: 422 `IDEMPOTENCY_KEY_REUSED`.
7. **Sign in as the auditor** (another browser window). The overview shows
   "The books balance", with 7 green checks. The Audit log shows every
   action above, including the refused attempt.
8. **Sign in as the admin → Users.** Freeze Bob's account with a reason.
   Bob's dashboard now says the account is frozen, and money moves fail.
   Unfreeze it. The audit log shows both changes with the admin's email.
9. **Security in one command:**
   `node important/testing/security-check.mjs <url>`. 21 attacks, all refused, and
   the ledger is still consistent at the end.

## Test scenarios

| # | Scenario | Expected result |
| --- | --- | --- |
| 1 | Register with an existing email (any case) | 409 `EMAIL_ALREADY_REGISTERED` |
| 2 | Sign in with a wrong password 5 times | 401 each time, then 429 `LOGIN_THROTTLED` for 15 minutes from that network |
| 3 | Deposit 0, -5 or 10.001 | 400, nothing saved |
| 4 | Withdraw more than the balance | 422 `INSUFFICIENT_FUNDS`, saved as FAILED, balance unchanged |
| 5 | Transfer to yourself / to `AT-SETTLEMENT` / to an unknown account | 400 / 404 / 404 |
| 6 | Same deposit twice with one `Idempotency-Key` | One transaction; the second answer is marked replayed |
| 7 | Ten simultaneous withdrawals of the whole balance | Exactly one succeeds; the balance ends at 0.00 |
| 8 | A customer opens `/admin/users` | The page shows "No access"; the API answers 403 and audits it |
| 9 | Sign out, then reuse the old token | 401 `TOKEN_REVOKED` |
| 10 | Wait 15 minutes without signing out | The app signs out by itself and explains why |
| 11 | Admin freezes an account | The owner cannot deposit, withdraw, send or receive |
| 12 | Edit a balance directly in the database | The auditor's integrity check turns red and names the account |

## Screenshots

| | |
| --- | --- |
| ![Sign in](screenshots/01-sign-in.png) | ![Dashboard](screenshots/02-dashboard.png) |
| ![Move money](screenshots/03-move-money.png) | ![History](screenshots/04-history.png) |
| ![Transaction details](screenshots/05-transaction-details.png) | ![Statement](screenshots/06-statement.png) |
| ![Audit overview](screenshots/07-audit-overview.png) | ![Audit log](screenshots/08-audit-log.png) |
| ![Admin users](screenshots/09-admin-users.png) | |
