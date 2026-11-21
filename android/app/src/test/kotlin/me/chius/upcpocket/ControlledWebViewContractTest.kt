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
        assertEquals(
            Decision.ALLOW,
            ControlledWebViewContract.inspectNavigation(
                "https://cas.upc.edu.cn/cas/themes/upc/login-mobile.html",
                method = "GET",
                isMainFrame = false,
                scopeHosts = ControlledWebViewContract.timetableScopeHosts,
            ),
        )
        assertEquals(
            Decision.BLOCK,
            ControlledWebViewContract.inspectNavigation(
                "https://cas.upc.edu.cn/cas/../admin",
                scopeHosts = ControlledWebViewContract.timetableScopeHosts,
            ),
        )
        assertEquals(
            Decision.BLOCK,
            ControlledWebViewContract.inspectNavigation(
                "https://libspace.upc.edu.cn/",
                scopeHosts = ControlledWebViewContract.timetableScopeHosts,
            ),
        )
        assertEquals(
            Decision.ALLOW,
            ControlledWebViewContract.inspectNavigation(
                "https://libspace.upc.edu.cn/libseat/#/login?token=abc",
                scopeHosts = ControlledWebViewContract.libraryScopeHosts,
            ),
        )
        assertEquals(
            Decision.BLOCK,
            ControlledWebViewContract.inspectNavigation(
                "https://libspace.upc.edu.cn/admin",
                scopeHosts = ControlledWebViewContract.libraryScopeHosts,
            ),
        )
        assertEquals(
            Decision.ALLOW,
            ControlledWebViewContract.inspectNavigation(
                "https://tyrzfw.chaoxing.com/auth3/upc/cas/init?refer=https%3A%2F%2Foffice.chaoxing.com",
                scopeHosts = ControlledWebViewContract.chaoxingScopeHosts,
            ),
        )
        assertEquals(
            Decision.ALLOW,
            ControlledWebViewContract.inspectNavigation(
                "https://passport2-api.chaoxing.com/api/v2/casreceive?thirdinfo=1&setmain=true",
                scopeHosts = ControlledWebViewContract.chaoxingScopeHosts,
            ),
        )
        assertEquals(
            Decision.BLOCK,
            ControlledWebViewContract.inspectNavigation(
                "https://office.chaoxing.com/front/third/apps/other/index",
                scopeHosts = ControlledWebViewContract.chaoxingScopeHosts,
            ),
        )
        assertEquals(
            Decision.BLOCK,
            ControlledWebViewContract.inspectEntryUrl("about:blank"),
        )
        assertEquals(
            Decision.ALLOW,
            ControlledWebViewContract.inspectEntryUrl(
                "https://cas.upc.edu.cn/cas/login?service=https%3A%2F%2Fapp.upc.edu.cn",
                scopeHosts = ControlledWebViewContract.timetableScopeHosts,
            ),
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

    @Test
    fun `allows jwxt card and shiguang scopes and upgrades exact http callbacks to https`() {
        assertEquals(
            Decision.ALLOW,
            ControlledWebViewContract.inspectNavigation(
                "https://jwxt.upc.edu.cn/jsxsd/kscj/cjcx_list",
                scopeHosts = ControlledWebViewContract.jwxtScopeHosts,
            ),
        )
        assertEquals(
            Decision.ALLOW,
            ControlledWebViewContract.inspectNavigation(
                "https://mb-xyk.upc.edu.cn/plat/?name=loginTransit",
                scopeHosts = ControlledWebViewContract.cardScopeHosts,
            ),
        )
        assertEquals(
            Decision.ALLOW,
            ControlledWebViewContract.inspectNavigation(
                "https://dekt.upc.edu.cn/actvMng/actvMngFront!jumpActvCampListPageForWeb.action",
                scopeHosts = ControlledWebViewContract.shiguangScopeHosts,
            ),
        )
        assertEquals(
            "https://jwxt.upc.edu.cn/sso.jsp?ticket=ST-1",
            ControlledWebViewContract.upgradeCallbackHttpToHttpsIfAllowed(
                "http://jwxt.upc.edu.cn/sso.jsp?ticket=ST-1",
                scopeHosts = ControlledWebViewContract.jwxtScopeHosts,
            ),
        )
        assertEquals(
            "https://dekt.upc.edu.cn/caslogin.jsp?ticket=ST-2",
            ControlledWebViewContract.upgradeCallbackHttpToHttpsIfAllowed(
                "http://dekt.upc.edu.cn/caslogin.jsp?ticket=ST-2",
                scopeHosts = ControlledWebViewContract.shiguangScopeHosts,
            ),
        )
        assertEquals(
            null,
            ControlledWebViewContract.upgradeCallbackHttpToHttpsIfAllowed(
                "http://jhzf.upc.edu.cn/test",
                scopeHosts = ControlledWebViewContract.cardScopeHosts,
            ),
        )
    }
}
