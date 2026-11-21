import Foundation

enum TimetableParseResult<T: Sendable>: Sendable {
    case ok(T)
    case authRequired
    case upstreamError(String)
}

private enum StrictJSONValue: Decodable, Sendable {
    case object([String: StrictJSONValue])
    case array([StrictJSONValue])
    case string(String)
    case int(Int)
    case double(Double)
    case bool(Bool)
    case null

    init(from decoder: Decoder) throws {
        let container = try decoder.singleValueContainer()
        if container.decodeNil() {
            self = .null
        } else if let obj = try? container.decode([String: StrictJSONValue].self) {
            self = .object(obj)
        } else if let arr = try? container.decode([StrictJSONValue].self) {
            self = .array(arr)
        } else if let b = try? container.decode(Bool.self) {
            self = .bool(b)
        } else if let i = try? container.decode(Int.self) {
            self = .int(i)
        } else if let d = try? container.decode(Double.self) {
            self = .double(d)
        } else if let s = try? container.decode(String.self) {
            self = .string(s)
        } else {
            throw DecodingError.typeMismatch(
                StrictJSONValue.self,
                DecodingError.Context(codingPath: decoder.codingPath, debugDescription: "Unsupported JSON value")
            )
        }
    }

    var objectValue: [String: StrictJSONValue]? {
        if case let .object(dict) = self { return dict }
        return nil
    }

    var arrayValue: [StrictJSONValue]? {
        if case let .array(arr) = self { return arr }
        return nil
    }

    var textValue: String? {
        switch self {
        case let .string(s):
            let trimmed = s.trimmingCharacters(in: .whitespacesAndNewlines)
            return trimmed.isEmpty ? nil : trimmed
        case let .int(i):
            return String(i)
        case let .double(d):
            if d.isFinite && d.rounded(.towardZero) == d && d >= Double(Int.min) && d <= Double(Int.max) {
                return String(Int(d))
            }
            return String(d)
        case .bool, .null, .object, .array:
            return nil
        }
    }

    var integralIntValue: Int? {
        switch self {
        case let .int(i):
            return i
        case let .double(d):
            guard d.isFinite, d.rounded(.towardZero) == d,
                  d >= Double(Int.min), d <= Double(Int.max) else {
                return nil
            }
            return Int(d)
        case let .string(s):
            let trimmed = s.trimmingCharacters(in: .whitespacesAndNewlines)
            guard !trimmed.isEmpty,
                  !trimmed.caseInsensitiveEquals("true"),
                  !trimmed.caseInsensitiveEquals("false"),
                  let d = Double(trimmed),
                  d.isFinite, d.rounded(.towardZero) == d,
                  d >= Double(Int.min), d <= Double(Int.max) else {
                return nil
            }
            return Int(d)
        case .bool, .null, .object, .array:
            return nil
        }
    }

    var isNull: Bool {
        if case .null = self { return true }
        return false
    }
}

enum TimetableProtocol: Sendable {
    static let maxWeeks = 30
    static let maxPeriod = 14
    static let maxResponseBytes = 2 * 1024 * 1024

    private static let termLabels: [String: String] = [
        "1": "秋季",
        "2": "春季",
        "3": "夏季"
    ]

    static func parseIndex(_ raw: String) -> TimetableParseResult<TimetableIndex> {
        guard let root = decodeJSON(raw) else {
            return nonJSONFailure(raw)
        }
        guard let obj = root.objectValue else {
            return .upstreamError("课表索引根节点格式异常")
        }
        guard let code = obj["e"]?.integralIntValue else {
            return .upstreamError("课表索引响应缺少合法状态码 e")
        }
        if code == 10013 { return .authRequired }
        if code != 0 { return .upstreamError("课表索引服务返回错误码（\(code)）") }

        guard let data = obj["d"]?.objectValue,
              let rawTerms = data["termInfo"]?.arrayValue,
              !rawTerms.isEmpty else {
            return .upstreamError("课表索引缺少学期列表 termInfo")
        }

        var terms: [TimetableTermInfo] = []
        var seen: Set<String> = []
        for element in rawTerms {
            guard let item = element.objectValue,
                  let year = item["year"]?.textValue,
                  let term = item["term"]?.textValue,
                  let startDay = parseCalendarDate(item["startday"]?.textValue),
                  let totalWeeks = item["countweek"]?.integralIntValue,
                  (1...maxWeeks).contains(totalWeeks) else {
                continue
            }
            let key = "\(year)|\(term)"
            guard seen.insert(key).inserted else { continue }
            let termLabel = "\(year) \(termLabels[term] ?? "第\(term)学期")"
            terms.append(
                TimetableTermInfo(
                    year: year,
                    term: term,
                    startDayIso: startDay.isoString,
                    totalWeeks: totalWeeks,
                    serverId: item["id"]?.textValue,
                    label: termLabel
                )
            )
        }

        guard !terms.isEmpty else {
            return .upstreamError("未识别到任何有效学期配置")
        }

        let params = data["params"]?.objectValue
        let currentYear = params?["year"]?.textValue
        let currentTerm = params?["term"]?.textValue
        if let cy = currentYear, let ct = currentTerm,
           !terms.contains(where: { $0.year == cy && $0.term == ct }) {
            return .upstreamError("当前学期配置缺失或无法解析")
        }

        let currentWeek = params?["week"]?.integralIntValue.flatMap { (1...maxWeeks).contains($0) ? $0 : nil }
        let currentWeekdays = parseConsecutiveWeekDates(data["weekdays"])?.map(\.isoString)
        let currentWeekday = normalizeWeekday(data["weekday"])

        return .ok(
            TimetableIndex(
                terms: terms,
                currentYear: currentYear,
                currentTerm: currentTerm,
                currentWeek: currentWeek,
                currentWeekdaysIso: currentWeekdays,
                currentWeekday: currentWeekday
            )
        )
    }

