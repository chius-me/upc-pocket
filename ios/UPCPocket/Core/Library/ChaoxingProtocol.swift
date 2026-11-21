import Foundation

/// Strict read-only protocol parsers for `office.chaoxing.com` (`seat` and `seatengine`).
enum ChaoxingProtocol: Sendable {
    private static let weekdayKeys = ["mon", "tues", "wed", "thur", "fri", "sat", "sun"]

    static func parseIdentity(_ html: String) throws -> ChaoxingUser {
        guard let rawJSON = extractJsonObject(in: html, marker: "userLoginInfo") else {
            throw ChaoxingProtocolError.protocolError("登录页缺少 userLoginInfo")
        }
        guard let data = rawJSON.data(using: .utf8),
              let root = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] else {
            throw ChaoxingProtocolError.protocolError("userLoginInfo 不是 JSON")
        }
        guard let userInfo = root["userInfo"] as? [String: Any] else {
            throw ChaoxingProtocolError.protocolError("userLoginInfo 缺少 userInfo")
        }
        guard let sno = nonBlankString(userInfo["sno"]) else {
            throw ChaoxingProtocolError.protocolError("userInfo 缺少 sno")
        }
        return ChaoxingUser(sno: sno)
    }

    static func parseSeatRooms(_ body: String, kind: ChaoxingSeatKind, date: String) throws -> ChaoxingSeatRoomPage {
        let data = try objectData(body)
        guard let list = data["seatRoomList"] as? [Any] else {
            throw ChaoxingProtocolError.protocolError("座位响应缺少 seatRoomList")
        }
        let weekdayIndex = try shanghaiWeekdayIndex(for: date)
        var rooms: [ChaoxingSeatRoom] = []
        for item in list {
            guard let obj = item as? [String: Any],
                  let id = intField(obj["id"]) else {
                continue
            }
            let hours = openHours(container: obj["seatSpecialTime"] as? [String: Any], weekdayIndex: weekdayIndex)
                ?? openHours(container: obj["seatEngineSpecialTime"] as? [String: Any], weekdayIndex: weekdayIndex)
            guard let hours else { continue }
            let joinedName = [
                nonBlankString(obj["firstLevelName"]),
                nonBlankString(obj["secondLevelName"]),
                nonBlankString(obj["thirdLevelName"])
            ].compactMap { $0 }.joined(separator: "-")
            let name = joinedName.isEmpty ? "\(kind.campusLabel) 区域 \(id)" : joinedName
            rooms.append(
                ChaoxingSeatRoom(
                    id: id,
                    name: name,
                    floor: nonBlankString(obj["secondLevelName"]) ?? "",
                    capacity: intField(obj["capacity"]) ?? 0,
                    usedCount: intField(obj["curUsedCount"]),
                    startMinute: hours.0,
                    endMinute: hours.1
                )
            )
        }
        return ChaoxingSeatRoomPage(
            rooms: rooms,
            totalRow: intField(data["totalRow"]) ?? rooms.count,
            totalPage: intField(data["totalPage"]) ?? 1
        )
    }

    static func parseSeatRoomDetail(
        _ body: String,
        date: String,
        expectedRoomId: Int
    ) throws -> ChaoxingSeatRoomDetail {
        let data = try objectData(body)
        guard let config = data["seatConfig"] as? [String: Any] else {
            throw ChaoxingProtocolError.protocolError("房间详情缺少 seatConfig")
        }
        guard let room = data["seatRoom"] as? [String: Any] else {
            throw ChaoxingProtocolError.protocolError("房间详情缺少 seatRoom")
        }
        guard let timeUnit = intField(config["timeUnit"]), timeUnit > 0 else {
            throw ChaoxingProtocolError.protocolError("房间详情缺少 timeUnit")
        }
        let weekdayIndex = try shanghaiWeekdayIndex(for: date)
        guard let hours = openHours(container: room["seatSpecialTime"] as? [String: Any], weekdayIndex: weekdayIndex)
            ?? openHours(container: room["seatEngineSpecialTime"] as? [String: Any], weekdayIndex: weekdayIndex)
            ?? openHours(container: config["commonTimeConfig"] as? [String: Any], weekdayIndex: weekdayIndex) else {
            throw ChaoxingProtocolError.protocolError("房间详情缺少开放时段")
        }
        guard let roomId = intField(room["id"]) else {
            throw ChaoxingProtocolError.protocolError("房间详情缺少标识")
        }
        guard roomId == expectedRoomId else {
            throw ChaoxingProtocolError.protocolError("房间详情与请求区域不一致")
        }

        let rawAttrs = data["seatAttributes"] as? [Any]
        var disabledSeatNums: Set<String> = []
        if let rawAttrs {
            for item in rawAttrs {
                guard let attr = item as? [String: Any] else {
                    throw ChaoxingProtocolError.protocolError("座位属性无效")
                }
                guard let num = nonBlankString(attr["seatNum"]) else {
                    throw ChaoxingProtocolError.protocolError("属性缺少座号")
                }
                guard let allowed = intField(attr["isReserve"]) else {
                    throw ChaoxingProtocolError.protocolError("属性缺少可约标记")
                }
                guard allowed == 0 || allowed == 1 else {
                    throw ChaoxingProtocolError.protocolError("可约标记无效")
                }
                if allowed == 0 {
                    disabledSeatNums.insert(num)
                }
            }
        }

        let joinedName = [
            nonBlankString(room["firstLevelName"]),
            nonBlankString(room["secondLevelName"]),
            nonBlankString(room["thirdLevelName"])
        ].compactMap { $0 }.joined(separator: "-")
        let name = joinedName.isEmpty ? "区域 \(roomId)" : joinedName

        return ChaoxingSeatRoomDetail(
            roomId: roomId,
            name: name,
            capacity: intField(room["capacity"]) ?? 0,
            timeUnit: timeUnit,
            startMinute: hours.0,
            endMinute: hours.1,
            places: [],
            disabledSeatNums: disabledSeatNums,
            seatRulesKnown: rawAttrs != nil,
            minDurationMinutes: try durationMinutes(config: config, key: "minReserveDuration"),
            maxDurationMinutes: try durationMinutes(config: config, key: "reserveDuration"),
            closed: intField(room["isOpen"]) == 1
        )
    }

    static func parseSeatPlaces(_ body: String) throws -> [ChaoxingSeatPlace] {
        let data = try dataArray(body)
        var places: [ChaoxingSeatPlace] = []
        var seenSeatNums: Set<String> = []
        for item in data {
            guard let obj = item as? [String: Any],
                  let seatNum = nonBlankString(obj["seatNum"]),
                  let x = doubleField(obj["xleft"]),
                  let y = doubleField(obj["ytop"]),
                  let width = doubleField(obj["width"]),
                  let height = doubleField(obj["height"]),
                  width > 0, height > 0 else {
                continue
            }
            let rawRotate = doubleField(obj["rotate"]) ?? 0
            let mod = rawRotate.truncatingRemainder(dividingBy: 360)
            let rotate = (mod + 360).truncatingRemainder(dividingBy: 360)
            places.append(
                ChaoxingSeatPlace(
                    seatNum: seatNum,
                    x: x,
                    y: y,
                    width: width,
                    height: height,
                    rotate: rotate
                )
            )
            seenSeatNums.insert(seatNum)
        }
        guard places.count == data.count && seenSeatNums.count == places.count else {
            throw ChaoxingProtocolError.protocolError("座位图坐标无效")
        }
        return places
    }

    static func parseUsedSeatNums(_ body: String) throws -> Set<String> {
        let data = try objectData(body)
        guard let list = data["seatReserves"] as? [Any] else {
            throw ChaoxingProtocolError.protocolError("已用座位响应缺少 seatReserves")
        }
        var result: Set<String> = []
        for item in list {
            guard let obj = item as? [String: Any] else {
                throw ChaoxingProtocolError.protocolError("占用项无效")
            }
            guard let seatNum = nonBlankString(obj["seatNum"]) else {
                throw ChaoxingProtocolError.protocolError("占用项缺少座号")
            }
            result.insert(seatNum)
        }
        return result
    }

    static func parseUsedTimes(_ body: String) throws -> [ChaoxingTimeInterval] {
        let data = try dataArray(body)
        var intervals: [ChaoxingTimeInterval] = []
        for item in data {
            guard let pair = item as? [Any], pair.count >= 2,
                  let start = int64Field(pair[0]),
                  let end = int64Field(pair[1]) else {
                continue
            }
            let startMinute = shanghaiMinute(fromEpoch: start)
            let rawEndMinute = shanghaiMinute(fromEpoch: end)
            intervals.append(
                ChaoxingTimeInterval(
                    startMinute: startMinute,
                    endMinute: rawEndMinute <= startMinute ? rawEndMinute + 24 * 60 : rawEndMinute
                )
            )
        }
        return intervals
    }

    private static func durationMinutes(config: [String: Any], key: String) throws -> Int? {
        guard let raw = config[key], !(raw is NSNull) else { return nil }
        guard let num = raw as? NSNumber, CFGetTypeID(num) != CFBooleanGetTypeID() else {
            return nil
        }
        let d = num.doubleValue
        guard d.isFinite else {
            throw ChaoxingProtocolError.protocolError("时长规则无效")
        }
        let minutes = Int(d * 60)
        return minutes > 0 ? minutes : nil
    }

    private static func openHours(container: [String: Any]?, weekdayIndex: Int) -> (Int, Int)? {
        guard let container, (0..<weekdayKeys.count).contains(weekdayIndex) else { return nil }
        let key = weekdayKeys[weekdayIndex]
        guard let start = parseClockMinute(nonBlankString(container["\(key)StartTime"])),
              let end = parseClockMinute(nonBlankString(container["\(key)EndTime"])),
              end > start else {
            return nil
        }
        return (start, end)
    }

    private static func parseClockMinute(_ raw: String?) -> Int? {
        guard let raw else { return nil }
        let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard trimmed.range(of: #"^\d{1,2}:\d{2}$"#, options: .regularExpression) != nil else {
            return nil
        }
        let parts = trimmed.split(separator: ":")
        guard parts.count == 2,
              let h = Int(parts[0]),
              let m = Int(parts[1]),
              (0...23).contains(h),
              (0...59).contains(m) else {
            return nil
        }
        return h * 60 + m
    }

    private static func shanghaiWeekdayIndex(for rawDate: String) throws -> Int {
        guard rawDate.range(of: #"^\d{4}-\d{2}-\d{2}$"#, options: .regularExpression) != nil else {
            throw ChaoxingProtocolError.protocolError("日期格式不合法")
        }
        var calendar = Calendar(identifier: .gregorian)
        let shanghai = TimeZone(identifier: "Asia/Shanghai") ?? .current
        calendar.timeZone = shanghai

        let formatter = DateFormatter()
        formatter.calendar = calendar
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.timeZone = shanghai
        formatter.dateFormat = "yyyy-MM-dd"
        guard let date = formatter.date(from: rawDate) else {
            throw ChaoxingProtocolError.protocolError("日期格式不合法")
        }
        let gregorianWeekday = calendar.component(.weekday, from: date)
        // Calendar weekday: 1=Sun, 2=Mon..7=Sat -> convert to 0=Mon..6=Sun
        return (gregorianWeekday + 5) % 7
    }

    private static func shanghaiMinute(fromEpoch value: Int64) -> Int {
        let seconds: TimeInterval = value > 1_000_000_000_000 ? Double(value) / 1000.0 : Double(value)
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(identifier: "Asia/Shanghai") ?? .current
        let comps = calendar.dateComponents([.hour, .minute], from: Date(timeIntervalSince1970: seconds))
        return (comps.hour ?? 0) * 60 + (comps.minute ?? 0)
    }

    private static func envelope(_ body: String) throws -> [String: Any] {
        guard let data = body.data(using: .utf8),
              let root = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] else {
            throw ChaoxingProtocolError.protocolError("响应不是 JSON")
        }
        guard let successNum = root["success"] as? NSNumber,
              CFGetTypeID(successNum) == CFBooleanGetTypeID(),
              successNum.boolValue else {
            throw ChaoxingProtocolError.upstream("学校返回业务失败")
        }
        guard root["data"] != nil && !(root["data"] is NSNull) else {
            throw ChaoxingProtocolError.protocolError("响应缺少 data")
        }
        return root
    }

    private static func objectData(_ body: String) throws -> [String: Any] {
        let root = try envelope(body)
        guard let obj = root["data"] as? [String: Any] else {
            throw ChaoxingProtocolError.protocolError("响应缺少 data 对象")
        }
        return obj
    }

    private static func dataArray(_ body: String) throws -> [Any] {
        let root = try envelope(body)
        guard let arr = root["data"] as? [Any] else {
            throw ChaoxingProtocolError.protocolError("响应缺少 data 数组")
        }
        return arr
    }

    private static func nonBlankString(_ raw: Any?) -> String? {
        if let str = raw as? String {
            return str.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? nil : str
        }
        if let num = raw as? NSNumber, CFGetTypeID(num) != CFBooleanGetTypeID() {
            return num.stringValue
        }
        return nil
    }

    private static func intField(_ raw: Any?) -> Int? {
        if let num = raw as? NSNumber, CFGetTypeID(num) != CFBooleanGetTypeID() {
            return num.intValue
        }
        if let str = raw as? String {
            return Int(str.trimmingCharacters(in: .whitespacesAndNewlines))
        }
        return nil
    }

    private static func int64Field(_ raw: Any?) -> Int64? {
        if let num = raw as? NSNumber, CFGetTypeID(num) != CFBooleanGetTypeID() {
            return num.int64Value
        }
        if let str = raw as? String {
            return Int64(str.trimmingCharacters(in: .whitespacesAndNewlines))
        }
        return nil
    }

    private static func doubleField(_ raw: Any?) -> Double? {
        if let num = raw as? NSNumber, CFGetTypeID(num) != CFBooleanGetTypeID() {
            let d = num.doubleValue
            return d.isFinite ? d : nil
        }
        if let str = raw as? String, let d = Double(str.trimmingCharacters(in: .whitespacesAndNewlines)) {
            return d.isFinite ? d : nil
        }
        return nil
    }

    private static func extractJsonObject(in html: String, marker: String) -> String? {
        guard let markerRange = html.range(of: marker),
              let braceStart = html[markerRange.lowerBound...].firstIndex(of: "{") else {
            return nil
        }
        var depth = 0
        var inString = false
        var escaped = false
        var idx = braceStart
        while idx < html.endIndex {
            let c = html[idx]
            if inString {
                if escaped {
                    escaped = false
                } else if c == "\\" {
                    escaped = true
                } else if c == "\"" {
                    inString = false
                }
            } else {
                switch c {
                case "\"":
                    inString = true
                case "{":
                    depth += 1
                case "}":
                    depth -= 1
                    if depth == 0 {
                        return String(html[braceStart...idx])
                    }
                default:
                    break
                }
            }
            idx = html.index(after: idx)
        }
        return nil
    }
}
