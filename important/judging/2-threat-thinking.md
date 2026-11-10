# 2. Threat thinking (15 marks)

> "Your THREATS.md against ours, and whether your tests actually test it."

Judges compare our threat list with theirs, then check that each threat is
tested, not just written down.

## What THREATS.md contains

* Assets, attackers and trust boundaries: what we protect, from whom, and
  where trust stops.
* **41 threats** (T35 to T41 added for BalanceVault: rewriting history, the
  app's database login, guessing across instances, a Redis outage, the
  first-admin bootstrap, staff onboarding, a password change with a stolen
  session), grouped by authentication, authorization, money, input and
  browser, and availability and accountability. Each is tagged with STRIDE.
* For every threat: the attack, the defence (with file names), **the test
  that proves it**, and the **residual risk**.
* An honest list of known limits (section 6).

## Why it holds up when compared

* **Every common category is covered.** Spoofing (tokens, passwords),
  tampering (amounts, replays, races, the database), repudiation (audit
  log), information disclosure (other users' data, error messages,
  enumeration), denial of service (floods, large bodies), elevation of
  privilege (roles, mass assignment).
* **It includes threats specific to money**, which generic lists miss:
  double spending through races, duplicate payments, a key reused for a
  bigger amount, half-finished transfers, an unbalanced ledger, direct
  database tampering, frozen accounts.
* **Every threat is traceable.** Ids T1–T19 appear in the Javadoc of the
  security test classes, and each row also names a check in
  `important/testing/security-check.mjs`.

## Show that the tests test it

1. Open `THREATS.md`, pick any row, for example T21 (double spending).
2. Open the named test, `concurrentWithdrawalsCannotOverdrawAnAccount`, and
   run it.
3. Then show that the tests are not decorative. PROMPTS.md, section 3.4: three
   bugs were planted on purpose and the tests caught each one.

## If a judge names a threat we missed

Add a row to THREATS.md and write the test first. The tests show the pattern:
attack through MockMvc, assert the status and the database state, and call
`assertLedgerIsConsistent()`.
