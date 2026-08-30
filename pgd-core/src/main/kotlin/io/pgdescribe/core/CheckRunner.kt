package io.pgdescribe.core

import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.walk

public data class CheckConfig(
    val migrationsDir: Path,
    val queriesDir: Path,
    /** When set (or `PGD_URL`), use this server instead of starting an embedded one. */
    val existingUrl: String? = null,
    val nullability: NullabilityMode = NullabilityMode.AUTO,
) {
    public companion object {
        public fun forRoot(root: Path, existingUrl: String? = null): CheckConfig = CheckConfig(
            migrationsDir = root.resolve("migrations"),
            queriesDir = root.resolve("queries"),
            existingUrl = existingUrl,
        )
    }
}

/** Everything both `check` and `generate` need from one pass over the project. */
public data class ProjectAnalysis(
    val analyzed: List<AnalyzedQuery>,
    val diagnostics: List<Diagnostic>,
    val migrationsApplied: Int,
    val queriesParsed: Int,
    val catalog: PgCatalog = PgCatalog.EMPTY,
) {
    public val ok: Boolean get() = diagnostics.none { it.severity == Severity.ERROR }

    public fun toReport(): CheckReport = CheckReport(
        ok = ok,
        migrationsApplied = migrationsApplied,
        queriesChecked = queriesParsed,
        diagnostics = diagnostics,
    )
}

/**
 * Applies the migrations to a scratch database and asks Postgres to describe
 * every query.
 */
public object ProjectAnalyzer {

    @OptIn(kotlin.io.path.ExperimentalPathApi::class)
    public fun analyze(config: CheckConfig, log: (String) -> Unit = {}): ProjectAnalysis {
        val diagnostics = mutableListOf<Diagnostic>()

        if (!config.migrationsDir.isDirectory()) {
            return ProjectAnalysis(
                analyzed = emptyList(),
                diagnostics = listOf(
                    Diagnostic(
                        severity = Severity.ERROR,
                        code = Diagnostic.NO_MIGRATIONS,
                        file = config.migrationsDir.toString(),
                        line = 1,
                        message = "Migrations directory not found.",
                        hint = "Create it and add a migration, e.g. migrations/V001__initial.sql.",
                    ),
                ),
                migrationsApplied = 0,
                queriesParsed = 0,
            )
        }

        val migrations = Migrations.load(config.migrationsDir)
        if (migrations.isEmpty()) {
            diagnostics += Diagnostic(
                severity = Severity.WARNING,
                code = Diagnostic.NO_MIGRATIONS,
                file = config.migrationsDir.toString(),
                line = 1,
                message = "No migrations found; every query will be checked against an empty schema.",
                hint = "Migration files are named V<version>__<description>.sql, e.g. V001__users.sql.",
            )
        }

        val queryFiles = if (config.queriesDir.isDirectory()) {
            config.queriesDir.walk()
                .filter { it.isRegularFile() && it.extension.equals("sql", ignoreCase = true) }
                .sortedBy { it.toString() }
                .toList()
        } else {
            emptyList()
        }
        if (queryFiles.isEmpty()) {
            diagnostics += Diagnostic(
                severity = Severity.WARNING,
                code = Diagnostic.NO_QUERIES,
                file = config.queriesDir.toString(),
                line = 1,
                message = "No .sql query files found.",
                hint = "Add one, e.g. queries/users.sql with a `-- name: FindUser :one` header.",
            )
        }

        val queries = mutableListOf<ParsedQuery>()
        for (file in queryFiles) {
            val parsed = QueryParser.parseFile(file)
            queries += parsed.queries
            diagnostics += parsed.diagnostics
        }

        val parser = when (config.nullability) {
            NullabilityMode.CONSERVATIVE -> null
            NullabilityMode.AUTO -> SqlParsers.available()
            NullabilityMode.PRECISE -> SqlParsers.available() ?: return ProjectAnalysis(
                analyzed = emptyList(),
                diagnostics = diagnostics + Diagnostic(
                    severity = Severity.ERROR,
                    code = Diagnostic.NATIVE_UNAVAILABLE,
                    file = config.migrationsDir.toString(),
                    line = 1,
                    message = "nullability is set to precise, but Postgres' parser is not available.",
                    hint = "Install libpg_query (brew install libpg_query) and put pgd-native on the " +
                        "classpath, or set nullability = \"auto\" in ${PgdConfig.FILE_NAME}.",
                ),
                migrationsApplied = 0,
                queriesParsed = 0,
            )
        }
        log(
            if (parser != null) {
                "Nullability: proving with ${parser.description}"
            } else {
                "Nullability: conservative (Postgres' parser not installed)"
            },
        )

        val analyzed = mutableListOf<AnalyzedQuery>()
        var catalog = PgCatalog.EMPTY
        val server = if (config.existingUrl != null) {
            ExistingPostgresServer(config.existingUrl)
        } else {
            EmbeddedPostgresServer.start()
        }

        server.use {
            log("Using ${server.description}")
            val prepared = ScratchDatabases.prepare(server, migrations, log)
            val scratch = prepared.database
                ?: return ProjectAnalysis(
                    analyzed = emptyList(),
                    diagnostics = diagnostics + prepared.diagnostics,
                    migrationsApplied = 0,
                    queriesParsed = 0,
                )
            try {
                scratch.connect().use { connection ->
                    log(
                        if (prepared.clonedFromTemplate && !prepared.builtTemplate) {
                            "Cloned ${migrations.size} migration(s) into ${scratch.name}"
                        } else {
                            "Applied ${migrations.size} migration(s) to ${scratch.name}"
                        },
                    )

                    catalog = PgCatalog.read(connection)

                    val analyzer = QueryAnalyzer(connection, parser)
                    for (query in queries) {
                        val analysis = analyzer.analyze(query)
                        diagnostics += analysis.diagnostics
                        analysis.analyzed?.let { analyzed += it }
                    }
                }
            } finally {
                scratch.close()
            }
        }

        return ProjectAnalysis(
            analyzed = analyzed,
            diagnostics = diagnostics.sortedWith(compareBy({ it.file }, { it.line }, { it.code })),
            migrationsApplied = migrations.size,
            queriesParsed = queries.size,
            catalog = catalog,
        )
    }
}

/** `pgd check`: verify only, emit nothing. */
public object CheckRunner {
    public fun run(config: CheckConfig, log: (String) -> Unit = {}): CheckReport =
        ProjectAnalyzer.analyze(config, log).toReport()
}