    static func parseWeek(
        _ raw: String,
        term: TimetableTermInfo,
        requestedWeek: Int,
        fetchedAtEpochMillis: Int64 = 0
    ) -> TimetableParseResult<TimetableWeek> {
        guard (1...term.totalWeeks).contains(requestedWeek) else {
            return .upstreamError("请求教学周超出学期周数范围")
        }
        guard let termStart = parseCalendarDate(term.startDayIso) else {
            return .upstreamError("学期起始日期格式异常")
        }
        guard let root = decodeJSON(raw) else {
            return nonJSONFailure(raw)
        }
        guard let obj = root.objectValue else {
            return .upstreamError("周课表根节点格式异常")
        }
        guard let code = obj["e"]?.integralIntValue else {
            return .upstreamError("周课表缺少合法状态码 e")
        }
        if code == 10013 { return .authRequired }
        if code != 0 { return .upstreamError("周课表服务返回错误码（\(code)）") }

        guard let data = obj["d"]?.objectValue,
              let classes = data["classes"]?.arrayValue,
              classes.count == 7 else {
            return .upstreamError("周课表缺少完整的 7 天课程数据")
        }

        guard let dates = parseConsecutiveWeekDates(data["weekdays"]) else {
            return .upstreamError("周课表 weekdays 日期格式或连续性异常")
        }
        guard let expectedStart = termStart.addingDays((requestedWeek - 1) * 7),
              dates[0] == expectedStart else {
            return .upstreamError("返回日期与请求第 \(requestedWeek) 周不匹配")
        }

        var allMeetings: [CourseMeeting] = []
        var dayModels: [TimetableDay] = []

        for (dayIndex, dayElement) in classes.enumerated() {
            guard let dayDict = dayElement.objectValue else {
                return .upstreamError("周课表第 \(dayIndex + 1) 天数据结构异常")
            }
            let date = dates[dayIndex]
            let dateIso = date.isoString
            let weekday = date.isoWeekday
            var dayMeetings: [CourseMeeting] = []

            for (key, value) in dayDict {
                guard let cellStart = parseCellStart(key: key) else {
                    if key.hasPrefix("c_") {
                        return .upstreamError("周课表存在未知节次键 \(key)")
                    }
                    continue
                }
                if value.isNull { continue }
                guard let entries = value.arrayValue else {
                    return .upstreamError("节次键 \(key) 非课程数组")
                }
                if !(1...maxPeriod).contains(cellStart) {
                    if !entries.isEmpty {
                        return .upstreamError("节次 \(cellStart) 超出 1–14 范围")
                    }
                    continue
                }

                for entryValue in entries {
                    guard let entry = entryValue.objectValue else {
                        return .upstreamError("课程条目类型异常")
                    }
                    guard let courseName = entry["course_name"]?.textValue else {
                        return .upstreamError("课程条目缺少 course_name")
                    }
                    guard let rawPeriods = entry["lessArr"]?.arrayValue, !rawPeriods.isEmpty else {
                        return .upstreamError("课程条目缺少 lessArr")
                    }
                    var sourcePeriods: [Int] = []
                    for p in rawPeriods {
                        guard let periodInt = p.integralIntValue else {
                            return .upstreamError("课程 lessArr 包含非整数节次")
                        }
                        guard (1...maxPeriod).contains(periodInt) else {
                            return .upstreamError("课程节次超出 1–14 合法范围")
                        }
                        sourcePeriods.append(periodInt)
                    }

                    guard let weekLabel = entry["week"]?.textValue,
                          let parsedWeeks = parseWeekNumbers(weekLabel, totalWeeks: term.totalWeeks) else {
                        return .upstreamError("课程条目缺少或无法解析 week 标签")
                    }
                    guard parsedWeeks.contains(requestedWeek) else {
                        return .upstreamError("课程周次不包含当前请求的第 \(requestedWeek) 周")
                    }

                    let rawCourseTime = entry["course_time"]?.textValue
                    let distinctSorted = Array(Set(sourcePeriods)).sorted()
                    let groups = consecutiveGroups(distinctSorted)
                    let split = groups.count > 1

                    for (start, end) in groups {
                        let meeting = CourseMeeting(
                            courseName: courseName,
                            weekday: weekday,
                            startPeriod: start,
                            endPeriod: end,
                            periods: Array(start...end),
                            sourcePeriods: sourcePeriods,
                            weeks: parsedWeeks,
                            weekLabel: weekLabel,
                            dateIso: dateIso,
                            timeRangeLabel: TimetableSchedule.resolveTimeRange(
                                startPeriod: start,
                                endPeriod: end,
                                rawCourseTime: rawCourseTime
                            ),
                            teacher: entry["teacher"]?.textValue,
                            location: entry["location"]?.textValue,
                            courseId: entry["course_id"]?.textValue,
                            weekType: entry["week_type"]?.textValue,
                            lessons: entry["lessons"]?.textValue,
                            courseTime: rawCourseTime,
                            sectionsSource: split ? "lessArr_split_noncontiguous" : "lessArr",
                            credit: entry["credit"]?.textValue,
                            courseType: entry["course_type"]?.textValue,
                            courseHour: entry["course_hour"]?.textValue,
                            examType: entry["khfs"]?.textValue
                        )
                        allMeetings.append(meeting)
                        dayMeetings.append(meeting)
                    }
                }
            }

            dayMeetings.sort { $0.startPeriod < $1.startPeriod }
            dayModels.append(TimetableDay(weekday: weekday, dateIso: dateIso, meetings: dayMeetings))
        }

        return .ok(
            TimetableWeek(
                term: term,
                week: requestedWeek,
                datesIso: dates.map(\.isoString),
                days: dayModels,
                meetings: allMeetings,
                fetchedAtEpochMillis: fetchedAtEpochMillis,
                complete: true
            )
        )
    }

