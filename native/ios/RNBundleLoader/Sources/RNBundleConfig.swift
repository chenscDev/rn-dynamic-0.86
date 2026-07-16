import Foundation

/// 单个分包配置（与 config/bundles.local.json 对齐）
public struct RNBundleItem: Codable, Equatable {
    public let key: String
    public let name: String?
    public let componentName: String?
    public let url: String
    public let hash: String
    public let platform: String
    public let localPath: String?

    public init(
        key: String,
        name: String? = nil,
        componentName: String? = nil,
        url: String,
        hash: String,
        platform: String,
        localPath: String? = nil
    ) {
        self.key = key
        self.name = name
        self.componentName = componentName
        self.url = url
        self.hash = hash
        self.platform = platform
        self.localPath = localPath
    }

    public var moduleName: String {
        componentName ?? key
    }

    /// 使用原生传入的 url 覆盖配置地址
    public func withURL(_ url: String) -> RNBundleItem {
        RNBundleItem(
            key: key,
            name: name,
            componentName: componentName,
            url: url,
            hash: hash,
            platform: platform,
            localPath: localPath
        )
    }
}

/// 配置文件根结构
public struct RNBundlesConfigFile: Codable {
    public let rnVersion: String
    public let baseVersion: String
    public let updatedAt: String
    public let bundles: [String: [RNBundleItem]]
}

public enum RNBundleConfigError: Error, LocalizedError {
    case fileNotFound(String)
    case decodeFailed(String)
    case keyNotFound(String)
    case platformNotFound(String)

    public var errorDescription: String? {
        switch self {
        case .fileNotFound(let path):
            return "配置文件不存在: \(path)"
        case .decodeFailed(let reason):
            return "配置解析失败: \(reason)"
        case .keyNotFound(let key):
            return "未找到分包 key: \(key)"
        case .platformNotFound(let key):
            return "分包 \(key) 缺少当前平台配置"
        }
    }
}

/// 读取本地分包配置
public final class RNBundleConfigStore {
    public let configURL: URL
    private var cached: RNBundlesConfigFile?

    public init(configURL: URL) {
        self.configURL = configURL
    }

    public func load(forceReload: Bool = false) throws -> RNBundlesConfigFile {
        if let cached, !forceReload {
            return cached
        }
        guard FileManager.default.fileExists(atPath: configURL.path) else {
            throw RNBundleConfigError.fileNotFound(configURL.path)
        }
        do {
            let data = try Data(contentsOf: configURL)
            let decoded = try JSONDecoder().decode(RNBundlesConfigFile.self, from: data)
            cached = decoded
            return decoded
        } catch {
            throw RNBundleConfigError.decodeFailed(error.localizedDescription)
        }
    }

    public func item(forKey key: String, platform: String = "ios") throws -> RNBundleItem {
        let config = try load()
        guard let list = config.bundles[key], !list.isEmpty else {
            throw RNBundleConfigError.keyNotFound(key)
        }
        guard let matched = list.first(where: { $0.platform == platform }) else {
            throw RNBundleConfigError.platformNotFound(key)
        }
        return matched
    }
}
