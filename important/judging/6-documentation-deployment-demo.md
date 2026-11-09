# 6. Documentation, deployment and demo (10 marks)

## Documentation

| Document | For |
| --- | --- |
| [README.md](../../README.md) | Overview, quick start, environment variables, links |
| [important/ARCHITECTURE.md](../ARCHITECTURE.md) | System diagram, request flow, double-entry model, ER diagram |
| [important/API.md](../API.md) | Every endpoint, body, answer and error code |
| [important/testing/TESTING.md](../testing/TESTING.md) | All five test levels and how to run them |
| [important/DEPLOYMENT.md](../DEPLOYMENT.md) | Render, Docker, plain Java; production checklist |
| [important/DEMO.md](../DEMO.md) | Demo accounts, the 7-minute script, test scenarios, screenshots |
| [important/GLOSSARY.md](../GLOSSARY.md) | Every technical term, explained, with where it is used |
| [THREATS.md](../../THREATS.md), [PROMPTS.md](../../PROMPTS.md) | Threat model; how AI was used |
| [important/STEP_1_VERCEL_PREREQUISITES.md](../STEP_1_VERCEL_PREREQUISITES.md), [STEP_2_PUSH_TO_GITHUB.md](../STEP_2_PUSH_TO_GITHUB.md), [STEP_3_GITHUB_TO_VERCEL_AND_SECURITY.md](../STEP_3_GITHUB_TO_VERCEL_AND_SECURITY.md) | Going live in three steps: prerequisites, GitHub, then Vercel (website) and Render (API) with a security checklist |
| [important/Athena-Technology-Overview.pptx](../Athena-Technology-Overview.pptx) | Presentation: every technology and its job, with speaker notes |

## Deployment

* `Dockerfile`: a multi-stage build; the final image has only the JRE and the
  jar, and runs as an unprivileged user.
* `frontend/vercel.json`: the website on Vercel, with page routes and
  security headers.
* `render.yaml`: one-click Render blueprint (web service and PostgreSQL), with
  secrets entered in the dashboard.
* `docker-compose.yml` plus `.env.example`: the full stack on any machine.
* `GET /api/health` for the platform's health checks.

**To do yourself:** create the Vercel and Render accounts, follow the three
step guides in important/ (STEP_1 to STEP_3), set the secrets, and put the
live Vercel URL at the top of the README.

## Demo

* Rehearse [important/DEMO.md](../DEMO.md). It covers every feature and ends with
  the security check and the integrity report.
* Screenshots are in `important/screenshots/`, taken by the Playwright suite
  (`SCREENSHOTS=1`).
* Have the Postman collection open as a backup if the network is slow.
