package io.pgdescribe.core

import java.sql.Connection
import java.sql.SQLException
import org.postgresql.PGResultSetMetaData

public data class ParamInfo(
    /** The `$n` as written in the query file. */
    val index: Int,
    val name: String?,
    val typeName: String?,
    /** How many times `$n` appears; each occurrence is a separate JDBC bind. */
    val occurrences: Int,
)

public data class ColumnInfo(
    val index: Int,
    val label: String,
    val typeName: String?,
    /** Non-null when the column is a straight passthrough from a base relation. */
    val baseTable: String?,
    val baseColumn: String?,
    /** See [Nullability]; conservative, so this may be true where a proof was possible. */
    val nullable: Boolean = true,
)

public data class AnalyzedQuery(
    val query: ParsedQuery,
    val params: List<ParamInfo>,
    val columns: List<ColumnInfo>,
    /** The `?`-placeholder form actually sent to the server, and later generated. */
    val jdbcSql: String,
    /** For each `?` in [jdbcSql], the `$n` whose value is bound there. */
    val bindings: List<Int>,
)

public data class QueryAnalysis(
    val analyzed: AnalyzedQuery?,
    val diagnostics: List<Diagnostic>,
)

/**
 * Asks Postgres what a statement means, instead of parsing it ourselves.
 *
 * `prepareStatement` is lazy in pgjdbc; requesting the metadata is what forces
 * the Parse/Describe round trip, so both calls are guarded.
 */
