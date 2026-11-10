# How AI was used to build Athena

Athena was built with an AI coding assistant (Claude Code). This file
records how the work was directed, which prompts drove it, and every case
where the AI's output was wrong, who or what caught it, and how it was fixed.
Quotes are copied from the session as written, typos included.

> Sections marked **✍️ (author)** are for the project author to complete in
> their own words. They describe things only the author knows (what they
> checked by hand, what they decided against). Nothing in them has been
> written for them.

## 1. The method: a plan with rules, written before any code

The project started from a four-day plan the author wrote and pasted as the
first prompt. It fixed the scope of each day and, more importantly, the
**rules the AI had to follow**:

> 1. Inspect existing code before creating new files.
> 2. Never duplicate existing classes or database fields.
> 3. Give exact Windows CMD commands and file paths.
> 5. Compile after each implementation milestone.
> 6. Test every API using Postman or Thunder Client.
> 7. Fix errors before moving forward.
> 8. Never compromise financial integrity for speed.
> 10. Never commit secrets, credentials, real customer data or JWT tokens.
>
> "Prioritize verified financial correctness over completing features on schedule."

These rules are why most mistakes below were caught early. The AI could not
call a step done without compiling and running the tests, and it had to read
the existing code before adding to it.

## 2. The prompts that steered the work

| When | Prompt (verbatim) | What it changed |
| --- | --- | --- |
| Day 2 start | The pasted four-day plan (section 1) | Scope, technology and rules for all four days. |
| Day 2 | "so you have complted the tasks scheduled on day 2 and make changes and write code for that ?" | Made the AI state what was done and what was verified, instead of the author assuming. |
| Day 2 | "wait lemme test and run the how to run these give me a walkthrough" | The author ran the app and the tests on their own machine before accepting the day. |
| Day 2 | (pasted their own `mvnw spring-boot:run` and `mvnw test` failures) | Showed that the walkthrough left out two setup steps: the `JWT_SECRET` variable and the `athena_test` database. See 3.1. |
| Day 2 | "i wanna run these things my self and see how its interacting how can i use" | Led to the Postman collection and the psql queries the author ran by hand. |
| Day 3 planning | Asked: "Do rejected attempts such as insufficient funds appear in history as FAILED, or only in the audit log?" Author: "these would be better" | **Design decision by the author**: refused attempts are stored as FAILED transactions with no ledger entries, as well as in the audit log. |
| Day 2 checkpoint | "commit and push and descriptions that testing in postman shows error and list 5 of them and at the end of solved and cleaed all the errors" | Commit messages list the errors found and how they were fixed (see the git history). |
| Day 3 | "Start Day 3" | Idempotency, locking, audit log, history, statements. |
| Day 3 review | "so you saying day 3 is also completed successfully" | The author challenged the claim. The AI had to separate "built and tested" from "not committed, and not yet run by you in Postman". |
| Day 3 checkpoint | "jus give me a command to git add . and git push so i have to do manually and what fies in comand exclude the files should be excluded" | **The author made the commit themselves.** Postman's workspace folders were deliberately left out. |
| Day 4 | "compltete the day 4 coding part in full fledeged … run all the tests and create a workflow … individually create them …" with the judging criteria pasted | Frontend, security hardening, CI, threat model, and these documents. |
| BalanceVault | The organisers' FAQ, pasted: LedgerLine renamed BalanceVault, new mandatory items (account management and onboarding, RBAC, PostgreSQL / Prisma, Redis rate limiting, a first-admin bootstrap script, database roles and deployment health checks), "Please extend your existing LedgerLine implementation" | The AI listed what was already met and what was missing, then asked three questions before writing code. |
| BalanceVault | Three answers: "Keep Athena (Recommended)", "Fallback on my PC (Recommended)", "Full least-privilege (Recommended)" | **Design decisions by the author**: the README states that Athena is the BalanceVault submission; Redis wherever `REDIS_URL` is set, memory with a warning on a PC; the app connects as a restricted role everywhere, and triggers make history append-only. |

