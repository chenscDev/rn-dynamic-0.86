import Foundation
import CryptoKit

public enum RNBundleCacheError: Error, LocalizedError {
    case invalidURL(String)
    case downloadFailed(String)
    case hashMismatch(expected: String, actual: String)
    case ioFailed(String)

    public var errorDescription: String? {
        switch self {
        case .invalidURL(let url):
            return "非法分包地址: \(url)"
        case .downloadFailed(let reason):
            return "下载分包失败: \(reason)"
        case .hashMismatch(let expected, let actual):
            return "分包 hash 不匹配 expected=\(expected) actual=\(actual)"
        case .ioFailed(let reason):
            return "缓存读写失败: \(reason)"
        }
    }
}

/// 按 key + hash 缓存分包；hash 变化时强制重新拉取
public final class RNBundleCache {
    public let cacheDirectory: URL

    public init(cacheDirectory: URL? = nil) throws {
        if let cacheDirectory {
            self.cacheDirectory = cacheDirectory
        } else {
            let base = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask).first
                ?? FileManager.default.temporaryDirectory
            self.cacheDirectory = base.appendingPathComponent("RNDynamicBundles", isDirectory: true)
        }
        try FileManager.default.createDirectory(at: self.cacheDirectory, withIntermediateDirectories: true)
    }

    public func localFileURL(key: String, hash: String) -> URL {
        cacheDirectory
            .appendingPathComponent(key, isDirectory: true)
            .appendingPathComponent("\(key).ios.\(hash).bundle")
    }

    /// 若本地已有对应 hash 则直接返回，否则下载/拷贝并校验
    public func resolveBundleFile(item: RNBundleItem) async throws -> URL {
        let target = localFileURL(key: item.key, hash: item.hash)
        if FileManager.default.fileExists(atPath: target.path) {
            let actual = try Self.sha256Prefix(ofFileAt: target)
            if actual == item.hash {
                return target
            }
            try? FileManager.default.removeItem(at: target)
        }

        // 清理同 key 旧缓存，保证强制用新 hash
        let keyDir = cacheDirectory.appendingPathComponent(item.key, isDirectory: true)
        if FileManager.default.fileExists(atPath: keyDir.path) {
            try? FileManager.default.removeItem(at: keyDir)
        }
        try FileManager.default.createDirectory(at: keyDir, withIntermediateDirectories: true)

        let sourceURL = try makeURL(from: item.url)
        if sourceURL.isFileURL {
            try FileManager.default.copyItem(at: sourceURL, to: target)
        } else {
            let (tempURL, response) = try await URLSession.shared.download(from: sourceURL)
            if let http = response as? HTTPURLResponse, !(200...299).contains(http.statusCode) {
                throw RNBundleCacheError.downloadFailed("HTTP \(http.statusCode)")
            }
            try FileManager.default.moveItem(at: tempURL, to: target)
        }

        let actual = try Self.sha256Prefix(ofFileAt: target)
        guard actual == item.hash else {
            try? FileManager.default.removeItem(at: target)
            throw RNBundleCacheError.hashMismatch(expected: item.hash, actual: actual)
        }
        return target
    }

    public func clearAll() throws {
        if FileManager.default.fileExists(atPath: cacheDirectory.path) {
            try FileManager.default.removeItem(at: cacheDirectory)
        }
        try FileManager.default.createDirectory(at: cacheDirectory, withIntermediateDirectories: true)
    }

    private func makeURL(from raw: String) throws -> URL {
        if raw.hasPrefix("file://"), let url = URL(string: raw) {
            return url
        }
        if raw.hasPrefix("/"), FileManager.default.fileExists(atPath: raw) {
            return URL(fileURLWithPath: raw)
        }
        if let url = URL(string: raw) {
            return url
        }
        throw RNBundleCacheError.invalidURL(raw)
    }

    public static func sha256Prefix(ofFileAt url: URL, length: Int = 12) throws -> String {
        let data = try Data(contentsOf: url)
        let digest = SHA256.hash(data: data)
        let hex = digest.map { String(format: "%02x", $0) }.joined()
        return String(hex.prefix(length))
    }
}
