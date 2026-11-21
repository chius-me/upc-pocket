package me.chius.upcpocket.feature.focus

import android.content.Context
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Wall-clock persisted Pomodoro controller storing snapshots and records in `noBackupFilesDir`.
 */
class FocusTimerController internal constructor(
    private val directory: File?,
    private val clock: () -> Long = System::currentTimeMillis,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
    private val idGenerator: () -> String = { UUID.randomUUID().toString() },
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    constructor(context: Context) : this(
        directory = File(context.applicationContext.noBackupFilesDir, FOCUS_DIR),
    )

    private val mutex = Mutex()
    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = false
    }

    private var persisted = FocusPersistenceState()
    private var tickJob: Job? = null

    private val _state = MutableStateFlow(FocusTimerUiState())
    val state: StateFlow<FocusTimerUiState> = _state.asStateFlow()

    suspend fun restore() = withContext(ioDispatcher) {
        mutex.withLock {
            withContext(NonCancellable) {
                persisted = readStateFromDisk()
                val now = clock().coerceAtLeast(1L)
                reconcileFinishedIfNeededLocked(now)
                publishUiLocked(now)
                if (persisted.snapshot.running) {
                    launchTickerLocked()
                }
            }
        }
    }

    suspend fun selectMode(mode: FocusMode) = withContext(ioDispatcher) {
        mutex.withLock {
            withContext(NonCancellable) {
                cancelTickerLocked()
                val now = clock().coerceAtLeast(1L)
                val updatedSnapshot = FocusTimerSnapshot(
                    mode = mode,
                    running = false,
                    startedAt = 0L,
                    plannedSeconds = mode.durationSeconds,
                    pausedTotalMs = 0L,
                    pausedAt = 0L,
                    focusRound = persisted.snapshot.focusRound,
                )
                persisted = persisted.copy(snapshot = updatedSnapshot)
                writeStateAtomically(persisted)
                publishUiLocked(now)
            }
        }
    }

    suspend fun toggleStartPause() = withContext(ioDispatcher) {
        mutex.withLock {
            withContext(NonCancellable) {
                val now = clock().coerceAtLeast(1L)
                val snap = persisted.snapshot
                when {
                    snap.idle -> {
                        val next = FocusTimerSnapshot(
                            mode = snap.mode,
                            running = true,
                            startedAt = now,
                            plannedSeconds = snap.mode.durationSeconds,
                            pausedTotalMs = 0L,
                            pausedAt = 0L,
                            focusRound = snap.focusRound,
                        )
                        persisted = persisted.copy(snapshot = next)
                        writeStateAtomically(persisted)
                        publishUiLocked(now)
                        launchTickerLocked()
                    }
                    snap.running -> {
                        cancelTickerLocked()
                        val next = snap.copy(
                            running = false,
                            pausedAt = now,
                        )
                        persisted = persisted.copy(snapshot = next)
                        writeStateAtomically(persisted)
                        publishUiLocked(now)
                    }
                    snap.paused -> {
                        val addedPause = (now - snap.pausedAt).coerceAtLeast(0L)
                        val next = snap.copy(
                            running = true,
                            pausedTotalMs = snap.pausedTotalMs + addedPause,
                            pausedAt = 0L,
                        )
                        persisted = persisted.copy(snapshot = next)
                        writeStateAtomically(persisted)
                        publishUiLocked(now)
                        launchTickerLocked()
                    }
                }
            }
        }
    }

    suspend fun reset() = withContext(ioDispatcher) {
        mutex.withLock {
            withContext(NonCancellable) {
                cancelTickerLocked()
                val now = clock().coerceAtLeast(1L)
                val snap = persisted.snapshot
                persisted = persisted.copy(
                    snapshot = FocusTimerSnapshot(
                        mode = snap.mode,
                        running = false,
                        startedAt = 0L,
                        plannedSeconds = snap.mode.durationSeconds,
                        pausedTotalMs = 0L,
                        pausedAt = 0L,
                        focusRound = snap.focusRound,
                    ),
                )
                writeStateAtomically(persisted)
                publishUiLocked(now)
            }
        }
    }

    suspend fun finishEarly() = withContext(ioDispatcher) {
        mutex.withLock {
            withContext(NonCancellable) {
                cancelTickerLocked()
                val now = clock().coerceAtLeast(1L)
                val snap = persisted.snapshot
                var nextRecords = persisted.records
                var nextRound = snap.focusRound

                if (!snap.idle) {
                    val elapsed = PomodoroClock.elapsedSeconds(snap, now)
                        .coerceAtMost(snap.plannedSeconds)
                    val completed = PomodoroClock.isFinished(snap, now)
                    if (snap.mode.isFocus && elapsed > 0) {
                        val rec = FocusRecord(
                            id = idGenerator(),
                            mode = snap.mode,
                            startedAt = snap.startedAt,
                            endedAt = now,
                            actualSeconds = elapsed,
                            completed = completed,
                        )
                        nextRecords = (listOf(rec) + nextRecords).take(MAX_RECORDS)
                        if (completed) {
                            nextRound += 1
                        }
                    }
                }

                persisted = persisted.copy(
                    snapshot = FocusTimerSnapshot(
                        mode = snap.mode,
                        running = false,
                        startedAt = 0L,
                        plannedSeconds = snap.mode.durationSeconds,
                        pausedTotalMs = 0L,
                        pausedAt = 0L,
                        focusRound = nextRound,
                    ),
                    records = nextRecords,
                )
                writeStateAtomically(persisted)
                publishUiLocked(now)
            }
        }
    }

    suspend fun clearHistory() = withContext(ioDispatcher) {
        mutex.withLock {
            withContext(NonCancellable) {
                val now = clock().coerceAtLeast(1L)
                persisted = persisted.copy(records = emptyList())
                writeStateAtomically(persisted)
                publishUiLocked(now)
            }
        }
    }

    private fun reconcileFinishedIfNeededLocked(now: Long) {
        val snap = persisted.snapshot
        if (snap.running && PomodoroClock.isFinished(snap, now)) {
            var nextRecords = persisted.records
            var nextRound = snap.focusRound
            if (snap.mode.isFocus && snap.plannedSeconds > 0) {
                val rec = FocusRecord(
                    id = idGenerator(),
                    mode = snap.mode,
                    startedAt = snap.startedAt,
                    endedAt = now,
                    actualSeconds = snap.plannedSeconds,
                    completed = true,
                )
                nextRecords = (listOf(rec) + nextRecords).take(MAX_RECORDS)
                nextRound += 1
            }
            persisted = persisted.copy(
                snapshot = FocusTimerSnapshot(
                    mode = snap.mode,
                    running = false,
                    startedAt = 0L,
                    plannedSeconds = snap.mode.durationSeconds,
                    pausedTotalMs = 0L,
                    pausedAt = 0L,
                    focusRound = nextRound,
                ),
                records = nextRecords,
            )
            writeStateAtomically(persisted)
        }
    }

    private fun launchTickerLocked() {
        tickJob?.cancel()
        tickJob = scope.launch {
            while (isActive) {
                delay(1_000L)
                val shouldContinue = mutex.withLock {
                    if (!persisted.snapshot.running) {
                        false
                    } else {
                        val now = clock().coerceAtLeast(1L)
                        reconcileFinishedIfNeededLocked(now)
                        publishUiLocked(now)
                        persisted.snapshot.running
                    }
                }
                if (!shouldContinue) break
            }
        }
    }

    private fun cancelTickerLocked() {
        tickJob?.cancel()
        tickJob = null
    }

    private fun publishUiLocked(now: Long) {
        val snap = persisted.snapshot
        val remaining = PomodoroClock.remainingSeconds(snap, now)
        val elapsed = PomodoroClock.elapsedSeconds(snap, now)
        val summary = FocusStats.summarize(persisted.records, now, zone())
        _state.value = FocusTimerUiState(
            mode = snap.mode,
            remainingSeconds = remaining,
            elapsedSeconds = elapsed,
            isRunning = snap.running,
            isPaused = snap.paused,
            isIdle = snap.idle,
            progress = PomodoroClock.progress(snap, now),
            formattedTime = PomodoroClock.format(remaining),
            focusRound = snap.focusRound,
            summary = summary,
            recentRecords = persisted.records.take(15),
        )
    }

    private fun readStateFromDisk(): FocusPersistenceState {
        val dir = directory ?: return FocusPersistenceState()
        val file = File(dir, FOCUS_FILE)
        if (!file.isFile || file.length() > MAX_FILE_BYTES) return FocusPersistenceState()
        return runCatching {
            val bytes = file.readBytes()
            if (bytes.size > MAX_FILE_BYTES) return FocusPersistenceState()
            json.decodeFromString<FocusPersistenceState>(bytes.toString(StandardCharsets.UTF_8))
        }.getOrDefault(FocusPersistenceState())
    }

    private fun writeStateAtomically(state: FocusPersistenceState) {
        val dir = directory ?: return
        runCatching {
            dir.mkdirs()
            val target = File(dir, FOCUS_FILE)
            val tmp = File(dir, "$FOCUS_FILE.tmp")
            val bytes = json.encodeToString(state).toByteArray(StandardCharsets.UTF_8)
            tmp.outputStream().use { fos ->
                fos.write(bytes)
                fos.fd.sync()
            }
            if (target.exists() && !target.delete()) {
                throw IOException("Failed to replace $FOCUS_FILE")
            }
            if (!tmp.renameTo(target)) {
                throw IOException("Failed to commit $FOCUS_FILE")
            }
        }
    }

    private companion object {
        const val FOCUS_DIR = "focus"
        const val FOCUS_FILE = "focus-v1.json"
        const val MAX_RECORDS = 200
        const val MAX_FILE_BYTES = 2 * 1024 * 1024
    }
}
