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

## Phase 1 — Market data service

### Q12. Why does `RandomWalk` take the random shock as a parameter instead of generating it?

**Answer.** Because it makes the maths a pure function.
[RandomWalk.step(currentPrice, shock)](market-data-service/src/main/java/com/taxlot/marketdata/simulation/RandomWalk.java)
owns the formula; the caller owns the `Random`. The consequences are practical:

- Every test is deterministic — `step(100.00, 1.0)` with 1% volatility is *always* 101.0000, so the
  assertions in `RandomWalkTest` are exact values, not ranges or statistical tolerances.
- There is nothing to mock. No `Random` stub, no seeded fixture, no `@Mock` annotation.
- Edge cases become trivially reachable. Testing the price floor means passing `shock = -1000.0`;
  with internal randomness you would have to wait for a 1000-sigma event.

**If they push back with "isn't that just pushing the problem to the caller?"** — yes, and that is
the point. The caller ([PriceSimulator](market-data-service/src/main/java/com/taxlot/marketdata/service/PriceSimulator.java))
is the layer that already deals with the outside world: the database, the clock, configuration. The
randomness belongs with the other impurities, not tangled into the formula. It is the same principle
that keeps `engine-core` free of Spring, applied at method scale.

---

### Q13. Why is the walk multiplicative rather than additive?

**Answer.** `next = current * (1 + drift + volatility * shock)`, not `next = current + shock`.

An additive walk moves every security by the same number of *dollars*. In this universe that means
one shock moves META ($580) and PFE ($26) identically in absolute terms — a rounding error for one
and a 7.7% swing for the other. It can also walk a price straight through zero into negative
territory, which would then corrupt every market-value and weight calculation downstream rather than
failing anywhere visible.

A multiplicative walk moves everything in percentage terms, which is how prices actually behave, and
stays positive for any sane shock. It is pinned by
[RandomWalkTest.aOneSigmaShockMovesEveryPriceByTheSamePercentage](market-data-service/src/test/java/com/taxlot/marketdata/simulation/RandomWalkTest.java):
a 1-sigma shock at 1% volatility takes 100 → 101 and 500 → 505.

There is still a `FLOOR_PRICE` of $0.01 as a backstop, because a large enough negative shock can
drive the growth factor negative.

---

### Q14. Your cache swallows Redis exceptions. Isn't that hiding failures?

**Answer.** It logs them at WARN and then degrades. Every method in
[PriceCache](market-data-service/src/main/java/com/taxlot/marketdata/service/PriceCache.java)
catches `DataAccessException`: reads report a miss, writes are dropped, and the caller falls through
to Postgres.

The justification is specific to what is in this cache. Prices are always recomputable from the
database — nothing in Redis is authoritative. So the question is: when Redis dies, should the price
endpoint return correct data slowly, or return a 500? Returning 500 means a Redis outage takes down
every rebalance in the system for data that was sitting in Postgres the whole time. A cache that
takes the service down when it fails is worse than no cache.

**If they push back with "how would you notice Redis was down, then?"** — the WARN logs, and from
Phase 6 the Micrometer metrics: cache hit rate falling to zero is the signal. That is a monitoring
concern, not a reason to fail user requests. The distinction to state is that **failing soft is only
correct when the cached data is recomputable** — if Redis held the only copy of something, swallowing
the error would be data loss, and the right answer would be the opposite.

---

### Q15. Tell me about a bug you found in your own code.

This is the strongest answer in the project. Use the second one if they want depth.

**The fail-soft cache would have thrown on the exact path it existed to protect.**
`PriceCache.get` returns `Map.of()` when Redis is unreachable. `MarketDataService` then wrote the
database results back into that map — which is immutable. So a Redis outage, the one scenario the
whole design was built to survive, would have thrown `UnsupportedOperationException` and returned a
500. Found in review, before committing. Fixed by copying into a mutable map, and pinned by
[MarketDataServiceTest.aRedisOutageFallsThroughToTheDatabase](market-data-service/src/test/java/com/taxlot/marketdata/service/MarketDataServiceTest.java),
which uses a mock precisely because a healthy container cannot reproduce the case that matters most.

**The simulator was not actually random.** `new Random(seed)` was constructed *per call*, so every
invocation drew the identical sequence of shocks. Calling `simulate(1)` repeatedly applied the same
move to each security every single time — AAPL would step the same direction on every call. A
systematic drift wearing a random walk's clothes. Fixed by mixing the starting date into the seed,
which keeps each run reproducible while making consecutive runs genuinely different.

**And then the regression test for it was wrong too.** The first version compared day-over-day price
ratios at 10 decimal places — and *passed with the bug still present*. Prices are stored at 4 decimal
places, so every step carries about 2e-7 of relative rounding noise, which is enough to make even
identical shocks produce different ratios at that precision. The test was measuring rounding noise
instead of the shock. Rounding the comparison to 5 decimal places puts the threshold well above the
noise floor and far below a real 1.2% move.

**The point to land:** a test that passes for the wrong reason is worse than no test, because it
actively certifies broken behaviour. The only way to trust a regression test is to watch it fail
against the bug it was written for — reintroduce the bug, confirm red, restore the fix, confirm
green. That is recorded in [BUILD-LOG.md](docs/BUILD-LOG.md).

---

### Q16. Your integration tests share containers. Didn't that cause problems?

**Answer.** Yes, twice, and both were worth the trade.

[AbstractIntegrationTest](market-data-service/src/test/java/com/taxlot/marketdata/AbstractIntegrationTest.java)
uses the **singleton container** pattern — Postgres and Redis start once in a static initialiser and
are shared by every IT class. The alternative, JUnit's `@Testcontainers` lifecycle, starts a fresh
pair per test class, costing seconds each time.

