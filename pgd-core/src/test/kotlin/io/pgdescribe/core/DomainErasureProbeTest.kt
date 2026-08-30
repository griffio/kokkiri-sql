package io.pgdescribe.core

import java.sql.Connection
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins down an asymmetry that decides what custom type mapping can ever look
 * like here: **Postgres erases domains in RowDescription but keeps them in
 * ParameterDescription.**
 *
 * A result column whose type is `CREATE DOMAIN email AS text` is described as
 * `text` — through aliases, subqueries, CTEs, joins and even an explicit
 * `::email` cast back. A *parameter* compared against or inserted into that
 * same column is described as `email`.
 *
 * So the oracle cannot tell us "this column is an email" on the way out, and a
 * domain is not usable as the SqlDelight-style `AS SomeType` hook for reads. It
 * is usable for parameter binding. If Postgres ever starts reporting the domain
 * OID in RowDescription, this test fails and that design is back on the table.
 */
class DomainErasureProbeTest {

    private fun <T> withSchema(block: (Connection) -> T): T {
        val scratch = ExistingPostgresServer(TestPostgres.url).use { it.createScratchDatabase() }
        return scratch.connect().use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("CREATE DOMAIN email AS text CHECK (VALUE LIKE '%@%')")
                statement.execute("CREATE TABLE person (addr email NOT NULL)")
            }
            block(connection)
        }
    }

    private fun Connection.columnType(sql: String): String =
        prepareStatement(sql).use { checkNotNull(it.metaData).getColumnTypeName(1) }

    private fun Connection.parameterType(sql: String): String =
        prepareStatement(sql).use { checkNotNull(it.parameterMetaData).getParameterTypeName(1) }

    @Test
    fun `a domain is erased to its base type in every result column shape`() {
        withSchema { connection ->
            for (sql in listOf(
                "SELECT addr FROM person",
                "SELECT addr AS a FROM person",
                "SELECT a FROM (SELECT addr AS a FROM person) t",
                "WITH t AS (SELECT addr FROM person) SELECT addr FROM t",
                "SELECT min(addr) FROM person",
                // Even casting back to the domain does not bring the name back.
                "SELECT (upper(addr))::email FROM person",
                "INSERT INTO person (addr) VALUES ('a@b') RETURNING addr",
            )) {
                assertEquals("text", connection.columnType(sql), sql)
            }
        }
    }

    @Test
    fun `a domain survives in parameter description`() {
        withSchema { connection ->
            assertEquals("email", connection.parameterType("INSERT INTO person (addr) VALUES (?)"))
            assertEquals("email", connection.parameterType("SELECT 1 FROM person WHERE addr = ?::email"))
            // Without the cast the comparison is resolved on the base type.
            assertEquals("text", connection.parameterType("SELECT 1 FROM person WHERE addr = ?"))
        }
    }

    @Test
    fun `an array of a domain keeps the domain, which is why the registry resolves them`() {
        withSchema { connection ->
            assertEquals("_email", connection.columnType("SELECT array_agg(addr) FROM person"))
        }
    }
}
