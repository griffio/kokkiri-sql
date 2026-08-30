# pgdescribe

```
    .-"-.             .-"-.
   /     \___________/     \
  |        o       o        |
   \         _____         /
    '-.____ (     ) ____.-'
          /  \   /  \
         '    | |    '
              | | 
              \ \/|
               \ \/
                 
```

Informally, **Kokkiri SQL** — 코끼리, Korean for elephant. `pgdescribe` and `pgd`
are what the build, the config file and the error codes answer to.

The command is `pgd` — **p**ost**g**res **d**escribe — which is the whole
mechanism in three letters. A Postgres-only, AI-first alternative to SqlDelight.
There is no SQL grammar:
your migrations are applied to a real Postgres, and every query is described by
the server itself. Whatever Postgres accepts is what the tool accepts.

**Status: M7, partly.** CLI and Gradle plugin, enums, domains, arrays and
jsonb, per-column nullability proved with Postgres' own parser, plus `COPY`
bulk loading, JDBC batching and exact-cardinality queries. The R2DBC target is
still an open decision — see [docs/PLAN.md](docs/PLAN.md) §17.

## Quick start

```bash
./gradlew :pgd-cli:installDist
pgd-cli/build/install/pgd/bin/pgd check --dir example/db
pgd-cli/build/install/pgd/bin/pgd generate --dir example/db
```

A project looks like this:

```
db/
  migrations/V001__users.sql     plain SQL, Flyway naming, the only schema truth
  queries/users.sql              one or more named statements
  pgd.toml                       optional settings
  schema.md                      generated summary of the current schema
  schema.json                    the same, machine-readable
```

```sql
-- name: FindActiveUsers :many
-- params: since
SELECT u.id, u.email, o.total_cents
FROM users u
LEFT JOIN orders o ON o.user_id = u.id
WHERE u.active AND u.created_at > $1::timestamptz;
```

Queries keep native `$1` placeholders, so any file here can be pasted straight
into `psql`.

## What check reports

```
db/queries/users.sql:4:8: error [PGD1001] FindActiveUsers: column u.emial does not exist
  hint:   Perhaps you meant to reference the column "u.email".
db/queries/users.sql:10: error [PGD1002] RenameUser: Query 'RenameUser' is declared :one but returns no columns.
  hint:   Add a RETURNING clause, or change the tag to :exec or :execrows.
```

`--format json` emits the same thing as structured data. Exit code is 0 for
clean, 1 for errors, 2 for bad usage.

### Checking a migration against code that is already deployed

`check` normally reads migrations and queries from the same commit, so a green
run proves *new code against new schema* — and says nothing about the instances
still running the previous release. During a rolling deploy those instances are
old code against new schema, and a dropped column takes them down.

`--queries` points the two halves at different revisions:

```bash
git worktree add ../deployed "$DEPLOYED_TAG"
pgd check --dir db --queries ../deployed/db/queries
```

That applies the *new* migrations and describes the *deployed* queries against
them, so a migration that would break running code fails the build:

```
../deployed/db/queries/users.sql:3:23: error [PGD1001] FindActiveUsers: column u.display_name does not exist
```

The way out is the usual two-release split. First ship a release that removes
every reference to the column, with no migration; `generate` drops the field
from the row class and the compiler finds the call sites. Then ship the
migration on its own — at which point `generate` should leave the Kotlin
byte-for-byte unchanged, and only `schema.md` and `schema.json` move. That empty
diff is the proof that nothing deployed still reads the column.

## What generate produces

Plain Kotlin against `java.sql`. No runtime library, no driver abstraction,
nothing to learn — extension functions on `java.sql.Connection`:

```kotlin
data class FindUserByEmailRow(
    val id: Long,
    val email: String,
    val displayName: String?,
    val createdAt: OffsetDateTime,
)

/**
 * ```sql
 * SELECT id, email, display_name, created_at FROM users WHERE email = $1::text
 * ```
 */
fun Connection.findUserByEmail(email: String): FindUserByEmailRow? = ...
```

The original SQL is inlined as KDoc, so the generated file doubles as an
always-current summary of the schema and the query set.

Cardinality decides the return type:

| Tag | Returns |
|---|---|
| `:many` | `List<Row>` |
| `:one` | `Row?` — throws if a second row matches |
| `:exactlyone` | `Row` — throws if there is no row, or more than one |
| `:exec` | `Unit` |
| `:execrows` | `Int`, the affected row count |
| `:copy` | `Long`, the number of rows bulk loaded |

