@testable import UPCPocket
import XCTest

final class CasLoginAndCaptchaTests: XCTestCase {
    private actor RequestLog {
        var requests: [CasHTTPRequest] = []

        func append(_ request: CasHTTPRequest) {
            requests.append(request)
        }

        func countPosts() -> Int {
            requests.filter { $0.method == "POST" }.count
        }
    }

    func testCaptchaDetectorDistinguishesInactivePlaceholdersFromActiveChallenges() {
        let benignHTML = """
        <html>
          <form><input type="hidden" name="captcha"></form>
          <!-- <input name="captcha"> -->
          <div id="captchaParent" style="display:none"><input name="captcha"></div>
          <script>
            const sel = "input[name='captcha']";
            // config.captcha = { id: 'commented' };
            config.captcha = null;
            config.captcha = false;
            config.captcha = undefined;
          </script>
        </html>
        """
        XCTAssertFalse(CasCaptchaChallengeDetector.inspect(benignHTML).active)

        let scriptConfig = CasCaptchaChallengeDetector.inspect(
            #"<script>config.captcha = { id: 'cap-fixture-1' };</script>"#
        )
        XCTAssertTrue(scriptConfig.active)
        XCTAssertEqual(scriptConfig.captchaId, "cap-fixture-1")

        XCTAssertTrue(CasCaptchaChallengeDetector.inspect("<script>config.captcha ||= false;</script>").active)
        XCTAssertTrue(CasCaptchaChallengeDetector.inspect("<form><input name='captcha'></form>").active)
        XCTAssertTrue(
            CasCaptchaChallengeDetector.inspect(
                "<div id='captchaParent' style='display:none;display:block'></div>"
            ).active
        )
    }

    func testSuccessfulCasLoginExportsOnlyAppCookies() async throws {
        let log = RequestLog()
        let formHTML = Self.validCasFormHTML(action: UpcTimetableAuthEndpoints.loginURLString)

        let authenticator = UpcTimetableAuthenticator { request in
            await log.append(request)
            let urlStr = request.url.absoluteString
            if request.method == "GET", urlStr.hasPrefix("https://cas.upc.edu.cn/cas/login") {
                return RawHTTPResponse(
                    statusCode: 200,
                    location: nil,
                    setCookieHeaders: ["JSESSIONID=cas-temp-1; Path=/cas; HttpOnly; Secure"],
                    body: formHTML,
                    contentType: "text/html"
                )
            }
            if request.method == "POST", urlStr.hasPrefix("https://cas.upc.edu.cn/cas/login") {
                XCTAssertTrue(request.headers["Cookie"]?.contains("JSESSIONID=cas-temp-1") ?? false)
                XCTAssertEqual(request.headers["Origin"], "https://cas.upc.edu.cn")
                return RawHTTPResponse(
                    statusCode: 302,
                    location: "https://app.upc.edu.cn/a_upc/api/cas/login?ticket=ST-fixture-123456&redirect=https%3A%2F%2Fapp.upc.edu.cn%2Fsite%2Fweekschedule%2Findex&from=wap",
                    setCookieHeaders: ["CASTGC=cas-tgt-secret; Path=/cas; HttpOnly; Secure"],
                    body: "",
                    contentType: nil
                )
            }
            if request.method == "GET", urlStr.hasPrefix("https://app.upc.edu.cn/a_upc/api/cas/login") {
                return RawHTTPResponse(
                    statusCode: 302,
                    location: UpcTimetableAuthEndpoints.pageURLString,
                    setCookieHeaders: ["eai-sess=app-session-xyz; Path=/; HttpOnly; Secure"],
                    body: "",
                    contentType: nil
                )
            }
            if request.method == "GET", urlStr == UpcTimetableAuthEndpoints.pageURLString {
                XCTAssertTrue(request.headers["Cookie"]?.contains("eai-sess=app-session-xyz") ?? false)
                XCTAssertFalse(request.headers["Cookie"]?.contains("CASTGC") ?? false)
                return RawHTTPResponse(statusCode: 200, location: nil, setCookieHeaders: [], body: "ok", contentType: "text/html")
            }
            if request.method == "GET", urlStr == UpcTimetableAuthEndpoints.indexURLString {
                return RawHTTPResponse(
                    statusCode: 200,
                    location: nil,
                    setCookieHeaders: [],
                    body: #"{"e":0,"m":"ok","d":{"termInfo":[{"year":"2026-2027","term":"1","startday":"2026-09-06","countweek":19}],"params":{"year":"2026-2027","term":"1","week":8},"weekday":1,"weekdays":["2026-10-25","2026-10-26","2026-10-27","2026-10-28","2026-10-29","2026-10-30","2026-10-31"]}}"#,
                    contentType: "application/json"
                )
            }
            XCTFail("Unexpected request: \(request.method) \(urlStr)")
            return RawHTTPResponse(statusCode: 500, location: nil, setCookieHeaders: [], body: "", contentType: nil)
        }

        let credential = SchoolLoginCredential(
            accountId: "acct0123456789ab",
            generation: 1,
            loginName: "2024010101",
            password: "fixture-password"
        )
        let result = await authenticator.authenticate(credential: credential)
        guard case let .success(cookies, _) = result else {
            return XCTFail("Expected success, got \(result)")
        }
        XCTAssertEqual(cookies, ["eai-sess": "app-session-xyz"])
        XCTAssertNil(cookies["CASTGC"])
        let postCount = await log.countPosts()
        XCTAssertEqual(postCount, 1)
    }

