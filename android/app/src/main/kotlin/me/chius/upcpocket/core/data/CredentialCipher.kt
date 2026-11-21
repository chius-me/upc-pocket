package me.chius.upcpocket.core.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

interface CredentialCipher {
    fun encrypt(plaintext: ByteArray): ByteArray
    fun decrypt(ciphertext: ByteArray): ByteArray
}

class CredentialCryptoException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

class AndroidKeystoreAesGcmCipher(
    private val keyAlias: String = DEFAULT_KEY_ALIAS,
) : CredentialCipher {

    override fun encrypt(plaintext: ByteArray): ByteArray {
        try {
            val secretKey = getOrCreateSecretKey()
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey)
            val iv = cipher.iv
            if (iv.size != GCM_IV_LENGTH) {
                throw CredentialCryptoException("Unexpected GCM IV length")
            }
            val encrypted = cipher.doFinal(plaintext)
            return ByteArray(2 + iv.size + encrypted.size).apply {
                this[0] = FORMAT_VERSION
                this[1] = iv.size.toByte()
                System.arraycopy(iv, 0, this, 2, iv.size)
                System.arraycopy(encrypted, 0, this, 2 + iv.size, encrypted.size)
            }
        } catch (e: CredentialCryptoException) {
            throw e
        } catch (e: Exception) {
            throw CredentialCryptoException("Failed to encrypt school credential", e)
        }
    }

    override fun decrypt(ciphertext: ByteArray): ByteArray {
        try {
            if (ciphertext.size < 3 || ciphertext[0] != FORMAT_VERSION) {
                throw CredentialCryptoException("Unsupported credential ciphertext format")
            }
            val ivLength = ciphertext[1].toInt() and 0xFF
            if (ivLength != GCM_IV_LENGTH || ciphertext.size <= 2 + ivLength) {
                throw CredentialCryptoException("Malformed credential ciphertext header")
            }
            val iv = ciphertext.copyOfRange(2, 2 + ivLength)
            val payload = ciphertext.copyOfRange(2 + ivLength, ciphertext.size)

            val secretKey = getExistingSecretKey()
                ?: throw CredentialCryptoException("Missing Android Keystore secret key")

            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
            return cipher.doFinal(payload)
        } catch (e: CredentialCryptoException) {
            throw e
        } catch (e: Exception) {
            throw CredentialCryptoException("Failed to decrypt school credential", e)
        }
    }

    private fun getExistingSecretKey(): SecretKey? {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        return keyStore.getKey(keyAlias, null) as? SecretKey
    }

    private fun getOrCreateSecretKey(): SecretKey {
        getExistingSecretKey()?.let { return it }
        val keyGenerator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            KEYSTORE_PROVIDER,
        )
        val spec = KeyGenParameterSpec.Builder(
            keyAlias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(AES_KEY_SIZE_BITS)
            .build()
        keyGenerator.init(spec)
        return keyGenerator.generateKey()
    }

    private companion object {
        const val DEFAULT_KEY_ALIAS = "upcpocket.school_account.v1"
        const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val FORMAT_VERSION: Byte = 1
        const val GCM_IV_LENGTH = 12
        const val GCM_TAG_LENGTH_BITS = 128
        const val AES_KEY_SIZE_BITS = 256
    }
}
