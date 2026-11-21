@testable import UPCPocket
import XCTest

final class LibrarySeatProtocolAndAuthTests: XCTestCase {
    private let sampleAccountId = "acct_test_library_01"
    private let sampleStudentId = "22010101"

    func testLibseatSigningMatchesCrossPlatformTestVectors() throws {
        let key = try UpcLibrarySigning.configKey()
        XCTAssertEqual(key.count, 9)

        let uuid = "00000000-0000-4000-8000-000000000000"
        let ts: Int64 = 1_700_000_000_000
        XCTAssertEqual(
            try UpcLibrarySigning.sign(method: "GET", uuid: uuid, timestamp: ts, hmacKey: key),
            "d96340212c47c2f7c2c843bbf99e0a53f90d41fa8e0505c4ffc16c407f968897"
        )
        XCTAssertEqual(
            try UpcLibrarySigning.sign(method: "POST", uuid: uuid, timestamp: ts, hmacKey: key),
            "c62c4b092c8d71425cc29e479bba3fb4120a89d3fbeb35ebf97f7aa5bfb1a8e1"
        )
        let headers = try UpcLibrarySigning.signedHeaders(token: "tok-1", method: "GET", uuid: uuid, timestamp: ts, hmacKey: key)
        XCTAssertEqual(headers["Authorization"], "tok-1")
        XCTAssertEqual(headers["loginType"], "PC")
    }

