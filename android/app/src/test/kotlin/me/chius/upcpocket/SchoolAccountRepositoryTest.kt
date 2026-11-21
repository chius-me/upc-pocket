package me.chius.upcpocket

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import me.chius.upcpocket.core.data.CredentialCipher
import me.chius.upcpocket.core.data.CredentialCryptoException
import me.chius.upcpocket.core.data.SchoolAccountRepository
import me.chius.upcpocket.core.model.AccountStatus
import me.chius.upcpocket.core.model.AccountWriteFailure
import me.chius.upcpocket.core.model.AccountWriteResult
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SchoolAccountRepositoryTest {
    private lateinit var tempDir: File

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("upc-account-test").toFile()
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun `save password change account switch clear and zero input array`() = runTest {
        val cipher = FakeXorCipher()
        var now = 1_000L
        val repo = SchoolAccountRepository(
            directory = tempDir,
            cipher = cipher,
            clock = { now },
        )

        repo.restore()
        assertEquals(AccountStatus.UNSET, repo.state.value.status)
        assertEquals(0L, repo.state.value.generation)

        val firstPassword = "test-password-1".toCharArray()
        val firstSave = repo.save(" 2024010101 ", firstPassword)
        assertEquals(AccountWriteResult.Success, firstSave)
        assertTrue(firstPassword.all { it == '\u0000' })

        val state1 = repo.state.value
        assertEquals(AccountStatus.SAVED_UNVERIFIED, state1.status)
        assertEquals(1L, state1.generation)
        assertNotNull(state1.accountId)
        assertTrue(state1.isValidStudentId)
        assertFalse(state1.maskedLoginName.orEmpty().contains("2024010101"))

        val metaContent = File(tempDir, "metadata.properties").readText()
        assertFalse(metaContent.contains("2024010101"))
        assertFalse(metaContent.contains("test-password-1"))

        // Same account password update preserves accountId and increments generation.
        now = 2_000L
        val secondSave = repo.save("2024010101", "test-password-2".toCharArray())
        assertEquals(AccountWriteResult.Success, secondSave)
        val state2 = repo.state.value
        assertEquals(state1.accountId, state2.accountId)
        assertEquals(2L, state2.generation)

        // Switching account changes accountId and increments generation.
        now = 3_000L
        val thirdSave = repo.save("TS240100001", "test-password-3".toCharArray())
        assertEquals(AccountWriteResult.Success, thirdSave)
        val state3 = repo.state.value
        assertNotEquals(state2.accountId, state3.accountId)
        assertEquals(3L, state3.generation)
        assertTrue(state3.isValidStudentId)

        // Re-creating repository restores persisted state cleanly.
        val reopened = SchoolAccountRepository(directory = tempDir, cipher = cipher)
        reopened.restore()
        assertEquals(AccountStatus.SAVED_UNVERIFIED, reopened.state.value.status)
        assertEquals(state3.accountId, reopened.state.value.accountId)
        assertEquals(3L, reopened.state.value.generation)

        // Clear removes encrypted file and resets status while advancing generation.
        assertTrue(reopened.clear())
        assertEquals(AccountStatus.UNSET, reopened.state.value.status)
        assertNull(reopened.state.value.accountId)
        assertEquals(4L, reopened.state.value.generation)
        assertFalse(File(tempDir, "credential.bin").exists())
    }

    @Test
    fun `corrupted ciphertext transitions to recovery required without crashing`() = runTest {
        val cipher = FakeXorCipher()
        val repo = SchoolAccountRepository(directory = tempDir, cipher = cipher)
        repo.save("2024010101", "secret".toCharArray())

        // Simulate keystore loss / ciphertext corruption.
        File(tempDir, "credential.bin").writeBytes(byteArrayOf(0x01, 0x02, 0x03))

        val reopened = SchoolAccountRepository(directory = tempDir, cipher = cipher)
        reopened.restore()
        assertEquals(AccountStatus.RECOVERY_REQUIRED, reopened.state.value.status)
        assertNull(reopened.state.value.accountId)
        assertFalse(File(tempDir, "credential.bin").exists())
    }

    @Test
    fun `rejects empty password and encryption failures`() = runTest {
        val failingCipher = object : CredentialCipher {
            override fun encrypt(plaintext: ByteArray): ByteArray {
                throw CredentialCryptoException("keystore locked")
            }

            override fun decrypt(ciphertext: ByteArray): ByteArray {
                throw CredentialCryptoException("keystore locked")
            }
        }
        val repo = SchoolAccountRepository(directory = tempDir, cipher = failingCipher)

        assertEquals(
            AccountWriteResult.Failure(AccountWriteFailure.INVALID_PASSWORD),
            repo.save("2024010101", charArrayOf()),
        )

        val secret = "pw".toCharArray()
        assertEquals(
            AccountWriteResult.Failure(AccountWriteFailure.ENCRYPTION_FAILED),
            repo.save("2024010101", secret),
        )
        assertTrue(secret.all { it == '\u0000' })
        assertEquals(AccountStatus.UNSET, repo.state.value.status)
    }

    private class FakeXorCipher : CredentialCipher {
        override fun encrypt(plaintext: ByteArray): ByteArray =
            byteArrayOf(0x7F) + plaintext.map { (it.toInt() xor 0x5A).toByte() }.toByteArray()

        override fun decrypt(ciphertext: ByteArray): ByteArray {
            if (ciphertext.size < 2 || ciphertext[0] != 0x7F.toByte()) {
                throw CredentialCryptoException("corrupted")
            }
            return ciphertext.drop(1).map { (it.toInt() xor 0x5A).toByte() }.toByteArray()
        }
    }
}
