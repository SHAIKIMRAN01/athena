# Judging guide

One page per marking criterion. Each says what judges look at, how
Athena meets it, how to show it in under two minutes, and what is still
up to you.

BalanceVault is judged on the same rubric, with the new deployment, RBAC
and Redis items added to it. [../BALANCEVAULT.md](../BALANCEVAULT.md) maps
every BalanceVault requirement to the code and the test that proves it.

| Marks | Criterion | Guide | Main evidence |
| ---: | --- | --- | --- |
| 35 | Integrity and security | [1-integrity-and-security.md](1-integrity-and-security.md) | `important/testing/security-check.mjs`, backend security tests |
| 15 | Threat thinking | [2-threat-thinking.md](2-threat-thinking.md) | [THREATS.md](../../THREATS.md) |
| 15 | Functionality | [3-functionality.md](3-functionality.md) | The app, Postman collection, E2E tests |
| 15 | How you used AI | [4-ai-usage.md](4-ai-usage.md) | [PROMPTS.md](../../PROMPTS.md) |
| 10 | Engineering quality | [5-engineering-quality.md](5-engineering-quality.md) | Code layout, error handling, CI |
| 10 | Documentation, deployment and demo | [6-documentation-deployment-demo.md](6-documentation-deployment-demo.md) | README, important/, `render.yaml`, Dockerfile |

## Before the judging day

1. Deploy (important/DEPLOYMENT.md) and run
   `node important/testing/security-check.mjs https://your-url`. It should report
   21 passed.
2. Fill in the ✍️ sections of PROMPTS.md in your own words.
3. If the problem statement has its own list of ten requirements, map them in
   [3-functionality.md](3-functionality.md). That page currently follows the
   project plan; the BalanceVault list is mapped in ../BALANCEVAULT.md.
4. Rehearse the 7-minute demo in important/DEMO.md, and record the demo
   video from its shot list.
5. Check `/api/health` on the deployed app: `"database":"UP","redis":"UP"`.
