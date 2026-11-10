# Deployment

Athena ships as **one Docker image**: the React app is built into the
Spring Boot jar (see the `Dockerfile`). It needs a PostgreSQL database, a
Redis-compatible store for the rate limits, and a few environment variables.
At startup it creates or upgrades its own tables (`db/schema.sql`,
`db/seed.sql`) as the database's owner, creates its own restricted database
role, and then connects as that role, so there is no separate migration step.

| What | Where it comes from |
| --- | --- |
| The tables' owner (`DB_OWNER_USERNAME`, `DB_OWNER_PASSWORD`) | The database's main user: Render's `athena`, Compose's `athena`, `postgres` on a PC. Used at startup only, to run `schema.sql` and to create the app's role. It must be allowed to create roles (CREATEROLE). |
| The app's role (`DB_USERNAME`, `DB_PASSWORD`) | `athena_app` and a long random password. The app creates the role (or resets its password) at startup, as a member of `athena_writer`, which `schema.sql` grants exactly what the app needs: no UPDATE or DELETE of the ledger, the transactions or the audit log, no TRUNCATE, no schema changes. |
| `REDIS_URL` | Render's Key Value service, or Compose's `redis` service. Without it each instance counts limits in its own memory, which is fine for one PC only. |
| The first administrator | `scripts/bootstrap-admin`, or `BOOTSTRAP_ADMIN_EMAIL` and `BOOTSTRAP_ADMIN_PASSWORD` for one start. Later staff are added in the app (Users page, "Add staff member"). |

The health check `GET /api/health` answers
`{"status":"UP","database":"UP","redis":"UP"}`. Only the database decides
the status (503 when it is down); `redis` shows `DOWN` during a Redis outage,
while limits keep working in each instance's memory.

## Recommended for the live demo: website on Vercel, API on Render

Follow the three guides in order:
[STEP_1_VERCEL_PREREQUISITES.md](STEP_1_VERCEL_PREREQUISITES.md),
[STEP_2_PUSH_TO_GITHUB.md](STEP_2_PUSH_TO_GITHUB.md) and
[STEP_3_GITHUB_TO_VERCEL_AND_SECURITY.md](STEP_3_GITHUB_TO_VERCEL_AND_SECURITY.md).
Option A below is the API part of that setup; on its own it also serves the
website from the same address.

## Option A: Render (blueprint in `render.yaml`)

1. Push the repository to GitHub.
2. In Render: **New → Blueprint**, then pick the repository. Render reads
   `render.yaml` and creates:
   * the `athena` web service (Docker),
   * the `athena-db` PostgreSQL database,
   * the `athena-redis` Key Value instance (Redis-compatible, free plan).

   Both stores accept no connections from the internet (`ipAllowList: []`);
   only the API reaches them, over Render's private network in the same
   region. Render wires `DB_HOST`, `DB_PORT`, `DB_NAME`, the owner
   (`DB_OWNER_*`), `REDIS_URL`, and generates the app role's `DB_PASSWORD`.
3. Render then asks for the values marked `sync: false`. These are secrets
   and never go into git:
   * `JWT_SECRET`: Base64 of 32+ random bytes. In Windows CMD:
     ```bat
     powershell -Command "$b=New-Object byte[] 48; [Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($b); [Convert]::ToBase64String($b)"
     ```
   * `BOOTSTRAP_ADMIN_EMAIL` and `BOOTSTRAP_ADMIN_PASSWORD`: your own first
     administrator (12+ characters with a letter and a digit). Leave both
     empty if you only want the demo users.
   * `DEMO_ADMIN_PASSWORD`, `DEMO_AUDITOR_PASSWORD`, `DEMO_USER_PASSWORD`:
     12+ characters with a letter and a digit each. Share them with the
     judges privately, not in the repository.
4. Wait for the deploy, then open `https://<service>.onrender.com/api/health`.
   It should say `{"status":"UP","database":"UP","redis":"UP"}`. The log
   shows `Database roles: the app connects as athena_app` and
   `Rate limits and sign-in throttling are counted in Redis`.
5. If you set the `BOOTSTRAP_ADMIN_*` values, the log says `Created the first
   administrator`. Sign in with them, then **delete both values** in the
   dashboard (Environment). Later starts would ignore them anyway, because an
   administrator exists, but a password does not belong in settings.
6. Run the security check against the deployed URL:
   ```bat
   node important\testing\security-check.mjs https://<service>.onrender.com
   ```

