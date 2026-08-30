package io.pgdescribe.gradle

import io.pgdescribe.core.EmbeddedPostgresServer
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome

class PgdPluginTest {

    private val server = EmbeddedPostgresServer.start()
    private val url: String get() = server.urlFor("postgres")

    @AfterTest
    fun tearDown() {
        runCatching { server.close() }
    }

    private lateinit var root: File

    private fun project(
        buildFileExtra: String = "",
        plugins: String = """id("io.pgdescribe")""",
        query: String = "-- name: FindUser :one\n-- params: id\nSELECT id, email FROM users WHERE id = $1::bigint;\n",
    ): File {
        root = Files.createTempDirectory("pgd-plugin").toFile()
        File(root, "settings.gradle.kts").writeText(
            """
            pluginManagement { repositories { gradlePluginPortal() } }
            dependencyResolutionManagement { repositories { mavenCentral() } }
            rootProject.name = "fixture"
            """.trimIndent(),
        )
        File(root, "build.gradle.kts").writeText(
            """
            plugins {
                $plugins
            }

            pgd {
                packageName.set("test.db")
                url.set("$url")
            }

            $buildFileExtra
            """.trimIndent(),
        )
        File(root, "db/migrations").mkdirs()
        File(root, "db/queries").mkdirs()
        File(root, "db/migrations/V001__users.sql").writeText(
            """
            CREATE TABLE users (
                id    bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                email text NOT NULL
            );
            """.trimIndent(),
        )
        File(root, "db/queries/users.sql").writeText(query)
        return root
    }

    private fun run(vararg args: String): BuildResult =
        runner(*args).build()

    private fun runAndFail(vararg args: String): BuildResult =
        runner(*args).buildAndFail()

    private fun runner(vararg args: String): GradleRunner = GradleRunner.create()
        .withProjectDir(root)
        .withPluginClasspath()
        .withArguments(*args, "--stacktrace")
        .forwardOutput()

    private fun outcome(result: BuildResult, task: String): TaskOutcome? = result.task(task)?.outcome

    @Test
    fun `generate writes Kotlin and the schema snapshot`() {
        project()
        val result = run("pgdGenerate")

        assertEquals(TaskOutcome.SUCCESS, outcome(result, ":pgdGenerate"))
        val generated = File(root, "build/generated/pgd/kotlin/test/db/Users.kt")
        assertTrue(generated.isFile, "expected generated file at ${generated.path}")
        assertTrue("package test.db" in generated.readText())
        assertTrue(File(root, "db/schema.md").isFile)
        assertTrue(File(root, "db/schema.json").isFile)
    }

    @Test
    fun `a second run with nothing changed is up to date`() {
        project()
        run("pgdGenerate")
        assertEquals(TaskOutcome.UP_TO_DATE, outcome(run("pgdGenerate"), ":pgdGenerate"))
    }

    @Test
    fun `changing a migration re-runs generation`() {
        project()
        run("pgdGenerate")
        File(root, "db/migrations/V002__add_column.sql")
            .writeText("ALTER TABLE users ADD COLUMN nickname text;")

        assertEquals(TaskOutcome.SUCCESS, outcome(run("pgdGenerate"), ":pgdGenerate"))
    }

    @Test
    fun `changing a query re-runs generation`() {
        project()
        run("pgdGenerate")
        File(root, "db/queries/users.sql").appendText("\n-- name: AllEmails :many\nSELECT email FROM users;\n")

        assertEquals(TaskOutcome.SUCCESS, outcome(run("pgdGenerate"), ":pgdGenerate"))
        assertTrue("fun Connection.allEmails()" in File(root, "build/generated/pgd/kotlin/test/db/Users.kt").readText())
    }

    @Test
    fun `an unrelated file in the project directory does not invalidate the task`() {
        project()
        run("pgdGenerate")
        // schema.md lives in db/, so db/ itself must not be a declared input.
        File(root, "db/notes.md").writeText("scratch notes")

        assertEquals(TaskOutcome.UP_TO_DATE, outcome(run("pgdGenerate"), ":pgdGenerate"))
    }

