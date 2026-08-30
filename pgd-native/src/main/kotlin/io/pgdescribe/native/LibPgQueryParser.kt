package io.pgdescribe.native

import io.pgdescribe.core.SqlParser

/**
 * The service entry point, compiled for Java 17 so this module can sit on any
 * consumer's classpath.
 *
 * The real work happens in `FfmPgQueryParser`, which uses the Foreign Function
 * & Memory API and is therefore compiled for Java 22. It is reached by name so
 * that on an older JVM the class is never loaded: `Class.forName` fails with
 * `UnsupportedClassVersionError`, this constructor throws, and `SqlParsers`
 * reads that as "no parser installed" and falls back to the conservative rule.
 *
 * The same path handles a machine with a new enough JVM but no libpg_query.
 */
public class LibPgQueryParser : SqlParser {

    private val delegate: SqlParser = try {
        Class.forName(IMPLEMENTATION)
            .getDeclaredConstructor()
            .newInstance() as SqlParser
    } catch (e: ReflectiveOperationException) {
        throw unavailable(e.cause ?: e)
    } catch (e: LinkageError) {
        throw unavailable(e)
    }

    override val description: String get() = delegate.description

    override fun parseTreeJson(sql: String): String? = delegate.parseTreeJson(sql)

    private fun unavailable(cause: Throwable): UnsupportedOperationException =
        UnsupportedOperationException(
            "libpg_query is not usable here (${cause.message}). It needs Java 22 or newer " +
                "and the libpg_query shared library.",
            cause,
        )

    private companion object {
        const val IMPLEMENTATION = "io.pgdescribe.native.FfmPgQueryParser"
    }
}