## 3. Where the AI was wrong, and how it was caught

Each entry says **who or what caught it**: the author, the automated tests
the plan required, or the AI reviewing its own work. Only real events are
listed.

### 3.1 Caught by the author running the code

| What was wrong | How it showed up | Fix |
| --- | --- | --- |
| The run walkthrough did not make `JWT_SECRET` a clear prerequisite. | The author ran `mvnw spring-boot:run` and pasted the failure: `Could not resolve placeholder 'JWT_SECRET'`. | Explained how to set it. On Day 4 the app also checks the key at startup with a clear message (`JwtService.signingKey`). |
| The test walkthrough did not say to create the test database first. | The author ran `mvnw test` and pasted `FATAL: database "athena_test" does not exist`. | Explained how to create it. The comment in `application-test.properties` gives the exact command. |
| "Day 3 is complete" was overstated. | The author asked "so you saying day 3 is also completed successfully". | The answer was corrected: not committed, not pushed, and not run by the author in Postman yet. |

### 3.2 Caught by the tests the plan required

| What was wrong | Test that failed | Fix |
| --- | --- | --- |
| A wrong password, a missing token or an invalid token got **403** instead of **401** (Day 2). | `TransactionControllerIntegrationTest.requestsWithoutATokenAreRejected`: "Status expected:<401> but was:<403>" | Added a 401 entry point in `SecurityConfig`. |
| Error responses exposed full Java stack traces. The AI had used `server.error.include-stacktrace`, which Spring Boot 4 renamed, so the setting did nothing (Day 2). | API requests during Day 2 testing showed a stack trace in an error body. | `spring.web.error.include-stacktrace=never`, the Boot 4 name. |
| A test spy was stubbed through Spring's transactional proxy, and the leftover stub broke the next test (Day 2). | `aFailureAfterPostingRollsBackTheWholeTransaction`: "No existing transaction found for transaction marked with propagation 'mandatory'" | The stub now targets the real object (`AopTestUtils.getUltimateTargetObject`). |
| Three new Day 4 security tests were wrong. Two re-read the test profile's `${random.value}` key, which is new on every read, so they signed tokens with the wrong key. One used emoji as letters in a password. | `anExpiredTokenIsRefusedAsExpired`, `rolesWrittenIntoATokenAreIgnored`, `weakPasswordsAreRefused` | The tests now read the server's real key, and use a two-byte letter. |
| Two Day 4 browser tests were wrong. One used an ambiguous text locator; one navigated before sign-in finished. | Playwright `customer.spec.js`, `screenshots.spec.js` | Precise locators; wait for the page heading. |
| The first CORS test for the Vercel setup sent its request without an `Origin` header, so it tested nothing. | `CorsIntegrationTest.theWebsiteCanReadAnswersHeadersAndErrors` | The request now carries the website's origin. |
| When the session check on page load failed for a network reason, the app signed the user out without a word and deleted the saved session. Phones that drop the network, or a reload while the check was running, would do the same. | Playwright `customer.spec.js`, "an expired session sends the user to sign in with an explanation", in Safari's engine (WebKit): 2 failures in about 20 runs, the check request cut off before it was answered | The check is tried again after 1, 2 and 4 seconds when the server cannot be reached; only a refused token, or a server that stays unreachable, signs the user out, always with a message. `AuthContext.test.jsx` covers all three cases; afterwards the WebKit test passed 20 times in a row. |
| **BalanceVault.** The Day 4 dialog moved the keyboard focus back to itself on every re-render, because it depended on the `onClose` function that each page passes anew. Typing into a field inside a dialog (the freeze reason, the new staff form) kept only the first character. | The new `Dialog.test.jsx`, written for the staff form: typing "Suspicious activity" left "S" in the field | The dialog keeps the latest `onClose` in a ref and moves the focus only when it opens. Playwright had not caught it because `fill()` sets a whole value at once. |
| **BalanceVault.** The new staff-onboarding browser test timed out in WebKit (Safari and the iPhone screen) in both layouts. Traces showed every action taking 1 to 7 seconds there: pages that earlier tests opened with `browser.newPage()` were never closed, so each WebKit test ran beside more leftover pages. | Playwright `staff.spec.js`, "an administrator adds an auditor…": 60 s timeout in the two WebKit setups, passing in the other four | A `newPage` fixture (`e2e/helpers.js`) closes those pages after each test, and the longest journey gets Playwright's slow-test time limit. It then passed in 29 s (Safari) and 23 s (iPhone). |
| **BalanceVault.** The staff endpoint first asked for `ROLE_AUDITOR`, while every API answer names roles without the prefix (`AUDITOR`). | `StaffOnboardingIntegrationTest`: expected `ROLE_AUDITOR`, got `AUDITOR` in the answer | Requests use `ADMIN` / `AUDITOR` like the rest of the API. |
| **BalanceVault.** The new Redis code used `JedisPooled` and `fromURI`, which Jedis 7 deprecates, and a `close()` that could throw `InterruptedException`. | The compiler: the build treats every warning as an error (`-Xlint:all`, `failOnWarning`) | Jedis 7's `RedisClient` builder; `close()` without checked exceptions. |
| **BalanceVault.** The first-admin script's one-off mode started the app without its web layer, which Spring Security needs, so it failed; and Spring Boot devtools' restarter turned that failure into exit code 0, so the script printed "Done". On Windows PowerShell 5.1 the script had also stopped at a harmless notice Java prints on stderr. | Running the script against a scratch database, three times: a weak password, a valid one, then again with other values | The one-off run starts the whole app on a free port and exits; devtools' restarter is off for it, so the exit code is honest; native stderr no longer stops the script. Afterwards: weak password exit 1 with the reason, valid exit 0 and one administrator, again exit 0 and nothing changed. |

