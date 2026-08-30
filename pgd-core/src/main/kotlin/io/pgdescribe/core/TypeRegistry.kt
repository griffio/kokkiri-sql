package io.pgdescribe.core

/** A Postgres type resolved to Kotlin, plus how to read and write it over JDBC. */
internal sealed interface ResolvedType {
    val kotlin: String
    val imports: Set<String>

    /** Expression producing the value of column [index] from `resultSet`. */
    fun read(index: Int, nullable: Boolean): String

    /** Statement binding [name] at 1-based [position] of `statement`. */
    fun bind(position: Int, name: String): String

    /** Expression rendering [name] as a COPY text field, or null if unsupported. */
    fun copyExpression(name: String, nullable: Boolean): String? = "pgdCopyText($name)"
}

internal class ScalarType(private val type: SqlType) : ResolvedType {
    override val kotlin: String get() = type.kotlin
    override val imports: Set<String> get() = setOfNotNull(type.import)

    override fun read(index: Int, nullable: Boolean): String = when (type.access) {
        Access.PRIMITIVE -> {
            val call = "resultSet.get${type.accessor}($index)"
            if (nullable) "$call.takeUnless { resultSet.wasNull() }" else call
        }

        Access.REFERENCE -> {
            val call = "resultSet.get${type.accessor}($index)"
            if (nullable) call else "checkNotNull($call)"
        }

        Access.OBJECT -> {
            val call = "resultSet.getObject($index, ${type.kotlin}::class.java)"
            if (nullable) call else "checkNotNull($call)"
        }
    }

    override fun bind(position: Int, name: String): String = when (type.access) {
        Access.OBJECT -> "statement.setObject($position, $name)"
        else -> "statement.set${type.accessor}($position, $name)"
    }

    /** How an element of this type is recovered from an untyped array slot. */
    fun fromArrayElement(expression: String): String = "$expression as ${type.kotlin}?"
}

internal class EnumType(
    val postgresName: String,
    override val kotlin: String,
    val labels: List<String>,
) : ResolvedType {
    override val imports: Set<String> get() = setOf("java.sql.Types")

    override fun read(index: Int, nullable: Boolean): String {
        val call = "resultSet.getString($index)"
        return if (nullable) {
            "$call?.let { $kotlin.fromLabel(it) }"
        } else {
            "$kotlin.fromLabel(checkNotNull($call))"
        }
    }

    // Sending the label as an untyped value lets Postgres coerce it to the enum,
    // whether or not the query casts the placeholder.
    override fun bind(position: Int, name: String): String =
        "statement.setObject($position, $name.label, Types.OTHER)"

    fun fromArrayElement(expression: String): String =
        "($expression as String?)?.let { $kotlin.fromLabel(it) }"

    override fun copyExpression(name: String, nullable: Boolean): String =
        if (nullable) "pgdCopyText($name?.label)" else "pgdCopyText($name.label)"

    /** Kotlin entry name for each label, in declaration order. */
    fun entries(): List<Pair<String, String>> = labels.map { Naming.enumEntry(it) to it }
}

internal class ArrayType(
    private val elementPostgresName: String,
    val element: ResolvedType,
) : ResolvedType {
    // Postgres arrays may contain NULL in any slot, independently of whether the
    // column itself is nullable, so elements are always optional.
    override val kotlin: String get() = "List<${element.kotlin}?>"
    override val imports: Set<String> get() = element.imports

    override fun read(index: Int, nullable: Boolean): String {
        val each = when (element) {
            is EnumType -> element.fromArrayElement("it")
            is ScalarType -> element.fromArrayElement("it")
            else -> "it as ${element.kotlin}?"
        }
        val body = "{ array -> (array.array as Array<*>).map { $each } }"
        return if (nullable) {
            "resultSet.getArray($index)?.let $body"
        } else {
            "checkNotNull(resultSet.getArray($index)).let $body"
        }
    }

    override fun bind(position: Int, name: String): String {
        val values = if (element is EnumType) "$name.map { it?.label }" else name
        return "statement.setArray($position, createArrayOf(\"$elementPostgresName\", $values.toTypedArray()))"
    }

    // COPY text format would need array-literal quoting rules of its own.
    override fun copyExpression(name: String, nullable: Boolean): String? = null
}

/**
 * Resolves Postgres type names against the live catalog: user aliases first,
 * then built-in scalars, then domains (to their base type), enums and arrays.
 *
 * Enums are memoised so the same Kotlin class is shared by every query that
 * mentions the type, and [usedEnums] reports exactly the ones generation must
 * emit.
 */
internal class TypeRegistry(
    catalog: PgCatalog,
    private val aliases: Map<String, String>,
) {
    private val domains = catalog.domains.associateBy { it.name.lowercase() }
    private val arrays = catalog.arrays.associate { it.name.lowercase() to it.elementType }
    private val enums = catalog.enums.associateBy { it.name.lowercase() }
    private val resolvedEnums = linkedMapOf<String, EnumType>()

    /** Enums actually referenced by the queries, in first-use order. */
    val usedEnums: List<EnumType> get() = resolvedEnums.values.toList()

    /** Enum names that collide once mapped to Kotlin, or whose labels collide. */
    val enumProblems: MutableList<String> = mutableListOf()

    fun resolve(postgresType: String?): ResolvedType? {
        if (postgresType == null) return null
        return resolve(postgresType, mutableSetOf())
    }

    private fun resolve(name: String, seen: MutableSet<String>): ResolvedType? {
        val key = name.lowercase()
        if (!seen.add(key)) return null

        aliases[key]?.let { return resolve(it, seen) }
        TypeMapper.builtin(key)?.let { return ScalarType(it) }
        domains[key]?.let { return resolve(it.baseType, seen) }
        enums[key]?.let { return enumType(it) }
        arrays[key]?.let { elementName ->
            val element = resolve(elementName, seen) ?: return null
            // Nested arrays would need a second unwrap; not supported.
            if (element is ArrayType) return null
            return ArrayType(elementName, element)
        }
        return null
    }

    private fun enumType(enum: PgEnum): EnumType = resolvedEnums.getOrPut(enum.name.lowercase()) {
        val kotlinName = Naming.upperCamel(enum.name)
        val clash = resolvedEnums.values.firstOrNull { it.kotlin == kotlinName }
        if (clash != null) {
            enumProblems += "Postgres enums '${clash.postgresName}' and '${enum.name}' both " +
                "become Kotlin class '$kotlinName'."
        }
        val entries = enum.labels.map { Naming.enumEntry(it) }
        entries.groupingBy { it }.eachCount().filterValues { it > 1 }.keys.forEach { duplicate ->
            enumProblems += "Enum '${enum.name}' has labels that both become entry '$duplicate'."
        }
        EnumType(enum.name, kotlinName, enum.labels)
    }
}
