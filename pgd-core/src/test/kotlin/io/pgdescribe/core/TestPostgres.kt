package io.pgdescribe.core

/**
 * One embedded server for the whole test JVM. Tests pass its URL to
 * [CheckConfig.existingUrl] so each case still gets its own scratch database,
 * without paying the server start-up cost per test.
 */
internal object TestPostgres {

    val startupMillis: Long

    private val server: EmbeddedPostgresServer

    init {
        val begun = System.nanoTime()
        server = EmbeddedPostgresServer.start()
        startupMillis = (System.nanoTime() - begun) / 1_000_000
        println("embedded Postgres ready in $startupMillis ms")
        Runtime.getRuntime().addShutdownHook(Thread { runCatching { server.close() } })
    }

    val url: String get() = server.urlFor("postgres")
}
