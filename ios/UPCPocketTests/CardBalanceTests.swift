@testable import UPCPocket
import XCTest

final class CardBalanceTests: XCTestCase {
    private actor IdentityHolder {
        var identity: SchoolStudentIdentity?

        init(_ initial: SchoolStudentIdentity?) {
            self.identity = initial
        }

        func set(_ next: SchoolStudentIdentity?) {
            identity = next
        }

        func get() -> SchoolStudentIdentity? {
            identity
        }
    }

    private var tempFile: URL!

    override func setUpWithError() throws {
        let dir = FileManager.default.temporaryDirectory
            .appendingPathComponent("upc-ios-card-tests-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        tempFile = dir.appendingPathComponent("card-balance-v1.json")
    }

    override func tearDownWithError() throws {
        if let dir = tempFile?.deletingLastPathComponent() {
            try? FileManager.default.removeItem(at: dir)
        }
    }

    func testParsesExactCentsAndRejectsSnoMismatch() throws {
        let body = """
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
        """

        let snapshot = try CardBalanceProtocol.parse(
            body: body,
            expectedStudentId: "2024010101",
            accountId: "acct0001",
            credentialGeneration: 1,
            fetchedAtEpochMillis: 10_000
        )

        XCTAssertEqual(snapshot.balanceText, "12.34")
        XCTAssertEqual(snapshot.transitionText, "0.56")
        XCTAssertEqual(snapshot.totalBalanceText, "12.90")

        XCTAssertThrowsError(
            try CardBalanceProtocol.parse(
                body: body,
                expectedStudentId: "2024010102",
                accountId: "acct0002",
                credentialGeneration: 1,
                fetchedAtEpochMillis: 10_000
            )
        )
    }

    func testPersistsRestoresClearsAndDropsAfterAccountSwitch() async throws {
        let holder = IdentityHolder(
            SchoolStudentIdentity(accountId: "acct1111", studentId: "2024010101", generation: 1)
        )
        let store = CardBalanceStore(
            fileURL: tempFile,
            identityProvider: { await holder.get() },
            requester: { items in
                XCTAssertTrue(items.contains(where: { $0.0 == "sno" && $0.1 == "2024010101" }))
                return #"{"retcode":"0","card":[{"sno":"2024010101","db_balance":"5000","unsettle_amount":"250"}]}"#
            },
            clock: { 55_000 }
        )

        let (result, state) = await store.refresh()
        XCTAssertNoThrow(try result.get())
        XCTAssertEqual(state.snapshot?.totalBalanceText, "52.50")

        let reopened = CardBalanceStore(
            fileURL: tempFile,
            identityProvider: { await holder.get() }
        )
        let restored = await reopened.restore()
        XCTAssertEqual(restored.snapshot?.totalBalanceText, "52.50")

        let cleared = await reopened.clearCache()
        XCTAssertNil(cleared.snapshot)

        // Switching account drops cached data cleanly.
        _ = await reopened.refresh()
        await holder.set(SchoolStudentIdentity(accountId: "acct2222", studentId: "TS240100001", generation: 2))
        let afterSwitch = await reopened.clearForAccountChange()
        XCTAssertNil(afterSwitch.snapshot)
    }
}