Upgrading a service created from the older blueprint (one database user):
sync the blueprint, then check in **Environment** that `DB_USERNAME` is
`athena_app` and that `DB_PASSWORD` is a new generated value, not the
database's password (if not, click Generate). Render's default database user
can create roles; if a deploy ever stops with `Could not prepare the app's
database role`, the message says what to change.

Notes:

* `FORWARD_HEADERS_STRATEGY=native` is set in the image, so rate limits see
  the real client IP behind Render's proxy, and HSTS is sent over HTTPS.
* Render's free plan sleeps when idle; the first request after a pause is
  slow. The free Key Value instance keeps no data on disk, which is fine:
  it holds only counters that expire on their own.
* `render.yaml` follows Render's blueprint format, but it has not been
  deployed from this machine (no Render account here). Check the service
  settings in the dashboard after the first deploy.

## Option B: any Docker host (Docker Compose)

```bat
cd /d E:\athena
scripts\setup-local.cmd
docker compose up --build
```

(Without the script: `copy .env.example .env`, then fill in
`DB_OWNER_PASSWORD`, `DB_PASSWORD` and `JWT_SECRET` with `notepad .env`.)

Open http://localhost:8080. `docker-compose.yml` starts PostgreSQL (owner
`athena`), Redis and the app, which connects as `athena_app`. Neither store
publishes a port: only the app reaches them. The database data lives in the
`athena-data` volume; Redis keeps nothing on disk.

First administrator, with the stack running:

```bat
sh scripts/bootstrap-admin.sh docker
```

It asks for the email, name and password (hidden), runs the app once in a
one-off mode and exits. CI runs exactly this against a fresh Compose stack
on every push.

## Option C: plain Java

```bat
cd /d E:\athena\frontend
npm ci
npm run build
xcopy /E /I /Y dist ..\backend\athena\src\main\resources\static
cd /d E:\athena\backend\athena
mvnw -DskipTests package
java -jar target\athena-0.0.1-SNAPSHOT.jar
```

Set the environment variables (README, "Environment variables") first. Do
not commit `src\main\resources\static`: it is a build output. The first
administrator: `BOOTSTRAP_ADMIN_EMAIL=… BOOTSTRAP_ADMIN_PASSWORD=… sh
scripts/bootstrap-admin.sh jar` (with `ATHENA_JAR` pointing at the jar), or
`scripts\bootstrap-admin.cmd` on Windows.

## Stricter: the app without the owner's password

By default the app holds the owner's password, to upgrade the schema at
every start. To keep it out of the running app entirely:

1. Deploy step, with the owner's credentials: run the one-off mode once
   (`BOOTSTRAP_ONLY=true`, plus `BOOTSTRAP_ADMIN_*` the first time). It runs
   `schema.sql`, creates the role and exits.
2. The running app: `SPRING_SQL_INIT_MODE=never`, no `DB_OWNER_*` at all,
   and `DB_USERNAME=athena_app` with its password. It then never runs DDL
   and never sees the owner's password.

## Production checklist

| Item | Why |
| --- | --- |
| HTTPS only (the platform's TLS) | Tokens and passwords must never cross the network in clear text. HSTS is sent automatically over HTTPS. |
| A unique `JWT_SECRET` per environment | A leaked key lets anyone mint tokens. Rotating it signs everyone out. |
| The app as `athena_app`, the owner only for the schema | A leaked app password cannot rewrite history, empty tables or change the schema (THREATS.md, T36). |
| Strong database passwords, database and Redis closed to the internet | The database is the last line of defence. On Render, `ipAllowList: []` lets only the API reach either store. |
| `REDIS_URL` set whenever more than one instance may run | Otherwise each instance has its own limits, and a guesser can spread attempts over them. |
| `/api/health` watched by the platform | Render holds traffic back from an instance whose database is down; `redis: DOWN` is a warning to act on. |
| `BOOTSTRAP_ADMIN_*` removed after the first start | A password does not belong in settings once it has done its job. |
| `FORWARD_HEADERS_STRATEGY=native` only behind a proxy | Otherwise clients could fake their IP to dodge rate limits. |
| `CORS_ALLOWED_ORIGINS` empty, or only the website's exact address | Empty when the app serves its own website (Option A alone). With the website on Vercel, only its exact address, never `*.vercel.app`. |
| `DEMO_DATA_ENABLED=true` only for demos | Demo users have known roles. |
| Run `important/testing/security-check.mjs` after each deploy | The same attacks a tester would try. |
| Watch the audit log (`/oversight/audit-log`) | Failed sign-ins, `LOGIN_LOCKED`, `ACCESS_DENIED`, `STAFF_CREATED` and `ADMIN_BOOTSTRAPPED` show attacks and staff changes. |
