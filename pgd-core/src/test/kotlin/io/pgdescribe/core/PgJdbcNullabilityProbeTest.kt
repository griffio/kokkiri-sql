package io.pgdescribe.core

import java.sql.Connection
import java.sql.ResultSetMetaData
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins down the two facts the M3 nullability design rests on:
 *
 *  1. Postgres' RowDescription carries no nullability, so pgjdbc answers
 *     `isNullable()` from `pg_attribute` instead.
 *  2. That answer describes the *base column's* constraint, so a NOT NULL
 *     column on the nullable side of an outer join is reported non-nullable.
 *
 * If (2) ever changes, the plan's "do the catalog lookup ourselves" decision
 * can be revisited — so this test exists to notice.
 */
class PgJdbcNullabilityProbeTest {

    private fun <T> withSchema(block: (Connection) -> T): T {
        val server = ExistingPostgresServer(TestPostgres.url)
        val scratch = server.createScratchDatabase()
        return try {
            scratch.connect().use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute(
                        """
                        CREATE TABLE person (
                            id   int  PRIMARY KEY,
                            name text NOT NULL,
                            note text
                        );
                        CREATE TABLE pet (
                            id        int  PRIMARY KEY,
                            person_id int  NOT NULL REFERENCES person (id),
                            nickname  text NOT NULL
                        );
                        """.trimIndent(),
                    )
                }
                block(connection)
            }
        } finally {
            scratch.close()
        }
    }

    private fun metaData(connection: Connection, sql: String): ResultSetMetaData =
        connection.prepareStatement(sql).metaData!!

    @Test
    fun `pgjdbc reports base column constraints correctly for a plain select`() {
        withSchema { connection ->
            val md = metaData(connection, "SELECT id, name, note FROM person")
            assertEquals(ResultSetMetaData.columnNoNulls, md.isNullable(1), "person.id")
            assertEquals(ResultSetMetaData.columnNoNulls, md.isNullable(2), "person.name")
            assertEquals(ResultSetMetaData.columnNullable, md.isNullable(3), "person.note")
        }
    }

    @Test
    fun `pgjdbc gets outer joins wrong, which is why we do the catalog lookup ourselves`() {
        withSchema { connection ->
            val md = metaData(
                connection,
                "SELECT p.name, t.nickname FROM person p LEFT JOIN pet t ON t.person_id = p.id",
            )
            assertEquals(ResultSetMetaData.columnNoNulls, md.isNullable(1), "person.name is genuinely NOT NULL")
            assertEquals(
                ResultSetMetaData.columnNoNulls,
                md.isNullable(2),
                "pet.nickname is NOT NULL in the catalog but nullable in this result set; " +
                    "pgjdbc reporting columnNoNulls here is the bug class M3 has to handle",
            )
        }
    }

    @Test
    fun `expressions carry no base column, so nothing can be assumed about them`() {
        withSchema { connection ->
            val md = metaData(connection, "SELECT count(*) AS total, upper(name) AS shout FROM person GROUP BY name")
            assertEquals(ResultSetMetaData.columnNullableUnknown, md.isNullable(1), "count(*)")
            assertEquals(ResultSetMetaData.columnNullableUnknown, md.isNullable(2), "upper(name)")
        }
    }

    @Test
    fun `base table and column are available for passthrough columns only`() {
        withSchema { connection ->
            val analyzer = QueryAnalyzer(connection)
            val query = QueryParser.parse(
                "-- name: A :many\nSELECT p.name, upper(p.name) AS shout FROM person p",
                java.nio.file.Path.of("q.sql"),
            ).queries.single()

            val columns = analyzer.analyze(query).analyzed!!.columns
            assertEquals("person", columns[0].baseTable)
            assertEquals("name", columns[0].baseColumn)
            assertEquals(null, columns[1].baseTable)
        }
    }
}
