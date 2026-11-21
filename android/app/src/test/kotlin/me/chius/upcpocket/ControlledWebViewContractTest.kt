package me.chius.upcpocket

import me.chius.upcpocket.core.model.AccountStatus
import me.chius.upcpocket.core.model.SchoolAccountState
import me.chius.upcpocket.core.webview.ControlledWebViewContract
import me.chius.upcpocket.core.webview.ControlledWebViewContract.Decision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ControlledWebViewContractTest {
    @Test
    fun `allows exact upc https auth urls and blocks http or third-party hosts`() {
        assertEquals(
            Decision.ALLOW,
            ControlledWebViewContract.inspectNavigation(
                "https://cas.upc.edu.cn/cas/login?service=https%3A%2F%2Fapp.upc.edu.cn",
                method = "GET",
                isMainFrame = true,
            ),
        )
        assertEquals(
            Decision.ALLOW,
            ControlledWebViewContract.inspectNavigation(
                "https://cas.upc.edu.cn/cas/login",
                method = "POST",
                isMainFrame = false,
            ),
        )
        assertEquals(
            Decision.ALLOW,
            ControlledWebViewContract.inspectNavigation(
                "https://app.upc.edu.cn/site/weekschedule/index",
                method = "GET",
                isMainFrame = true,
            ),
        )

        // Cleartext hosts are disallowed inside the auth WebView.
        assertEquals(
            Decision.BLOCK,
            ControlledWebViewContract.inspectNavigation("http://jhzf.upc.edu.cn:20081/"),
        )
        assertEquals(
            Decision.BLOCK,
            ControlledWebViewContract.inspectNavigation("http://cas.upc.edu.cn/cas/login"),
        )
        assertEquals(
            Decision.BLOCK,
            ControlledWebViewContract.inspectNavigation("https://evil.example.com/cas/login"),
        )
        assertEquals(
            Decision.BLOCK,
            ControlledWebViewContract.inspectNavigation("https://user:pass@cas.upc.edu.cn/cas/login"),
        )
        assertEquals(
            Decision.BLOCK,
            ControlledWebViewContract.inspectNavigation("https://cas.upc.edu.cn:8443/cas/login"),
        )
    }

    @Test
    fun `session context invalidates when account generation changes`() {
        val initial = SchoolAccountState(
            status = AccountStatus.SAVED_UNVERIFIED,
            generation = 1L,
            accountId = "0123456789abcdef",
        )
        val context = ControlledWebViewContract.SessionContext(
            accountId = initial.accountId,
            generation = initial.generation,
            entryUrl = "https://cas.upc.edu.cn/cas/login",
        )
        assertTrue(context.isStillValidFor(initial))
        assertFalse(context.isStillValidFor(initial.copy(generation = 2L)))
        assertFalse(context.isStillValidFor(initial.copy(accountId = null)))
    }
}
