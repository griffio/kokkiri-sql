package io.pgdescribe.core

import java.sql.Connection
import kotlinx.serialization.Serializable

@Serializable
public data class PgEnum(val schema: String, val name: String, val labels: List<String>)

@Serializable
public data class PgDomain(val name: String, val baseType: String, val notNull: Boolean)

@Serializable
public data class PgArrayType(val name: String, val elementType: String)

@Serializable
public data class PgColumn(
    val name: String,
    val type: String,
    val nullable: Boolean,
    val default: String? = null,
)

@Serializable
public data class PgForeignKey(
    val columns: List<String>,
    val targetTable: String,
    val targetColumns: List<String>,
)

@Serializable
public data class PgTable(
    val schema: String,
    val name: String,
    val columns: List<PgColumn>,
    val primaryKey: List<String>,
    val uniques: List<List<String>>,
    val foreignKeys: List<PgForeignKey>,
)

/**
 * A snapshot of everything generation and the schema summary need, read once
 * per run while the scratch database is open.
 */
@Serializable
public data class PgCatalog(
    val tables: List<PgTable>,
    val enums: List<PgEnum>,
    val domains: List<PgDomain>,
    val arrays: List<PgArrayType>,
) {
    public companion object {
        public val EMPTY: PgCatalog = PgCatalog(emptyList(), emptyList(), emptyList(), emptyList())

        public fun read(connection: Connection): PgCatalog = PgCatalog(
            tables = readTables(connection),
            enums = readEnums(connection),
            domains = readDomains(connection),
            arrays = readArrays(connection),
        )

        private const val USER_SCHEMAS =
            "n.nspname NOT IN ('pg_catalog', 'information_schema') AND n.nspname NOT LIKE 'pg_%'"

        private fun readEnums(connection: Connection): List<PgEnum> {
            val labels = linkedMapOf<Pair<String, String>, MutableList<String>>()
            connection.query(
                """
                SELECT n.nspname AS schema, t.typname AS name, e.enumlabel AS label
                FROM pg_type t
                JOIN pg_namespace n ON n.oid = t.typnamespace
                JOIN pg_enum e ON e.enumtypid = t.oid
                WHERE $USER_SCHEMAS
                ORDER BY n.nspname, t.typname, e.enumsortorder
                """,
            ) { row ->
                val key = row.getString("schema") to row.getString("name")
                labels.getOrPut(key) { mutableListOf() } += row.getString("label")
            }
            return labels.map { (key, values) -> PgEnum(key.first, key.second, values) }
        }

        private fun readDomains(connection: Connection): List<PgDomain> = buildList {
            connection.query(
                """
                SELECT t.typname AS name, b.typname AS base, t.typnotnull AS not_null
                FROM pg_type t
                JOIN pg_namespace n ON n.oid = t.typnamespace
                JOIN pg_type b ON b.oid = t.typbasetype
                WHERE t.typtype = 'd' AND $USER_SCHEMAS
                ORDER BY t.typname
                """,
            ) { row ->
                add(PgDomain(row.getString("name"), row.getString("base"), row.getBoolean("not_null")))
            }
        }

        private fun readArrays(connection: Connection): List<PgArrayType> = buildList {
            connection.query(
                """
                SELECT t.typname AS name, e.typname AS element
                FROM pg_type t
                JOIN pg_type e ON e.oid = t.typelem
                WHERE t.typcategory = 'A' AND t.typelem <> 0
                ORDER BY t.typname
                """,
            ) { row -> add(PgArrayType(row.getString("name"), row.getString("element"))) }
        }

        private fun readTables(connection: Connection): List<PgTable> {
            val columns = linkedMapOf<Pair<String, String>, MutableList<PgColumn>>()
            connection.query(
                """
                SELECT n.nspname AS schema,
                       c.relname AS table,
                       a.attname AS column,
                       format_type(a.atttypid, a.atttypmod) AS type,
                       NOT a.attnotnull AS nullable,
                       pg_get_expr(d.adbin, d.adrelid) AS default_expr
                FROM pg_attribute a
                JOIN pg_class c ON c.oid = a.attrelid
                JOIN pg_namespace n ON n.oid = c.relnamespace
                LEFT JOIN pg_attrdef d ON d.adrelid = c.oid AND d.adnum = a.attnum
                WHERE c.relkind IN ('r', 'p', 'v', 'm', 'f')
                  AND a.attnum > 0
                  AND NOT a.attisdropped
                  AND $USER_SCHEMAS
                ORDER BY n.nspname, c.relname, a.attnum
                """,
            ) { row ->
                val key = row.getString("schema") to row.getString("table")
                columns.getOrPut(key) { mutableListOf() } += PgColumn(
                    name = row.getString("column"),
                    type = row.getString("type"),
                    nullable = row.getBoolean("nullable"),
                    default = row.getString("default_expr"),
                )
            }

            val primaryKeys = mutableMapOf<Pair<String, String>, List<String>>()
            val uniques = linkedMapOf<Pair<String, String>, MutableList<List<String>>>()
            val foreignKeys = linkedMapOf<Pair<String, String>, MutableList<PgForeignKey>>()
            connection.query(
                """
                SELECT n.nspname AS schema,
                       c.relname AS table,
                       con.contype AS kind,
                       (SELECT array_agg(att.attname ORDER BY k.ord)
                          FROM unnest(con.conkey) WITH ORDINALITY AS k(attnum, ord)
                          JOIN pg_attribute att ON att.attrelid = con.conrelid AND att.attnum = k.attnum
                       ) AS columns,
                       tn.nspname AS target_schema,
                       tc.relname AS target_table,
                       (SELECT array_agg(att.attname ORDER BY k.ord)
                          FROM unnest(con.confkey) WITH ORDINALITY AS k(attnum, ord)
                          JOIN pg_attribute att ON att.attrelid = con.confrelid AND att.attnum = k.attnum
                       ) AS target_columns
                FROM pg_constraint con
                JOIN pg_class c ON c.oid = con.conrelid
                JOIN pg_namespace n ON n.oid = c.relnamespace
                LEFT JOIN pg_class tc ON tc.oid = con.confrelid
                LEFT JOIN pg_namespace tn ON tn.oid = tc.relnamespace
                WHERE con.contype IN ('p', 'u', 'f') AND $USER_SCHEMAS
                ORDER BY n.nspname, c.relname, con.conname
                """,
            ) { row ->
                val key = row.getString("schema") to row.getString("table")
                val names = row.stringArray("columns")
                when (row.getString("kind")) {
                    "p" -> primaryKeys[key] = names
                    "u" -> uniques.getOrPut(key) { mutableListOf() } += names
                    "f" -> foreignKeys.getOrPut(key) { mutableListOf() } += PgForeignKey(
                        columns = names,
                        targetTable = qualify(row.getString("target_schema"), row.getString("target_table")),
                        targetColumns = row.stringArray("target_columns"),
                    )
                }
            }

            return columns.map { (key, cols) ->
                PgTable(
                    schema = key.first,
                    name = key.second,
                    columns = cols,
                    primaryKey = primaryKeys[key].orEmpty(),
                    uniques = uniques[key].orEmpty(),
                    foreignKeys = foreignKeys[key].orEmpty(),
                )
            }
        }

        private fun qualify(schema: String?, name: String?): String =
            if (schema == null || schema == "public") name.orEmpty() else "$schema.$name"
    }
}

private fun Connection.query(sql: String, row: (java.sql.ResultSet) -> Unit) {
    createStatement().use { statement ->
        statement.executeQuery(sql.trimIndent()).use { results ->
            while (results.next()) row(results)
        }
    }
}

private fun java.sql.ResultSet.stringArray(label: String): List<String> {
    val array = getArray(label) ?: return emptyList()
    @Suppress("UNCHECKED_CAST")
    return (array.array as Array<*>).mapNotNull { it as String? }
}
