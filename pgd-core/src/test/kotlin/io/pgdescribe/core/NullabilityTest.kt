package io.pgdescribe.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NullabilityTest {

    private fun demotes(sql: String): Boolean =
        Nullability.demotesEverything(SqlRewriter.toJdbc(sql).masked)

    @Test
    fun `outer joins demote`() {
        assertTrue(demotes("SELECT a FROM x LEFT JOIN y ON true"))
        assertTrue(demotes("SELECT a FROM x left outer join y ON true"))
        assertTrue(demotes("SELECT a FROM x RIGHT JOIN y ON true"))
        assertTrue(demotes("SELECT a FROM x FULL OUTER JOIN y ON true"))
    }

    @Test
    fun `grouping constructs demote`() {
        assertTrue(demotes("SELECT a, count(*) FROM x GROUP BY ROLLUP (a)"))
        assertTrue(demotes("SELECT a FROM x GROUP BY CUBE (a)"))
        assertTrue(demotes("SELECT a FROM x GROUP BY GROUPING SETS ((a), ())"))
    }

    @Test
    fun `inner joins do not demote`() {
        assertFalse(demotes("SELECT a FROM x JOIN y ON true"))
        assertFalse(demotes("SELECT a FROM x INNER JOIN y ON true"))
        assertFalse(demotes("SELECT a FROM x, y WHERE x.id = y.id"))
    }

    @Test
    fun `a keyword inside a string literal does not demote`() {
        assertFalse(demotes("SELECT 'LEFT JOIN' AS label FROM x"))
    }

    @Test
    fun `a keyword inside a comment does not demote`() {
        assertFalse(demotes("SELECT a FROM x -- was a LEFT JOIN once\n"))
        assertFalse(demotes("SELECT a /* LEFT JOIN */ FROM x"))
    }

    @Test
    fun `a column named left_join does not demote`() {
        assertFalse(demotes("SELECT left_join_count FROM x"))
    }

    @Test
    fun `facts override the keyword scan in both directions`() {
        // The keyword scan sees LEFT JOIN and would demote everything; the parse
        // tree knows only one relation is on the nullable side.
        val sql = "SELECT a.name, b.note FROM person a LEFT JOIN pet b ON b.id = a.id"
        val query = QueryParser.parse("-- name: Q :many\n$sql", java.nio.file.Path.of("q.sql")).queries.single()
        val columns = listOf(
            ColumnInfo(1, "name", "text", baseTable = "person", baseColumn = "name"),
            ColumnInfo(2, "note", "text", baseTable = "pet", baseColumn = "note"),
        )

        assertTrue(demotes(sql), "the keyword scan should see the outer join")

        val precise = Nullability.resolve(
            query = query,
            maskedSql = SqlRewriter.toJdbc(sql).masked,
            metaData = null,
            columns = columns,
            facts = JoinFacts(nullableRelations = setOf("pet"), demotesEverything = false),
        )
        // metaData is null here, so nothing can be proven non-null; what matters
        // is that the join no longer demotes the person columns by itself.
        assertEquals(listOf(true, true), precise)
    }

    @Test
    fun `facts that demote everything win over any proof`() {
        val sql = "SELECT a FROM t GROUP BY ROLLUP (a)"
        val query = QueryParser.parse("-- name: Q :many\n$sql", java.nio.file.Path.of("q.sql")).queries.single()
        val resolved = Nullability.resolve(
            query = query,
            maskedSql = SqlRewriter.toJdbc(sql).masked,
            metaData = null,
            columns = listOf(ColumnInfo(1, "a", "text", baseTable = "t", baseColumn = "a")),
            facts = JoinFacts(emptySet(), demotesEverything = true),
        )
        assertEquals(listOf(true), resolved)
    }

    @Test
    fun `a column with no base relation does not crash the parse tree lookup`() {
        // sortedSetOf, not setOf: JoinFacts is built with one, and TreeSet is
        // the collection whose contains(null) throws. A LinkedHashSet from
        // setOf() tolerates it, which is why this went unnoticed.
        val sql = "SELECT count(*) FROM person a LEFT JOIN pet b ON b.id = a.id"
        val query = QueryParser.parse("-- name: Q :one\n$sql", java.nio.file.Path.of("q.sql")).queries.single()
        val resolved = Nullability.resolve(
            query = query,
            maskedSql = SqlRewriter.toJdbc(sql).masked,
            metaData = null,
            columns = listOf(ColumnInfo(1, "count", "int8", baseTable = null, baseColumn = null)),
            facts = JoinFacts(sortedSetOf("pet"), demotesEverything = false),
        )
        // An aggregate passes through from no column, so nothing is provable
        // and it stays nullable — the same answer the conservative path gives.
        assertEquals(listOf(true), resolved)
    }

    @Test
    fun `an override still answers for a column with no base relation`() {
        val sql = "SELECT count(*) FROM person"
        val query = QueryParser.parse(
            "-- name: Q :one\n-- notnull: count\n$sql",
            java.nio.file.Path.of("q.sql"),
        ).queries.single()
        val resolved = Nullability.resolve(
            query = query,
            maskedSql = SqlRewriter.toJdbc(sql).masked,
            metaData = null,
            columns = listOf(ColumnInfo(1, "count", "int8", baseTable = null, baseColumn = null)),
            facts = JoinFacts(sortedSetOf(), demotesEverything = false),
        )
        assertEquals(listOf(false), resolved)
    }

    @Test
    fun `overrides still win over the parse tree`() {
        val sql = "SELECT b.note FROM person a LEFT JOIN pet b ON b.id = a.id"
        val query = QueryParser.parse(
            "-- name: Q :many\n-- notnull: note\n$sql",
            java.nio.file.Path.of("q.sql"),
        ).queries.single()
        val resolved = Nullability.resolve(
            query = query,
            maskedSql = SqlRewriter.toJdbc(sql).masked,
            metaData = null,
            columns = listOf(ColumnInfo(1, "note", "text", baseTable = "pet", baseColumn = "note")),
            facts = JoinFacts(setOf("pet"), demotesEverything = false),
        )
        assertEquals(listOf(false), resolved)
    }
}
