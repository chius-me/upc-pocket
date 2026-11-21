import Foundation

/// Official UPC class schedule per 《关于调整作息时间的通知》(2020-05-25).
/// Periods 1..12 map to fixed clock ranges; 13..14 return nil ("时间待确认").
enum TimetableSchedule: Sendable {
    struct ClockRange: Equatable, Sendable {
        let startMinute: Int
        let endMinute: Int

        var label: String {
            "\(Self.formatMinute(startMinute))–\(Self.formatMinute(endMinute))"
        }

        private static func formatMinute(_ minute: Int) -> String {
            String(format: "%02d:%02d", minute / 60, minute % 60)
        }
    }

    private static let officialPeriods: [Int: ClockRange] = [
        1: ClockRange(startMinute: 8 * 60, endMinute: 8 * 60 + 45),
        2: ClockRange(startMinute: 8 * 60 + 50, endMinute: 9 * 60 + 35),
        3: ClockRange(startMinute: 9 * 60 + 55, endMinute: 10 * 60 + 40),
        4: ClockRange(startMinute: 10 * 60 + 45, endMinute: 11 * 60 + 30),
        5: ClockRange(startMinute: 11 * 60 + 35, endMinute: 12 * 60 + 20),
        6: ClockRange(startMinute: 14 * 60, endMinute: 14 * 60 + 45),
        7: ClockRange(startMinute: 14 * 60 + 50, endMinute: 15 * 60 + 35),
        8: ClockRange(startMinute: 15 * 60 + 55, endMinute: 16 * 60 + 40),
        9: ClockRange(startMinute: 16 * 60 + 45, endMinute: 17 * 60 + 30),
        10: ClockRange(startMinute: 19 * 60, endMinute: 19 * 60 + 45),
        11: ClockRange(startMinute: 19 * 60 + 50, endMinute: 20 * 60 + 35),
        12: ClockRange(startMinute: 20 * 60 + 40, endMinute: 21 * 60 + 25)
    ]

    static func periodRange(_ period: Int) -> ClockRange? {
        officialPeriods[period]
    }

    static func resolveTimeRange(
        startPeriod: Int,
        endPeriod: Int,
        rawCourseTime: String? = nil
    ) -> String? {
        if let parsed = parseCourseTime(rawCourseTime) {
            return parsed.label
        }
        guard let start = periodRange(startPeriod),
              let end = periodRange(endPeriod),
              end.endMinute > start.startMinute else {
            return nil
        }
        return ClockRange(startMinute: start.startMinute, endMinute: end.endMinute).label
    }

    private static func parseCourseTime(_ rawCourseTime: String?) -> ClockRange? {
        let raw = rawCourseTime?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        guard !raw.isEmpty else { return nil }
        let pattern = "(\\d{1,2})\\s*[:：]\\s*(\\d{2})\\s*[~～\\-–—]\\s*(\\d{1,2})\\s*[:：]\\s*(\\d{2})"
        guard let regex = try? NSRegularExpression(pattern: pattern),
              let match = regex.firstMatch(in: raw, range: NSRange(raw.startIndex..<raw.endIndex, in: raw)),
              let r1 = Range(match.range(at: 1), in: raw),
              let r2 = Range(match.range(at: 2), in: raw),
              let r3 = Range(match.range(at: 3), in: raw),
              let r4 = Range(match.range(at: 4), in: raw),
              let sh = Int(raw[r1]),
              let sm = Int(raw[r2]),
              let eh = Int(raw[r3]),
              let em = Int(raw[r4]),
              (0...59).contains(sm),
              (0...59).contains(em) else {
            return nil
        }
        let start = sh * 60 + sm
        let end = eh * 60 + em
        guard (0..<(24 * 60)).contains(start),
              (1...(24 * 60)).contains(end),
              end > start else {
            return nil
        }
        return ClockRange(startMinute: start, endMinute: end)
    }
}
