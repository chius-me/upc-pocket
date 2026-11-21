package me.chius.upcpocket.feature.timetable

import android.content.Context
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.chius.upcpocket.core.data.AndroidKeystoreAesGcmCipher
import me.chius.upcpocket.core.data.CredentialCipher

@Serializable
data class TimetableSession(
    val accountId: String,
    val credentialGeneration: Long,
    val savedAtEpochMillis: Long,
    val cookies: Map<String, String>,
) {
    override fun toString(): String =
        "TimetableSession(accountId=$accountId, generation=$credentialGeneration, cookieCount=${cookies.size})"
}

/**
 * Encrypted local store for verified `app.upc.edu.cn` timetable session cookies.
 * Uses a dedicated Keystore AES-256-GCM alias isolated from the master school credential cipher
 * and stores files exclusively inside `noBackupFilesDir`.
 */
class TimetableSessionStore internal constructor(
    private val directory: File,
    private val cipher: CredentialCipher,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    constructor(context: Context) : this(
        directory = File(context.applicationContext.noBackupFilesDir, TIMETABLE_DIR),
        cipher = AndroidKeystoreAesGcmCipher(keyAlias = SESSION_KEY_ALIAS),
    )

    private val mutex = Mutex()
    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = false
    }

    suspend fun load(accountId: String, credentialGeneration: Long): TimetableSession? =
        withContext(ioDispatcher) {
            mutex.withLock {
                val file = File(directory, SESSION_FILE)
                if (!file.isFile || file.length() > MAX_SESSION_BYTES) return@withLock null
                val cipherBytes = runCatching { file.readBytes() }.getOrNull() ?: return@withLock null
                var plainBytes: ByteArray? = null
                try {
                    plainBytes = cipher.decrypt(cipherBytes)
                    val decoded = json.decodeFromString<TimetableSession>(
                        plainBytes.toString(StandardCharsets.UTF_8),
                    )
                    decoded.takeIf {
                        it.accountId == accountId &&
                            it.credentialGeneration == credentialGeneration &&
                            it.cookies.isNotEmpty() &&
                            it.cookies.size <= MAX_COOKIES
                    }
                } catch (_: Exception) {
                    null
                } finally {
                    plainBytes?.fill(0)
                }
            }
        }

    suspend fun save(session: TimetableSession): Boolean = withContext(ioDispatcher) {
        if (session.cookies.isEmpty() || session.cookies.size > MAX_COOKIES) return@withContext false
        mutex.withLock {
            var plainBytes: ByteArray? = null
            try {
                directory.mkdirs()
                plainBytes = json.encodeToString(session).toByteArray(StandardCharsets.UTF_8)
                val encrypted = cipher.encrypt(plainBytes)
                if (encrypted.size > MAX_SESSION_BYTES) return@withLock false
                writeAtomically(File(directory, SESSION_FILE), encrypted)
                true
            } catch (_: Exception) {
                false
            } finally {
                plainBytes?.fill(0)
            }
        }
    }

    suspend fun clear() = withContext(ioDispatcher) {
        mutex.withLock {
            deleteAtomic(directory, SESSION_FILE)
        }
    }

    private companion object {
        const val TIMETABLE_DIR = "timetable"
        const val SESSION_FILE = "session.bin"
        const val SESSION_KEY_ALIAS = "upcpocket.timetable_session.v1"
        const val MAX_SESSION_BYTES = 128 * 1024
        const val MAX_COOKIES = 64
    }
}

/**
 * Per-account offline cache for verified complete timetable weeks and index metadata.
 */
class TimetableCacheStore internal constructor(
    private val directory: File,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    constructor(context: Context) : this(
        directory = File(context.applicationContext.noBackupFilesDir, TIMETABLE_DIR),
    )

    private val mutex = Mutex()
    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = false
    }

    suspend fun load(accountId: String): TimetableOfflineCache? = withContext(ioDispatcher) {
        mutex.withLock {
            readCacheLocked(accountId)
        }
    }

    suspend fun save(
        accountId: String,
        index: TimetableIndex?,
        week: TimetableWeek?,
        nowEpochMillis: Long,
    ): Boolean = withContext(ioDispatcher) {
        if (index == null && week == null) return@withContext false
        if (week != null && !week.complete) return@withContext false
        mutex.withLock {
            try {
                directory.mkdirs()
                val existing = readCacheLocked(accountId)
                val mergedWeeks = (existing?.weeks.orEmpty())
                    .filter { it.complete }
                    .filterNot {
                        week != null &&
                            it.week == week.week &&
                            it.term.year == week.term.year &&
                            it.term.term == week.term.term
                    }
                    .toMutableList()
                if (week != null) {
                    mergedWeeks += week
                }
                val boundedWeeks = mergedWeeks
                    .sortedByDescending { it.fetchedAtEpochMillis }
                    .take(MAX_CACHED_WEEKS)
                val updated = TimetableOfflineCache(
                    accountId = accountId,
                    cachedAtEpochMillis = nowEpochMillis.coerceAtLeast(1L),
                    index = index ?: existing?.index,
                    weeks = boundedWeeks,
                )
                val bytes = json.encodeToString(updated).toByteArray(StandardCharsets.UTF_8)
                if (bytes.size > MAX_CACHE_BYTES) return@withLock false
                writeAtomically(File(directory, CACHE_FILE), bytes)
                true
            } catch (_: Exception) {
                false
            }
        }
    }

    suspend fun clear() = withContext(ioDispatcher) {
        mutex.withLock {
            deleteAtomic(directory, CACHE_FILE)
        }
    }

    private fun readCacheLocked(expectedAccountId: String): TimetableOfflineCache? {
        val file = File(directory, CACHE_FILE)
        if (!file.isFile || file.length() > MAX_CACHE_BYTES) return null
        return runCatching {
            val bytes = file.readBytes()
            if (bytes.size > MAX_CACHE_BYTES) return null
            val decoded = json.decodeFromString<TimetableOfflineCache>(
                bytes.toString(StandardCharsets.UTF_8),
            )
            decoded.takeIf { it.accountId == expectedAccountId }?.let { cache ->
                cache.copy(weeks = cache.weeks.filter { it.complete })
            }
        }.getOrNull()
    }

    private companion object {
        const val TIMETABLE_DIR = "timetable"
        const val CACHE_FILE = "cache-v1.json"
        const val MAX_CACHED_WEEKS = 32
        const val MAX_CACHE_BYTES = 2 * 1024 * 1024
    }
}

private fun writeAtomically(target: File, bytes: ByteArray) {
    val tmp = File(target.parentFile, "${target.name}.tmp")
    tmp.outputStream().use { fos ->
        fos.write(bytes)
        fos.fd.sync()
    }
    if (target.exists() && !target.delete()) {
        throw IOException("Failed to replace ${target.name}")
    }
    if (!tmp.renameTo(target)) {
        throw IOException("Failed to commit ${target.name}")
    }
}

private fun deleteAtomic(directory: File, filename: String) {
    File(directory, filename).delete()
    File(directory, "$filename.tmp").delete()
}
