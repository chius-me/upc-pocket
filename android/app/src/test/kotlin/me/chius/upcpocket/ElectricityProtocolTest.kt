package me.chius.upcpocket

import java.io.File
import java.math.BigDecimal
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import me.chius.upcpocket.feature.power.ElectricityProtocol
import me.chius.upcpocket.feature.power.PowerRepository
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ElectricityProtocolTest {
    private lateinit var tempDir: File

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("upc-power-test").toFile()
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun `parses webforms fields and prevents fuzzy substring room mismatch`() {
        val formHtml = """
            <html><body><form id="form1">
            <input type="hidden" name="__VIEWSTATE" value="VS123" />
            <input type="hidden" name="__VIEWSTATEGENERATOR" value="GEN456" />
            <input type="hidden" name="__EVENTVALIDATION" value="EV789" />
            <select name="lou">
                <option value="1号楼">1号楼</option>
                <option value="2号楼">2号楼</option>
            </select>
            </form></body></html>
        """.trimIndent()

        val form = ElectricityProtocol.parseForm(formHtml)
        assertEquals("VS123", form.viewState)
        assertEquals(listOf("1号楼", "2号楼"), form.buildings)

        // Fuzzy hit: user requested 101, school returned 1101 in candidates + plotted grid.
        val ambiguousHtml = """
            <table id="yongdianGrid">
              <tr><td>当前剩余电量:</td><td>17.34度</td><td>SYS01</td><td>1101</td></tr>
            </table>
            <table id="yonghuGridView">
              <tr><td>系统编号</td><td>楼号</td><td>房间号</td></tr>
              <tr><td>SYS01</td><td>1号楼</td><td>1101</td></tr>
            </table>
        """.trimIndent()

        val ambiguousOutcome = ElectricityProtocol.parseResult(ambiguousHtml, "101")
        assertTrue(ambiguousOutcome is ElectricityProtocol.Outcome.Ambiguous)
        val candidates = (ambiguousOutcome as ElectricityProtocol.Outcome.Ambiguous).candidates
        assertEquals(1, candidates.size)
        assertEquals("1101", candidates.single().room)

        // Exact match returns Success.
        val exactOutcome = ElectricityProtocol.parseResult(ambiguousHtml, "1101")
        assertTrue(exactOutcome is ElectricityProtocol.Outcome.Success)
        val reading = (exactOutcome as ElectricityProtocol.Outcome.Success).reading
        assertEquals(BigDecimal("17.34"), reading.kwh)
        assertEquals("1101", reading.room)
    }

    @Test
    fun `repository loads buildings queries exact reading and clears saved reading`() = runTest {
        val formHtml = """
            <form id="form1">
            <input type="hidden" name="__VIEWSTATE" value="VS1" />
            <input type="hidden" name="__VIEWSTATEGENERATOR" value="GEN1" />
            <input type="hidden" name="__EVENTVALIDATION" value="EV1" />
            <select name="lou"><option value="1号楼">1号楼</option></select>
            </form>
        """.trimIndent()
        val resultHtml = """
            <table id="yongdianGrid">
              <tr><td>当前剩余电量:</td><td>42.50度</td><td>SYS99</td><td>205</td></tr>
            </table>
        """.trimIndent()

        val repo = PowerRepository(
            directory = tempDir,
            getPage = { formHtml },
            postQuery = { resultHtml },
            clock = { 123_456L },
        )

        val buildings = repo.loadBuildings().getOrThrow()
        assertEquals(listOf("1号楼"), buildings)

        val outcome = repo.query("1号楼", "205").getOrThrow()
        assertTrue(outcome is ElectricityProtocol.Outcome.Success)
        assertEquals("42.50", repo.uiState.value.lastReading?.kwhText)

        repo.clearSaved()
        assertNull(repo.uiState.value.lastReading)
        assertEquals("", repo.uiState.value.room)
    }
}
