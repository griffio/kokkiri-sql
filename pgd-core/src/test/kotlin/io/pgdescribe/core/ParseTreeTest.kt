package io.pgdescribe.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The walker, exercised against hand-written trees so it needs no native code.
 * `FfmPgQueryParserTest` in pgd-native pins these shapes against the real
 * parser.
 */
class ParseTreeTest {

    private fun rel(name: String) = """{"RangeVar":{"relname":"$name","inh":true}}"""

    private fun join(type: String, left: String, right: String) =
        """{"JoinExpr":{"jointype":"$type","larg":$left,"rarg":$right}}"""

    private fun select(from: String, extra: String = "") =
        """{"version":180004,"stmts":[{"stmt":{"SelectStmt":{"fromClause":[$from]$extra}}}]}"""

    private fun facts(json: String) = ParseTree.joinFacts(json)

    @Test
    fun `a left join nulls the right side only`() {
        val result = facts(select(join("JOIN_LEFT", rel("users"), rel("orders"))))!!
        assertEquals(setOf("orders"), result.nullableRelations)
        assertFalse(result.demotesEverything)
    }

    @Test
    fun `a right join nulls the left side only`() {
        assertEquals(
            setOf("users"),
            facts(select(join("JOIN_RIGHT", rel("users"), rel("orders"))))!!.nullableRelations,
        )
    }

    @Test
    fun `a full join nulls both sides`() {
        assertEquals(
            setOf("orders", "users"),
            facts(select(join("JOIN_FULL", rel("users"), rel("orders"))))!!.nullableRelations,
        )
    }

    @Test
    fun `an inner join nulls nothing`() {
        assertEquals(
            emptySet(),
            facts(select(join("JOIN_INNER", rel("users"), rel("orders"))))!!.nullableRelations,
        )
    }

    @Test
    fun `a nested join on a nullable side nulls everything beneath it`() {
        val inner = join("JOIN_INNER", rel("orders"), rel("items"))
        val result = facts(select(join("JOIN_LEFT", rel("users"), inner)))!!
        assertEquals(setOf("items", "orders"), result.nullableRelations)
    }

    @Test
    fun `relations inside a subquery on a nullable side are nulled`() {
        val subselect = """{"RangeSubselect":{"subquery":{"SelectStmt":{"fromClause":[${rel("orders")}]}}}}"""
        assertEquals(
            setOf("orders"),
            facts(select(join("JOIN_LEFT", rel("users"), subselect)))!!.nullableRelations,
        )
    }

    @Test
    fun `an outer join inside a CTE still counts`() {
        val cte = """
            {"version":180004,"stmts":[{"stmt":{"SelectStmt":{
              "withClause":{"ctes":[{"CommonTableExpr":{"ctename":"x","ctequery":{"SelectStmt":{
                "fromClause":[${join("JOIN_LEFT", rel("a"), rel("b"))}]}}}}]},
              "fromClause":[${rel("x")}]}}}]}
        """.trimIndent()
        assertEquals(setOf("b"), facts(cte)!!.nullableRelations)
    }

    @Test
    fun `the same relation on both sides of a self join resolves the safe way`() {
        val result = facts(select(join("JOIN_LEFT", rel("users"), rel("users"))))!!
        assertEquals(setOf("users"), result.nullableRelations)
    }

    @Test
    fun `relation names are compared in lower case`() {
        assertEquals(
            setOf("orders"),
            facts(select(join("JOIN_LEFT", rel("users"), rel("Orders"))))!!.nullableRelations,
        )
    }

    @Test
    fun `a grouping set demotes everything`() {
        val grouping = ""","groupClause":[{"GroupingSet":{"kind":"GROUPING_SET_ROLLUP"}}]"""
        val result = facts(select(rel("t"), grouping))!!
        assertTrue(result.demotesEverything)
    }

    @Test
    fun `a plain group by does not demote`() {
        val grouping = ""","groupClause":[{"ColumnRef":{"fields":[{"String":{"sval":"a"}}]}}]"""
        assertFalse(facts(select(rel("t"), grouping))!!.demotesEverything)
    }

    @Test
    fun `a query with no joins has nothing to null`() {
        val result = facts(select(rel("users")))!!
        assertEquals(emptySet(), result.nullableRelations)
        assertFalse(result.demotesEverything)
    }

    @Test
    fun `more than one statement is refused`() {
        val two = """{"version":180004,"stmts":[{"stmt":{"SelectStmt":{}}},{"stmt":{"SelectStmt":{}}}]}"""
        assertNull(facts(two))
    }

    @Test
    fun `malformed json is refused rather than thrown`() {
        assertNull(facts("not json at all"))
        assertNull(facts("[]"))
        assertNull(facts("{}"))
    }
}
