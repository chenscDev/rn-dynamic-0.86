import UIKit

#if canImport(React)
import React
#endif

/// 正式入口参数：整页打开某个分包
public struct RNOpenBundleRequest {
    public let key: String
    public var channel: String
    public var urlOverride: String?
    public var initialProps: [String: Any]
    /// 是否按 dependsOn 加载 common（默认 true）
    public var loadDependencies: Bool

    public init(
        key: String,
        channel: String = "main",
        urlOverride: String? = nil,
        initialProps: [String: Any] = [:],
        loadDependencies: Bool = true
    ) {
        self.key = key
        self.channel = RNBundleConfigStore.normalizeChannel(channel)
        self.urlOverride = urlOverride
        self.initialProps = initialProps
        self.loadDependencies = loadDependencies
    }
}

/// 调试入口参数
public struct RNDebugEntryRequest {
    public var host: String
    public var port: Int
    public var key: String
    public var platform: String
    public var channel: String
    public var useDevServer: Bool
    public var bundleURL: String?
    public var loadCommon: Bool
    public var initialProps: [String: Any]

    public init(
        host: String = "localhost",
        port: Int = 8081,
        key: String,
        platform: String = "ios",
        channel: String = "main",
        useDevServer: Bool = true,
        bundleURL: String? = nil,
        loadCommon: Bool = true,
        initialProps: [String: Any] = [:]
    ) {
        self.host = host
        self.port = port
        self.key = key
        self.platform = platform
        self.channel = RNBundleConfigStore.normalizeChannel(channel)
        self.useDevServer = useDevServer
        self.bundleURL = bundleURL
        self.loadCommon = loadCommon
        self.initialProps = initialProps
    }

    /// Metro 业务包地址：/src/<key>/index.bundle
    public var metroPageBundleURL: URL? {
        metroURL(path: "/src/\(key)/index.bundle")
    }

    /// Metro 公共包地址：/packages/common/src/index.bundle
    public var metroCommonBundleURL: URL? {
        metroURL(path: "/packages/common/src/index.bundle")
    }

    /// 兼容旧字段名
    public var metroBundleURL: URL? { metroPageBundleURL }

    private func metroURL(path: String) -> URL? {
        var components = URLComponents()
        components.scheme = "http"
        components.host = host
        components.port = port
        components.path = path
        components.queryItems = [
            URLQueryItem(name: "platform", value: platform),
            URLQueryItem(name: "dev", value: "true"),
            URLQueryItem(name: "minify", value: "false"),
        ]
        return components.url
    }
}

/**
 整页替换（Mode A）宿主控制器。
 支持 common 预依赖 + page 双包挂载。
 */
public final class RNBundleHostViewController: UIViewController {
    private let request: RNOpenBundleRequest
    private let configStore: RNBundleConfigStore
    private let cache: RNBundleCache
    private let statusLabel = UILabel()
    private var mountedView: UIView?

    public init(
        request: RNOpenBundleRequest,
        configStore: RNBundleConfigStore,
        cache: RNBundleCache
    ) {
        self.request = request
        self.configStore = configStore
        self.cache = cache
        super.init(nibName: nil, bundle: nil)
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    public override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .systemBackground
        title = "\(request.channel)/\(request.key)"

        statusLabel.numberOfLines = 0
        statusLabel.textAlignment = .center
        statusLabel.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(statusLabel)
        NSLayoutConstraint.activate([
            statusLabel.leadingAnchor.constraint(equalTo: view.leadingAnchor, constant: 24),
            statusLabel.trailingAnchor.constraint(equalTo: view.trailingAnchor, constant: -24),
            statusLabel.centerYAnchor.constraint(equalTo: view.centerYAnchor),
        ])

        statusLabel.text = "正在准备 \(request.channel)/\(request.key)..."
        Task { await loadAndMount() }
    }

    private func loadAndMount() async {
        do {
            var pageItem = try configStore.item(forKey: request.key, platform: "ios")
            if let urlOverride = request.urlOverride, !urlOverride.isEmpty {
                pageItem = pageItem.withURL(urlOverride)
            }

            var commonURL: URL?
            if request.loadDependencies {
                for dep in pageItem.dependencyKeys {
                    let depItem = try configStore.item(forKey: dep, platform: "ios")
                    let file = try await cache.resolveBundleFile(item: depItem)
                    if dep == "common" {
                        commonURL = file
                    }
                }
            }

            let pageURL = try await cache.resolveBundleFile(item: pageItem)

            await MainActor.run {
                do {
                    self.statusLabel.isHidden = true
                    self.mountedView = try RNBundleMount.mount(
                        in: self.view,
                        request: RNBundleMountRequest(
                            moduleName: pageItem.moduleName,
                            pageBundleURL: pageURL,
                            commonBundleURL: commonURL,
                            initialProperties: self.request.initialProps
                        )
                    )
                } catch {
                    self.statusLabel.isHidden = false
                    self.statusLabel.text = """
                    分包文件已就绪，但挂载失败:
                    \(error.localizedDescription)

                    channel: \(self.request.channel)
                    key: \(pageItem.key)
                    hash: \(pageItem.hash)
                    page: \(pageURL.path)
                    common: \(commonURL?.path ?? "(无)")
                    """
                }
            }
        } catch {
            await MainActor.run {
                self.statusLabel.text = "加载失败: \(error.localizedDescription)"
            }
        }
    }
}
