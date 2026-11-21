import CommonCrypto
import CryptoKit
import Foundation

enum UpcLibrarySigningError: Error, Sendable {
    case invalidCiphertext
    case decryptionFailed(CCCryptorStatus)
    case unsupportedMethod(String)
}

/// Request signing helper for UPC `libspace.upc.edu.cn` (`libseat`) APIs.
///
/// Uses the non-secret public client key embedded in the school's frontend script to compute:
/// `HMAC-SHA256("seat::<uuid>::<date>::<METHOD>", configKey)` as lowercase hex.
enum UpcLibrarySigning: Sendable {
    private static let numCode = "cDf+jadFUWncEn536MXItw=="
    private static let configKeySeed = "server_date_time"
    private static let configIVSeed = "client_date_time"

    static func configKey() throws -> Data {
        guard let cipherData = Data(base64Encoded: numCode) else {
            throw UpcLibrarySigningError.invalidCiphertext
        }
        let keyData = Data(configKeySeed.utf8)
        let ivData = Data(configIVSeed.utf8)
        var outBytes = [UInt8](repeating: 0, count: cipherData.count + kCCBlockSizeAES128)
        var numBytesDecrypted = 0

        let status: CCCryptorStatus = outBytes.withUnsafeMutableBytes { outPtr in
            cipherData.withUnsafeBytes { dataPtr in
                keyData.withUnsafeBytes { keyPtr in
                    ivData.withUnsafeBytes { ivPtr in
                        CCCrypt(
                            CCOperation(kCCDecrypt),
                            CCAlgorithm(kCCAlgorithmAES),
                            CCOptions(kCCOptionPKCS7Padding),
                            keyPtr.baseAddress,
                            keyData.count,
                            ivPtr.baseAddress,
                            dataPtr.baseAddress,
                            cipherData.count,
                            outPtr.baseAddress,
                            outPtr.count,
                            &numBytesDecrypted
                        )
                    }
                }
            }
        }
        guard status == kCCSuccess else {
            throw UpcLibrarySigningError.decryptionFailed(status)
        }
        return Data(outBytes.prefix(numBytesDecrypted))
    }

    static func sign(
        method: String,
        uuid: String,
        timestamp: Int64,
        hmacKey: Data? = nil
    ) throws -> String {
        let normalizedMethod = method.uppercased()
        guard normalizedMethod == "GET" || normalizedMethod == "POST" else {
            throw UpcLibrarySigningError.unsupportedMethod(normalizedMethod)
        }
        let resolvedKey = try hmacKey ?? configKey()
        let message = "seat::\(uuid)::\(timestamp)::\(normalizedMethod)"
        let digest = HMAC<SHA256>.authenticationCode(
            for: Data(message.utf8),
            using: SymmetricKey(data: resolvedKey)
        )
        return digest.map { String(format: "%02x", $0) }.joined()
    }

    static func signedHeaders(
        token: String?,
        method: String,
        uuid: String = UUID().uuidString.lowercased(),
        timestamp: Int64 = Int64(Date().timeIntervalSince1970 * 1000),
        hmacKey: Data? = nil
    ) throws -> [String: String] {
        var headers: [String: String] = [
            "loginType": "PC",
            "X-request-id": uuid,
            "X-request-date": String(timestamp),
            "X-hmac-request-key": try sign(method: method, uuid: uuid, timestamp: timestamp, hmacKey: hmacKey),
            "Accept": "application/json"
        ]
        if let token, !token.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            headers["Authorization"] = token
        }
        return headers
    }
}
