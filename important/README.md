# Important: start here

Everything you need to **understand, present, test and deploy** Athena is
in this folder. The code itself is in `backend/` and `frontend/`.

## 1. Understand the project

| Read this | To learn |
| --- | --- |
| [Athena-Technology-Overview.pptx](Athena-Technology-Overview.pptx) | **Start here.** 16 slides: each technology and its job. Every slide has speaker notes. |
| [ARCHITECTURE.md](ARCHITECTURE.md) | How the parts fit: system diagram, the journey of one transfer, double-entry, database diagram |
| [GLOSSARY.md](GLOSSARY.md) | Every technical term, in plain words, with where it is used |
| [API.md](API.md) | Every API endpoint, its input, its answer and its error codes |
| [../THREATS.md](../THREATS.md) | The threat model: 34 threats, their defences and the tests that prove them |
| [../PROMPTS.md](../PROMPTS.md) | How AI was used, and every time it was wrong |

## 2. Test it

| Open | For |
| --- | --- |
| [testing/TESTING.md](testing/TESTING.md) | All five test levels, **every test file**, how to run them, the history since Day 1 |
| [testing/TEST_RESULTS.md](testing/TEST_RESULTS.md) | The latest full run, suite by suite |
| [testing/Athena.postman_collection.json](testing/Athena.postman_collection.json) | Import into Postman and click Run |
| [testing/security-check.mjs](testing/security-check.mjs) | Attack a running server: `node important\testing\security-check.mjs <api-url>` |
| [testing/SECURITY_CHECK_REPORT.md](testing/SECURITY_CHECK_REPORT.md) | The last security check report |

## 3. Put it online

| Step | Open |
| --- | --- |
| 1. Prerequisites: software, accounts, passing tests, secrets, names | [STEP_1_VERCEL_PREREQUISITES.md](STEP_1_VERCEL_PREREQUISITES.md) |
| 2. Put all the files on GitHub, without secrets | [STEP_2_PUSH_TO_GITHUB.md](STEP_2_PUSH_TO_GITHUB.md) |
| 3. Connect GitHub to Vercel and Render, go live, handle security | [STEP_3_GITHUB_TO_VERCEL_AND_SECURITY.md](STEP_3_GITHUB_TO_VERCEL_AND_SECURITY.md) |
| Other ways (Docker, plain Java) and the production checklist | [DEPLOYMENT.md](DEPLOYMENT.md) |

## 4. Show it

| Open | For |
| --- | --- |
| [DEMO.md](DEMO.md) | Demo accounts, a 7-minute demo script, 12 test scenarios |
| [screenshots/](screenshots/) | 12 screenshots of the app (9 on a desktop, 3 on a phone), taken automatically by the browser tests |
| [judging/README.md](judging/README.md) | One guide per judging criterion |

## Where the code is

```
athena/
├─ backend/athena/       Spring Boot API: src/main (code), src/test (159 tests)
├─ frontend/             React website: src (code + unit tests), e2e (browser tests)
├─ important/            this folder
│  └─ testing/           Postman collection, security check, test guide and results
├─ .github/              CI, CodeQL, Dependabot
├─ Dockerfile, docker-compose.yml, render.yaml, .env.example
└─ README.md, THREATS.md, PROMPTS.md
```
