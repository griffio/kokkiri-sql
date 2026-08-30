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

/** Reads [JoinFacts] out of the JSON parse tree libpg_query produces. */
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

        walk(statements) { name, node ->
            when (name) {
                "JoinExpr" -> when (node.text("jointype")) {
                    "JOIN_LEFT" -> node["rarg"]?.let { collectRelations(it, nullable) }
                    "JOIN_RIGHT" -> node["larg"]?.let { collectRelations(it, nullable) }
                    "JOIN_FULL" -> {
                        node["larg"]?.let { collectRelations(it, nullable) }
                        node["rarg"]?.let { collectRelations(it, nullable) }
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

    /** Every relation named anywhere beneath [element], including inside subqueries. */
    private fun collectRelations(element: JsonElement, into: MutableSet<String>) {
        walk(element) { name, node ->
            if (name == "RangeVar") node.text("relname")?.let { into += it.lowercase() }
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
