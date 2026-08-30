package io.pgdescribe.core

import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CheckRunnerTest {

    private fun project(block: (migrations: Path, queries: Path) -> Unit): CheckConfig {
        val root = Files.createTempDirectory("pgd-test")
        val migrations = root.resolve("migrations").createDirectories()
        val queries = root.resolve("queries").createDirectories()
        block(migrations, queries)
        return CheckConfig.forRoot(root, TestPostgres.url)
    }

    private fun schema(migrations: Path) {
        migrations.resolve("V001__users.sql").writeText(
            """
            CREATE TABLE users (
                id           bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                email        text        NOT NULL UNIQUE,
                display_name text,
                created_at   timestamptz NOT NULL DEFAULT now()
            );
            """.trimIndent(),
        )
    }

    @Test
    fun `the bundled example checks clean`() {
        val example = findExampleDb()
        val report = CheckRunner.run(CheckConfig.forRoot(example, TestPostgres.url))
        assertTrue(report.ok, Reporters.toText(report))
        assertEquals(0, report.errorCount, Reporters.toText(report))
        assertEquals(4, report.migrationsApplied)
        assertEquals(12, report.queriesChecked)
    }

    @Test
    fun `an unknown column is reported at its real line and column`() {
        val config = project { migrations, queries ->
            schema(migrations)
            queries.resolve("users.sql").writeText(
                """
                -- name: FindUser :one
                -- params: id
                SELECT id,
                       emial
                FROM users
                WHERE id = $1::bigint
                """.trimIndent(),
            )
        }

        val report = CheckRunner.run(config)
        assertFalse(report.ok)
        val diagnostic = report.diagnostics.single { it.code == Diagnostic.SQL_ERROR }
        assertEquals("42703", diagnostic.sqlState)
        assertEquals("FindUser", diagnostic.query)
        assertEquals(4, diagnostic.line, "should point at the line holding 'emial'")
        assertEquals(8, diagnostic.column)
        assertTrue("email" in diagnostic.hint.orEmpty(), diagnostic.hint.orEmpty())
    }

    @Test
    fun `a missing table is reported`() {
        val config = project { migrations, queries ->
            schema(migrations)
            queries.resolve("q.sql").writeText("-- name: A :many\nSELECT * FROM widgets;\n")
        }
        val report = CheckRunner.run(config)
        val diagnostic = report.diagnostics.single { it.code == Diagnostic.SQL_ERROR }
        assertEquals("42P01", diagnostic.sqlState)
        assertTrue(
            "migrations directory" in diagnostic.hint.orEmpty(),
            "Postgres gives no hint for a missing relation, so ours should fill in: " + diagnostic.hint,
        )
    }

    @Test
    fun `declaring one on a statement with no result set is an error`() {
        val config = project { migrations, queries ->
            schema(migrations)
            queries.resolve("q.sql").writeText(
                "-- name: Rename :one\n-- params: id, name\n" +
                    "UPDATE users SET display_name = $2::text WHERE id = $1::bigint;\n",
            )
        }
        val report = CheckRunner.run(config)
        assertFalse(report.ok)
        val diagnostic = report.diagnostics.single { it.code == Diagnostic.CARDINALITY_MISMATCH }
        assertEquals(Severity.ERROR, diagnostic.severity)
        assertTrue("RETURNING" in diagnostic.hint.orEmpty())
    }

    @Test
    fun `declaring exec on a statement that returns rows is a warning only`() {
        val config = project { migrations, queries ->
            schema(migrations)
            queries.resolve("q.sql").writeText("-- name: A :exec\nSELECT id, email FROM users;\n")
        }
        val report = CheckRunner.run(config)
        assertTrue(report.ok)
        val diagnostic = report.diagnostics.single { it.code == Diagnostic.CARDINALITY_MISMATCH }
        assertEquals(Severity.WARNING, diagnostic.severity)
        assertEquals(1, report.warningCount)
    }

    @Test
    fun `RETURNING satisfies one`() {
        val config = project { migrations, queries ->
            schema(migrations)
            queries.resolve("q.sql").writeText(
                "-- name: InsertUser :one\n-- params: email\n" +
                    "INSERT INTO users (email) VALUES ($1::text) RETURNING id, created_at;\n",
            )
        }
        val report = CheckRunner.run(config)
        assertTrue(report.ok, Reporters.toText(report))
        assertEquals(emptyList(), report.diagnostics)
    }

    @Test
    fun `a failing migration stops the run and names the file`() {
        val config = project { migrations, _ ->
            schema(migrations)
            migrations.resolve("V002__broken.sql").writeText("ALTER TABLE users ADD COLUMN nope nosuchtype;\n")
        }
        val report = CheckRunner.run(config)
        assertFalse(report.ok)
        val diagnostic = report.diagnostics.single { it.code == Diagnostic.MIGRATION_FAILED }
        assertTrue("V002__broken.sql" in diagnostic.message, diagnostic.message)
        assertEquals(0, report.queriesChecked)
    }

    @Test
    fun `migrations run in numeric order, not lexicographic`() {
        val config = project { migrations, queries ->
            schema(migrations)
            migrations.resolve("V002__a.sql").writeText("ALTER TABLE users ADD COLUMN nickname text;\n")
            migrations.resolve("V010__b.sql").writeText("ALTER TABLE users RENAME COLUMN nickname TO handle;\n")
            queries.resolve("q.sql").writeText("-- name: A :many\nSELECT handle FROM users;\n")
        }
        val report = CheckRunner.run(config)
        assertTrue(report.ok, Reporters.toText(report))
    }

    /**
     * The rolling-deploy check. `check` and `generate` normally read migrations
     * and queries from the same commit, which only ever proves *new code against
     * new schema*. Pointing the two at different revisions proves the cell that
     * actually causes downtime: the code already deployed, against the schema the
     * next migration is about to create.
     */
    @Test
    fun `queries and migrations can come from different revisions`() {
        val deployed = Files.createTempDirectory("pgd-deployed").resolve("queries").createDirectories()
        deployed.resolve("users.sql").writeText(
            "-- name: FindUser :many\nSELECT id, display_name FROM users;\n",
        )
        val next = Files.createTempDirectory("pgd-next").resolve("migrations").createDirectories()
        schema(next)
        next.resolve("V002__drop_display_name.sql").writeText("ALTER TABLE users DROP COLUMN display_name;\n")

        val config = CheckConfig(migrationsDir = next, queriesDir = deployed, existingUrl = TestPostgres.url)
        val report = CheckRunner.run(config)

        assertFalse(report.ok, Reporters.toText(report))
        val error = report.diagnostics.single { it.severity == Severity.ERROR }
        assertEquals(Diagnostic.SQL_ERROR, error.code)
        assertTrue("display_name" in error.message, error.message)
        // Against the schema those instances are running on today the same query
        // is fine, which is what attributes the break to the migration rather
        // than to the query.
        val current = Files.createTempDirectory("pgd-current").resolve("migrations").createDirectories()
        schema(current)
        val before = CheckRunner.run(config.copy(migrationsDir = current))
        assertTrue(before.ok, Reporters.toText(before))
    }

    @Test
    fun `a missing migrations directory is an error`() {
        val root = Files.createTempDirectory("pgd-empty")
        val report = CheckRunner.run(CheckConfig.forRoot(root, TestPostgres.url))
        assertFalse(report.ok)
        assertEquals(Diagnostic.NO_MIGRATIONS, report.diagnostics.single().code)
    }

    @Test
    fun `no query files is a warning, and the migrations are still applied`() {
        val config = project { migrations, _ -> schema(migrations) }
        val report = CheckRunner.run(config)
        assertTrue(report.ok)
        assertEquals(Diagnostic.NO_QUERIES, report.diagnostics.single().code)
        assertEquals(1, report.migrationsApplied)
    }

    @Test
    fun `parse errors are reported without reaching the database`() {
        val config = project { migrations, queries ->
            schema(migrations)
            queries.resolve("q.sql").writeText("-- name: A :nope\nSELECT 1;\n")
        }
        val report = CheckRunner.run(config)
        assertFalse(report.ok)
        assertEquals(Diagnostic.UNKNOWN_CARDINALITY, report.diagnostics.single().code)
        assertEquals(0, report.queriesChecked)
    }

    @Test
    fun `the scratch database is dropped afterwards`() {
        val config = project { migrations, _ -> schema(migrations) }
        CheckRunner.run(config)
        DriverManager.getConnection(TestPostgres.url).use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT count(*) FROM pg_database WHERE datname LIKE 'pgd_check_%'").use {
                    assertTrue(it.next())
                    assertEquals(0, it.getInt(1), "scratch databases were left behind")
                }
            }
        }
    }

    @Test
    fun `the embedded provider works end to end`() {
        val config = project { migrations, queries ->
            schema(migrations)
            queries.resolve("q.sql").writeText("-- name: A :many\nSELECT id, email FROM users;\n")
        }
        val report = CheckRunner.run(config.copy(existingUrl = null))
        assertTrue(report.ok, Reporters.toText(report))
        assertEquals(1, report.queriesChecked)
    }

    private fun findExampleDb(): Path {
        var candidate: Path? = Path.of("").toAbsolutePath()
        while (candidate != null) {
            val example = candidate.resolve("example").resolve("db")
            if (example.exists()) return example
            candidate = candidate.parent
        }
        error("could not locate example/db from ${Path.of("").toAbsolutePath()}")
    }

    @Test
    fun `json output round trips`() {
        val config = project { migrations, queries ->
            schema(migrations)
            queries.resolve("q.sql").writeText("-- name: A :many\nSELECT nope FROM users;\n")
        }
        val json = Reporters.toJson(CheckRunner.run(config))
        assertTrue("\"code\": \"${Diagnostic.SQL_ERROR}\"" in json, json)
        assertNotNull(json)
    }

    @Test
    fun `precise nullability fails when Postgres' parser is not installed`() {
        // pgd-core alone has no SqlParser provider on its test classpath.
        val config = project { migrations, queries ->
            schema(migrations)
            queries.resolve("q.sql").writeText("-- name: A :many\nSELECT id FROM users;\n")
        }
        val report = CheckRunner.run(config.copy(nullability = NullabilityMode.PRECISE))

        assertFalse(report.ok)
        val diagnostic = report.diagnostics.single { it.code == Diagnostic.NATIVE_UNAVAILABLE }
        assertTrue("libpg_query" in diagnostic.hint.orEmpty(), diagnostic.hint.orEmpty())
        assertEquals(0, report.queriesChecked)
    }

    @Test
    fun `conservative nullability is the same with or without a parser installed`() {
        val config = project { migrations, queries ->
            schema(migrations)
            queries.resolve("q.sql").writeText("-- name: A :many\nSELECT id, email FROM users;\n")
        }
        val report = CheckRunner.run(config.copy(nullability = NullabilityMode.CONSERVATIVE))
        assertTrue(report.ok, Reporters.toText(report))
    }
}
