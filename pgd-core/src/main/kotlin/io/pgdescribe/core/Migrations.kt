package io.pgdescribe.core

import java.nio.file.Path
import java.security.MessageDigest
import java.sql.Connection
import java.sql.SQLException
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readText

public data class Migration(
    val version: String,
    val description: String,
    val path: Path,
    val sql: String,
)

/**
 * `migrations/` is the only source of schema truth — there is no separately
 * maintained DDL file to drift out of sync.
 */
public object Migrations {

    private val FILE_NAME = Regex("""^V(\d+(?:[._]\d+)*)__(.+)\.sql$""", RegexOption.IGNORE_CASE)

    public fun load(dir: Path): List<Migration> {
        if (!dir.isDirectory()) return emptyList()
        return dir.listDirectoryEntries()
            .filter { it.isRegularFile() && FILE_NAME.matches(it.name) }
            .map { path ->
                val m = FILE_NAME.matchEntire(path.name)!!
                Migration(
                    version = m.groupValues[1],
                    description = m.groupValues[2],
                    path = path,
                    sql = path.readText(),
                )
            }
            .sortedWith(compareBy(VersionComparator) { it.version })
    }

    /**
     * SHA-256 over every migration's version and body. Stable across machines,
     * and the key a future template-database cache will hang off.
     */
    public fun fingerprint(migrations: List<Migration>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        for (m in migrations) {
            digest.update(m.version.toByteArray())
            digest.update(0)
            digest.update(m.sql.toByteArray())
            digest.update(0)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * Applies every migration in order. Each file is sent whole — pgjdbc splits
     * multi-statement bodies itself and understands dollar quoting, so function
     * bodies survive intact.
     */
    public fun applyAll(connection: Connection, migrations: List<Migration>): List<Diagnostic> {
        for (migration in migrations) {
            try {
                connection.createStatement().use { it.execute(migration.sql) }
            } catch (e: SQLException) {
                return listOf(
                    SqlErrors.toDiagnostic(
                        e = e,
                        code = Diagnostic.MIGRATION_FAILED,
                        file = migration.path.toString(),
                        sql = migration.sql,
                        sqlStartLine = 1,
                        queryName = null,
                        messagePrefix = "Migration ${migration.path.name} failed: ",
                    ),
                )
            }
        }
        return emptyList()
    }

    private object VersionComparator : Comparator<String> {
        override fun compare(a: String, b: String): Int {
            val left = a.split('.', '_').map { it.toIntOrNull() ?: 0 }
            val right = b.split('.', '_').map { it.toIntOrNull() ?: 0 }
            for (i in 0 until maxOf(left.size, right.size)) {
                val c = (left.getOrNull(i) ?: 0).compareTo(right.getOrNull(i) ?: 0)
                if (c != 0) return c
            }
            return 0
        }
    }
}
