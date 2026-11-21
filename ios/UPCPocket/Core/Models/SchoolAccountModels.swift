import Foundation

/// Valid UPC student ID formats per specs/account-contract.md:
/// - 8..16 ASCII digits
/// - S / Z / M + 8 ASCII digits (length 9, case-sensitive)
/// - TS + 9 ASCII digits (length 11, case-sensitive)
/// - BY + 10 ASCII digits (length 12, case-sensitive)
enum StudentIdValidator: Sendable {
    static func isValid(_ value: String) -> Bool {
        let bytes = Array(value.utf8)
        if (8...16).contains(bytes.count), allAsciiDigits(bytes[0...]) {
            return true
        }
        if bytes.count == 9, (bytes[0] == 0x53 || bytes[0] == 0x5A || bytes[0] == 0x4D) {
            return allAsciiDigits(bytes[1...])
        }
        if bytes.count == 11, bytes[0] == 0x54, bytes[1] == 0x53 {
            return allAsciiDigits(bytes[2...])
        }
        if bytes.count == 12, bytes[0] == 0x42, bytes[1] == 0x59 {
            return allAsciiDigits(bytes[2...])
        }
        return false
    }

    private static func allAsciiDigits(_ slice: ArraySlice<UInt8>) -> Bool {
        slice.allSatisfy { (0x30...0x39).contains($0) }
    }
}

enum AccountStatus: String, Codable, Sendable {
    case unset
    case savedUnverified
    case recoveryRequired

    var label: String {
        switch self {
        case .unset: "未保存"
        case .savedUnverified: "已保存（未验证）"
        case .recoveryRequired: "需要重新保存"
        }
    }
}

struct SchoolStudentIdentity: Equatable, Sendable {
    let accountId: String
    let studentId: String
    let generation: Int64
}

struct SchoolAccountState: Equatable, Sendable {
    var status: AccountStatus = .unset
    var generation: Int64 = 0
    var accountId: String? = nil
    var maskedLoginName: String? = nil
    var isValidStudentId: Bool = false
    var savedAtEpochMillis: Int64? = nil
}

enum AccountInvalidationReason: Sendable {
    case accountChangedOrCleared
    case credentialsUpdated
}

struct SchoolLoginCredential: Sendable, CustomStringConvertible {
    let accountId: String
    let generation: Int64
    let loginName: String
    let password: String

    var description: String {
        "SchoolLoginCredential(accountId: \(accountId), generation: \(generation), password: [REDACTED])"
    }
}

enum AccountWriteFailure: Error, Equatable, Sendable {
    case invalidLoginName
    case invalidPassword
    case keychainFailed
    case storageFailed
}

enum AccountWriteResult: Equatable, Sendable {
    case success
    case failure(AccountWriteFailure)
}

func maskLoginName(_ loginName: String) -> String {
    let trimmed = loginName.trimmingCharacters(in: .whitespacesAndNewlines)
    guard !trimmed.isEmpty else { return "" }
    if trimmed.count <= 4 {
        return String(repeating: "*", count: trimmed.count)
    }
    let prefix = trimmed.prefix(2)
    let suffix = trimmed.suffix(2)
    let stars = String(repeating: "*", count: max(3, trimmed.count - 4))
    return "\(prefix)\(stars)\(suffix)"
}
