import UIKit

#if canImport(React)
import React
#endif

/**
 本地 / 测试环境调试入口页面

 - 本地：填写 host + port + key，Metro 直连 page（Fast Refresh + Dev Menu）
 - 测试：关闭 DevServer，填写测试环境分包 URL / channel
 - platform 固定为 ios（由本控制器所在宿主标识）
 */
public final class RNDebugEntryViewController: UIViewController {
    private let hostField = UITextField()
    private let portField = UITextField()
    private let keyField = UITextField()
    private let channelField = UITextField()
    private let urlField = UITextField()
    private let devSwitch = UISwitch()
    private let commonSwitch = UISwitch()
    private let infoLabel = UILabel()
    private let cache: RNBundleCache
    private let configRootURL: URL?

    public init(cache: RNBundleCache, configRootURL: URL? = nil) {
        self.cache = cache
        self.configRootURL = configRootURL
        super.init(nibName: nil, bundle: nil)
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    public override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .systemBackground
        title = "RN 调试入口"

        hostField.placeholder = "Host（本地 IP / 域名）"
        hostField.text = "localhost"
        hostField.borderStyle = .roundedRect

        portField.placeholder = "Port"
        portField.text = "8081"
        portField.keyboardType = .numberPad
        portField.borderStyle = .roundedRect

        keyField.placeholder = "分包 key（如 home）"
        keyField.text = "home"
        keyField.borderStyle = .roundedRect
        keyField.autocapitalizationType = .none

        channelField.placeholder = "channel（分支，如 order-main）"
        channelField.text = "main"
        channelField.borderStyle = .roundedRect
        channelField.autocapitalizationType = .none
        channelField.autocorrectionType = .no

        urlField.placeholder = "测试环境 page bundle URL（非 DevServer 时必填）"
        urlField.borderStyle = .roundedRect
        urlField.autocapitalizationType = .none

        devSwitch.isOn = true
        commonSwitch.isOn = true

        let openButton = UIButton(type: .system)
        openButton.setTitle("打开分包", for: .normal)
        openButton.addTarget(self, action: #selector(onOpen), for: .touchUpInside)

        let clearButton = UIButton(type: .system)
        clearButton.setTitle("清除本地分包缓存", for: .normal)
        clearButton.addTarget(self, action: #selector(onClearCache), for: .touchUpInside)

        infoLabel.numberOfLines = 0
        infoLabel.font = .systemFont(ofSize: 13)
        infoLabel.textColor = .secondaryLabel

        let stack = UIStackView(arrangedSubviews: [
            labeled("Host", hostField),
            labeled("Port", portField),
            labeled("Key", keyField),
            labeled("Channel", channelField),
            labeled("DevServer", devSwitch),
            labeled("加载 common", commonSwitch),
            labeled("Bundle URL", urlField),
            openButton,
            clearButton,
            infoLabel,
        ])
        stack.axis = .vertical
        stack.spacing = 12
        stack.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(stack)

        NSLayoutConstraint.activate([
            stack.leadingAnchor.constraint(equalTo: view.leadingAnchor, constant: 20),
            stack.trailingAnchor.constraint(equalTo: view.trailingAnchor, constant: -20),
            stack.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor, constant: 20),
        ])

        refreshInfo()
    }

    private func labeled(_ title: String, _ view: UIView) -> UIStackView {
        let label = UILabel()
        label.text = title
        label.font = .systemFont(ofSize: 13, weight: .semibold)
        let row = UIStackView(arrangedSubviews: [label, view])
        row.axis = .vertical
        row.spacing = 4
        return row
    }

    private func refreshInfo() {
        let mode = devSwitch.isOn
            ? "本地 Metro 直连（Fast Refresh + 摇一摇 Dev Menu）"
            : "测试包 / channel CDN"
        infoLabel.text = """
        模式: \(mode)
        platform: ios（宿主标识）
        Metro：仅挂载 page 全量包 http URL（不预下载、不拼 common）
        page:   http://host:port/src/key/index.bundle?platform=ios&dev=true
        CDN:    rn/0.86.0/{channel}/{key}/ios/...
        """
    }

    @objc private func onOpen() {
        refreshInfo()
        let port = Int(portField.text ?? "8081") ?? 8081
        let key = (keyField.text ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        let channel = (channelField.text ?? "main").trimmingCharacters(in: .whitespacesAndNewlines)
        let host = (hostField.text ?? "localhost").trimmingCharacters(in: .whitespacesAndNewlines)
        guard !key.isEmpty else {
            infoLabel.text = "请填写分包 key"
            return
        }

        let request = RNDebugEntryRequest(
            host: host,
            port: port,
            key: key,
            platform: "ios",
            channel: channel,
            useDevServer: devSwitch.isOn,
            bundleURL: urlField.text,
            loadCommon: commonSwitch.isOn,
            initialProps: [
                "fromNative": "debug-entry",
                "channel": channel,
                "metroHost": host,
            ]
        )

        if request.useDevServer {
            guard let pageURL = request.metroPageBundleURL else {
                infoLabel.text = "无法构造 Metro page URL"
                return
            }
            // 配置 packager，供摇一摇 / Reload / DevTools 连接电脑 Metro
            #if canImport(React)
            RCTBundleURLProvider.sharedSettings().jsLocation = "\(host):\(port)"
            #endif
            // 直连 Metro page 全量包（不要双包拼装，否则无热更新）
            presentMounted(pageURL: pageURL, commonURL: nil, moduleName: key, props: request.initialProps)
        } else if let root = configRootURL {
            // 非 DevServer：按 channel 读配置并正式挂载
            do {
                let store = RNBundleConfigStore(configURL: root, channel: request.channel)
                let host = RNBundleHostViewController(
                    request: RNOpenBundleRequest(
                        key: key,
                        channel: request.channel,
                        urlOverride: request.bundleURL,
                        initialProps: request.initialProps
                    ),
                    configStore: store,
                    cache: cache
                )
                navigationController?.pushViewController(host, animated: true)
                    ?? present(UINavigationController(rootViewController: host), animated: true)
                infoLabel.text = "已打开正式入口 channel=\(request.channel) key=\(key)"
            } catch {
                infoLabel.text = "打开失败: \(error.localizedDescription)"
            }
        } else {
            let url = (request.bundleURL ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
            guard !url.isEmpty, let pageURL = URL(string: url) else {
                infoLabel.text = "测试模式请填写合法 Bundle URL，或提供 configRootURL"
                return
            }
            presentMounted(pageURL: pageURL, commonURL: nil, moduleName: key, props: request.initialProps)
        }
    }

    private func presentMounted(
        pageURL: URL,
        commonURL: URL?,
        moduleName: String,
        props: [String: Any]
    ) {
        let hostVC = UIViewController()
        hostVC.view.backgroundColor = .systemBackground
        hostVC.title = moduleName
        do {
            _ = try RNBundleMount.mount(
                in: hostVC.view,
                request: RNBundleMountRequest(
                    moduleName: moduleName,
                    pageBundleURL: pageURL,
                    commonBundleURL: commonURL,
                    initialProperties: props
                )
            )
            navigationController?.pushViewController(hostVC, animated: true)
                ?? present(UINavigationController(rootViewController: hostVC), animated: true)
            infoLabel.text = """
            已挂载:
            common: \(commonURL?.absoluteString ?? "(无)")
            page: \(pageURL.absoluteString)
            moduleName=\(moduleName)
            """
        } catch {
            infoLabel.text = "挂载失败: \(error.localizedDescription)\npage=\(pageURL.absoluteString)"
        }
    }

    @objc private func onClearCache() {
        do {
            try cache.clearAll()
            infoLabel.text = "已清除本地分包缓存目录: \(cache.cacheDirectory.path)"
        } catch {
            infoLabel.text = "清缓存失败: \(error.localizedDescription)"
        }
    }
}
