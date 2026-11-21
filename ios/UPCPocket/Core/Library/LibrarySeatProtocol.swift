import Foundation

/// Strict read-only protocol parsers for `libspace.upc.edu.cn` (`libseat`).
enum LibrarySeatProtocol: Sendable {
    static let baseURLString = "https://libspace.upc.edu.cn"

    static func formEncode(_ items: [(String, String)]) -> String {
        items.map { "\(percentEncode($0.0))=\(percentEncode($0.1))" }.joined(separator: "&")
    }

    static func extractTokenFromURL(_ rawURL: String) -> String? {
        UpcLibraryAuthenticator.tokenFromURL(rawURL)
    }

    static func parseToken(_ body: String) throws -> String {
        let root = try parseRootObject(body)
        try ensureBusinessSuccess(root)
        guard let data = root["data"] as? [String: Any],
              let token = stringValue(data["token"]),
              !token.isEmpty else {
            throw LibrarySeatError.message("登录响应缺少 token")
        }
        return token
    }

    static func parseUser(_ body: String) throws -> LibraryUser {
        let root = try parseRootObject(body)
        try ensureBusinessSuccess(root)
        guard let data = root["data"] as? [String: Any] else {
            throw LibrarySeatError.message("用户信息缺少 data")
        }
        let id = stringValue(data["id"]) ?? ""
        let username = stringValue(data["username"]) ?? ""
        let rawName = stringValue(data["name"]) ?? ""
        let name = rawName.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? username : rawName
        return LibraryUser(id: id, username: username, name: name)
    }

    static func parseFilters(_ body: String) throws -> LibraryFilters {
        let root = try parseRootObject(body)
        try ensureBusinessSuccess(root)
        guard let data = root["data"] as? [String: Any] else {
            throw LibrarySeatError.message("筛选条件缺少 data")
        }
        guard let rawDates = data["dates"] as? [Any] else {
            throw LibrarySeatError.message("学校未返回可预约日期")
        }
        let dates = rawDates.compactMap { $0 as? String }.filter { !$0.isEmpty }
        guard !dates.isEmpty else {
            throw LibrarySeatError.message("学校未返回可预约日期")
        }

        guard let rawBuildings = data["buildings"] as? [Any] else {
            throw LibrarySeatError.message("学校未返回馆区")
        }
        var buildings: [LibraryBuilding] = []
        for item in rawBuildings {
            if let tuple = item as? [Any], tuple.count >= 2 {
                let id = stringValue(tuple[0]) ?? ""
                let name = stringValue(tuple[1]) ?? ""
                guard !id.isEmpty, !name.isEmpty else {
                    throw LibrarySeatError.message("馆区字段不完整")
                }
                buildings.append(LibraryBuilding(id: id, name: name))
            } else if let obj = item as? [String: Any] {
                let id = stringValue(obj["id"]) ?? ""
                let name = stringValue(obj["name"]) ?? ""
                guard !id.isEmpty, !name.isEmpty else {
                    throw LibrarySeatError.message("馆区字段不完整")
                }
                buildings.append(LibraryBuilding(id: id, name: name))
            } else {
                throw LibrarySeatError.message("馆区字段格式异常")
            }
        }
        guard !buildings.isEmpty else {
            throw LibrarySeatError.message("学校未返回馆区")
        }
        return LibraryFilters(dates: dates, buildings: buildings)
    }

    static func parseFloors(_ body: String) throws -> [LibraryFloor] {
        let root = try parseRootObject(body)
        try ensureBusinessSuccess(root)
        guard let data = root["data"] as? [Any] else {
            throw LibrarySeatError.message("楼层响应缺少 data")
        }
        return try data.map { item in
            guard let obj = item as? [String: Any] else {
                throw LibrarySeatError.message("楼层字段格式异常")
            }
            guard let floorNum = strictInt(obj["floorNum"]) else {
                throw LibrarySeatError.message("缺少数字字段 floorNum")
            }
            let floorName = stringValue(obj["floorName"]) ?? ""
            return LibraryFloor(floorNum: floorNum, floorName: floorName)
        }
    }