### 3.3 Caught by the AI reviewing its own work

| What was wrong | Why it mattered | Fix |
| --- | --- | --- |
| Day 2 made idempotency keys unique **across all users**. | One user's key could collide with another's. | On Day 3, keys became unique per user, and the upgrade drops the old constraint. |
| A replayed response could show a different `createdAt` than the first one. | Java keeps nanoseconds; PostgreSQL stores microseconds. | Timestamps are truncated to microseconds when created. |
| The Day 1 JWT filter printed token subjects and stack traces with `System.out`. | Sensitive data in logs. | Removed on Day 4; the filter logs nothing about tokens. |
| Duplicate-email registration answered 500 (Day 1). | Looks like a crash to a tester. | 409 `EMAIL_ALREADY_REGISTERED`, including two registrations racing each other. |
| A password longer than 72 bytes made BCrypt throw, so login answered 500. | Anyone could trigger a server error. | Refused at registration, and an ordinary 401 at login. Test: `aVeryLongPasswordAtLoginIsAnOrdinaryFailure`. |
| The first integrity report ran its queries without one snapshot. | A transfer committing between two queries could raise a false "books don't balance" alarm. | REPEATABLE READ, so all queries see one snapshot. |
| A Python edit wrote the registration regexes with single backslashes (`"\p{L}"`), which is invalid Java. | It would not compile. | Spotted when re-reading the file; fixed before compiling. |
| Renaming LedgerLine to Athena in the seed script would, in a database created before the rename, have left the cash account under its old number and added a second system user with no account (the single-settlement index stopped a second cash account). | Inconsistent books for anyone who kept an old database. | `seed.sql` renames the old system user and settlement account in place; `RenameCarryOverIntegrationTest`; the whole backend suite also ran against a real pre-rename database (159 / 159). |
| On tablets and phones, a short page stretched the new top bar to fill the screen: the CSS grid shared the spare height between its rows. | Seen only in Safari-engine screenshots; Chromium's full-page screenshots hid it. | `grid-template-rows: auto 1fr` for the top-bar layout. |
| Account numbers and references broke in the middle inside tables on tablets. | Hard to read, easy to mistype. | Codes stay on one line in tables, and tables become two-column cards on tablets. |
| Checked against a go-live checklist, the list pages ran one or two extra database queries per row (N + 1): the user list took 13 statements for 4 users and 33 for 14. | Pages slow down and the database works harder as data grows. | Hibernate batch fetching, and users loaded with their accounts in one query. `ListQueryCountIntegrationTest` proves 2 rows and 12 rows take the same number of statements. |
| **BalanceVault.** The first version of "a password change ends every session" compared the token's issue time with the change in whole seconds (JWT times have no milliseconds), so a token issued in the same second as the change would have kept working. | A stolen session could survive the change meant to end it, and the test would have been flaky. | Each token carries the exact password stamp it was issued with; any other stamp is refused. The test issues two tokens moments before the change and both are refused. |
| **BalanceVault.** The first Redis failover tried Redis again on every call. | During an outage every request would wait for a network timeout, several times per sign-in. | Redis is retried every 5 seconds; meanwhile limits count in memory, and a lockout from the outage still holds afterwards (`FailoverCounterStoreTest`). |
| **BalanceVault.** The new append-only triggers would have broken an existing test that back-dates ledger rows. | A failing suite, or worse, a trigger weakened to let the test through. | A test helper switches the trigger off for one statement inside one transaction, which only the tables' owner can do. |
| **BalanceVault.** Edits made with Python on Windows turned LF files into CRLF, and the new `bootstrap-admin.sh` would not have run in Git Bash with CRLF. | Git normalises line endings on commit here, but a shell script in the working copy would fail. | The script is LF, and `.gitattributes` keeps `*.sh` LF on every checkout. |

