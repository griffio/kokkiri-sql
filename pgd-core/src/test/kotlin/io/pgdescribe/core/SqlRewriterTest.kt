package io.pgdescribe.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SqlRewriterTest {

    private fun rewrite(sql: String) = SqlRewriter.toJdbc(sql)

    @Test
    fun `replaces placeholders and records their order`() {
        val result = rewrite("SELECT id FROM t WHERE a = $1 AND b = $2")
        assertEquals("SELECT id FROM t WHERE a = ?  AND b = ? ", result.jdbcSql)
        assertEquals(listOf(1, 2), result.bindings)
    }

    @Test
    fun `keeps character offsets identical so error positions still line up`() {
        val sql = "SELECT id\nFROM t\nWHERE a = $1 AND b = $22 AND c = $333"
        val result = rewrite(sql)
        assertEquals(sql.length, result.jdbcSql.length)
        assertEquals(sql.indexOf('\n'), result.jdbcSql.indexOf('\n'))
        assertEquals(listOf(1, 22, 333), result.bindings)
    }

    @Test
    fun `a repeated placeholder becomes two binds pointing at the same parameter`() {
        val result = rewrite("SELECT * FROM t WHERE a = $1 OR b = $1")
        assertEquals(listOf(1, 1), result.bindings)
        assertEquals(listOf(1), result.distinct)
        assertEquals(2, result.jdbcSql.count { it == '?' })
    }

    @Test
    fun `ignores placeholders inside single quoted strings`() {
        val result = rewrite("SELECT '\$1 is not a param', $1")
        assertEquals(listOf(1), result.bindings)
        assertTrue("'\$1 is not a param'" in result.jdbcSql)
    }

    @Test
    fun `handles doubled quotes inside a string`() {
        val result = rewrite("SELECT 'it''s $1 fine', $2")
        assertEquals(listOf(2), result.bindings)
        assertTrue("'it''s \$1 fine'" in result.jdbcSql)
    }

    @Test
    fun `ignores placeholders inside quoted identifiers`() {
        val result = rewrite("""SELECT "weird$1column" FROM t WHERE a = $1""")
        assertEquals(listOf(1), result.bindings)
        assertTrue(""""weird${'$'}1column"""" in result.jdbcSql)
    }

    @Test
    fun `ignores placeholders inside line comments`() {
        val result = rewrite("SELECT 1 -- not a param: $9\nWHERE a = $1")
        assertEquals(listOf(1), result.bindings)
        assertTrue("-- not a param: \$9" in result.jdbcSql)
    }

    @Test
    fun `ignores placeholders inside nested block comments`() {
        val result = rewrite("SELECT /* outer /* inner $9 */ still $8 */ a FROM t WHERE b = $1")
        assertEquals(listOf(1), result.bindings)
    }

    @Test
    fun `ignores placeholders inside dollar quoted bodies`() {
        val sql = "SELECT \$\$a literal \$1 here\$\$, $1 FROM t"
        val result = rewrite(sql)
        assertEquals(listOf(1), result.bindings)
        assertEquals(sql.length, result.jdbcSql.length)
    }

    @Test
    fun `ignores placeholders inside tagged dollar quoted bodies`() {
        val sql = "SELECT \$body\$ if x then \$2 end \$body\$, $1"
        val result = rewrite(sql)
        assertEquals(listOf(1), result.bindings)
    }

    @Test
    fun `leaves a lone dollar sign alone`() {
        val result = rewrite("SELECT 'cost' AS $ FROM t")
        assertEquals(emptyList(), result.bindings)
    }

    @Test
    fun `a cast immediately after a placeholder stays valid`() {
        val result = rewrite("SELECT * FROM t WHERE id = $1::bigint")
        assertTrue("? ::bigint" in result.jdbcSql, result.jdbcSql)
    }
}
