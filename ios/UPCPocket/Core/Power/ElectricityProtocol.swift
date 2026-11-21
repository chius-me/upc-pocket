import Foundation

enum ElectricityProtocolError: Error, LocalizedError, Sendable, Equatable {
    case authRequired
    case formatChanged(String)
    case roomNotFound

    var errorDescription: String? {
        switch self {
        case .authRequired:
            "学校电费页面要求先完成微信授权，当前会话不可用"
        case let .formatChanged(message):
            message
        case .roomNotFound:
            "没有找到该房间，请核对楼号与房间号"
        }
    }
}

struct ElectricityForm: Equatable, Sendable {
    let viewState: String
    let viewStateGenerator: String
    let eventValidation: String
    let buildings: [String]
}

struct ElectricityCandidate: Equatable, Hashable, Identifiable, Sendable {
    var id: String { "\(systemNo)|\(building)|\(room)" }
    let systemNo: String
    let building: String
    let room: String
}

struct ElectricityReading: Equatable, Sendable {
    let building: String
    let room: String
    let systemNo: String
    let kwhText: String
}

enum ElectricityOutcome: Equatable, Sendable {
    case success(ElectricityReading)
    case ambiguous([ElectricityCandidate])
}

enum ElectricityProtocol: Sendable {
    static let pageURL = URL(string: "http://dfcz.upc.edu.cn/ShiYou/Default.aspx?code=0")!
    static let postURL = URL(string: "http://dfcz.upc.edu.cn/ShiYou/Default.aspx")!
    static let maxResponseBytes = 1024 * 1024

    static func parseForm(html: String) throws -> ElectricityForm {
        if html.contains("open.weixin.qq.com") && !html.contains("id=\"form1\"") {
            throw ElectricityProtocolError.authRequired
        }
        guard let viewState = hiddenValue(in: html, name: "__VIEWSTATE"),
              let eventValidation = hiddenValue(in: html, name: "__EVENTVALIDATION"),
              html.contains("id=\"form1\"") else {
            throw ElectricityProtocolError.formatChanged("学校电费页面结构已变化，暂时无法查询")
        }
        let generator = hiddenValue(in: html, name: "__VIEWSTATEGENERATOR") ?? ""

        let optionMatches = matches(
            pattern: "<option\\s+value=\"([^\"]+)\"",
            in: html,
            options: [.caseInsensitive]
        )
        var seen = Set<String>()
        var buildings: [String] = []
        for match in optionMatches {
            let candidate = decodeEntities(match[1]).trimmingCharacters(in: .whitespacesAndNewlines)
            if !candidate.isEmpty && seen.insert(candidate).inserted {
                buildings.append(candidate)
            }
        }
        guard !buildings.isEmpty else {
            throw ElectricityProtocolError.formatChanged("学校电费页面没有返回楼号列表，请稍后再试")
        }

        return ElectricityForm(
            viewState: viewState,
            viewStateGenerator: generator,
            eventValidation: eventValidation,
            buildings: buildings
        )
    }

    static func parseResult(html: String, requestedRoom: String) throws -> ElectricityOutcome {
        let requested = requestedRoom.trimmingCharacters(in: .whitespacesAndNewlines)
        let grid = try parseGrid(in: html)
        let candidates = parseCandidates(in: html)

        if !candidates.isEmpty {
            let exact = candidates.filter {
                $0.room.trimmingCharacters(in: .whitespacesAndNewlines) == requested
            }
            let consistent = grid == nil ||
                grid!.room.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ||
                grid!.room.trimmingCharacters(in: .whitespacesAndNewlines) == requested

            if exact.count == 1, consistent, let hit = exact.first, let plotted = grid {
                return .success(
                    ElectricityReading(
                        building: hit.building,
                        room: hit.room,
                        systemNo: hit.systemNo,
                        kwhText: plotted.kwhText
                    )
                )
            }
            return .ambiguous(candidates)
        }

        if let grid {
            let gridRoom = grid.room.trimmingCharacters(in: .whitespacesAndNewlines)
            if !gridRoom.isEmpty && gridRoom != requested {
                return .ambiguous([
                    ElectricityCandidate(
                        systemNo: grid.systemNo,
                        building: grid.building,
                        room: grid.room
                    )
                ])
            }
            return .success(
                ElectricityReading(
                    building: grid.building,
                    room: gridRoom.isEmpty ? requested : grid.room,
                    systemNo: grid.systemNo,
                    kwhText: grid.kwhText
                )
            )
        }

        if html.contains("楼列表为空") {
            throw ElectricityProtocolError.formatChanged("学校电费页面没有可用楼号，请稍后再试")
        }
        throw ElectricityProtocolError.roomNotFound
    }

    static func parseCandidates(in html: String) -> [ElectricityCandidate] {
        guard let table = tableFragment(in: html, id: "yonghuGridView") else {
            return []
        }
        let rows = matches(
            pattern: "<tr[^>]*>(.*?)</tr>",
            in: table,
            options: [.caseInsensitive, .dotMatchesLineSeparators]
        )
        var result: [ElectricityCandidate] = []
        for row in rows {
            let rowCells = extractCells(in: row[1])
            guard rowCells.count >= 3 else { continue }
            if rowCells.contains(where: { $0.contains("系统编号") }) { continue }
            let systemNo = rowCells[0].trimmingCharacters(in: .whitespacesAndNewlines)
            let building = rowCells[1].trimmingCharacters(in: .whitespacesAndNewlines)
            let room = rowCells[2].trimmingCharacters(in: .whitespacesAndNewlines)
            guard !systemNo.isEmpty && !room.isEmpty else { continue }
            result.append(ElectricityCandidate(systemNo: systemNo, building: building, room: room))
        }
        return result
    }

