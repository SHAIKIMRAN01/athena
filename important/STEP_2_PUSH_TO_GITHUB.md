# Step 2 of 3: put all the files on GitHub

Do [Step 1](STEP_1_VERCEL_PREREQUISITES.md) first. When this step is done,
continue with [Step 3](STEP_3_GITHUB_TO_VERCEL_AND_SECURITY.md).

Your repository is **https://github.com/SHAIKIMRAN01/athena** and your
computer is already connected to it. Uploading means three things:
**stage** the files, **commit** them with a message, and **push** them.

## What goes to GitHub, and what never does

| Uploaded | Never uploaded (listed in `.gitignore`) |
| --- | --- |
| `backend/` (code, tests, `pom.xml`, Maven wrapper) | `node_modules/`: downloaded libraries, rebuilt by `npm ci` |
| `frontend/` (code, tests, `package.json`, `package-lock.json`, `vercel.json`) | `target/` and `dist/`: build output |
| `important/` (guides, deck, screenshots, testing files) | `.env`, `.env.local`: real secrets |
| `.github/` (CI, CodeQL, Dependabot) | `postman/`, `.postman/`: your Postman app's workspace |
| `README.md`, `THREATS.md`, `PROMPTS.md`, `Dockerfile`, `docker-compose.yml`, `render.yaml`, `.env.example` | `playwright-report/`, `test-results/`: test output |

## A. Open the project and check the connection

```bat
cd /d E:\athena
git remote -v
git fetch origin
git status
```
- [ ] `git remote -v` shows `https://github.com/SHAIKIMRAN01/athena.git`.
- [ ] `git status` says `Your branch is up to date with 'origin/main'`.

If it says your branch is **behind**, someone pushed from elsewhere. Run
`git stash`, then `git pull`, then `git stash pop` before you continue.

## B. Look before you upload: no secrets

```bat
git status
```
Read the list. It must **not** contain:
- [ ] a `.env` file (`.env.example` files are fine: they hold names only);
- [ ] a file with real passwords, a JWT (text starting with `eyJ`), or a key;
- [ ] `node_modules`, `target`, `dist` or `postman` folders.

Expected in the list (all intended):
* **new**: `important/`, the Day 4 code, tests, `.github/`, `Dockerfile` and more;
* **modified**: changed code and settings;
* **renamed**: the project is now Athena, so `backend/ledgerline/…` became
  `backend/athena/…` (Java package `com.athena`), and `docs/postman/…` became
  `important/testing/…`. Git may first list these as deleted plus new files;
  after `git add .` it shows them as renamed;
* **deleted**: `database/*.sql` (empty placeholders), `frontend/src/App.css`
  and the Vite starter pictures, `Repositry.java` (empty), the test-only
  `SecurityTestController.java`, and the old place of `AccountController.java`
  (it moved to `controller/`).

## C. Stage everything

```bat
git add .
git status
```
- [ ] Every change now shows in green under "Changes to be committed".

Lines like `LF will be replaced by CRLF` are only warnings about line endings
on Windows. They are harmless.

**One more secret check**, on exactly what will be uploaded:
```bat
git diff --cached --name-only | findstr /I /R "\.env$ \.pem$ \.key$ id_rsa"
```
- [ ] It prints **nothing**. If it prints a file, remove it from the
  upload with `git restore --staged <file>` and add it to `.gitignore`.

## D. Commit with the prepared message

```bat
git commit -F .git\ATHENA_DAY4_COMMIT_MSG
git log --oneline -1
```
- [ ] The last line shows `feat: complete frontend integration and testing`.

The message lists what Day 4 added, the errors found in testing, and how
each was fixed.

## E. Push to GitHub

```bat
git push origin main
```
- [ ] The output ends with `main -> main`.

**Signing in:** the first time, Git Credential Manager opens a browser window.
Sign in to GitHub there (with your two-factor code). Never type your GitHub
password into CMD: GitHub refuses passwords for `git push`.

## F. Check the result on GitHub

1. Open https://github.com/SHAIKIMRAN01/athena.
   - [ ] The latest commit is `feat: complete frontend integration and testing`.
   - [ ] You can see `important/`, `backend/`, `frontend/`, `README.md`, `THREATS.md`, `PROMPTS.md`.
   - [ ] The README shows the dashboard screenshot.
2. Open the **Actions** tab. A run called **CI** starts by itself. It takes
   about 10 to 15 minutes.
   - [ ] All four jobs end green: backend tests, frontend, end-to-end and
     security check, Docker image.
   - [ ] On a public repository, **CodeQL** runs as well.

This is the first time the CI runs on GitHub. If a job fails, open it, copy
the red error lines, and fix them (or send them to me) before Step 3. Vercel
and Render would deploy the same code.

## G. Protect the repository (recommended)

In the repository on GitHub, **Settings**:
- [ ] **Code security** (called "Code security and analysis" on older
  screens): check that **Dependabot alerts**, **Secret scanning** and **Push
  protection** are on. These are free on public repositories. Push protection
  blocks a push that contains a known kind of secret.
- [ ] **Rules → Rulesets → New branch ruleset** (older screens: **Branches →
  Add rule**): target `main`, tick **Restrict deletions** and **Block force
  pushes**. This stops history being rewritten or deleted by accident.
- [ ] **Collaborators**: only people who should be able to push.
- [ ] Your GitHub account has **two-factor authentication** on (Step 1).

## If something goes wrong

| Message | What it means | What to do |
| --- | --- | --- |
| `! [rejected] main -> main (fetch first)` | GitHub has commits you don't have | `git pull --rebase`, then `git push origin main` |
| `Authentication failed` or `Permission denied` | Wrong or expired sign-in | Run `git push origin main` again and sign in to the browser window as `SHAIKIMRAN01` |
| `nothing to commit, working tree clean` | Everything is already committed | Go straight to step E |
| `LF will be replaced by CRLF` | Line-ending warning | Ignore it |
| `GH013: Repository rule violations … secret` | Push protection found a secret | Remove the secret from the file, `git commit --amend`, push again; then **change** that secret, because it was exposed |
| `file exceeds GitHub's file size limit` | A file over 100 MB | Not expected in this project. Remove the file and add it to `.gitignore`. |

## Every change from now on

```bat
cd /d E:\athena
git add .
git commit -m "describe what you changed"
git push origin main
```

After Step 3, every push to `main` also redeploys the website on Vercel and
the API on Render.

Continue with [Step 3: from GitHub to Vercel, safely](STEP_3_GITHUB_TO_VERCEL_AND_SECURITY.md).
