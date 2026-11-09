# 4. How you used AI (15 marks)

> "Your PROMPTS.md: evidence that you directed the tool and caught it being wrong."

Judges want to see that **you steered the AI** and **verified its work**:
that you were not just accepting output.

## What PROMPTS.md shows

1. **You set the rules before any code**: inspect before writing, compile and
   test after every step, fix errors before moving on, financial correctness
   over speed, no secrets in git.
2. **Your prompts and decisions, verbatim.** For example, choosing that
   refused attempts appear in history as FAILED, and challenging "so you
   saying day 3 is also completed successfully".
3. **Every case where the AI was wrong**, split by who caught it: you
   (running the app yourself), the tests your rules required, or the AI
   reviewing its own work.
4. **Proof that the tests can catch real bugs**: three bugs planted on
   purpose, each caught.

## What only you can add (important)

The ✍️ sections of PROMPTS.md are empty on purpose. Judges will ask about
them, so write them yourself:

* What you ran by hand: Postman, psql, the app in the browser, and what you
  saw.
* Anything that looked wrong to you, and what you asked the AI to change.
* A suggestion you rejected, and why.

Be specific and honest. A short true list is worth more than a long vague one.

## Be ready to explain

Pick any line of code and explain why it is there. Good candidates:
`TransactionProcessor.lockInIdOrder` (deadlocks), the deferred ledger
trigger, `JwtAuthenticationFilter` (revocation and disabled users).
