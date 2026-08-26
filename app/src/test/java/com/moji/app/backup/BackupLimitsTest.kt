package com.moji.app.backup

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class BackupLimitsTest {
    @Test fun boundedCopyAcceptsContentAtLimit() {
        val input = ByteArray(8_192) { (it % 251).toByte() }
        val output = ByteArrayOutputStream()
        assertEquals(input.size.toLong(), copyBounded(ByteArrayInputStream(input), output, input.size.toLong()))
        assertArrayEquals(input, output.toByteArray())
    }

    @Test fun boundedCopyRejectsContentAboveLimit() {
        assertThrows(IllegalArgumentException::class.java) {
            copyBounded(ByteArrayInputStream(ByteArray(8_193)), ByteArrayOutputStream(), 8_192)
        }
    }

    @Test fun archiveEntryValidationAllowsOnlyTwoUniqueFiles() {
        val seen = mutableSetOf<String>()
        validateBackupEntry("manifest.json", false, seen)
        validateBackupEntry("data.json", false, seen)
        assertThrows(IllegalArgumentException::class.java) { validateBackupEntry("extra.bin", false, seen) }
        assertThrows(IllegalArgumentException::class.java) { validateBackupEntry("data.json", false, seen) }
        assertThrows(IllegalArgumentException::class.java) { validateBackupEntry("manifest.json", true, mutableSetOf()) }
    }

    @Test fun csvCellsNeutralizeSpreadsheetFormulas() {
        assertEquals("\"'=SUM(A1:A2)\"", csvCell("=SUM(A1:A2)"))
        assertEquals("\"'+cmd\"", csvCell("+cmd"))
        assertEquals("\"ordinary merchant\"", csvCell("ordinary merchant"))
        assertEquals("\"a\"\"b\"", csvCell("a\"b"))
    }
}
