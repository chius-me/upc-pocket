@testable import UPCPocket
import XCTest

final class SchoolAccountStoreTests: XCTestCase {
    private actor FakeCredentialStore: CredentialBackingStore {
        private var storage: [String: Data] = [:]

        func save(accountKey: String, data: Data) async throws {
            storage[accountKey] = data
        }

        func load(accountKey: String) async throws -> Data? {
            storage[accountKey]
        }

        func delete(accountKey: String) async throws {
            storage.removeValue(forKey: accountKey)
        }
    }

    private var tempDir: URL!

    override func setUpWithError() throws {
        tempDir = FileManager.default.temporaryDirectory
            .appendingPathComponent("upc-account-tests-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: tempDir, withIntermediateDirectories: true)
    }

    override func tearDownWithError() throws {
        if let tempDir {
            try? FileManager.default.removeItem(at: tempDir)
        }
    }

    func testSavePasswordChangeAccountSwitchAndClear() async throws {
        let backingStore = FakeCredentialStore()
        let store = SchoolAccountStore(
            directoryURL: tempDir,
            credentialStore: backingStore,
            clock: { 1_000 }
        )

        let initial = await store.restore()
        XCTAssertEqual(initial.status, .unset)
        XCTAssertEqual(initial.generation, 0)

        let (result1, state1) = await store.save(rawLoginName: " 2024010101 ", password: "test-password-1")
        XCTAssertEqual(result1, .success)
        XCTAssertEqual(state1.status, .savedUnverified)
        XCTAssertEqual(state1.generation, 1)
        XCTAssertNotNil(state1.accountId)
        XCTAssertTrue(state1.isValidStudentId)
        XCTAssertFalse(state1.maskedLoginName?.contains("2024010101") ?? false)

        let metadataURL = tempDir.appendingPathComponent("school_account_metadata.json")
        let metadataText = try String(contentsOf: metadataURL, encoding: .utf8)
        XCTAssertFalse(metadataText.contains("2024010101"))
        XCTAssertFalse(metadataText.contains("test-password-1"))

        // Password change on same account keeps accountId and increments generation.
        let (result2, state2) = await store.save(rawLoginName: "2024010101", password: "test-password-2")
        XCTAssertEqual(result2, .success)
        XCTAssertEqual(state2.accountId, state1.accountId)
        XCTAssertEqual(state2.generation, 2)

        // Switching account changes accountId and increments generation.
        let (result3, state3) = await store.save(rawLoginName: "TS240100001", password: "test-password-3")
        XCTAssertEqual(result3, .success)
        XCTAssertNotEqual(state3.accountId, state2.accountId)
        XCTAssertEqual(state3.generation, 3)
        XCTAssertTrue(state3.isValidStudentId)

        // Re-creating store restores state cleanly.
        let reopened = SchoolAccountStore(directoryURL: tempDir, credentialStore: backingStore)
        let restored = await reopened.restore()
        XCTAssertEqual(restored.status, .savedUnverified)
        XCTAssertEqual(restored.accountId, state3.accountId)
        XCTAssertEqual(restored.generation, 3)

        // Clearing resets to unset and advances generation.
        let (cleared, clearedState) = await reopened.clear()
        XCTAssertTrue(cleared)
        XCTAssertEqual(clearedState.status, .unset)
        XCTAssertNil(clearedState.accountId)
        XCTAssertEqual(clearedState.generation, 4)
    }

    func testMissingOrCorruptedKeychainDataTriggersRecoveryRequired() async throws {
        let backingStore = FakeCredentialStore()
        let store = SchoolAccountStore(directoryURL: tempDir, credentialStore: backingStore)
        _ = await store.save(rawLoginName: "2024010101", password: "secret")

        // Simulate missing/corrupted keychain entry.
        try await backingStore.save(accountKey: "primary_school_account_v1", data: Data([0x01, 0x02]))

        let reopened = SchoolAccountStore(directoryURL: tempDir, credentialStore: backingStore)
        let restored = await reopened.restore()
        XCTAssertEqual(restored.status, .recoveryRequired)
        XCTAssertNil(restored.accountId)
    }
}
