/**
 * iOS 内测壳：底部 Tab（首页 / 问答 / 我的），对齐 Android MainShellActivity
 */
import UIKit

final class MainShellViewController: UITabBarController, UITabBarControllerDelegate {
  /// 供 RNNavigationModule.switchTab 调用
  static weak var shared: MainShellViewController?

  private let channel: String
  private var tabIds: [String] = []
  private var tabConfigs: [ShellTabConfig] = []
  private let cache: RNBundleCache
  private var configStore: RNBundleConfigStore?
  private var chromeStatusBarHidden = false
  private var chromeStatusBarStyle: UIStatusBarStyle = .darkContent
  private let chromeTitleBar = UILabel()
  private var chromeTitleHeightConstraint: NSLayoutConstraint?

  init(channel: String = ShellConfigLoader.defaultChannel) {
    self.channel = RNBundleConfigStore.normalizeChannel(channel)
    do {
      self.cache = try RNBundleCache()
    } catch {
      // 缓存目录创建失败时仍继续，后续挂载会报错
      self.cache = (try? RNBundleCache(cacheDirectory: FileManager.default.temporaryDirectory))!
    }
    super.init(nibName: nil, bundle: nil)
  }

  @available(*, unavailable)
  required init?(coder: NSCoder) {
    fatalError("init(coder:) has not been implemented")
  }

  override var prefersStatusBarHidden: Bool { chromeStatusBarHidden }
  override var preferredStatusBarStyle: UIStatusBarStyle { chromeStatusBarStyle }
  // 由 Shell 自身控制状态栏，不被子 VC 覆盖
  override var childForStatusBarHidden: UIViewController? { nil }
  override var childForStatusBarStyle: UIViewController? { nil }

