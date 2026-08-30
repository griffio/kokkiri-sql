package io.pgdescribe.core

import java.nio.file.Path
import kotlin.io.path.createParentDirectories
import kotlin.io.path.writeText
import kotlinx.serialization.Serializable

@Serializable
public data class GenerateReport(
    val ok: Boolean,
    val migrationsApplied: Int,
    val queriesChecked: Int,
    /** Paths written, relative to the output directory. Empty when nothing was written. */
    val written: List<String>,
    val diagnostics: List<Diagnostic>,
) {
    public val errorCount: Int get() = diagnostics.count { it.severity == Severity.ERROR }
    public val warningCount: Int get() = diagnostics.count { it.severity == Severity.WARNING }
}

/**
 * `pgd generate`: check, then emit Kotlin. Nothing is written unless the whole
 * project checks clean, so a half-generated source tree is never left behind.
 */
public object GenerateRunner {

    public fun run(
        config: CheckConfig,
        codegen: CodeGenConfig,
        outputDir: Path,
        /** Where `schema.md` and `schema.json` go; null to skip them. */
        schemaDir: Path? = null,
        log: (String) -> Unit = {},
    ): GenerateReport {
        val analysis = ProjectAnalyzer.analyze(config, log)
        val diagnostics = analysis.diagnostics.toMutableList()

        val files = mutableListOf<GeneratedFile>()
        val extras = mutableListOf<Pair<Path, String>>()

        if (analysis.ok) {
            val registry = TypeRegistry(analysis.catalog, codegen.typeAliases)
            analysis.analyzed
                .groupBy { it.query.file }
                .toSortedMap()
                .forEach { (_, queries) ->
                    val (file, problems) = CodeGenerator.generate(queries, codegen, registry)
                    diagnostics += problems
                    file?.let { files += it }
                }

            CodeGenerator.generateEnums(registry.usedEnums, codegen)?.let { files += it }
            if (analysis.analyzed.any { it.query.cardinality.isCopy }) {
                files += CodeGenerator.generateCopySupport(codegen)
            }
            diagnostics += registry.enumProblems.map { problem ->
                Diagnostic(
                    severity = Severity.ERROR,
                    code = Diagnostic.ENUM_COLLISION,
                    file = config.migrationsDir.toString(),
                    line = 1,
                    message = problem,
                    hint = "Rename the type or label, or map it to text in pgd.toml under [types].",
                )
            }

            if (schemaDir != null) {
                extras += schemaDir.resolve("schema.md") to SchemaWriter.toMarkdown(analysis.catalog)
                extras += schemaDir.resolve("schema.json") to SchemaWriter.toJson(analysis.catalog)
            }
        }

        val ok = diagnostics.none { it.severity == Severity.ERROR }
        if (ok) {
            for (file in files) {
                val target = outputDir.resolve(file.relativePath)
                target.createParentDirectories()
                target.writeText(file.contents)
                // The path on disk, not the package-relative one, which reads
                // like a directory that exists and usually does not.
                log("Wrote ${display(target)}")
            }
            for ((target, contents) in extras) {
                target.createParentDirectories()
                target.writeText(contents)
                log("Wrote ${display(target)}")
            }
        }

        return GenerateReport(
            ok = ok,
            migrationsApplied = analysis.migrationsApplied,
            queriesChecked = analysis.queriesParsed,
            written = if (ok) files.map { it.relativePath } + extras.map { it.first.fileName.toString() } else emptyList(),
            diagnostics = diagnostics.sortedWith(compareBy({ it.file }, { it.line }, { it.code })),
        )
    }
}

/** A written path, relative to the working directory when it is underneath it. */
private fun display(path: Path): String {
    val absolute = path.toAbsolutePath().normalize()
    val here = Path.of("").toAbsolutePath().normalize()
    return if (absolute.startsWith(here)) here.relativize(absolute).toString() else absolute.toString()
}

/** `pgd schema`: write the schema snapshot without generating any Kotlin. */
public object SchemaRunner {

    public fun run(config: CheckConfig, outputDir: Path, log: (String) -> Unit = {}): CheckReport {
        val analysis = ProjectAnalyzer.analyze(config, log)
        if (analysis.ok) {
            for ((name, contents) in listOf(
                "schema.md" to SchemaWriter.toMarkdown(analysis.catalog),
                "schema.json" to SchemaWriter.toJson(analysis.catalog),
            )) {
                val target = outputDir.resolve(name)
                target.createParentDirectories()
                target.writeText(contents)
                log("Wrote ${display(target)}")
            }
        }
        return analysis.toReport()
    }
}

/** `pgd clean`: drop every template and scratch database pgd has left behind. */
public object CleanRunner {
    public fun run(url: String, log: (String) -> Unit = {}): Int =
        ExistingPostgresServer(url).use { ScratchDatabases.clean(it, log) }
}