The two failures:
1. `PriceCacheIT` ran a simulation before `PriceSimulationIT` started, so the price history was
   further along than that class assumed and its absolute-date assertions failed. Fixed by rewinding
   price history to the seed date in `@BeforeAll` (`PER_CLASS` lifecycle so it can use injected beans).
2. `PriceCacheIT` writes a deliberately fake price into Redis to prove reads go through the cache —
   and did not clean it up. Another class then failed with `expected: 200.0000 but was: 1.2345`.
   Fixed with `@AfterEach` eviction.

**If they push back with "why not just isolate every test?"** — full isolation costs container
startup per class, and the alternative to the rewind would have been dropping the exact-date
assertions. Those assertions are the ones that catch a weekend-skipping regression: "the 30th trading
day after a Friday is six calendar weeks later" is a real property worth pinning. The lesson I would
actually state is narrower: **a test that deliberately corrupts shared state owns cleaning it up**,
and "shared state" includes the cache, not just the database.

---

### Q17. Why did your integration tests fail with `ClassNotFoundException` on your own classes?

**Answer.** Packaging, not code — and it is worth knowing because the error names nothing useful.
The symptom was `TestEngine with ID 'junit-jupiter' failed to discover tests`; no test ran, and the
real cause only appeared in the Failsafe dump file.

`spring-boot-maven-plugin:repackage` **replaces** the module's main jar with an executable fat jar
whose classes live under `BOOT-INF/classes`. Failsafe runs after `package` and resolves the module
from that artifact, so every application class disappeared from the integration-test classpath.

The fix in [pom.xml](pom.xml) is `<classifier>exec</classifier>`: the plain jar stays the main
artifact (38 KB) and the runnable fat jar becomes `-exec.jar` (65 MB). It also matters for module
dependencies — `rebalance-engine` depends on `engine-core`, and a fat jar is not usable as a library.

**Rule of thumb worth quoting:** a `ClassNotFoundException` for your *own* classes during test
discovery is almost always a packaging problem.

---

### Q18. Walk me through what happens on `GET /prices/latest?tickers=AAPL,MSFT`.

**Answer.**
1. [PriceController.latest](market-data-service/src/main/java/com/taxlot/marketdata/web/PriceController.java)
   trims, uppercases and de-duplicates into a `LinkedHashSet` — `?tickers=aapl,AAPL` is one lookup,
   not two, and insertion order is preserved so the response order is predictable.
2. [MarketDataService.findLatestPrices](market-data-service/src/main/java/com/taxlot/marketdata/service/MarketDataService.java)
   asks Redis for all of them in a single `HMGET`.
3. Whatever missed is fetched from Postgres in **one** query, then written back to the cache.
4. Results are reordered to match the caller's list. Unknown tickers are simply absent — not null
   entries — because for a rebalance a missing price is an error and for an ad-hoc query it is not,
   so the caller decides.

The SQL behind step 3 uses a correlated subquery to find *each security's own* latest date, not one
global maximum. The simulator currently advances every security together, so in practice the dates
match — but a CSV load in Phase 7 could easily leave one security a day behind, and a global-max
query would silently return nothing for it.

---

### Q19. Why does every security have a substitute when CLAUDE.md only lists 11 pairs?

**Answer.** Because the named pairs leave 9 of the 30 securities (WFC, UNH, MCD, COP, SLB, EOG, HON,
GE, UPS) with no substitute at all — and a security with no substitute **can never be tax-loss
harvested**, because selling it would drop the client's market exposure with nothing to replace it.

So [V2__seed_security_universe.sql](market-data-service/src/main/resources/db/migration/V2__seed_security_universe.sql)
seeds CLAUDE.md's pairs at rank 1 and every other same-sector peer behind them. That is what the
`rank` column exists for.

Same-sector is the right fallback for a specific legal reason: a different company in the same sector
is **not "substantially identical"** under the wash-sale rule, so the loss is still allowed, while
the client keeps equivalent market exposure. Buying the same security back would be the wash sale.

Two things are enforced in the schema rather than in code
([V1](market-data-service/src/main/resources/db/migration/V1__create_security_price_substitute.sql)):
`CHECK (security_id <> substitute_id)` makes a self-substitute impossible, and
`UNIQUE (security_id, rank)` makes "the best substitute" deterministic — two rank-1 rows would make a
rebalance non-reproducible, and a rebalance that cannot be reproduced cannot be audited.

---

### Q20. Why does the simulator skip weekends?

**Answer.** Markets do not trade at weekends, and it costs four lines. The reason it actually matters
is the wash-sale rule: that window is **30 calendar days**, applied against a **trading-day** price
series. Having a price history that includes Saturdays would give the window a different shape in
tests than in the rule being modelled, and the wash-sale boundary is precisely where a bug would
hide.

It is pinned by a date that was chosen deliberately: the seed anchor `2026-01-02` is a **Friday**, so
the very first simulated day exercises the weekend skip — `simulate(1)` must land on Monday the 5th,
not Saturday the 3rd.

---

### Q21. Anything surprising you ran into?

A short, memorable one: `curl | python3 -m json.tool` displayed `"close": 200.0` where the database
held `200.0000`. The API was fine — checking the raw bytes confirmed it returned `200.0000`. Python's
JSON parser had coerced the number to a float and reformatted it.

The precision survived Postgres, JPA, Jackson and Redis, and was destroyed by a *debugging tool*. It
is an unusually on-the-nose demonstration of why this codebase keeps `BigDecimal` everywhere near
money — and a reminder to check raw output when verifying financial values.

---
