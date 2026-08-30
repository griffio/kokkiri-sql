package io.pgdescribe.cli

import io.pgdescribe.core.CheckConfig
import io.pgdescribe.core.CheckRunner
import io.pgdescribe.core.CleanRunner
import io.pgdescribe.core.CodeGenConfig
import io.pgdescribe.core.GenerateRunner
import io.pgdescribe.core.PgdConfig
import io.pgdescribe.core.Reporters
import io.pgdescribe.core.SchemaRunner
import io.pgdescribe.core.Severity
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.system.exitProcess

private const val USAGE = """
pgd — verify PostgreSQL queries against your migrations, and generate Kotlin for them.

Usage:
  pgd check [options]
  pgd generate [options]
  pgd schema [options]
  pgd clean --url <jdbc>

Options:
  --dir <path>       Project directory holding migrations/, queries/ and pgd.toml
                     (default: db, else .)
  --url <jdbc>       Check against an existing server instead of an embedded one.
                     Also read from PGD_URL. A scratch database is created and dropped;
                     the database named in the URL is never migrated into.
  --queries <path>   Check these queries instead of <dir>/queries. Point it at a
                     previous release's checkout to learn whether a migration
                     breaks the code that is already deployed.
  --format <fmt>     text (default) or json
  --quiet            Suppress progress messages on stderr
  -h, --help         Show this message

generate:
  --out <path>       Output directory for generated Kotlin
  --package <name>   Package for generated code
  --no-schema        Do not write schema.md / schema.json

schema:
  --out <path>       Where to write schema.md and schema.json (default: --dir)

clean:
  Drops the template and scratch databases pgd has created on a server. Only
  meaningful with --url; an embedded server is discarded when the run ends.

Settings also come from pgd.toml in the project directory; flags win over the file.

Exit codes:
  0  no errors
  1  errors found
  2  bad usage or internal failure
"""

private class Options {
    var dir: String? = null
    var url: String? = System.getenv("PGD_URL")
    var format: String = "text"
    var quiet: Boolean = false
    var queries: String? = null
    var out: String? = null
    var packageName: String? = null
    var noSchema: Boolean = false

    val root: Path
        get() {
            dir?.let { return Path.of(it).toAbsolutePath().normalize() }
            // Prefer the documented db/ layout, but tolerate a bare migrations/queries pair.
            val cwd = Path.of("").toAbsolutePath()
            val db = cwd.resolve("db")
            return if (db.resolve("migrations").isDirectory()) db else cwd
        }

    val log: (String) -> Unit
        get() = if (quiet || format == "json") {
            {}
        } else {
            { message -> System.err.println(message) }
        }
}

private val COMMANDS = setOf("check", "generate", "schema", "clean")

public fun main(args: Array<String>) {
    if (args.isEmpty() || args[0] in setOf("-h", "--help", "help")) {
        println(USAGE.trim())
        exitProcess(if (args.isEmpty()) 2 else 0)
    }

    val command = args[0]
    if (command !in COMMANDS) {
        System.err.println("Unknown command '$command'.\n")
        System.err.println(USAGE.trim())
        exitProcess(2)
    }

    val options = Options()
    val rest = args.drop(1)
    var i = 0
    while (i < rest.size) {
        val arg = rest[i]
        val value = { rest.getOrNull(++i) ?: fail("$arg needs a value") }
        when (arg) {
            "--dir" -> options.dir = value()
            "--url" -> options.url = value()
            "--queries" -> options.queries = value()
            "--format" -> options.format = value()
            "--quiet" -> options.quiet = true
            "--out" -> options.out = value()
            "--package" -> options.packageName = value()
            "--no-schema" -> options.noSchema = true
            else -> fail("Unknown option '$arg'")
        }
        i++
    }
    if (options.format !in setOf("text", "json")) fail("--format must be text or json, not '${options.format}'")

    if (command == "clean") {
        val url = options.url ?: fail("clean needs --url (or PGD_URL); an embedded server has nothing to clean")
        exitProcess(
            try {
                val dropped = CleanRunner.run(url) { message -> if (!options.quiet) println(message) }
                println("Dropped $dropped database(s).")
                0
            } catch (e: Exception) {
                System.err.println("pgd: ${e.message ?: e::class.simpleName}")
                2
            },
        )
    }

    val root = options.root
    val (fileConfig, configProblems) = try {
        PgdConfig.load(root)
    } catch (e: Exception) {
        System.err.println("pgd: could not read ${PgdConfig.FILE_NAME}: ${e.message ?: e::class.simpleName}")
        exitProcess(2)
    }
    if (configProblems.any { it.severity == Severity.ERROR }) {
        print(Reporters.toText(configProblems, root.parent))
        exitProcess(1)
    }

    val checkConfig = CheckConfig(
        migrationsDir = root.resolve(fileConfig.migrations),
        // Flags are relative to the shell, as in --out below.
        queriesDir = options.queries?.let { Path.of(it).toAbsolutePath().normalize() }
            ?: root.resolve(fileConfig.queries),
        existingUrl = options.url,
        nullability = fileConfig.nullability,
    )

    exitProcess(
        try {
            when (command) {
                "check" -> {
                    val report = CheckRunner.run(checkConfig, options.log)
                    emit(options, root, Reporters.toJson(report), Reporters.toText(report, root.parent))
                    if (report.ok) 0 else 1
                }

                "schema" -> {
                    val out = options.out?.let { Path.of(it).toAbsolutePath().normalize() } ?: root
                    val report = SchemaRunner.run(checkConfig, out, options.log)
                    emit(options, root, Reporters.toJson(report), Reporters.toText(report, root.parent))
                    if (report.ok) 0 else 1
                }

                else -> {
                    val packageName = options.packageName ?: fileConfig.packageName
                    if (!PACKAGE.matches(packageName)) fail("'$packageName' is not a valid Kotlin package name")
                    val report = GenerateRunner.run(
                        config = checkConfig,
                        codegen = CodeGenConfig(packageName, fileConfig.typeAliases),
                        // Flags are relative to the shell; pgd.toml paths are
                        // relative to pgd.toml, as config files usually are.
                        outputDir = options.out?.let { Path.of(it).toAbsolutePath().normalize() }
                            ?: root.resolve(fileConfig.output).normalize(),
                        schemaDir = if (options.noSchema) null else root,
                        log = options.log,
                    )
                    emit(options, root, Reporters.toJson(report), Reporters.toText(report, root.parent))
                    if (report.ok) 0 else 1
                }
            }
        } catch (e: Exception) {
            System.err.println("pgd: ${e.message ?: e::class.simpleName}")
            2
        },
    )
}

private fun emit(options: Options, root: Path, json: String, text: String) {
    print(if (options.format == "json") "$json\n" else text)
}

private val PACKAGE = Regex("""^[A-Za-z_][A-Za-z0-9_]*(\.[A-Za-z_][A-Za-z0-9_]*)*$""")

private fun fail(message: String): Nothing {
    System.err.println("pgd: $message\n")
    System.err.println(USAGE.trim())
    exitProcess(2)
}
