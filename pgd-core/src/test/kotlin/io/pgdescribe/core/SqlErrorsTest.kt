package io.pgdescribe.core

import kotlin.test.Test
import kotlin.test.assertEquals

class SqlErrorsTest {

    @Test
    fun `maps an offset on the first line`() {
        assertEquals(0 to 8, SqlErrors.offsetToLineColumn("SELECT emial FROM users", 8))
    }

    @Test
    fun `maps an offset on a later line`() {
        val sql = "SELECT id\nFROM userz\nWHERE id = 1"
        // 1-based offset of 'u' in "userz"
        val position = sql.indexOf("userz") + 1
        assertEquals(1 to 6, SqlErrors.offsetToLineColumn(sql, position))
    }

    @Test
    fun `clamps an out of range offset`() {
        val (line, column) = SqlErrors.offsetToLineColumn("SELECT 1", 9999)
        assertEquals(0, line)
        assertEquals(8, column)
    }
}
