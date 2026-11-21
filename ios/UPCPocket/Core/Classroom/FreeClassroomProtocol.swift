import Foundation

enum FreeClassroomError: Error, LocalizedError, Sendable, Equatable {
    case invalidQuery
    case upstreamFailure
    case schemaChanged

    var errorDescription: String? {
        switch self {
        case .invalidQuery:
            "请选择教学楼并至少选择 1 个节次"
        case .upstreamFailure:
            "学校未返回有效查询结果，请稍后重试"
        case .schemaChanged:
            "学校返回格式已变化，请稍后重试"
        }
    }
}

enum FreeClassroomProtocol: Sendable {
    static let endpoint = URL(string: "https://app.upc.edu.cn/freeclass/wap/default/search-all")!
    static let maxResponseBytes = 2 * 1024 * 1024

    static func formItems(query: FreeClassroomQuery) throws -> [(String, String)] {
        guard query.isValid else {
            throw FreeClassroomError.invalidQuery
        }
        var items: [(String, String)] = [
            ("xq", query.campus.apiLabel),
            ("date", query.dateString),
            ("lh", query.building)
        ]
        for period in query.periods.sorted() {
            items.append(("jc[]", "\(period)"))
        }
        return items
    }

    private struct RootCodeEnvelope: Decodable {
        let e: Int
    }

    static func parse(
        body: String,
        query: FreeClassroomQuery,
        fetchedAtEpochMillis: Int64
    ) throws -> FreeClassroomResult {
        guard let data = body.data(using: .utf8),
              let root = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            throw FreeClassroomError.schemaChanged
        }

        guard let envelope = try? JSONDecoder().decode(RootCodeEnvelope.self, from: data),
              envelope.e == 0 else {
            throw FreeClassroomError.upstreamFailure
        }

        guard let d = root["d"] as? [String: Any] else {
            throw FreeClassroomError.schemaChanged
        }

        let allGrouped = try decodeGroupedRooms(d["all"])
        let freeGrouped = try decodeGroupedRooms(d["js"])
        if freeGrouped.keys.contains(where: { allGrouped[$0] == nil }) {
            throw FreeClassroomError.schemaChanged
        }

        guard let choices = d["listLh"] as? [[String: Any]] else {
            throw FreeClassroomError.schemaChanged
        }

        var buildings: [String] = []
        var seenBuildings = Set<String>()
        for choice in choices {
            guard let id = choice["id"] as? String, !id.isEmpty else {
                throw FreeClassroomError.schemaChanged
            }
            if seenBuildings.insert(id).inserted {
                buildings.append(id)
            }
        }
        if !buildings.contains("全部") {
            buildings.insert("全部", at: 0)
        }

        var freeCodes = Set<String>()
        for groupItems in freeGrouped.values {
            for item in groupItems {
                guard let code = item["code"] as? String, !code.isEmpty else {
                    throw FreeClassroomError.schemaChanged
                }
                freeCodes.insert(code)
            }
        }

        var rooms: [FreeClassroomRoom] = []
        var seenCodes = Set<String>()
        for groupName in allGrouped.keys.sorted() {
            guard let groupItems = allGrouped[groupName] else { continue }
            for item in groupItems {
                guard let code = item["code"] as? String, !code.isEmpty,
                      let name = item["name"] as? String, !name.isEmpty,
                      let campus = item["xqm"] as? String, !campus.isEmpty,
                      let building = item["jxlm"] as? String, !building.isEmpty else {
                    throw FreeClassroomError.schemaChanged
                }
                guard campus == query.campus.apiLabel,
                      building == groupName,
                      (query.building == "全部" || building == query.building) else {
                    throw FreeClassroomError.schemaChanged
                }
                guard seenCodes.insert(code).inserted else {
                    throw FreeClassroomError.schemaChanged
                }

                let capacity: Int? = if let strNum = item["num"] as? String, let parsed = Int(strNum), parsed > 0 {
                    parsed
                } else if let intNum = item["num"] as? Int, intNum > 0 {
                    intNum
                } else {
                    nil
                }

                rooms.append(
                    FreeClassroomRoom(
                        code: code,
                        name: name,
                        building: building,
                        campus: campus,
                        capacity: capacity,
                        available: freeCodes.contains(code)
                    )
                )
            }
        }

        guard freeCodes.isSubset(of: seenCodes) else {
            throw FreeClassroomError.schemaChanged
        }

        return FreeClassroomResult(
            query: query,
            buildings: buildings,
            rooms: rooms,
            fetchedAtEpochMillis: fetchedAtEpochMillis
        )
    }

    private static func decodeGroupedRooms(_ raw: Any?) throws -> [String: [[String: Any]]] {
        if let dict = raw as? [String: Any] {
            var output: [String: [[String: Any]]] = [:]
            for (key, value) in dict {
                guard let array = value as? [[String: Any]] else {
                    throw FreeClassroomError.schemaChanged
                }
                output[key] = array
            }
            return output
        }
        // PHP empty associative array serializes as [].
        if let array = raw as? [Any], array.isEmpty {
            return [:]
        }
        throw FreeClassroomError.schemaChanged
    }
}
