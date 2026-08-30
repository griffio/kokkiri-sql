package io.pgdescribe.core

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.walk
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GenerateRunnerTest {

    private val schema = """
        CREATE TABLE person (
            id       bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
            name     text    NOT NULL,
            note     text,
            age      integer NOT NULL,
            object   text    NOT NULL
        );
        CREATE TABLE pet (
            id        bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
            person_id bigint NOT NULL REFERENCES person (id),
            nickname  text   NOT NULL
        );
    """.trimIndent()

    private val arraySchema = """
        CREATE DOMAIN email_address AS text;
        CREATE TABLE tagged (
            id      bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
            tags    text[],
            contact email_address
        );
    """.trimIndent()

    private val enumSchema = """
        CREATE TYPE mood AS ENUM ('sad', 'ok', 'over the moon');
        CREATE TABLE feeling (
            id   bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
            mood mood NOT NULL
        );
    """.trimIndent()

    private class Result(val report: GenerateReport, val code: String?, val enums: String?)

    @OptIn(kotlin.io.path.ExperimentalPathApi::class)
    private fun generate(
        queries: String,
        migration: String = schema,
        aliases: Map<String, String> = emptyMap(),
    ): Result {
        val root = Files.createTempDirectory("pgd-gen")
        root.resolve("migrations").createDirectories()
            .resolve("V001__schema.sql").writeText(migration)
        root.resolve("queries").createDirectories()
            .resolve("q.sql").writeText(queries.trimIndent())

        val out: Path = root.resolve("out")
        val report = GenerateRunner.run(
            config = CheckConfig.forRoot(root, TestPostgres.url),
            codegen = CodeGenConfig("db", aliases),
            outputDir = out,
        )
        val kotlinFiles = if (Files.exists(out)) {
            out.walk().filter { it.toString().endsWith(".kt") }.toList()
        } else {
            emptyList()
        }
        return Result(
            report = report,
            code = kotlinFiles.firstOrNull {
                !it.toString().endsWith("Enums.kt") && !it.toString().endsWith("PgdCopy.kt")
            }?.readText(),
            enums = kotlinFiles.firstOrNull { it.toString().endsWith("Enums.kt") }?.readText(),
        )
    }

    @Test
    fun `a single non-null column collapses to a scalar list`() {
        val result = generate("-- name: AllNames :many\nSELECT name FROM person;")
        assertTrue(result.report.ok, Reporters.toText(result.report))
        val code = result.code!!
        assertTrue("fun Connection.allNames(): List<String> =" in code, code)
        assertFalse("data class" in code, code)
    }

    @Test
    fun `a single nullable column keeps its nullability in the list`() {
        val code = generate("-- name: AllNotes :many\nSELECT note FROM person;").code!!
        assertTrue("List<String?>" in code, code)
    }

    @Test
    fun `one with a single non-null column returns the value directly`() {
        val code = generate("-- name: OneName :one\nSELECT name FROM person LIMIT 1;").code!!
        assertTrue("fun Connection.oneName(): String? =" in code, code)
        assertFalse("data class" in code, code)
    }

    @Test
    fun `one with a single nullable column keeps a wrapper so absence stays distinguishable`() {
        val code = generate("-- name: OneNote :one\nSELECT note FROM person LIMIT 1;").code!!
        assertTrue("data class OneNoteRow(" in code, code)
        assertTrue("val note: String?," in code, code)
        assertTrue("fun Connection.oneNote(): OneNoteRow? =" in code, code)
    }

    @Test
    fun `a row class also gets a mapper overload that does the work`() {
        val code = generate("-- name: People :many\nSELECT id, name FROM person;").code!!
        assertTrue("fun <T : Any> Connection.people(\n" in code, code)
        assertTrue("    mapper: (\n        id: Long,\n        name: String,\n    ) -> T,\n" in code, code)
        assertTrue("): List<T> =" in code, code)
        assertTrue("val rows = ArrayList<T>()" in code, code)
        // The named form is a one-line delegate, not a second copy of the body.
        assertTrue("fun Connection.people(): List<PeopleRow> =\n    people(::PeopleRow)\n" in code, code)
    }

    @Test
    fun `the mapper is invoked positionally, since function types reject named arguments`() {
        val code = generate("-- name: People :many\nSELECT id, name FROM person;").code!!
        assertTrue("mapper(\n                    resultSet.getLong(1),\n" in code, code)
        assertFalse("mapper(\n                    id =" in code, code)
    }

    @Test
    fun `one and exactlyone carry the mapper through their own return types`() {
        val one = generate("-- name: Someone :one\nSELECT id, name FROM person LIMIT 1;").code!!
        assertTrue("): T? =" in one, one)
        assertTrue("fun Connection.someone(): SomeoneRow? =\n    someone(::SomeoneRow)\n" in one, one)

        val exact = generate(
            "-- name: Newest :exactlyone\nSELECT id, name FROM person ORDER BY id DESC LIMIT 1;",
        ).code!!
        assertTrue("): T =" in exact, exact)
        assertTrue("fun Connection.newest(): NewestRow =\n    newest(::NewestRow)\n" in exact, exact)
    }

    @Test
    fun `a collapsed scalar query has nothing to map, so it gets no overload`() {
        val code = generate("-- name: AllNames :many\nSELECT name FROM person;").code!!
        assertFalse("mapper" in code, code)
    }

    @Test
    fun `statements with no result set get no mapper`() {
        val exec = generate("-- name: Wipe :exec\nDELETE FROM person;").code!!
        assertFalse("mapper" in exec, exec)
        val copy = generate("-- name: LoadPeople :copy\nCOPY person (name) FROM STDIN;").code!!
        assertFalse("mapper" in copy, copy)
    }

    @Test
    fun `a parameter named mapper does not shadow the mapper itself`() {
        val code = generate(
            "-- name: ByMapper :many\n-- params: mapper\nSELECT id, name FROM person WHERE name = \$1::text;",
        ).code!!
        assertTrue("mapper: String," in code, code)
        assertTrue("mapper_: (" in code, code)
        assertTrue("byMapper(mapper, ::ByMapperRow)" in code, code)
    }

    @Test
    fun `exec generates a Unit function`() {
        val code = generate("-- name: Wipe :exec\nDELETE FROM pet;").code!!
        assertTrue("fun Connection.wipe() {" in code, code)
        assertTrue("statement.execute()" in code, code)
    }

    @Test
    fun `execrows returns the update count`() {
        val code = generate("-- name: Wipe :execrows\nDELETE FROM pet;").code!!
        assertTrue("fun Connection.wipe(): Int =" in code, code)
        assertTrue("statement.executeUpdate()" in code, code)
    }

    @Test
    fun `an outer join makes every column nullable`() {
        val code = generate(
            """
            -- name: PeopleAndPets :many
            SELECT p.name, t.nickname FROM person p LEFT JOIN pet t ON t.person_id = p.id;
            """,
        ).code!!
        assertTrue("val name: String?," in code, code)
        assertTrue("val nickname: String?," in code, code)
    }

    @Test
    fun `notnull overrides an outer join demotion`() {
        val code = generate(
            """
            -- name: PeopleAndPets :many
            -- notnull: name
            SELECT p.name, t.nickname FROM person p LEFT JOIN pet t ON t.person_id = p.id;
            """,
        ).code!!
        assertTrue("val name: String," in code, code)
        assertTrue("val nickname: String?," in code, code)
    }

    @Test
    fun `nullable overrides a proven non-null column`() {
        val code = generate(
            """
            -- name: Names :many
            -- nullable: name
            SELECT name FROM person;
            """,
        ).code!!
        assertTrue("List<String?>" in code, code)
    }

    @Test
    fun `an override naming a column that is not returned is an error`() {
        val result = generate(
            """
            -- name: Names :many
            -- notnull: nmae
            SELECT name FROM person;
            """,
        )
        assertFalse(result.report.ok)
        val diagnostic = result.report.diagnostics.single { it.code == Diagnostic.UNKNOWN_OVERRIDE }
        assertTrue("name" in diagnostic.hint.orEmpty(), diagnostic.hint.orEmpty())
        assertNull(result.code, "nothing should be written when the project has errors")
    }

    @Test
    fun `a Kotlin keyword column name is escaped`() {
        val code = generate("-- name: Objects :many\nSELECT object, name FROM person;").code!!
        assertTrue("val `object`: String," in code, code)
    }

    @Test
    fun `two columns that collapse to the same property name are rejected`() {
        val result = generate("-- name: Ids :many\nSELECT p.id, t.id FROM person p JOIN pet t ON t.person_id = p.id;")
        assertFalse(result.report.ok)
        val diagnostic = result.report.diagnostics.single { it.code == Diagnostic.DUPLICATE_COLUMN_LABEL }
        assertTrue("AS" in diagnostic.hint.orEmpty(), diagnostic.hint.orEmpty())
    }

    @Test
    fun `an unmapped Postgres type is a loud error rather than a silent String`() {
        val result = generate("-- name: Where :many\nSELECT '(1,2)'::point AS at;")
        assertFalse(result.report.ok)
        val diagnostic = result.report.diagnostics.single { it.code == Diagnostic.UNMAPPED_TYPE }
        assertTrue("pgd.toml" in diagnostic.hint.orEmpty(), diagnostic.hint.orEmpty())
    }

    @Test
    fun `a type alias rescues an otherwise unmapped type`() {
        val result = generate(
            "-- name: Gap :many\nSELECT '1 day'::interval AS gap;",
            aliases = mapOf("interval" to "text"),
        )
        assertTrue(result.report.ok, Reporters.toText(result.report))
        assertTrue("List<String?>" in result.code!!, result.code)
    }

    @Test
    fun `an array column becomes a list with nullable elements`() {
        val code = generate("-- name: Tags :many\nSELECT tags FROM tagged;", migration = arraySchema).code!!
        assertTrue("List<List<String?>?>" in code, code)
        assertTrue("array.array as Array<*>" in code, code)
    }

    @Test
    fun `an array parameter is bound with createArrayOf`() {
        val code = generate(
            """
            -- name: WithAnyTag :many
            -- params: tags
            SELECT id FROM tagged WHERE tags && $1::text[];
            """,
            migration = arraySchema,
        ).code!!
        assertTrue("tags: List<String?>" in code, code)
        assertTrue("""createArrayOf("text", tags.toTypedArray())""" in code, code)
    }

    @Test
    fun `an enum column generates a Kotlin enum and reads through it`() {
        val result = generate("-- name: Moods :many\nSELECT mood FROM feeling;", migration = enumSchema)
        assertTrue(result.report.ok, Reporters.toText(result.report))
        assertTrue(result.report.written.any { it.endsWith("Enums.kt") }, result.report.written.toString())
        assertTrue("List<Mood>" in result.code!!, result.code)
        assertTrue("Mood.fromLabel(checkNotNull(resultSet.getString(1)))" in result.code, result.code)
    }

    @Test
    fun `an enum parameter is bound as an untyped label`() {
        val code = generate(
            """
            -- name: InMood :many
            -- params: mood
            SELECT id FROM feeling WHERE mood = $1::mood;
            """,
            migration = enumSchema,
        ).code!!
        assertTrue("mood: Mood" in code, code)
        assertTrue("statement.setObject(1, mood.label, Types.OTHER)" in code, code)
        assertTrue("import java.sql.Types" in code, code)
    }

    @Test
    fun `an enum label that is not an identifier still becomes a usable entry`() {
        val result = generate("-- name: Moods :many\nSELECT mood FROM feeling;", migration = enumSchema)
        val enums = result.enums!!
        assertTrue("OVER_THE_MOON(\"over the moon\")," in enums, enums)
    }

    @Test
    fun `an enum can be opted out of its Kotlin class with an alias`() {
        val result = generate(
            "-- name: Moods :many\nSELECT mood FROM feeling;",
            migration = enumSchema,
            aliases = mapOf("mood" to "text"),
        )
        assertTrue(result.report.ok, Reporters.toText(result.report))
        assertTrue("List<String>" in result.code!!, result.code)
        assertFalse(result.report.written.any { it.endsWith("Enums.kt") })
    }

    @Test
    fun `a domain resolves to its base type`() {
        val code = generate(
            "-- name: Contacts :many\nSELECT contact FROM tagged;",
            migration = arraySchema,
        ).code!!
        assertTrue("List<String?>" in code, code)
    }

    @Test
    fun `an alias cycle does not hang`() {
        val result = generate(
            "-- name: Gap :many\nSELECT '1 day'::interval AS gap;",
            aliases = mapOf("interval" to "reltime", "reltime" to "interval"),
        )
        assertFalse(result.report.ok)
        assertTrue(result.report.diagnostics.any { it.code == Diagnostic.UNMAPPED_TYPE })
    }

    @Test
    fun `duplicate parameter names are rejected`() {
        val result = generate(
            """
            -- name: Find :many
            -- params: value, value
            SELECT name FROM person WHERE name = $1::text OR note = $2::text;
            """,
        )
        assertFalse(result.report.ok)
        assertEquals(1, result.report.diagnostics.count { it.code == Diagnostic.DUPLICATE_PARAM_NAME })
    }

    @Test
    fun `a repeated placeholder binds the same argument twice`() {
        val code = generate(
            """
            -- name: Find :many
            -- params: needle
            SELECT name FROM person WHERE name = $1::text OR note = $1::text;
            """,
        ).code!!
        assertTrue("fun Connection.find(needle: String): List<String> =" in code, code)
        assertTrue("statement.setString(1, needle)" in code, code)
        assertTrue("statement.setString(2, needle)" in code, code)
    }

    @Test
    fun `parameters without declared names fall back to positional names`() {
        val code = generate("-- name: Find :many\nSELECT name FROM person WHERE age = $1::integer;").code!!
        assertTrue("fun Connection.find(param1: Int): List<String> =" in code, code)
    }

    @Test
    fun `timestamptz maps to OffsetDateTime, never a local type`() {
        val code = generate("-- name: Now :one\nSELECT now() AS at;").code!!
        assertTrue("import java.time.OffsetDateTime" in code, code)
        assertFalse("LocalDateTime" in code, code)
    }

    @Test
    fun `nothing is written when a query fails to check`() {
        val result = generate("-- name: Bad :many\nSELECT nope FROM person;")
        assertFalse(result.report.ok)
        assertEquals(emptyList(), result.report.written)
        assertNull(result.code)
    }

    @Test
    fun `generation is deterministic`() {
        val query = "-- name: AllNames :many\nSELECT name, note FROM person;"
        assertEquals(generate(query).code, generate(query).code)
    }

    @Test
    fun `exactlyone returns a non-null row`() {
        val code = generate(
            "-- name: Newest :exactlyone\nSELECT id, name FROM person ORDER BY id DESC LIMIT 1;",
        ).code!!
        assertTrue("fun Connection.newest(): NewestRow =" in code, code)
        assertTrue("declared :exactlyone but matched no rows" in code, code)
        assertTrue("declared :exactlyone but matched more than one row" in code, code)
    }

    @Test
    fun `exactlyone collapses a single column without losing its nullability`() {
        assertTrue(
            "fun Connection.total(): Long =" in
                generate("-- name: Total :exactlyone\n-- notnull: count\nSELECT count(*) FROM person;").code!!,
        )
        assertTrue(
            "fun Connection.note(): String? =" in
                generate("-- name: Note :exactlyone\nSELECT note FROM person LIMIT 1;").code!!,
        )
    }

    @Test
    fun `batch generates a params class and a batching function`() {
        val code = generate(
            """
            -- name: Rename :execrows
            -- params: id, name
            -- batch
            UPDATE person SET name = $2::text WHERE id = $1::bigint;
            """,
        ).code!!
        assertTrue("data class RenameParams(" in code, code)
        assertTrue("val id: Long," in code, code)
        assertTrue("fun Connection.renameBatch(rows: Iterable<RenameParams>): IntArray =" in code, code)
        assertTrue("statement.addBatch()" in code, code)
        assertTrue("statement.executeBatch()" in code, code)
        // The single-row function is still there.
        assertTrue("fun Connection.rename(id: Long, name: String): Int =" in code, code)
    }

    @Test
    fun `a single parameter batches without a wrapper class`() {
        val code = generate(
            """
            -- name: Forget :execrows
            -- params: id
            -- batch
            DELETE FROM person WHERE id = $1::bigint;
            """,
        ).code!!
        assertTrue("fun Connection.forgetBatch(rows: Iterable<Long>): IntArray =" in code, code)
        assertTrue("statement.setLong(1, row)" in code, code)
        assertFalse("ForgetParams" in code, code)
    }

    @Test
    fun `batching a statement that returns rows is rejected`() {
        // Given a parameter, so only the cardinality objection can fire.
        val result = generate(
            "-- name: All :many\n-- params: id\n-- batch\nSELECT name FROM person WHERE id = $1::bigint;",
        )
        assertFalse(result.report.ok)
        val diagnostic = result.report.diagnostics.single { it.code == Diagnostic.BATCH_NOT_APPLICABLE }
        assertTrue("result set" in diagnostic.hint.orEmpty(), diagnostic.hint.orEmpty())
    }

    @Test
    fun `batching a statement with no parameters is rejected`() {
        val result = generate("-- name: Wipe :exec\n-- batch\nDELETE FROM person;")
        assertFalse(result.report.ok)
        assertEquals(1, result.report.diagnostics.count { it.code == Diagnostic.BATCH_NOT_APPLICABLE })
    }

    @Test
    fun `copy generates a row class, a loader and the shared encoder`() {
        val result = generate("-- name: Load :copy\nCOPY person (name, note, age) FROM STDIN;")
        assertTrue(result.report.ok, Reporters.toText(result.report))
        val code = result.code!!
        assertTrue("data class LoadRow(" in code, code)
        // NOT NULL columns are required; nullable ones are not.
        assertTrue("val name: String," in code, code)
        assertTrue("val note: String?," in code, code)
        assertTrue("val age: Int," in code, code)
        assertTrue("fun Connection.load(rows: Iterable<LoadRow>): Long {" in code, code)
        assertTrue("copy.endCopy()" in code, code)
        assertTrue("cancelCopy()" in code, code)
        assertTrue(result.report.written.any { it.endsWith("PgdCopy.kt") }, result.report.written.toString())
    }

    @Test
    fun `copy validates its column list against the real schema`() {
        val result = generate("-- name: Load :copy\nCOPY person (name, nmae) FROM STDIN;")
        assertFalse(result.report.ok)
        val diagnostic = result.report.diagnostics.single { it.code == Diagnostic.SQL_ERROR }
        assertEquals("42703", diagnostic.sqlState)
    }

    @Test
    fun `copy without an explicit column list is rejected`() {
        val result = generate("-- name: Load :copy\nCOPY person FROM STDIN;")
        assertFalse(result.report.ok)
        val diagnostic = result.report.diagnostics.single { it.code == Diagnostic.COPY_FORM }
        assertTrue("COPY table (column, column) FROM STDIN" in diagnostic.hint.orEmpty(), diagnostic.hint.orEmpty())
    }

    @Test
    fun `copy from a file rather than STDIN is rejected`() {
        val result = generate("-- name: Load :copy\nCOPY person (name) FROM '/tmp/people.csv';")
        assertFalse(result.report.ok)
        assertEquals(1, result.report.diagnostics.count { it.code == Diagnostic.COPY_FORM })
    }

    @Test
    fun `copy naming a column twice is rejected`() {
        val result = generate("-- name: Load :copy\nCOPY person (name, name) FROM STDIN;")
        assertFalse(result.report.ok)
        val diagnostic = result.report.diagnostics.single { it.code == Diagnostic.COPY_FORM }
        assertTrue("twice" in diagnostic.message, diagnostic.message)
    }

    @Test
    fun `copy with placeholders is rejected`() {
        val result = generate("-- name: Load :copy\nCOPY person (name) FROM STDIN WHERE name = $1;")
        assertFalse(result.report.ok)
        assertTrue(result.report.diagnostics.any { it.code == Diagnostic.COPY_FORM })
    }

    @Test
    fun `copy of an array column is rejected rather than silently mangled`() {
        val result = generate(
            "-- name: Load :copy\nCOPY tagged (tags) FROM STDIN;",
            migration = arraySchema,
        )
        assertFalse(result.report.ok)
        val diagnostic = result.report.diagnostics.single { it.code == Diagnostic.COPY_FORM }
        assertTrue("Arrays" in diagnostic.hint.orEmpty(), diagnostic.hint.orEmpty())
    }

    @Test
    fun `copy writes an enum as its label`() {
        val code = generate("-- name: Load :copy\nCOPY feeling (mood) FROM STDIN;", migration = enumSchema).code!!
        assertTrue("pgdCopyText(row.mood.label)" in code, code)
    }
}
