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
