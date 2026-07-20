import Foundation

/// 远程配置加载：HTTP 拉取 + 本地磁盘缓存
public final class RNBundleRemoteConfigStore {
    public let configBaseURL: URL
    public let rnVersion: String
    public let channel: String
    private let cacheDirectory: URL
    private var cached: RNBundlesConfigFile?

    /// - Parameters:
    ///   - configBaseURL: 配置中心根地址，如 https://config.rn.example.com
    ///   - rnVersion: RN 版本，如 0.86.0
    ///   - channel: 发布通道
    ///   - cacheDirectory: 本地缓存目录
    public init(
        configBaseURL: URL,
        rnVersion: String,
        channel: String = "main",
        cacheDirectory: URL
    ) {
        self.configBaseURL = configBaseURL
        self.rnVersion = rnVersion
        self.channel = RNBundleConfigStore.normalizeChannel(channel)
        self.cacheDirectory = cacheDirectory
    }

    private var cacheFileURL: URL {
        cacheDirectory
            .appendingPathComponent("remote-config", isDirectory: true)
            .appendingPathComponent(rnVersion, isDirectory: true)
            .appendingPathComponent("\(channel).json")
    }

    private var remoteURL: URL {
        configBaseURL
            .appendingPathComponent("config")
            .appendingPathComponent(rnVersion)
            .appendingPathComponent(channel)
    }

    public func load(forceReload: Bool = false) throws -> RNBundlesConfigFile {
        if let cached, !forceReload {
            return cached
        }

        do {
            let config = try fetchRemote()
            cached = config
            try writeCache(config)
            return config
        } catch {
            if let cached = try? readCache() {
                return cached
            }
            throw error
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

    private func fetchRemote() throws -> RNBundlesConfigFile {
        var request = URLRequest(url: remoteURL)
        request.httpMethod = "GET"
        request.timeoutInterval = 15
        request.setValue("application/json", forHTTPHeaderField: "Accept")

        let (data, response) = try URLSession.shared.syncData(for: request)
        guard let http = response as? HTTPURLResponse else {
            throw RNBundleConfigError.decodeFailed("无效 HTTP 响应")
        }
        guard (200...299).contains(http.statusCode) else {
            throw RNBundleConfigError.decodeFailed("远程配置 HTTP \(http.statusCode)")
        }
        return try JSONDecoder().decode(RNBundlesConfigFile.self, from: data)
    }

    private func writeCache(_ config: RNBundlesConfigFile) throws {
        let dir = cacheFileURL.deletingLastPathComponent()
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let data = try JSONEncoder().encode(config)
        try data.write(to: cacheFileURL, options: .atomic)
    }

    private func readCache() throws -> RNBundlesConfigFile {
        let data = try Data(contentsOf: cacheFileURL)
        return try JSONDecoder().decode(RNBundlesConfigFile.self, from: data)
    }
}

private extension URLSession {
    func syncData(for request: URLRequest) throws -> (Data, URLResponse) {
        var result: Result<(Data, URLResponse), Error>?
        let semaphore = DispatchSemaphore(value: 0)
        let task = dataTask(with: request) { data, response, error in
            if let error {
                result = .failure(error)
            } else if let data, let response {
                result = .success((data, response))
            } else {
                result = .failure(RNBundleConfigError.decodeFailed("空响应"))
            }
            semaphore.signal()
        }
        task.resume()
        semaphore.wait()
        switch result {
        case .success(let value):
            return value
        case .failure(let error):
            throw error
        case .none:
            throw RNBundleConfigError.decodeFailed("请求未完成")
        }
    }
}
