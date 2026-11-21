package me.chius.upcpocket

import me.chius.upcpocket.feature.grades.CourseGradeRecord
import me.chius.upcpocket.feature.grades.GpaCalculator
import me.chius.upcpocket.feature.grades.GradeFilterPreset
import me.chius.upcpocket.feature.grades.GradesParseOutcome
import me.chius.upcpocket.feature.grades.GradesProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GradesProtocolAndCalculatorTest {
    private val sampleHtml = """
        <html><body>
        <div>学号：2300000001</div>
        <table id="dataList">
          <tr>
            <th>序号</th><th>开课学期</th><th>课程编号</th><th>课程名称</th><th>成绩</th>
            <th>学分</th><th>总学时</th><th>绩点</th><th>补重学期</th><th>考核方式</th>
            <th>考试性质</th><th>课程属性</th><th>课程性质</th>
          </tr>
          <tr>
            <td>1</td><td>2025-2026-1</td><td>CS101</td><td>数据结构</td><td>92</td>
            <td>4.0</td><td>64</td><td>4.2</td><td></td><td>考试</td>
            <td>正常考试</td><td>必修</td><td>学科基础</td>
          </tr>
          <tr>
            <td>2</td><td>2025-2026-1</td><td>PE101</td><td>体育</td><td>良好</td>
            <td>1.0</td><td>32</td><td>3.5</td><td></td><td>考查</td>
            <td>正常考试</td><td>任选</td><td>通识选修</td>
          </tr>
        </table>
        </body></html>
    """.trimIndent()

    @Test
    fun `parses valid jwxt grades table and detects login or mismatched identity`() {
        val outcome = GradesProtocol.parse(sampleHtml, expectedStudentId = "2300000001")
        assertTrue(outcome is GradesParseOutcome.Ok)
        val ok = outcome as GradesParseOutcome.Ok
        assertEquals(2, ok.records.size)
        assertEquals(92.0, ok.records[0].numericScore)
        assertEquals(85.0, ok.records[1].numericScore)

        val mismatch = GradesProtocol.parse(sampleHtml, expectedStudentId = "2399999999")
        assertTrue(mismatch is GradesParseOutcome.IdentityMismatch)

        val loginHtml = """<form id="loginForm" action="https://cas.upc.edu.cn/cas/login"><input type="password" name="password"/><input type="hidden" name="execution" value="e1s1"/></form>"""
        assertTrue(GradesProtocol.parse(loginHtml, "2300000001") is GradesParseOutcome.AuthRequired)
    }

    @Test
    fun `computes upc gpa filters presets and simulates score changes`() {
        assertEquals(4.2, GpaCalculator.scoreToUpcGpa(92.0), 0.001)
        assertEquals(0.0, GpaCalculator.scoreToUpcGpa(59.0), 0.001)

        val records = listOf(
            CourseGradeRecord(
                id = "c1", term = "2025-2026-1", courseCode = "CS101", courseName = "数据结构",
                scoreText = "90", numericScore = 90.0, credit = 4.0, gpaPoint = 4.0, courseAttribute = "必修",
            ),
            CourseGradeRecord(
                id = "c2", term = "2025-2026-1", courseCode = "GE101", courseName = "选修课",
                scoreText = "80", numericScore = 80.0, credit = 2.0, gpaPoint = 3.0, courseAttribute = "任选",
            ),
        )

        val filtered = GpaCalculator.filterByPreset(records, GradeFilterPreset.REQUIRED_AND_RESTRICTED, null)
        assertEquals(1, filtered.size)
        val reqSummary = GpaCalculator.calculate(filtered)
        assertEquals(90.0, reqSummary.weightedAverage!!, 0.001)
        assertEquals(4.0, reqSummary.officialGpa!!, 0.001)

        val simSummary = GpaCalculator.calculate(
            filteredRecords = records,
            excludedIds = setOf("c2"),
            simulatedScores = mapOf("c1" to 95.0),
        )
        assertEquals(95.0, simSummary.weightedAverage!!, 0.001)
        assertEquals(4.5, simSummary.officialGpa!!, 0.001)
        assertTrue((simSummary.gpaDelta ?: 0.0) > 0.0)
    }
}
