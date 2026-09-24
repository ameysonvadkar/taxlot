# TaxLot — Build Log

A running record of how TaxLot was built, phase by phase: what went in, what was decided and why,
what broke, and how it was fixed. Written as we go, not reconstructed afterwards.

Each phase records: **what was built → decisions → problems hit → verification**.

---

## Phase 0 — Setup

**Date:** 23 September 2026
**Tag:** `phase-0`
**Gate (CLAUDE.md):** *`docker compose up` starts both containers; CI is green on an empty build.*

### What was built

| Area | Contents |
|---|---|
| Maven | Parent POM + 7 modules: `common`, `engine-core`, 3 services, `bdd-tests`, `perf-tests` |
| `common` | `Side`, `ReasonCode`, `Term` enums; `Money` utility; 38 tests |
| Services | Three Spring Boot apps (ports 8081/8082/8083) with actuator, springdoc and context-loads tests |
| Infra | `docker-compose.yml` — Postgres 16 + Redis 7 with healthchecks; `init-db.sql` creating 3 schemas |
| CI | `.github/workflows/ci.yml` — JDK 21, `mvn -B verify`, test-report artifacts |

### Decisions

**Spring Boot as a BOM import, not as `<parent>`.**
The common approach is `<parent>spring-boot-starter-parent</parent>`. That would push Spring's plugin
configuration and resource filtering into *every* module, including `engine-core` — which CLAUDE.md
requires to stay free of Spring. Importing `spring-boot-dependencies` as a BOM in
`<dependencyManagement>` gives the same version management with none of the inherited build
behaviour. `engine-core/pom.xml` consequently has exactly one dependency: `common`.

**One database, three schemas.**
`market_data`, `portfolio` and `rebalance` are separate schemas in one `taxlot` database rather than
three databases. The services are independently deployable, but the data is one client's portfolio
and a rebalance has to read lots and prices consistently. Schemas keep ownership explicit while
leaving a single transaction possible if it is ever needed.

**Schemas in `init-db.sql`, tables in Flyway.**
The bootstrap script creates only the three schemas. Every table, index and constraint comes from a
Flyway migration owned by its service, so schema history is versioned and reviewable instead of
buried in a container bootstrap that runs once and is then invisible.

**Surefire/Failsafe split from day one.**
`*Test` runs in `mvn test`; `*IT` runs only in `mvn verify`. Set up now so that when Testcontainers
arrives in Phase 1, the slow container-backed suites do not creep into every `mvn test`.

**`Money` written in Phase 0 rather than Phase 3.**
"CI green on an empty build" is a hollow gate if no test runs. More importantly, every later phase
depends on money rounding being consistent, and rounding is far cheaper to pin down before anything
calls it than after twenty call sites exist.

**JaCoCo added.** CLAUDE.md sets a >80% coverage gate on `engine-core` but names no coverage tool.
JaCoCo is a build plugin, not a runtime dependency, so it does not affect what ships.

### Problems hit

**1. Homebrew could not install JDK 21 — `sudo` had no terminal.**
`brew install --cask temurin@21` downloaded the package, then failed:
`sudo: a terminal is required to read the password`. Cask installs shell out to `/usr/sbin/installer`
against `/Library/`, which needs root.

*Fix:* installed the Temurin 21 tarball into `~/Library/Java/JavaVirtualMachines/` instead. macOS's
`/usr/libexec/java_home` scans the per-user directory as well as the system one, so the JDK is
discovered normally with no root involved. Worth knowing generally: a per-user JDK is a complete
substitute for a system one on macOS.

**2. `~/.zshrc` already pinned JDK 24; `zsh -lc` then appeared to ignore the fix.**
After repointing `JAVA_HOME` to 21, `zsh -lc 'java -version'` still printed 24. The edit was correct —
`zsh -l` starts a *login but non-interactive* shell, which does not read `.zshrc` at all, so the
command inherited the stale `JAVA_HOME` from the parent process. `zsh -ic` confirmed 21.

