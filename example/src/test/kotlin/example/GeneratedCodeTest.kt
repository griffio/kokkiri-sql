package example

import example.db.AnnotateEventParams
import example.db.BulkLoadEventsRow
import example.db.Mood
import example.db.deactivateStaleUsers
import example.db.findActiveUsers
import example.db.findUserByEmail
import example.db.annotateEventBatch
import example.db.bulkLoadEvents
import example.db.countEvents
import example.db.eventNames
import example.db.findPreferences
import example.db.insertUser
import example.db.recordEvent
import example.db.setPreferences
import example.db.usersInMood
import io.pgdescribe.core.CodeGenConfig
import io.pgdescribe.core.EmbeddedPostgresServer
import io.pgdescribe.core.GenerateRunner
import io.pgdescribe.core.CheckConfig
import io.pgdescribe.core.Migrations
import io.pgdescribe.core.PgdConfig
import io.pgdescribe.core.SchemaWriter
import io.pgdescribe.core.ProjectAnalyzer
import io.pgdescribe.core.PostgresServer
import io.pgdescribe.core.Reporters
import io.pgdescribe.core.createScratchDatabase
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.time.OffsetDateTime
import kotlin.io.path.readText
import kotlin.io.path.walk
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GeneratedCodeTest {

    private val root: Path = Path.of("").toAbsolutePath()

    private val server: PostgresServer by lazy { EmbeddedPostgresServer.start() }

    @AfterTest
    fun tearDown() {
        runCatching { server.close() }
    }

    private fun <T> withSchema(block: (Connection) -> T): T {
        val scratch = server.createScratchDatabase()
        return try {
            scratch.connect().use { connection ->
                Migrations.applyAll(connection, Migrations.load(root.resolve("db/migrations")))
                block(connection)
            }
        } finally {
            scratch.close()
        }
    }

    @Test
    fun `insert then read back through the generated functions`() {
        withSchema { connection ->
            val inserted = connection.insertUser(email = "ada@example.com", displayName = "Ada")
            assertNotNull(inserted)
            assertTrue(inserted.id > 0)

            val found = connection.findUserByEmail("ada@example.com")
            assertNotNull(found)
            assertEquals(inserted.id, found.id)
            assertEquals("ada@example.com", found.email)
            assertEquals("Ada", found.displayName)
            assertEquals(inserted.createdAt, found.createdAt)
        }
    }

    @Test
    fun `one returns null when nothing matches`() {
        withSchema { connection ->
            assertNull(connection.findUserByEmail("nobody@example.com"))
        }
    }

    @Test
    fun `the left joined column really does come back null`() {
        withSchema { connection ->
            connection.insertUser(email = "grace@example.com", displayName = "Grace")

            val rows = connection.findActiveUsers(OffsetDateTime.now().minusDays(1))
            val row = rows.single()
            assertEquals("grace@example.com", row.email)
            // No orders exist, so the LEFT JOIN produces NULL here. The generated
            // type says Int?, which is the whole point.
            assertNull(row.totalCents)
        }
    }

    @Test
    fun `a custom class can be built straight off the result set`() {
        withSchema { connection ->
            connection.insertUser(email = "ada@example.com", displayName = "Ada")

            val users = connection.findActiveUsers(OffsetDateTime.now().minusDays(1)) {
                    id, email, displayName, _ ->
                ActiveUser(checkNotNull(id), displayName ?: email.orEmpty())
            }

            // No FindActiveUsersRow was ever allocated, and ActiveUser is
            // internal and carries annotations the generator knows nothing about.
            assertEquals(listOf("Ada"), users.map { it.label })
        }
    }

    @Test
    fun `the mapper overload also covers one`() {
        withSchema { connection ->
            connection.insertUser(email = "ada@example.com", displayName = "Ada")

            val user = connection.findUserByEmail("ada@example.com") { id, email, _, _ ->
                ActiveUser(id, email)
            }

            assertEquals("ada@example.com", assertNotNull(user).label)
        }
    }

    @Test
    fun `execrows returns the affected count`() {
        withSchema { connection ->
            connection.insertUser(email = "a@example.com", displayName = "A")
            connection.insertUser(email = "b@example.com", displayName = "B")

            assertEquals(0, connection.deactivateStaleUsers(OffsetDateTime.now().minusYears(1)))
            assertEquals(2, connection.deactivateStaleUsers(OffsetDateTime.now().plusDays(1)))
            assertEquals(emptyList(), connection.findActiveUsers(OffsetDateTime.now().minusDays(1)))
        }
    }

    @Test
    fun `enum, array and jsonb columns round trip`() {
        withSchema { connection ->
            val user = connection.insertUser(email = "ada@example.com", displayName = "Ada")!!

            connection.setPreferences(
                userId = user.id,
                mood = Mood.OVER_THE_MOON,
                tags = listOf("beta", null, "vip"),
                settings = """{"theme":"dark"}""",
            )

            val preferences = connection.findPreferences(user.id)
            assertNotNull(preferences)
            assertEquals(Mood.OVER_THE_MOON, preferences.mood)
            assertEquals(listOf("beta", null, "vip"), preferences.tags)
            assertEquals("""{"theme": "dark"}""", preferences.settings)
            // The domain resolves to its base type, and is nullable here.
            assertNull(preferences.contact)
            // interval has no built-in mapping; pgd.toml aliases it to text.
            assertNull(preferences.sessionLength)

            assertEquals(listOf(user.id), connection.usersInMood(Mood.OVER_THE_MOON))
            assertEquals(emptyList(), connection.usersInMood(Mood.SAD))
        }
    }

    @Test
    fun `an upsert replaces the previous preferences`() {
        withSchema { connection ->
            val user = connection.insertUser(email = "grace@example.com", displayName = "Grace")!!
            connection.setPreferences(user.id, Mood.SAD, listOf("a"), "{}")
            connection.setPreferences(user.id, Mood.HAPPY, emptyList(), "{}")

            val preferences = connection.findPreferences(user.id)!!
            assertEquals(Mood.HAPPY, preferences.mood)
            assertEquals(emptyList(), preferences.tags)
        }
    }

    @Test
    fun `enum labels map to their Postgres spelling`() {
        assertEquals("over the moon", Mood.OVER_THE_MOON.label)
        assertEquals(Mood.OVER_THE_MOON, Mood.fromLabel("over the moon"))
    }

    @OptIn(kotlin.io.path.ExperimentalPathApi::class)
    @Test
    fun `the committed generated code is up to date`() {
        val db = root.resolve("db")
        val (config, problems) = PgdConfig.load(db)
        assertEquals(emptyList(), problems)

        val temporary = Files.createTempDirectory("pgd-drift")
        val report = GenerateRunner.run(
            config = CheckConfig.forRoot(db),
            codegen = CodeGenConfig(config.packageName, config.typeAliases),
            outputDir = temporary,
        )
        assertTrue(report.ok, Reporters.toText(report))

        val committed = root.resolve("src/main/kotlin")
        val regenerated = temporary.walk().filter { it.toString().endsWith(".kt") }.toList()
        assertTrue(regenerated.isNotEmpty())

        for (file in regenerated) {
            val relative = temporary.relativize(file).toString()
            assertEquals(
                file.readText(),
                committed.resolve(relative).readText(),
                "$relative is stale — run `pgd generate --dir example/db`",
            )
        }
    }

    @Test
    fun `the committed schema snapshot is up to date`() {
        val analysis = ProjectAnalyzer.analyze(CheckConfig.forRoot(root.resolve("db")))
        assertTrue(analysis.ok, analysis.diagnostics.toString())
        assertEquals(
            SchemaWriter.toMarkdown(analysis.catalog),
            root.resolve("db/schema.md").readText(),
            "schema.md is stale — run `pgd generate --dir example/db`",
        )
        assertEquals(
            SchemaWriter.toJson(analysis.catalog),
            root.resolve("db/schema.json").readText(),
        )
    }

    @Test
    fun `copy bulk loads rows and reports how many`() {
        withSchema { connection ->
            val user = connection.insertUser(email = "ada@example.com", displayName = "Ada")!!

            val loaded = connection.bulkLoadEvents(
                listOf(
                    BulkLoadEventsRow(userId = user.id, name = "boot", note = null, feeling = Mood.OK),
                    BulkLoadEventsRow(userId = user.id, name = "run", note = "fine", feeling = Mood.HAPPY),
                ),
            )

            assertEquals(2L, loaded)
            assertEquals(2L, connection.countEvents())
            assertEquals(listOf("boot", "run"), connection.eventNames(user.id))
        }
    }

    @Test
    fun `copy escapes text that would otherwise end the field or the row`() {
        withSchema { connection ->
            val user = connection.insertUser(email = "grace@example.com", displayName = "Grace")!!
            val awkward = "tab\there\nnewline\\backslash"

            connection.bulkLoadEvents(
                listOf(BulkLoadEventsRow(user.id, awkward, note = null, feeling = Mood.SAD)),
            )

            assertEquals(listOf(awkward), connection.eventNames(user.id))
        }
    }

    @Test
    fun `copy loading nothing is not an error`() {
        withSchema { connection ->
            assertEquals(0L, connection.bulkLoadEvents(emptyList()))
            assertEquals(0L, connection.countEvents())
        }
    }

    @Test
    fun `exactlyone returns a non-null row`() {
        withSchema { connection ->
            val user = connection.insertUser(email = "ada@example.com", displayName = "Ada")!!
            // No `!!` at the call site: the return type is not nullable.
            val event = connection.recordEvent(user.id, "deploy", Mood.HAPPY)
            assertTrue(event.id > 0)
            assertEquals(1L, connection.countEvents())
        }
    }

    @Test
    fun `batch applies every row and reports each count`() {
        withSchema { connection ->
            val user = connection.insertUser(email = "ada@example.com", displayName = "Ada")!!
            val first = connection.recordEvent(user.id, "one", Mood.OK)
            val second = connection.recordEvent(user.id, "two", Mood.OK)

            val counts = connection.annotateEventBatch(
                listOf(
                    AnnotateEventParams(id = first.id, note = "first note"),
                    AnnotateEventParams(id = second.id, note = "second note"),
                    AnnotateEventParams(id = -1, note = "matches nothing"),
                ),
            )

            assertEquals(listOf(1, 1, 0), counts.toList())
        }
    }
}

/**
 * The kind of class the generator will never emit: internal, and shaped for the
 * caller rather than for the query. The mapper overload is what makes it
 * reachable without a throwaway generated row in between.
 */
internal data class ActiveUser(val id: Long, val label: String)
