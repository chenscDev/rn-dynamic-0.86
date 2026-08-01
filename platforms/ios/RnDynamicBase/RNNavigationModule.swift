/**
 * RN 导航桥：切 Tab / 关容器 / 打开业务分包（对齐 Android RNNavigationModule）
 */
import Foundation
import UIKit

@objc(RNNavigationModule)
final class RNNavigationModule: NSObject {
  @objc static func requiresMainQueueSetup() -> Bool { true }

  @objc func switchTab(_ tabId: String) {
    let id = tabId.trimmingCharacters(in: .whitespacesAndNewlines)
    guard !id.isEmpty else { return }
    DispatchQueue.main.async {
      MainShellViewController.shared?.switchToTab(id)
    }
  }

  @objc func setTabBarVisible(_ visible: Bool) {
    DispatchQueue.main.async {
      MainShellViewController.shared?.setTabBarVisible(visible)
    }
  }

  @objc func finishContainer() {
    DispatchQueue.main.async {
      guard let top = Self.topViewController() else { return }
      if let nav = top.navigationController, nav.presentingViewController != nil {
        nav.dismiss(animated: true)
      } else if top.presentingViewController != nil {
        top.dismiss(animated: true)
      }
    }
  }

  @objc func openBundle(_ bundleKey: String, title: String?, props: NSDictionary?) {
    let key = bundleKey.trimmingCharacters(in: .whitespacesAndNewlines)
    guard !key.isEmpty else { return }
    var dict: [String: Any] = [:]
    if let props {
      for (k, v) in props {
        if let ks = k as? String {
          dict[ks] = v
        }
      }
    }
    DispatchQueue.main.async {
      if let shell = MainShellViewController.shared {
        shell.openBundle(key: key, title: title, props: dict.isEmpty ? nil : dict)
        return
      }
      // 无 Shell 时降级：直接 present Host
      guard let root = ShellConfigLoader.configRootURL(),
            let cache = try? RNBundleCache() else { return }
      let store = RNBundleConfigStore(configURL: root, channel: ShellConfigLoader.defaultChannel)
      let host = RNBundleHostViewController(
        request: RNOpenBundleRequest(
          key: key,
          channel: ShellConfigLoader.defaultChannel,
          initialProps: dict
        ),
        configStore: store,
        cache: cache
      )
      host.title = (title?.isEmpty == false ? title : key) ?? key
      let nav = UINavigationController(rootViewController: host)
      nav.modalPresentationStyle = .fullScreen
      Self.topViewController()?.present(nav, animated: true)
    }
  }

  private static func topViewController(
    base: UIViewController? = {
      let scene = UIApplication.shared.connectedScenes
        .compactMap { $0 as? UIWindowScene }
        .first { $0.activationState == .foregroundActive }
        ?? UIApplication.shared.connectedScenes
          .compactMap { $0 as? UIWindowScene }
          .first
      let window = scene?.windows.first { $0.isKeyWindow } ?? scene?.windows.first
      return window?.rootViewController
    }()
  ) -> UIViewController? {
    if let nav = base as? UINavigationController {
      return topViewController(base: nav.visibleViewController)
    }
    if let tab = base as? UITabBarController {
      return topViewController(base: tab.selectedViewController)
    }
    if let presented = base?.presentedViewController {
      return topViewController(base: presented)
    }
    return base
  }
}