### 3.4 Proving the tests can catch real bugs (deliberate)

Passing tests prove little if they cannot fail. On Day 3 the AI planted three
bugs on purpose, one at a time, ran the suite, and then restored the code
(checked byte for byte):

| Planted bug | Test that caught it |
| --- | --- |
| Skip the idempotency replay check. | `repeatingARequestWithTheSameKeyReturnsTheFirstResult` (database unique-key violation) |
| Check the key **before** taking the row locks. | `simultaneousDuplicatesMoveMoneyOnlyOnce` (race between duplicates) |
| Show other people's refused transfers to the receiver. | `receiversSeeIncomingTransfersButNotOtherPeoplesFailedAttempts` (Bob saw 2 items instead of 1) |

## 4. Where the AI pushed back on the author

* Asked to "switch our website to vercel with all these features working",
  the AI checked Vercel's current documentation instead of assuming. Java is
  not an official Vercel runtime, and the Container runtime is in Beta, with
  instances that stop when idle and do not share memory. It therefore put
  the website on Vercel and kept the API on Render, then proved the split
  setup locally (important/STEP_3_GITHUB_TO_VERCEL_AND_SECURITY.md).

* When asked to write that "testing in Postman shows errors", the AI pointed
  out that the Day 2 errors had come from the automated test suite and curl,
  not Postman. The commit message names the real sources, to keep the
  history accurate.
