package io.pgdescribe.core

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class QueryParserTest {

    private val file = Path.of("queries", "test.sql")

    private fun parse(text: String) = QueryParser.parse(text.trimIndent(), file)

    @Test
    fun `reads name, tag, params and body`() {
        val result = parse(
            """
            -- name: FindActiveUsers :many
            -- params: since
            SELECT id FROM users WHERE created_at > $1::timestamptz;
            """,
        )

        assertEquals(emptyList(), result.diagnostics)
        val query = result.queries.single()
        assertEquals("FindActiveUsers", query.name)
        assertEquals(Cardinality.MANY, query.cardinality)
        assertEquals(listOf("since"), query.paramNames)
        assertEquals("SELECT id FROM users WHERE created_at > $1::timestamptz", query.sql)
        assertEquals(1, query.headerLine)
        assertEquals(3, query.sqlStartLine)
    }

    @Test
    fun `records the line where SQL actually starts`() {
        val result = parse(
            """
            -- name: A :exec
            DELETE FROM users;

            -- name: B :one
            -- params: id

            SELECT id FROM users WHERE id = $1::bigint;
            """,
        )
        assertEquals(emptyList(), result.diagnostics)
        assertEquals(listOf(2, 7), result.queries.map { it.sqlStartLine })
    }

    @Test
    fun `reads nullability overrides`() {
        val query = parse(
            """
            -- name: A :many
            -- nullable: display_name, note
            -- notnull: id
            SELECT id, display_name FROM users;
            """,
        ).queries.single()
        assertEquals(setOf("display_name", "note"), query.nullableOverrides)
        assertEquals(setOf("id"), query.notNullOverrides)
    }

    @Test
    fun `rejects a missing cardinality tag`() {
        val diagnostic = parse(
            """
            -- name: A
            SELECT 1;
            """,
        ).diagnostics.single()
        assertEquals(Diagnostic.MALFORMED_HEADER, diagnostic.code)
        assertEquals(1, diagnostic.line)
    }

    @Test
    fun `rejects an unknown cardinality tag`() {
        val diagnostic = parse(
            """
            -- name: A :lots
            SELECT 1;
            """,
        ).diagnostics.single()
        assertEquals(Diagnostic.UNKNOWN_CARDINALITY, diagnostic.code)
        assertTrue(":many" in diagnostic.hint.orEmpty())
    }

    @Test
    fun `rejects trailing junk in the header`() {
        val diagnostic = parse(
            """
            -- name: A :many and then some
            SELECT 1;
            """,
        ).diagnostics.single()
        assertEquals(Diagnostic.MALFORMED_HEADER, diagnostic.code)
    }

    @Test
    fun `rejects duplicate names and keeps the first`() {
        val result = parse(
            """
            -- name: A :one
            SELECT 1;

            -- name: A :one
            SELECT 2;
            """,
        )
        val diagnostic = result.diagnostics.single()
        assertEquals(Diagnostic.DUPLICATE_QUERY_NAME, diagnostic.code)
        assertEquals(4, diagnostic.line)
        assertEquals("SELECT 1", result.queries.single().sql)
    }

    @Test
    fun `reports SQL that precedes every header exactly once`() {
        val result = parse(
            """
            SELECT 1;
            SELECT 2;

            -- name: A :one
            SELECT 3;
            """,
        )
        val diagnostic = result.diagnostics.single()
        assertEquals(Diagnostic.ORPHAN_SQL, diagnostic.code)
        assertEquals(1, diagnostic.line)
        assertEquals(listOf("A"), result.queries.map { it.name })
    }

    @Test
    fun `does not emit a query for a malformed header`() {
        val result = parse(
            """
            -- name: A :nope
            SELECT 1;

            -- name: B :one
            SELECT 2;
            """,
        )
        assertEquals(listOf("B"), result.queries.map { it.name })
        assertEquals(1, result.diagnostics.size)
    }

    @Test
    fun `reports an empty body`() {
        val diagnostic = parse(
            """
            -- name: A :one

            -- name: B :one
            SELECT 1;
            """,
        ).diagnostics.single()
        assertEquals(Diagnostic.EMPTY_QUERY, diagnostic.code)
    }

    @Test
    fun `reports too few parameter names`() {
        val diagnostic = parse(
            """
            -- name: A :one
            -- params: email
            SELECT id FROM users WHERE email = $1::text AND active = $2::boolean;
            """,
        ).diagnostics.single()
        assertEquals(Diagnostic.PARAM_NAME_ARITY, diagnostic.code)
        assertTrue("1 more name" in diagnostic.hint.orEmpty(), diagnostic.hint.orEmpty())
    }

    @Test
    fun `reports too many parameter names`() {
        val diagnostic = parse(
            """
            -- name: A :one
            -- params: email, extra
            SELECT id FROM users WHERE email = $1::text;
            """,
        ).diagnostics.single()
        assertEquals(Diagnostic.PARAM_NAME_ARITY, diagnostic.code)
    }

    @Test
    fun `treats a directive after the body starts as ordinary SQL comment`() {
        val query = parse(
            """
            -- name: A :one
            SELECT id
            -- params: bogus
            FROM users;
            """,
        ).queries.single()
        assertEquals(emptyList(), query.paramNames)
        assertTrue("-- params: bogus" in query.sql)
    }

    @Test
    fun `drops a trailing comment so the body ends at its semicolon`() {
        // The comment is documentation for the *next* query, written above its
        // header. It must not leave a `;` in this statement's body.
        val result = QueryParser.parse(
            """
            -- name: First :one
            SELECT 1;

            -- The second query does something else.
            -- name: Second :one
            SELECT 2;
            """.trimIndent(),
            Path.of("q.sql"),
        )
        assertEquals(emptyList(), result.diagnostics)
        assertEquals(listOf("SELECT 1", "SELECT 2"), result.queries.map { it.sql })
    }

    @Test
    fun `a body that is only comments is empty`() {
        val result = QueryParser.parse(
            "-- name: Q :one\n-- nothing here\n\n-- name: R :one\nSELECT 1;\n",
            Path.of("q.sql"),
        )
        assertEquals(listOf(Diagnostic.EMPTY_QUERY), result.diagnostics.map { it.code })
        assertEquals(listOf("R"), result.queries.map { it.name })
    }

    @Test
    fun `a leading comment documents the query instead of joining its body`() {
        val query = parse(
            """
            -- name: A :one
            -- Finds the newest user.
            SELECT id FROM users ORDER BY created_at DESC LIMIT 1;
            """,
        ).queries.single()
        assertNotNull(query)
        assertEquals(listOf("Finds the newest user."), query.doc)
        assertEquals(3, query.sqlStartLine)
        assertTrue(query.sql.startsWith("SELECT"), query.sql)
    }

    @Test
    fun `a directive written after prose is still read`() {
        val query = parse(
            """
            -- name: A :one
            -- Prose first, which used to start the body and swallow the line below.
            -- notnull: total
            SELECT count(*) AS total FROM users;
            """,
        ).queries.single()
        assertEquals(setOf("total"), query.notNullOverrides)
        assertEquals(listOf("Prose first, which used to start the body and swallow the line below."), query.doc)
        assertFalse("--" in query.sql, query.sql)
    }

    @Test
    fun `a comment block directly above a header documents that query, not the one before`() {
        val result = parse(
            """
            -- name: A :one
            SELECT 1;

            -- Belongs to B.
            -- Two lines of it.
            -- name: B :one
            SELECT 2;
            """,
        )
        val (a, b) = result.queries
        assertEquals("SELECT 1", a.sql)
        assertEquals(emptyList(), a.doc)
        assertEquals(listOf("Belongs to B.", "Two lines of it."), b.doc)
    }

    @Test
    fun `a file comment separated from the first header by a blank line documents nothing`() {
        val query = parse(
            """
            -- Queries about users.

            -- name: A :one
            SELECT 1;
            """,
        ).queries.single()
        assertEquals(emptyList(), query.doc)
    }

    @Test
    fun `a question mark on a parameter name makes it nullable`() {
        val query = parse(
            """
            -- name: A :many
            -- params: name, since?
            SELECT id FROM users WHERE name = $1::text AND ($2::timestamptz IS NULL OR created_at > $2::timestamptz);
            """,
        ).queries.single()
        assertEquals(listOf("name", "since"), query.paramNames)
        assertEquals(setOf("since"), query.nullableParams)
    }

    @Test
    fun `reports a gap in the placeholder run`() {
        val diagnostic = parse(
            """
            -- name: A :one
            SELECT id FROM users WHERE email = $1::text AND active = $3::boolean;
            """,
        ).diagnostics.single()
        assertEquals(Diagnostic.PARAM_GAP, diagnostic.code)
        assertTrue("$2" in diagnostic.message, diagnostic.message)
    }

    @Test
    fun `does not count placeholders inside string literals`() {
        val result = parse(
            """
            -- name: A :one
            -- params: email
            SELECT id, 'literal $9' AS note FROM users WHERE email = $1::text;
            """,
        )
        assertEquals(emptyList(), result.diagnostics)
    }
}
