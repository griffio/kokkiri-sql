package io.pgdescribe.native

import io.pgdescribe.core.ParseTree
import io.pgdescribe.core.SqlParser
import io.pgdescribe.core.SqlParsers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before

/** Pins the parse-tree shapes `ParseTree` relies on against the real parser. */
class FfmPgQueryParserTest {

    private val parser: SqlParser? = runCatching { LibPgQueryParser() }.getOrNull()

    @Before
    fun requireLibrary() {
        assumeTrue("libpg_query is not installed on this machine", parser != null)
    }

    private fun facts(sql: String) = ParseTree.joinFacts(assertNotNull(parser!!.parseTreeJson(sql)))

    @Test
    fun `parses a statement into a tree`() {
        val json = parser!!.parseTreeJson("SELECT a FROM t WHERE b = $1")
        assertNotNull(json)
        assertTrue("SelectStmt" in json, json)
    }

    @Test
    fun `refuses SQL Postgres cannot parse`() {
        assertNull(parser!!.parseTreeJson("SELECT FROM WHERE"))
    }

    @Test
    fun `refuses JDBC placeholders, which is why the original SQL is parsed`() {
        // The rewritten `?` form is not valid Postgres. If analysis ever passed
        // it here, every statement with a parameter would silently lose its
        // parse tree and fall back to the conservative rule.
        assertNull(parser!!.parseTreeJson("SELECT a FROM t WHERE b = ?"))
        assertNotNull(parser.parseTreeJson("SELECT a FROM t WHERE b = $1"))
    }

    @Test
    fun `a real left join names only the nullable side`() {
        val result = facts("SELECT u.id, o.total FROM users u LEFT JOIN orders o ON o.user_id = u.id")!!
        assertEquals(setOf("orders"), result.nullableRelations)
        assertFalse(result.demotesEverything)
    }

    @Test
    fun `a real right join names the other side`() {
        assertEquals(
            setOf("users"),
            facts("SELECT 1 FROM users u RIGHT JOIN orders o ON o.user_id = u.id")!!.nullableRelations,
        )
    }

    @Test
    fun `a real full join names both`() {
        assertEquals(
            setOf("orders", "users"),
            facts("SELECT 1 FROM users u FULL OUTER JOIN orders o ON o.user_id = u.id")!!.nullableRelations,
        )
    }

    @Test
    fun `a real inner join names nothing`() {
        assertEquals(
            emptySet(),
            facts("SELECT 1 FROM users u JOIN orders o ON o.user_id = u.id")!!.nullableRelations,
        )
    }

    @Test
    fun `a real self join with one outer arm resolves the safe way`() {
        assertEquals(
            setOf("users"),
            facts("SELECT 1 FROM users a LEFT JOIN users b ON b.id = a.id")!!.nullableRelations,
        )
    }

    @Test
    fun `real rollup, cube and grouping sets all demote`() {
        for (sql in listOf(
            "SELECT a, count(*) FROM t GROUP BY ROLLUP (a)",
            "SELECT a, count(*) FROM t GROUP BY CUBE (a)",
            "SELECT a, count(*) FROM t GROUP BY GROUPING SETS ((a), ())",
        )) {
            assertTrue(facts(sql)!!.demotesEverything, sql)
        }
    }

    @Test
    fun `a real plain group by does not demote`() {
        assertFalse(facts("SELECT a, count(*) FROM t GROUP BY a")!!.demotesEverything)
    }

    @Test
    fun `a keyword inside a string literal is not a join`() {
        assertEquals(
            emptySet(),
            facts("SELECT 'LEFT JOIN orders' AS label FROM users")!!.nullableRelations,
        )
    }

    @Test
    fun `an outer join in a subquery still counts`() {
        val result = facts(
            "SELECT x.id FROM (SELECT u.id FROM users u LEFT JOIN orders o ON o.user_id = u.id) x",
        )!!
        assertEquals(setOf("orders"), result.nullableRelations)
    }

    @Test
    fun `RETURNING has no joins to worry about`() {
        val result = facts("INSERT INTO users (email) VALUES ($1) RETURNING id, email")!!
        assertEquals(emptySet(), result.nullableRelations)
        assertFalse(result.demotesEverything)
    }

    @Test
    fun `the parser is discovered through ServiceLoader`() {
        assertNotNull(SqlParsers.available(), "pgd-native should register itself as a SqlParser")
        assertEquals("libpg_query", SqlParsers.available()!!.description)
    }
}