    static func parseRooms(_ body: String, floorNamesByNum: [Int: String] = [:]) throws -> [LibraryRoom] {
        let root = try parseRootObject(body)
        try ensureBusinessSuccess(root)
        guard let data = root["data"] as? [Any] else {
            throw LibrarySeatError.message("房间响应缺少 data")
        }
        var seenIds: Set<String> = []
        var rooms: [LibraryRoom] = []
        for item in data {
            guard let obj = item as? [String: Any] else {
                throw LibrarySeatError.message("房间字段格式异常")
            }
            guard let floor = strictInt(obj["floor"]) else {
                throw LibrarySeatError.message("缺少数字字段 floor")
            }
            let roomId = firstString(obj, keys: ["roomId", "id"])
            let name = firstString(obj, keys: ["room", "roomName", "name"])
            guard !roomId.isEmpty, !name.isEmpty else { continue }
            guard !seenIds.contains(roomId) else { continue }
            seenIds.insert(roomId)

            let rawFloorName = stringValue(obj["floorName"]) ?? ""
            let resolvedFloorName = rawFloorName.isEmpty ? floorNamesByNum[floor] : rawFloorName
            rooms.append(
                LibraryRoom(
                    roomId: roomId,
                    name: name,
                    floor: floor,
                    floorName: resolvedFloorName,
                    totalSeats: strictInt(obj["totalSeats"]),
                    freeSeats: strictInt(obj["free"]),
                    reserved: strictInt(obj["reserved"]),
                    inUse: strictInt(obj["inUse"]),
                    away: strictInt(obj["away"]),
                    disabled: strictInt(obj["disableSeat"]),
                    maxHour: strictInt(obj["maxHour"])
                )
            )
        }
        return rooms
    }

    static func parseLayout(_ body: String) throws -> LibrarySeatLayout {
        let root = try parseRootObject(body)
        try ensureBusinessSuccess(root)
        guard let data = root["data"] as? [String: Any] else {
            throw LibrarySeatError.message("座位布局缺少 data")
        }
        guard let rows = strictInt(data["rows"]), let cols = strictInt(data["cols"]) else {
            throw LibrarySeatError.message("座位布局缺少行列维度")
        }
        guard rows > 0, cols > 0, rows <= 200, cols <= 200 else {
            throw LibrarySeatError.message("座位布局尺寸异常")
        }
        guard let layout = data["layout"] as? [String: Any] else {
            throw LibrarySeatError.message("座位布局缺少 layout")
        }

        var seats: [LibrarySeat] = []
        for (key, value) in layout {
            guard let obj = value as? [String: Any] else { continue }
            let numericKey = Int(key)
            let parts = key.split(separator: "_", maxSplits: 1)
            let row = strictInt(obj["row"])
                ?? numericKey.map { $0 / 1000 }
                ?? (parts.first.flatMap { Int($0) })
            let col = strictInt(obj["col"])
                ?? numericKey.map { $0 % 1000 }
                ?? (parts.count == 2 ? Int(parts[1]) : nil)
            guard let row, let col else { continue }
            let id = stringValue(obj["id"]) ?? ""
            guard !id.isEmpty else { continue }
            let typeText = stringValue(obj["type"]) ?? ""
            let statusText = stringValue(obj["status"]) ?? ""
            seats.append(
                LibrarySeat(
                    id: id,
                    name: stringValue(obj["name"]) ?? "",
                    row: row,
                    col: col,
                    type: typeText.isEmpty ? "seat" : typeText,
                    enabled: boolFlag(obj["enabled"]),
                    status: statusText.isEmpty ? "UNKNOWN" : statusText,
                    localOnly: boolFlag(obj["local"])
                )
            )
        }
        guard !seats.isEmpty else {
            throw LibrarySeatError.message("学校未返回座位布局")
        }
        seats.sort { ($0.row, $0.col, $0.id) < ($1.row, $1.col, $1.id) }
        return LibrarySeatLayout(rows: rows, cols: cols, seats: seats)
    }

    static func parseCurrentReservations(_ body: String) throws -> [LibraryReservation] {
        let root = try parseRootObject(body)
        try ensureBusinessSuccess(root)
        guard let dataVal = root["data"], !(dataVal is NSNull) else {
            return []
        }
        guard let arr = dataVal as? [Any] else {
            throw LibrarySeatError.message("当前预约格式异常：data 既不是数组也不是 null")
        }
        return try parseReservationArray(arr)
    }

    static func parseHistoryReservations(_ body: String) throws -> [LibraryReservation] {
        let root = try parseRootObject(body)
        try ensureBusinessSuccess(root)
        guard let data = root["data"] as? [String: Any] else {
            throw LibrarySeatError.message("历史预约缺少 data 对象")
        }
        guard let arr = data["reservations"] as? [Any] else {
            throw LibrarySeatError.message("历史预约缺少 reservations 数组")
        }
        return try parseReservationArray(arr)
    }