* The AI asked before every commit and push, and confirmed the files to
  leave out (Postman's workspace folders) before giving the commands.
* Asked to change the name to Athena "in all places", the AI gave new
  accounts `AT` numbers but kept existing `LL` numbers working instead of
  renumbering accounts: account numbers are identifiers that customers share
  and that the audit log records.
* The BalanceVault FAQ lists "PostgreSQL / Prisma". Prisma is an ORM for
  Node.js, and Athena's backend is Java, so the AI did not bolt a Node layer
  onto it: Hibernate (JPA) plays Prisma's part, with entities that the app
  checks against the real schema at every start (`ddl-auto=validate`). The
  write-up explains the mapping.
* With full least privilege chosen, the AI pointed out what it does not
  cover: the app still holds the owner's password to upgrade the schema at
  startup. THREATS.md (T36) and DEPLOYMENT.md describe the stricter split
  instead of claiming more than was built.
* There is no Docker on the author's PC, so the AI did not claim the Compose
  file works: CI now starts the whole Compose stack, checks Redis and the
  restricted role, and runs the first-admin script against it.

## 5. Checks the AI ran on its own output

* A secret scan of every changed file before each checkpoint: no JWTs, keys
  or passwords. Only the made-up demo password `Password123!` appears, in
  tests and the Postman collection.
* The schema upgrade tested both on a fresh database and on one created by
  the previous day's code.
* After every major change, a reconciliation of the whole ledger in SQL:
  debits equal credits, every balance matches its entries, and the
  settlement account equals all customer money.

## 6. ✍️ (author) What the AI got wrong, and what I fixed myself

The AI's own list of its mistakes is in section 3. These are the ones I ran
into myself, and the calls I made.

### Caught by running it myself

| What was wrong | How I caught it | What was done |
| --- | --- | --- |
| The run instructions did not make clear that `JWT_SECRET` must be set. | I ran `mvnw spring-boot:run` and got `Could not resolve placeholder 'JWT_SECRET'`. | The app now checks the key at startup and says what is missing; the README explains how to make one. |
| The test instructions did not say to create the test database first. | `mvnw test` failed with `database "athena_test" does not exist`. | The exact command is now in the test settings file and in TESTING.md. |
| The AI said Day 3 was complete. | I asked whether that was really true. | It was not yet committed, pushed or tried in Postman; the answer was corrected. |
| The first dashboard had six usability problems. | I went through the page and wrote them up. | One entry point for moving money, action cards with icons, sentence-case labels, a bigger copy button that announces what it did, centred page headers, one link style. |
| The Trivy vulnerability scan was added to CI without ever being run (there is no Docker on my PC), and it failed on GitHub. | I pushed, read the failed runs and gave the AI the log. | The Java base image had a known high bug in `apt` that Ubuntu had already fixed. The image now installs Ubuntu's security fixes, and the CI actions moved off the deprecated Node 20. The next push was green. |
| Starting the backend failed with `password authentication failed for user "postgres"`, and every new window needed the password and a JWT key typed in again. | I ran it after the rename and pasted the error. | The window had no `DB_PASSWORD`, and the renamed `athena` database did not exist yet. Now `scripts\setup-local.cmd` saves the password and a JWT key in `.env` once and creates the databases; `scripts\start-local.cmd` starts everything. |

### Calls I made

* **Playwright for the browser tests:** after seeing what `npx e2e init` set up, I chose to stay with Playwright.
* **The look:** I asked for calm base colours instead of the bright colours AI tools usually pick. The result is ink, paper and slate blue.
* **Every device and browser:** I asked whether the site works the same on every device and browser. Testing six setups then found a real bug: in Safari's engine, a failed session check signed people out without a word.
* **The name:** I renamed the project from LedgerLine to Athena, everywhere.
* **The go-live checklist:** I checked the project against a 19-item go-live checklist. That is how the list pages were found running one database query per row.
* **A `git add` command:** I wanted no secret to reach GitHub, so I wrote a `git add` command excluding every kind of secret file and asked first. It would also have left out `application.properties` and broken the build, so I did not use it as written.
* **BalanceVault:** I kept the Athena name and state in the README that it is the BalanceVault submission; I chose Redis wherever `REDIS_URL` is set with memory as the fallback on my PC; and I chose full least privilege, so the app runs as a restricted database role everywhere.

### What I checked myself

* I ran the backend and the tests on my own PC; the failures above came from those runs.
* I committed and pushed every checkpoint myself.
* After each push I read the CI results on GitHub, until every job was green (`final_v7`).

## 7. ✍️ (author) What I would do differently

- …
