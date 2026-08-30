package io.pgdescribe.core

import kotlinx.serialization.Serializable

/**
 * Bumped whenever generated output could change. Build tools use it as a cache
 * key so upgrading pgd re-runs generation.
 */
public const val PGD_VERSION: String = "0.5.0"

@Serializable
public enum class Severity { ERROR, WARNING }

/**
 * A single problem found by `pgd check`, shaped to be acted on directly by a
 * human or a model: it always names a file and line, and carries a [hint]
 * phrased as an instruction whenever we can produce one.
 */
@Serializable
public data class Diagnostic(
    val severity: Severity,
    val code: String,
    val file: String,
    val line: Int,
    val column: Int? = null,
    val query: String? = null,
    val message: String,
    val sqlState: String? = null,
    val detail: String? = null,
    val hint: String? = null,
) {
    public companion object {
        /** Postgres itself rejected the statement. */
        public const val SQL_ERROR: String = "PGD1001"

        /** The `:many` / `:one` / `:exec` tag disagrees with what the statement returns. */
        public const val CARDINALITY_MISMATCH: String = "PGD1002"

        /** Postgres could not infer a parameter's type. */
        public const val UNRESOLVED_PARAM: String = "PGD1003"

        /** Declared parameter names do not match the number of placeholders. */
        public const val PARAM_NAME_ARITY: String = "PGD1004"

        /** `$1` and `$3` used but not `$2`. */
        public const val PARAM_GAP: String = "PGD1005"

        /** The same `$n` was inferred as two different types in two places. */
        public const val PARAM_TYPE_CONFLICT: String = "PGD1006"

        /** A `-- nullable:`/`-- notnull:` override names a column that is not returned. */
        public const val UNKNOWN_OVERRIDE: String = "PGD1007"

        /** No Kotlin type is known for a Postgres type the query returns or takes. */
        public const val UNMAPPED_TYPE: String = "PGD4001"

        /** Two result columns share a label, so they cannot become one data class. */
        public const val DUPLICATE_COLUMN_LABEL: String = "PGD4002"

        /** Two parameters would generate the same Kotlin name. */
        public const val DUPLICATE_PARAM_NAME: String = "PGD4003"

        /** `-- batch` on a statement that cannot be batched. */
        public const val BATCH_NOT_APPLICABLE: String = "PGD1008"

        /** A `:copy` statement is not in the form pgd can generate for. */
        public const val COPY_FORM: String = "PGD1009"

        public const val DUPLICATE_QUERY_NAME: String = "PGD2001"
        public const val MALFORMED_HEADER: String = "PGD2002"
        public const val UNKNOWN_CARDINALITY: String = "PGD2003"
        public const val ORPHAN_SQL: String = "PGD2004"
        public const val EMPTY_QUERY: String = "PGD2005"

        /** Two Postgres enums, or two labels, collapse to the same Kotlin name. */
        public const val ENUM_COLLISION: String = "PGD4004"

        /** pgd.toml is unreadable or has a bad value. */
        public const val CONFIG_ERROR: String = "PGD5001"

        /** nullability = "precise" but no parser is installed. */
        public const val NATIVE_UNAVAILABLE: String = "PGD5002"

        public const val MIGRATION_FAILED: String = "PGD3001"
        public const val NO_MIGRATIONS: String = "PGD3002"
        public const val NO_QUERIES: String = "PGD3003"
    }
}

@Serializable
public data class CheckReport(
    val ok: Boolean,
    val migrationsApplied: Int,
    val queriesChecked: Int,
    val diagnostics: List<Diagnostic>,
) {
    val errorCount: Int get() = diagnostics.count { it.severity == Severity.ERROR }
    val warningCount: Int get() = diagnostics.count { it.severity == Severity.WARNING }
}
