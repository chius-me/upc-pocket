import Foundation

enum CardBalanceError: Error, LocalizedError, Sendable, Equatable {
    case invalidStudentId
    case schemaMismatch
    case accountChanged
    case custom(String)

    var errorDescription: String? {
        switch self {
        case .invalidStudentId:
            "请先配置包含有效本人学号的学校账号"
        case .schemaMismatch:
            "学校未返回有效的本人校园卡余额（状态、学号或金额校验失败）"
        case .accountChanged:
            "账号已变更，已放弃本次余额结果"
        case let .custom(message):
            message
        }
    }
}

enum CardBalanceProtocol: Sendable {
    static let endpoint = URL(string: "http://jhzf.upc.edu.cn:20081/wechat/callinterface/getCardInfo.html")!
    static let maxResponseBytes = 256 * 1024

    static func formItems(studentId: String) throws -> [(String, String)] {
        guard StudentIdValidator.isValid(studentId) else {
            throw CardBalanceError.invalidStudentId
        }
        return [
            ("sno", studentId),
            ("xxbh", "synjones"),
            ("idtype", "sno"),
            ("id", studentId)
        ]
    }

    static func parse(
        body: String,
        expectedStudentId: String,
        accountId: String,
        credentialGeneration: Int64,
        fetchedAtEpochMillis: Int64
    ) throws -> CardBalanceSnapshot {
        guard StudentIdValidator.isValid(expectedStudentId) else {
            throw CardBalanceError.invalidStudentId
        }
        guard let data = body.data(using: .utf8),
              let root = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let retcode = root["retcode"] as? String,
              retcode == "0",
              let cards = root["card"] as? [[String: Any]],
              cards.count == 1,
              let card = cards.first,
              let returnedSno = card["sno"] as? String,
              returnedSno == expectedStudentId,
              let balanceCents = parseCents(card["db_balance"]),
              let transitionCents = parseCents(card["unsettle_amount"]) else {
            throw CardBalanceError.schemaMismatch
        }

        return CardBalanceSnapshot(
            accountId: accountId,
            studentId: expectedStudentId,
            credentialGeneration: credentialGeneration,
            balanceCents: balanceCents,
            transitionCents: transitionCents,
            fetchedAtEpochMillis: fetchedAtEpochMillis
        )
    }

    private static func parseCents(_ raw: Any?) -> Int64? {
        guard let str = raw as? String,
              str.range(of: "^-?[0-9]+$", options: .regularExpression) != nil,
              let value = Int64(str) else {
            return nil
        }
        return value
    }
}
