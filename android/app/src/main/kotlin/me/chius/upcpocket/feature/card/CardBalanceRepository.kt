package me.chius.upcpocket.feature.card

import android.content.Context
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.util.concurrent.atomic.AtomicLong
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
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.chius.upcpocket.core.model.SchoolStudentIdentity

class CardBalanceRepository internal constructor(
    private val directory: File,
    private val identityProvider: () -> SchoolStudentIdentity?,
    private val requester: suspend (String) -> String = ::defaultRequestCardBalance,
    private val clock: () -> Long = System::currentTimeMillis,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    constructor(
        context: Context,
        identityProvider: () -> SchoolStudentIdentity?,
    ) : this(
        directory = File(context.applicationContext.noBackupFilesDir, DIR_NAME),
        identityProvider = identityProvider,
    )

    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = false
    }
    private val fileMutex = Mutex()
    private val refreshMutex = Mutex()
    private val businessEpoch = AtomicLong(0L)

    private val _uiState = MutableStateFlow(CardBalanceUiState())
    val uiState: StateFlow<CardBalanceUiState> = _uiState.asStateFlow()

    suspend fun restore() = withContext(ioDispatcher) {
        val identity = identityProvider()
        val epochAtStart = businessEpoch.get()
        if (identity == null) {
            _uiState.update { it.copy(snapshot = null, isHistoricalGeneration = false) }
            return@withContext
        }
        val loaded = fileMutex.withLock {
            readSnapshotOrNull(identity)
        }
        if (identityProvider() != identity || businessEpoch.get() != epochAtStart) {
            return@withContext
        }
        _uiState.update {
            it.copy(
                snapshot = loaded,
                isHistoricalGeneration = loaded != null && loaded.credentialGeneration != identity.generation,
            )
        }
    }

    suspend fun refresh(): Result<CardBalanceSnapshot> = withContext(ioDispatcher) {
        refreshMutex.withLock {
            val identity = identityProvider()
                ?: return@withLock Result.failure(CardBalanceException("请先配置包含有效本人学号的学校账号"))
            val epochAtStart = businessEpoch.get()
            _uiState.update { it.copy(isRefreshing = true, errorMessage = null) }

            try {
                val form = CardBalanceProtocol.formBody(identity.studentId)
                val body = requester(form)
                val parsed = CardBalanceProtocol.parse(
                    body = body,
                    expectedStudentId = identity.studentId,
                    accountId = identity.accountId,
                    credentialGeneration = identity.generation,
                    fetchedAtEpochMillis = clock().coerceAtLeast(1L),
                )

                // Re-verify active identity and epoch before committing to disk or UI state.
                if (identityProvider() != identity || businessEpoch.get() != epochAtStart) {
                    _uiState.update { it.copy(isRefreshing = false) }
                    return@withLock Result.failure(CardBalanceException("账号已变更，已放弃本次余额结果"))
                }

                fileMutex.withLock {
                    if (identityProvider() != identity || businessEpoch.get() != epochAtStart) {
                        throw CardBalanceException("账号已变更，已放弃保存余额")
                    }
                    writeSnapshotAtomically(parsed)
                }

                _uiState.update {
                    it.copy(
                        snapshot = parsed,
                        isRefreshing = false,
                        errorMessage = null,
                        isHistoricalGeneration = false,
                    )
                }
                Result.success(parsed)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                val message = (error as? CardBalanceException)?.message
                    ?: "校园卡余额查询失败，请检查校园网络后重试"
                _uiState.update { it.copy(isRefreshing = false, errorMessage = message) }
                Result.failure(error)
            }
        }
    }

    suspend fun clearCache() = withContext(ioDispatcher) {
        businessEpoch.incrementAndGet()
        _uiState.update {
            it.copy(
                snapshot = null,
                errorMessage = null,
                isHistoricalGeneration = false,
            )
        }
        fileMutex.withLock {
            deleteSnapshotFiles()
        }
    }

    suspend fun clearForAccountChange() = withContext(ioDispatcher) {
        businessEpoch.incrementAndGet()
        _uiState.update {
            CardBalanceUiState()
        }
        fileMutex.withLock {
            deleteSnapshotFiles()
        }
    }

    private fun readSnapshotOrNull(identity: SchoolStudentIdentity): CardBalanceSnapshot? {
        val file = File(directory, SNAPSHOT_FILE)
        if (!file.exists()) return null
        return runCatching {
            val bytes = file.readBytes()
            if (bytes.size > MAX_CACHE_BYTES) return null
            val snapshot = json.decodeFromString<CardBalanceSnapshot>(bytes.toString(Charsets.UTF_8))
            snapshot.takeIf { it.sameOwner(identity.accountId, identity.studentId) }
        }.getOrNull()
    }

    private fun writeSnapshotAtomically(snapshot: CardBalanceSnapshot) {
        directory.mkdirs()
        val target = File(directory, SNAPSHOT_FILE)
        val tmp = File(directory, "$SNAPSHOT_FILE.tmp")
        val bytes = json.encodeToString(snapshot).toByteArray(Charsets.UTF_8)
        tmp.outputStream().use { fos ->
            fos.write(bytes)
            fos.fd.sync()
        }
        if (target.exists() && !target.delete()) {
            throw IOException("Failed to replace card balance snapshot")
        }
        if (!tmp.renameTo(target)) {
            throw IOException("Failed to commit card balance snapshot")
        }
    }

    private fun deleteSnapshotFiles() {
        File(directory, SNAPSHOT_FILE).delete()
        File(directory, "$SNAPSHOT_FILE.tmp").delete()
    }

    private companion object {
        const val DIR_NAME = "card-balance"
        const val SNAPSHOT_FILE = "snapshot-v1.json"
        const val MAX_CACHE_BYTES = 16 * 1024
    }
}

private suspend fun defaultRequestCardBalance(formBody: String): String = withContext(Dispatchers.IO) {
    val connection = URI(CardBalanceProtocol.ENDPOINT).toURL().openConnection() as HttpURLConnection
    try {
        connection.requestMethod = "POST"
        connection.instanceFollowRedirects = false
        connection.connectTimeout = 10_000
        connection.readTimeout = 20_000
        connection.useCaches = false
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
        // School gateway rejects application/json with HTTP 406.
        connection.setRequestProperty("Accept", "*/*")
        connection.setRequestProperty("X-Requested-With", "XMLHttpRequest")
        connection.setRequestProperty("User-Agent", "UPCPocket-Native/0.1")
        connection.setRequestProperty("Cache-Control", "no-cache, no-store")
        connection.setRequestProperty("Cookie", "")

        val bytes = formBody.toByteArray(Charsets.UTF_8)
        connection.setFixedLengthStreamingMode(bytes.size)
        connection.outputStream.use { it.write(bytes) }

        if (connection.responseCode !in 200..299) {
            throw CardBalanceException("校园卡服务暂时不可用（HTTP ${connection.responseCode}），请稍后重试")
        }

        connection.inputStream.use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(4096)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (output.size() + count > CardBalanceProtocol.MAX_RESPONSE_BYTES) {
                    throw CardBalanceException("校园卡响应异常过大")
                }
                output.write(buffer, 0, count)
            }
            output.toString(Charsets.UTF_8.name())
        }
    } catch (e: CardBalanceException) {
        throw e
    } catch (_: IOException) {
        throw CardBalanceException("无法连接校园卡余额服务，请检查网络后重试")
    } finally {
        connection.disconnect()
    }
}
