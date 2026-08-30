package io.pgdescribe.gradle

import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property

/**
 * ```kotlin
 * pgd {
 *     directory.set(layout.projectDirectory.dir("db"))
 *     packageName.set("com.example.db")
 * }
 * ```
 *
 * Anything left unset falls back to `pgd.toml` in [directory], then to pgd's
 * own defaults.
 */
public abstract class PgdExtension {
    /** Project directory holding `migrations/`, `queries/` and `pgd.toml`. */
    public abstract val directory: DirectoryProperty

    /** Where generated Kotlin goes. */
    public abstract val outputDirectory: DirectoryProperty

    /** Package for generated code. Overrides `pgd.toml`. */
    public abstract val packageName: Property<String>

    /**
     * JDBC URL of an existing server. Falls back to `PGD_URL`. When unset an
     * embedded Postgres is started for the duration of the task.
     */
    public abstract val url: Property<String>

    /** Write `schema.md` and `schema.json` into [directory]. Defaults to true. */
    public abstract val generateSchema: Property<Boolean>

    /** Add the generated directory to the main Kotlin source set. Defaults to true. */
    public abstract val addToSourceSet: Property<Boolean>
}