*Takeaway:* when a shell-config change "doesn't take", check which startup file that shell actually
reads before assuming the edit was wrong. Original config backed up to `~/.zshrc.bak.taxlot`.

**3. Port 5432 was already bound — by a native PostgreSQL 17 daemon.**
`docker compose up` failed with `bind: address already in use`. `lsof` as a normal user showed
nothing, because the listener belonged to another user. `netstat -an` confirmed something was on
5432, and `ps aux` found it: `/Library/PostgreSQL/17/bin/postgres`, running since 2 September.

*Fix:* mapped the container to host port **55432** (`${TAXLOT_PG_PORT:-55432}:5432`) rather than
stopping a server another project may depend on. The container still speaks 5432 internally, so
nothing inside the compose network changes, and Testcontainers is unaffected — it allocates random
host ports regardless.

*Diagnostic note:* `lsof -i` silently under-reports other users' sockets. `netstat -an` plus
`ps aux` is the reliable pair.

**4. CI actions were already deprecated on first run.**
The first green run warned that `checkout@v4`, `setup-java@v4` and `upload-artifact@v4` target
Node.js 20, which GitHub has deprecated and is already force-running on Node 24. Bumped all three to
v5 while the workflow was still a single file. `upload-artifact@v5` still reports the Node 20
warning — that is upstream's to fix, and it does not affect the build.

### Review pass — issues found and fixed before committing

**Surefire would have silently skipped `*Tests.java`.** The include list was narrowed to
`**/*Test.java`, which is *narrower than Surefire's default*. A class later named `AccountTests`
would not have run, the build would still have passed, and the coverage report would still have
looked healthy. Silently-skipped tests are the worst failure mode available, so both suffixes are
now listed explicitly with a comment saying why.

**Actuator config listed an endpoint that did not exist.** All three services exposed
`health,info,prometheus`, but `micrometer-registry-prometheus` is not a dependency until Phase 6, so
Boot exposed 2 endpoints and ignored the third without complaint. Reduced to `health,info` — config
that silently does nothing is worse than config that is absent.

**`Term` had no test.** JaCoCo showed class coverage at 75% (3 of 4). The gap was `Term`, never
referenced by any test. Added `TermTest` pinning the constant names, which are the persisted values
in `realized_gain.term` — renaming one would silently orphan existing rows. Coverage went to 100%.

### Known, accepted for now

- **Mockito/ByteBuddy dynamic agent warning.** JDK 21 warns `A Java agent has been loaded
  dynamically`; a future JDK will disallow it by default. Harmless today. When it needs silencing,
  the fix is `-XX:+EnableDynamicAgentLoading` in Surefire's `argLine` — and it must be written
  `@{argLine} -XX:+EnableDynamicAgentLoading`, because JaCoCo sets `argLine` too and a plain
  override would disable coverage.
- **JDK 24 is still installed** and remains the machine default for anything outside this project's
  shell. `~/.zshrc` selects 21.

### Verification

```
$ java -version
openjdk version "21.0.12.1" 2026-08-18 LTS
OpenJDK Runtime Environment Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS)

$ mvn -B verify
taxlot-parent ...................................... SUCCESS [  0.150 s]
common ............................................. SUCCESS [  0.853 s]
engine-core ........................................ SUCCESS [  0.140 s]
market-data-service ................................ SUCCESS [  2.613 s]
portfolio-service .................................. SUCCESS [  2.143 s]
rebalance-engine ................................... SUCCESS [  2.057 s]
bdd-tests .......................................... SUCCESS [  0.008 s]
perf-tests ......................................... SUCCESS [  0.006 s]
BUILD SUCCESS
Total time:  8.171 s

Tests: 38 passed, 0 failed, 0 skipped
  common                35   (Money 22, ReasonCode 7, Term 1, + nested containers)
  market-data-service    1   (context loads)
  portfolio-service      1   (context loads)
  rebalance-engine       1   (context loads)

$ docker compose -f infra/docker-compose.yml ps
taxlot-postgres   postgres:16-alpine   Up (healthy)   0.0.0.0:55432->5432/tcp
taxlot-redis      redis:7-alpine       Up (healthy)   0.0.0.0:6379->6379/tcp

$ docker exec taxlot-postgres psql -U taxlot -d taxlot -c '\dn'
    Name     |       Owner
-------------+-------------------
 market_data | taxlot
 portfolio   | taxlot
 public      | pg_database_owner
 rebalance   | taxlot

$ docker exec taxlot-redis redis-cli ping
PONG
```

