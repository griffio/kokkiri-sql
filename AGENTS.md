# Working in this repo

`pgd` verifies PostgreSQL queries against your migrations by asking Postgres,
not by parsing SQL. If Postgres accepts it, `pgd` accepts it.

## Before you finish any change touching db/

```bash
pgd check --dir db          # verify only
pgd generate --dir db       # check, then write Kotlin and schema.md
pgd schema --dir db         # refresh schema.md / schema.json only
```

Settings live in `db/pgd.toml` (package, output directory, type aliases), so
`generate` usually needs no flags.

In a Gradle build the same thing is `./gradlew pgdGenerate` (or just
`compileKotlin`, which depends on it) and `./gradlew pgdCheck`. Generated code
lands in `build/generated/pgd/kotlin` and is already on the main Kotlin source
set — do not commit it, and do not add the directory by hand.

`generate` checks first and writes nothing unless the whole project is clean, so
a half-generated source tree is never left behind. Never hand-edit generated
files; change the query or the migration and regenerate.

Exit code 0 means clean. Fix every error before reporting the work done; the
whole point of this tool is that a stale column name is a build failure rather
than a runtime surprise.

## Writing a query

One file per aggregate, any number of named statements per file.

```sql
-- name: FindUserByEmail :one
-- params: email
SELECT id, email, display_name, created_at
FROM users
WHERE email = $1::text;
```

Rules:

- The header is exactly `-- name: <Name> <tag>`. Nothing else on the line.
- `<Name>` is a Kotlin-style identifier and must be unique across the project;
  it becomes a function name.
- Tags: `:many` (0+ rows), `:one` (0 or 1 row), `:exactlyone` (exactly one row,
  returns a non-null value and throws otherwise), `:exec` (no result set),
  `:execrows` (no result set, returns the affected row count), `:copy` (bulk
  load, see below).
- `-- batch` on an `:exec` or `:execrows` query adds a second function taking
  many rows. It needs at least one parameter, and does not work on queries that
  return rows.
- Parameters are native `$1`, `$2`, … in order with no gaps. Keep them native so
  the file stays runnable in `psql`.
- `-- params:` names them positionally, for generated function signatures. If
  you write it, it must name exactly as many as the statement uses.
- Cast a placeholder (`$1::uuid`) whenever Postgres cannot infer its type. The
  error will tell you when this is needed.
- `-- nullable:` / `-- notnull:` override inferred column nullability. Reach for
  these only when the checker is wrong; prefer fixing the query.

Directives are only read in the comment block directly after the header. Once
the statement body starts, `--` lines are ordinary SQL comments.

## Bulk loading

```sql
-- name: BulkLoadEvents :copy
COPY events (user_id, name, note, feeling) FROM STDIN;
```

The column list must be explicit and the source must be `STDIN`. Every column
becomes a field of the generated row class, non-null when the column is
`NOT NULL`. Arrays cannot be written in COPY text format — use `:exec` with
`-- batch` for those.

## Reading the schema

`db/schema.md` is the current schema — tables, columns, nullability, defaults,
keys, enums, domains. Read it instead of replaying the migrations. It is
generated, so if it looks stale run `pgd schema --dir db`.

## Changing the schema

Add a migration; never edit an applied one, and never keep a separate DDL file.
`db/migrations/V<version>__<description>.sql`, applied in numeric order
(`V002` before `V010`). Each file is sent whole, so multiple statements and
dollar-quoted function bodies are fine.

### Removing a column or a table

A migration must be backward-compatible with the code that is already deployed,
because during a rolling deploy the old instances are running against the new
schema. Split the change into two releases: first one that removes every
reference from `queries/`, with no migration; then one that adds the `DROP` on
its own. In the second, `generate` should leave the Kotlin byte-for-byte
unchanged and move only `schema.md` and `schema.json` — a non-empty Kotlin diff
means the first release missed something and the drop is not safe yet.

To check that rather than trust it, describe the deployed queries against the
new schema:

```bash
pgd check --dir db --queries ../deployed/db/queries   # a worktree of the deployed tag
```