A single non-null column collapses to the value itself, so
`SELECT name FROM users` returns `List<String>` rather than a wrapper. The one
exception is `:one` over a nullable column, where collapsing would conflate "no
row matched" with "the value was NULL"; that keeps its wrapper.

### Your own row class

Every query with a row class also gets a mapper overload, and the mapper form is
the one that does the work — the `Row` form is a one-line delegate to it:

```kotlin
fun <T : Any> Connection.findUserByEmail(
    email: String,
    mapper: (
        id: Long,
        email: String,
        displayName: String?,
        createdAt: OffsetDateTime,
    ) -> T,
): T? = ...

/** Maps each row to [FindUserByEmailRow]. */
fun Connection.findUserByEmail(email: String): FindUserByEmailRow? =
    findUserByEmail(email, ::FindUserByEmailRow)
```

So a class the generator will never emit — `@Serializable`, `internal`, a
`value class` field, a constructor that does work — is built straight off the
`ResultSet` with no throwaway row in between:

```kotlin
@Serializable
internal data class User(val id: Long, val email: String, val name: String?)

val users = connection.findUserByEmail("ada@example.com") { id, email, name, _ ->
    User(id, email, name)
}
```

The mapper's parameter list is a typed contract against the query. Add a column,
change one to nullable, and every hand-written mapper stops compiling at the
call site rather than failing at runtime — the same feedback loop the row class
gives you, extended to classes pgd does not own.

Arguments are positional, because Kotlin does not allow named arguments when
invoking a function type. The parameter names are still there for the IDE, and
`Row` stays as the worked example of the shape a mapper has to match.

### Bulk loading and batching

`:copy` generates a streaming loader over `COPY ... FROM STDIN`:

```sql
-- name: BulkLoadEvents :copy
COPY events (user_id, name, note, feeling) FROM STDIN;
```

```kotlin
val loaded: Long = connection.bulkLoadEvents(rows)
```

COPY cannot be prepared, so pgd validates it by describing
`SELECT <columns> FROM <table> WHERE false` instead — the table and every column
name are still checked by Postgres, and the column types and `NOT NULL`
constraints come back with them. Rows stream out rather than being buffered, and
a failure mid-load cancels the copy so the connection is not left stuck. Arrays
are not supported in COPY text format and are a build error.

`-- batch` adds a second entry point to any `:exec` or `:execrows` query:

```sql
-- name: AnnotateEvent :execrows
-- params: id, note
-- batch
UPDATE events SET note = $2::text WHERE id = $1::bigint;
```

```kotlin
val counts: IntArray = connection.annotateEventBatch(rows)
```

### Nullability

This is the part that has to be right, because a `LEFT JOIN` column typed
non-null is exactly the silent bug the tool exists to prevent.

Postgres does not report result nullability over the wire, so it has to be
derived. A column is typed non-null when it passes straight through from a
`NOT NULL` base column and nothing in the statement can null it. Override per
column with `-- notnull:` / `-- nullable:` when you know better.

How precisely "nothing can null it" is decided depends on whether Postgres' own
parser is available:

| Mode | Behaviour |
|---|---|
| `precise` | Requires libpg_query. Only relations actually on the nullable side of an outer join are demoted. Fails the run if the parser is missing. |
| `auto` (default) | `precise` when libpg_query is installed, `conservative` when it is not. |
| `conservative` | Never uses the parser. Any outer join, `ROLLUP`, `CUBE` or `GROUPING SETS` anywhere in the statement demotes every column. |

```sql
SELECT u.id, u.email, o.total_cents
FROM users u LEFT JOIN orders o ON o.user_id = u.id
```

`conservative` types all three nullable. `precise` gives `id: Long`,
`email: String`, `total_cents: Int?`. Both are wrong only in the safe direction;
`precise` is just less pessimistic.

A self-join with one outer arm (`FROM users a LEFT JOIN users b`) stays
conservative even in `precise` mode: both columns come from `users`, so neither
can be proven.

Set the mode in `pgd.toml`:

```toml
nullability = "auto"
```

**Pin it if a team shares the generated code.** Under `auto`, output depends on
whether libpg_query happens to be installed, so two machines can produce
different types from the same inputs.

### Postgres' parser

