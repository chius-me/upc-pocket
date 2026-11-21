package me.chius.upcpocket

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import me.chius.upcpocket.core.model.SchoolStudentIdentity
import me.chius.upcpocket.feature.card.CardBalanceException
import me.chius.upcpocket.feature.card.CardBalanceProtocol
import me.chius.upcpocket.feature.card.CardBalanceRepository
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class CardBalanceProtocolAndRepoTest {
    private lateinit var tempDir: File

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("upc-card-test").toFile()
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun `parses valid response with exact cent arithmetic and rejects sno mismatch`() {
        val body = """
            {
              "retcode": "0",
              "card": [
                {
                  "sno": "2024010101",
                  "db_balance": "1234",
                  "unsettle_amount": "56"
                }
              ]
            }
        """.trimIndent()

        val snapshot = CardBalanceProtocol.parse(
            body = body,
            expectedStudentId = "2024010101",
            accountId = "acct0001",
            credentialGeneration = 1L,
            fetchedAtEpochMillis = 10_000L,
        )

        assertEquals("12.34", snapshot.balanceText)
        assertEquals("0.56", snapshot.transitionText)
        assertEquals("12.90", snapshot.totalBalanceText)

        var threw = false
        try {
            CardBalanceProtocol.parse(
                body = body,
                expectedStudentId = "2024010102",
                accountId = "acct0002",
                credentialGeneration = 1L,
                fetchedAtEpochMillis = 10_000L,
            )
        } catch (_: CardBalanceException) {
            threw = true
        }
        assertTrue(threw)
    }

    @Test
    fun `persists restores clears and drops late responses after account switch`() = runTest {
        var identity: SchoolStudentIdentity? = SchoolStudentIdentity(
            accountId = "acct1111",
            studentId = "2024010101",
            generation = 1L,
        )
        val repo = CardBalanceRepository(
            directory = tempDir,
            identityProvider = { identity },
            requester = { form ->
                assertTrue(form.contains("sno=2024010101"))
                """{"retcode":"0","card":[{"sno":"2024010101","db_balance":"5000","unsettle_amount":"250"}]}"""
            },
            clock = { 55_000L },
        )

        val result = repo.refresh()
        assertTrue(result.isSuccess)
        assertEquals("52.50", repo.uiState.value.snapshot?.totalBalanceText)

        // Reopen repository for same owner restores cached snapshot.
        val reopened = CardBalanceRepository(
            directory = tempDir,
            identityProvider = { identity },
        )
        reopened.restore()
        assertNotNull(reopened.uiState.value.snapshot)
        assertEquals("52.50", reopened.uiState.value.snapshot?.totalBalanceText)

        // Clear cache removes snapshot only.
        reopened.clearCache()
        assertNull(reopened.uiState.value.snapshot)

        // Late network response arriving after account switch must be dropped.
        val gate = CompletableDeferred<Unit>()
        val slowRepo = CardBalanceRepository(
            directory = tempDir,
            identityProvider = { identity },
            requester = {
                gate.await()
                """{"retcode":"0","card":[{"sno":"2024010101","db_balance":"9999","unsettle_amount":"0"}]}"""
            },
        )
        val pending = async { slowRepo.refresh() }
        kotlinx.coroutines.yield()

        identity = SchoolStudentIdentity(
            accountId = "acct2222",
            studentId = "TS240100001",
            generation = 2L,
        )
        slowRepo.clearForAccountChange()
        gate.complete(Unit)

        val lateResult = pending.await()
        assertTrue(lateResult.isFailure)
        assertNull(slowRepo.uiState.value.snapshot)
    }
}