    func testActiveCaptchaStopsBeforePasswordSubmission() async {
        let log = RequestLog()
        let formHTML = Self.validCasFormHTML(action: UpcTimetableAuthEndpoints.loginURLString)
        let authenticator = UpcTimetableAuthenticator { request in
            await log.append(request)
            return RawHTTPResponse(
                statusCode: 200,
                location: nil,
                setCookieHeaders: [],
                body: "<script>config.captcha = { id: 'cap-2' };</script>\(formHTML)",
                contentType: "text/html"
            )
        }
        let credential = SchoolLoginCredential(
            accountId: "acct0123456789ab",
            generation: 1,
            loginName: "2024010101",
            password: "fixture-password"
        )
        let result = await authenticator.authenticate(credential: credential)
        guard case .interactionRequired = result else {
            return XCTFail("Expected interactionRequired, got \(result)")
        }
        let postCount = await log.countPosts()
        XCTAssertEqual(postCount, 0)
    }

    func testExplicitPasswordRejectionReturnsInvalidCredentialsWithoutRetry() async {
        let log = RequestLog()
        let formHTML = Self.validCasFormHTML(action: UpcTimetableAuthEndpoints.loginURLString)
        let authenticator = UpcTimetableAuthenticator { request in
            await log.append(request)
            if request.method == "GET" {
                return RawHTTPResponse(statusCode: 200, location: nil, setCookieHeaders: [], body: formHTML, contentType: "text/html")
            }
            return RawHTTPResponse(
                statusCode: 200,
                location: nil,
                setCookieHeaders: [],
                body: #"<div id="errorDiv">用户名或密码错误</div>\#(formHTML)"#,
                contentType: "text/html"
            )
        }
        let credential = SchoolLoginCredential(
            accountId: "acct0123456789ab",
            generation: 1,
            loginName: "2024010101",
            password: "wrong-password"
        )
        let result = await authenticator.authenticate(credential: credential)
        guard case .invalidCredentials = result else {
            return XCTFail("Expected invalidCredentials, got \(result)")
        }
        let postCount = await log.countPosts()
        XCTAssertEqual(postCount, 1)
    }

    func testRejectsForeignFormActionBeforePosting() async {
        let log = RequestLog()
        let service = UpcCasService(
            casHost: UpcTimetableAuthEndpoints.casHost,
            serviceURL: UpcTimetableAuthEndpoints.serviceDecoded,
            callbackHost: UpcTimetableAuthEndpoints.appHost,
            callbackPath: UpcTimetableAuthEndpoints.appCallbackPath,
            callbackParameterNames: ["ticket", "redirect", "from"]
        )
        let casLogin = UpcCasLogin(service: service) { request in
            await log.append(request)
            return RawHTTPResponse(
                statusCode: 200,
                location: nil,
                setCookieHeaders: [],
                body: Self.validCasFormHTML(action: "https://evil.example.com/cas/login"),
                contentType: "text/html"
            )
        }
        let credential = SchoolLoginCredential(
            accountId: "acct0123456789ab",
            generation: 1,
            loginName: "2024010101",
            password: "fixture-password"
        )
        let result = await casLogin.login(credential: credential, casURL: UpcTimetableAuthEndpoints.loginURL)
        guard case .upstreamError = result else {
            return XCTFail("Expected upstreamError, got \(result)")
        }
        let postCount = await log.countPosts()
        XCTAssertEqual(postCount, 0)
    }

    private static func validCasFormHTML(action: String) -> String {
        """
        <form id="fm1" action="\(action)" method="post">
          <input type="text" name="username" value="" />
          <input type="password" name="password" value="" />
          <input type="hidden" name="type" value="username_password" />
          <input type="hidden" name="execution" value="e1s1" />
          <input type="hidden" name="_eventId" value="submit" />
          <input type="submit" name="submit" value="登录" />
        </form>
        """
    }
}
