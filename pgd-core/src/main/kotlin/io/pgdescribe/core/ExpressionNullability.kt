package io.pgdescribe.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Result columns whose *expression* cannot be NULL, whatever the data.
 *
 * pgjdbc reports nothing about a column that is not a straight passthrough
 * from a base column — an aggregate, an `EXISTS`, a subquery — so without this
 * every such column is nullable and needs a hand-written `-- notnull:`. Some of
 * them are non-null by definition in SQL, and the parse tree says which:
 *
 * - `count(...)` without `OVER`, which counts zero rows as 0, not NULL;
 * - `EXISTS (...)`, `ARRAY(...)`, `IS [NOT] NULL` and `IS [NOT] TRUE`-style tests;
 * - a scalar subquery whose only target is `count(...)` and which has no
 *   `GROUP BY`, `HAVING`, `LIMIT` or `OFFSET`, so it always yields one row;
 * - a non-NULL literal, and a cast of anything proven here;
 * - `COALESCE` with at least one argument proven here;
 * - `+`, `-` and `*` over operands proven here — this assumes the operator
 *   is a built-in one, which never returns NULL for non-NULL input;
 * - a column of a CTE or subquery that is not on the nullable side of an
 *   outer join, nor grouped by `ROLLUP`/`CUBE`/`GROUPING SETS`, whose own
 *   expression is proven here.
 *
 * Anything else is "not proven", never "nullable": the caller falls back to
 * the catalog and the outer-join rule, so this can only remove a `?`.
 *
 * Proofs are positional against the RowDescription, so a target list
 * containing `*` — whose expansion the parse tree cannot see — or a set
 * operation is not analysed at all.
 */
internal object ExpressionNullability {

    private const val MAX_DEPTH = 32
    private val ARITHMETIC = setOf("+", "-", "*")

    /**
     * The 1-based result positions proven non-null, and how many result
     * columns the target list describes; null when it cannot be matched
     * positionally.
     */
    fun analyze(statement: JsonObject): Pair<Set<Int>, Int>? {
        val select = statement.node("SelectStmt") ?: return null
        val targets = select.plainTargets() ?: return null
        val scope = Scope.of(select, emptyMap())
        val proven = targets.withIndex()
            .filter { (_, target) -> target["val"]?.let { scope.nonNull(it, 0) } == true }
            .map { it.index + 1 }
            .toSet()
        return proven to targets.size
    }

    /** Where a qualified column reference reads from. */
    private class Source(
        /** The CTE or subquery body; null for a table, view or function. */
        val select: JsonObject?,
        /** On the nullable side of an outer join in this scope. */
        val nullable: Boolean,
        val ctes: Map<String, JsonObject>,
    )

    /** One `SELECT`'s view of the names it can resolve. */
    private class Scope(
        /** Range entries by alias, or by name when unaliased. Null marks a name used twice. */
        private val from: Map<String, Source?>,
        private val groupingSets: Boolean,
    ) {

        fun nonNull(element: JsonElement, depth: Int): Boolean {
            if (depth > MAX_DEPTH) return false
            val (kind, node) = (element as? JsonObject)?.single() ?: return false
            return when (kind) {
                "TypeCast" -> node["arg"]?.let { nonNull(it, depth + 1) } == true
                "A_Const" -> node.flag("isnull") != true
                "FuncCall" -> isCount(node)
                "NullTest", "BooleanTest" -> true
                "SubLink" -> when (node.text("subLinkType")) {
                    "EXISTS_SUBLINK", "ARRAY_SUBLINK" -> true
                    "EXPR_SUBLINK" -> isCountSubquery(node["subselect"])
                    // ANY, ALL and row comparisons yield NULL when a NULL is compared.
                    else -> false
                }

                "CoalesceExpr" -> node.list("args").any { nonNull(it, depth + 1) }
                "A_Expr" -> {
                    val operator = node.list("name").singleOrNull()?.string()
                    val left = node["lexpr"]
                    val right = node["rexpr"]
                    node.text("kind") == "AEXPR_OP" &&
                        operator in ARITHMETIC &&
                        right != null && nonNull(right, depth + 1) &&
                        // A unary minus has no left operand.
                        (left == null || nonNull(left, depth + 1))
                }

                "ColumnRef" -> column(node, depth)
                else -> false
            }
        }

        private fun column(node: JsonObject, depth: Int): Boolean {
            // ROLLUP and friends null grouped columns in their subtotal rows.
            if (groupingSets) return false
            val fields = node.list("fields").map { it.string() ?: return false }
            val (source, name) = when (fields.size) {
                1 -> (from.values.singleOrNull() ?: return false) to fields[0]
                2 -> (from[fields[0]] ?: return false) to fields[1]
                else -> return false
            }
            if (source.nullable) return false
            val select = source.select ?: return false
            val targets = select.plainTargets() ?: return false
            val target = targets.filter { outputName(it) == name }.singleOrNull() ?: return false
            val expression = target["val"] ?: return false
            return of(select, source.ctes).nonNull(expression, depth + 1)
        }

        companion object {
            fun of(select: JsonObject, outerCtes: Map<String, JsonObject>): Scope {
                val ctes = HashMap(outerCtes)
                select.node("withClause")?.list("ctes")?.forEach { cte ->
                    val (kind, node) = (cte as? JsonObject)?.single() ?: return@forEach
                    if (kind != "CommonTableExpr") return@forEach
                    val name = node.text("ctename") ?: return@forEach
                    // A data-modifying CTE has no SELECT to look into.
                    val body = node.node("ctequery")?.node("SelectStmt")
                    if (body != null) ctes[name] = body else ctes.remove(name)
                }

                val from = HashMap<String, Source?>()
                fun register(name: String, source: Source) {
                    from[name] = if (name in from) null else source
                }

                fun collect(element: JsonElement, nullable: Boolean) {
                    val (kind, node) = (element as? JsonObject)?.single() ?: return
                    when (kind) {
                        "RangeVar" -> {
                            val relation = node.text("relname") ?: return
                            val name = node.node("alias")?.text("aliasname") ?: relation
                            val cte = if (node["schemaname"] == null) ctes[relation] else null
                            register(name, Source(cte, nullable, ctes))
                        }

                        "RangeSubselect" -> {
                            val name = node.node("alias")?.text("aliasname") ?: return
                            register(name, Source(node.node("subquery")?.node("SelectStmt"), nullable, ctes))
                        }

                        "JoinExpr" -> {
                            val type = node.text("jointype")
                            node["larg"]?.let { collect(it, nullable || type == "JOIN_RIGHT" || type == "JOIN_FULL") }
                            node["rarg"]?.let { collect(it, nullable || type == "JOIN_LEFT" || type == "JOIN_FULL") }
                        }

                        else -> node.node("alias")?.text("aliasname")?.let { register(it, Source(null, nullable, ctes)) }
                    }
                }
                select.list("fromClause").forEach { collect(it, nullable = false) }

                return Scope(from, groupingSets = select["groupClause"]?.let(::containsGroupingSet) == true)
            }
        }
    }