## Diagnostic codes

| Code | Meaning |
|---|---|
| PGD1001 | Postgres rejected the statement. The message and hint are Postgres' own. |
| PGD1002 | The cardinality tag disagrees with what the statement returns. |
| PGD1003 | Postgres could not infer a parameter's type. Add a cast. |
| PGD1004 | `-- params:` names a different number of parameters than the statement uses. |
| PGD1005 | Placeholders skip a number (`$1` and `$3` but no `$2`). |
| PGD1006 | One `$n` used twice, inferred as two different types. Cast both. |
| PGD1007 | A `-- nullable:`/`-- notnull:` override names a column the query does not return. |
| PGD1008 | `-- batch` on a statement that returns rows, or takes no parameters. |
| PGD1009 | A `:copy` statement is not `COPY table (columns) FROM STDIN`, or copies a type COPY text cannot carry. |
| PGD2001-2005 | Malformed query file: duplicate name, bad header, unknown tag, SQL with no header, empty body. |
| PGD3001-3003 | A migration failed, or a migrations/queries directory is missing or empty. |
| PGD4001 | No Kotlin type for a Postgres type. Arrays, enums, domains and composites are not supported yet. |
| PGD4002 | Two result columns collapse to the same Kotlin property. Alias one. |
| PGD4003 | Two parameters collapse to the same Kotlin name. |
| PGD4004 | Two Postgres enums, or two labels, collapse to the same Kotlin name. |
| PGD5001 | `pgd.toml` is unreadable or has a bad value. |
| PGD5002 | `nullability = "precise"` but Postgres' parser is not installed. |

## What the generated code looks like

Extension functions on `java.sql.Connection`, plain `PreparedStatement` and
`ResultSet`, no runtime library. Return type follows the cardinality tag:
`:many` to `List<Row>`, `:one` to `Row?`, `:exec` to `Unit`, `:execrows` to
`Int`. A single non-null column collapses to the value itself.

Any query with a row class gets a second, generic overload taking a
`mapper: (col, col, ...) -> T`. That overload holds the body; the `Row` form
delegates to it with `::Row`. Use it to build your own class — `@Serializable`,
`internal`, whatever — without an intermediate allocation. Arguments are
positional, in column order, because Kotlin forbids named arguments on a
function type. Queries that collapse to a scalar, and `:exec`/`:execrows`/
`:copy`, have no row to map and get no overload.

A column is typed non-null only when it passes straight through from a NOT NULL
base column and nothing in the statement can null it. If a type looks too
pessimistic, fix it with `-- notnull:` rather than casting around it.

How precisely that is decided is set by `nullability` in `pgd.toml`:
`conservative` demotes every column in any statement containing an outer join or
`ROLLUP`/`CUBE`/`GROUPING SETS`; `precise` uses Postgres' own parser and demotes
only the relations actually on a nullable side; `auto` (the default) uses the
parser when it is installed. `precise` needs `brew install libpg_query` and
Java 22+.

Enums become Kotlin `enum class`es in `Enums.kt`, domains resolve to their base
type, and arrays become `List<T?>` — elements are nullable because a Postgres
array may hold NULL in any slot. A type with no mapping is a PGD4001 error;
either cast it in the query or alias it in `pgd.toml` under `[types]`.

## Repo layout

- `pgd-core` — analyzer and code generator.
- `pgd-cli` — the `pgd` command.
- `pgd-native` — optional libpg_query bindings. Its `src/ffm` source set is
  compiled for Java 22; `src/main` stays at 17 and reaches it by name.
- `pgd-gradle` — the `io.pgdescribe` Gradle plugin. Its TestKit tests run
  real builds; they are the ones that prove incremental and build-cache
  behaviour, so run them after touching task inputs or outputs.
- `example` — a project that checks clean, whose generated code is committed and
  compiled by `./gradlew build`. `GeneratedCodeTest` fails if it drifts, so
  regenerate it after changing the generator.
- `docs/PLAN.md` — the design and the locked decisions. Read before proposing
  architecture changes.
