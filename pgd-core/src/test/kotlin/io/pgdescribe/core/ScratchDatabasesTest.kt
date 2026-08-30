package io.pgdescribe.core

import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ScratchDatabasesTest {

    private val server = ExistingPostgresServer(TestPostgres.url)

    private fun migrations(vararg bodies: String): List<Migration> {
        val directory: Path = Files.createTempDirectory("pgd-migrations").resolve("m").createDirectories()
        bodies.forEachIndexed { index, body ->
            directory.resolve("V%03d__m.sql".format(index + 1)).writeText(body)
        }
        return Migrations.load(directory)
    }

    private fun databases(like: String): List<String> = buildList {
        DriverManager.getConnection(TestPostgres.url).use { connection ->
            connection.prepareStatement("SELECT datname FROM pg_database WHERE datname LIKE ?").use { statement ->
                statement.setString(1, like)
                statement.executeQuery().use { results ->
                    while (results.next()) add(results.getString(1))
                }
            }
        }
    }

    private fun templateFor(migrations: List<Migration>): String =
        "pgd_tpl_" + Migrations.fingerprint(migrations).take(32)

    private fun drop(name: String) {
        DriverManager.getConnection(TestPostgres.url).use { connection ->
            connection.createStatement().use { it.execute("DROP DATABASE IF EXISTS \"$name\" WITH (FORCE)") }
        }
    }

    @Test
    fun `the first run builds a template and the second clones it`() {
        val migrations = migrations("CREATE TABLE cached_one (id int PRIMARY KEY);")
        val template = templateFor(migrations)
        drop(template)
        try {
            val first = ScratchDatabases.prepare(server, migrations)
            assertTrue(first.builtTemplate, "the first run should have built the template")
            first.database!!.close()
            assertTrue(template in databases("pgd_tpl_%"), "template was not kept")

            val second = ScratchDatabases.prepare(server, migrations)
            assertTrue(second.clonedFromTemplate, "the second run should have cloned the template")
            assertFalse(second.builtTemplate, "the second run should not have rebuilt the template")
            second.database!!.connect().use { connection ->
                connection.createStatement().use { statement ->
                    assertTrue(statement.executeQuery("SELECT 1 FROM cached_one").let { true })
                }
            }
            second.database.close()
        } finally {
            drop(template)
        }
    }

    @Test
    fun `different migrations get different templates`() {
        val a = migrations("CREATE TABLE cached_a (id int);")
        val b = migrations("CREATE TABLE cached_b (id int);")
        assertTrue(templateFor(a) != templateFor(b))
        try {
            ScratchDatabases.prepare(server, a).database!!.close()
            ScratchDatabases.prepare(server, b).database!!.close()
            val existing = databases("pgd_tpl_%")
            assertTrue(templateFor(a) in existing)
            assertTrue(templateFor(b) in existing)
        } finally {
            drop(templateFor(a))
            drop(templateFor(b))
        }
    }

    @Test
    fun `reordering the same statements changes the fingerprint`() {
        val a = migrations("CREATE TABLE x (id int);", "CREATE TABLE y (id int);")
        val b = migrations("CREATE TABLE y (id int);", "CREATE TABLE x (id int);")
        assertTrue(Migrations.fingerprint(a) != Migrations.fingerprint(b))
    }

    @Test
    fun `a failing migration leaves no template behind`() {
        val migrations = migrations("CREATE TABLE fine (id int);", "CREATE TABLE broken (id nosuchtype);")
        val template = templateFor(migrations)
        drop(template)

        val prepared = ScratchDatabases.prepare(server, migrations)
        assertNull(prepared.database)
        assertEquals(Diagnostic.MIGRATION_FAILED, prepared.diagnostics.single().code)
        assertFalse(template in databases("pgd_tpl_%"), "a broken template was cached")
        assertEquals(emptyList(), databases("pgd_tpl_%_building_%"), "staging database was left behind")
    }

    @Test
    fun `a throwaway server does not bother with templates`() {
        val migrations = migrations("CREATE TABLE ephemeral (id int);")
        EmbeddedPostgresServer.start().use { embedded ->
            assertFalse(embedded.persistent)
            val prepared = ScratchDatabases.prepare(embedded, migrations)
            assertFalse(prepared.clonedFromTemplate)
            prepared.database!!.close()

            DriverManager.getConnection(embedded.urlFor("postgres")).use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeQuery("SELECT count(*) FROM pg_database WHERE datname LIKE 'pgd_tpl_%'")
                        .use {
                            assertTrue(it.next())
                            assertEquals(0, it.getInt(1))
                        }
                }
            }
        }
    }

    @Test
    fun `an empty migration set skips the template path`() {
        val prepared = ScratchDatabases.prepare(server, emptyList())
        assertFalse(prepared.clonedFromTemplate)
        prepared.database!!.close()
    }

    @Test
    fun `clean drops the databases pgd created`() {
        val migrations = migrations("CREATE TABLE cleanup_me (id int);")
        ScratchDatabases.prepare(server, migrations).database!!.close()
        assertTrue(databases("pgd_tpl_%").isNotEmpty())

        ScratchDatabases.clean(server)
        assertEquals(emptyList(), databases("pgd_tpl_%"))
        assertEquals(emptyList(), databases("pgd_check_%"))
    }
}
