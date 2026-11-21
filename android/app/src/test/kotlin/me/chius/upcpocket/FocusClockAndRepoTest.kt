package me.chius.upcpocket

import java.io.File
import java.nio.file.Files
import java.time.LocalDateTime
import java.time.ZoneId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import me.chius.upcpocket.feature.focus.FocusMode
import me.chius.upcpocket.feature.focus.FocusRecord
import me.chius.upcpocket.feature.focus.FocusStats
import me.chius.upcpocket.feature.focus.FocusTimerController
import me.chius.upcpocket.feature.focus.FocusTimerSnapshot
import me.chius.upcpocket.feature.focus.PomodoroClock
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class FocusClockAndRepoTest {
    private lateinit var tempDir: File

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("upc-focus-test").toFile()
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun `pomodoro clock computes wall-clock elapsed and remaining accurately across pauses`() {
        val snap = FocusTimerSnapshot(
            mode = FocusMode.FOCUS_25,
            running = true,
            startedAt = 10_000L,
            plannedSeconds = 1_500,
            pausedTotalMs = 5_000L,
            pausedAt = 0L,
        )
        // Elapsed = (75_000 - 10_000 - 5_000) / 1000 = 60s
        assertEquals(60, PomodoroClock.elapsedSeconds(snap, nowMillis = 75_000L))
        assertEquals(1_440, PomodoroClock.remainingSeconds(snap, nowMillis = 75_000L))
        assertFalse(PomodoroClock.isFinished(snap, nowMillis = 75_000L))

        val pausedSnap = snap.copy(running = false, pausedAt = 75_000L)
        // Advancing wall clock while paused does not increment elapsed seconds.
        assertEquals(60, PomodoroClock.elapsedSeconds(pausedSnap, nowMillis = 200_000L))
        assertEquals("24:00", PomodoroClock.format(PomodoroClock.remainingSeconds(pausedSnap, 200_000L)))
    }

    @Test
    fun `focus stats use 04_00 cutoff ignore breaks and count sessions only on completion`() {
        val zone = ZoneId.of("Asia/Shanghai")
        fun millis(year: Int, month: Int, day: Int, hour: Int, min: Int): Long =
            LocalDateTime.of(year, month, day, hour, min).atZone(zone).toInstant().toEpochMilli()

        val now = millis(2026, 11, 21, 14, 0) // Study day: 2026-11-21
        val lateNightSameStudyDay = millis(2026, 11, 22, 2, 30) // Still belongs to 2026-11-21!
        val beforeCutoffPrevStudyDay = millis(2026, 11, 21, 3, 50) // Belongs to 2026-11-20!

        val records = listOf(
            FocusRecord(
                id = "1",
                mode = FocusMode.FOCUS_25,
                startedAt = now,
                endedAt = now + 1_500_000L,
                actualSeconds = 1_500,
                completed = true,
            ),
            FocusRecord(
                id = "2",
                mode = FocusMode.DEEP_45,
                startedAt = now + 2_000_000L,
                endedAt = now + 2_300_000L,
                actualSeconds = 300,
                completed = false, // abandoned early: adds seconds, NOT completed session
            ),
            FocusRecord(
                id = "3",
                mode = FocusMode.SHORT_BREAK_5,
                startedAt = now + 2_500_000L,
                endedAt = now + 2_800_000L,
                actualSeconds = 300,
                completed = true, // break: ignored completely
            ),
            FocusRecord(
                id = "4",
                mode = FocusMode.FOCUS_25,
                startedAt = beforeCutoffPrevStudyDay,
                endedAt = beforeCutoffPrevStudyDay + 1_500_000L,
                actualSeconds = 1_500,
                completed = true,
            ),
        )

        assertEquals("2026-11-21", FocusStats.dayKey(lateNightSameStudyDay, zone).toString())
        assertEquals("2026-11-20", FocusStats.dayKey(beforeCutoffPrevStudyDay, zone).toString())

        val summary = FocusStats.summarize(records, nowMillis = now, zone = zone)
        assertEquals(1_800, summary.today.seconds)
        assertEquals(1, summary.today.sessions)
        assertEquals(3_300, summary.week.seconds)
        assertEquals(2, summary.week.sessions)
    }

    @Test
    fun `controller persists running state recovers completed timer after restart and supports finishEarly`() = runTest {
        val zone = ZoneId.of("UTC")
        var now = 100_000L

        val firstScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val first = FocusTimerController(
            directory = tempDir,
            clock = { now },
            zone = { zone },
            idGenerator = { "rec-1" },
            scope = firstScope,
        )
        first.restore()
        first.toggleStartPause() // Start 25m focus at t=100s
        assertTrue(first.state.value.isRunning)
        // Simulate process death cleanly before advancing clock.
        firstScope.cancel()

        // Reopen 30 minutes later (1_800_000ms > 1_500_000ms).
        now += 1_800_000L
        val reopenedScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val reopened = FocusTimerController(
                directory = tempDir,
                clock = { now },
                zone = { zone },
                idGenerator = { "rec-auto" },
                scope = reopenedScope,
            )
            reopened.restore()
            assertFalse(reopened.state.value.isRunning)
            assertEquals(1_500, reopened.state.value.summary.today.seconds)
            assertEquals(1, reopened.state.value.summary.today.sessions)

            // Start another timer, advance 120s, and finishEarly.
            reopened.toggleStartPause()
            now += 120_000L
            reopened.finishEarly()
            assertEquals(1_620, reopened.state.value.summary.today.seconds)
            assertEquals(1, reopened.state.value.summary.today.sessions)
            assertEquals(2, reopened.state.value.recentRecords.size)
        } finally {
            reopenedScope.cancel()
        }
    }
}
