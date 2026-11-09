# Athena

**A financial transaction simulator: every transaction accurate, every
movement authorized, every record traceable.**

Customers deposit, withdraw and transfer money through a React app. Every
movement is posted to a **double-entry ledger** in PostgreSQL, protected
against duplicate requests, race conditions and tampering, and written to an
**audit log** that auditors and administrators can inspect.

> **Live demo:** _add your Vercel URL here_ · Demo accounts: [important/DEMO.md](important/DEMO.md) ·
> Presentation: [important/Athena-Technology-Overview.pptx](important/Athena-Technology-Overview.pptx)

![Dashboard](important/screenshots/02-dashboard.png)

## Features

| Area | What you get |
| --- | --- |
| Accounts | Registration with BCrypt passwords; an account number per customer; sign-in with short-lived JWTs, server-side logout and automatic sign-out on expiry |
| Money | Deposits, withdrawals and transfers (with confirmation), exact decimals end to end |
| Ledger | Double-entry postings against a settlement (cash) account; the database refuses to commit an unbalanced transaction |
| Safety | Idempotency keys (a retry never pays twice), row locking in a fixed order (no double spending, no deadlocks), atomic rollback |
| History | Search by reference or account; filters by type, status and date; paging, sorting and transaction details with ledger entries |
| Statements | Opening and closing balances, money in and out, running balance, CSV download, print |
| Roles | USER (own money), AUDITOR (read-only monitoring), ADMIN (users and account freezing, no money movement) |
| Oversight | Every transaction, the audit log, system totals and a live ledger reconciliation |
| Security | Login throttling, rate limits, request size limits, strict CSP and security headers, closed CORS, problem-JSON errors without internals |

## Works on every screen and browser

| Where | How it looks and works |
| --- | --- |
| Phones | A top bar with a Menu button, tables shown as cards, confirmations that slide up from the bottom, 16 px text in forms (no zoom on focus), every button at least 44 px tall |
| Tablets | The same top bar; tables as two-column cards |
| Desktops | A sidebar and full tables |
| Browsers | Chrome, Edge, Firefox, Safari (Mac, iPhone, iPad) and Samsung Internet from the last few years: Chrome, Edge and Firefox 100+, Safari and iOS 15+. Form controls are restyled, so they look and size the same in each. |
| Look | Calm ink, paper and slate-blue colours with muted status colours; every text colour meets WCAG AA contrast |
| Tested | Every browser test runs in six setups on each push: desktop, tablet and phone in Chromium, then Firefox, Safari (WebKit) and an iPhone screen |

On a phone: [dashboard](important/screenshots/10-phone-dashboard.png) ·
[menu](important/screenshots/11-phone-menu.png) ·
[history as cards](important/screenshots/12-phone-history.png).

## Tech stack

Java 21 · Spring Boot 4.1 · Spring Security 7 · JJWT · Hibernate 7 ·
PostgreSQL · React 19 · React Router 7 · Axios · Vite 8 · JUnit 6 ·
Vitest · Playwright · Docker · GitHub Actions

## Documentation

Everything to read, present, test and deploy is in one folder,
**[important/](important/README.md)**, which opens with a start-here index.

| Read this | To learn |
| --- | --- |
| [important/ARCHITECTURE.md](important/ARCHITECTURE.md) | System and request-flow diagrams, the double-entry model, the ER diagram |
| [important/API.md](important/API.md) | Every endpoint and error code |
| [THREATS.md](THREATS.md) | The threat model: 34 threats, their defences, and the tests that prove them |
| [PROMPTS.md](PROMPTS.md) | How AI was used, and every time it was wrong |
| [important/testing/](important/testing/TESTING.md) | All testing in one place: every test file, how to run them, the Postman collection, the security check and the latest results |
| [important/STEP_1_VERCEL_PREREQUISITES.md](important/STEP_1_VERCEL_PREREQUISITES.md) | Going live, step 1: the software, accounts, passing tests, secrets and names to have ready |
| [important/STEP_2_PUSH_TO_GITHUB.md](important/STEP_2_PUSH_TO_GITHUB.md) | Step 2: put all the files on GitHub without secrets, and protect the repository |
| [important/STEP_3_GITHUB_TO_VERCEL_AND_SECURITY.md](important/STEP_3_GITHUB_TO_VERCEL_AND_SECURITY.md) | Step 3: connect GitHub to Vercel (website) and Render (API and database), go live and handle security |
| [important/DEPLOYMENT.md](important/DEPLOYMENT.md) | Render, Docker and plain Java deployment |
| [important/Athena-Technology-Overview.pptx](important/Athena-Technology-Overview.pptx) | A 16-slide deck: each technology and its job, with speaker notes |
| [important/DEMO.md](important/DEMO.md) | Demo accounts, a 7-minute demo script, test scenarios, screenshots |
| [important/GLOSSARY.md](important/GLOSSARY.md) | Every technical term used in the project, explained |
| [important/judging/](important/judging/README.md) | One guide per judging criterion |

## Quick start (Windows CMD)

Prerequisites: JDK 21+, Node.js 22+, PostgreSQL 16+.

