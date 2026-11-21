@testable import UPCPocket
import XCTest

final class ElectricityProtocolTests: XCTestCase {
    private var tempFile: URL!

    override func setUpWithError() throws {
        let dir = FileManager.default.temporaryDirectory
            .appendingPathComponent("upc-ios-power-tests-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        tempFile = dir.appendingPathComponent("power-tangdaowan-v1.json")
    }

    override func tearDownWithError() throws {
        if let dir = tempFile?.deletingLastPathComponent() {
            try? FileManager.default.removeItem(at: dir)
        }
    }

    func testParsesFormAndPreventsFuzzySubstringMismatch() throws {
        let formHtml = """
        <html><body><form id="form1">
        <input type="hidden" name="__VIEWSTATE" value="VS123" />
        <input type="hidden" name="__VIEWSTATEGENERATOR" value="GEN456" />
        <input type="hidden" name="__EVENTVALIDATION" value="EV789" />
        <select name="lou">
            <option value="1号楼">1号楼</option>
            <option value="2号楼">2号楼</option>
        </select>
        </form></body></html>
        """

        let form = try ElectricityProtocol.parseForm(html: formHtml)
        XCTAssertEqual(form.viewState, "VS123")
        XCTAssertEqual(form.buildings, ["1号楼", "2号楼"])

        let ambiguousHtml = """
        <table id="yongdianGrid">
          <tr><td>当前剩余电量:</td><td>17.34度</td><td>SYS01</td><td>1101</td></tr>
        </table>
        <table id="yonghuGridView">
          <tr><td>系统编号</td><td>楼号</td><td>房间号</td></tr>
          <tr><td>SYS01</td><td>1号楼</td><td>1101</td></tr>
        </table>
        """

        let ambiguousOutcome = try ElectricityProtocol.parseResult(html: ambiguousHtml, requestedRoom: "101")
        guard case let .ambiguous(candidates) = ambiguousOutcome else {
            return XCTFail("Expected ambiguous outcome for fuzzy room 101 vs 1101")
        }
        XCTAssertEqual(candidates.count, 1)
        XCTAssertEqual(candidates.first?.room, "1101")

        let exactOutcome = try ElectricityProtocol.parseResult(html: ambiguousHtml, requestedRoom: "1101")
        guard case let .success(reading) = exactOutcome else {
            return XCTFail("Expected success for exact room 1101")
        }
        XCTAssertEqual(reading.kwhText, "17.34")
        XCTAssertEqual(reading.room, "1101")
    }

    func testPowerStoreLoadsBuildingsQueriesAndClears() async throws {
        let formHtml = """
        <form id="form1">
        <input type="hidden" name="__VIEWSTATE" value="VS1" />
        <input type="hidden" name="__VIEWSTATEGENERATOR" value="GEN1" />
        <input type="hidden" name="__EVENTVALIDATION" value="EV1" />
        <select name="lou"><option value="1号楼">1号楼</option></select>
        </form>
        """
        let resultHtml = """
        <table id="yongdianGrid">
          <tr><td>当前剩余电量:</td><td>42.50度</td><td>SYS99</td><td>205</td></tr>
        </table>
        """

        let store = PowerStore(
            fileURL: tempFile,
            fetchFormPage: { formHtml },
            postQueryForm: { _ in resultHtml },
            clock: { 123_456 }
        )

        let (buildingsResult, _) = await store.loadBuildings()
        XCTAssertEqual(try buildingsResult.get(), ["1号楼"])

        let (queryResult, stateAfterQuery) = await store.query(buildingInput: "1号楼", roomInput: "205")
        guard case .success = try queryResult.get() else {
            return XCTFail("Expected success outcome")
        }
        XCTAssertEqual(stateAfterQuery.lastReading?.kwhText, "42.50")

        let clearedState = await store.clearSaved()
        XCTAssertNil(clearedState.lastReading)
        XCTAssertEqual(clearedState.room, "")
    }
}
