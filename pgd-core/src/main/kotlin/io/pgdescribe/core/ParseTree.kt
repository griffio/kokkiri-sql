package io.pgdescribe.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * What a statement's parse tree says about nullability.
 *
 * Deliberately coarse: rather than tracking every output column back through
 * the target list — which breaks on `SELECT *` and needs alias resolution — it
 * answers one question, "which base relations can an outer join null?", and
 * lets the catalog answer the rest. A relation that appears on both a nullable
 * and a non-nullable side (a self-join with one outer arm) lands in
 * [nullableRelations], so the ambiguity resolves the safe way.
 */
public data class JoinFacts(
    /** Relation names, lowercased, that an outer join can turn into NULL. */
    val nullableRelations: Set<String>,
    /** ROLLUP, CUBE or GROUPING SETS is present, which can null any grouped column. */
    val demotesEverything: Boolean,
)

/**
 * Reads [JoinFacts] out of the JSON parse tree libpg_query produces.
 *
 * A relation on the nullable side may be a CTE rather than a table. That
 * matters because Postgres reports a column selected through a non-recursive
 * CTE as belonging to the table underneath it — so pgjdbc answers with that
 * table's NOT NULL constraint, and the name in the parse tree (`x`) never
 * matches the name in the RowDescription (`members`). A CTE on a nullable
 * side therefore also nulls every relation its query reads from, recursively.
 * Views need no such step: Postgres reports their columns as the view's own,
 * and a view column carries no NOT NULL constraint.
 */
public object ParseTree {

    private val json = Json { ignoreUnknownKeys = true }

    public fun joinFacts(parseTreeJson: String): JoinFacts? {
        val root = runCatching { json.parseToJsonElement(parseTreeJson) }.getOrNull() as? JsonObject
            ?: return null
        val statements = root["stmts"] as? JsonArray ?: return null
        // One statement per named query; anything else is not ours to reason about.
        if (statements.size != 1) return null

        val nullable = sortedSetOf<String>()
        var demotes = false

        // Every CTE in the statement, by name, wherever it is declared. Scope
        // is ignored on purpose: treating a CTE as visible everywhere can only
        // add relations to the nullable set, which is the safe direction.
        val ctes = HashMap<String, JsonElement>()
        walk(statements) { name, node ->
            if (name == "CommonTableExpr") {
                val cteName = node.text("ctename") ?: return@walk
                node["ctequery"]?.let { ctes[cteName.lowercase()] = it }
            }
        }

        walk(statements) { name, node ->
            when (name) {
                "JoinExpr" -> when (node.text("jointype")) {
                    "JOIN_LEFT" -> node["rarg"]?.let { collectRelations(it, nullable, ctes) }
                    "JOIN_RIGHT" -> node["larg"]?.let { collectRelations(it, nullable, ctes) }
                    "JOIN_FULL" -> {
                        node["larg"]?.let { collectRelations(it, nullable, ctes) }
                        node["rarg"]?.let { collectRelations(it, nullable, ctes) }
                    }

                    else -> Unit
                }

                // A plain GROUP BY has no GroupingSet node at all, so the mere
                // presence of one means ROLLUP, CUBE or GROUPING SETS.
                "GroupingSet" -> demotes = true
            }
        }

        return JoinFacts(nullableRelations = nullable, demotesEverything = demotes)
    }

    /**
     * Every relation named anywhere beneath [element], including inside
     * subqueries, and inside the query of any CTE named there. [expanded]
     * stops a recursive CTE, or two CTEs naming each other, from looping.
     */
    private fun collectRelations(
        element: JsonElement,
        into: MutableSet<String>,
        ctes: Map<String, JsonElement>,
        expanded: MutableSet<String> = HashSet(),
    ) {
        walk(element) { name, node ->
            if (name != "RangeVar") return@walk
            val relation = node.text("relname")?.lowercase() ?: return@walk
            into += relation
            val cte = ctes[relation] ?: return@walk
            if (expanded.add(relation)) collectRelations(cte, into, ctes, expanded)
        }
    }

    /** Visits every `{"NodeName": {...}}` pair in the tree, at any depth. */
    private fun walk(element: JsonElement, visit: (String, JsonObject) -> Unit) {
        when (element) {
            is JsonObject -> for ((name, value) in element) {
                if (value is JsonObject) visit(name, value)
                walk(value, visit)
            }

            is JsonArray -> element.forEach { walk(it, visit) }
            else -> Unit
        }
    }

    private fun JsonObject.text(key: String): String? =
        runCatching { this[key]?.jsonPrimitive?.content }.getOrNull()
}
