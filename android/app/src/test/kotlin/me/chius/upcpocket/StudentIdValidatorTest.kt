package me.chius.upcpocket

import me.chius.upcpocket.core.model.StudentIdValidator
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StudentIdValidatorTest {
    @Test
    fun `accepts valid undergraduate and graduate student id formats`() {
        val validIds = listOf(
            "2024010101",
            "12345678",
            "1234567890123456",
            "S24010001",
            "Z24010001",
            "M24010001",
            "TS240100001",
            "BY2401000001",
        )
        validIds.forEach { candidate ->
            assertTrue("Expected valid student ID: $candidate", StudentIdValidator.isValid(candidate))
        }
    }

    @Test
    fun `rejects malformed or wrong-case or non-ascii student ids`() {
        val invalidIds = listOf(
            "",
            "1234567",
            "12345678901234567",
            "s24010001",
            "ts240100001",
            "by2401000001",
            "S2401000",
            "TS24010000",
            "BY240100000",
            "TS240100001X",
            " 2024010101 ",
            "\u0662\u0660\u0662\u0664\u0660\u0661\u0660\u0661",
        )
        invalidIds.forEach { candidate ->
            assertFalse("Expected invalid student ID: $candidate", StudentIdValidator.isValid(candidate))
        }
    }
}
