# TaxLot — Interview Preparation

Questions an interviewer is likely to ask about **the code in this repo**, with answers grounded in
actual files. Every answer points at something you can open and defend.

Read the rule from CLAUDE.md section 9 first: *interviews will be about your code, not Claude's.*
Open each file referenced below before you rely on the answer.

---

## Phase 0 — Project setup

### Q1. Why did you use `BigDecimal` everywhere instead of `double`?

**Answer.** Because `double` cannot represent most decimal fractions exactly. `0.1 + 0.2` is
`0.30000000000000004` in binary floating point. In this system a cost basis feeds a realised
gain, which feeds a tax figure — an error of a fraction of a cent compounds into a wrong number on
a client's tax return. `BigDecimal` stores an exact unscaled integer plus a scale, so `20000.00`
means exactly twenty thousand.

The rule is enforced project-wide: there is no `double` anywhere in the codebase, and all scaling
goes through one class — [Money.java](common/src/main/java/com/taxlot/common/money/Money.java).

**If they push back with "isn't `BigDecimal` slow?"** — yes, meaningfully slower than primitive
arithmetic, and the trade is deliberate. Our scale ceiling is thousands of accounts times tens of
lots, and the batch target is 1,000 accounts in under 60 seconds, which is nowhere near a
`BigDecimal` bottleneck — the time goes on I/O. Correctness of money is not something you trade for
speed you don't need. If it ever did become the bottleneck, the answer is long cents (integer minor
units), not `double`.

---

### Q2. What is `RoundingMode.HALF_EVEN` and why not `HALF_UP`?

**Answer.** HALF_EVEN — "banker's rounding" — breaks an exact tie toward the *even* digit rather
than always upward. `2.345 → 2.34` (4 is even, stays) but `2.355 → 2.36` (5 is odd, rounds up).

HALF_UP rounds *every* exact half upward, so across many operations the error is one-directional
and the total drifts high. HALF_EVEN sends ties down about as often as up, so they cancel.

This is tested directly, not just asserted:
[MoneyTest.tiesCancelOutInsteadOfAccumulating](common/src/test/java/com/taxlot/common/money/MoneyTest.java)
sums four exact halves and shows HALF_EVEN gives `0.56` where HALF_UP would give `0.58` — a full
cent of drift from four operations. Multiply that by a 1,000-account batch.

**If they push back with "does one cent matter?"** — it matters because it is systematic, not
random. A bias that always goes one way is an accounting discrepancy that grows without bound; one
that cancels stays near zero. It is also the convention financial systems expect, so a reconciliation
against a custodian using HALF_EVEN would show a permanent unexplained difference.

---

### Q3. Why is Spring Boot imported as a BOM instead of used as the parent POM?

**Answer.** The usual scaffold makes `spring-boot-starter-parent` the parent of every module. That
inherits Spring's plugin configuration and resource filtering everywhere — including `engine-core`,
which is required to have no Spring at all.

Instead, [pom.xml](pom.xml) imports `spring-boot-dependencies` as a BOM inside
`<dependencyManagement>`. That gives centralised version management with no inherited build
behaviour, so [engine-core/pom.xml](engine-core/pom.xml) can declare exactly one dependency —
`common` — and nothing pulls Spring onto its classpath by accident.

**If they push back with "isn't that just a style preference?"** — it is the mechanism that makes
the architectural rule real rather than aspirational. A rule enforced by convention gets broken on a
busy afternoon; a rule enforced by the dependency graph does not.

---

### Q4. Why does `engine-core` have no Spring and no I/O?

**Answer.** `engine-core` holds the logic that must be provably correct: HIFO lot selection, the
30-day wash-sale window, drift thresholds, the proposal validator. Every input — lots, prices,
weights, exclusions, blocks, and even "today" — is passed in as an immutable value, and every result
is returned as one. See the constraint written down in
[package-info.java](engine-core/src/main/java/com/taxlot/engine/package-info.java).

