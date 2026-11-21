package me.chius.upcpocket

import java.time.LocalDate
import me.chius.upcpocket.feature.classroom.ClassroomPeriodGroup
import me.chius.upcpocket.feature.classroom.FreeClassroomCampus
import me.chius.upcpocket.feature.classroom.FreeClassroomProtocol
import me.chius.upcpocket.feature.classroom.FreeClassroomQuery
import me.chius.upcpocket.feature.classroom.ZhuoyueWingFilter
import me.chius.upcpocket.feature.classroom.formatPeriods
import me.chius.upcpocket.feature.classroom.toggleGroup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FreeClassroomProtocolTest {
    @Test
    fun `parses js intersection and handles php empty array`() {
        val query = FreeClassroomQuery(
            campus = FreeClassroomCampus.GUZHENKOU,
            date = LocalDate.of(2026, 11, 21),
            building = "全部",
            periods = setOf(1, 2, 3),
        )

        val body = """
            {
              "e": 0,
              "d": {
                "listLh": [{"id": "全部"}, {"id": "卓越教学楼"}],
                "all": {
                  "卓越教学楼": [
                    {"code": "Z_A101", "name": "卓越A101", "xqm": "古镇口校区", "jxlm": "卓越教学楼", "num": "60"},
                    {"code": "Z_B202", "name": "卓越 B 202", "xqm": "古镇口校区", "jxlm": "卓越教学楼", "num": "40"}
                  ]
                },
                "js": {
                  "卓越教学楼": [
                    {"code": "Z_A101", "name": "卓越A101", "xqm": "古镇口校区", "jxlm": "卓越教学楼", "num": "60"}
                  ]
                }
              }
            }
        """.trimIndent()

        val result = FreeClassroomProtocol.parse(body, query, 999L)
        assertEquals(2, result.rooms.size)
        assertTrue(result.rooms.first { it.code == "Z_A101" }.available)
        assertFalse(result.rooms.first { it.code == "Z_B202" }.available)

        val wingA = result.filterZhuoyueWing(ZhuoyueWingFilter.A)
        assertEquals(listOf("Z_A101"), wingA.rooms.map { it.code })

        val wingB = result.filterZhuoyueWing(ZhuoyueWingFilter.B)
        assertEquals(listOf("Z_B202"), wingB.rooms.map { it.code })

        // Empty PHP array for `js` must mark zero classrooms as available.
        val emptyJsBody = """
            {
              "e": 0,
              "d": {
                "listLh": [{"id": "全部"}],
                "all": {
                  "卓越教学楼": [
                    {"code": "Z_A101", "name": "卓越A101", "xqm": "古镇口校区", "jxlm": "卓越教学楼", "num": "60"}
                  ]
                },
                "js": []
              }
            }
        """.trimIndent()
        val noneFree = FreeClassroomProtocol.parse(emptyJsBody, query, 999L)
        assertTrue(noneFree.availableRooms.isEmpty())
    }

    @Test
    fun `formats periods and toggles period groups consistently`() {
        assertEquals("1–3、5、10–13", formatPeriods(setOf(1, 2, 3, 5, 10, 11, 12, 13)))
        val afterToggle = toggleGroup(setOf(1, 2), ClassroomPeriodGroup.MORNING)
        assertEquals(setOf(1, 2, 3, 4, 5), afterToggle)
        val afterSecondToggle = toggleGroup(afterToggle, ClassroomPeriodGroup.MORNING)
        assertTrue(afterSecondToggle.isEmpty())
    }
}
