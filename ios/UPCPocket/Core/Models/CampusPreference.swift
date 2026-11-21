import Foundation

enum CampusPreference: String, CaseIterable, Codable, Sendable, Identifiable {
    case all
    case tangdaowan
    case guzhenkou

    var id: String { rawValue }

    var displayName: String {
        switch self {
        case .all: "全部校区"
        case .tangdaowan: "唐岛湾"
        case .guzhenkou: "古镇口"
        }
    }
}
