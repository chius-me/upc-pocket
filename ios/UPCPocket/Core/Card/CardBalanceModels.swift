import Foundation

struct CardBalanceSnapshot: Codable, Equatable, Sendable {
    let accountId: String
    let studentId: String
    let credentialGeneration: Int64
    let balanceCents: Int64
    let transitionCents: Int64
    let fetchedAtEpochMillis: Int64

    var balanceText: String {
        Self.formatCents(balanceCents)
    }

    var transitionText: String {
        Self.formatCents(transitionCents)
    }

    var totalBalanceText: String {
        Self.formatCents(balanceCents &+ transitionCents)
    }

    func sameOwner(accountId: String, studentId: String) -> Bool {
        self.accountId == accountId && self.studentId == studentId
    }

    static func formatCents(_ cents: Int64) -> String {
        let sign = cents < 0 ? "-" : ""
        let absCents: UInt64 = if cents == Int64.min {
            UInt64(Int64.max) + 1
        } else {
            UInt64(abs(cents))
        }
        let yuan = absCents / 100
        let fen = absCents % 100
        return String(format: "%@%llu.%02llu", sign, yuan, fen)
    }
}

struct CardBalanceUiState: Equatable, Sendable {
    var snapshot: CardBalanceSnapshot? = nil
    var isRefreshing: Bool = false
    var errorMessage: String? = nil
    var isHistoricalGeneration: Bool = false
}