**Coverage — `common` (JaCoCo):**

| Counter | Covered | % |
|---|---|---|
| Instruction | 211/212 | 99.5% |
| Branch | 9/10 | 90.0% |
| Line | 40/40 | 100% |
| Method | 20/20 | 100% |
| Class | 4/4 | 100% |

**Gate: passed.** Both containers healthy, full build green, CI green on GitHub.

---

## Phase 1 — Market data

**Date:** 24 September 2026
**Tag:** `phase-1`
**Gate (CLAUDE.md):** *`POST /prices/simulate?days=30` moves prices; `GET /prices/latest` is served from Redis.*

### What was built

| Area | Contents |
|---|---|
| Schema | `V1` — `security`, `price`, `substitute` with FKs, checks and a composite PK on `price` |
| Seed | `V2` — 30 securities across 6 sectors, 120 substitute rows, 30 opening prices |
| Domain | `Security`, `Price` + `PriceId`, `Substitute` + `SubstituteId`, `LatestPrice` |
| Simulator | `RandomWalk` (pure) + `PriceSimulator` (weekday-only, transactional) |
| Cache | `PriceCache` — one Redis hash, JSON values, fails soft |
| API | `GET /securities`, `GET /securities/{ticker}/substitutes`, `GET /prices/latest`, `POST /prices/simulate` |
| Tests | 22 unit + 29 integration (Testcontainers: real Postgres + real Redis) |

### Decisions

**The random walk is multiplicative, and the shock is a parameter.**
`RandomWalk.step(currentPrice, shock)` takes the random draw as an argument instead of generating
it. That makes the maths a pure function: every price path in a test is reproducible, and the class
needs no mocking. The walk multiplies rather than adds, so a $26 security and a $580 security move
by the same *percentage* — an additive walk would be a rounding error for META and a 7.7% swing for
PFE, and could walk a price through zero.

**Weekday-only price history.** Markets do not trade at weekends. It costs four lines and it keeps
the wash-sale window — which counts *calendar* days against a *trading*-day price series — behaving
in tests the way it will in the rule being modelled.

**The cache fails soft.** Every Redis operation in `PriceCache` catches `DataAccessException`:
reads report a miss, writes are dropped, and the caller falls through to Postgres. Prices are always
recomputable from the database, so there is nothing here worth failing a request over. A cache that
takes the service down when it fails is worse than no cache.

**Prices cached as one Redis hash, not one key per ticker.** A rebalance asks for thirty tickers at
once; `HMGET` answers that in one round trip where thirty `GET`s would take thirty.

**Substitutes go beyond CLAUDE.md's list.** The spec names 11 pairs, which would leave 9 of the 30
securities (WFC, UNH, MCD, COP, SLB, EOG, HON, GE, UPS) with no substitute at all — and a security
with no substitute can never be tax-loss harvested, because selling it would drop the client's
market exposure. The named pairs are seeded at rank 1; every other same-sector peer follows behind.
That is what the `rank` column is for.

**Models deferred to Phase 2.** CLAUDE.md lists "1 model (US Large Cap 30)" under Phase 1, but its
own architecture table assigns models to `portfolio-service`. Ownership won: `model` and
`model_weight` are built in Phase 2 alongside accounts, not here.

