@testable import UPCPocket
import XCTest

final class ControlledWebViewPolicyTests: XCTestCase {
    func testAllowsExactHttpsHostsAndBlocksHttpOrForeignHosts() {
        XCTAssertEqual(
            ControlledWebViewPolicy.inspectNavigation(
                rawURL: "https://cas.upc.edu.cn/cas/login?service=https%3A%2F%2Fapp.upc.edu.cn",
                method: "GET",
                isMainFrame: true
            ),
            .allow
        )
        XCTAssertEqual(
            ControlledWebViewPolicy.inspectNavigation(
                rawURL: "https://cas.upc.edu.cn/cas/login",
                method: "POST",
                isMainFrame: false
            ),
            .allow
        )
        XCTAssertEqual(
            ControlledWebViewPolicy.inspectNavigation(
                rawURL: "https://app.upc.edu.cn/site/weekschedule/index",
                method: "GET",
                isMainFrame: true
            ),
            .allow
        )

        XCTAssertEqual(
            ControlledWebViewPolicy.inspectNavigation(rawURL: "http://jhzf.upc.edu.cn:20081/"),
            .block
        )
        XCTAssertEqual(
            ControlledWebViewPolicy.inspectNavigation(rawURL: "http://cas.upc.edu.cn/cas/login"),
            .block
        )
        XCTAssertEqual(
            ControlledWebViewPolicy.inspectNavigation(rawURL: "https://evil.example.com/cas/login"),
            .block
        )
        XCTAssertEqual(
            ControlledWebViewPolicy.inspectNavigation(rawURL: "https://user:pass@cas.upc.edu.cn/cas/login"),
            .block
        )
        XCTAssertEqual(
            ControlledWebViewPolicy.inspectNavigation(rawURL: "https://cas.upc.edu.cn:8443/cas/login"),
            .block
        )
        XCTAssertEqual(
            ControlledWebViewPolicy.inspectNavigation(
                rawURL: "https://cas.upc.edu.cn/cas/themes/upc/login-mobile.html",
                method: "GET",
                isMainFrame: false,
                scopeHosts: ControlledWebViewPolicy.timetableScopeHosts
            ),
            .allow
        )
        XCTAssertEqual(
            ControlledWebViewPolicy.inspectNavigation(
                rawURL: "https://cas.upc.edu.cn/cas/../admin",
                scopeHosts: ControlledWebViewPolicy.timetableScopeHosts
            ),
            .block
        )
        XCTAssertEqual(
            ControlledWebViewPolicy.inspectNavigation(
                rawURL: "https://libspace.upc.edu.cn/",
                scopeHosts: ControlledWebViewPolicy.timetableScopeHosts
            ),
            .block
        )
        XCTAssertEqual(
            ControlledWebViewPolicy.inspectNavigation(
                rawURL: "https://libspace.upc.edu.cn/libseat/#/login?token=abc",
                scopeHosts: ControlledWebViewPolicy.libraryScopeHosts
            ),
            .allow
        )
        XCTAssertEqual(
            ControlledWebViewPolicy.inspectNavigation(
                rawURL: "https://libspace.upc.edu.cn/admin",
                scopeHosts: ControlledWebViewPolicy.libraryScopeHosts
            ),
            .block
        )
        XCTAssertEqual(
            ControlledWebViewPolicy.inspectNavigation(
                rawURL: "https://tyrzfw.chaoxing.com/auth3/upc/cas/init?refer=https%3A%2F%2Foffice.chaoxing.com",
                scopeHosts: ControlledWebViewPolicy.chaoxingScopeHosts
            ),
            .allow
        )
        XCTAssertEqual(
            ControlledWebViewPolicy.inspectNavigation(
                rawURL: "https://passport2-api.chaoxing.com/api/v2/casreceive?thirdinfo=1&setmain=true",
                scopeHosts: ControlledWebViewPolicy.chaoxingScopeHosts
            ),
            .allow
        )
        XCTAssertEqual(
            ControlledWebViewPolicy.inspectNavigation(
                rawURL: "https://office.chaoxing.com/front/third/apps/other/index",
                scopeHosts: ControlledWebViewPolicy.chaoxingScopeHosts
            ),
            .block
        )
        XCTAssertEqual(
            ControlledWebViewPolicy.inspectEntryURL(rawURL: "about:blank"),
            .block
        )
        XCTAssertEqual(
            ControlledWebViewPolicy.inspectEntryURL(
                rawURL: "https://cas.upc.edu.cn/cas/login?service=https%3A%2F%2Fapp.upc.edu.cn",
                scopeHosts: ControlledWebViewPolicy.timetableScopeHosts
            ),
            .allow
        )
    }

    func testSessionContextInvalidatesWhenGenerationChanges() {
        let state = SchoolAccountState(
            status: .savedUnverified,
            generation: 1,
            accountId: "0123456789abcdef"
        )
        let context = ControlledWebViewPolicy.SessionContext(
            accountId: state.accountId,
            generation: state.generation,
            entryURL: URL(string: "https://cas.upc.edu.cn/cas/login")!
        )

        XCTAssertTrue(context.isStillValid(for: state))

        var updated = state
        updated.generation = 2
        XCTAssertFalse(context.isStillValid(for: updated))
    }
}
