package me.chius.upcpocket.feature.power

import android.content.Context
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class SavedPowerReading(
    val building: String,
    val room: String,
    val systemNo: String,
    val kwhText: String,
    val fetchedAtEpochMillis: Long,
)

@Serializable
internal data class PersistedPowerState(
    val draftBuilding: String = "",
    val draftRoom: String = "",
    val buildings: List<String> = emptyList(),
    val lastReading: SavedPowerReading? = null,
)

data class PowerUiState(
    val building: String = "",
    val room: String = "",
    val buildings: List<String> = emptyList(),
    val lastReading: SavedPowerReading? = null,
    val candidates: List<ElectricityProtocol.Candidate> = emptyList(),
    val isLoadingBuildings: Boolean = false,
    val isQuerying: Boolean = false,
    val errorMessage: String? = null,
)

class PowerRepository internal constructor(
    private val directory: File,
    private val getPage: suspend () -> String = ::defaultFetchPowerFormPage,
    private val postQuery: suspend (String) -> String = ::defaultPostPowerQuery,
    private val clock: () -> Long = System::currentTimeMillis,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    constructor(context: Context) : this(
        directory = File(context.applicationContext.noBackupFilesDir, DIR_NAME),
    )

    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }
    private val fileMutex = Mutex()
    private val queryMutex = Mutex()

    private val _uiState = MutableStateFlow(PowerUiState())
    val uiState: StateFlow<PowerUiState> = _uiState.asStateFlow()

    suspend fun restore() = withContext(ioDispatcher) {
        val persisted = fileMutex.withLock { readPersistedOrNull() } ?: return@withContext
        _uiState.update {
            it.copy(
                building = persisted.draftBuilding,
                room = persisted.draftRoom,
                buildings = persisted.buildings,
                lastReading = persisted.lastReading,
            )
        }
    }

    suspend fun updateDraft(building: String, room: String) = withContext(ioDispatcher) {
        _uiState.update { it.copy(building = building, room = room) }
        persistCurrentState()
    }

    suspend fun loadBuildings(): Result<List<String>> = withContext(ioDispatcher) {
        queryMutex.withLock {
            _uiState.update { it.copy(isLoadingBuildings = true, errorMessage = null) }
            try {
                val form = ElectricityProtocol.parseForm(getPage())
                _uiState.update { state ->
                    val nextBuilding = if (state.building in form.buildings) {
                        state.building
                    } else {
                        state.building.ifBlank { form.buildings.firstOrNull().orEmpty() }
                    }
                    state.copy(
                        buildings = form.buildings,
                        building = nextBuilding,
                        isLoadingBuildings = false,
                        errorMessage = null,
                    )
                }
                persistCurrentState()
                Result.success(form.buildings)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                val msg = (error as? ElectricityProtocol.PageException)?.message
                    ?: (error as? IOException)?.message
                    ?: "获取楼号列表失败，请检查校园网络"
                _uiState.update { it.copy(isLoadingBuildings = false, errorMessage = msg) }
                Result.failure(error)
            }
        }
    }

    suspend fun query(buildingInput: String, roomInput: String): Result<ElectricityProtocol.Outcome> =
        withContext(ioDispatcher) {
            val building = buildingInput.trim()
            val room = roomInput.trim()
            if (building.isEmpty() || room.isEmpty()) {
                val msg = "请先选择楼号并输入房间号"
                _uiState.update { it.copy(errorMessage = msg) }
                return@withContext Result.failure(IllegalArgumentException(msg))
            }

            queryMutex.withLock {
                _uiState.update {
                    it.copy(
                        building = building,
                        room = room,
                        isQuerying = true,
                        errorMessage = null,
                        candidates = emptyList(),
                    )
                }
                persistCurrentState()

                try {
                    val form = ElectricityProtocol.parseForm(getPage())
                    val targetBuilding = form.buildings.firstOrNull { it == building }
                        ?: throw IOException("学校页面没有找到楼号“$building”，请刷新楼号列表后重试")
                    val html = postQuery(ElectricityProtocol.formBody(form, targetBuilding, room))
                    val outcome = ElectricityProtocol.parseResult(html, room)

                    when (outcome) {
                        is ElectricityProtocol.Outcome.Success -> {
                            val resolvedRoom = outcome.reading.room.ifBlank { room }
                            val reading = SavedPowerReading(
                                building = targetBuilding,
                                room = resolvedRoom,
                                systemNo = outcome.reading.systemNo,
                                kwhText = outcome.reading.kwh.toPlainString(),
                                fetchedAtEpochMillis = clock().coerceAtLeast(1L),
                            )
                            _uiState.update {
                                it.copy(
                                    buildings = form.buildings,
                                    building = targetBuilding,
                                    room = resolvedRoom,
                                    lastReading = reading,
                                    candidates = emptyList(),
                                    isQuerying = false,
                                    errorMessage = null,
                                )
                            }
                            persistCurrentState()
                        }

                        is ElectricityProtocol.Outcome.Ambiguous -> {
                            _uiState.update {
                                it.copy(
                                    buildings = form.buildings,
                                    candidates = outcome.candidates,
                                    isQuerying = false,
                                    errorMessage = null,
                                )
                            }
                            persistCurrentState()
                        }
                    }
                    Result.success(outcome)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    val msg = (error as? ElectricityProtocol.PageException)?.message
                        ?: (error as? IOException)?.message
                        ?: "电量查询失败，请检查网络后重试"
                    _uiState.update { it.copy(isQuerying = false, errorMessage = msg) }
                    Result.failure(error)
                }
            }
        }

    suspend fun clearSaved() = withContext(ioDispatcher) {
        _uiState.update {
            PowerUiState(buildings = it.buildings)
        }
        fileMutex.withLock {
            File(directory, STATE_FILE).delete()
            File(directory, "$STATE_FILE.tmp").delete()
        }
    }

    private suspend fun persistCurrentState() {
        val current = _uiState.value
        val persisted = PersistedPowerState(
            draftBuilding = current.building,
            draftRoom = current.room,
            buildings = current.buildings,
            lastReading = current.lastReading,
        )
        fileMutex.withLock {
            runCatching { writePersistedAtomically(persisted) }
        }
    }

    private fun readPersistedOrNull(): PersistedPowerState? {
        val file = File(directory, STATE_FILE)
        if (!file.exists()) return null
        return runCatching {
            val bytes = file.readBytes()
            if (bytes.size > MAX_CACHE_BYTES) return null
            json.decodeFromString<PersistedPowerState>(bytes.toString(Charsets.UTF_8))
        }.getOrNull()
    }

    private fun writePersistedAtomically(state: PersistedPowerState) {
        directory.mkdirs()
        val target = File(directory, STATE_FILE)
        val tmp = File(directory, "$STATE_FILE.tmp")
        val bytes = json.encodeToString(state).toByteArray(Charsets.UTF_8)
        tmp.outputStream().use { fos ->
            fos.write(bytes)
            fos.fd.sync()
        }
        if (target.exists() && !target.delete()) {
            throw IOException("Failed to replace power state")
        }
        if (!tmp.renameTo(target)) {
            throw IOException("Failed to commit power state")
        }
    }

    private companion object {
        const val DIR_NAME = "power"
        const val STATE_FILE = "tangdaowan-v1.json"
        const val MAX_CACHE_BYTES = 64 * 1024
    }
}

