# pgdescribe — plan

**Status: M0–M6 are done, M7 is three quarters done** (2026-08-27). `COPY`,
batching, exact cardinality and row mappers have shipped; the R2DBC target is an
open decision, see §17. CLI, Gradle plugin and
optional libpg_query bindings all work; 189 tests pass, including generated code
that compiles and runs against a real Postgres, TestKit builds that prove the
plugin's incremental and build-cache behaviour, and per-column nullability
proved against Postgres' own parser. See §11–§15 for what building them
changed.

**One line:** a Postgres-only, AI-first replacement for SqlDelight where the *database itself* is the type checker, and the generated Kotlin is the contract the model reads.

## 0. Design principle

> Minimise what the model must know; maximise what it can check.

SqlDelight optimised the opposite way — a hand-rolled Grammar Kit parser so it could type-check without a database, plus IDE affordances for a human in the moment. In an AI-first workflow the parser is a liability (it lags Postgres, it is the biggest single chunk of the codebase) and the IDE layer is dead weight. The verifier is the whole product.

## 1. The core bet: Postgres as the oracle

Do **not** write a SQL grammar. For every query:

1. Start an ephemeral Postgres, apply `migrations/` in order.
2. Send `Parse` + `Describe(statement)` over the extended protocol (via pgjdbc: `PreparedStatement.getParameterMetaData()` / `.getMetaData()`).
3. Postgres answers with `ParameterDescription` (param type OIDs) and `RowDescription` (per column: name, **tableOid**, **column attnum**, type OID, typmod).
4. Anything Postgres rejects is a build error, with its real `SQLSTATE` and message. Zero dialect drift, forever.

This is pgtyped's model and where sqlc has been migrating. It also means every new Postgres feature works on day one.

### What the oracle does *not* give you

- **Result-column nullability.** `RowDescription` has no nullable flag. This is the one genuinely hard problem — see §4.
- **Utility statements.** `PREPARE` only accepts `SELECT`/`INSERT`/`UPDATE`/`DELETE`/`VALUES`. `CALL`, DDL, `COPY` need a separate path (execute inside a transaction that is always rolled back, or declare them `:exec` and only syntax-check).
- **Ambiguous parameter types.** `$1` in a bare `=` against an unknown-typed expression can come back as `unknown`/`text`. Treat any `unknown` param OID as a hard error with the message *"add an explicit cast, e.g. `$1::uuid`"* — this is exactly the kind of nudge a model acts on correctly.

## 2. Shape of the thing

```
pgd-core        analyzer: pg lifecycle, migrations, describe, catalog lookups, type mapping
pgd-codegen     Kotlin emission (JDBC target)
pgd-cli         `pgd check | generate | schema` — a fat jar + wrapper script
pgd-gradle      thin Gradle plugin wrapping the CLI as a cacheable task
pgd-native      OPTIONAL: FFM bindings to libpg_query (see §4, phase 3)
```

Zero runtime library. Generated code depends on `java.sql` and nothing else. There is no `SqlDriver` abstraction — the generated functions take a `java.sql.Connection`. Hikari, pooling, transaction scoping are the application's business.

## 3. Layout and conventions

```
db/
  migrations/
    V001__users.sql            plain Flyway-style SQL; the ONLY source of schema truth
    V002__orders.sql
  queries/
    users.sql                  one file per aggregate, N named queries per file
  pgd.toml                     config: package, type overrides, target
  schema.md                    GENERATED — the artifact the LLM actually reads
  schema.json                  GENERATED — machine-readable, for tooling
```

Query file format — sqlc's header syntax, because it has by far the most training data:

```sql
-- name: FindActiveUsers :many
-- params: since
SELECT u.id, u.email, u.display_name, o.total_cents
FROM users u
LEFT JOIN orders o ON o.user_id = u.id
WHERE u.active AND u.created_at > $1::timestamptz;
```

- Positional `$1` (not `:name`) so the file stays **valid SQL you can paste into psql**. `-- params:` names them positionally for codegen ergonomics.
- `:many` / `:one` / `:exec` / `:execrows` cardinality tags, same as sqlc.
- Escape hatches for nullability the analyzer can't prove: `-- notnull: total_cents` / `-- nullable: display_name`.

Generated output, deliberately boring:

```kotlin
public data class FindActiveUsersRow(
  public val id: Long,
  public val email: String,
  public val displayName: String,
  public val totalCents: Int?,   // LEFT JOIN -> nullable
)

/**
 * SELECT u.id, u.email, ... (full SQL inlined here so it is readable in place)
 */
public fun Connection.findActiveUsers(since: OffsetDateTime): List<FindActiveUsersRow> { ... }
```

The SQL is inlined as KDoc on purpose: the generated file doubles as a compressed, always-current summary of both schema and query set, which is what a model loads instead of re-reading every migration.

## 4. The nullability problem — staged, honest

Three phases, each independently shippable. Phase 1 is enough to be useful.

**Phase 1 — catalog passthrough + conservative default.**
`RowDescription` gives `tableOid` and `attnum` for any column that is a *direct passthrough* from a base relation. Look up `pg_attribute.attnotnull` for those; everything else (expressions, aggregates, `COALESCE`, subselects) is nullable unless annotated. Cost: one catalog query per generation run.