### Problems hit

**1. Integration tests died at discovery with `ClassNotFoundException` on the service's own classes.**
The failure was `TestEngine with ID 'junit-jupiter' failed to discover tests` — no test ran, and
nothing named the real cause until the Failsafe dump file was read.

Cause: `spring-boot-maven-plugin:repackage` *replaces* the module's main jar with an executable fat
jar whose classes live under `BOOT-INF/classes`. Failsafe runs after `package` and resolves the
module from that artifact, so every application class vanished from the integration-test classpath.

*Fix:* `<classifier>exec</classifier>` in the parent's `pluginManagement`. The plain jar stays the
main artifact (38 KB) and the runnable fat jar becomes `-exec.jar` (65 MB). This also matters for
inter-module dependencies — `rebalance-engine` depends on `engine-core`, and a fat jar is not usable
as a library.

*Worth remembering:* a `ClassNotFoundException` for your **own** classes during test discovery is
almost always packaging, not code.

**2. Integration tests polluted each other through shared containers.**
Four tests failed on absolute dates. The containers are singletons shared by every IT class, and
`PriceCacheIT` had already run a simulation before `PriceSimulationIT` started, so the price history
was several days further on than that class assumed.

*Fix:* `PriceSimulationIT` rewinds price history to the seed date in `@BeforeAll` (`PER_CLASS`
lifecycle, so it can use injected beans). Keeping the exact-date assertions was worth the rewind —
"the 30th trading day after a Friday is six calendar weeks later" is precisely the property that
would break silently if weekend skipping regressed.

**3. Then the cache poisoned a different test class.**
After fixing the dates, one failure remained: `expected: 200.0000 but was: 1.2345`. The strongest
test in the suite deliberately writes a fake price into Redis to prove reads go through the cache —
and never cleaned it up. Redis is shared across IT classes too.

*Fix:* `@AfterEach` eviction in `PriceCacheIT`, plus a cache evict in `PriceSimulationIT`'s rewind.
*Lesson:* a test that deliberately corrupts shared state owns cleaning it up, and "shared state"
includes the cache, not just the database.

**4. A pretty-printer, not the API, destroyed decimal precision.**
`curl | python3 -m json.tool` showed `"close": 200.0` where the database held `200.0000`. Checking
the raw bytes showed the API was correct — Python's JSON parser had coerced the number to a float
and reformatted it. An unusually on-the-nose demonstration of why this codebase does not use
floating point anywhere near money: the precision survived Postgres, JPA, Jackson and Redis, and was
lost by a *debugging tool*.

### Review pass — issues found and fixed before committing

**A Redis outage would have thrown `UnsupportedOperationException`.** `PriceCache.get` returns
`Map.of()` when Redis is unreachable, and `MarketDataService` wrote database results straight back
into that map — which is immutable. So the exact path the fail-soft cache existed to protect would
have thrown, turning a degraded cache into a broken endpoint. Fixed by copying into a mutable map,
and pinned by `MarketDataServiceTest.aRedisOutageFallsThroughToTheDatabase`, which mocks the cache
because a running container cannot easily reproduce the case that matters most.

**The simulator replayed the identical shock on every call.** `new Random(seed)` was constructed
per call, so every invocation drew the same sequence: calling `simulate(1)` repeatedly applied the
*same* move to each security every time — a systematic drift wearing a random walk's clothes. Fixed
by mixing the starting date into the seed (`seed + lastDate.toEpochDay()`), which keeps each run
reproducible while making consecutive runs genuinely different.

**The regression test for that bug did not actually catch it.** The first version compared
day-over-day ratios at 10 decimal places and passed *with the bug still present*. Prices are stored
at 4 decimal places, so each step carries ~2e-7 of relative rounding noise — enough to make even
identical shocks produce different ratios at that precision. It was measuring rounding noise, not
the shock. Rounding the ratios to 5 decimal places puts the threshold well above the noise and far
below a real 1.2% move. Verified properly by reintroducing the bug and confirming the test fails,
then restoring the fix and confirming it passes.

