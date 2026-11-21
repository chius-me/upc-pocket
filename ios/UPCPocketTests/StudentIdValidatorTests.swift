@testable import UPCPocket
import XCTest

final class StudentIdValidatorTests: XCTestCase {
    func testAcceptsValidUndergraduateAndGraduateFormats() {
        let validIds = [
            "2024010101",
            "12345678",
            "1234567890123456",
            "S24010001",
            "Z24010001",
            "M24010001",
            "TS240100001",
            "BY2401000001"
        ]

        for candidate in validIds {
            XCTAssertTrue(
                StudentIdValidator.isValid(candidate),
                "Expected valid student ID: \(candidate)"
            )
        }
    }

    func testRejectsMalformedWrongCaseAndNonAsciiInputs() {
        let invalidIds = [
            "",
            "1234567",
            "12345678901234567",
            "s24010001",
            "ts240100001",
            "by2401000001",
            "S2401000",
            "TS24010000",
            "BY240100000",
            "TS240100001X",
            " 2024010101 ",
            "\u{0662}\u{0660}\u{0662}\u{0664}\u{0660}\u{0661}\u{0660}\u{0661}"
        ]

        for candidate in invalidIds {
            XCTAssertFalse(
                StudentIdValidator.isValid(candidate),
                "Expected invalid student ID: \(candidate)"
            )
        }
    }
}