**1. Create the databases** (the second one is only for the tests):

```bat
"C:\Program Files\PostgreSQL\18\bin\psql.exe" -U postgres -c "CREATE DATABASE athena"
"C:\Program Files\PostgreSQL\18\bin\psql.exe" -U postgres -c "CREATE DATABASE athena_test"
```

**2. Start the backend** on http://localhost:8080. The tables are created on
the first start.

```bat
cd /d E:\athena\backend\athena
set DB_PASSWORD=your-postgres-password
for /f %i in ('powershell -Command "$b=New-Object byte[] 48; [Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($b); [Convert]::ToBase64String($b)"') do set JWT_SECRET=%i
mvnw spring-boot:run
```

**3. Start the frontend** in a second window, then open http://localhost:5173:

```bat
cd /d E:\athena\frontend
npm install
npm run dev
```

**4. Run the tests:**

```bat
cd /d E:\athena\backend\athena
mvnw test

cd /d E:\athena\frontend
npm test
```

To get the demo users, set `DEMO_DATA_ENABLED=true` and the three
`DEMO_*_PASSWORD` variables before step 2 (see below). For Docker, see
[important/DEPLOYMENT.md](important/DEPLOYMENT.md).

## Environment variables

| Variable | Required | Default | Purpose |
| --- | --- | --- | --- |
| `DB_PASSWORD` | yes | – | PostgreSQL password |
| `JWT_SECRET` | yes | – | Base64 of 32+ random bytes. The app refuses to start without a strong one. |
| `DB_URL` | no | `jdbc:postgresql://${DB_HOST}:${DB_PORT}/${DB_NAME}` | Full JDBC URL (overrides the three below) |
| `DB_HOST` / `DB_PORT` / `DB_NAME` | no | `localhost` / `5432` / `athena` | Database location |
| `DB_USERNAME` | no | `postgres` | Database user |
| `PORT` | no | `8080` | HTTP port |
| `JWT_EXPIRATION_MS` | no | `900000` (15 min) | Access token lifetime |
| `FORWARD_HEADERS_STRATEGY` | no | `none` | `native` behind a TLS proxy (set in the Docker image) |
| `CORS_ALLOWED_ORIGINS` | no | empty (closed) | Comma-separated website addresses allowed to call the API, e.g. the Vercel address (see important/STEP_3_GITHUB_TO_VERCEL_AND_SECURITY.md) |
| `API_RATE_LIMIT_PER_MINUTE` | no | `600` | API requests per client IP per minute |
| `REGISTRATION_LIMIT_PER_HOUR` | no | `50` | Registrations per client IP per hour |
| `LOGIN_MAX_FAILURES_PER_ACCOUNT_AND_IP` | no | `5` | Failed logins before a 15-minute pause |
| `LOGIN_MAX_FAILURES_PER_IP` / `_PER_ACCOUNT` | no | `30` / `50` | Wider login limits |
| `MAX_REQUEST_BYTES` | no | `16384` | Largest accepted request body |
| `DEMO_DATA_ENABLED` | no | `false` | Create the demo users |
| `DEMO_ADMIN_PASSWORD` / `DEMO_AUDITOR_PASSWORD` / `DEMO_USER_PASSWORD` | if demo | – | 12+ characters with a letter and a digit |
| `SHOW_SQL` | no | `false` | Log SQL statements (development only) |
| `TEST_DB_URL` | tests | `jdbc:postgresql://localhost:5432/athena_test` | Test database (its name must end in `_test`) |
| `VITE_API_BASE_URL` | frontend build, if the API is elsewhere | unset (same origin) | The API address built into the website, e.g. `https://athena.onrender.com` (set in Vercel) |

Never commit real values: `.env` is git-ignored, and
[.env.example](.env.example) lists the names.

## Project layout

```
athena/
├─ backend/athena/            Spring Boot API
│  ├─ src/main/java           the code (controller, service, repository, entity, security, ...)
│  ├─ src/main/resources/db   schema.sql (tables, constraints, ledger trigger), seed.sql
│  └─ src/test                159 backend tests
├─ frontend/                  React website
│  ├─ src                     pages, components, API client (unit tests sit next to the code)
│  ├─ e2e                     Playwright browser tests
│  └─ vercel.json             settings for hosting the website on Vercel
├─ important/                 start here: deck, go-live steps 1 to 3, architecture, API, glossary, demo, judging
│  └─ testing/                Postman collection, security check, test guide and results
├─ .github/                   CI, CodeQL, Dependabot
├─ Dockerfile, docker-compose.yml, render.yaml, .env.example
└─ README.md, THREATS.md, PROMPTS.md
```

## Test results (2026-11-08)

Backend **159/159** · Frontend unit **58/58** · Postman **75/75** ·
Browser tests **38/38** in six setups (desktop, tablet and phone; Chrome/Edge,
Firefox, Safari) · Security check **21/21** (**23/23** with the website on a
separate origin, as on Vercel), with the ledger consistent afterwards. Details: [important/testing/TEST_RESULTS.md](important/testing/TEST_RESULTS.md).
