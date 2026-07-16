import UIKit

#if canImport(React)
import React
#endif

/// 正式入口参数：整页打开某个分包
public struct RNOpenBundleRequest {
    public let key: String
    public var urlOverride: String?
    public var initialProps: [String: Any]

    public init(key: String, urlOverride: String? = nil, initialProps: [String: Any] = [:]) {
        self.key = key
        self.urlOverride = urlOverride
        self.initialProps = initialProps
    }
}

/// 调试入口参数
public struct RNDebugEntryRequest {
    public var host: String
    public var port: Int
    public var key: String
    public var useDevServer: Bool
    public var bundleURL: String?
    public var initialProps: [String: Any]

    public init(
        host: String = "localhost",
        port: Int = 8081,
        key: String,
        useDevServer: Bool = true,
        bundleURL: String? = nil,
        initialProps: [String: Any] = [:]
    ) {
        self.host = host
        self.port = port
        self.key = key
        self.useDevServer = useDevServer
        self.bundleURL = bundleURL
        self.initialProps = initialProps
    }

    /// Metro 多入口地址：/src/<key>/index.bundle
    public var metroBundleURL: URL? {
        var components = URLComponents()
        components.scheme = "http"
        components.host = host
        components.port = port
        components.path = "/src/\(key)/index.bundle"
        components.queryItems = [
            URLQueryItem(name: "platform", value: "ios"),
            URLQueryItem(name: "dev", value: "true"),
            URLQueryItem(name: "minify", value: "false"),
        ]
        return components.url
    }
}

/**
 整页替换（Mode A）宿主控制器骨架。

 接入说明：
 1. 宿主 App 已集成与基座一致的 React Native 0.86
 2. 将本 SDK 源码加入工程
 3. 配置文件指向仓库 config/bundles.local.json（或拷贝进 App Bundle）
 4. push / present 本控制器即可打开对应分包

 注意：此处保留 RCTRootView / RCTReactNativeFactory 的接入点注释，
 便于在宿主完成 RN 依赖后补齐具体创建代码，避免本仓库在未 pod install 时无法编译演示。
 */
public final class RNBundleHostViewController: UIViewController {
    private let request: RNOpenBundleRequest
    private let configStore: RNBundleConfigStore
    private let cache: RNBundleCache
    private let statusLabel = UILabel()

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
        title = request.key

        statusLabel.numberOfLines = 0
        statusLabel.textAlignment = .center
        statusLabel.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(statusLabel)
        NSLayoutConstraint.activate([
            statusLabel.leadingAnchor.constraint(equalTo: view.leadingAnchor, constant: 24),
            statusLabel.trailingAnchor.constraint(equalTo: view.trailingAnchor, constant: -24),
            statusLabel.centerYAnchor.constraint(equalTo: view.centerYAnchor),
        ])

        statusLabel.text = "正在准备分包 \(request.key)..."
        Task { await loadAndMount() }
    }

    private func loadAndMount() async {
        do {
            var item = try configStore.item(forKey: request.key, platform: "ios")
            if let urlOverride = request.urlOverride, !urlOverride.isEmpty {
                item = item.withURL(urlOverride)
            }

            let fileURL = try await cache.resolveBundleFile(item: item)
            await MainActor.run {
                self.statusLabel.text = """
                分包已就绪（Mode A 整页）
                key: \(item.key)
                hash: \(item.hash)
                path: \(fileURL.path)

                请在宿主完成 RN 集成后，使用 RCTRootView
                moduleName=\(item.moduleName)
                bundleURL=fileURL
                initialProperties=\(self.request.initialProps)
                挂载到 self.view。
                """
                // TODO(宿主接入): 创建 React Root View 并 addSubview
                // let rootView = RCTRootView(bundleURL: fileURL, moduleName: item.moduleName, initialProperties: request.initialProps, launchOptions: nil)
                // rootView.frame = view.bounds
                // view.addSubview(rootView)
            }
        } catch {
            await MainActor.run {
                self.statusLabel.text = "加载失败: \(error.localizedDescription)"
            }
        }
    }
}