*This is the most useful thing in this phase.* A test that passes for the wrong reason is worse than
no test, because it actively certifies broken behaviour. The only way to trust a regression test is
to watch it fail against the bug it was written for.

### Known, accepted for now

- **Coverage numbers are unit-tests-only.** JaCoCo's agent is bound to Surefire, so the reported
  73.6% line coverage for `market-data-service` excludes all 29 integration tests — real coverage is
  materially higher. Wiring `prepare-agent-integration` is deferred to Phase 3/4, where the >80%
  `engine-core` gate actually applies.
- **`saveAll` on a composite-key entity issues a SELECT before each INSERT.** Hibernate cannot tell
  new from detached for an `@EmbeddedId` without a version field, so a 30-day run does 900 selects it
  does not need. Fast enough at this size; revisit in Phase 6 if the batch numbers need it.
- **Prices are simulated, not real.** CSV loading from S3 is a Phase 7 addition.

### Verification

```
$ mvn -B clean verify
taxlot-parent ...................................... SUCCESS [  0.177 s]
common ............................................. SUCCESS [  1.237 s]
engine-core ........................................ SUCCESS [  0.032 s]
market-data-service ................................ SUCCESS [ 10.532 s]
portfolio-service .................................. SUCCESS [  2.623 s]
rebalance-engine ................................... SUCCESS [  2.294 s]
bdd-tests .......................................... SUCCESS [  0.010 s]
perf-tests ......................................... SUCCESS [  0.008 s]
BUILD SUCCESS

Tests: 88 passed, 0 failed  (59 unit + 29 integration)
```

Against the live Docker stack:

```
$ docker exec taxlot-postgres psql -U taxlot -d taxlot \
    -c "SELECT version, description, success FROM market_data.flyway_schema_history"
 1 | create security price substitute | t
 2 | seed security universe           | t

 securities | substitutes | prices
         30 |         120 |     30

$ docker exec taxlot-redis redis-cli EXISTS market-data:prices:latest
0                                            # cold

$ curl -s "localhost:8081/prices/latest?tickers=AAPL"
[{"ticker":"AAPL","close":200.0000,"priceDate":"2026-01-02"}]

$ docker exec taxlot-redis redis-cli HGET market-data:prices:latest AAPL
{"ticker":"AAPL","close":200.0000,"priceDate":"2026-01-02"}   # served from Redis next time

$ curl -s -X POST "localhost:8081/prices/simulate?days=30"
{"tradingDays":30,"securities":30,"fromDate":"2026-01-02","toDate":"2026-02-13","pricesWritten":900}

$ docker exec taxlot-redis redis-cli EXISTS market-data:prices:latest
0                                            # evicted by the simulation

$ curl -s "localhost:8081/prices/latest?tickers=AAPL,MSFT,XOM"
[{"ticker":"AAPL","close":213.5808,...},   # 200.0000 -> 213.5808
 {"ticker":"MSFT","close":407.6914,...},   # 420.0000 -> 407.6914
 {"ticker":"XOM","close":113.3745,...}]    # 115.0000 -> 113.3745

$ psql -c "SELECT count(*) FROM market_data.price WHERE EXTRACT(ISODOW FROM price_date) > 5"
0                                            # no weekend prices
$ psql -c "SELECT count(DISTINCT price_date) FROM market_data.price"
31                                           # 1 seeded + 30 simulated

$ curl -s localhost:8081/securities/AAPL/substitutes
["MSFT","GOOGL","META","NVDA"]               # CLAUDE.md's pair first, then same-sector

$ curl -o /dev/null -w "%{http_code}" -X POST "localhost:8081/prices/simulate?days=0"
400                                          # bad input is 4xx, not 5xx
```

**Gate: passed.** Prices move, the cache is demonstrably in the read path, and weekends are skipped.

---
