import Foundation

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