    private static func decodeJSON(_ raw: String) -> StrictJSONValue? {
        guard raw.utf8.count <= maxResponseBytes else { return nil }
        let trimmed = raw.trimmingCharacters(in: CharacterSet(charactersIn: "\u{FEFF}\u{200B}").union(.whitespacesAndNewlines))
        guard !trimmed.isEmpty, let data = trimmed.data(using: .utf8) else { return nil }
        return try? JSONDecoder().decode(StrictJSONValue.self, from: data)
    }

    private static func nonJSONFailure<T>(_ raw: String) -> TimetableParseResult<T> {
        if raw.utf8.count > maxResponseBytes {
            return .upstreamError("课表响应体积超过 2 MiB 上限")
        }
        let trimmed = raw.trimmingCharacters(in: CharacterSet(charactersIn: "\u{FEFF}\u{200B}").union(.whitespacesAndNewlines))
        if trimmed.hasPrefix("<") {
            let lower = trimmed.lowercased()
            if lower.contains("cas/login") || lower.contains("username_password") || lower.contains("execution") {
                return .authRequired
            }
            return .upstreamError("课表接口返回了非 JSON 网页内容")
        }
        return .upstreamError("课表响应不是合法 JSON")
    }

    private static func parseCellStart(key: String) -> Int? {
        guard key.range(of: "^c_(\\d{1,2})$", options: .regularExpression) != nil else { return nil }
        return Int(key.dropFirst(2))
    }

    private static func normalizeWeekday(_ value: StrictJSONValue?) -> Int? {
        guard let number = value?.integralIntValue else { return nil }
        if number == 0 { return 7 }
        if (1...7).contains(number) { return number }
        return nil
    }

    private struct CalendarDay: Equatable {
        let date: Date
        let isoString: String
        let isoWeekday: Int

