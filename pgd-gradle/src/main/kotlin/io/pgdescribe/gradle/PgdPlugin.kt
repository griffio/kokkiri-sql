package io.pgdescribe.gradle

import io.pgdescribe.core.PGD_VERSION
import io.pgdescribe.core.PgdConfig
import io.pgdescribe.core.SqlParsers
import org.gradle.api.GradleException
import org.gradle.api.NamedDomainObjectContainer
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.SourceDirectorySet
import org.gradle.api.tasks.TaskProvider

public class PgdPlugin : Plugin<Project> {

    override fun apply(project: Project): Unit = with(project) {
        val extension = extensions.create("pgd", PgdExtension::class.java).apply {
            directory.convention(layout.projectDirectory.dir("db"))
            outputDirectory.convention(layout.buildDirectory.dir("generated/pgd/kotlin"))
            generateSchema.convention(true)
            addToSourceSet.convention(true)
        }

        val check = tasks.register("pgdCheck", PgdCheckTask::class.java) { task ->
            task.group = GROUP
            task.description = "Verifies every query in the project against the migrations."
            task.wireInputs(project, extension)
            task.report.convention(layout.buildDirectory.file("reports/pgd/check.txt"))
        }

        val generate = tasks.register("pgdGenerate", PgdGenerateTask::class.java) { task ->
            task.group = GROUP
            task.description = "Verifies every query and generates Kotlin for it."
            task.wireInputs(project, extension)
            task.packageName.set(extension.packageName)
            task.outputDirectory.set(extension.outputDirectory)
            task.schemaMarkdown.set(
                extension.generateSchema.flatMap { on ->
                    if (on) extension.directory.file("schema.md") else providers.provider { null }
                },
            )
            task.schemaJson.set(
                extension.generateSchema.flatMap { on ->
                    if (on) extension.directory.file("schema.json") else providers.provider { null }
                },
            )
        }

        plugins.withId("base") {
            tasks.named("check") { it.dependsOn(check) }
        }

        plugins.withId("org.jetbrains.kotlin.jvm") {
            if (extension.addToSourceSet.getOrElse(true)) addGeneratedSourceDir(generate)
        }
    }

    /**
     * Adds the generated directory to the main Kotlin source set without
     * compiling against the Kotlin Gradle plugin.
     *
     * A `compileOnly` dependency on KGP is not enough. Under Gradle TestKit —
     * and anywhere else the two plugins land in sibling classloaders — this
     * plugin's classloader cannot see KGP's DSL types, and naming one throws
     * NoClassDefFoundError at apply time. Going through Gradle's own
     * SourceDirectorySet keeps the coupling to the part of KGP that has
     * actually stayed stable.
     */
    private fun Project.addGeneratedSourceDir(task: TaskProvider<PgdGenerateTask>) {
        val kotlin = extensions.findByName("kotlin") ?: return
        try {
            val sourceSets = kotlin.javaClass.getMethod("getSourceSets")
                .invoke(kotlin) as NamedDomainObjectContainer<*>
            val main = sourceSets.getByName("main")
            val directories = main.javaClass.getMethod("getKotlin").invoke(main) as SourceDirectorySet
            // Only the generated Kotlin, not the task's other outputs: schema.md
            // and schema.json are files, and a file cannot be a source directory.
            // flatMap keeps the dependency on the task that produces it.
            directories.srcDir(task.flatMap { it.outputDirectory })
        } catch (e: ReflectiveOperationException) {
            throw GradleException(
                "pgd could not add its generated sources to the main Kotlin source set " +
                    "(${e.message}). Set pgd { addToSourceSet.set(false) } and add " +
                    "the directory to your source set yourself.",
                e,
            )
        }
    }

    private fun PgdTask.wireInputs(project: Project, extension: PgdExtension) {
        migrations.from(extension.directory.dir("migrations"))
        queries.from(extension.directory.dir("queries"))
        configFile.from(extension.directory.file(PgdConfig.FILE_NAME))
        directory.set(extension.directory)
        reportRoot.set(project.layout.projectDirectory)
        url.set(extension.url)
        toolVersion.set(PGD_VERSION)
        analysisMode.set(SqlParsers.available()?.description ?: "conservative")
    }

    private companion object {
        const val GROUP = "pgd"
    }
}