    static func parseKwh(_ raw: String) throws -> String {
        let normalized = decodeEntities(raw)
            .replacingOccurrences(of: "\u{00A0}", with: " ")
            .trimmingCharacters(in: .whitespacesAndNewlines)
        let captured = matches(pattern: "^([0-9]+(?:\\.[0-9]+)?)\\s*度$", in: normalized, options: [])
        guard let numberText = captured.first?[1],
              Decimal(string: numberText) != nil else {
            throw ElectricityProtocolError.formatChanged("剩余电量字段格式已变化：\(normalized)")
        }
        return numberText
    }

    static func formItems(form: ElectricityForm, building: String, room: String) -> [(String, String)] {
        [
            ("__VIEWSTATE", form.viewState),
            ("__VIEWSTATEGENERATOR", form.viewStateGenerator),
            ("__EVENTVALIDATION", form.eventValidation),
            ("lou", building),
            ("yonghu", room),
            ("chaxun", "查询")
        ]
    }

    private static func parseGrid(in html: String) throws -> ElectricityReading? {
        guard let table = tableFragment(in: html, id: "yongdianGrid") else {
            return nil
        }
        let tableCells = extractCells(in: table)
        guard let labelIndex = tableCells.firstIndex(where: { $0.contains("当前剩余电量") }),
              labelIndex + 1 < tableCells.count else {
            throw ElectricityProtocolError.formatChanged("学校电费页面已变化，未能读到剩余电量")
        }
        let rawKwh = tableCells[labelIndex + 1]
        let kwhText = try parseKwh(rawKwh)
        let hidden = tableCells.dropFirst(labelIndex + 2).filter {
            !$0.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
        }
        return ElectricityReading(
            building: "",
            room: hidden.count > 1 ? hidden[1] : "",
            systemNo: hidden.first ?? "",
            kwhText: kwhText
        )
    }

    private static func hiddenValue(in html: String, name: String) -> String? {
        let escaped = NSRegularExpression.escapedPattern(for: name)
        let pattern = "name=\"\(escaped)\"[^>]*value=\"([^\"]*)\""
        guard let match = matches(pattern: pattern, in: html, options: [.caseInsensitive]).first else {
            return nil
        }
        return decodeEntities(match[1])
    }

    private static func tableFragment(in html: String, id: String) -> String? {
        let escaped = NSRegularExpression.escapedPattern(for: id)
        let pattern = "<table[^>]*id=\"\(escaped)\"[^>]*>"
        guard let regex = try? NSRegularExpression(pattern: pattern, options: [.caseInsensitive]) else {
            return nil
        }
        let nsHtml = html as NSString
        let range = NSRange(location: 0, length: nsHtml.length)
        guard let open = regex.firstMatch(in: html, options: [], range: range) else {
            return nil
        }
        let searchRange = NSRange(location: open.range.location, length: nsHtml.length - open.range.location)
        let closeRange = nsHtml.range(of: "</table>", options: [.caseInsensitive], range: searchRange)
        guard closeRange.location != NSNotFound else {
            return nil
        }
        return nsHtml.substring(with: NSRange(location: open.range.location, length: closeRange.location - open.range.location))
    }

    private static func extractCells(in fragment: String) -> [String] {
        matches(
            pattern: "<td[^>]*>(.*?)</td>",
            in: fragment,
            options: [.caseInsensitive, .dotMatchesLineSeparators]
        ).map { stripTags($0[1]) }
    }

    private static func stripTags(_ raw: String) -> String {
        let withoutTags = raw.replacingOccurrences(
            of: "<[^>]+>",
            with: " ",
            options: .regularExpression
        )
        return decodeEntities(withoutTags)
            .replacingOccurrences(of: "\u{00A0}", with: " ")
            .replacingOccurrences(of: "\\s+", with: " ", options: .regularExpression)
            .trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private static func decodeEntities(_ raw: String) -> String {
        raw.replacingOccurrences(of: "&nbsp;", with: " ")
            .replacingOccurrences(of: "&amp;", with: "&")
            .replacingOccurrences(of: "&lt;", with: "<")
            .replacingOccurrences(of: "&gt;", with: ">")
            .replacingOccurrences(of: "&quot;", with: "\"")
            .replacingOccurrences(of: "&#39;", with: "'")
    }

    private static func matches(
        pattern: String,
        in input: String,
        options: NSRegularExpression.Options
    ) -> [[String]] {
        guard let regex = try? NSRegularExpression(pattern: pattern, options: options) else {
            return []
        }
        let nsInput = input as NSString
        let fullRange = NSRange(location: 0, length: nsInput.length)
        return regex.matches(in: input, options: [], range: fullRange).map { result in
            (0..<result.numberOfRanges).map { index in
                let r = result.range(at: index)
                return r.location == NSNotFound ? "" : nsInput.substring(with: r)
            }
        }
    }
}
