/**
 * 「我的」原生页：展示 channel，并进入 RN 调试入口
 */
import UIKit

final class MineViewController: UIViewController {
  private let channel: String
  private let cache: RNBundleCache

  init(channel: String, cache: RNBundleCache) {
    self.channel = channel
    self.cache = cache
    super.init(nibName: nil, bundle: nil)
  }

  @available(*, unavailable)
  required init?(coder: NSCoder) {
    fatalError("init(coder:) has not been implemented")
  }

  override func viewDidLoad() {
    super.viewDidLoad()
    view.backgroundColor = .systemBackground
    title = "我的"

    let info = UILabel()
    info.numberOfLines = 0
    info.font = .systemFont(ofSize: 14)
    info.textColor = .secondaryLabel
    info.text = """
    iOS 内测壳（对齐 Android MainShell）
    channel: \(channel)
    首页 / 问答：CDN 分包
    调试：可连本机 Metro
    """

    let debugBtn = UIButton(type: .system)
    debugBtn.setTitle("RN 调试入口", for: .normal)
    debugBtn.titleLabel?.font = .systemFont(ofSize: 17, weight: .semibold)
    debugBtn.addTarget(self, action: #selector(openDebug), for: .touchUpInside)

    let stack = UIStackView(arrangedSubviews: [info, debugBtn])
    stack.axis = .vertical
    stack.spacing = 20
    stack.translatesAutoresizingMaskIntoConstraints = false
    view.addSubview(stack)
    NSLayoutConstraint.activate([
      stack.leadingAnchor.constraint(equalTo: view.leadingAnchor, constant: 24),
      stack.trailingAnchor.constraint(equalTo: view.trailingAnchor, constant: -24),
      stack.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor, constant: 32),
    ])
  }

  @objc private func openDebug() {
    let root = ShellConfigLoader.configRootURL()
    let debug = RNDebugEntryViewController(cache: cache, configRootURL: root)
    // 默认填当前 channel，减少手输
    navigationController?.pushViewController(debug, animated: true)
  }
}
