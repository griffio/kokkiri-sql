package io.pgdescribe.core

/** snake_case from Postgres to Kotlin names, and keyword-safe identifiers. */
internal object Naming {

    private val HARD_KEYWORDS = setOf(
        "as", "break", "class", "continue", "do", "else", "false", "for", "fun", "if", "in",
        "interface", "is", "null", "object", "package", "return", "super", "this", "throw",
        "true", "try", "typealias", "typeof", "val", "var", "when", "while",
    )

    private val SEPARATOR = Regex("""[_\s\-.]+""")

    fun lowerCamel(raw: String): String {
        val upper = upperCamel(raw)
        return upper.replaceFirstChar { it.lowercaseChar() }
    }

    fun upperCamel(raw: String): String {
        val parts = raw.split(SEPARATOR).filter { it.isNotEmpty() }
        if (parts.isEmpty()) return "Column"
        val joined = parts.joinToString("") { part ->
            // An already-camelCased part keeps its inner capitals.
            part.replaceFirstChar { it.uppercaseChar() }
        }
        return if (joined.first().isDigit()) "N$joined" else joined
    }

    /** Wraps Kotlin keywords in backticks so a column named `object` still works. */
    fun escape(identifier: String): String =
        if (identifier in HARD_KEYWORDS) "`$identifier`" else identifier

    fun propertyName(columnLabel: String): String = escape(lowerCamel(columnLabel))

    /** An enum label becomes a Kotlin entry name: `in progress` becomes `IN_PROGRESS`. */
    fun enumEntry(label: String): String {
        val cleaned = label.map { if (it.isLetterOrDigit() || it == '_') it else '_' }
            .joinToString("")
            .uppercase()
            .trim('_')
            .ifEmpty { "EMPTY" }
        return if (cleaned.first().isDigit()) "N_$cleaned" else cleaned
    }

    /** `users.sql` becomes `Users.kt`. */
    fun fileNameFor(sqlFileBaseName: String): String = upperCamel(sqlFileBaseName) + ".kt"

    /** SCREAMING_SNAKE constant holding a query's SQL. */
    fun constantName(queryName: String): String =
        queryName
            .replace(Regex("""([a-z0-9])([A-Z])"""), "$1_$2")
            .uppercase()
}