private suspend fun defaultFetchPowerFormPage(): String = withContext(Dispatchers.IO) {
    val url = "${ElectricityProtocol.HOST}${ElectricityProtocol.PAGE_PATH}?code=${ElectricityProtocol.NEUTRAL_CODE}"
    val connection = URI(url).toURL().openConnection() as HttpURLConnection
    try {
        connection.requestMethod = "GET"
        connection.instanceFollowRedirects = false
        connection.connectTimeout = 10_000
        connection.readTimeout = 20_000
        connection.useCaches = false
        connection.setRequestProperty("Accept", "text/html,application/xhtml+xml")
        connection.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9")

        when (connection.responseCode) {
            in 200..299 -> Unit
            in 300..399 -> throw IOException("学校电费页面要求先完成微信授权，当前无法直接查询")
            else -> throw IOException("电费查询暂时不可用（HTTP ${connection.responseCode}）")
        }
        readBoundedStream(connection)
    } finally {
        connection.disconnect()
    }
}

private suspend fun defaultPostPowerQuery(formBody: String): String = withContext(Dispatchers.IO) {
    val url = "${ElectricityProtocol.HOST}${ElectricityProtocol.PAGE_PATH}"
    val connection = URI(url).toURL().openConnection() as HttpURLConnection
    try {
        connection.requestMethod = "POST"
        connection.instanceFollowRedirects = false
        connection.connectTimeout = 10_000
        connection.readTimeout = 20_000
        connection.useCaches = false
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
        connection.setRequestProperty("Accept", "text/html,application/xhtml+xml")
        connection.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9")

        val bytes = formBody.toByteArray(Charsets.UTF_8)
        connection.setFixedLengthStreamingMode(bytes.size)
        connection.outputStream.use { it.write(bytes) }

        when (connection.responseCode) {
            in 200..299 -> Unit
            in 300..399 -> throw IOException("电费查询被要求跳转，请稍后重试")
            else -> throw IOException("电费查询暂时不可用（HTTP ${connection.responseCode}）")
        }
        readBoundedStream(connection)
    } finally {
        connection.disconnect()
    }
}

private fun readBoundedStream(connection: HttpURLConnection): String {
    return connection.inputStream.use { input ->
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (output.size() + count > ElectricityProtocol.MAX_RESPONSE_BYTES) {
                throw IOException("电费页面响应过大，请稍后重试")
            }
            output.write(buffer, 0, count)
        }
        output.toString(Charsets.UTF_8.name())
    }
}
