package io.pgdescribe.core

import java.sql.SQLException
import org.postgresql.util.PSQLException

/** Turns a Postgres error into a diagnostic that points at a real file and line. */
internal object SqlErrors {

    fun toDiagnostic(
        e: SQLException,
        code: String,
        file: String,
        sql: String,
        sqlStartLine: Int,
        queryName: String?,
        messagePrefix: String = "",
    ): Diagnostic {
        val server = (e as? PSQLException)?.serverErrorMessage
        val position = server?.position ?: 0
        val (lineOffset, column) = if (position > 0) offsetToLineColumn(sql, position) else 0 to null

        return Diagnostic(
            severity = Severity.ERROR,
            code = code,
            file = file,
            line = sqlStartLine + lineOffset,
            column = column,
            query = queryName,
            message = messagePrefix + (server?.message ?: e.message ?: "SQL error"),
            sqlState = e.sqlState,
            detail = server?.detail,
            // Postgres' own hint names the actual column it thinks you meant, so
            // it beats anything we can synthesise. Ours is the fallback.
            hint = server?.hint ?: hintFor(e.sqlState),
        )
    }

    /**
     * Postgres reports a 1-based character offset into the statement it was
     * sent. Convert that to (lines past the statement's first line, column).
     */
    internal fun offsetToLineColumn(sql: String, position: Int): Pair<Int, Int> {
        val index = (position - 1).coerceIn(0, maxOf(sql.length - 1, 0))
        var line = 0
        var lastNewline = -1
        for (i in 0 until index) {
            if (sql[i] == '\n') {
                line++
                lastNewline = i
            }
        }
        return line to (index - lastNewline)
    }

    /** Instruction-shaped hints for the SQLSTATEs a query file actually hits. */
    private fun hintFor(sqlState: String?): String? = when (sqlState) {
        "42703" -> "Column does not exist. Check the current schema in schema.md, or add a migration."
        "42P01" -> "Table or view does not exist. Check the migrations directory for its real name."
        "42P18" -> "Postgres could not infer a parameter's type. Add an explicit cast, e.g. \$1::uuid."
        "42883" -> "No function matches that name and argument types. Cast the arguments explicitly."
        "42601" -> "Syntax error. The caret position above points at the first token Postgres rejected."
        else -> null
    }
}