        func addingDays(_ days: Int) -> CalendarDay? {
            var calendar = Calendar(identifier: .gregorian)
            calendar.timeZone = TimeZone(secondsFromGMT: 0)!
            guard let next = calendar.date(byAdding: .day, value: days, to: date) else { return nil }
            let comps = calendar.dateComponents([.year, .month, .day, .weekday], from: next)
            guard let y = comps.year, let m = comps.month, let d = comps.day, let wd = comps.weekday else {
                return nil
            }
            // Foundation Calendar weekday: 1=Sun, 2=Mon .. 7=Sat -> ISO 1=Mon .. 7=Sun
            let iso = wd == 1 ? 7 : wd - 1
            return CalendarDay(
                date: next,
                isoString: String(format: "%04d-%02d-%02d", y, m, d),
                isoWeekday: iso
            )
        }
    }

    private static func parseCalendarDate(_ raw: String?) -> CalendarDay? {
        guard let raw else { return nil }
        let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard trimmed.range(of: "^\\d{4}-\\d{1,2}-\\d{1,2}$", options: .regularExpression) != nil else {
            return nil
        }
        let parts = trimmed.split(separator: "-")
        guard parts.count == 3,
              let year = Int(parts[0]),
              let month = Int(parts[1]),
              let day = Int(parts[2]) else {
            return nil
        }
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(secondsFromGMT: 0)!
        let input = DateComponents(year: year, month: month, day: day, hour: 12)
        guard let date = calendar.date(from: input) else { return nil }
        let roundTrip = calendar.dateComponents([.year, .month, .day, .weekday], from: date)
        guard roundTrip.year == year, roundTrip.month == month, roundTrip.day == day,
              let wd = roundTrip.weekday else {
            return nil
        }
        let iso = wd == 1 ? 7 : wd - 1
        return CalendarDay(
            date: date,
            isoString: String(format: "%04d-%02d-%02d", year, month, day),
            isoWeekday: iso
        )
    }

    private static func parseConsecutiveWeekDates(_ value: StrictJSONValue?) -> [CalendarDay]? {
        guard let arr = value?.arrayValue, arr.count == 7 else { return nil }
        var days: [CalendarDay] = []
        for elem in arr {
            guard let parsed = parseCalendarDate(elem.textValue) else { return nil }
            days.append(parsed)
        }
        for i in 0..<6 {
            guard days[i].addingDays(1)?.isoString == days[i + 1].isoString else { return nil }
        }
        return days
    }

    private static func parseWeekNumbers(_ raw: String, totalWeeks: Int) -> [Int]? {
        let seps = CharacterSet(charactersIn: ",，、;；").union(.whitespacesAndNewlines)
        let tokens = raw.trimmingCharacters(in: .whitespacesAndNewlines)
            .components(separatedBy: seps)
            .filter { !$0.isEmpty }
        guard !tokens.isEmpty else { return nil }

        var result: Set<Int> = []
        let rangeRegex = try? NSRegularExpression(pattern: "^(\\d{1,2})\\s*[-~—－]\\s*(\\d{1,2})$")

        for original in tokens {
            var token = original.trimmingCharacters(in: .whitespacesAndNewlines)
            if token.hasSuffix("周") {
                token = String(token.dropLast()).trimmingCharacters(in: .whitespacesAndNewlines)
            }
            guard !token.isEmpty else { return nil }

            if let rangeRegex,
               let match = rangeRegex.firstMatch(in: token, range: NSRange(token.startIndex..<token.endIndex, in: token)),
               let r1 = Range(match.range(at: 1), in: token),
               let r2 = Range(match.range(at: 2), in: token),
               let start = Int(token[r1]),
               let end = Int(token[r2]) {
                guard start <= end else { return nil }
                for w in start...end where (1...totalWeeks).contains(w) {
                    result.insert(w)
                }
            } else if token.range(of: "^\\d{1,2}$", options: .regularExpression) != nil,
                      let value = Int(token) {
                if (1...totalWeeks).contains(value) {
                    result.insert(value)
                }
            } else {
                return nil
            }
        }
        return result.sorted()
    }

    private static func consecutiveGroups(_ periods: [Int]) -> [(Int, Int)] {
        let ordered = Array(Set(periods)).sorted()
        var groups: [(Int, Int)] = []
        for period in ordered {
            if let last = groups.last, period == last.1 + 1 {
                groups[groups.count - 1] = (last.0, period)
            } else {
                groups.append((period, period))
            }
        }
        return groups
    }
}

private extension String {
    func caseInsensitiveEquals(_ other: String) -> Bool {
        self.caseInsensitiveCompare(other) == .orderedSame
    }
}
