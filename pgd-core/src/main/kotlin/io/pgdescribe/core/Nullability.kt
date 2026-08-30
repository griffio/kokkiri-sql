package io.pgdescribe.core

import java.sql.ResultSetMetaData

/**
 * Decides whether a result column can be NULL.
 *
 * Postgres' RowDescription carries no nullability, so the only real evidence is
 * the catalog entry for the base column a result column passes through.
 * pgjdbc already performs that lookup by table OID and attribute number, which
 * is more precise than matching on a table *name* — but it answers with the
 * base column's own constraint, ignoring anything in the query that can turn a
 * NOT NULL column into a NULL result.
 *
 * So its answer is only trusted for statements that contain none of those
 * constructs. Everything else is nullable. That is wrong only in the safe
 * direction: a column may be typed nullable when it could have been proven
 * non-null, never the reverse.
 *
 * When libpg_query is installed, [JoinFacts] narrows this from "the statement
 * contains an outer join, so nothing is provable" to "these relations are on a
 * nullable side". Without it the keyword scan below is the fallback, and it is
 * wrong only in the same safe direction.
 *
 * See `PgJdbcNullabilityProbeTest`, which pins the pgjdbc behaviour relied on
 * here so a change to it is noticed.
 */
internal object Nullability {

    private val DEMOTING = Regex(
        """\b(?:(?:LEFT|RIGHT|FULL)\s+(?:OUTER\s+)?JOIN|ROLLUP|CUBE|GROUPING\s+SETS)\b""",
        RegexOption.IGNORE_CASE,
    )

    /**
     * True when the statement contains something that can make an otherwise
     * NOT NULL column come back NULL. Run against masked SQL so a keyword
     * inside a string literal or comment does not count.
     */
    fun demotesEverything(maskedSql: String): Boolean = DEMOTING.containsMatchIn(maskedSql)

    fun resolve(
        query: ParsedQuery,
        maskedSql: String,
        metaData: ResultSetMetaData?,
        columns: List<ColumnInfo>,
        facts: JoinFacts?,
    ): List<Boolean> {
        val demoteAll = if (facts != null) facts.demotesEverything else demotesEverything(maskedSql)
        return columns.map { column ->
            val baseTable = column.baseTable?.lowercase()
            when {
                column.label in query.notNullOverrides -> false
                column.label in query.nullableOverrides -> true
                demoteAll -> true
                // The null check is not defensive: a column with no base
                // relation — an aggregate, an EXISTS, a scalar subquery — passes
                // through from no column at all, so the parse tree has nothing
                // to say about it and pgjdbc answers instead. Asking anyway
                // threw, because JoinFacts.nullableRelations is a sorted set and
                // TreeSet.contains(null) is specified to throw.
                facts != null && baseTable != null && baseTable in facts.nullableRelations -> true
                metaData == null -> true
                else -> runCatching {
                    metaData.isNullable(column.index) != ResultSetMetaData.columnNoNulls
                }.getOrDefault(true)
            }
        }
    }

    /** Overrides that name a column the query does not actually return. */
    fun unknownOverrides(query: ParsedQuery, columns: List<ColumnInfo>): List<Diagnostic> {
        val labels = columns.map { it.label }.toSet()
        return (query.notNullOverrides + query.nullableOverrides)
            .filterNot { it in labels }
            .sorted()
            .map { name ->
                Diagnostic(
                    severity = Severity.ERROR,
                    code = Diagnostic.UNKNOWN_OVERRIDE,
                    file = query.file.toString(),
                    line = query.headerLine,
                    query = query.name,
                    message = "Query '${query.name}' overrides nullability for '$name', " +
                        "which is not one of its result columns.",
                    hint = if (labels.isEmpty()) {
                        "This statement returns no columns."
                    } else {
                        "Its columns are: " + labels.joinToString(", ") + "."
                    },
                )
            }
    }
}
