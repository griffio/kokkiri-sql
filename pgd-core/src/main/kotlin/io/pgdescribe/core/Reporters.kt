package io.pgdescribe.core

import java.nio.file.Path
import kotlinx.serialization.json.Json

public object Reporters {

    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
    }

    public fun toJson(report: CheckReport): String = json.encodeToString(CheckReport.serializer(), report)

    public fun toJson(report: GenerateReport): String = json.encodeToString(GenerateReport.serializer(), report)

    public fun toText(report: CheckReport, relativeTo: Path? = null): String =
        diagnostics(report.diagnostics, relativeTo) +
            summary(report.queriesChecked, report.migrationsApplied, report.errorCount, report.warningCount)

    public fun toText(report: GenerateReport, relativeTo: Path? = null): String = buildString {
        append(diagnostics(report.diagnostics, relativeTo))
        append(summary(report.queriesChecked, report.migrationsApplied, report.errorCount, report.warningCount))
        append(
            if (report.written.isEmpty()) {
                "Wrote nothing.\n"
            } else {
                "Wrote ${report.written.size} file(s): ${report.written.joinToString(", ")}\n"
            },
        )
    }

    /** Renders a bare diagnostic list, for problems found before any run starts. */
    public fun toText(diagnostics: List<Diagnostic>, relativeTo: Path? = null): String =
        diagnostics(diagnostics, relativeTo)

    /** Compact, one problem per block, always `file:line:col`. */
    private fun diagnostics(diagnostics: List<Diagnostic>, relativeTo: Path?): String {
        val out = StringBuilder()
        for (d in diagnostics) {
            val file = relativeTo?.let { root ->
                runCatching { root.relativize(Path.of(d.file)).toString() }.getOrDefault(d.file)
            } ?: d.file
            val position = listOfNotNull(file, d.line.toString(), d.column?.toString()).joinToString(":")
            val subject = d.query?.let { "$it: " } ?: ""
            out.append("$position: ${d.severity.name.lowercase()} [${d.code}] $subject${d.message}\n")
            d.detail?.let { out.append("  detail: $it\n") }
            d.hint?.let { out.append("  hint:   $it\n") }
        }
        if (diagnostics.isNotEmpty()) out.append('\n')
        return out.toString()
    }

    private fun summary(queries: Int, migrations: Int, errors: Int, warnings: Int): String =
        "Checked $queries quer${if (queries == 1) "y" else "ies"} against $migrations migration(s): " +
            "$errors error(s), $warnings warning(s).\n"
}
