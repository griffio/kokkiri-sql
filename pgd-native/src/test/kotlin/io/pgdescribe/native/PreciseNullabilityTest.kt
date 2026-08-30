package io.pgdescribe.native

import io.pgdescribe.core.EmbeddedPostgresServer
import io.pgdescribe.core.QueryAnalyzer
import io.pgdescribe.core.QueryParser
import io.pgdescribe.core.SqlParser
import io.pgdescribe.core.createScratchDatabase
import java.nio.file.Path
import java.sql.Connection
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Before

/**
 * The point of the whole milestone: with Postgres' parser installed, an outer
 * join stops demoting every column in the statement.
 */
class PreciseNullabilityTest {

    private val parser: SqlParser? = runCatching { LibPgQueryParser() }.getOrNull()
    private val server = EmbeddedPostgresServer.start()

    @Before
    fun requireLibrary() {
        assumeTrue("libpg_query is not installed on this machine", parser != null)
    }

    @AfterTest
    fun tearDown() {
        runCatching { server.close() }
    }

    private fun <T> withSchema(block: (Connection) -> T): T {
        val scratch = server.createScratchDatabase()
        return try {
            scratch.connect().use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute(
                        """
                        CREATE TABLE person (id int PRIMARY KEY, name text NOT NULL, note text);
                        CREATE TABLE pet (id int PRIMARY KEY, person_id int NOT NULL, nickname text NOT NULL);
                        """.trimIndent(),
                    )
                }
                block(connection)
            }
        } finally {
            scratch.close()
        }
    }

    private fun nullability(connection: Connection, sql: String, withParser: SqlParser?): List<Pair<String, Boolean>> {
        val query = QueryParser.parse("-- name: Q :many\n$sql", Path.of("q.sql")).queries.single()
        val analyzed = QueryAnalyzer(connection, withParser).analyze(query).analyzed!!
        return analyzed.columns.map { it.label to it.nullable }
    }

    @Test
    fun `an outer join only nulls the columns it can actually null`() {
        withSchema { connection ->
            val sql = "SELECT p.name, t.nickname FROM person p LEFT JOIN pet t ON t.person_id = p.id"

            assertEquals(
                listOf("name" to true, "nickname" to true),
                nullability(connection, sql, withParser = null),
                "without the parser, the whole statement is demoted",
            )

            assertEquals(
                listOf("name" to false, "nickname" to true),
                nullability(connection, sql, withParser = parser),
                "person.name is NOT NULL and person is not on the nullable side",
            )
        }
    }

    @Test
    fun `a nullable base column stays nullable either way`() {
        withSchema { connection ->
            val sql = "SELECT p.note, t.nickname FROM person p LEFT JOIN pet t ON t.person_id = p.id"
            assertEquals(
                listOf("note" to true, "nickname" to true),
                nullability(connection, sql, withParser = parser),
            )
        }
    }

    @Test
    fun `a self join with one outer arm stays conservative`() {
        withSchema { connection ->
            val sql = "SELECT a.name AS a_name, b.name AS b_name FROM person a LEFT JOIN person b ON b.id = a.id"
            assertEquals(
                listOf("a_name" to true, "b_name" to true),
                nullability(connection, sql, withParser = parser),
                "both sides are person, so neither can be proven",
            )
        }
    }

    @Test
    fun `an inner join proves both sides`() {
        withSchema { connection ->
            val sql = "SELECT p.name, t.nickname FROM person p JOIN pet t ON t.person_id = p.id"
            assertEquals(
                listOf("name" to false, "nickname" to false),
                nullability(connection, sql, withParser = parser),
            )
        }
    }

    @Test
    fun `columns with no base relation are described, not crashed on`() {
        withSchema { connection ->
            // Every one of these threw NullPointerException before the null
            // guard in Nullability.resolve: pgjdbc reports no base table for a
            // column that is not a plain reference, and the parse-tree lookup
            // asked a TreeSet whether it contained null.
            assertEquals(
                listOf("count" to true),
                nullability(connection, "SELECT count(*) FROM person", parser),
            )
            assertEquals(
                listOf("exists" to true),
                nullability(connection, "SELECT EXISTS (SELECT 1 FROM person)", parser),
            )
            assertEquals(
                listOf("avg" to true),
                nullability(connection, "SELECT avg(p.id::float8) FROM person p", parser),
            )
            assertEquals(
                listOf("n" to true),
                nullability(connection, "SELECT (SELECT count(*) FROM pet) AS n FROM person", parser),
            )
        }
    }

    @Test
    fun `an aggregate beside a provable column does not disturb it`() {
        withSchema { connection ->
            // The aggregate is unprovable, but it must not cost the column
            // beside it the proof the parse tree can still make.
            assertEquals(
                listOf("name" to false, "count" to true),
                nullability(
                    connection,
                    "SELECT p.name, count(t.id) FROM person p LEFT JOIN pet t ON t.person_id = p.id " +
                        "GROUP BY p.name",
                    parser,
                ),
            )
        }
    }

    @Test
    fun `grouping sets still demote everything`() {
        withSchema { connection ->
            val sql = "SELECT p.name FROM person p GROUP BY ROLLUP (p.name)"
            assertEquals(listOf("name" to true), nullability(connection, sql, withParser = parser))
        }
    }
}