    private static func parseReservationArray(_ arr: [Any]) throws -> [LibraryReservation] {
        try arr.map { item in
            guard let obj = item as? [String: Any] else {
                throw LibrarySeatError.message("预约记录不是对象")
            }
            let id = firstString(obj, keys: ["id"])
            guard !id.isEmpty else { throw LibrarySeatError.message("预约记录缺少 id") }
            let date = firstString(obj, keys: ["date", "onDate"])
            guard !date.isEmpty else { throw LibrarySeatError.message("预约记录 \(id) 缺少日期") }
            let begin = firstString(obj, keys: ["begin"])
            guard !begin.isEmpty else { throw LibrarySeatError.message("预约记录 \(id) 缺少开始时间") }
            let end = firstString(obj, keys: ["end"])
            guard !end.isEmpty else { throw LibrarySeatError.message("预约记录 \(id) 缺少结束时间") }
            let location = firstString(obj, keys: ["location", "loc"])
            guard !location.isEmpty else { throw LibrarySeatError.message("预约记录 \(id) 缺少座位标识") }
            return LibraryReservation(
                id: id,
                date: date,
                begin: begin,
                end: end,
                location: location,
                status: firstString(obj, keys: ["stat", "status"])
            )
        }
    }

    private static func parseRootObject(_ body: String) throws -> [String: Any] {
        guard let data = body.data(using: .utf8),
              let root = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] else {
            throw LibrarySeatError.message("学校返回格式已变化，请稍后重试")
        }
        return root
    }

    private static func ensureBusinessSuccess(_ root: [String: Any]) throws {
        let code = leniencyInt(root["code"])
        if code == 12 {
            throw LibrarySeatError.sessionExpired("图书馆登录已失效，请重新验证")
        }
        if code == 20 {
            let raw = stringValue(root["message"]) ?? "学校拒绝了这次请求"
            throw LibrarySeatError.message(
                raw + "（本地判断：这是请求签名或手机时间不匹配，请先校准设备时间后重试）"
            )
        }

        if let num = root["status"] as? NSNumber, CFGetTypeID(num) == CFBooleanGetTypeID() {
            if !num.boolValue {
                let message = stringValue(root["message"]) ?? "学校返回操作失败"
                throw LibrarySeatError.message(message)
            }
            return
        }

        if let strStatus = root["status"] as? String {
            let lower = strStatus.lowercased()
            if lower == "fail" || lower == "error" {
                let message = stringValue(root["message"]) ?? "学校返回操作失败"
                throw LibrarySeatError.message(message)
            }
            return
        }

        if let code, code != 0 && code != 200 {
            let message = stringValue(root["message"]) ?? "学校返回错误（code=\(code)）"
            throw LibrarySeatError.message(message)
        }
        if root["success"] == nil && root["data"] == nil && root["status"] == nil {
            throw LibrarySeatError.message("学校返回格式已变化，请稍后重试")
        }
    }

    private static func firstString(_ obj: [String: Any], keys: [String]) -> String {
        for key in keys {
            if let text = stringValue(obj[key]), !text.isEmpty {
                return text
            }
        }
        return ""
    }

    private static func stringValue(_ raw: Any?) -> String? {
        if raw is NSNull || raw == nil { return nil }
        if let str = raw as? String { return str }
        if let num = raw as? NSNumber {
            if CFGetTypeID(num) == CFBooleanGetTypeID() {
                return num.boolValue ? "true" : "false"
            }
            return num.stringValue
        }
        return nil
    }

    private static func strictInt(_ raw: Any?) -> Int? {
        if let num = raw as? NSNumber, CFGetTypeID(num) != CFBooleanGetTypeID() {
            let d = num.doubleValue
            if d.rounded() == d && d >= Double(Int.min) && d <= Double(Int.max) {
                return num.intValue
            }
        }
        return nil
    }

    private static func leniencyInt(_ raw: Any?) -> Int? {
        if let val = strictInt(raw) { return val }
        if let str = raw as? String {
            return Int(str.trimmingCharacters(in: .whitespacesAndNewlines))
        }
        return nil
    }

    private static func boolFlag(_ raw: Any?) -> Bool {
        if let num = raw as? NSNumber {
            if CFGetTypeID(num) == CFBooleanGetTypeID() { return num.boolValue }
            return num.intValue == 1
        }
        if let str = raw as? String {
            return str == "1" || str.caseInsensitiveCompare("true") == .orderedSame
        }
        return false
    }

    private static func percentEncode(_ raw: String) -> String {
        let allowed = CharacterSet(
            charactersIn: "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~"
        )
        return raw.addingPercentEncoding(withAllowedCharacters: allowed) ?? raw
    }
}
