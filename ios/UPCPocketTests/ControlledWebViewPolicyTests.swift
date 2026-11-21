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