Three concrete payoffs:
1. **Speed of testing.** Pure functions run thousands of generated cases in milliseconds — which is
   what makes the 1,000-case-per-invariant jqwik target in CLAUDE.md realistic at all.
2. **Precise failures.** A red test points at a rule, not at wiring, a container or a fixture.
3. **Determinism.** Passing "today" in as an argument means a wash-sale test can sit exactly on the
   30-day boundary. If the code read `LocalDate.now()` internally, that test would be unwritable —
   and the boundary is exactly where the bug would be.

**If they push back with "why not just mock the repository?"** — a mock proves the code calls the
collaborator you expected. It does not prove the tax rule is right. Here there is nothing to mock,
so the test can only be about the rule.

---

### Q5. Why one database with three schemas rather than three databases?

**Answer.** The three services are independently deployable, but the data is one client's
portfolio, and a rebalance has to read tax lots and prices that agree with each other. Separate
schemas — `market_data`, `portfolio`, `rebalance`, created in
[init-db.sql](infra/init-db.sql) — keep ownership explicit and make a boundary violation visible in
review, while leaving a single transaction available if it is ever genuinely needed.

**If they push back with "that's not really microservices — you're sharing a database"** — agreed,
and it is a deliberate trade for this project's scale. Strict per-service databases would mean
eventual consistency between lots and prices, and a rebalance computed on prices that disagree with
the lots it is selling is wrong in a way clients notice. The honest framing: shared-database is a
known coupling, taken knowingly, with schema ownership as the discipline that keeps it manageable.
The stretch goal in CLAUDE.md — moving to SQS/Kafka events — is exactly the path out.

---

### Q6. Why create schemas in `init-db.sql` but tables in Flyway?

**Answer.** `init-db.sql` runs once, only on an empty Postgres data directory, and after that it is
invisible — change it and nothing happens until someone does `docker compose down -v`. That is
acceptable for three `CREATE SCHEMA` statements that will never change. It is not acceptable for
tables, which change constantly and need a reviewable, ordered, versioned history. So every table,
index and constraint comes from a Flyway migration owned by its service.

**If they push back with "why not put the schemas in Flyway too?"** — a fair point, and it would
work. The reason they are separate is ordering: Flyway needs the schema to exist to record its own
history table in it. Bootstrapping that from the container keeps each service's migration chain
starting at a clean, guaranteed-present schema.

---

### Q7. What is the Surefire/Failsafe split for?

**Answer.** [pom.xml](pom.xml) binds `*Test` to Surefire (runs in `mvn test`) and `*IT` to Failsafe
(runs only in `mvn verify`). From Phase 1 onwards the integration tests start real Postgres and
Redis containers via Testcontainers, which takes seconds per suite. Keeping them out of `mvn test`
means the fast inner loop stays fast, while CI's `mvn verify` still runs everything.

Failsafe also differs in an important way: it does not fail the build immediately on a test failure.
It runs `post-integration-test` first, so containers and servers get torn down, then fails at the
`verify` goal. Surefire would leave them running.

---

### Q8. Your Surefire config lists both `*Test.java` and `*Tests.java`. Why bother?

**Answer.** Because the first version listed only `*Test.java`, and that is *narrower than
Surefire's own default*. A class someone later named `AccountTests` would not have run — and
critically, the build would still have gone green and the coverage report would still have looked
healthy. A silently skipped test is worse than a failing one: a failing test tells you something;
a skipped one tells you nothing while looking identical to success.

This was caught in the Phase 0 review pass and is recorded in
[BUILD-LOG.md](docs/BUILD-LOG.md). It is a good example to give if asked *"tell me about a bug you
caught before it shipped"* — it is small, real, and the reasoning is about failure modes rather
than syntax.

---

### Q9. Why Java 21 specifically?

**Answer.** Two reasons, one immediate and one structural.

Immediate: it is an LTS release, which is what a bank actually runs.

