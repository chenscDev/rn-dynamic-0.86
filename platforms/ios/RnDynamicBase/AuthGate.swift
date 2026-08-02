/**
 * iOS 启动门禁：已登录进 Shell；未登录且有 login 分包则进登录页；否则降级进 Shell（内测友好）
 */
import UIKit

enum AuthGate {
  static func makeRootViewController(channel: String = ShellConfigLoader.defaultChannel) -> UIViewController {
    if AuthSession.isLoggedIn() {
      return MainShellViewController(channel: channel)
    }

    // 无 login 分包时不挡启动（CDN/示例配置可能尚未发布 ios login）
    guard let loginVC = makeLoginController(channel: channel) else {
      return MainShellViewController(channel: channel)
    }
    let nav = UINavigationController(rootViewController: loginVC)
    nav.modalPresentationStyle = .fullScreen
    return nav
  }

  /// 登录成功后切到 Shell
  static func enterShell(animated: Bool = true) {
    guard let window = keyWindow() else { return }
    let shell = MainShellViewController(channel: ShellConfigLoader.defaultChannel)
    if animated {
      UIView.transition(
        with: window,
        duration: 0.25,
        options: .transitionCrossDissolve,
        animations: { window.rootViewController = shell },
        completion: nil
      )
    } else {
      window.rootViewController = shell
    }
  }

  /// 仅构建登录导航（无 login 包时返回 nil，不降级到 Shell）
  static func makeLoginNavigation(channel: String = ShellConfigLoader.defaultChannel) -> UINavigationController? {
    guard let loginVC = makeLoginController(channel: channel) else { return nil }
    let nav = UINavigationController(rootViewController: loginVC)
    nav.modalPresentationStyle = .fullScreen
    return nav
  }

  private static func makeLoginController(channel: String) -> UIViewController? {
    do {
      let cache = try RNBundleCache()
      guard let store = makeConfigStore(channel: channel, cache: cache) else {
        return nil
      }
      // 探测是否存在 ios login 分包
      _ = try store.item(forKey: "login", platform: "ios")
      let host = RNBundleHostViewController(
        request: RNOpenBundleRequest(
          key: "login",
          channel: channel,
          initialProps: [
            "fromNative": "ios-auth-gate",
            "channel": channel,
            "loginReason": "cold_start",
          ]
        ),
        configStore: store,
        cache: cache
      )
      host.title = "登录"
      return host
    } catch {
      return nil
    }
  }

  private static func makeConfigStore(channel: String, cache: RNBundleCache) -> RNBundleConfigStore? {
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
      if let remoteConfig = try? remoteStore.load(),
         let data = try? JSONEncoder().encode(remoteConfig) {
        let tmp = cacheDir
          .appendingPathComponent("rn-config-runtime", isDirectory: true)
          .appendingPathComponent(channel, isDirectory: true)
        try? FileManager.default.createDirectory(at: tmp, withIntermediateDirectories: true)
        let file = tmp.appendingPathComponent("bundles.local.json")
        try? data.write(to: file, options: .atomic)
        let store = RNBundleConfigStore(configURL: file, channel: channel)
        if (try? store.item(forKey: "login", platform: "ios")) != nil {
          return store
        }
      }
    }
    guard let root = ShellConfigLoader.configRootURL() else { return nil }
    return RNBundleConfigStore(configURL: root, channel: channel)
  }

  private static func keyWindow() -> UIWindow? {
    UIApplication.shared.connectedScenes
      .compactMap { $0 as? UIWindowScene }
      .flatMap(\.windows)
      .first { $0.isKeyWindow }
      ?? UIApplication.shared.connectedScenes
        .compactMap { $0 as? UIWindowScene }
        .flatMap(\.windows)
        .first
  }
}

private extension String {
  var nilIfEmpty: String? {
    let t = trimmingCharacters(in: .whitespacesAndNewlines)
    return t.isEmpty ? nil : t
  }
}
