package io.pgdescribe.core

import java.nio.file.Path
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText
import org.tomlj.Toml
import org.tomlj.TomlTable

/**
 * `pgd.toml`, read from the project directory. Every key is optional, and any
 * command-line flag wins over the file.
 *
 * ```toml
 * package = "com.example.db"
 * output  = "src/main/kotlin"
 *
 * [types]
 * # Treat a Postgres type as another Postgres type. Useful for extension types
 * # pgd does not know, or to opt an enum out of getting its own Kotlin class.
 * citext = "text"
 * mood   = "text"
 * ```
 */
/** How hard pgd tries to prove a column non-null. */
public enum class NullabilityMode {
    /** Use Postgres' parser when it is installed, the conservative rule when it is not. */
    AUTO,

    /** Never use the parser, so output is identical on every machine. */
    CONSERVATIVE,

    /** Require the parser; fail the run if it is not installed. */
    PRECISE,
    ;

    public companion object {
        public fun parse(value: String): NullabilityMode? =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) }

        public val names: String get() = entries.joinToString(", ") { it.name.lowercase() }
    }
}

public data class PgdConfig(
    val packageName: String = DEFAULT_PACKAGE,
    val output: String = DEFAULT_OUTPUT,
    val migrations: String = "migrations",
    val queries: String = "queries",
    /** Postgres type name to another Postgres type name. */
    val typeAliases: Map<String, String> = emptyMap(),
    val nullability: NullabilityMode = NullabilityMode.AUTO,
) {
    public companion object {
        public const val FILE_NAME: String = "pgd.toml"
        public const val DEFAULT_PACKAGE: String = "db"
        public const val DEFAULT_OUTPUT: String = "build/generated/pgd/kotlin"

        private val PACKAGE = Regex("""^[A-Za-z_][A-Za-z0-9_]*(\.[A-Za-z_][A-Za-z0-9_]*)*$""")

        /** Returns defaults when the file is absent; diagnostics when it is unusable. */
        public fun load(directory: Path): Pair<PgdConfig, List<Diagnostic>> {
            val file = directory.resolve(FILE_NAME)
            if (!file.isRegularFile()) return PgdConfig() to emptyList()

            fun problem(message: String, hint: String, line: Int = 1) = Diagnostic(
                severity = Severity.ERROR,
                code = Diagnostic.CONFIG_ERROR,
                file = file.toString(),
                line = line,
                message = message,
                hint = hint,
            )

            val parsed = runCatching { Toml.parse(file.readText()) }.getOrElse { e ->
                return PgdConfig() to listOf(
                    problem("Could not read $FILE_NAME: ${e.message}", "Check the file is valid UTF-8 TOML."),
                )
            }
            if (parsed.hasErrors()) {
                return PgdConfig() to parsed.errors().map { error ->
                    problem(
                        message = error.message ?: "Invalid TOML.",
                        hint = "Fix the TOML syntax in $FILE_NAME.",
                        line = error.position().line(),
                    )
                }
            }

            val diagnostics = mutableListOf<Diagnostic>()

            // tomlj throws rather than returning null when a key holds the wrong
            // type, so every read is guarded and reported as a diagnostic.
            fun string(key: String, default: String): String = when {
                !parsed.contains(key) -> default
                parsed.isString(key) -> parsed.getString(key) ?: default
                else -> {
                    diagnostics += problem(
                        "$key must be a string.",
                        "For example: $key = \"${default}\".",
                    )
                    default
                }
            }

            val packageName = string("package", DEFAULT_PACKAGE)
            if (!PACKAGE.matches(packageName)) {
                diagnostics += problem(
                    "'$packageName' is not a valid Kotlin package name.",
                    "Use dot-separated identifiers, e.g. package = \"com.example.db\".",
                )
            }

            val aliases = mutableMapOf<String, String>()
            // Explicit types throughout: tomlj carries checkerframework annotations
            // Kotlin cannot see, and inferring through them is an error in 2.4.
            val types: TomlTable? = parsed.getTable("types")
            if (types != null) {
                for (key in types.keySet()) {
                    val path = listOf(key)
                    val value: String? = if (types.isString(path)) types.getString(path) else null
                    if (value == null) {
                        diagnostics += problem(
                            "[types] $key must be a string naming another Postgres type.",
                            "For example: $key = \"text\".",
                        )
                    } else {
                        aliases[key.lowercase()] = value.lowercase()
                    }
                }
            }

            val nullabilityName = string("nullability", NullabilityMode.AUTO.name.lowercase())
            val nullability = NullabilityMode.parse(nullabilityName)
            if (nullability == null) {
                diagnostics += problem(
                    "'$nullabilityName' is not a nullability mode.",
                    "Use one of ${NullabilityMode.names}.",
                )
            }

            val config = PgdConfig(
                packageName = packageName,
                output = string("output", DEFAULT_OUTPUT),
                migrations = string("migrations", "migrations"),
                queries = string("queries", "queries"),
                typeAliases = aliases,
                nullability = nullability ?: NullabilityMode.AUTO,
            )
            return config to diagnostics
        }
    }
}
