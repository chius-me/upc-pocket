package me.chius.upcpocket.feature.card

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.nio.charset.StandardCharsets
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.chius.upcpocket.core.data.AndroidKeystoreAesGcmCipher
import me.chius.upcpocket.core.data.CredentialCipher
import me.chius.upcpocket.core.model.SchoolStudentIdentity

@Serializable
private data class PersistedPendingRecharge(
    val version: Int = 1,
    val accountId: String,
    val studentId: String,
    val generation: Long,
    val attemptId: String,
    val yuan: String,
    val orderId: String? = null,
    val payId: String? = null,
)

/**
 * Keystore AES-256-GCM encrypted store inside `noBackupFilesDir` tracking in-flight card recharge orders
 * across process death so unknown POST states are never blindly retried.
 */
class RechargePendingStore internal constructor(
    private val directory: File,
    private val cipher: CredentialCipher,
) {
    constructor(context: Context) : this(
        directory = File(context.applicationContext.noBackupFilesDir, DIR_NAME),
        cipher = AndroidKeystoreAesGcmCipher(KEY_ALIAS),
    )

    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = false
    }

    @Synchronized
    fun read(owner: SchoolStudentIdentity): RechargePendingRecord? {
        val file = atomicFileFor(owner)
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return null
        var plain: ByteArray? = null
        return try {
            val bytes = file.openRead().use { it.readBytes() }
            if (bytes.size > MAX_BYTES) throw CardWalletException("挂起订单记录过大", "P-PENDING-STORE")
            plain = cipher.decrypt(bytes)
            val decoded = json.decodeFromString<PersistedPendingRecharge>(plain.toString(StandardCharsets.UTF_8))
            if (decoded.version != 1 ||
                decoded.accountId != owner.accountId ||
                decoded.studentId != owner.studentId ||
                decoded.generation != owner.generation
            ) {
                return null
            }
            RechargePendingRecord(
                accountId = decoded.accountId,
                studentId = decoded.studentId,
                generation = decoded.generation,
                attemptId = decoded.attemptId,
                yuan = decoded.yuan,
                orderId = decoded.orderId,
                payId = decoded.payId,
            )
        } catch (e: CardWalletException) {
            throw e
        } catch (_: Exception) {
            throw CardWalletException("无法读取挂起订单记录，请先核对微信账单", "P-PENDING-STORE")
        } finally {
            plain?.fill(0)
        }
    }

    @Synchronized
    fun save(owner: SchoolStudentIdentity, record: RechargePendingRecord) {
        if (!directory.exists() && !directory.mkdirs()) {
            throw CardWalletException("无法创建挂起订单目录", "P-PENDING-STORE")
        }
        val file = atomicFileFor(owner)
        val payload = PersistedPendingRecharge(
            version = 1,
            accountId = record.accountId,
            studentId = record.studentId,
            generation = record.generation,
            attemptId = record.attemptId,
            yuan = record.yuan,
            orderId = record.orderId,
            payId = record.payId,
        )
        val plain = json.encodeToString(payload).toByteArray(StandardCharsets.UTF_8)
        try {
            val encrypted = cipher.encrypt(plain)
            val stream = file.startWrite()
            try {
                stream.write(encrypted)
                stream.fd.sync()
                file.finishWrite(stream)
            } catch (e: Exception) {
                file.failWrite(stream)
                throw e
            }
            if (read(owner) != record) {
                throw CardWalletException("挂起订单校验写回失败", "P-PENDING-STORE")
            }
        } catch (e: CardWalletException) {
            throw e
        } catch (_: Exception) {
            throw CardWalletException("无法安全保存充值单状态", "P-PENDING-STORE")
        } finally {
            plain.fill(0)
        }
    }

    @Synchronized
    fun removeIfMatches(owner: SchoolStudentIdentity, attemptId: String) {
        val current = runCatching { read(owner) }.getOrNull() ?: return
        if (current.attemptId == attemptId) {
            atomicFileFor(owner).delete()
        }
    }

    @Synchronized
    fun clearAll() {
        directory.listFiles()?.forEach { it.delete() }
    }

    private fun atomicFileFor(owner: SchoolStudentIdentity): AtomicFile {
        val name = CardWalletProtocol.sha256Hex("${owner.accountId}:${owner.studentId}") + ".enc"
        return AtomicFile(File(directory, name))
    }

    private companion object {
        const val DIR_NAME = "card_recharge"
        const val KEY_ALIAS = "upcpocket.card_recharge.v1"
        const val MAX_BYTES = 32 * 1024
    }
}