Caveat to handle explicitly: **pgjdbc's `ResultSetMetaData.isNullable()` already does this lookup, and gets outer joins wrong** — it reports the base column's constraint, so a `NOT NULL` column on the nullable side of a `LEFT JOIN` is reported non-nullable. That is precisely the bug class we exist to catch, so do the catalog lookup ourselves rather than trusting `isNullable()`. *(Verify pgjdbc's exact behaviour before relying on this claim.)*

**Phase 2 — outer-join demotion, cheaply.**
The only common case Phase 1 gets wrong is outer joins. Detect the nullable side and demote those columns. Doing this without a parser is fragile; doing it with one is trivial.

**Phase 3 — real parser via FFM.**
`libpg_query` is the actual Postgres parser extracted as a C library, returning a parse tree as JSON/protobuf, branch-tracked to each PG major. Bind it with Java's **Foreign Function & Memory API** (finalised in Java 22, JEP 454 — JDK 25 is installed here). It buys: outer-join nullability, `COALESCE`/`CASE` refinement, statement splitting, and precise error spans for editor/LLM feedback.

The real cost is **distribution**: `libpg_query` ships as a static lib; we would need to build and ship `.dylib`/`.so` for macOS arm64+x64 and Linux x64+arm64. Therefore: **keep it strictly optional**. Without the native lib the tool degrades to Phase 1/2 plus annotations, never breaks.

FFM against `libpq` itself was considered and rejected: pgjdbc is a *build-time-only* dependency, so there is no runtime cost to shed, and pgjdbc already speaks the extended protocol correctly. FFM earns its keep only for the parser.

## 5. Why a generator and not just a verifier

The "LLM writes the mapper, a `pgcheck` task verifies it" middle path is attractive but has a hidden cost: **to verify hand-written Kotlin you must read Kotlin types**, which means KSP or the Kotlin compiler embeddable — more machinery than the codegen it replaces, and it drags in a compiler plugin, exactly the thing we were avoiding. Generation reads no Kotlin at all.

Plus the maintenance asymmetry: 200 hand-written mappers is 200 non-deterministic edits and 200 reviews on a column rename; a generator is one command and a diff you can trust.

So: **build the generator, but ship the analyzer as a library and expose `pgd check` as a first-class mode** that validates SQL against the schema without emitting anything. Verifier-only users get most of the value, and it costs ~nothing extra.

## 6. Decisions (locked 2026-08-26)

| # | Decision | Choice | Rejected |
|---|---|---|---|
| 1 | Type source | Live Postgres describe | Hand-rolled grammar (SqlDelight), JSqlParser |
| 2 | Parser | None in v1; optional libpg_query via FFM later | Grammar Kit, ANTLR, regex |
| 3 | Output | Generate Kotlin + `check` mode | Verify-only (needs KSP), or no tool at all |
| 4 | Runtime | None. `java.sql.Connection` receiver | `SqlDriver` abstraction, ORM session |
| 5 | Async | JDBC only in v1 | R2DBC — on JDK 21+ virtual threads make blocking JDBC good enough; revisit as a codegen flag, never a runtime interface |
| 6 | SQL location | Separate `.sql` files | Inline Kotlin strings (forces compiler-plugin territory, loses psql/sqlfluff) |
| 7 | Params | `$1` + `-- params:` names | `:name` (breaks psql-runnability), `sqlc.arg()` |
| 8 | Schema source | `migrations/` only | Separate maintained DDL file (always drifts) |
| 9 | Toolchain | Build/run on JDK 25; generated code targets JDK 17 | Forcing consumers onto 22+ |
| 10 | Distribution | CLI first, Gradle plugin wraps it | Gradle-plugin-only — a model can run a CLI and read its stdout directly |
| 11 | Postgres source | **Zonky `embedded-postgres`** by default, `PGD_URL` override | Testcontainers (hard-requires a Docker daemon), local `initdb` binaries (version drift between each dev machine and prod defeats the point of a fidelity tool) |
| 12 | Relationship to sqlc | Build fresh, but **read `sqlc-gen-kotlin` before writing any codegen** | Contributing to it (inherits the Go plugin protocol, its config format, and its analyzer's limits) |

Cache the Postgres aggressively: hash `migrations/` into a template database and skip re-apply when unchanged. Testcontainers stays available as config for anyone who wants production fidelity over speed.

## 7. Type mapping

OID → Kotlin, user-extensible in `pgd.toml`:

| Postgres | Kotlin |
|---|---|
| `int2/int4/int8` | `Short/Int/Long` |
| `text/varchar/bpchar` | `String` |
| `bool` | `Boolean` |
| `numeric` | `BigDecimal` |
| `timestamptz` | `OffsetDateTime` |
| `timestamp` | `LocalDateTime` |
| `date/time` | `LocalDate/LocalTime` |
| `uuid` | `java.util.UUID` |
| `jsonb/json` | `String` (override to a serializer) |
| `_int4` etc. | `List<Int>` |
| enum types | generated Kotlin `enum class` from `pg_enum` |
| domains | base type + optional value class |
| composites / ranges | v2 |

`timestamptz → OffsetDateTime` not `LocalDateTime` — mapping a `timestamptz` to a local type is one of the three silent failures this tool exists to prevent (alongside stale columns and outer-join nullability).

## 8. LLM ergonomics — the part that is actually the product

- `pgd check --format=json` emits machine-readable diagnostics: file, line, col, the offending SQL, `SQLSTATE`, Postgres' message, and a **suggested fix** phrased as an instruction ("add `::uuid` to `$1`", "column `user_id` was renamed to `owner_id` in V007").
- `pgd schema` writes `schema.md` — tables, columns, types, constraints, enums — so a session reads one file instead of grepping migrations.
- Ship an `AGENTS.md` template describing the conventions, so the tool teaches its own usage.
- Exit codes are stable and documented; every failure names the file and the query.

## 9. Milestones

- **M0** Repo skeleton: Gradle version catalog, Kotlin JVM, JDK 25 toolchain, CI.
- **M1** `pgd check` — **the first shippable milestone**; — spin PG, apply migrations, describe every query, report failures. Ships and is useful before any codegen exists; codegen is layered onto the same analyzer.
- **M2** ✅ Type extraction + `pgd generate` for JDBC, all four cardinalities.
- **M3** ~~Nullability Phase 1~~ — pulled into M2 (see §12). What remains is
  replacing the outer-join keyword heuristic with real analysis, which is the
  same work as M6; **M3 and M6 have merged.**
- **M4** ✅ Enums, domains, arrays, jsonb; `pgd.toml`; `schema.md` / `schema.json`.
- **M5** ✅ Gradle plugin, incremental + build-cache correctness, template-DB caching.
- **M6** ✅ Nullability Phase 3: `pgd-native` FFM bindings to libpg_query,
  optional. Subsumed M3: the statement-wide keyword heuristic is now a fallback,
  not the only answer.
- **M7** `COPY` ✅, batch ✅, cardinality refinements ✅. R2DBC target: **open**,
  see §17.

## 10. Risks

1. **Native distribution** (libpg_query) — mitigated by making it optional.
2. **Startup cost** of a Postgres per build — mitigated by template-DB caching + `PGD_URL`.
3. **Nullability correctness** is the tool's whole credibility; being *conservative* (over-nullable) is safe, being wrong the other way is not. Default must lean nullable.
4. **sqlc already exists** with a Kotlin plugin (`sqlc-gen-kotlin`, `engine: postgresql`). Study it before writing a line of codegen — the differentiators must be real: idiomatic Kotlin (not Java-flavoured), nullability rigour, and LLM-shaped diagnostics. If those three don't hold up, contributing to sqlc-gen-kotlin is the better move.


## 11. What building M1 changed

Three things the plan got wrong or left open, resolved by contact with the code.

### pgjdbc does not understand `$1`

Decision #7 keeps native `$1` placeholders in query files so they stay runnable
in psql. But JDBC's placeholder is `?`, and pgjdbc counts placeholders itself
before sending Parse. Handed a `$1` query it finds zero parameters, the server
replies describing one, and pgjdbc throws
`ArrayIndexOutOfBoundsException` out of `getParameterMetaData`.

So `SqlRewriter` converts `$n` to `?` before the describe, skipping string
literals, quoted identifiers, line and nested block comments, and dollar-quoted
bodies. Two consequences worth recording:

- Each `?` is padded with spaces to the exact width of the `$n` it replaced, so
  character offsets are identical in both strings and a position reported by
  Postgres still lands on the right column of the original file. This is what
  makes `users.sql:4:8` point at the actual typo.
- The rewrite is **not** analysis-only scaffolding. Generated JDBC code has to
  execute `?` SQL too, so M2 reuses it. A repeated `$1` becomes two binds of the
  same value, and the analyzer errors (PGD1006) if Postgres infers a different
  type at each occurrence — which real `$1` would have unified.

This also adds a check Postgres can no longer make for us: after rewriting,
`SELECT $1, $3` becomes valid, so gaps in the placeholder run are caught
ourselves (PGD1005).

### The pgjdbc nullability claim is confirmed

`PgJdbcNullabilityProbeTest` pins it down: for `person LEFT JOIN pet`, pgjdbc
reports `pet.nickname` as `columnNoNulls` because that is the base column's
constraint, even though the outer join makes it nullable in that result set.
Expressions come back `columnNullableUnknown` with no base table.

M3 therefore does its own `pg_attribute` lookup rather than trusting
`isNullable()`, exactly as planned. The probe test stays, so we notice if pgjdbc
ever changes.

### Start-up cost is not a problem yet

On the bundled example, cold: **~2.0s** embedded, **~0.44s** against an
already-running server via `--url`. Template-database caching was listed as an
M1 risk mitigation; at these numbers it can wait for M5 as originally scheduled.

### Toolchain refinement

Decision #9 said build on JDK 25, generated code targets 17. In practice the
*tool itself* should target 17 too — the `application` start script runs under
whatever `JAVA_HOME` is set, and requiring JDK 25 to run `pgd` is adoption
friction for no gain until FFM arrives in M6. The build now compiles with the
JDK 25 toolchain and emits Java 17 bytecode via `-Xjdk-release=17`.


## 12. What building M2 changed

### Nullability could not wait for M3

M2 was scoped as codegen with nullability deferred. That does not survive
contact with the output: every column typed `T?` is unusable, and would have
churned the whole generated tree one milestone later. But the alternative —
trusting pgjdbc's `isNullable()` — types the bundled example's `LEFT JOIN`
column as non-null, which is precisely the silent bug this project exists to
catch.

So Phase 1 moved into M2, gated: a column is non-null only when Postgres says
its base column is `NOT NULL` **and** the statement contains no outer join,
`ROLLUP`, `CUBE` or `GROUPING SETS`. The keyword scan runs over *masked* SQL —
literals, quoted identifiers and comments blanked to spaces, length preserved —
so `SELECT 'LEFT JOIN' AS label` does not demote anything.

This is wrong only in the safe direction, and it makes §4's staging obsolete:
Phase 1 and a cheap Phase 2 are shipped, and what remains is Phase 3. **M3 and
M6 are now one milestone.**

One correction to §4 while we are here: the plan said to do the catalog lookup
ourselves rather than trust `isNullable()`. In practice `isNullable()` is the
better primitive — it resolves by table OID and attribute number, where our own
lookup would have had to match on a table *name* and could pick the wrong schema.
Its only defect was outer joins, which the gate now handles. The probe test
still pins the behaviour so a pgjdbc change is noticed.

### Native `$1` needed the M1 rewriter to reach codegen

Confirmed the M1 prediction: `SqlRewriter` is not analysis-only. Generated code
embeds the `?` form, and the `bindings` list is what lets a repeated `$1` bind
the same argument to two positions.

### pgjdbc renames identity columns

`getColumnTypeName` reports `bigserial` for a `bigint GENERATED ALWAYS AS
IDENTITY` column (and `serial`/`smallserial` for the narrower ones), so the type
table needs the serial spellings alongside the real type names. Found because an
unmapped type is a hard error rather than a silent fallback — the design working
as intended on its first real input.

### Scalar collapse, and one place it must not happen

A single-column result returns the value rather than a wrapper: `SELECT name`
gives `List<String>`. The exception is `:one` over a *nullable* column, where
collapsing to `String?` would conflate "no row matched" with "the value was
NULL". That case keeps its row class.

### The example is now a compiled module

`example/` is a Gradle module whose generated source is committed and compiled
by `./gradlew build`, with tests that run the generated functions against a real
Postgres — including one asserting the `LEFT JOIN` column really does come back
null. A drift test regenerates and fails if the committed output is stale, which
also pins determinism.


## 13. What building M4 changed

### `[types]` aliases one Postgres type to another, not to arbitrary Kotlin

§7 said the type table would be "user-extensible". The obvious reading — let
users name any Kotlin class — needs a conversion hook, which means a runtime
library, which contradicts decision #4. The useful cases turn out not to need
one: extension types you want read as text, and enums you would rather have as
plain strings. So `[types]` maps a Postgres type name to *another Postgres type
name*, and everything downstream is unchanged:

```toml
[types]
interval = "text"   # pgd has no interval mapping; read its text form
mood     = "text"   # opt this enum out of getting a Kotlin class
```

Arbitrary Kotlin targets with converters stay open as a later milestone, but
they are a different feature, not an extension of this one.

### Array elements are `List<T?>`, not `List<T>`

A Postgres array may hold NULL in any slot regardless of whether the column
itself is nullable, and nothing in the wire protocol says otherwise. `List<T>`
would be a lie Kotlin cannot catch — the elements arrive through an untyped
`Array<*>` cast, so a NULL would land in a `List<String>` silently. Same
principle as the nullability gate in §12: wrong only in the safe direction.

### Domains resolve, they do not wrap

§7 floated "base type + optional value class" for domains. Generating a value
class per domain adds a type the model has to learn for no verification gain —
the CHECK constraint is enforced by Postgres either way. Domains resolve to
their base type, and the domain name still appears in `schema.md`.

### tomlj throws instead of returning null

`TomlTable.getString` raises `TomlInvalidTypeException` when the key holds a
non-string, so every read is guarded by `isString` and reported as a PGD5001
diagnostic. Found by a test; then found again in the CLI, where config loading
sat outside the top-level error handling and a bad `pgd.toml` printed a stack
trace instead of a diagnostic.

### Config paths are relative to the config file

`pgd.toml`'s `output` is resolved against the directory holding `pgd.toml`,
while `--out` is resolved against the shell's working directory. Anything else
makes a committed `pgd.toml` depend on where you happen to run `pgd` from.

### The example now covers the hard types

`example/db` has an enum with a non-identifier label (`over the moon`), a
domain, a `text[]`, a `jsonb` column and an `interval` reached through a
`[types]` alias — all round-tripped against a real Postgres in
`GeneratedCodeTest`. The committed `schema.md` and `schema.json` have their own
drift test alongside the generated Kotlin.


## 14. What building M5 changed

### `schema.md` in the project directory forces the input split

The task cannot declare `db/` as an input, because `schema.md` is written into
it and an output nested inside a declared input can never be up to date. So
`migrations/`, `queries/` and `pgd.toml` are declared individually. A test pins
this: dropping an unrelated file into `db/` must leave the task UP-TO-DATE.

### Gradle's `@InputFile` requires the file to exist, `@Optional` or not

`@Optional` means "the property may be unset", not "the file may be missing", so
always pointing `configFile` at `db/pgd.toml` broke every build without one.
All three inputs are now `ConfigurableFileCollection`, which tolerates missing
paths — and as a bonus a missing `migrations/` is reported by pgd's own PGD3002
diagnostic, with a hint, instead of as a Gradle validation failure.

### A `compileOnly` dependency on the Kotlin Gradle plugin is not enough

Referencing `KotlinJvmProjectExtension` throws `NoClassDefFoundError` under
Gradle TestKit, where this plugin's classloader is a sibling of the one KGP is
loaded into rather than a child. It works in an ordinary build and fails in a
legitimate consumer configuration, which is the worst kind of coupling.

The wiring now goes through Gradle's own `SourceDirectorySet` by reflection and
the KGP dependency is gone entirely. Two related traps: `srcDir(taskProvider)`
adds *every* output of the task, so `schema.json` was offered as a source
directory — it has to be `srcDir(task.flatMap { it.outputDirectory })`, which
still carries the task dependency.

### The database URL must not be a task input

Which server ran the analysis does not change a byte of the output, so `url` is
`@Internal`. Making it an `@Input` would invalidate every cache entry whenever
someone set `PGD_URL`, for no correctness gain.

### Template caching, measured

Keyed on a hash of the migrations, only on servers whose databases outlive the
process — a server we start and throw away has nothing to reuse. Built under a
temporary name and renamed atomically into place, so a run that dies partway
through leaves a database nobody will ever match on rather than a corrupt cache
entry; if another run wins the race, the loser drops its staging copy.

On 120 migrations: **1.54s** cold, **0.73s** clone. The saving scales with the
migration count, which is exactly the case where it matters.

Templates accumulate on a shared server, so `pgd clean --url <jdbc>` drops every
template and scratch database pgd has created. Automatic eviction was considered
and rejected: several projects can share a dev server, and guessing which
templates belong to someone else's checkout is not a guess worth making.

### Known limitations

- The plugin runs pgd-core inside the Gradle daemon rather than in an isolated
  worker, so pgjdbc, zonky and tomlj land on the buildscript classpath. Moving
  to the Worker API with classloader isolation is the fix, and it wants a
  published artifact to resolve — so it waits for publishing.
- `example/` still generates through the CLI rather than dogfooding the plugin,
  because applying a plugin defined in the same build needs a composite build.
  The TestKit test covers the same ground, including `compileKotlin`.


## 15. What building M6 changed

### The native distribution risk did not materialise

§4 called shipping `libpg_query` the real cost, on the assumption it is
static-only and we would have to build and distribute `.dylib`/`.so` per
platform. Homebrew ships a **shared** `libpg_query.dylib` in homebrew-core, at
version 18.0.0 with `PG_VERSION_NUM 180004` — the same Postgres 18.4 as the
Zonky binaries the tool already uses. `brew install libpg_query` is the whole
installation story, and the equivalent packages exist on Linux.

That removes the argument for keeping the parser at arm's length, though the
optionality is kept anyway: it still degrades rather than breaks.

### One coarse question beats per-column tracking

The obvious design — walk the target list, resolve each output column to a range
table entry, decide per column — breaks on `SELECT *`, needs alias resolution,
and needs positional matching against `RowDescription` that a star expansion
destroys.

Instead the parse tree answers exactly one question: **which base relations can
an outer join null?** The catalog answers the rest, because pgjdbc already
reports each column's base table by OID. Matching on relation name rather than
alias makes the whole alias problem disappear, and the one case it cannot
resolve — a self-join with one outer arm, where both columns come from the same
relation — lands in the nullable set and stays conservative. Small enough to
hold in your head, and wrong only in the safe direction.

### Gradle will not put Java 22 bytecode on a Java 17 classpath

Correctly: `pgd-cli` targets 17 so it runs anywhere, and Gradle's
`TargetJvmVersion` attribute refused `pgd-native` outright. Overriding the
attribute would have been a lie about a real incompatibility.

The module is split instead. `src/main` is compiled for 17 and holds the
`ServiceLoader` entry point, which reaches the FFM implementation in `src/ffm`
**by name**. On an older JVM `Class.forName` throws `UnsupportedClassVersionError`,
the constructor throws, and `SqlParsers` reads that as "no parser installed".
The jar carries class file versions 61 and 66 side by side, which is the point.

### The parse tree must be taken from the `$1` form

`?` is not valid Postgres syntax, so handing libpg_query the JDBC-rewritten SQL
would return a parse error for *every* parameterised query — silently falling
back to the conservative rule everywhere it matters most. There is a regression
test asserting both halves of this.

### Optional analysis makes output machine-dependent

This is the part worth being careful about. If nullability precision depends on
whether an optional library happens to be installed, two developers generate
different types from identical inputs, and a Gradle cache entry produced on one
machine is wrong on the other.

So `nullability` is now a `pgd.toml` setting — `auto`, `conservative` or
`precise` — and the *effective* mode is a Gradle task input, so a cached
artifact cannot cross that boundary. `precise` fails the run when the parser is
missing (PGD5002) rather than quietly downgrading. `example/db` pins
`conservative`, so its committed output is byte-identical everywhere and
`./gradlew build` works on a machine with no libpg_query.

### Two smaller things

`dlopen` by bare name does not find `/usr/local/lib` on macOS, which is exactly
where Homebrew puts the library, so lookup walks an explicit search path before
falling back to the loader.

Java 24+ warns on every restricted method call. The flag that silences it,
`--enable-native-access`, cannot go in the launcher script: it does not exist on
Java 17, which pgd still supports, and an unknown launcher option is fatal.
`PGD_OPTS` is the documented answer until the tool's own floor moves past 22.


## 16. What building M7 changed

### COPY keeps the oracle by asking a different question

`COPY` cannot be prepared, so the describe path that verifies every other
statement does not work on it — which looked like it would need a hand-rolled
parser and a catalog lookup, exactly the machinery this project exists to avoid.

It does not. The column list is turned into
`SELECT <columns> FROM <table> WHERE false`, which Postgres *will* describe.
That validates the table and every column name with Postgres' own error
messages, and hands back the types and `NOT NULL` constraints the loader needs.
A typo'd column in a `:copy` statement fails with SQLSTATE 42703 like anywhere
else. The only thing pgd parses itself is the fixed
`COPY <table> (<columns>) FROM STDIN` shape, and anything else is refused with
an instruction rather than guessed at.

### pgjdbc's COPY row count is unusable after close

`PGCopyOutputStream.getHandledRowCount()` throws
`AssertionError: Misuse of castNonNull` once the stream has been closed, because
`close()` releases the underlying operation. The count has to come from
`endCopy()`, which returns it. Generated loaders therefore flush, call
`endCopy()`, and on any failure call `cancelCopy()` — leaving a connection
mid-COPY would break every later statement on it.

Found by a test that asserted the returned count rather than trusting it.

### `:exactlyone` instead of inferring cardinality

The tempting version of "RETURNING refinements" was to notice, via the parse
tree, that `INSERT ... VALUES ... RETURNING` always produces exactly one row and
silently return a non-null type. That would have put a *second* axis of
generated output behind whether libpg_query happens to be installed — the exact
hazard §15 had just finished fencing off.

`:exactlyone` is explicit instead: opt-in, deterministic, identical on every
machine, and it reads at the call site. `connection.recordEvent(...)` returns a
row rather than a row-or-null, with no `!!`.

It also composes with the scalar collapse in a way `:one` cannot. `SELECT
count(*)` is an expression, so its nullability is unprovable and `:one` keeps a
wrapper to avoid conflating "no row" with "NULL". Declared `:exactlyone` with
`-- notnull: count`, it collapses to a plain `Long`.

### Batch is a directive, not a cardinality

`-- batch` *adds* a second entry point rather than replacing the single-row one,
so a query can be called both ways without duplicating the SQL. It is rejected
on anything returning rows (JDBC batches only statements with no result set) and
on anything with no parameters (which would just run the same statement N times).

## 17. Open: the R2DBC target

Decision #5 deferred R2DBC with a reason rather than a refusal: "on JDK 21+
virtual threads make blocking JDBC good enough; revisit as a codegen flag, never
a runtime interface." M7 is that revisit, and it has not been made yet.

**What it would cost.** A second emitter (suspend functions and `Flow`), a
second type mapping — r2dbc-postgresql does not map types the way pgjdbc does —
and a second live-database test matrix. Roughly the size of M2. Analysis is
untouched; only emission differs, which is the whole reason decision #5 said
"flag, not interface".

**What it would buy.** Genuinely non-blocking data access for stacks that cannot
block: Spring WebFlux, and Ktor deployments that will not run on virtual
threads. Generated code would take `io.r2dbc.spi.Connection` and depend on
`r2dbc-spi` plus kotlinx-coroutines — still no *pgd* runtime library, so
decision #4 survives, but consumers gain dependencies JDBC does not need.

**Recommendation: do not build it yet.** On JDK 21+, `Dispatchers.IO` backed by
virtual threads makes blocking JDBC behave well under coroutines, and the
generated code stays the boring `PreparedStatement`/`ResultSet` shape the brief
asked for. Better next work, in order: publishing (which also unblocks the
Worker API isolation left open in §14), then transactions and connection
ergonomics, then R2DBC if a real project needs it.


## 18. Row mappers, taken from SqlDelight

SqlDelight generates, for every query, an overload taking
`mapper: (col, col, ...) -> T`. It exists so a consumer can supply their own
class — annotated `@Serializable`, `internal`, holding value classes — instead of
the generated data class. pgd now does the same thing, and the reasoning is a
better fit here than it was there.

**The mapper form holds the body; `Row` delegates to it.** Not the other way
round, and not two copies:

```kotlin
fun <T : Any> Connection.findUserByEmail(email: String, mapper: (...) -> T): T? = ...
fun Connection.findUserByEmail(email: String): FindUserByEmailRow? =
    findUserByEmail(email, ::FindUserByEmailRow)
```

That ordering is what makes the feature worth having: a custom class is built
straight off the `ResultSet`, so there is no generated row allocated and
immediately discarded. Had `Row` held the body, the mapper overload would have
been a `.map { }` with extra steps, which callers can already write.

**It is a second typed contract, which is the point for this project.** The
whole bet in §1 is that the model should be told as little as possible and be
able to *check* as much as possible. A hand-written class is exactly the place
where a model's stale schema knowledge would otherwise go unchecked — it can
invent a field, or guess a nullability, and nothing contradicts it. Routed
through a mapper, the query's shape is a function type: add a column, or demote
one to nullable, and every hand-written mapper fails to compile at the call
site. The row class gave that guarantee only to classes pgd owns; the mapper
extends it to classes it does not.

**Arguments are positional.** Kotlin does not allow named arguments when
invoking a function type, so `mapper(id = ...)` is illegal and the generated call
is ordered. The parameter names in the function type still show up in the IDE and
in the generated source, and `Row` remains the worked example of the shape a
mapper must match — so the ordering is discoverable rather than something to
remember.

**Where there is no overload.** A query whose single non-null column collapses to
a scalar has nothing to map. Neither do `:exec`, `:execrows`, or `:copy`. `:copy`
is the interesting one: its natural mirror image would be an *extractor*,
`(T) -> columns`, letting a caller stream their own class into `COPY`. That is
deliberately not built, because unlike the read side it buys nothing — a caller
can `map { }` into the generated row before calling, and pays one allocation per
row on a path that is already streaming to a socket. The read side had no such
workaround, which is why it got the overload.

**A parameter named `mapper`** takes the name; the mapper becomes `mapper_`. Rare,
but a silent shadow would produce a baffling compile error in generated code.

## 19. The name

Renamed from `psql-delight` on 2026-08-27. Two things were wrong with the old
one. `psql` is the *interactive terminal client*, which this project never
touches — it speaks the extended query protocol through pgjdbc, so the name
pointed at the wrong program. And "delight" only meant anything by reference to
SqlDelight: a discovery crutch that read as "fork or port", and a permanent
framing of a Postgres-native tool as derivative of a multi-dialect one, which is
precisely the opposite of the argument in §1.

`pgdescribe` names the mechanism. Parse then Describe is the bet — §2 in full —
so anyone who knows the wire protocol reads the name and already knows why there
is no grammar in here.

It also cost nothing to adopt, which decided it against the alternatives
(`touchstone`, `assay`, `plumbline`): the CLI already shipped as `pgd`, so the
binary, the module names (`pgd-core`, `pgd-cli`, …), `pgd.toml`, `PGD_URL` and
every `PGD*` diagnostic code stayed exactly as they were. `pgd` stopped being an
abbreviation of a name being dropped and became an acronym for the new one. Only
the package root moved, `io.psqldelight.pgd` to `io.pgdescribe`, dropping a level
on the way: `io.pgdescribe.core`, `.cli`, `.gradle`, `.native`. The Gradle plugin
id is now `io.pgdescribe`.

Near-neighbours checked and avoided: [kotgres](https://github.com/mfarsikov/kotgres)
(taken, and close in philosophy — KAPT-based rather than describe-based),
[pgen](https://github.com/goquati/pgen) (taken), and
[sqlx4k](https://github.com/smyrgeorge/sqlx4k) (taken; KMP, compile-time
validation). `oracle` was ruled out on trademark grounds despite fitting §1.

### Kokkiri SQL, informally

**Kokkiri SQL** (코끼리, Korean for elephant) is a nickname, not a rename. It is
a pun on the elephant every Postgres tool eventually reaches for, and it is a
better *name* than `pgdescribe` in the way names are usually better than
descriptions — it is memorable and it is not a spec.

It stays informal on the criterion that decided this section in the first place:
`pgdescribe` won because it cost nothing to adopt. Kokkiri costs a lot. `pgd`
stops being an acronym and goes back to being an abbreviation of a name nobody
uses; the four module directories, the plugin id, `pgd.toml` and `PGD_URL` all
move; and the ~54 `PGD*` diagnostic codes — which §14 and §20 cite *by number*,
and which anyone's CI greps for — would renumber for no gain in what they mean.
That is a large, entirely cosmetic diff across a surface users write against.

So: `pgdescribe` and `pgd` for anything a machine reads, Kokkiri SQL for the
README, the talk title and the sticker. The two do not have to agree, and the
projects that force them to agree usually pay for it in exactly the churn above.

## 20. Custom column type mapping: decided against, with a measurement

Prompted by [Exposed's `TypeMapper`](https://www.jetbrains.com/help/exposed/custom-type-mapping.html)
and by the observation that SqlDelight's `email TEXT AS CustomerEmail` is not
valid SQL and only survives on pure table results. Both are true; neither
approach is the one to copy.

### The measurement that reframed it

The obvious answer is "Postgres already has the feature SqlDelight had to
invent" — `CREATE DOMAIN email AS text` is a distinct type, declared in a
migration, enforced by the server, valid SQL. Under decision #1 the oracle
should just report it.

It does not. Measured against a real server (`DomainErasureProbeTest`):

| Shape | Reported type |
|---|---|
| `SELECT addr FROM person` | `text` |
| aliased, through a subquery, through a CTE | `text` |
| `SELECT min(addr) FROM person` | `text` |
| `SELECT (upper(addr))::email FROM person` | `text` |
| `SELECT array_agg(addr) FROM person` | `_email` |
| parameter: `INSERT INTO person (addr) VALUES ($1)` | `email` |
| parameter: `... WHERE addr = $1::email` | `email` |
| parameter: `... WHERE addr = $1` | `text` |

**Postgres erases domains in `RowDescription` and keeps them in
`ParameterDescription`.** Casting explicitly back to `::email` does not restore
it. So the oracle cannot tell us a result column is an `email`, which is exactly
where a custom type is wanted, and no amount of work on our side recovers it —
the information is not on the wire. Array-of-domain does survive, which is
incidentally why `TypeRegistry`'s domain branch earns its keep.

`DomainErasureProbeTest` pins this the way `PgJdbcNullabilityProbeTest` pins the
nullability asymmetry: if Postgres ever reports the domain OID in
`RowDescription`, that test fails and this decision reopens.

### Why not Exposed's shape

A runtime SPI registry: implementations discovered by `ServiceLoader`, scoped by
dialect and column type, priority-ordered, `setValue` returning `Boolean` to
defer down the chain. Resolution happens at execution time.

That breaks decision #4 outright — it *is* a runtime library. Worse, it is the
wrong shape for §0: an out-of-band registry maximises what a model must know and
minimises what it can check. A missing or mis-prioritised mapper is a runtime
failure, and reading a query tells you nothing about the type you will get. It
is also currently R2DBC-only and documented outbound-only, so adopting it would
have entangled this with §17.

### Why not SqlDelight's shape

Two objections beyond "not valid SQL", which alone is disqualifying under
decision #6 and would require the grammar decision #2 deleted:

- The adapters are threaded in at construction (`Customer.Adapter(emailAdapter =
  …)`), so the *type* is compile-time but the *conversion* is runtime-injected
  and unverifiable.
- The type is lost through expressions **silently**. `SELECT upper(email)` hands
  back a plain `String` with no diagnostic — precisely the quiet wrongness this
  project exists to remove.

Its genuine advantage is declare-once-at-the-column ergonomics, which is worth
matching if it can be had cheaply.

### It can, and §18 already did it

The mapper overload is the column adapter, written in Kotlin:

```kotlin
fun toUser(id: Long, email: String, name: String?, at: OffsetDateTime) =
    User(UserId(id), Email(email), name, at)

val u = connection.findUserByEmail("ada@example.com", ::toUser)
```

Declared once, reused everywhere, no grammar, no config, no runtime registry.
The function reference *is* the adapter. It works on expressions, ad-hoc queries
and joins — where `AS` degrades silently — because it is keyed on the query's
actual described shape rather than on a table column. And it fails at compile
time when the schema moves, which is the §0 property both alternatives lack.

**Decision: build no mapping layer.**

### If this is revisited

Prefer a `-- as:` header directive over config. It stays inside a comment, so
the file still runs in psql (decision #6 intact), it is per-query so it works on
expressions, and it can be validated at generate time against the real described
columns, reusing PGD1007. Reject the tempting alternative — a `[columns]
"users.email" = "..."` table in `pgd.toml`, keyed on the `baseTable`/
`baseColumn` pgjdbc already reports for nullability — because it resolves only
for direct column references and so reproduces SqlDelight's limitation exactly,
merely relocated out of the grammar.

The one real gap is `:copy`, which has no mapper overload: its row class is
fixed, so bulk-loading a custom type means mapping to the generated row first.
That is where a declared mapping would first pay for itself, and it is the same
place §18 declined to add an extractor. If custom types become a recurring
theme, start there.

## 21. Schema changes and rolling deploys: checking the off-diagonal

Deriving everything from the schema in one shot makes a coupling visible that
every database-backed application has and most tools leave implicit: the
generated code and the schema are two artefacts that get deployed separately,
and for the length of a rolling deploy they are at *different versions*.

`check` and `generate` read `migrations/` and `queries/` from the same working
tree. So a green build proves exactly one cell:

|              | old schema                 | new schema                  |
|--------------|----------------------------|-----------------------------|
| **old code** | verified by the last build | **the rolling window**      |
| **new code** | the rollback window        | what `pgd check` proves     |

The two off-diagonal cells are the ones that cause downtime, and neither is on
any build's path. During a rolling deploy of two instances, both instances *are*
the top-right cell until the last one is replaced.

The generated code makes the failure sharp rather than gradual. The SQL is a
frozen `const` string and the reads are positional:

```kotlin
private const val FIND_ACTIVE_USERS: String =
    "SELECT u.id, u.email, u.display_name, o.total_cents\n" + ...
resultSet.getString(3)
```

Drop `display_name` and every still-running instance throws `42703` on that
query until it is replaced. Not degraded — dead. This is not a defect of
deriving from the schema; hand-written SQL fails identically. What deriving
changes is that the build *looks* like it covered the question, and does not.

### The protocol: two releases

The rule is that **a migration must be backward-compatible with the code
currently deployed.** For anything on the contract side — dropping a column or
a table, narrowing a type, adding a `NOT NULL` — that forces two deploys.

**Release 1, code only, no migration.** Remove every reference from
`queries/`. `generate` drops the field from the row class, and the compiler
finds every call site and every hand-written §18 mapper. Deploy; let both
instances roll.

**Release 2, migration only.** Add the `DROP COLUMN`, run `generate`, and read
the diff: **the generated Kotlin should be byte-for-byte unchanged**, with only
`schema.md` and `schema.json` moving. That empty diff is a mechanical proof that
nothing in the deployed query set read the column. A non-empty Kotlin diff means
release 1 did not finish, and release 2 is not safe to ship.

The proof only holds because generation is deterministic, which is why the
example pins `nullability = "conservative"` (§4). It is a nice dividend from a
property that was adopted for a different reason.

Collapsing the two releases into one also destroys the rollback: release N-1's
code references a column that no longer exists, so going back needs a data
restore rather than a redeploy. Expand/contract buys safety in both directions.

Dropping a *table* is the same shape, one step safer: the query files must go
too, and forgetting fails `pgd check` with `42P01` at build time rather than at
runtime. Half of that case was already covered.

### What shipped: `--queries`

`CheckConfig` already took `migrationsDir` and `queriesDir` separately — the
split existed for `pgd.toml`'s `migrations` / `queries` keys and nothing was
using it interestingly. Exposing it on the CLI turns it into the missing check:

```bash
git worktree add ../deployed "$DEPLOYED_TAG"
pgd check --dir db --queries ../deployed/db/queries
```

New migrations, previously-deployed queries. Measured on the example, with
`display_name` dropped in a new `V005` and the deployed queries beside it:

```
Applied 5 migration(s) to pgd_check_dd3ed4db5d8f2c0
deployed/queries/users.sql:3:23:  error [PGD1001] FindActiveUsers: column u.display_name does not exist
deployed/queries/users.sql:11:19: error [PGD1001] FindUserByEmail: column "display_name" does not exist
deployed/queries/users.sql:17:27: error [PGD1001] InsertUser: column "display_name" of relation "users" does not exist
Checked 12 queries against 5 migration(s): 3 error(s), 0 warning(s).
```

Every diagnostic already carries a file, line and column, so pointing the flag
at a worktree produces errors located in *that* checkout — usable output without
any new reporting. Paths are relativised against the project's parent, so a
worktree beside the repo reads best; one under `/tmp` prints a long climb.

`CheckRunnerTest.queries and migrations can come from different revisions` pins
it, and pairs the failing run with a control against the schema those instances
are actually on, so the break is attributed to the migration and not to the
query.

This is a capability the SqlDelight shape structurally cannot have. Describing
last release's queries against next release's schema requires a server that can
hold both; a hand-rolled grammar has only whatever the current tree says. It is
decision #1 paying out somewhere it was not aimed.

The flag applies to `generate` as well, because it is plumbing on `CheckConfig`
rather than a check-only path. That combination writes Kotlin for the deployed
query set, which is almost never wanted; it is documented as a `check` flag and
left unguarded rather than special-cased.

### Not done, deliberately

**`--migrations`, the mirror cell.** New code against old schema — the case
where the app deploys ahead of the migration. It is the same three lines. It was
left out because the ask was the rolling window, and because the two flags
together invite a `pgd compat --since <ref>` that should be designed once rather
than accreted. The moment someone deploys code before migrating, add it.

**A Gradle equivalent.** This is a CLI-only flag; the plugin has no
`pgdCheckDeployed` task. That is the right split for now — the check belongs in
CI next to the worktree checkout, not in the same graph as `compileKotlin` —
but it means plugin-only projects need the CLI to run it.

**`SELECT *`.** The one place this fails *silently*. The SQL string is frozen
but `*` re-expands at runtime, so dropping a mid-table column shifts every
position after it and `getLong(4)` starts reading a different column —
misparsed if you are lucky, plausible garbage if you are not. `ADD COLUMN`
appends and is safe; `DROP COLUMN` is not. A warning diagnostic on a described
statement whose text contains a bare `*` in the target list is the fix, and it
needs the star-expansion machinery §15 avoided.

**Runtime hazards that are not about code generation**, recorded so they are not
mistaken for tool problems. `ALTER TABLE ... DROP COLUMN` is metadata-only and
fast, but takes `ACCESS EXCLUSIVE`: it queues behind a long-running reader and
then blocks everything behind it, so `lock_timeout` plus retry is the standard
mitigation. Separately, a type-changing migration can make pooled connections
raise `0A000 cached plan must not change result type` even when the new code is
correct; cycle the pool after migrating, or set pgjdbc's `prepareThreshold=0`.
Neither belongs in pgd, but both belong in whatever `docs/` eventually says
about deploying.