    func testLibrarySeatProtocolParsesPayloadsAndErrors() throws {
        let filters = try LibrarySeatProtocol.parseFilters(
            #"{"status":"success","code":"0","data":{"dates":["2026-11-21"],"buildings":[["1","唐岛湾主馆"],{"id":"2","name":"南堂"}]}}"#
        )
        XCTAssertEqual(filters.dates, ["2026-11-21"])
        XCTAssertEqual(filters.buildings.count, 2)

        let floors = try LibrarySeatProtocol.parseFloors(
            #"{"status":"success","data":[{"floorNum":2,"floorName":"二楼"}]}"#
        )
        XCTAssertEqual(floors.first?.floorName, "二楼")

        let rooms = try LibrarySeatProtocol.parseRooms(
            #"{"status":"success","data":[{"roomId":"101","room":"二楼自习区","floor":2,"totalSeats":120},{"roomId":"102","room":"二楼报刊区","floor":2,"totalSeats":80,"free":25,"maxHour":4}]}"#,
            floorNamesByNum: [2: "二楼"]
        )
        XCTAssertEqual(rooms.count, 2)
        XCTAssertNil(rooms[0].freeSeats)
        XCTAssertEqual(rooms[0].remainingLabel, "座位统计待查询" == rooms[0].remainingLabel ? "impossible" : "共 120 座")
        XCTAssertEqual(rooms[1].remainingLabel, "余 25 / 共 80 座")
        XCTAssertEqual(rooms[1].maxDurationLabel, "学校单次最长 4 小时")

        let layout = try LibrarySeatProtocol.parseLayout(
            #"{"status":"success","data":{"rows":4,"cols":5,"layout":{"2003":{"id":"s1","name":"A01","type":"seat","enabled":"true","status":"FREE","local":"0"}}}}"#
        )
        XCTAssertEqual(layout.seats.first?.row, 2)
        XCTAssertEqual(layout.seats.first?.col, 3)
        XCTAssertEqual(layout.seats.first?.selectable, true)

        XCTAssertTrue(try LibrarySeatProtocol.parseCurrentReservations(#"{"status":"success","data":null}"#).isEmpty)

        let history = try LibrarySeatProtocol.parseHistoryReservations(
            #"{"status":"success","data":{"reservations":[{"id":"r1","date":"2026-11-20","begin":"08:00","end":"12:00","location":"二楼-A01","status":"COMPLETE"}]}}"#
        )
        XCTAssertEqual(history.first?.statusLabel, "已履约")

        XCTAssertThrowsError(try LibrarySeatProtocol.parseCurrentReservations(#"{"status":"success","data":{"bad":1}}"#))

        XCTAssertThrowsError(try LibrarySeatProtocol.parseFilters(#"{"code":12,"status":"fail"}"#)) { error in
            XCTAssertEqual(error as? LibrarySeatError, .sessionExpired("图书馆登录已失效，请重新验证"))
        }

        XCTAssertThrowsError(try LibrarySeatProtocol.parseFilters(#"{"code":"20","message":"请检查手机时间"}"#)) { error in
            XCTAssertTrue(error.localizedDescription.contains("校准设备时间"))
        }
    }

    func testChaoxingProtocolParsesIdentityRoomsDetailPlacesAndTimes() throws {
        let user = try ChaoxingProtocol.parseIdentity(
            #"<script>var userLoginInfo={"nested":{"a":1},"userInfo":{"sno":"22010101"}}||{};</script>"#
        )
        XCTAssertEqual(user.sno, "22010101")

        let rooms = try ChaoxingProtocol.parseSeatRooms(
            #"{"success":true,"data":{"totalRow":1,"totalPage":1,"seatRoomList":[{"id":12,"firstLevelName":"东区","secondLevelName":"二层","thirdLevelName":"A区","capacity":100,"curUsedCount":40,"seatSpecialTime":{"satStartTime":"08:00","satEndTime":"22:00"}}]}}"#,
            kind: .seat,
            date: "2026-11-21"
        )
        XCTAssertEqual(rooms.rooms.first?.remainingEstimate, 60)

        let detail = try ChaoxingProtocol.parseSeatRoomDetail(
            #"{"success":true,"data":{"seatRoom":{"id":12,"firstLevelName":"东区","secondLevelName":"二层","capacity":100,"isOpen":0,"seatSpecialTime":{"satStartTime":"08:00","satEndTime":"22:00"}},"seatConfig":{"timeUnit":30,"minReserveDuration":1.0,"reserveDuration":4.0},"seatAttributes":[{"seatNum":"01","isReserve":0},{"seatNum":"02","isReserve":1}]}}"#,
            date: "2026-11-21",
            expectedRoomId: 12
        )
        XCTAssertEqual(detail.disabledSeatNums, ["01"])
        XCTAssertEqual(detail.maxDurationMinutes, 240)

        let places = try ChaoxingProtocol.parseSeatPlaces(
            #"{"success":true,"data":[{"seatNum":"01","xleft":166.5,"ytop":75.25,"width":40.0,"height":40.0,"rotate":630.0}]}"#
        )
        XCTAssertEqual(places.first?.rotate ?? -1, 270.0, accuracy: 0.01)

        let used = try ChaoxingProtocol.parseUsedSeatNums(
            #"{"success":true,"data":{"seatReserves":[{"seatNum":"02"}]}}"#
        )
        XCTAssertEqual(used, ["02"])

        let times = try ChaoxingProtocol.parseUsedTimes(
            #"{"success":true,"data":[[1795226400000,1795240800000]]}"#
        )
        XCTAssertEqual(times.count, 1)
        XCTAssertTrue(times[0].endMinute > times[0].startMinute)

        XCTAssertThrowsError(try ChaoxingProtocol.parseSeatPlaces(
            #"{"success":true,"data":[{"seatNum":"01","xleft":10.0,"ytop":10.0,"width":20.0,"height":20.0},{"seatNum":"01","xleft":40.0,"ytop":10.0,"width":20.0,"height":20.0}]}"#
        ))
    }

    func testUpcLibraryAuthenticatorHappyMismatchAndCaptcha() async {
        let casURL = "https://cas.upc.edu.cn/cas/login?service=https%3A%2F%2Flibspace.upc.edu.cn%2Fremote%2Fstatic%2FcasAuth%2FgetServiceByVerifyTicket%2FcasLogin"
        let cbURL = "https://libspace.upc.edu.cn/remote/static/casAuth/getServiceByVerifyTicket/casLogin?ticket=ST-12345678"
        let counter = PostCounter()

        let auth = UpcLibraryAuthenticator { req in
            if req.method == "POST" { await counter.increment() }
            let url = req.url.absoluteString
            if url == UpcLibraryAuthEndpoints.entryURLString {
                return RawHTTPResponse(statusCode: 302, location: casURL, setCookieHeaders: [], body: "", contentType: nil)
            } else if url == casURL && req.method == "GET" {
                return RawHTTPResponse(statusCode: 200, location: nil, setCookieHeaders: [], body: Self.casFormHTML(actionURL: casURL), contentType: nil)
            } else if url == casURL && req.method == "POST" {
                return RawHTTPResponse(statusCode: 302, location: cbURL, setCookieHeaders: [], body: "", contentType: nil)
            } else if url == cbURL {
                return RawHTTPResponse(statusCode: 302, location: "https://libspace.upc.edu.cn/libseat/#/login?token=app-tok-9", setCookieHeaders: [], body: "", contentType: nil)
            } else if url.hasPrefix(UpcLibraryAuthEndpoints.ssoAuthURLString) {
                return RawHTTPResponse(statusCode: 200, location: nil, setCookieHeaders: [], body: #"{"status":"success","data":{"token":"seat-tok-9"}}"#, contentType: nil)
            } else if url.hasPrefix(UpcLibraryAuthEndpoints.userURLString) {
                return RawHTTPResponse(statusCode: 200, location: nil, setCookieHeaders: [], body: #"{"status":"success","data":{"id":"u1","username":"22010101","name":"测试同学"}}"#, contentType: nil)
            }
            XCTFail("Unexpected request: \(req.method) \(url)")
            return RawHTTPResponse(statusCode: 500, location: nil, setCookieHeaders: [], body: "", contentType: nil)
        }

        let res = await auth.authenticate(credential: sampleCredential(sampleStudentId))
        if case let .success(token, user) = res {
            XCTAssertEqual(token, "seat-tok-9")
            XCTAssertEqual(user.username, sampleStudentId)
            XCTAssertFalse(res.description.contains("seat-tok-9"))
        } else {
            XCTFail("Expected success, got \(res)")
        }
        let postCount = await counter.value
        XCTAssertEqual(postCount, 2)

        let captchaCounter = PostCounter()
        let captchaAuth = UpcLibraryAuthenticator { req in
            if req.method == "POST" { await captchaCounter.increment() }
            let url = req.url.absoluteString
            if url == UpcLibraryAuthEndpoints.entryURLString {
                return RawHTTPResponse(statusCode: 302, location: casURL, setCookieHeaders: [], body: "", contentType: nil)
            }
            return RawHTTPResponse(statusCode: 200, location: nil, setCookieHeaders: [], body: Self.casFormHTML(actionURL: casURL, captcha: true), contentType: nil)
        }
        let captchaRes = await captchaAuth.authenticate(credential: sampleCredential(sampleStudentId))
        if case let .failure(reason, _, _) = captchaRes {
            XCTAssertEqual(reason, .interactionRequired)
        } else {
            XCTFail("Expected interactionRequired")
        }
        let captchaPosts = await captchaCounter.value
        XCTAssertEqual(captchaPosts, 0)

        let mismatchAuth = UpcLibraryAuthenticator { req in
            let url = req.url.absoluteString
            if url == UpcLibraryAuthEndpoints.entryURLString {
                return RawHTTPResponse(statusCode: 302, location: casURL, setCookieHeaders: [], body: "", contentType: nil)
            } else if url == casURL && req.method == "GET" {
                return RawHTTPResponse(statusCode: 200, location: nil, setCookieHeaders: [], body: Self.casFormHTML(actionURL: casURL), contentType: nil)
            } else if url == casURL && req.method == "POST" {
                return RawHTTPResponse(statusCode: 302, location: cbURL, setCookieHeaders: [], body: "", contentType: nil)
            } else if url == cbURL {
                return RawHTTPResponse(statusCode: 302, location: "https://libspace.upc.edu.cn/libseat/#/login?token=app-tok-9", setCookieHeaders: [], body: "", contentType: nil)
            } else if url.hasPrefix(UpcLibraryAuthEndpoints.ssoAuthURLString) {
                return RawHTTPResponse(statusCode: 200, location: nil, setCookieHeaders: [], body: #"{"status":"success","data":{"token":"seat-tok-9"}}"#, contentType: nil)
            }
            return RawHTTPResponse(statusCode: 200, location: nil, setCookieHeaders: [], body: #"{"status":"success","data":{"id":"u2","username":"99999999","name":"他人"}}"#, contentType: nil)
        }
        let mismatchRes = await mismatchAuth.authenticate(credential: sampleCredential(sampleStudentId))
        if case let .failure(reason, _, _) = mismatchRes {
            XCTAssertEqual(reason, .identityMismatch)
        } else {
            XCTFail("Expected identityMismatch")
        }
    }

    func testUpcChaoxingAuthenticatorDomainCookiesAndForeignHopRejection() async {
        let jar = UpcCasCookieJar(allowedHosts: [
            UpcChaoxingAuthEndpoints.casHost,
            UpcChaoxingAuthEndpoints.officeHost,
            UpcChaoxingAuthEndpoints.chaoxingCookieDomain
        ])
        await jar.saveFromHeaders(
            requestURL: URL(string: "https://office.chaoxing.com/front/third/apps/seat/index")!,
            setCookieHeaders: [
                "fid=fid1; Domain=.chaoxing.com; Path=/; Secure",
                "JSESSIONID=js1; Path=/; Secure"
            ]
        )
        let header = await jar.cookieHeader(for: UpcChaoxingAuthEndpoints.seatEntryURL) ?? ""
        XCTAssertTrue(header.contains("fid=fid1"))
        XCTAssertTrue(header.contains("JSESSIONID=js1"))

        let initURL = "https://tyrzfw.chaoxing.com/auth3/upc/cas/init?refer=https%3A%2F%2Foffice.chaoxing.com%2Ffront%2Fapps%2Fseat%2Findex%3FfidEnc%3De32f8fbde51f9dba&mappId=4197453"
        let casURL = "https://cas.upc.edu.cn/cas/login?service=https%3A%2F%2Ftyrzfw.chaoxing.com%2Fauth3%2Fupc%2Fcas%2Findex"
        let cbURL = "https://tyrzfw.chaoxing.com/auth3/upc/cas/index?ticket=ST-chaoxing-123"
        let passportURL = "https://passport2-api.chaoxing.com/api/v2/casreceive?thirdinfo=abc&refer=https%3A%2F%2Foffice.chaoxing.com%2Ffront%2Fapps%2Fseat%2Findex%3FfidEnc%3De32f8fbde51f9dba&setmain=true"
        let officeRefer = "https://office.chaoxing.com/front/apps/seat/index?fidEnc=e32f8fbde51f9dba&mappId=4197453"
        let entryCalls = PostCounter()

        let auth = UpcChaoxingAuthenticator { req in
            let url = req.url.absoluteString
            if url == UpcChaoxingAuthEndpoints.seatEntryString {
                let count = await entryCalls.next()
                if count == 0 {
                    return RawHTTPResponse(statusCode: 303, location: initURL, setCookieHeaders: [], body: "", contentType: nil)
                }
                let cookie = req.headers["Cookie"] ?? ""
                XCTAssertTrue(cookie.contains("fid=cx-fid"))
                XCTAssertTrue(cookie.contains("JSESSIONID=cx-js"))
                XCTAssertFalse(cookie.contains("CASTGC"))
                return RawHTTPResponse(statusCode: 200, location: nil, setCookieHeaders: [], body: #"<script>var userLoginInfo={"userInfo":{"sno":"22010101"}};</script>"#, contentType: nil)
            } else if url == initURL {
                return RawHTTPResponse(statusCode: 200, location: nil, setCookieHeaders: [], body: "<script>window.location=\"\(casURL)\";</script>", contentType: nil)
            } else if url == casURL && req.method == "GET" {
                return RawHTTPResponse(statusCode: 200, location: nil, setCookieHeaders: [], body: Self.casFormHTML(actionURL: casURL), contentType: nil)
            } else if url == casURL && req.method == "POST" {
                return RawHTTPResponse(statusCode: 302, location: cbURL, setCookieHeaders: ["CASTGC=cas-secret; Path=/cas/; Secure"], body: "", contentType: nil)
            } else if url == cbURL {
                return RawHTTPResponse(statusCode: 200, location: nil, setCookieHeaders: [], body: "<script>window.location=\"\(passportURL)\";</script>", contentType: nil)
            } else if url == passportURL {
                return RawHTTPResponse(statusCode: 200, location: nil, setCookieHeaders: ["fid=cx-fid; Domain=.chaoxing.com; Path=/; Secure"], body: "<input type=\"hidden\" id=\"refer\" value=\"\(officeRefer)\">", contentType: nil)
            } else if url == officeRefer {
                return RawHTTPResponse(statusCode: 302, location: UpcChaoxingAuthEndpoints.seatEntryString, setCookieHeaders: ["JSESSIONID=cx-js; Path=/; Secure"], body: "", contentType: nil)
            }
            XCTFail("Unexpected URL: \(url)")
            return RawHTTPResponse(statusCode: 500, location: nil, setCookieHeaders: [], body: "", contentType: nil)
        }

        let res = await auth.authenticate(credential: sampleCredential(sampleStudentId))
        if case let .success(cookieHeader, _) = res {
            XCTAssertTrue(cookieHeader.contains("fid=cx-fid"))
            XCTAssertFalse(res.description.contains("cx-fid"))
        } else {
            XCTFail("Expected Chaoxing success, got \(res)")
        }

        let badAuth = UpcChaoxingAuthenticator { _ in
            RawHTTPResponse(statusCode: 303, location: "https://evil.example.com/auth3/upc/cas/init", setCookieHeaders: [], body: "", contentType: nil)
        }
        let badRes = await badAuth.authenticate(credential: sampleCredential(sampleStudentId))
        if case let .failure(reason, _, _) = badRes {
            XCTAssertEqual(reason, .protocolError)
        } else {
            XCTFail("Expected protocolError")
        }
    }

    func testLibrarySeatStoreDropsLateResultOnAccountSwitchAndRejectsStaleWebView() async {
        let holder = AccountHolder(
            state: SchoolAccountState(
                status: .savedUnverified,
                generation: 1,
                accountId: sampleAccountId,
                maskedLoginName: "22******01",
                isValidStudentId: true,
                savedAtEpochMillis: 1
            )
        )
        let latch = AsyncLatch()
        let casURL = "https://cas.upc.edu.cn/cas/login?service=https%3A%2F%2Flibspace.upc.edu.cn%2Fremote%2Fstatic%2FcasAuth%2FgetServiceByVerifyTicket%2FcasLogin"
        let cbURL = "https://libspace.upc.edu.cn/remote/static/casAuth/getServiceByVerifyTicket/casLogin?ticket=ST-99999999"

        let store = LibrarySeatStore(
            accountStateProvider: { await holder.get() },
            matchesAccountGeneration: { accId, gen in
                let s = await holder.get()
                return s.status == .savedUnverified && s.accountId == accId && s.generation == gen
            },
            loginCredentialProvider: {
                let s = await holder.get()
                guard s.status == .savedUnverified, let aid = s.accountId else { return nil }
                return SchoolLoginCredential(accountId: aid, generation: s.generation, loginName: "22010101", password: "synthetic-password")
            },
            transport: { req in
                let url = req.url.absoluteString
                if url == UpcLibraryAuthEndpoints.entryURLString {
                    return RawHTTPResponse(statusCode: 302, location: casURL, setCookieHeaders: [], body: "", contentType: nil)
                } else if url == casURL && req.method == "GET" {
                    return RawHTTPResponse(statusCode: 200, location: nil, setCookieHeaders: [], body: Self.casFormHTML(actionURL: casURL), contentType: nil)
                } else if url == casURL && req.method == "POST" {
                    return RawHTTPResponse(statusCode: 302, location: cbURL, setCookieHeaders: [], body: "", contentType: nil)
                } else if url == cbURL {
                    return RawHTTPResponse(statusCode: 302, location: "https://libspace.upc.edu.cn/libseat/#/login?token=app-t", setCookieHeaders: [], body: "", contentType: nil)
                } else if url.hasPrefix(UpcLibraryAuthEndpoints.ssoAuthURLString) {
                    return RawHTTPResponse(statusCode: 200, location: nil, setCookieHeaders: [], body: #"{"status":"success","data":{"token":"seat-t"}}"#, contentType: nil)
                } else if url.hasPrefix(UpcLibraryAuthEndpoints.userURLString) {
                    return RawHTTPResponse(statusCode: 200, location: nil, setCookieHeaders: [], body: #"{"status":"success","data":{"id":"1","username":"22010101","name":"A"}}"#, contentType: nil)
                } else if url.contains("/rest/v2/free/filters") {
                    await latch.markEnteredAndWait()
                    return RawHTTPResponse(statusCode: 200, location: nil, setCookieHeaders: [], body: #"{"status":"success","data":{"dates":["2026-11-21"],"buildings":[["1","主馆"]]}}"#, contentType: nil)
                }
                return RawHTTPResponse(statusCode: 200, location: nil, setCookieHeaders: [], body: #"{"status":"success","data":[]}"#, contentType: nil)
            }
        )

        let synced = await store.syncAccountStatusAndCampus(.tangdaowan)
        XCTAssertEqual(synced.activeTab, .libseat)

        let refreshTask = Task { await store.refreshActiveSystem() }
        await latch.waitUntilEntered()

        await holder.set(SchoolAccountState(status: .unset, generation: 2, accountId: nil, maskedLoginName: nil, isValidStudentId: false, savedAtEpochMillis: nil))
        let invalidated = await store.onAccountInvalidated(reason: .accountChangedOrCleared)
        await latch.release()
        _ = await refreshTask.value

        XCTAssertEqual(invalidated.connectionStatus, .unconfigured)

        let staleCtx = ControlledWebViewPolicy.SessionContext(
            accountId: sampleAccountId,
            generation: 1,
            entryURL: UpcLibraryAuthEndpoints.entryURL
        )
        let afterStale = await store.adoptLibseatWebViewToken(
            URL(string: "https://libspace.upc.edu.cn/libseat/#/login?token=app-t")!,
            sessionContext: staleCtx
        )
        XCTAssertEqual(afterStale.connectionStatus, .unconfigured)
        XCTAssertTrue(afterStale.libseatRooms.isEmpty)
    }

    private func sampleCredential(_ loginName: String) -> SchoolLoginCredential {
        SchoolLoginCredential(
            accountId: sampleAccountId,
            generation: 1,
            loginName: loginName,
            password: "synthetic-password"
        )
    }

    private static func casFormHTML(actionURL: String, captcha: Bool = false) -> String {
        let captchaHTML = captcha ? #"<img src="/cas/captcha.jpg" id="captchaImg"/>"# : ""
        return """
        <html><body>
        <form action="\(actionURL)" method="post">
          <input type="text" name="username"/>
          <input type="password" name="password"/>
          <input type="hidden" name="type" value="username_password"/>
          <input type="hidden" name="execution" value="e1s1"/>
          <input type="hidden" name="_eventId" value="submit"/>
          <input type="submit" name="submit" value="登录"/>
          \(captchaHTML)
        </form>
        </body></html>
        """
    }
}

private actor PostCounter {
    private(set) var value = 0
    func increment() { value += 1 }
    func next() -> Int {
        let current = value
        value += 1
        return current
    }
}

private actor AccountHolder {
    private var state: SchoolAccountState
    init(state: SchoolAccountState) { self.state = state }
    func get() -> SchoolAccountState { state }
    func set(_ next: SchoolAccountState) { state = next }
}

private actor AsyncLatch {
    private var entered = false
    private var released = false
    private var enteredCont: CheckedContinuation<Void, Never>?
    private var releaseCont: CheckedContinuation<Void, Never>?

    func markEnteredAndWait() async {
        entered = true
        enteredCont?.resume()
        enteredCont = nil
        if !released {
            await withCheckedContinuation { cont in
                releaseCont = cont
            }
        }
    }

    func waitUntilEntered() async {
        if !entered {
            await withCheckedContinuation { cont in
                enteredCont = cont
            }
        }
    }

    func release() {
        released = true
        releaseCont?.resume()
        releaseCont = nil
    }
}
