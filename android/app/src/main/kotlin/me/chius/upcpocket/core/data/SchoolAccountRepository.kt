package me.chius.upcpocket.core.data

import android.content.Context
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Properties
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.chius.upcpocket.core.model.AccountInvalidationReason
import me.chius.upcpocket.core.model.AccountStatus
import me.chius.upcpocket.core.model.AccountWriteFailure
import me.chius.upcpocket.core.model.AccountWriteResult
import me.chius.upcpocket.core.model.SchoolAccountState
import me.chius.upcpocket.core.model.SchoolLoginCredential
import me.chius.upcpocket.core.model.SchoolStudentIdentity
import me.chius.upcpocket.core.model.StudentIdValidator
import me.chius.upcpocket.core.model.maskLoginName

/**
 * Local-only encrypted school account repository.
 *
 * Security invariants:
 * - Stored exclusively under noBackupFilesDir (never backed up to cloud/device transfer).
 * - Metadata stores only masked login name, SHA-256 accountId prefix, generation, and status.
 * - Passwords are encrypted via [CredentialCipher] and input CharArrays are zeroed immediately.
 * - Corrupted ciphertext or missing key transitions to [AccountStatus.RECOVERY_REQUIRED]
 *   without crashing or falling back to plaintext.
 */
class SchoolAccountRepository internal constructor(
    private val directory: File,
    private val cipher: CredentialCipher,
    private val clock: () -> Long = System::currentTimeMillis,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    constructor(context: Context) : this(
        directory = File(context.applicationContext.noBackupFilesDir, ACCOUNT_DIR),
        cipher = AndroidKeystoreAesGcmCipher(),
    )

    private val mutex = Mutex()
    private val _state = MutableStateFlow(SchoolAccountState())
    val state: StateFlow<SchoolAccountState> = _state.asStateFlow()

    private val _studentIdentity = MutableStateFlow<SchoolStudentIdentity?>(null)
    val studentIdentity: StateFlow<SchoolStudentIdentity?> = _studentIdentity.asStateFlow()

    private val invalidationListeners = CopyOnWriteArrayList<suspend (AccountInvalidationReason) -> Unit>()

    private var initialized = false

    fun addInvalidationListener(listener: suspend (AccountInvalidationReason) -> Unit) {
        invalidationListeners.add(listener)
    }

    fun matchesAccountGeneration(accountId: String, generation: Long): Boolean {
        val current = _state.value
        return current.status == AccountStatus.SAVED_UNVERIFIED &&
            current.accountId == accountId &&
            current.generation == generation
    }

    /**
     * Decrypts the saved credential under [mutex], copies the password into an ephemeral [CharArray],
     * executes [block] outside [mutex] so network I/O does not block account operations, and always
     * zeroes the password array on completion.
     */
    suspend fun <T> withLoginCredential(block: suspend (SchoolLoginCredential) -> T): T? {
        val credential = withContext(ioDispatcher) {
            mutex.withLock {
                if (!initialized) {
                    restoreLocked()
                }
                val current = _state.value
                if (current.status != AccountStatus.SAVED_UNVERIFIED || current.accountId == null) {
                    return@withLock null
                }
                val credentialFile = File(directory, CREDENTIAL_FILE)
                if (!credentialFile.exists()) return@withLock null
                val decrypted = decryptPayloadWithPasswordOrNull(credentialFile)
                    ?: return@withLock null
                val computedAccountId = stableAccountId(decrypted.loginName)
                if (computedAccountId != current.accountId || decrypted.generation != current.generation) {
                    decrypted.wipe()
                    return@withLock null
                }
                decrypted
            }
        } ?: return null

        return try {
            block(credential)
        } finally {
            credential.wipe()
        }
    }

    suspend fun restore() {
        withContext(ioDispatcher) {
            mutex.withLock {
                withContext(NonCancellable) {
                    restoreLocked()
                }
            }
        }
    }

    suspend fun save(rawLoginName: String, password: CharArray): AccountWriteResult =
        withContext(ioDispatcher) {
            try {
                mutex.withLock {
                    withContext(NonCancellable) {
                        saveLocked(rawLoginName, password)
                    }
                }
            } finally {
                password.fill('\u0000')
            }
        }

    suspend fun clear(): Boolean = withContext(ioDispatcher) {
        mutex.withLock {
            withContext(NonCancellable) {
                clearLocked()
            }
        }
    }

    private suspend fun restoreLocked() {
        initialized = true
        val metadataFile = File(directory, METADATA_FILE)
        val credentialFile = File(directory, CREDENTIAL_FILE)

        if (!metadataFile.exists() && !credentialFile.exists()) {
            _studentIdentity.value = null
            _state.value = SchoolAccountState(status = AccountStatus.UNSET, generation = 0L)
            return
        }

        if (!metadataFile.exists() && credentialFile.exists()) {
            enterRecoveryLocked(baseGeneration = 0L)
            return
        }

        val metadata = readMetadataOrNull(metadataFile)
        if (metadata == null) {
            enterRecoveryLocked(baseGeneration = _state.value.generation)
            return
        }

        when (metadata.status) {
            AccountStatus.UNSET -> {
                if (credentialFile.exists()) {
                    credentialFile.delete()
                }
                _studentIdentity.value = null
                _state.value = SchoolAccountState(
                    status = AccountStatus.UNSET,
                    generation = metadata.generation,
                )
            }

            AccountStatus.RECOVERY_REQUIRED -> {
                if (credentialFile.exists()) {
                    credentialFile.delete()
                }
                _studentIdentity.value = null
                _state.value = SchoolAccountState(
                    status = AccountStatus.RECOVERY_REQUIRED,
                    generation = metadata.generation,
                )
            }

            AccountStatus.SAVED_UNVERIFIED -> {
                if (!credentialFile.exists()) {
                    enterRecoveryLocked(baseGeneration = metadata.generation)
                    return
                }
                val decrypted = decryptPayloadOrNull(credentialFile)
                if (decrypted == null || decrypted.generation != metadata.generation) {
                    enterRecoveryLocked(baseGeneration = metadata.generation)
                    return
                }
                val computedAccountId = stableAccountId(decrypted.loginName)
                if (computedAccountId != metadata.accountId) {
                    enterRecoveryLocked(baseGeneration = metadata.generation)
                    return
                }
                val validStudentId = StudentIdValidator.isValid(decrypted.loginName)
                _studentIdentity.value = if (validStudentId) {
                    SchoolStudentIdentity(
                        accountId = computedAccountId,
                        studentId = decrypted.loginName,
                        generation = decrypted.generation,
                    )
                } else {
                    null
                }
                _state.value = SchoolAccountState(
                    status = AccountStatus.SAVED_UNVERIFIED,
                    generation = decrypted.generation,
                    accountId = computedAccountId,
                    maskedLoginName = maskLoginName(decrypted.loginName),
                    isValidStudentId = validStudentId,
                    savedAtEpochMillis = decrypted.savedAtEpochMillis,
                )
            }
        }
    }

    private suspend fun saveLocked(rawLoginName: String, password: CharArray): AccountWriteResult {
        if (!initialized) {
            restoreLocked()
        }
        val loginName = rawLoginName.trim()
        if (loginName.isEmpty() || loginName.length > MAX_LOGIN_LENGTH ||
            loginName.any { it.isISOControl() }
        ) {
            return AccountWriteResult.Failure(AccountWriteFailure.INVALID_LOGIN_NAME)
        }
        if (password.isEmpty() || password.size > MAX_PASSWORD_LENGTH ||
            password.any { it == '\u0000' }
        ) {
            return AccountWriteResult.Failure(AccountWriteFailure.INVALID_PASSWORD)
        }

        val previousAccountId = _state.value.accountId
        val nextGeneration = _state.value.generation + 1L
        val savedAt = clock().coerceAtLeast(1L)
        val accountId = stableAccountId(loginName)
        val masked = maskLoginName(loginName)
        val validStudentId = StudentIdValidator.isValid(loginName)

        val plainBytes = encodePayload(
            generation = nextGeneration,
            savedAtEpochMillis = savedAt,
            loginName = loginName,
            password = password,
        )
        val encryptedBytes = try {
            cipher.encrypt(plainBytes)
        } catch (_: Exception) {
            return AccountWriteResult.Failure(AccountWriteFailure.ENCRYPTION_FAILED)
        } finally {
            plainBytes.fill(0)
        }

        return try {
            if (previousAccountId != null && previousAccountId != accountId) {
                _studentIdentity.value = null
                notifyInvalidationListeners(AccountInvalidationReason.ACCOUNT_CHANGED_OR_CLEARED)
            }
            directory.mkdirs()
            writeAtomically(File(directory, CREDENTIAL_FILE), encryptedBytes)
            val metadata = PersistedMetadata(
                status = AccountStatus.SAVED_UNVERIFIED,
                generation = nextGeneration,
                accountId = accountId,
                maskedLoginName = masked,
                isValidStudentId = validStudentId,
                savedAtEpochMillis = savedAt,
            )
            writeAtomically(File(directory, METADATA_FILE), encodeMetadata(metadata))
            _studentIdentity.value = if (validStudentId) {
                SchoolStudentIdentity(
                    accountId = accountId,
                    studentId = loginName,
                    generation = nextGeneration,
                )
            } else {
                null
            }
            _state.value = SchoolAccountState(
                status = AccountStatus.SAVED_UNVERIFIED,
                generation = nextGeneration,
                accountId = accountId,
                maskedLoginName = masked,
                isValidStudentId = validStudentId,
                savedAtEpochMillis = savedAt,
            )
            if (previousAccountId != null && previousAccountId == accountId) {
                notifyInvalidationListeners(AccountInvalidationReason.CREDENTIALS_UPDATED)
            }
            AccountWriteResult.Success
        } catch (_: Exception) {
            AccountWriteResult.Failure(AccountWriteFailure.STORAGE_FAILED)
        }
    }

    private suspend fun clearLocked(): Boolean {
        if (!initialized) {
            restoreLocked()
        }
        val current = _state.value
        val nextGeneration = if (current.status == AccountStatus.UNSET && current.accountId == null) {
            current.generation
        } else {
            current.generation + 1L
        }
        return try {
            _studentIdentity.value = null
            notifyInvalidationListeners(AccountInvalidationReason.ACCOUNT_CHANGED_OR_CLEARED)
            directory.mkdirs()
            val credentialFile = File(directory, CREDENTIAL_FILE)
            if (credentialFile.exists() && !credentialFile.delete()) {
                return false
            }
            val metadata = PersistedMetadata(
                status = AccountStatus.UNSET,
                generation = nextGeneration,
                accountId = null,
                maskedLoginName = null,
                isValidStudentId = false,
                savedAtEpochMillis = null,
            )
            writeAtomically(File(directory, METADATA_FILE), encodeMetadata(metadata))
            _state.value = SchoolAccountState(
                status = AccountStatus.UNSET,
                generation = nextGeneration,
            )
            true
        } catch (_: Exception) {
            false
        }
    }

    private suspend fun enterRecoveryLocked(baseGeneration: Long) {
        val nextGeneration = baseGeneration.coerceAtLeast(0L) + 1L
        _studentIdentity.value = null
        notifyInvalidationListeners(AccountInvalidationReason.ACCOUNT_CHANGED_OR_CLEARED)
        runCatching {
            directory.mkdirs()
            File(directory, CREDENTIAL_FILE).delete()
            writeAtomically(
                File(directory, METADATA_FILE),
                encodeMetadata(
                    PersistedMetadata(
                        status = AccountStatus.RECOVERY_REQUIRED,
                        generation = nextGeneration,
                        accountId = null,
                        maskedLoginName = null,
                        isValidStudentId = false,
                        savedAtEpochMillis = null,
                    ),
                ),
            )
        }
        _state.value = SchoolAccountState(
            status = AccountStatus.RECOVERY_REQUIRED,
            generation = nextGeneration,
        )
    }

    private suspend fun notifyInvalidationListeners(reason: AccountInvalidationReason) {
        invalidationListeners.forEach { listener ->
            runCatching { listener(reason) }
        }
    }

    private fun decryptPayloadOrNull(file: File): DecryptedPayload? {
        return try {
            val cipherBytes = file.readBytes()
            if (cipherBytes.size > MAX_CREDENTIAL_FILE_BYTES) return null
            val plainBytes = cipher.decrypt(cipherBytes)
            try {
                decodePayload(plainBytes)
            } finally {
                plainBytes.fill(0)
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun decryptPayloadWithPasswordOrNull(file: File): SchoolLoginCredential? {
        return try {
            val cipherBytes = file.readBytes()
            if (cipherBytes.size > MAX_CREDENTIAL_FILE_BYTES) return null
            val plainBytes = cipher.decrypt(cipherBytes)
            try {
                decodePayloadWithPassword(plainBytes)
            } finally {
                plainBytes.fill(0)
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun encodePayload(
        generation: Long,
        savedAtEpochMillis: Long,
        loginName: String,
        password: CharArray,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        DataOutputStream(out).use { dos ->
            dos.writeInt(PAYLOAD_MAGIC)
            dos.writeByte(PAYLOAD_VERSION)
            dos.writeLong(generation)
            dos.writeLong(savedAtEpochMillis)
            val loginBytes = loginName.toByteArray(StandardCharsets.UTF_8)
            dos.writeInt(loginBytes.size)
            dos.write(loginBytes)
            val passwordBytes = String(password).toByteArray(StandardCharsets.UTF_8)
            try {
                dos.writeInt(passwordBytes.size)
                dos.write(passwordBytes)
            } finally {
                passwordBytes.fill(0)
            }
        }
        return out.toByteArray()
    }

    private fun decodePayload(plainBytes: ByteArray): DecryptedPayload? {
        DataInputStream(ByteArrayInputStream(plainBytes)).use { dis ->
            if (dis.readInt() != PAYLOAD_MAGIC) return null
            if (dis.readByte().toInt() != PAYLOAD_VERSION) return null
            val generation = dis.readLong()
            val savedAt = dis.readLong()
            val loginSize = dis.readInt()
            if (loginSize <= 0 || loginSize > 1024) return null
            val loginBytes = ByteArray(loginSize)
            dis.readFully(loginBytes)
            val passwordSize = dis.readInt()
            if (passwordSize <= 0 || passwordSize > 4096) return null
            val passwordBytes = ByteArray(passwordSize)
            dis.readFully(passwordBytes)
            passwordBytes.fill(0)
            if (dis.available() != 0) return null
            val loginName = String(loginBytes, StandardCharsets.UTF_8)
            if (loginName.isBlank()) return null
            return DecryptedPayload(
                generation = generation,
                savedAtEpochMillis = savedAt,
                loginName = loginName,
            )
        }
    }

    private fun decodePayloadWithPassword(plainBytes: ByteArray): SchoolLoginCredential? {
        DataInputStream(ByteArrayInputStream(plainBytes)).use { dis ->
            if (dis.readInt() != PAYLOAD_MAGIC) return null
            if (dis.readByte().toInt() != PAYLOAD_VERSION) return null
            val generation = dis.readLong()
            dis.readLong() // savedAtEpochMillis
            val loginSize = dis.readInt()
            if (loginSize <= 0 || loginSize > 1024) return null
            val loginBytes = ByteArray(loginSize)
            dis.readFully(loginBytes)
            val passwordSize = dis.readInt()
            if (passwordSize <= 0 || passwordSize > 4096) return null
            val passwordBytes = ByteArray(passwordSize)
            try {
                dis.readFully(passwordBytes)
                if (dis.available() != 0) return null
                val loginName = String(loginBytes, StandardCharsets.UTF_8)
                if (loginName.isBlank()) return null
                val charBuffer = StandardCharsets.UTF_8.newDecoder()
                    .decode(java.nio.ByteBuffer.wrap(passwordBytes))
                val passwordChars = CharArray(charBuffer.remaining())
                charBuffer.get(passwordChars)
                if (charBuffer.hasArray()) {
                    charBuffer.array().fill('\u0000')
                }
                if (passwordChars.isEmpty() || passwordChars.any { it == '\u0000' }) {
                    passwordChars.fill('\u0000')
                    return null
                }
                return SchoolLoginCredential(
                    accountId = stableAccountId(loginName),
                    generation = generation,
                    loginName = loginName,
                    password = passwordChars,
                )
            } finally {
                passwordBytes.fill(0)
            }
        }
    }

    private fun encodeMetadata(meta: PersistedMetadata): ByteArray {
        val props = Properties().apply {
            setProperty("status", meta.status.name)
            setProperty("generation", meta.generation.toString())
            meta.accountId?.let { setProperty("accountId", it) }
            meta.maskedLoginName?.let { setProperty("maskedLoginName", it) }
            setProperty("isValidStudentId", meta.isValidStudentId.toString())
            meta.savedAtEpochMillis?.let { setProperty("savedAtEpochMillis", it.toString()) }
        }
        val out = ByteArrayOutputStream()
        props.store(out, "UPC Pocket School Account Metadata (no secrets)")
        return out.toByteArray()
    }

    private fun readMetadataOrNull(file: File): PersistedMetadata? {
        return try {
            val props = Properties()
            file.inputStream().use { props.load(it) }
            val status = AccountStatus.entries.firstOrNull {
                it.name == props.getProperty("status")
            } ?: return null
            val generation = props.getProperty("generation")?.toLongOrNull()
                ?.takeIf { it >= 0L } ?: return null
            PersistedMetadata(
                status = status,
                generation = generation,
                accountId = props.getProperty("accountId"),
                maskedLoginName = props.getProperty("maskedLoginName"),
                isValidStudentId = props.getProperty("isValidStudentId")?.toBooleanStrictOrNull() ?: false,
                savedAtEpochMillis = props.getProperty("savedAtEpochMillis")?.toLongOrNull(),
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun writeAtomically(target: File, bytes: ByteArray) {
        val tempFile = File(target.parentFile, "${target.name}.tmp")
        tempFile.outputStream().use { fos ->
            fos.write(bytes)
            fos.fd.sync()
        }
        if (target.exists() && !target.delete()) {
            throw IOException("Failed to replace ${target.name}")
        }
        if (!tempFile.renameTo(target)) {
            throw IOException("Failed to commit ${target.name}")
        }
    }

    private fun stableAccountId(loginName: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(loginName.toByteArray(StandardCharsets.UTF_8))
        return digest.take(8).joinToString("") { byte -> "%02x".format(byte) }
    }

    private data class DecryptedPayload(
        val generation: Long,
        val savedAtEpochMillis: Long,
        val loginName: String,
    )

    private data class PersistedMetadata(
        val status: AccountStatus,
        val generation: Long,
        val accountId: String?,
        val maskedLoginName: String?,
        val isValidStudentId: Boolean,
        val savedAtEpochMillis: Long?,
    )

    private companion object {
        const val ACCOUNT_DIR = "school-account"
        const val CREDENTIAL_FILE = "credential.bin"
        const val METADATA_FILE = "metadata.properties"
        const val PAYLOAD_MAGIC = 0x55504331
        const val PAYLOAD_VERSION = 1
        const val MAX_LOGIN_LENGTH = 128
        const val MAX_PASSWORD_LENGTH = 512
        const val MAX_CREDENTIAL_FILE_BYTES = 16 * 1024
    }
}
