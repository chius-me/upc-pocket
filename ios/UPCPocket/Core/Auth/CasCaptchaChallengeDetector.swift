import Foundation

/// Deterministic pre-submit captcha and second-factor detector matching Android `CasCaptchaChallengeDetector`.
enum CasCaptchaChallengeDetector: Sendable {
    struct Result: Equatable, Sendable {
        let active: Bool
        let captchaId: String?
    }

    private static let voidTags: Set<String> = [
        "area", "base", "br", "col", "embed", "hr", "img",
        "input", "link", "meta", "param", "source", "track", "wbr"
    ]

    static func visibleMarkup(_ html: String) -> String {
        let withoutComments = replacePattern("<!--.*?-->", options: [.dotMatchesLineSeparators], in: html, with: "")
        return replacePattern(
            "<(script|style|textarea|template|title)\\b[^>]*>.*?</\\1\\s*>",
            options: [.caseInsensitive, .dotMatchesLineSeparators],
            in: withoutComments,
            with: ""
        )
    }

    static func inspect(_ html: String) -> Result {
        let clean = replacePattern("<!--.*?-->", options: [.dotMatchesLineSeparators], in: html, with: "")
        let scriptMatches = matches(
            "<script\\b[^>]*>(.*?)</script\\s*>",
            options: [.caseInsensitive, .dotMatchesLineSeparators],
            in: clean
        )
        var detectedCaptchaId: String?
        var scriptActive = false
        for match in scriptMatches {
            if let range = Range(match.range(at: 1), in: clean) {
                if let active = activeConfiguration(in: String(clean[range])) {
                    scriptActive = true
                    detectedCaptchaId = active.captchaId
                    break
                }
            }
        }
        let domActive = scriptActive ? false : hasVisibleCaptchaElement(in: visibleMarkup(clean))
        return Result(active: scriptActive || domActive, captchaId: detectedCaptchaId)
    }

    private static func activeConfiguration(in script: String) -> (source: String, captchaId: String?)? {
        let syntax = maskJavaScript(script, preserveStrings: false)
        let sourceWithoutComments = maskJavaScript(script, preserveStrings: true)

        let assignments = matches(
            "\\bconfig\\s*\\.\\s*captcha\\s*(\\|\\|=|&&=|\\?\\?=|<<=|>>>=|>>=|\\*\\*=|[+*/%&^|\\-]=|(?<![=!<>])=(?!=|>))",
            options: [.caseInsensitive],
            in: syntax
        )
        let inactivePattern = "(?i)^(null|false|undefined)\\b"
        let syntaxChars = Array(syntax)
        let cleanChars = Array(sourceWithoutComments)

        for assignment in assignments {
            guard let opRange = Range(assignment.range(at: 1), in: syntax),
                  let fullRange = Range(assignment.range, in: syntax) else {
                continue
            }
            let op = String(syntax[opRange])
            if op != "=" {
                return ("active", nil)
            }

            var valueIdx = syntax.distance(from: syntax.startIndex, to: fullRange.upperBound)
            while valueIdx < syntaxChars.count && syntaxChars[valueIdx].isWhitespace {
                valueIdx += 1
            }

            if valueIdx < syntaxChars.count && syntaxChars[valueIdx] == "{" {
                if let endIdx = matchingObjectEnd(in: syntaxChars, start: valueIdx) {
                    let objectSource = String(cleanChars[valueIdx...endIdx])
                    let idMatch = matches(
                        "(?:['\"]?id['\"]?)\\s*:\\s*['\"]([^'\"]+)['\"]",
                        options: [.caseInsensitive, .dotMatchesLineSeparators],
                        in: objectSource
                    ).first
                    let capId = idMatch.flatMap { Range($0.range(at: 1), in: objectSource).map { String(objectSource[$0]) } }
                    return (objectSource, capId)
                }
            }

            let remainder = valueIdx < syntaxChars.count ? String(syntaxChars[valueIdx...]) : ""
            if let inactiveMatch = matches(inactivePattern, in: remainder).first,
               let r = Range(inactiveMatch.range, in: remainder) {
                let consumed = remainder.distance(from: remainder.startIndex, to: r.upperBound)
                var trailing = valueIdx + consumed
                while trailing < syntaxChars.count && syntaxChars[trailing].isWhitespace {
                    trailing += 1
                }
                if trailing == syntaxChars.count || syntaxChars[trailing] == ";" {
                    continue
                }
            }

            return ("active", nil)
        }
        return nil
    }

    private static func matchingObjectEnd(in chars: [Character], start: Int) -> Int? {
        var depth = 0
        for idx in start..<chars.count {
            if chars[idx] == "{" {
                depth += 1
            } else if chars[idx] == "}" {
                depth -= 1
                if depth == 0 { return idx }
            }
        }
        return nil
    }

    private static func maskJavaScript(_ source: String, preserveStrings: Bool) -> String {
        var chars = Array(source)
        var mode = 0 // 0=code, 1=single, 2=double, 3=template, 4=line-comment, 5=block-comment
        var escaped = false
        var index = 0

        func hide(_ at: Int) {
            if chars[at] != "\n" && chars[at] != "\r" {
                chars[at] = " "
            }
        }

        while index < chars.count {
            let current = chars[index]
            let next: Character? = (index + 1 < chars.count) ? chars[index + 1] : nil
            switch mode {
            case 0:
                if current == "/" && next == "/" {
                    hide(index)
                    hide(index + 1)
                    mode = 4
                    index += 2
                    continue
                } else if current == "/" && next == "*" {
                    hide(index)
                    hide(index + 1)
                    mode = 5
                    index += 2
                    continue
                } else if current == "'" || current == "\"" || current == "`" {
                    mode = current == "'" ? 1 : (current == "\"" ? 2 : 3)
                    if !preserveStrings { hide(index) }
                }
            case 1, 2, 3:
                if !preserveStrings { hide(index) }
                if escaped {
                    escaped = false
                } else if current == "\\" {
                    escaped = true
                } else if (mode == 1 && current == "'") || (mode == 2 && current == "\"") || (mode == 3 && current == "`") {
                    mode = 0
                }
            case 4:
                if current == "\n" || current == "\r" {
                    mode = 0
                } else {
                    hide(index)
                }
            case 5:
                hide(index)
                if current == "*" && next == "/" {
                    hide(index + 1)
                    mode = 0
                    index += 1
                }
            default:
                break
            }
            index += 1
        }
        return String(chars)
    }

