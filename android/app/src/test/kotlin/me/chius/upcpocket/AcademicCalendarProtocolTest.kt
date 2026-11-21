package me.chius.upcpocket

import me.chius.upcpocket.feature.calendar.CalendarProtocol
import me.chius.upcpocket.feature.timetable.CourseMeeting
import me.chius.upcpocket.feature.timetable.TimetableSchedule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AcademicCalendarProtocolTest {
    @Test
    fun `parses jwc calendar list, images and enforces jwc host allowlist`() {
        val listHtml = """
            <ul>
              <li><a href="/2026/0508/c603a490075/page.htm" title="2026-2027学年校历">2026-2027学年校历</a></li>
              <li><a href="https://evil.example.com/2026/0101/c603a1/page.htm">钓鱼链接</a></li>
            </ul>
        """.trimIndent()
        val items = CalendarProtocol.parseList(listHtml)
        assertEquals(1, items.size)
        assertEquals("https://jwc.upc.edu.cn/2026/0508/c603a490075/page.htm", items.single().pageUrl)
        assertEquals("2026-05-08", items.single().publishDate)

        val pageHtml = """
            <div class="wp_articlecontent">
              <img src="/_upload/article/images/a/sem1.jpg" />
              <img src="http://jwc.upc.edu.cn/_upload/article/images/a/sem2.png" />
            </div>
        """.trimIndent()
        val images = CalendarProtocol.parseArticleImages(pageHtml, items.single().pageUrl)
        assertEquals(
            listOf(
                "https://jwc.upc.edu.cn/_upload/article/images/a/sem1.jpg",
                "https://jwc.upc.edu.cn/_upload/article/images/a/sem2.png",
            ),
            images,
        )
        assertNull(CalendarProtocol.resolveAllowedJwcUrl(CalendarProtocol.LIST_URL, "https://other.upc.edu.cn/x.jpg"))
    }

    @Test
    fun `resolves current or next period and today class status`() {
        assertEquals(1, TimetableSchedule.currentOrNextPeriod(7 * 60 + 30))
        assertEquals(6, TimetableSchedule.currentOrNextPeriod(14 * 60 + 15))
        assertEquals(12, TimetableSchedule.currentOrNextPeriod(23 * 60))

        val meeting = CourseMeeting(
            courseName = "算法设计",
            weekday = 1,
            startPeriod = 1,
            endPeriod = 2,
            periods = listOf(1, 2),
            sourcePeriods = listOf(1, 2),
            weeks = listOf(1, 2),
            weekLabel = "1-2周",
            dateIso = "2026-11-21",
        )
        val ongoing = TimetableSchedule.resolveTodayStatus(listOf(meeting), 8 * 60 + 30)
        assertTrue(ongoing is TimetableSchedule.TodayScheduleStatus.Ongoing)
        val upcoming = TimetableSchedule.resolveTodayStatus(listOf(meeting), 7 * 60 + 40)
        assertTrue(upcoming is TimetableSchedule.TodayScheduleStatus.Upcoming)
        val finished = TimetableSchedule.resolveTodayStatus(listOf(meeting), 18 * 60)
        assertTrue(finished is TimetableSchedule.TodayScheduleStatus.FinishedToday)
    }
}