public class QueryAnalyzer(
    private val connection: Connection,
    /** Postgres' own parser, when one is installed. Null falls back to the keyword rule. */
    private val parser: SqlParser? = null,
) {

    public fun analyze(query: ParsedQuery): QueryAnalysis =
        if (query.cardinality.isCopy) analyzeCopy(query) else analyzePrepared(query)

    /**
     * COPY is described through a stand-in SELECT, so the column list is checked
     * by Postgres exactly like every other statement.
     */
    private fun analyzeCopy(query: ParsedQuery): QueryAnalysis {
        val (copy, problems) = CopyStatement.parse(query)
        if (copy == null) return QueryAnalysis(null, problems)

        return try {
            connection.prepareStatement(copy.describeSql()).use { statement ->
                val resultMetaData = statement.metaData
                val columnCount = resultMetaData?.columnCount ?: 0
                val columns = (1..columnCount).map { i ->
                    ColumnInfo(
                        index = i,
                        label = copy.columns[i - 1],
                        typeName = runCatching { resultMetaData!!.getColumnTypeName(i) }.getOrNull(),
                        baseTable = copy.table,
                        baseColumn = copy.columns[i - 1],
                        // A NOT NULL column must be supplied, so the field is non-null.
                        nullable = runCatching {
                            resultMetaData!!.isNullable(i) != java.sql.ResultSetMetaData.columnNoNulls
                        }.getOrDefault(true),
                    )
                }
                QueryAnalysis(
                    analyzed = AnalyzedQuery(
                        query = query,
                        params = emptyList(),
                        columns = columns,
                        // COPY is sent verbatim; there is nothing to rewrite.
                        jdbcSql = query.sql,
                        bindings = emptyList(),
                    ),
                    diagnostics = emptyList(),
                )
            }
        } catch (e: SQLException) {
            QueryAnalysis(
                analyzed = null,
                diagnostics = listOf(
                    SqlErrors.toDiagnostic(
                        e = e,
                        code = Diagnostic.SQL_ERROR,
                        file = query.file.toString(),
                        sql = query.sql,
                        sqlStartLine = query.sqlStartLine,
                        queryName = query.name,
                    ),
                ),
            )
        }
    }

    private fun analyzePrepared(query: ParsedQuery): QueryAnalysis {
        val diagnostics = mutableListOf<Diagnostic>()
        val rewritten = SqlRewriter.toJdbc(query.sql)

        fun error(code: String, message: String, hint: String) {
            diagnostics += Diagnostic(
                severity = Severity.ERROR,
                code = code,
                file = query.file.toString(),
                line = query.sqlStartLine,
                query = query.name,
                message = message,
                hint = hint,
            )
        }

        return try {
            connection.prepareStatement(rewritten.jdbcSql).use { statement ->
                val parameterMetaData = statement.parameterMetaData
                val resultMetaData = statement.metaData

                val typesByPlaceholder = LinkedHashMap<Int, MutableList<String?>>()
                rewritten.bindings.forEachIndexed { position, original ->
                    val typeName = runCatching {
                        parameterMetaData.getParameterTypeName(position + 1)
                    }.getOrNull()
                    typesByPlaceholder.getOrPut(original) { mutableListOf() } += typeName
                }

                val params = typesByPlaceholder.entries.sortedBy { it.key }.map { (original, types) ->
                    val typeName = types.first()
                    if (typeName.isNullOrBlank() || typeName in UNRESOLVED) {
                        error(
                            Diagnostic.UNRESOLVED_PARAM,
                            "Postgres could not determine the type of parameter \$$original.",
                            "Add an explicit cast at the placeholder, e.g. \$$original::uuid.",
                        )
                    } else if (types.distinct().size > 1) {
                        error(
                            Diagnostic.PARAM_TYPE_CONFLICT,
                            "Parameter \$$original is used in ${types.size} places and Postgres inferred " +
                                "different types for them: ${types.joinToString(", ") { it ?: "unknown" }}.",
                            "Cast every occurrence to the same type, e.g. \$$original::text.",
                        )
                    }
                    ParamInfo(
                        index = original,
                        name = query.paramNames.getOrNull(original - 1),
                        typeName = typeName,
                        occurrences = types.size,
                    )
                }

                val columnCount = resultMetaData?.columnCount ?: 0
                val base = resultMetaData as? PGResultSetMetaData
                val described = (1..columnCount).map { i ->
                    ColumnInfo(
                        index = i,
                        label = resultMetaData!!.getColumnLabel(i),
                        typeName = runCatching { resultMetaData.getColumnTypeName(i) }.getOrNull(),
                        baseTable = runCatching { base?.getBaseTableName(i) }.getOrNull()?.ifEmpty { null },
                        baseColumn = runCatching { base?.getBaseColumnName(i) }.getOrNull()?.ifEmpty { null },
                    )
                }

                // The parse tree is taken from the original `$n` form: `?` is not
                // valid Postgres syntax and libpg_query would reject it.
                val facts = parser?.parseTreeJson(query.sql)?.let { ParseTree.joinFacts(it) }
                val nullable = Nullability.resolve(query, rewritten.masked, resultMetaData, described, facts)
                val columns = described.mapIndexed { i, column -> column.copy(nullable = nullable[i]) }

                diagnostics += Nullability.unknownOverrides(query, columns)
                diagnostics += checkCardinality(query, columnCount)

                QueryAnalysis(
                    analyzed = AnalyzedQuery(
                        query = query,
                        params = params,
                        columns = columns,
                        jdbcSql = rewritten.jdbcSql,
                        bindings = rewritten.bindings,
                    ),
                    diagnostics = diagnostics,
                )
            }
        } catch (e: SQLException) {
            QueryAnalysis(
                analyzed = null,
                diagnostics = diagnostics + SqlErrors.toDiagnostic(
                    e = e,
                    code = Diagnostic.SQL_ERROR,
                    file = query.file.toString(),
                    sql = query.sql,
                    sqlStartLine = query.sqlStartLine,
                    queryName = query.name,
                ),
            )
        }
    }

    private fun checkCardinality(query: ParsedQuery, columnCount: Int): List<Diagnostic> = when {
        query.cardinality.expectsRows && columnCount == 0 -> listOf(
            Diagnostic(
                severity = Severity.ERROR,
                code = Diagnostic.CARDINALITY_MISMATCH,
                file = query.file.toString(),
                line = query.headerLine,
                query = query.name,
                message = "Query '${query.name}' is declared ${query.cardinality.tag} " +
                    "but returns no columns.",
                hint = "Add a RETURNING clause, or change the tag to ${Cardinality.EXEC.tag} " +
                    "or ${Cardinality.EXECROWS.tag}.",
            ),
        )

        !query.cardinality.expectsRows && columnCount > 0 -> listOf(
            Diagnostic(
                severity = Severity.WARNING,
                code = Diagnostic.CARDINALITY_MISMATCH,
                file = query.file.toString(),
                line = query.headerLine,
                query = query.name,
                message = "Query '${query.name}' is declared ${query.cardinality.tag} " +
                    "but returns $columnCount column(s), which will be discarded.",
                hint = "Change the tag to ${Cardinality.MANY.tag} or ${Cardinality.ONE.tag} to read them.",
            ),
        )

        else -> emptyList()
    }

    private companion object {
        val UNRESOLVED = setOf("unknown", "???", "void")
    }
}