`pgd-native` binds [libpg_query](https://github.com/pganalyze/libpg_query) — the
real Postgres parser as a C library — through Java's Foreign Function & Memory
API. It is entirely optional: without it, `auto` falls back and the run still
succeeds.

```bash
brew install libpg_query      # or your platform's package
```

It needs **Java 22 or newer** (where FFM was finalised) and the shared library
on disk. pgd looks in `java.library.path`, then the usual system directories;
`PGD_LIBPG_QUERY` (or `-Dpgd.libpgquery.path=`) points it somewhere specific.
`PGD_NO_NATIVE=1` turns it off.

On Java 24+ the JVM warns that a restricted method was called. Silence it with
`PGD_OPTS="--enable-native-access=ALL-UNNAMED"` — it cannot go in the launcher
itself, because the flag does not exist on Java 17, which pgd still runs on.

### Types

| Postgres | Kotlin |
|---|---|
| `int2` / `int4` / `int8` (and `serial` spellings) | `Short` / `Int` / `Long` |
| `text`, `varchar`, `bpchar`, `json`, `jsonb`, `xml` | `String` |
| `bool` | `Boolean` |
| `numeric` | `BigDecimal` |
| `float4` / `float8` | `Float` / `Double` |
| `bytea` | `ByteArray` |
| `uuid` | `java.util.UUID` |
| `date` / `time` / `timetz` | `LocalDate` / `LocalTime` / `OffsetTime` |
| `timestamp` | `LocalDateTime` |
| `timestamptz` | `OffsetDateTime` |
| enum types | a generated Kotlin `enum class` |
| domains | their base type |
| arrays | `List<T?>` |

`timestamptz` maps to `OffsetDateTime`, never `LocalDateTime` — it is an
instant, and mapping it to a local type is one of the silent failures this tool
exists to stop. Array elements are `T?` because a Postgres array may hold NULL
in any slot regardless of the column's own nullability.

Anything else — composites, ranges, extension types — is a build error, never a
silent `String`. Map it in `pgd.toml`.

## pgd.toml

Optional, read from the project directory. Command-line flags win over it, and
paths in it are relative to the file.

```toml
package = "com.example.db"
output  = "../src/main/kotlin"

[types]
# Treat a Postgres type as another Postgres type. For extension types pgd does
# not know, or to opt an enum out of getting its own Kotlin class.
interval = "text"
mood     = "text"
```

## schema.md

`generate` and `schema` write a `schema.md` and `schema.json` snapshot of the
current schema — tables, columns, nullability, defaults, keys, enums, domains.
It exists so a session reads one current file instead of replaying every
migration to work out what the schema is now.

## Gradle

```kotlin
plugins {
    kotlin("jvm")
    id("io.pgdescribe")
}

pgd {
    directory.set(layout.projectDirectory.dir("db"))   // default
    packageName.set("com.example.db")                  // or set it in pgd.toml
}
```

`pgdGenerate` writes Kotlin into `build/generated/pgd/kotlin` and adds it to the
main Kotlin source set, so `compileKotlin` just works. `pgdCheck` verifies
without generating and is wired into the lifecycle `check` task.

Both tasks are `@CacheableTask` and properly incremental: they re-run when a
migration, a query or `pgd.toml` changes, and not when anything else in the
project directory does. `pgdGenerate` clears its output directory first, so
deleting a query removes its generated file rather than leaving a stale one.

The JDBC URL is deliberately *not* a task input — which server ran the analysis
does not change the result, so switching `PGD_URL` will not invalidate anything.

## Where the database comes from

By default an embedded Postgres 18 (Zonky's pinned binaries) — no Docker daemon
needed. Point at an already-running server with `--url` or `PGD_URL` to skip the
start-up cost:

| Provider | Cold run on the example |
|---|---|
| embedded (default) | ~2.0s |
| `--url` (running server) | ~0.44s |

Either way, migrations are applied to a freshly created scratch database that is
dropped on the way out. The database named in your URL is never migrated into.

### Template caching

On a server whose databases outlive the process, the migrated schema is kept as
a template database keyed by a hash of the migrations, and each run clones from
it instead of replaying every migration. With 120 migrations that takes a run
from ~1.54s to ~0.73s, and the saving grows with the migration count.

The template is built under a temporary name and renamed into place, so a run
that dies midway leaves a database nobody will match on rather than a corrupt
cache entry. `pgd clean --url <jdbc>` drops every template and scratch database
pgd has created on a server.

## Building

Built with JDK 25, emits Java 17 bytecode, so `pgd` runs on any JDK 17+.

```bash
./gradlew build
```
