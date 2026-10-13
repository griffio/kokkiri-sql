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
    fun `an outer join to a CTE nulls the table underneath it`() {
        withSchema { connection ->
            // pgjdbc reports x.nickname as pet.nickname, which is NOT NULL in
            // the catalog. Before the CTE was expanded, only "x" was on the
            // nullable side, so this came back non-null — the one direction
            // the tool must never be wrong in.
            val sql = "WITH x AS (SELECT id, person_id, nickname FROM pet) " +
                "SELECT p.name, x.nickname FROM person p LEFT JOIN x ON x.person_id = p.id"
            assertEquals(
                listOf("name" to false, "nickname" to true),
                nullability(connection, sql, withParser = parser),
            )
        }
    }

    @Test
    fun `an outer join to a view stays nullable without any help`() {
        withSchema { connection ->
            connection.createStatement().use { it.execute("CREATE VIEW pets AS SELECT id, person_id, nickname FROM pet") }
            // Postgres reports view columns as the view's own attributes, which
            // carry no NOT NULL, so pgjdbc already answers nullable here.
            val sql = "SELECT p.name, v.nickname FROM person p LEFT JOIN pets v ON v.person_id = p.id"
            assertEquals(
                listOf("name" to false, "nickname" to true),
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
            // asked a TreeSet whether it contained null. count(*), EXISTS and
            // an ungrouped count subquery are now proven non-null outright;
            // avg has no such proof and stays nullable.
            assertEquals(
                listOf("count" to false),
                nullability(connection, "SELECT count(*) FROM person", parser),
            )
            assertEquals(
                listOf("exists" to false),
                nullability(connection, "SELECT EXISTS (SELECT 1 FROM person)", parser),
            )
            assertEquals(
                listOf("avg" to true),
                nullability(connection, "SELECT avg(p.id::float8) FROM person p", parser),
            )
            assertEquals(
                listOf("n" to false),
                nullability(connection, "SELECT (SELECT count(*) FROM pet) AS n FROM person", parser),
            )
        }
    }

    @Test
    fun `an aggregate beside a provable column does not disturb it`() {
        withSchema { connection ->
            // Neither proof may cost the other: the base column is proven by
            // the parse tree, the count by its own definition, despite the
            // outer join it reads from.
            assertEquals(
                listOf("name" to false, "count" to false),
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
    fun `expressions SQL defines as never null are proven so`() {
        withSchema { connection ->
            val sql = """
                SELECT count(t.id) FILTER (WHERE t.nickname <> '') AS filtered,
                       ARRAY(SELECT nickname FROM pet ORDER BY nickname) AS nicknames,
                       COALESCE(p.note, '') AS note,
                       p.note IS NULL AS unnoted,
                       (count(*) + count(t.id) * 2)::bigint AS score,
                       -count(*) AS negated,
                       'literal' AS label
                FROM person p LEFT JOIN pet t ON t.person_id = p.id
                GROUP BY p.note
            """.trimIndent()
            assertEquals(
                listOf(
                    "filtered" to false, "nicknames" to false, "note" to false, "unnoted" to false,
                    "score" to false, "negated" to false, "label" to false,
                ),
                nullability(connection, sql, parser),
            )
        }
    }

    @Test
    fun `expressions that can be null stay nullable`() {
        withSchema { connection ->
            val sql = """
                SELECT count(*) OVER () AS windowed,
                       (SELECT count(*) FROM pet GROUP BY person_id LIMIT 1) AS grouped,
                       (SELECT nickname FROM pet LIMIT 1) AS first_nickname,
                       COALESCE(p.note, NULL) AS still_null,
                       NULL::text AS nothing,
                       p.id = ANY (SELECT person_id FROM pet) AS any_match,
                       sum(p.id) AS total
                FROM person p
                GROUP BY p.id, p.note
            """.trimIndent()
            assertEquals(
                listOf(
                    "windowed" to true, "grouped" to true, "first_nickname" to true, "still_null" to true,
                    "nothing" to true, "any_match" to true, "total" to true,
                ),
                nullability(connection, sql, parser),
            )
        }
    }

    @Test
    fun `a CTE column carries its proof to the query that reads it`() {
        withSchema { connection ->
            val sql = """
                WITH counted AS (
                    SELECT p.id, (SELECT count(*) FROM pet t WHERE t.person_id = p.id) AS pets
                    FROM person p
                )
                SELECT c.pets, c.pets * 2 AS doubled, x.pets AS outer_pets
                FROM counted c
                LEFT JOIN counted x ON x.id = c.id + 1
            """.trimIndent()
            // The same CTE column is proven through c and not through x, which
            // sits on the nullable side of the outer join.
            assertEquals(
                listOf("pets" to false, "doubled" to false, "outer_pets" to true),
                nullability(connection, sql, parser),
            )
        }
    }

    @Test
    fun `a subquery column is proven, unless an outer join can null it`() {
        withSchema { connection ->
            val sql = """
                SELECT s.n, j.n AS joined_n
                FROM (SELECT count(*) AS n FROM pet) s
                LEFT JOIN (SELECT person_id, count(*) AS n FROM pet GROUP BY person_id) j ON true
            """.trimIndent()
            assertEquals(listOf("n" to false, "joined_n" to true), nullability(connection, sql, parser))
        }
    }

    @Test
    fun `a star or a set operation leaves expressions unproven rather than misaligned`() {
        withSchema { connection ->
            assertEquals(
                listOf("id" to false, "name" to false, "note" to true, "count" to true),
                nullability(connection, "SELECT p.*, (SELECT count(*) FROM pet) AS count FROM person p", parser),
            )
            assertEquals(
                listOf("count" to true),
                nullability(connection, "SELECT count(*) FROM person UNION ALL SELECT count(*) FROM pet", parser),
            )
        }
    }

    @Test
    fun `count stays proven under grouping sets, which null only the grouped columns`() {
        withSchema { connection ->
            val sql = "SELECT p.name, count(*) FROM person p GROUP BY ROLLUP (p.name)"
            assertEquals(listOf("name" to true, "count" to false), nullability(connection, sql, parser))
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
