package io.pgdescribe.core

/**
 * `COPY <table> (<columns>) FROM STDIN` — the only shape pgd generates a bulk
 * loader for.
 *
 * COPY cannot be prepared, so the usual describe path does not work. Instead the
 * column list is turned into `SELECT ... FROM <table> WHERE false`, which
 * Postgres *will* describe: that validates the table and every column name, and
 * hands back exactly the types and constraints the loader needs. The oracle
 * stays the oracle.
 */
internal data class CopyStatement(
    val table: String,
    val columns: List<String>,
) {
    /** A statement Postgres will describe, standing in for the COPY. */
    fun describeSql(): String =
        "SELECT " + columns.joinToString(", ") { "\"$it\"" } + " FROM $table WHERE false"

    companion object {
        private val FORM = Regex(
            """^\s*COPY\s+([\w."$]+)\s*\(([^)]*)\)\s+FROM\s+STDIN\s*$""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        )

        fun parse(query: ParsedQuery): Pair<CopyStatement?, List<Diagnostic>> {
            fun problem(message: String, hint: String) = Diagnostic(
                severity = Severity.ERROR,
                code = Diagnostic.COPY_FORM,
                file = query.file.toString(),
                line = query.sqlStartLine,
                query = query.name,
                message = message,
                hint = hint,
            )

            val match = FORM.matchEntire(SqlRewriter.toJdbc(query.sql).masked.trim())
                ?: return null to listOf(
                    problem(
                        "Query '${query.name}' is declared ${Cardinality.COPY.tag} but is not a " +
                            "COPY ... FROM STDIN statement naming its columns.",
                        "Write it as `COPY table (column, column) FROM STDIN`.",
                    ),
                )

            val columns = match.groupValues[2]
                .split(',')
                .map { it.trim().trim('"') }
                .filter { it.isNotEmpty() }
            if (columns.isEmpty()) {
                return null to listOf(
                    problem(
                        "Query '${query.name}' names no columns to copy.",
                        "List them explicitly: `COPY table (column, column) FROM STDIN`.",
                    ),
                )
            }

            val duplicate = columns.groupingBy { it }.eachCount().filterValues { it > 1 }.keys.sorted()
            if (duplicate.isNotEmpty()) {
                return null to listOf(
                    problem(
                        "Query '${query.name}' names column(s) ${duplicate.joinToString(", ")} twice.",
                        "Each column may appear once in a COPY column list.",
                    ),
                )
            }

            return CopyStatement(match.groupValues[1], columns) to emptyList()
        }
    }
}
