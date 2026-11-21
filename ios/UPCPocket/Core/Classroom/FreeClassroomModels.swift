import Foundation

enum FreeClassroomCampus: String, CaseIterable, Identifiable, Sendable {
    case tangdaowan
    case guzhenkou

    var id: String { rawValue }

    var apiLabel: String {
        switch self {
        case .tangdaowan: "唐岛湾校区"
        case .guzhenkou: "古镇口校区"
        }
    }

    var shortLabel: String {
        switch self {
        case .tangdaowan: "唐岛湾"
        case .guzhenkou: "古镇口"
        }
    }

    static func fromPreferred(_ campus: CampusPreference) -> FreeClassroomCampus {
        switch campus {
        case .guzhenkou: .guzhenkou
        case .tangdaowan, .all: .tangdaowan
        }
    }
}

enum ClassroomPeriodGroup: String, CaseIterable, Identifiable, Sendable {
    case morning
    case afternoon
    case evening

    var id: String { rawValue }

    var label: String {
        switch self {
        case .morning: "上午"
        case .afternoon: "下午"
        case .evening: "晚上"
        }
    }

    var periods: Set<Int> {
        switch self {
        case .morning: [1, 2, 3, 4, 5]
        case .afternoon: [6, 7, 8, 9]
        case .evening: [10, 11, 12, 13]
        }
    }

    var rangeLabel: String {
        let minP = periods.min() ?? 1
        let maxP = periods.max() ?? 1
        return "\(minP)–\(maxP)"
    }
}

enum ZhuoyueWingFilter: String, CaseIterable, Identifiable, Sendable {
    case all
    case wingA
    case wingB

    var id: String { rawValue }

    var label: String {
        switch self {
        case .all: "全部"
        case .wingA: "卓越 A"
        case .wingB: "卓越 B"
        }
    }

    func matches(_ room: FreeClassroomRoom) -> Bool {
        switch self {
        case .all:
            return true
        case .wingA:
            return room.building == "卓越教学楼" &&
                room.name.trimmingCharacters(in: .whitespacesAndNewlines)
                .range(of: "^卓越\\s*A\\s*\\d", options: .regularExpression) != nil
        case .wingB:
            return room.building == "卓越教学楼" &&
                room.name.trimmingCharacters(in: .whitespacesAndNewlines)
                .range(of: "^卓越\\s*B\\s*\\d", options: .regularExpression) != nil
        }
    }
}

enum GroupSelectionState: Equatable, Sendable {
    case none
    case partial
    case all
}

enum ClassroomPeriodHelpers: Sendable {
    static func groupSelectionState(selected: Set<Int>, group: ClassroomPeriodGroup) -> GroupSelectionState {
        let count = group.periods.filter { selected.contains($0) }.count
        if count == 0 { return .none }
        if count == group.periods.count { return .all }
        return .partial
    }

    static func toggleGroup(selected: Set<Int>, group: ClassroomPeriodGroup) -> Set<Int> {
        if groupSelectionState(selected: selected, group: group) == .all {
            return selected.subtracting(group.periods)
        }
        return selected.union(group.periods)
    }

    static func formatPeriods(_ periods: Set<Int>) -> String {
        guard !periods.isEmpty else { return "" }
        let sorted = periods.sorted()
        var ranges: [ClosedRange<Int>] = []
        var start = sorted[0]
        var prev = start

        for value in sorted.dropFirst() {
            if value == prev + 1 {
                prev = value
            } else {
                ranges.append(start...prev)
                start = value
                prev = value
            }
        }
        ranges.append(start...prev)

        return ranges.map { range in
            range.lowerBound == range.upperBound
                ? "\(range.lowerBound)"
                : "\(range.lowerBound)–\(range.upperBound)"
        }.joined(separator: "、")
    }
}

struct FreeClassroomQuery: Equatable, Sendable {
    let campus: FreeClassroomCampus
    let dateString: String
    let building: String
    let periods: Set<Int>

    var isValid: Bool {
        let trimmedBuilding = building.trimmingCharacters(in: .whitespacesAndNewlines)
        return !trimmedBuilding.isEmpty &&
            trimmedBuilding.count <= 100 &&
            !periods.isEmpty &&
            periods.allSatisfy { (1...13).contains($0) }
    }
}

struct FreeClassroomRoom: Equatable, Identifiable, Sendable {
    var id: String { code }
    let code: String
    let name: String
    let building: String
    let campus: String
    let capacity: Int?
    let available: Bool
}

struct BuildingRoomGroup: Equatable, Identifiable, Sendable {
    var id: String { building }
    let building: String
    let rooms: [FreeClassroomRoom]
    let freeCount: Int
    let totalCount: Int
}

struct FreeClassroomResult: Equatable, Sendable {
    let query: FreeClassroomQuery
    let buildings: [String]
    let rooms: [FreeClassroomRoom]
    let fetchedAtEpochMillis: Int64

    var availableRooms: [FreeClassroomRoom] {
        rooms.filter { $0.available }
    }

    func filterZhuoyueWing(_ filter: ZhuoyueWingFilter) -> FreeClassroomResult {
        guard query.campus == .guzhenkou, filter != .all else {
            return self
        }
        return FreeClassroomResult(
            query: query,
            buildings: buildings,
            rooms: rooms.filter { filter.matches($0) },
            fetchedAtEpochMillis: fetchedAtEpochMillis
        )
    }

    func displayGroups(onlyAvailable: Bool) -> [BuildingRoomGroup] {
        var order: [String] = []
        var buckets: [String: [FreeClassroomRoom]] = [:]
        for room in rooms {
            if buckets[room.building] == nil {
                order.append(room.building)
                buckets[room.building] = []
            }
            buckets[room.building]?.append(room)
        }

        return order.compactMap { building in
            guard let groupRooms = buckets[building] else { return nil }
            let shown = onlyAvailable ? groupRooms.filter { $0.available } : groupRooms
            guard !shown.isEmpty else { return nil }
            return BuildingRoomGroup(
                building: building,
                rooms: shown,
                freeCount: groupRooms.filter { $0.available }.count,
                totalCount: groupRooms.count
            )
        }
    }
}