    /** `count(...)` resolved to the built-in, as an aggregate rather than a window function. */
    private fun isCount(node: JsonObject): Boolean {
        val name = node.list("funcname").map { it.string() ?: return false }
        val builtin = name == listOf("count") || name == listOf("pg_catalog", "count")
        return builtin && node["over"] == null
    }

    /** `(SELECT count(...) FROM ...)`: an ungrouped aggregate, so exactly one row. */
    private fun isCountSubquery(element: JsonElement?): Boolean {
        val select = (element as? JsonObject)?.node("SelectStmt") ?: return false
        val target = select.plainTargets()?.singleOrNull() ?: return false
        val oneRow = listOf("groupClause", "havingClause", "limitCount", "limitOffset").none { it in select }
        return oneRow && unwrapCasts(target["val"])?.let { (kind, node) -> kind == "FuncCall" && isCount(node) } == true
    }

    private fun unwrapCasts(element: JsonElement?): Pair<String, JsonObject>? {
        var current = (element as? JsonObject)?.single() ?: return null
        while (current.first == "TypeCast") {
            current = (current.second["arg"] as? JsonObject)?.single() ?: return null
        }
        return current
    }

    /** The column name Postgres gives a target, for the shapes worth resolving. */
    private fun outputName(target: JsonObject): String? {
        target.text("name")?.let { return it }
        var (kind, node) = (target["val"] as? JsonObject)?.single() ?: return null
        while (kind == "TypeCast") {
            val arg = (node["arg"] as? JsonObject)?.single() ?: return null
            kind = arg.first
            node = arg.second
        }
        return when (kind) {
            "ColumnRef" -> node.list("fields").lastOrNull()?.string()
            "FuncCall" -> node.list("funcname").lastOrNull()?.string()
            "CoalesceExpr" -> "coalesce"
            "SubLink" -> when (node.text("subLinkType")) {
                "EXISTS_SUBLINK" -> "exists"
                "ARRAY_SUBLINK" -> "array"
                else -> null
            }

            else -> null
        }
    }

    /**
     * The `ResTarget`s of a SELECT that is neither a set operation nor VALUES,
     * and whose target list has no `*`; null otherwise.
     */
    private fun JsonObject.plainTargets(): List<JsonObject>? {
        val op = text("op")
        if (op != null && op != "SETOP_NONE") return null
        val targets = list("targetList").map { element ->
            val (kind, node) = (element as? JsonObject)?.single() ?: return null
            if (kind != "ResTarget") return null
            node
        }
        if (targets.isEmpty() || targets.any { containsStar(it) }) return null
        return targets
    }

    private fun containsStar(element: JsonElement): Boolean = when (element) {
        is JsonObject -> "A_Star" in element || element.values.any(::containsStar)
        is JsonArray -> element.any(::containsStar)
        else -> false
    }

    private fun containsGroupingSet(element: JsonElement): Boolean = when (element) {
        is JsonObject -> "GroupingSet" in element || element.values.any(::containsGroupingSet)
        is JsonArray -> element.any(::containsGroupingSet)
        else -> false
    }

    /** A `{"NodeName": {...}}` wrapper, unwrapped. */
    private fun JsonObject.single(): Pair<String, JsonObject>? {
        val entry = entries.singleOrNull() ?: return null
        return entry.key to (entry.value as? JsonObject ?: return null)
    }

    private fun JsonObject.node(key: String): JsonObject? = this[key] as? JsonObject

    private fun JsonObject.list(key: String): List<JsonElement> = (this[key] as? JsonArray).orEmpty()

    private fun JsonObject.text(key: String): String? =
        runCatching { this[key]?.jsonPrimitive?.content }.getOrNull()

    private fun JsonObject.flag(key: String): Boolean? =
        runCatching { this[key]?.jsonPrimitive?.booleanOrNull }.getOrNull()

    /** The value of a `{"String": {"sval": ...}}` node. */
    private fun JsonElement.string(): String? = (this as? JsonObject)?.node("String")?.text("sval")
}
