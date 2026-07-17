import Foundation

/// 单个分包配置（与 bundles JSON 对齐，支持 common/page + channel）
public struct RNBundleItem: Codable, Equatable {
    public let key: String
    public let name: String?
    public let componentName: String?
    public let url: String
    public let hash: String
    public let platform: String
    public let channel: String?
    public let kind: String?
    public let dependsOn: [String]?
    public let version: String?
    public let assetsUrl: String?
    public let localPath: String?

    public init(
        key: String,
        name: String? = nil,
        componentName: String? = nil,
        url: String,
        hash: String,
        platform: String,
        channel: String? = nil,
        kind: String? = nil,
        dependsOn: [String]? = nil,
        version: String? = nil,
        assetsUrl: String? = nil,
        localPath: String? = nil
    ) {
        self.key = key
        self.name = name
        self.componentName = componentName
        self.url = url
        self.hash = hash
        self.platform = platform
        self.channel = channel
        self.kind = kind
        self.dependsOn = dependsOn
        self.version = version
        self.assetsUrl = assetsUrl
        self.localPath = localPath
    }

    public var moduleName: String {
        componentName ?? key
    }

    public var resolvedKind: String {
        kind ?? (key == "common" ? "common" : "page")
    }

    public var dependencyKeys: [String] {
        dependsOn ?? (resolvedKind == "page" ? ["common"] : [])
    }

    public func withURL(_ url: String) -> RNBundleItem {
        RNBundleItem(
            key: key,
            name: name,
            componentName: componentName,
            url: url,
            hash: hash,
            platform: platform,
            channel: channel,
            kind: kind,
            dependsOn: dependsOn,
            version: version,
            assetsUrl: assetsUrl,
            localPath: localPath
        )
    }
}

/// 单个 channel 的配置文件根结构
public struct RNBundlesConfigFile: Codable {
    public let rnVersion: String
    public let baseVersion: String
    public let channel: String?
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

/// 读取本地分包配置（按 channel 文件）
public final class RNBundleConfigStore {
    public let configURL: URL
    public let channel: String
    private var cached: RNBundlesConfigFile?

    /// - Parameters:
    ///   - configURL: 完整配置文件 URL；若传目录则拼 channels/<channel>/bundles.local.json
    ///   - channel: 发布通道
    public init(configURL: URL, channel: String = "main") {
        self.channel = Self.normalizeChannel(channel)
        if configURL.hasDirectoryPath || configURL.pathExtension.isEmpty && !configURL.lastPathComponent.contains(".") {
            self.configURL = configURL
                .appendingPathComponent("channels", isDirectory: true)
                .appendingPathComponent(self.channel, isDirectory: true)
                .appendingPathComponent("bundles.local.json")
        } else if configURL.lastPathComponent == "bundles.local.json",
                  configURL.deletingLastPathComponent().lastPathComponent != self.channel {
            // 兼容：传入 project/config/bundles.local.json 时改写到 channels/<channel>/
            let configDir = configURL.deletingLastPathComponent()
            self.configURL = configDir
                .appendingPathComponent("channels", isDirectory: true)
                .appendingPathComponent(self.channel, isDirectory: true)
                .appendingPathComponent("bundles.local.json")
        } else {
            self.configURL = configURL
        }
    }

    public static func normalizeChannel(_ raw: String) -> String {
        let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
            .replacingOccurrences(of: "refs/heads/", with: "")
        if trimmed.isEmpty { return "main" }
        let safe = trimmed
            .replacingOccurrences(of: "/", with: "-")
            .replacingOccurrences(of: "\\", with: "-")
        return safe.isEmpty ? "main" : safe
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
