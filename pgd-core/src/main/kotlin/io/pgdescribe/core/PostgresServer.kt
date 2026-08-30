package io.pgdescribe.core

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import kotlin.random.Random

/**
 * A Postgres instance we may create and drop scratch databases on.
 *
 * Checking never touches a database the caller cares about: migrations are
 * always applied to a freshly created scratch database that is dropped again on
 * the way out.
 */
public interface PostgresServer : AutoCloseable {
    public fun urlFor(database: String): String
    public val adminDatabase: String
    public val description: String

    /**
     * True when databases outlive this process, so a migrated template database
     * is worth keeping between runs. False for a server we start and throw away.
     */
    public val persistent: Boolean get() = false
}

/** Default provider: Zonky's pinned binaries. No Docker daemon required. */
public class EmbeddedPostgresServer private constructor(
    private val postgres: EmbeddedPostgres,
) : PostgresServer {

    override fun urlFor(database: String): String = postgres.getJdbcUrl("postgres", database)
    override val adminDatabase: String = "postgres"
    override val description: String = "embedded Postgres (port ${postgres.port})"
    override val persistent: Boolean = false

    override fun close() {
        postgres.close()
    }

    public companion object {
        public fun start(): EmbeddedPostgresServer =
            EmbeddedPostgresServer(EmbeddedPostgres.builder().start())
    }
}

/**
 * An already-running server, addressed by JDBC URL (`PGD_URL`). The database
 * named in the URL is only used to issue `CREATE DATABASE`; it is never
 * migrated into.
 */
public class ExistingPostgresServer(private val templateUrl: String) : PostgresServer {

    private val match = URL.matchEntire(templateUrl)
        ?: throw IllegalArgumentException(
            "Cannot parse '$templateUrl' as a JDBC URL. " +
                "Expected the form jdbc:postgresql://host:port/database?user=...",
        )

    override val adminDatabase: String = match.groupValues[2].ifEmpty { "postgres" }

    override val description: String = "existing Postgres at ${match.groupValues[1]}"
    override val persistent: Boolean = true

    override fun urlFor(database: String): String =
        match.groupValues[1] + "/" + database + match.groupValues[3]

    override fun close() {
        // Not ours to shut down.
    }

    private companion object {
        val URL = Regex("""^(jdbc:postgresql://[^/?]*)/([^?]*)(\?.*)?$""")
    }
}

/** A throwaway database. Closing it drops the database. */
public class ScratchDatabase(
    private val server: PostgresServer,
    public val name: String,
) : AutoCloseable {

    public val url: String get() = server.urlFor(name)

    public fun connect(): Connection = DriverManager.getConnection(url)

    override fun close() {
        DriverManager.getConnection(server.urlFor(server.adminDatabase)).use { admin ->
            admin.createStatement().use { it.execute("DROP DATABASE IF EXISTS \"$name\" WITH (FORCE)") }
        }
    }
}

public fun PostgresServer.createScratchDatabase(): ScratchDatabase =
    ScratchDatabases.prepare(this, emptyList()).database!!

internal fun PostgresServer.admin(): Connection = DriverManager.getConnection(urlFor(adminDatabase))

private fun scratchName(): String = "pgd_check_" + Random.nextLong(0, Long.MAX_VALUE).toString(16)

/**
 * Creates the throwaway database a run works in.
 *
 * On a server whose databases outlive the process, the migrated schema is kept
 * as a template database keyed by a hash of the migrations, and each run's
 * scratch database is cloned from it. Applying a large migration set is the
 * dominant cost of a repeat run; cloning skips it entirely.
 *
 * The template is built under a temporary name and renamed into place, so a run
 * that dies midway leaves a half-populated database that nobody will ever match
 * on rather than a corrupt cache entry.
 */
internal object ScratchDatabases {

    private const val DUPLICATE_DATABASE = "42P04"
    internal const val TEMPLATE_PREFIX = "pgd_tpl_"
    internal const val SCRATCH_PREFIX = "pgd_check_"

