import UIKit

/**
 本地 / 测试环境调试入口页面

 - 本地：填写 host + port + key，走 Metro（可使用 RN 自带调试）
 - 测试：关闭 DevServer，填写测试环境分包 URL，并展示强制清缓存等能力
 */
public final class RNDebugEntryViewController: UIViewController {
    private let hostField = UITextField()
    private let portField = UITextField()
    private let keyField = UITextField()
    private let urlField = UITextField()
    private let devSwitch = UISwitch()
    private let infoLabel = UILabel()
    private let cache: RNBundleCache

    public init(cache: RNBundleCache) {
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

        urlField.placeholder = "测试环境 bundle URL（非 DevServer 时必填）"
        urlField.borderStyle = .roundedRect
        urlField.autocapitalizationType = .none

        devSwitch.isOn = true

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
            labeled("DevServer", devSwitch),
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
        let mode = devSwitch.isOn ? "本地 Metro（可用 RN DevTools）" : "测试包 URL"
        infoLabel.text = """
        模式: \(mode)
        Metro 示例: http://host:port/src/key/index.bundle?platform=ios&dev=true
        正式环境请使用配置入口，不要暴露本页面。
        """
    }

    @objc private func onOpen() {
        refreshInfo()
        let port = Int(portField.text ?? "8081") ?? 8081
        let key = (keyField.text ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        guard !key.isEmpty else {
            infoLabel.text = "请填写分包 key"
            return
        }

        let request = RNDebugEntryRequest(
            host: hostField.text ?? "localhost",
            port: port,
            key: key,
            useDevServer: devSwitch.isOn,
            bundleURL: urlField.text,
            initialProps: ["fromNative": "debug-entry"]
        )

        if request.useDevServer {
            guard let metroURL = request.metroBundleURL else {
                infoLabel.text = "无法构造 Metro URL"
                return
            }
            infoLabel.text = """
            准备连接 Metro:
            \(metroURL.absoluteString)
            moduleName=\(key)

            宿主接入后在此创建 RCTRootView(bundleURL: metroURL, moduleName: key, ...)
            本地请先在 packages/base 执行 yarn start。
            """
        } else {
            let url = (request.bundleURL ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
            guard !url.isEmpty else {
                infoLabel.text = "测试模式请填写 Bundle URL"
                return
            }
            infoLabel.text = """
            准备加载测试包:
            url=\(url)
            moduleName=\(key)
            建议配合 hash 校验与清缓存按钮验证强制更新。
            """
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
