# Deployment

Athena ships as **one Docker image**: the React app is built into the
Spring Boot jar (see the `Dockerfile`). It needs a PostgreSQL database and a
few environment variables. It creates and upgrades its own tables at
startup (`db/schema.sql`, `db/seed.sql`), so there is no migration step.

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
   `render.yaml` and creates the `athena` web service (Docker) and the
   `athena-db` PostgreSQL database. It wires the `DB_*` variables
   automatically.
3. Render then asks for the values marked `sync: false`. These are secrets
   and never go into git:
   * `JWT_SECRET`: Base64 of 32+ random bytes. In Windows CMD:
     ```bat
     powershell -Command "$b=New-Object byte[] 48; [Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($b); [Convert]::ToBase64String($b)"
     ```
   * `DEMO_ADMIN_PASSWORD`, `DEMO_AUDITOR_PASSWORD`, `DEMO_USER_PASSWORD`:
     12+ characters with a letter and a digit each. Share them with the
     judges privately, not in the repository.
4. Wait for the deploy, then open `https://<service>.onrender.com/api/health`.
   It should say `{"status":"UP"}`.
5. Run the security check against the deployed URL:
   ```bat
   node important\testing\security-check.mjs https://<service>.onrender.com
   ```

Notes:

* `FORWARD_HEADERS_STRATEGY=native` is set in the image, so rate limits see
  the real client IP behind Render's proxy, and HSTS is sent over HTTPS.
* The database accepts no connections from the internet (`ipAllowList: []`
  in `render.yaml`). Only the API reaches it, over Render's private network
  in the same region.
* Render's free plan sleeps when idle; the first request after a pause is
  slow.
* `render.yaml` follows Render's blueprint format, but it has not been
  deployed from this machine (no Render account here). Check the service
  settings in the dashboard after the first deploy.

## Option B: any Docker host

```bat
cd /d E:\athena
copy .env.example .env
notepad .env
docker compose up --build
```

Open http://localhost:8080. `docker-compose.yml` starts PostgreSQL and the
app; the database data lives in the `athena-data` volume.

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
not commit `src\main\resources\static`: it is a build output.

## Production checklist

| Item | Why |
| --- | --- |
| HTTPS only (the platform's TLS) | Tokens and passwords must never cross the network in clear text. HSTS is sent automatically over HTTPS. |
| A unique `JWT_SECRET` per environment | A leaked key lets anyone mint tokens. Rotating it signs everyone out. |
| A strong database password, database closed to the internet | The database is the last line of defence. On Render, `ipAllowList: []` lets only the API reach it. |
| `FORWARD_HEADERS_STRATEGY=native` only behind a proxy | Otherwise clients could fake their IP to dodge rate limits. |
| `CORS_ALLOWED_ORIGINS` empty, or only the website's exact address | Empty when the app serves its own website (Option A alone). With the website on Vercel, only its exact address, never `*.vercel.app`. |
| `DEMO_DATA_ENABLED=true` only for demos | Demo users have known roles. |
| Run `important/testing/security-check.mjs` after each deploy | The same attacks a tester would try. |
| Watch the audit log (`/oversight/audit-log`) | Failed sign-ins, `LOGIN_LOCKED` and `ACCESS_DENIED` show attacks in progress. |