    class Prepared(
        val database: ScratchDatabase?,
        val diagnostics: List<Diagnostic>,
        /** The scratch database was cloned from a template rather than migrated directly. */
        val clonedFromTemplate: Boolean,
        /** The template did not exist yet, so this run paid to build it. */
        val builtTemplate: Boolean = false,
    )

    fun prepare(
        server: PostgresServer,
        migrations: List<Migration>,
        log: (String) -> Unit = {},
    ): Prepared {
        val name = scratchName()

        if (!server.persistent || migrations.isEmpty()) {
            server.admin().use { createDatabase(it, name) }
            val scratch = ScratchDatabase(server, name)
            val failures = scratch.connect().use { Migrations.applyAll(it, migrations) }
            if (failures.isNotEmpty()) {
                scratch.close()
                return Prepared(null, failures, clonedFromTemplate = false)
            }
            return Prepared(scratch, emptyList(), clonedFromTemplate = false)
        }

        val template = TEMPLATE_PREFIX + Migrations.fingerprint(migrations).take(32)
        val existed = server.admin().use { admin -> databaseExists(admin, template) }
        if (!existed) {
            val failures = buildTemplate(server, template, migrations, log)
            if (failures.isNotEmpty()) return Prepared(null, failures, clonedFromTemplate = false)
        } else {
            log("Reusing template database $template")
        }

        server.admin().use { createDatabase(it, name, template = template) }
        return Prepared(
            database = ScratchDatabase(server, name),
            diagnostics = emptyList(),
            clonedFromTemplate = true,
            builtTemplate = !existed,
        )
    }

    private fun buildTemplate(
        server: PostgresServer,
        template: String,
        migrations: List<Migration>,
        log: (String) -> Unit,
    ): List<Diagnostic> {
        val building = template + "_building_" + Random.nextLong(0, Long.MAX_VALUE).toString(16)
        server.admin().use { createDatabase(it, building) }
        val staging = ScratchDatabase(server, building)

        val failures = staging.connect().use { Migrations.applyAll(it, migrations) }
        if (failures.isNotEmpty()) {
            staging.close()
            return failures
        }

        val renamed = server.admin().use { admin ->
            try {
                admin.createStatement().use {
                    it.execute("ALTER DATABASE \"$building\" RENAME TO \"$template\"")
                }
                true
            } catch (e: SQLException) {
                // Another run finished the same template first; theirs is as good as ours.
                if (e.sqlState == DUPLICATE_DATABASE) false else throw e
            }
        }
        if (renamed) {
            log("Built template database $template")
        } else {
            staging.close()
        }
        return emptyList()
    }

    private fun createDatabase(admin: Connection, name: String, template: String? = null) {
        val suffix = template?.let { " TEMPLATE \"$it\"" }.orEmpty()
        admin.createStatement().use { it.execute("CREATE DATABASE \"$name\"$suffix") }
    }

    private fun databaseExists(admin: Connection, name: String): Boolean =
        admin.prepareStatement("SELECT 1 FROM pg_database WHERE datname = ?").use { statement ->
            statement.setString(1, name)
            statement.executeQuery().use { it.next() }
        }

    /** Drops every database this tool has left on a server. */
    fun clean(server: PostgresServer, log: (String) -> Unit = {}): Int {
        val names = mutableListOf<String>()
        server.admin().use { admin ->
            admin.prepareStatement(
                "SELECT datname FROM pg_database WHERE datname LIKE ? OR datname LIKE ? ORDER BY datname",
            ).use { statement ->
                statement.setString(1, "$TEMPLATE_PREFIX%")
                statement.setString(2, "$SCRATCH_PREFIX%")
                statement.executeQuery().use { results ->
                    while (results.next()) names += results.getString(1)
                }
            }
            for (name in names) {
                admin.createStatement().use { it.execute("DROP DATABASE IF EXISTS \"$name\" WITH (FORCE)") }
                log("Dropped $name")
            }
        }
        return names.size
    }
}
