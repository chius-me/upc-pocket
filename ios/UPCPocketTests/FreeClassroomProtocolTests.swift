@testable import UPCPocket
import XCTest

final class FreeClassroomProtocolTests: XCTestCase {
    func testParsesJsIntersectionAndHandlesPhpEmptyArray() throws {
        let query = FreeClassroomQuery(
            campus: .guzhenkou,
            dateString: "2026-11-21",
            building: "全部",
            periods: [1, 2, 3]
        )

        let body = """
        {
          "e": 0,
          "d": {
            "listLh": [{"id": "全部"}, {"id": "卓越教学楼"}],
            "all": {
              "卓越教学楼": [
                {"code": "Z_A101", "name": "卓越A101", "xqm": "古镇口校区", "jxlm": "卓越教学楼", "num": "60"},
                {"code": "Z_B202", "name": "卓越 B 202", "xqm": "古镇口校区", "jxlm": "卓越教学楼", "num": "40"}
              ]
            },
            "js": {
              "卓越教学楼": [
                {"code": "Z_A101", "name": "卓越A101", "xqm": "古镇口校区", "jxlm": "卓越教学楼", "num": "60"}
              ]
            }
          }
        }
        """

        let result = try FreeClassroomProtocol.parse(body: body, query: query, fetchedAtEpochMillis: 999)
        XCTAssertEqual(result.rooms.count, 2)
        XCTAssertTrue(result.rooms.first(where: { $0.code == "Z_A101" })?.available == true)
        XCTAssertTrue(result.rooms.first(where: { $0.code == "Z_B202" })?.available == false)

        let wingA = result.filterZhuoyueWing(.wingA)
        XCTAssertEqual(wingA.rooms.map(\.code), ["Z_A101"])

        let wingB = result.filterZhuoyueWing(.wingB)
        XCTAssertEqual(wingB.rooms.map(\.code), ["Z_B202"])

        let emptyJsBody = """
        {
          "e": 0,
          "d": {
            "listLh": [{"id": "全部"}],
            "all": {
              "卓越教学楼": [
                {"code": "Z_A101", "name": "卓越A101", "xqm": "古镇口校区", "jxlm": "卓越教学楼", "num": "60"}
              ]
            },
            "js": []
          }
        }
        """
        let noneFree = try FreeClassroomProtocol.parse(body: emptyJsBody, query: query, fetchedAtEpochMillis: 999)
        XCTAssertTrue(noneFree.availableRooms.isEmpty)
    }

    func testFormatsPeriodsAndTogglesGroupsConsistently() {
        XCTAssertEqual(
            ClassroomPeriodHelpers.formatPeriods([1, 2, 3, 5, 10, 11, 12, 13]),
            "1–3、5、10–13"
        )
        let afterToggle = ClassroomPeriodHelpers.toggleGroup(selected: [1, 2], group: .morning)
        XCTAssertEqual(afterToggle, [1, 2, 3, 4, 5])
        let afterSecondToggle = ClassroomPeriodHelpers.toggleGroup(selected: afterToggle, group: .morning)
        XCTAssertTrue(afterSecondToggle.isEmpty)
    }
}