    private static func hasVisibleCaptchaElement(in markup: String) -> Bool {
        var ancestors: [(name: String, hidden: Bool)] = []
        let tags = matches("<\\s*(/?)\\s*([A-Za-z][A-Za-z0-9:-]*)\\b([^>]*)>", in: markup)

        for tag in tags {
            guard let closingRange = Range(tag.range(at: 1), in: markup),
                  let nameRange = Range(tag.range(at: 2), in: markup),
                  let attrsRange = Range(tag.range(at: 3), in: markup) else {
                continue
            }
            let closing = !markup[closingRange].isEmpty
            let name = markup[nameRange].lowercased()
            if closing {
                if let idx = ancestors.lastIndex(where: { $0.name == name }) {
                    ancestors.removeSubrange(idx..<ancestors.endIndex)
                }
                continue
            }

            let rawAttrs = String(markup[attrsRange])
            let attributes = parseAttributes(rawAttrs)
            let parentHidden = ancestors.last?.hidden == true
            let style = attributes["style"] ?? ""
            let displays = styleValues(style: style, property: "display")
            let visibilities = styleValues(style: style, property: "visibility")
            let display = displays.last
            let visibility = visibilities.last
            let displayAmbiguous = displays.count > 1
            let visibilityAmbiguous = visibilities.count > 1

            let selfHidden = attributes.keys.contains("hidden") ||
                (!displayAmbiguous && display?.caseInsensitiveCompare("none") == .orderedSame) ||
                (!visibilityAmbiguous && (
                    visibility?.caseInsensitiveCompare("hidden") == .orderedSame ||
                    visibility?.caseInsensitiveCompare("collapse") == .orderedSame
                ))
            let hidden = parentHidden || selfHidden

            let id = attributes["id"] ?? ""
            let fieldName = attributes["name"] ?? ""
            let type = attributes["type"] ?? ""

            let activeContainer = name == "div" &&
                id.caseInsensitiveCompare("captchaParent") == .orderedSame && !hidden
            let activeInput = name == "input" &&
                (fieldName.caseInsensitiveCompare("captcha") == .orderedSame ||
                    id.caseInsensitiveCompare("captcha") == .orderedSame) &&
                !hidden &&
                type.caseInsensitiveCompare("hidden") != .orderedSame
            let activeImage = name == "img" &&
                ["captcha", "captchaimg", "captchaimage"].contains(id.lowercased()) &&
                !hidden
            if activeContainer || activeInput || activeImage {
                return true
            }

            let trimmedAttrs = rawAttrs.trimmingCharacters(in: .whitespacesAndNewlines)
            let selfClosing = trimmedAttrs.hasSuffix("/")
            if !voidTags.contains(name) && !selfClosing {
                ancestors.append((name, hidden))
            }
        }
        return false
    }

    private static func parseAttributes(_ raw: String) -> [String: String] {
        var result: [String: String] = [:]
        let attrMatches = matches(
            "([A-Za-z_:][-A-Za-z0-9_:.]*)\\s*(?:=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s>]+)))?",
            in: raw
        )
        for match in attrMatches {
            guard let nameRange = Range(match.range(at: 1), in: raw) else { continue }
            let key = raw[nameRange].lowercased()
            let val: String = [2, 3, 4].compactMap { group -> String? in
                let nsRange = match.range(at: group)
                guard nsRange.location != NSNotFound, let r = Range(nsRange, in: raw) else { return nil }
                let s = String(raw[r])
                return s.isEmpty ? nil : s
            }.first ?? ""
            result[key] = val
        }
        return result
    }

    private static func styleValues(style: String, property: String) -> [String] {
        style.split(separator: ";").compactMap { declaration -> String? in
            guard let colon = declaration.firstIndex(of: ":") else { return nil }
            let propName = declaration[..<colon].trimmingCharacters(in: .whitespacesAndNewlines)
            guard propName.caseInsensitiveCompare(property) == .orderedSame else { return nil }
            let rawValue = String(declaration[declaration.index(after: colon)...])
                .trimmingCharacters(in: .whitespacesAndNewlines)
            return replacePattern("\\s*!important\\s*$", options: [.caseInsensitive], in: rawValue, with: "")
                .trimmingCharacters(in: .whitespacesAndNewlines)
        }
    }

    private static func matches(
        _ pattern: String,
        options: NSRegularExpression.Options = [],
        in text: String
    ) -> [NSTextCheckingResult] {
        guard let regex = try? NSRegularExpression(pattern: pattern, options: options) else { return [] }
        let range = NSRange(text.startIndex..<text.endIndex, in: text)
        return regex.matches(in: text, options: [], range: range)
    }

    private static func replacePattern(
        _ pattern: String,
        options: NSRegularExpression.Options = [],
        in text: String,
        with template: String
    ) -> String {
        guard let regex = try? NSRegularExpression(pattern: pattern, options: options) else { return text }
        let range = NSRange(text.startIndex..<text.endIndex, in: text)
        return regex.stringByReplacingMatches(in: text, options: [], range: range, withTemplate: template)
    }
}
