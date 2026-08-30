package io.pgdescribe.core

import java.util.ServiceLoader

/**
 * Access to Postgres' real parser.
 *
 * Implemented by `pgd-native`, which binds libpg_query through the Foreign
 * Function & Memory API. It is deliberately the narrowest possible interface —
 * a string in, the parse tree as JSON out — so that every rule derived from the
 * tree lives in this module, where it can be tested against captured fixtures
 * with no native code present.
 */
public interface SqlParser {
    /** The parse tree as JSON, or null when the statement could not be parsed. */
    public fun parseTreeJson(sql: String): String?

    /** Shown in diagnostics so it is obvious which path a run took. */
    public val description: String
}

/**
 * Finds a [SqlParser] if one is installed.
 *
 * Every failure mode is a miss, not an error: the module may be absent, may be
 * compiled for a newer JVM than this one, or may be present on a machine with
 * no libpg_query. In all of those cases nullability analysis falls back to the
 * conservative statement-wide rule and the run still succeeds.
 */
public object SqlParsers {

    public const val DISABLE_PROPERTY: String = "pgd.native.disabled"
    public const val DISABLE_ENV: String = "PGD_NO_NATIVE"

    private val loaded: SqlParser? by lazy { load() }

    public fun available(): SqlParser? = loaded

    private fun load(): SqlParser? {
        if (System.getProperty(DISABLE_PROPERTY).toBoolean()) return null
        if (System.getenv(DISABLE_ENV) != null) return null
        return try {
            ServiceLoader.load(SqlParser::class.java, SqlParser::class.java.classLoader).firstOrNull()
        } catch (e: Throwable) {
            // UnsupportedClassVersionError on an older JVM, ServiceConfigurationError
            // for a bad provider, UnsatisfiedLinkError when the library is missing.
            null
        }
    }
}