Structural: Phase 6 rebalances 1,000 accounts concurrently. Each account's work is dominated by
waiting — REST calls to two other services, database round-trips, a Redis lock. That is exactly the
workload virtual threads are for: a platform-thread pool would need to be sized for the blocking, or
the code rewritten in reactive style. With virtual threads, blocking code stays readable and the
carrier thread is released during the wait. The bound then comes from a semaphore sized to protect
downstream services, not from thread count.

**If they push back with "why not reactive / WebFlux?"** — WebFlux would also work, at the cost of
every piece of business logic becoming a reactive chain, which makes the tax rules harder to read and
much harder to debug. Virtual threads give most of the scalability while the code stays sequential.
For logic this rule-heavy, readability is worth more than the last increment of throughput.

---

### Q10. Walk me through `Money.weightOf`. Why does it throw on a zero total?

**Answer.** [Money.weightOf](common/src/main/java/com/taxlot/common/money/Money.java) computes a
position's share of the portfolio and throws `IllegalArgumentException` when the total is zero.

Returning `0` there would be *wrong but plausible-looking*. A weight against an empty portfolio has
no meaning, and a zero weight would flow straight into the drift calculation, where it reads as
"this position is massively underweight" and produces a BUY. The caller reaching that state has a
bug — an account with no holdings and no cash — and the loud failure surfaces it at the point of
cause rather than as a mysterious trade three steps later.

**General principle to state:** in financial code, prefer a loud failure to a plausible default.
A crash gets investigated; a wrong number gets executed.

---

### Q11. Tell me about a problem you hit setting this up.

Pick whichever fits the conversation — all three are in [BUILD-LOG.md](docs/BUILD-LOG.md):

- **Port 5432 was already bound.** `docker compose up` failed, and `lsof` showed nothing because the
  listener belonged to another user. `netstat -an` confirmed a listener existed; `ps aux` identified
  it as a native PostgreSQL 17 daemon. Rather than stop a server another project might depend on, I
  mapped the container to host port 55432. *Point to make:* `lsof -i` under-reports other users'
  sockets — `netstat` plus `ps` is the reliable pair.
- **A `JAVA_HOME` change that appeared not to work.** `zsh -lc` kept reporting the old JDK. The edit
  was fine — a login-but-non-interactive shell never reads `.zshrc`. *Point to make:* before assuming
  a config change failed, check which file that shell actually reads.
- **Config that silently did nothing.** All three services exposed a `prometheus` actuator endpoint
  whose dependency was not on the classpath. Boot ignored it without warning. Removed until Phase 6
  adds the dependency.

---

## Domain refresher — the worked example

Be able to do this on a whiteboard, from memory, with no notes:

```
Bought:  100 shares AAPL @ $200   → cost basis  = $20,000
Now:     AAPL @ $170              → market value = $17,000
                                    unrealised loss = $3,000

Sell all 100 → the $3,000 loss becomes REALISED
Tax saved ≈ $3,000 × 35% = $1,050

Buy ~$17,000 of MSFT (same sector) → client keeps tech exposure
Block AAPL buys for 30 days       → wash-sale rule
```

The two numbers in that example are already pinned by a test —
[MoneyTest.reproducesTheWorkedExampleCostBasis](common/src/test/java/com/taxlot/common/money/MoneyTest.java)
asserts the $20,000 basis and the $3,000 loss.

**The question behind the question:** *why buy MSFT at all?* Because the client hired the firm for
index exposure, not for cash. Selling AAPL and holding the proceeds would mean missing a market
rally, which can easily cost more than the $1,050 the harvest saved. The substitute keeps the risk
profile intact while the loss is banked.

**And why 30 days?** The IRS wash-sale rule disallows a loss if you buy the same or a
"substantially identical" security within 30 days *either side* of the sale — a 61-day window in
total. Buying a same-sector but different company sidesteps it; buying AAPL back on day 20 does not.

---