    @Test
    fun `deleting a query removes its generated file`() {
        project()
        File(root, "db/queries/extra.sql").writeText("-- name: AllEmails :many\nSELECT email FROM users;\n")
        run("pgdGenerate")
        assertTrue(File(root, "build/generated/pgd/kotlin/test/db/Extra.kt").isFile)

        File(root, "db/queries/extra.sql").delete()
        run("pgdGenerate")
        assertFalse(
            File(root, "build/generated/pgd/kotlin/test/db/Extra.kt").exists(),
            "stale output survived a query file being deleted",
        )
    }

    @Test
    fun `output is restored from the build cache`() {
        project()
        run("pgdGenerate", "--build-cache")
        File(root, "build/generated").deleteRecursively()
        File(root, "db/schema.md").delete()
        File(root, "db/schema.json").delete()

        val result = run("pgdGenerate", "--build-cache")
        assertEquals(TaskOutcome.FROM_CACHE, outcome(result, ":pgdGenerate"))
        assertTrue(File(root, "build/generated/pgd/kotlin/test/db/Users.kt").isFile)
        assertTrue(File(root, "db/schema.md").isFile)
    }

    @Test
    fun `a broken query fails the build with the diagnostic`() {
        project(query = "-- name: FindUser :one\nSELECT emial FROM users;\n")
        val result = runAndFail("pgdGenerate")

        assertEquals(TaskOutcome.FAILED, outcome(result, ":pgdGenerate"))
        assertTrue("PGD1001" in result.output, result.output)
        assertTrue("emial" in result.output, result.output)
        assertFalse(File(root, "build/generated/pgd/kotlin/test/db/Users.kt").exists())
    }

    @Test
    fun `check verifies without generating and is up to date on a second run`() {
        project()
        val first = run("pgdCheck")
        assertEquals(TaskOutcome.SUCCESS, outcome(first, ":pgdCheck"))
        assertTrue(File(root, "build/reports/pgd/check.txt").isFile)
        assertFalse(File(root, "build/generated/pgd/kotlin").exists())

        assertEquals(TaskOutcome.UP_TO_DATE, outcome(run("pgdCheck"), ":pgdCheck"))
    }

    @Test
    fun `generated code is compiled by the Kotlin plugin`() {
        project(
            plugins = """
                kotlin("jvm") version "2.3.21"
                id("io.pgdescribe")
            """.trimIndent(),
            buildFileExtra = """dependencies { implementation("org.postgresql:postgresql:42.7.13") }""",
        )
        val result = run("compileKotlin")

        assertEquals(TaskOutcome.SUCCESS, outcome(result, ":pgdGenerate"))
        assertEquals(TaskOutcome.SUCCESS, outcome(result, ":compileKotlin"))
        assertTrue(
            File(root, "build/classes/kotlin/main/test/db/UsersKt.class").isFile,
            "generated source was not added to the main Kotlin source set",
        )
    }

    @Test
    fun `the schema snapshot can be turned off`() {
        project(buildFileExtra = "pgd { generateSchema.set(false) }")
        run("pgdGenerate")

        assertFalse(File(root, "db/schema.md").exists())
        assertFalse(File(root, "db/schema.json").exists())
        assertTrue(File(root, "build/generated/pgd/kotlin/test/db/Users.kt").isFile)
        assertEquals(TaskOutcome.UP_TO_DATE, outcome(run("pgdGenerate"), ":pgdGenerate"))
    }

    @Test
    fun `check is wired into the lifecycle check task`() {
        project(
            plugins = """
                base
                id("io.pgdescribe")
            """.trimIndent(),
        )
        assertEquals(TaskOutcome.SUCCESS, outcome(run("check"), ":pgdCheck"))
    }
}
