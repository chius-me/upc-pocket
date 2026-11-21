import Foundation

struct RawHTTPResponse: Sendable {
    let statusCode: Int
    let location: String?
    let setCookieHeaders: [String]
    let body: String
    let contentType: String?
}

enum StrictHTTPError: Error, LocalizedError, Sendable, Equatable {
    case redirectBlocked(Int)
    case httpStatus(Int)
    case responseTooLarge
    case invalidEncoding
    case transport(String)

    var errorDescription: String? {
        switch self {
        case .redirectBlocked:
            "学校服务被要求跳转，当前已阻止重定向"
        case let .httpStatus(code):
            "学校服务暂时不可用（HTTP \(code)）"
        case .responseTooLarge:
            "学校响应体积异常过大，请稍后重试"
        case .invalidEncoding:
            "学校响应编码异常"
        case let .transport(message):
            message
        }
    }
}

private final class NoRedirectDelegate: NSObject, URLSessionTaskDelegate, Sendable {
    func urlSession(
        _ session: URLSession,
        task: URLSessionTask,
        willPerformHTTPRedirection response: HTTPURLResponse,
        newRequest request: URLRequest,
        completionHandler: @escaping @Sendable (URLRequest?) -> Void
    ) {
        completionHandler(nil)
    }
}

actor StrictHTTPClient {
    static let shared = StrictHTTPClient()

    private let session: URLSession
    private let delegate = NoRedirectDelegate()

    init() {
        let config = URLSessionConfiguration.ephemeral
        config.requestCachePolicy = .reloadIgnoringLocalCacheData
        config.urlCache = nil
        config.httpCookieStorage = nil
        config.httpShouldSetCookies = false
        self.session = URLSession(configuration: config, delegate: delegate, delegateQueue: nil)
    }

    func get(
        url: URL,
        headers: [String: String] = [:],
        timeout: TimeInterval = 20,
        maxBytes: Int
    ) async throws -> String {
        var request = URLRequest(url: url, timeoutInterval: timeout)
        request.httpMethod = "GET"
        request.cachePolicy = .reloadIgnoringLocalCacheData
        for (key, value) in headers {
            request.setValue(value, forHTTPHeaderField: key)
        }
        return try await execute(request: request, maxBytes: maxBytes)
    }

    func postForm(
        url: URL,
        formItems: [(String, String)],
        headers: [String: String] = [:],
        timeout: TimeInterval = 20,
        maxBytes: Int
    ) async throws -> String {
        var request = URLRequest(url: url, timeoutInterval: timeout)
        request.httpMethod = "POST"
        request.cachePolicy = .reloadIgnoringLocalCacheData
        request.setValue("application/x-www-form-urlencoded; charset=UTF-8", forHTTPHeaderField: "Content-Type")
        for (key, value) in headers {
            request.setValue(value, forHTTPHeaderField: key)
        }
        request.httpBody = Data(Self.encodeForm(formItems).utf8)
        return try await execute(request: request, maxBytes: maxBytes)
    }

    func executeRaw(
        url: URL,
        method: String = "GET",
        headers: [String: String] = [:],
        formItems: [(String, String)]? = nil,
        formBody: String? = nil,
        timeout: TimeInterval = 20,
        maxBytes: Int = 2 * 1024 * 1024
    ) async throws -> RawHTTPResponse {
        var request = URLRequest(url: url, timeoutInterval: timeout)
        request.httpMethod = method
        request.cachePolicy = .reloadIgnoringLocalCacheData
        request.setValue("no-cache, no-store", forHTTPHeaderField: "Cache-Control")
        request.setValue("UPCPocket-Native/0.1", forHTTPHeaderField: "User-Agent")
        for (key, value) in headers {
            request.setValue(value, forHTTPHeaderField: key)
        }
        if let formItems {
            if !headers.keys.contains(where: { $0.caseInsensitiveCompare("Content-Type") == .orderedSame }) {
                request.setValue("application/x-www-form-urlencoded; charset=UTF-8", forHTTPHeaderField: "Content-Type")
            }
            request.httpBody = Data(Self.encodeForm(formItems).utf8)
        } else if let formBody {
            if !headers.keys.contains(where: { $0.caseInsensitiveCompare("Content-Type") == .orderedSame }) {
                request.setValue("application/x-www-form-urlencoded; charset=UTF-8", forHTTPHeaderField: "Content-Type")
            }
            request.httpBody = Data(formBody.utf8)
        }

        let data: Data
        let response: URLResponse
        do {
            (data, response) = try await session.data(for: request)
        } catch let error as URLError {
            throw StrictHTTPError.transport("无法连接学校服务：\(error.localizedDescription)")
        } catch {
            throw StrictHTTPError.transport("网络请求失败，请检查校园网络后重试")
        }

        guard let httpResponse = response as? HTTPURLResponse else {
            throw StrictHTTPError.transport("无效的网络响应")
        }
        guard data.count <= maxBytes else {
            throw StrictHTTPError.responseTooLarge
        }
        let text: String
        if data.isEmpty {
            text = ""
        } else if let decoded = String(data: data, encoding: .utf8) {
            text = decoded
        } else {
            throw StrictHTTPError.invalidEncoding
        }

        var location: String?
        var contentType: String?
        var stringFields: [String: String] = [:]
        for (rawKey, rawValue) in httpResponse.allHeaderFields {
            guard let key = rawKey as? String, let val = rawValue as? String else { continue }
            stringFields[key] = val
            if key.caseInsensitiveCompare("Location") == .orderedSame {
                location = val
            } else if key.caseInsensitiveCompare("Content-Type") == .orderedSame {
                contentType = val
            }
        }

        let parsedCookies = HTTPCookie.cookies(withResponseHeaderFields: stringFields, for: url)
        let setCookieHeaders = parsedCookies.map { cookie -> String in
            var parts = [
                "\(cookie.name)=\(cookie.value)",
                "Domain=\(cookie.domain)",
                "Path=\(cookie.path)"
            ]
            if cookie.isSecure { parts.append("Secure") }
            if cookie.isHTTPOnly { parts.append("HttpOnly") }
            if let expires = cookie.expiresDate {
                let remaining = Int(expires.timeIntervalSinceNow.rounded(.down))
                parts.append("Max-Age=\(remaining)")
            }
            return parts.joined(separator: "; ")
        }

        return RawHTTPResponse(
            statusCode: httpResponse.statusCode,
            location: location,
            setCookieHeaders: setCookieHeaders,
            body: text,
            contentType: contentType
        )
    }

    static func encodeForm(_ items: [(String, String)]) -> String {
        items.map { key, value in
            "\(percentEncode(key))=\(percentEncode(value))"
        }.joined(separator: "&")
    }

    private static func percentEncode(_ raw: String) -> String {
        let allowed = CharacterSet(
            charactersIn: "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~"
        )
        return raw.addingPercentEncoding(withAllowedCharacters: allowed) ?? raw
    }

    private func execute(request: URLRequest, maxBytes: Int) async throws -> String {
        let data: Data
        let response: URLResponse
        do {
            (data, response) = try await session.data(for: request)
        } catch let error as URLError {
            throw StrictHTTPError.transport("无法连接学校服务：\(error.localizedDescription)")
        } catch {
            throw StrictHTTPError.transport("网络请求失败，请检查校园网络后重试")
        }

        guard let httpResponse = response as? HTTPURLResponse else {
            throw StrictHTTPError.transport("无效的网络响应")
        }
        let status = httpResponse.statusCode
        if (300...399).contains(status) {
            throw StrictHTTPError.redirectBlocked(status)
        }
        guard (200...299).contains(status) else {
            throw StrictHTTPError.httpStatus(status)
        }
        guard data.count <= maxBytes else {
            throw StrictHTTPError.responseTooLarge
        }
        guard let text = String(data: data, encoding: .utf8) else {
            throw StrictHTTPError.invalidEncoding
        }
        return text
    }
}