  override func viewDidLoad() {
    super.viewDidLoad()
    MainShellViewController.shared = self
    delegate = self
    view.backgroundColor = .systemBackground
    // 选中态：深色 tint；未选中灰色，保证对比明显
    tabBar.tintColor = UIColor(red: 0.06, green: 0.09, blue: 0.16, alpha: 1)
    tabBar.unselectedItemTintColor = UIColor(red: 0.58, green: 0.64, blue: 0.72, alpha: 1)
    if #available(iOS 15.0, *) {
      let appearance = UITabBarAppearance()
      appearance.configureWithOpaqueBackground()
      appearance.backgroundColor = .white
      appearance.stackedLayoutAppearance.selected.iconColor = tabBar.tintColor
      appearance.stackedLayoutAppearance.selected.titleTextAttributes = [
        .foregroundColor: tabBar.tintColor as Any,
        .font: UIFont.systemFont(ofSize: 11, weight: .semibold),
      ]
      appearance.stackedLayoutAppearance.normal.iconColor = tabBar.unselectedItemTintColor
      appearance.stackedLayoutAppearance.normal.titleTextAttributes = [
        .foregroundColor: tabBar.unselectedItemTintColor as Any,
        .font: UIFont.systemFont(ofSize: 11, weight: .regular),
      ]
      tabBar.standardAppearance = appearance
      tabBar.scrollEdgeAppearance = appearance
    }
    setupChromeTitleBar()
    bootstrap()
  }

  /// RN 桥：切换底部 Tab
  func switchToTab(_ tabId: String) {
    let id = tabId.trimmingCharacters(in: .whitespacesAndNewlines)
    guard let idx = tabIds.firstIndex(of: id) else { return }
    selectedIndex = idx
    notifyShellTabSelected(id)
  }

  /// RN 桥：全屏页隐藏/恢复底部 Tab
  func setTabBarVisible(_ visible: Bool) {
    tabBar.isHidden = !visible
    // 隐藏时扣掉 Tab 高度，避免底部留白；显示时清零
    if visible {
      additionalSafeAreaInsets.bottom = 0
    } else {
      additionalSafeAreaInsets.bottom = -tabBar.bounds.height
    }
    view.setNeedsLayout()
  }

  func tabBarController(_ tabBarController: UITabBarController, didSelect viewController: UIViewController) {
    let idx = tabBarController.selectedIndex
    guard idx >= 0, idx < tabIds.count else { return }
    notifyShellTabSelected(tabIds[idx])
  }

  private func notifyShellTabSelected(_ tabId: String) {
    let mountId: String
    if let tab = tabConfigs.first(where: { $0.id == tabId }) {
      mountId = (tab.bundleKey ?? tab.id)
    } else {
      mountId = tabId
    }
    NotificationCenter.default.post(
      name: .rnShellTabSelected,
      object: nil,
      userInfo: ["tabId": tabId, "mountId": mountId]
    )
  }

  /// RN 桥：原生标题栏
  func applyNativeTitle(
    title: String? = nil,
    backgroundColor: UIColor? = nil,
    textColor: UIColor? = nil,
    visible: Bool? = nil
  ) {
    if let title {
      chromeTitleBar.text = title
    }
    if let backgroundColor {
      chromeTitleBar.backgroundColor = backgroundColor
    }
    if let textColor {
      chromeTitleBar.textColor = textColor
    }
    let show: Bool
    if let visible {
      show = visible
    } else if let title, !title.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
      show = true
    } else {
      return
    }
    chromeTitleBar.isHidden = !show
    chromeTitleHeightConstraint?.constant = show ? 44 : 0
    additionalSafeAreaInsets.top = show ? 44 : 0
    view.layoutIfNeeded()
  }

  /// RN 桥：状态栏
  func applyStatusBarChrome(
    visible: Bool? = nil,
    backgroundColor: UIColor? = nil,
    lightContent: Bool? = nil
  ) {
    if let visible {
      chromeStatusBarHidden = !visible
    }
    if let lightContent {
      chromeStatusBarStyle = lightContent ? .lightContent : .darkContent
    }
    if let backgroundColor {
      view.backgroundColor = backgroundColor
    }
    setNeedsStatusBarAppearanceUpdate()
  }

  private func setupChromeTitleBar() {
    chromeTitleBar.translatesAutoresizingMaskIntoConstraints = false
    chromeTitleBar.textAlignment = .center
    chromeTitleBar.font = .systemFont(ofSize: 17, weight: .semibold)
    chromeTitleBar.textColor = UIColor(red: 0.07, green: 0.07, blue: 0.07, alpha: 1)
    chromeTitleBar.backgroundColor = .white
    chromeTitleBar.isHidden = true
    view.addSubview(chromeTitleBar)
    let height = chromeTitleBar.heightAnchor.constraint(equalToConstant: 0)
    chromeTitleHeightConstraint = height
    NSLayoutConstraint.activate([
      chromeTitleBar.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor),
      chromeTitleBar.leadingAnchor.constraint(equalTo: view.leadingAnchor),
      chromeTitleBar.trailingAnchor.constraint(equalTo: view.trailingAnchor),
      height,
    ])
  }

  private func bootstrap() {
    do {
      let shell = try ShellConfigLoader.loadShell(channel: channel)
      configStore = makeConfigStore()
      let tabs = shell.visibleTabs
      var controllers: [UIViewController] = []
      var ids: [String] = []

      for tab in tabs {
        let vc: UIViewController
        switch tab.type {
        case "rn-root":
          let key = (tab.bundleKey ?? tab.id).trimmingCharacters(in: .whitespacesAndNewlines)
          vc = makeRnRootController(tabId: tab.id, bundleKey: key, title: tab.title)
        case "native" where (tab.nativeKey ?? "") == "mine":
          vc = makeMineController(title: tab.title)
        default:
          // 未实现类型：占位，避免整壳崩溃
          let placeholder = UIViewController()
          placeholder.view.backgroundColor = .systemBackground
          let label = UILabel()
          label.text = "暂未接入：\(tab.title) (\(tab.type))"
          label.textAlignment = .center
          label.translatesAutoresizingMaskIntoConstraints = false
          placeholder.view.addSubview(label)
          NSLayoutConstraint.activate([
            label.centerXAnchor.constraint(equalTo: placeholder.view.centerXAnchor),
            label.centerYAnchor.constraint(equalTo: placeholder.view.centerYAnchor),
          ])
          vc = placeholder
        }
        let symbol = UIImage(systemName: symbolName(for: tab.id))
        vc.tabBarItem = UITabBarItem(
          title: tab.title,
          image: symbol?.withRenderingMode(.alwaysTemplate),
          selectedImage: symbol?.withRenderingMode(.alwaysTemplate)
        )
        controllers.append(vc)
        ids.append(tab.id)
      }

      tabIds = ids
      tabConfigs = tabs
      viewControllers = controllers
      if !controllers.isEmpty {
        selectedIndex = 0
        // 首个 Tab 也发一次，便于 home 冷启动拉 handoff
        notifyShellTabSelected(ids[0])
      }
      // 标题栏保持在最上层，避免被 Tab 内容盖住
      view.bringSubviewToFront(chromeTitleBar)
    } catch {
      presentFatal("Shell 启动失败", error.localizedDescription)
    }
  }

  /// 优先远程 CDN 配置，失败回退内置 bundles.local.json
  private func makeConfigStore() -> RNBundleConfigStore? {
    if let remote = ShellConfigLoader.loadRemoteSettings(channel: channel),
       remote.enabled == true,
       let base = (remote.baseUrl || "").trimmingCharacters(in: .whitespacesAndNewlines).nilIfEmpty,
       let baseURL = URL(string: base) {
      let rnVersion = (remote.rnVersion || "0.86.0").trimmingCharacters(in: .whitespacesAndNewlines)
      let cacheDir = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask).first
        ?? FileManager.default.temporaryDirectory
      let remoteStore = RNBundleRemoteConfigStore(
        configBaseURL: baseURL,
        rnVersion: rnVersion.isEmpty ? "0.86.0" : rnVersion,
        channel: channel,
        cacheDirectory: cacheDir
      )
      // 仅当远程配置含 iOS 分包时采用（避免被 Android-only 配置覆盖）
      if let remoteConfig = try? remoteStore.load(),
         Self.containsIOSBundles(remoteConfig),
         let data = try? JSONEncoder().encode(remoteConfig) {
        let tmp = cacheDir
          .appendingPathComponent("rn-config-runtime", isDirectory: true)
          .appendingPathComponent(channel, isDirectory: true)
        try? FileManager.default.createDirectory(at: tmp, withIntermediateDirectories: true)
        let file = tmp.appendingPathComponent("bundles.local.json")
        try? data.write(to: file, options: .atomic)
        return RNBundleConfigStore(configURL: file, channel: channel)
      }
    }

    guard let root = ShellConfigLoader.configRootURL() else { return nil }
    return RNBundleConfigStore(configURL: root, channel: channel)
  }

  private func makeRnRootController(tabId: String, bundleKey: String, title: String) -> UIViewController {
    guard let store = configStore else {
      return errorController("缺少分包配置 store")
    }
    let host = RNBundleHostViewController(
      request: RNOpenBundleRequest(
        key: bundleKey,
        channel: channel,
        initialProps: [
          "fromNative": "ios-shell-tab",
          "channel": channel,
          "shellTabId": tabId,
        ]
      ),
      configStore: store,
      cache: cache
    )
    host.title = title
    // Tab 内不需要再包一层导航栏标题栏（业务分包自带导航）
    return host
  }

  private func makeMineController(title: String) -> UIViewController {
    let mine = MineViewController(channel: channel, cache: cache)
    let nav = UINavigationController(rootViewController: mine)
    nav.navigationBar.prefersLargeTitles = false
    mine.title = title
    return nav
  }

  private func symbolName(for tabId: String) -> String {
    switch tabId {
    case "home": return "house"
    case "chat": return "bubble.left.and.bubble.right"
    case "mine": return "person"
    default: return "square.grid.2x2"
    }
  }

  private func errorController(_ message: String) -> UIViewController {
    let vc = UIViewController()
    vc.view.backgroundColor = .systemBackground
    let label = UILabel()
    label.text = message
    label.numberOfLines = 0
    label.textAlignment = .center
    label.translatesAutoresizingMaskIntoConstraints = false
    vc.view.addSubview(label)
    NSLayoutConstraint.activate([
      label.leadingAnchor.constraint(equalTo: vc.view.leadingAnchor, constant: 24),
      label.trailingAnchor.constraint(equalTo: vc.view.trailingAnchor, constant: -24),
      label.centerYAnchor.constraint(equalTo: vc.view.centerYAnchor),
    ])
    return vc
  }

  private func presentFatal(_ title: String, _ message: String) {
    let alert = UIAlertController(title: title, message: message, preferredStyle: .alert)
    alert.addAction(UIAlertAction(title: "知道了", style: .default))
    DispatchQueue.main.async { [weak self] in
      self?.present(alert, animated: true)
    }
  }

  /// 打开业务分包（整页 present，对齐 Android openBundle）
  func openBundle(key: String, title: String?, props: [String: Any]?) {
    guard let store = configStore ?? makeConfigStore() else {
      presentFatal("打开失败", "缺少分包配置")
      return
    }
    var initial = props ?? [:]
    initial["fromNative"] = initial["fromNative"] ?? "rn-open-bundle"
    initial["channel"] = channel
    let host = RNBundleHostViewController(
      request: RNOpenBundleRequest(
        key: key,
        channel: channel,
        initialProps: initial
      ),
      configStore: store,
      cache: cache
    )
    host.title = (title?.trimmingCharacters(in: .whitespacesAndNewlines)).flatMap { $0.isEmpty ? nil : $0 } ?? key
    let nav = UINavigationController(rootViewController: host)
    nav.modalPresentationStyle = .fullScreen
    present(nav, animated: true)
  }
}

private extension MainShellViewController {
  static func containsIOSBundles(_ config: RNBundlesConfigFile) -> Bool {
    for list in config.bundles.values {
      if list.contains(where: { $0.platform == "ios" }) {
        return true
      }
    }
    return false
  }
}

private extension String {
  var nilIfEmpty: String? {
    let t = trimmingCharacters(in: .whitespacesAndNewlines)
    return t.isEmpty ? nil : t
  }
}
