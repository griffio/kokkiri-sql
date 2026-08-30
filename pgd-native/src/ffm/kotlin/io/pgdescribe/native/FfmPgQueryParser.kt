package io.pgdescribe.native

import io.pgdescribe.core.SqlParser
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemoryLayout
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout
import java.io.File
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile

/**
 * Postgres' own parser, reached through libpg_query.
 *
 * ```c
 * typedef struct {
 *   char* parse_tree;
 *   char* stderr_buffer;
 *   PgQueryError* error;
 * } PgQueryParseResult;
 *
 * PgQueryParseResult pg_query_parse(const char* input);
 * void pg_query_free_parse_result(PgQueryParseResult result);
 * ```
 *
 * Compiled for Java 22, where the Foreign Function & Memory API was finalised.
 * [LibPgQueryParser] reaches it reflectively so that this class is simply never
 * loaded on an older JVM.
 *
 * The constructor throws when the library cannot be found; `SqlParsers` treats
 * that as "no parser installed" rather than an error.
 */
public class FfmPgQueryParser : SqlParser {

    private val arena = Arena.ofShared()
    private val library = locate(arena)
    private val linker = Linker.nativeLinker()

    private val parse = linker.downcallHandle(
        library.find(PARSE).orElseThrow { UnsatisfiedLinkError("$PARSE is not exported by libpg_query") },
        FunctionDescriptor.of(PARSE_RESULT, ValueLayout.ADDRESS),
    )

    private val free = linker.downcallHandle(
        library.find(FREE).orElseThrow { UnsatisfiedLinkError("$FREE is not exported by libpg_query") },
        FunctionDescriptor.ofVoid(PARSE_RESULT),
    )

    override val description: String = "libpg_query"

    override fun parseTreeJson(sql: String): String? = Arena.ofConfined().use { call ->
        val result = parse.invokeWithArguments(call, call.allocateFrom(sql)) as MemorySegment
        try {
            // A parse failure is not this tool's problem to report: Postgres has
            // already accepted or rejected the statement over the wire.
            if (result.get(ValueLayout.ADDRESS, ERROR_OFFSET) != MemorySegment.NULL) return@use null
            val tree = result.get(ValueLayout.ADDRESS, TREE_OFFSET)
            if (tree == MemorySegment.NULL) null else tree.reinterpret(Long.MAX_VALUE).getString(0)
        } finally {
            free.invokeWithArguments(result)
        }
    }

    private companion object {
        const val PARSE = "pg_query_parse"
        const val FREE = "pg_query_free_parse_result"

        /** Where to look, in order, before giving up. */
        const val PATH_PROPERTY = "pgd.libpgquery.path"
        const val PATH_ENV = "PGD_LIBPG_QUERY"

        val PARSE_RESULT: MemoryLayout = MemoryLayout.structLayout(
            ValueLayout.ADDRESS.withName("parse_tree"),
            ValueLayout.ADDRESS.withName("stderr_buffer"),
            ValueLayout.ADDRESS.withName("error"),
        )

        const val TREE_OFFSET = 0L
        val ERROR_OFFSET: Long = PARSE_RESULT.byteOffset(MemoryLayout.PathElement.groupElement("error"))

        /**
         * Where the library is looked up, in order. The bare name is last
         * because macOS does not reliably search /usr/local/lib for it, which
         * is exactly where Homebrew puts it.
         */
        val SEARCH_DIRECTORIES: List<String> = listOf(
            "/opt/homebrew/lib",
            "/usr/local/lib",
            "/usr/lib",
            "/usr/lib64",
            "/usr/lib/x86_64-linux-gnu",
            "/usr/lib/aarch64-linux-gnu",
        )

        fun locate(arena: Arena): SymbolLookup {
            val fileName = System.mapLibraryName("pg_query")
            val explicit = System.getProperty(PATH_PROPERTY) ?: System.getenv(PATH_ENV)
            if (explicit != null) {
                val path = Path.of(explicit)
                val file = if (path.isDirectory()) path.resolve(fileName) else path
                return SymbolLookup.libraryLookup(file, arena)
            }

            val fromLibraryPath = System.getProperty("java.library.path").orEmpty()
                .split(File.pathSeparator)
                .filter { it.isNotBlank() }
            for (directory in fromLibraryPath + SEARCH_DIRECTORIES) {
                val candidate = Path.of(directory, fileName)
                if (candidate.isRegularFile()) {
                    return SymbolLookup.libraryLookup(candidate, arena)
                }
            }
            return SymbolLookup.libraryLookup(fileName, arena)
        }
    }
}
