package io.pgdescribe.core

/** How a value is read from a `ResultSet` and written to a `PreparedStatement`. */
internal enum class Access {
    /**
     * A JDBC primitive getter, which returns 0/false for SQL NULL, so a nullable
     * read has to consult `wasNull()`.
     */
    PRIMITIVE,

    /** A getter that already returns null for SQL NULL. */
    REFERENCE,

    /** `getObject(index, T::class.java)` / `setObject(index, value)`. */
    OBJECT,
}

internal data class SqlType(
    /** Kotlin type as written in generated code. */
    val kotlin: String,
    /** Import the generated file needs, if any. */
    val import: String?,
    val access: Access,
    /** JDBC accessor suffix, e.g. `Long` for getLong/setLong. Unused for [Access.OBJECT]. */
    val accessor: String = "",
    /**
     * The `java.sql.Types` constant a NULL of this type is bound as. Typed
     * rather than `OTHER`, so pgjdbc sends the same parameter type for NULL as
     * for a value and a server-prepared statement is not re-prepared each time
     * the argument flips between the two.
     */
    val jdbcType: String = "OTHER",
)

/**
 * Postgres type name to Kotlin type.
 *
 * Deliberately small: everything here is a scalar pgjdbc handles natively, so
 * generated code needs no conversion helpers. Arrays, enums, domains and
 * composites are M4 and currently produce [Diagnostic.UNMAPPED_TYPE] rather
 * than a silent `String`.
 */
internal object TypeMapper {

    private val TYPES: Map<String, SqlType> = buildMap {
        fun primitive(vararg names: String, kotlin: String, accessor: String, jdbcType: String) {
            names.forEach { put(it, SqlType(kotlin, null, Access.PRIMITIVE, accessor, jdbcType)) }
        }

        fun reference(vararg names: String, kotlin: String, import: String?, accessor: String, jdbcType: String) {
            names.forEach { put(it, SqlType(kotlin, import, Access.REFERENCE, accessor, jdbcType)) }
        }

        fun obj(vararg names: String, kotlin: String, import: String, jdbcType: String) {
            names.forEach { put(it, SqlType(kotlin, import, Access.OBJECT, jdbcType = jdbcType)) }
        }

        primitive("bool", kotlin = "Boolean", accessor = "Boolean", jdbcType = "BOOLEAN")
        // pgjdbc renames identity and serial columns in getColumnTypeName, so
        // the serial spellings have to be here alongside the real type names.
        primitive("int2", "smallint", "smallserial", kotlin = "Short", accessor = "Short", jdbcType = "SMALLINT")
        primitive("int4", "integer", "serial", kotlin = "Int", accessor = "Int", jdbcType = "INTEGER")
        primitive("int8", "bigint", "bigserial", "oid", kotlin = "Long", accessor = "Long", jdbcType = "BIGINT")
        primitive("float4", kotlin = "Float", accessor = "Float", jdbcType = "REAL")
        primitive("float8", kotlin = "Double", accessor = "Double", jdbcType = "DOUBLE")

        reference(
            "text", "varchar", "bpchar", "char", "name",
            // json is read and written as its text form; query files cast explicitly.
            "json", "jsonb", "xml",
            kotlin = "String", import = null, accessor = "String", jdbcType = "VARCHAR",
        )
        reference(
            "numeric", "decimal",
            kotlin = "BigDecimal", import = "java.math.BigDecimal", accessor = "BigDecimal", jdbcType = "NUMERIC",
        )
        reference("bytea", kotlin = "ByteArray", import = null, accessor = "Bytes", jdbcType = "BINARY")

        obj("uuid", kotlin = "UUID", import = "java.util.UUID", jdbcType = "OTHER")
        obj("date", kotlin = "LocalDate", import = "java.time.LocalDate", jdbcType = "DATE")
        obj("time", kotlin = "LocalTime", import = "java.time.LocalTime", jdbcType = "TIME")
        obj("timetz", kotlin = "OffsetTime", import = "java.time.OffsetTime", jdbcType = "TIME_WITH_TIMEZONE")
        obj("timestamp", kotlin = "LocalDateTime", import = "java.time.LocalDateTime", jdbcType = "TIMESTAMP")
        // timestamptz is an instant, never a local time — mapping it to
        // LocalDateTime is one of the silent failures this tool exists to stop.
        obj(
            "timestamptz",
            kotlin = "OffsetDateTime", import = "java.time.OffsetDateTime", jdbcType = "TIMESTAMP_WITH_TIMEZONE",
        )
    }

    fun builtin(postgresType: String): SqlType? = TYPES[postgresType.lowercase()]

    fun unmapped(
        postgresType: String?,
        query: ParsedQuery,
        what: String,
    ): Diagnostic = Diagnostic(
        severity = Severity.ERROR,
        code = Diagnostic.UNMAPPED_TYPE,
        file = query.file.toString(),
        line = query.headerLine,
        query = query.name,
        message = "No Kotlin type is known for Postgres type '${postgresType ?: "unknown"}' ($what).",
        hint = "Composite and range types are not supported. Either cast the expression " +
            "to a supported type, e.g. ::text, or map it in pgd.toml under [types].",
    )
}
