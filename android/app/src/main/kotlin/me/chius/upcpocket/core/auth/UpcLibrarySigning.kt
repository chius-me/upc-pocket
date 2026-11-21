package me.chius.upcpocket.core.auth

import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.Locale
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Request signing helper for UPC `libspace.upc.edu.cn` (`libseat`) APIs.
 *
 * Uses the non-secret public client key embedded in the school's frontend script to compute:
 * `HMAC-SHA256("seat::<uuid>::<date>::<METHOD>", configKey)` as lowercase hex.
 */
object UpcLibrarySigning {
    private const val NUM_CODE = "cDf+jadFUWncEn536MXItw=="
    private const val CONFIG_KEY = "server_date_time"
    private const val CONFIG_IV = "client_date_time"

    fun configKey(): ByteArray {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(CONFIG_KEY.toByteArray(StandardCharsets.UTF_8), "AES"),
            IvParameterSpec(CONFIG_IV.toByteArray(StandardCharsets.UTF_8)),
        )
        return cipher.doFinal(Base64.getDecoder().decode(NUM_CODE))
    }

    fun sign(
        method: String,
        uuid: String,
        timestamp: Long,
        hmacKey: ByteArray = configKey(),
    ): String {
        val normalizedMethod = method.uppercase(Locale.ROOT)
        require(normalizedMethod == "GET" || normalizedMethod == "POST") {
            "Unsupported HTTP method for libseat signing"
        }
        val message = "seat::$uuid::$timestamp::$normalizedMethod"
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(hmacKey, "HmacSHA256"))
        return mac.doFinal(message.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(Locale.ROOT, it) }
    }

    fun signedHeaders(
        token: String?,
        method: String,
        uuid: String = UUID.randomUUID().toString(),
        timestamp: Long = System.currentTimeMillis(),
        hmacKey: ByteArray = configKey(),
    ): Map<String, String> = buildMap {
        if (!token.isNullOrBlank()) {
            put("Authorization", token)
        }
        put("loginType", "PC")
        put("X-request-id", uuid)
        put("X-request-date", timestamp.toString())
        put("X-hmac-request-key", sign(method, uuid, timestamp, hmacKey))
        put("Accept", "application/json")
    }
}
