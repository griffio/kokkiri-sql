package io.pgdescribe.core

import java.nio.file.Files
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PgdConfigTest {

    private fun load(toml: String?): Pair<PgdConfig, List<Diagnostic>> {
        val directory = Files.createTempDirectory("pgd-config")
        toml?.let { directory.resolve(PgdConfig.FILE_NAME).writeText(it.trimIndent()) }
        return PgdConfig.load(directory)
    }

    @Test
    fun `a missing file yields defaults`() {
        val (config, problems) = load(null)
        assertEquals(emptyList(), problems)
        assertEquals(PgdConfig.DEFAULT_PACKAGE, config.packageName)
        assertEquals("migrations", config.migrations)
        assertEquals("queries", config.queries)
        assertEquals(emptyMap(), config.typeAliases)
    }

    @Test
    fun `every key is read`() {
        val (config, problems) = load(
            """
            package = "com.example.db"
            output = "src/main/kotlin"
            migrations = "sql/migrations"
            queries = "sql/queries"

            [types]
            interval = "text"
            citext = "TEXT"
            """,
        )
        assertEquals(emptyList(), problems)
        assertEquals("com.example.db", config.packageName)
        assertEquals("src/main/kotlin", config.output)
        assertEquals("sql/migrations", config.migrations)
        assertEquals("sql/queries", config.queries)
        assertEquals(mapOf("interval" to "text", "citext" to "text"), config.typeAliases)
    }

    @Test
    fun `an unset key keeps its default`() {
        val (config, _) = load("""package = "only.this"""")
        assertEquals("only.this", config.packageName)
        assertEquals(PgdConfig.DEFAULT_OUTPUT, config.output)
    }

    @Test
    fun `malformed TOML is reported with a line number`() {
        val (_, problems) = load("package = \nthis is not toml")
        assertTrue(problems.isNotEmpty())
        assertTrue(problems.all { it.code == Diagnostic.CONFIG_ERROR })
        assertTrue(problems.all { it.severity == Severity.ERROR })
    }

    @Test
    fun `an invalid package name is rejected`() {
        val (_, problems) = load("""package = "1.bad-name"""")
        val diagnostic = problems.single()
        assertEquals(Diagnostic.CONFIG_ERROR, diagnostic.code)
        assertTrue("package" in diagnostic.hint.orEmpty())
    }

    @Test
    fun `a non-string type mapping is rejected`() {
        val (_, problems) = load(
            """
            [types]
            interval = 42
            """,
        )
        assertEquals(Diagnostic.CONFIG_ERROR, problems.single().code)
    }

    @Test
    fun `nullability defaults to auto`() {
        assertEquals(NullabilityMode.AUTO, load(null).first.nullability)
    }

    @Test
    fun `nullability is read case insensitively`() {
        assertEquals(NullabilityMode.CONSERVATIVE, load("""nullability = "conservative"""").first.nullability)
        assertEquals(NullabilityMode.PRECISE, load("""nullability = "PRECISE"""").first.nullability)
    }

    @Test
    fun `an unknown nullability mode is rejected`() {
        val (config, problems) = load("""nullability = "vibes"""")
        val diagnostic = problems.single()
        assertEquals(Diagnostic.CONFIG_ERROR, diagnostic.code)
        assertTrue("conservative" in diagnostic.hint.orEmpty(), diagnostic.hint.orEmpty())
        assertEquals(NullabilityMode.AUTO, config.nullability)
    }
}
