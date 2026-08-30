package io.pgdescribe.core

/**
 * Query files use native `$1` placeholders so they stay runnable in psql, but
 * JDBC only understands `?`. This rewrites one into the other.
 *
 * Every `$n` is replaced by `?` padded with spaces to exactly the same width,
 * so character offsets are identical in both strings and a position reported by
 * Postgres still points at the right column of the original file.
 *
 * The same rewrite is what the generated JDBC code will execute, so this is not
 * analysis-only scaffolding.
 */
internal object SqlRewriter {

    data class Rewritten(
        val jdbcSql: String,
        /** For each `?` in [jdbcSql], in order, the original `$n` it came from. */
        val bindings: List<Int>,
        /**
         * [jdbcSql] with every string literal, quoted identifier and comment
         * blanked to spaces, same length. Safe to run keyword searches over.
         */
        val masked: String,
    ) {
        /** Distinct original placeholders, ascending. */
        val distinct: List<Int> get() = bindings.distinct().sorted()
    }

    fun toJdbc(sql: String): Rewritten {
        val out = StringBuilder(sql.length)
        val masked = StringBuilder(sql.length)
        val bindings = mutableListOf<Int>()
        var i = 0

        // Newlines are kept while masking so line numbers survive.
        fun blank(from: Int, to: Int) {
            for (k in from until to) masked.append(if (sql[k] == '\n') '\n' else ' ')
        }

        while (i < sql.length) {
            val c = sql[i]
            when {
                c == '-' && sql.startsWith("--", i) -> {
                    val end = sql.indexOf('\n', i).let { if (it == -1) sql.length else it }
                    out.append(sql, i, end)
                    blank(i, end)
                    i = end
                }

                c == '/' && sql.startsWith("/*", i) -> {
                    var depth = 0
                    val start = i
                    while (i < sql.length) {
                        if (sql.startsWith("/*", i)) {
                            depth++
                            i += 2
                        } else if (sql.startsWith("*/", i)) {
                            depth--
                            i += 2
                            if (depth == 0) break
                        } else {
                            i++
                        }
                    }
                    out.append(sql, start, i)
                    blank(start, i)
                }

                c == '\'' || c == '"' -> {
                    val quote = c
                    val start = i
                    i++
                    while (i < sql.length) {
                        if (sql[i] == quote) {
                            // A doubled quote is an escaped quote, not the end.
                            if (i + 1 < sql.length && sql[i + 1] == quote) i += 2 else { i++; break }
                        } else {
                            i++
                        }
                    }
                    out.append(sql, start, i)
                    blank(start, i)
                }

                c == '$' -> {
                    val parameter = readParameter(sql, i)
                    if (parameter != null) {
                        val (number, end) = parameter
                        bindings += number
                        out.append('?')
                        masked.append('?')
                        repeat(end - i - 1) { out.append(' '); masked.append(' ') }
                        i = end
                    } else {
                        val tagEnd = readDollarTag(sql, i)
                        if (tagEnd == null) {
                            out.append(c)
                            masked.append(c)
                            i++
                        } else {
                            val tag = sql.substring(i, tagEnd)
                            val close = sql.indexOf(tag, tagEnd).let { if (it == -1) sql.length else it + tag.length }
                            out.append(sql, i, close)
                            blank(i, close)
                            i = close
                        }
                    }
                }

                else -> {
                    out.append(c)
                    masked.append(c)
                    i++
                }
            }
        }

        return Rewritten(out.toString(), bindings, masked.toString())
    }

    /** `$12` at [start] returns 12 and the index just past the digits. */
    private fun readParameter(sql: String, start: Int): Pair<Int, Int>? {
        var end = start + 1
        while (end < sql.length && sql[end].isDigit()) end++
        if (end == start + 1) return null
        val number = sql.substring(start + 1, end).toIntOrNull() ?: return null
        return number to end
    }

    /** `$$` or `$tag$` at [start] returns the index just past the opening tag. */
    private fun readDollarTag(sql: String, start: Int): Int? {
        var end = start + 1
        while (end < sql.length && (sql[end].isLetterOrDigit() || sql[end] == '_')) end++
        if (end >= sql.length || sql[end] != '$') return null
        if (end > start + 1 && sql[start + 1].isDigit()) return null
        return end + 1
    }
}
