package io.pgdescribe.core

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
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
    fun `keeps a leading comment as part of the body`() {
        val query = parse(
            """
            -- name: A :one
            -- Finds the newest user.
            SELECT id FROM users ORDER BY created_at DESC LIMIT 1;
            """,
        ).queries.single()
        assertNotNull(query)
        assertEquals(2, query.sqlStartLine)
        assertTrue(query.sql.startsWith("-- Finds the newest user."))
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
