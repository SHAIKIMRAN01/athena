# 5. Engineering quality (10 marks)

> "Readable, sensibly built, errors handled."

## Readable

* One responsibility per class. Controllers only translate HTTP; services
  hold the rules; `LedgerService` is the only code that changes a balance.
* Names say what things do: `lockInIdOrder`, `assertLedgerIsConsistent`,
  `aFrozenAccountCanNeitherSendNorReceive`.
* Comments explain *why*, for example why the idempotency check runs after
  locking, or why the integrity report uses one snapshot.

## Sensibly built

* Layered packages (`controller`, `service`, `repository`, `entity`, `dto`,
  `security`, `exception`, `config`); see important/ARCHITECTURE.md.
* The database enforces the money rules too (constraints and a trigger), not
  just the Java code.
* Configuration only through environment variables; the app refuses to start
  with a missing or weak JWT key.
* The frontend separates API calls (`api/`), session handling (`auth/`), pages
  and small exact helpers (`lib/`).

## Errors handled

* Every error is RFC 9457 JSON with a stable `code` (important/API.md lists them
  all). The frontend shows the server's message and, where relevant, the
  transaction reference.
* No 500s for user mistakes: duplicate emails (409), over-long passwords
  (401/400), bad parameters (400), large bodies (413).
* An unexpected error returns a random `errorId` that matches the server log,
  and says no money moved.
* The frontend handles network failures safely. A money request whose outcome
  is unknown can be retried with the same idempotency key, so it can never pay
  twice.

## Automated checks

`.github/workflows/ci.yml` runs on every push:

* backend tests on PostgreSQL;
* frontend lint, unit tests, build and `npm audit`;
* the full app with the security check and the browser tests;
* a Docker build.

CodeQL and Dependabot watch for vulnerabilities.

## Day 1 clean-up done on Day 4

* `AccountController` had sat in the `dto` folder: moved to `controller`.
* A test-only controller and an empty `Repositry.java` were removed.
* Empty `database/*.sql` placeholder files were removed.
* Debug `System.out` prints in the JWT filter were removed.
