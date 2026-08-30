package io.pgdescribe.gradle

import io.pgdescribe.core.CheckConfig
import io.pgdescribe.core.CheckRunner
import io.pgdescribe.core.CodeGenConfig
import io.pgdescribe.core.GenerateRunner
import io.pgdescribe.core.PgdConfig
import io.pgdescribe.core.Reporters
import io.pgdescribe.core.Severity
import java.nio.file.Path
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * Inputs shared by both tasks.
 *
 * `migrations/`, `queries/` and `pgd.toml` are snapshotted individually rather
 * than as one project directory, for two reasons. `schema.md` is written *into*
 * that directory, and an output nested inside a declared input can never be up
 * to date. And they are declared as file *collections* so that a missing
 * directory is reported by pgd's own diagnostics — with a line number and a
 * hint — instead of as a Gradle validation failure.
 */
public abstract class PgdTask : DefaultTask() {

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    public abstract val migrations: ConfigurableFileCollection

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    public abstract val queries: ConfigurableFileCollection

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    public abstract val configFile: ConfigurableFileCollection

    /** Changing pgd itself must invalidate the cache. */
    @get:Input
    public abstract val toolVersion: Property<String>

    /** The project directory holding `migrations/`, `queries/` and `pgd.toml`. */
    @get:Internal
    public abstract val directory: DirectoryProperty

    /** Used only to shorten paths in diagnostics. */
    @get:Internal
    public abstract val reportRoot: DirectoryProperty

    /**
     * Deliberately not an input: which server ran the analysis does not change
     * the result, so switching PGD_URL should not invalidate anything.
     */
    @get:Internal
    public abstract val url: Property<String>

    /**
     * Whether Postgres' parser is in play. It changes the generated types, so it
     * has to be part of the cache key: without it, an artifact produced on a
     * machine with libpg_query could be restored onto one without.
     */
    @get:Input
    public abstract val analysisMode: Property<String>

    protected fun checkConfig(): CheckConfig {
        val config = fileConfig()
        return CheckConfig(
            migrationsDir = directory.get().asFile.toPath().resolve(config.migrations),
            queriesDir = directory.get().asFile.toPath().resolve(config.queries),
            existingUrl = url.orNull ?: System.getenv("PGD_URL"),
            nullability = config.nullability,
        )
    }

    protected fun fileConfig(): PgdConfig {
        val (config, problems) = PgdConfig.load(directory.get().asFile.toPath())
        if (problems.any { it.severity == Severity.ERROR }) {
            logger.error(Reporters.toText(problems))
            throw GradleException("pgd: ${PgdConfig.FILE_NAME} has errors.")
        }
        return config
    }

    protected fun projectPath(): Path = reportRoot.get().asFile.toPath()
}

/** Verifies every query against the migrations. Emits nothing but a report. */
@CacheableTask
public abstract class PgdCheckTask : PgdTask() {

    @get:OutputFile
    public abstract val report: RegularFileProperty

    @TaskAction
    public fun run() {
        val result = CheckRunner.run(checkConfig()) { logger.info(it) }
        val text = Reporters.toText(result, projectPath())
        report.get().asFile.also { it.parentFile.mkdirs() }.writeText(text)
        if (!result.ok) {
            logger.error(text)
            throw GradleException("pgd check found ${result.errorCount} error(s).")
        }
        logger.lifecycle(text.trim())
    }
}

/** Verifies, then writes Kotlin and the schema snapshot. */
@CacheableTask
public abstract class PgdGenerateTask : PgdTask() {

    @get:Input
    @get:Optional
    public abstract val packageName: Property<String>

    @get:OutputDirectory
    public abstract val outputDirectory: DirectoryProperty

    @get:OutputFile
    @get:Optional
    public abstract val schemaMarkdown: RegularFileProperty

    @get:OutputFile
    @get:Optional
    public abstract val schemaJson: RegularFileProperty

    @TaskAction
    public fun run() {
        val config = fileConfig()
        val output = outputDirectory.get().asFile
        // Stale output would otherwise survive a query being deleted or renamed.
        output.deleteRecursively()
        output.mkdirs()

        val result = GenerateRunner.run(
            config = checkConfig(),
            codegen = CodeGenConfig(packageName.orNull ?: config.packageName, config.typeAliases),
            outputDir = output.toPath(),
            schemaDir = schemaMarkdown.orNull?.asFile?.parentFile?.toPath(),
        ) { logger.info(it) }

        val text = Reporters.toText(result, projectPath())
        if (!result.ok) {
            logger.error(text)
            throw GradleException("pgd generate found ${result.errorCount} error(s).")
        }
        logger.lifecycle(text.trim())
    }
}
