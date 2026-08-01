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

  @objc func setStatusBarVisible(_ visible: Bool) {
    DispatchQueue.main.async {
      MainShellViewController.shared?.applyStatusBarChrome(visible: visible)
    }
  }

  @objc func setStatusBarStyle(_ backgroundColor: String?, lightContent: Bool) {
    DispatchQueue.main.async {
      MainShellViewController.shared?.applyStatusBarChrome(
        backgroundColor: Self.parseColor(backgroundColor),
        lightContent: lightContent
      )
    }
  }

  @objc func setNativeTitle(_ title: String?, backgroundColor: String?, textColor: String?) {
    DispatchQueue.main.async {
      MainShellViewController.shared?.applyNativeTitle(
        title: title,
        backgroundColor: Self.parseColor(backgroundColor),
        textColor: Self.parseColor(textColor),
        visible: (title?.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty == false) ? true : nil
      )
    }
  }

  @objc func setNativeTitleVisible(_ visible: Bool) {
    DispatchQueue.main.async {
      MainShellViewController.shared?.applyNativeTitle(visible: visible)
    }
  }

  /// 一次性配置原生 Chrome（推荐）
  @objc func setChrome(_ options: NSDictionary?) {
    guard let options else { return }
    DispatchQueue.main.async {
      let shell = MainShellViewController.shared
      if let tabVisible = options["tabBarVisible"] as? Bool {
        shell?.setTabBarVisible(tabVisible)
      }
      if let titleVisible = options["titleVisible"] as? Bool {
        shell?.applyNativeTitle(visible: titleVisible)
      }
      let title = options["title"] as? String
      let titleBg = Self.parseColor(options["titleBackgroundColor"] as? String)
      let titleFg = Self.parseColor(options["titleTextColor"] as? String)
      if title != nil || titleBg != nil || titleFg != nil {
        shell?.applyNativeTitle(
          title: title,
          backgroundColor: titleBg,
          textColor: titleFg,
          visible: (options["titleVisible"] as? Bool)
            ?? ((title?.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty == false) ? true : nil)
        )
      }
      let statusVisible = options["statusBarVisible"] as? Bool
      let statusBg = Self.parseColor(options["statusBarBackgroundColor"] as? String)
      let lightContent = options["statusBarLightContent"] as? Bool
      if statusVisible != nil || statusBg != nil || lightContent != nil {
        shell?.applyStatusBarChrome(
          visible: statusVisible,
          backgroundColor: statusBg,
          lightContent: lightContent
        )
      }
    }
  }

  private static func parseColor(_ raw: String?) -> UIColor? {
    guard var s = raw?.trimmingCharacters(in: .whitespacesAndNewlines), !s.isEmpty else {
      return nil
    }
    if s.hasPrefix("#") { s.removeFirst() }
    var value: UInt64 = 0
    guard Scanner(string: s).scanHexInt64(&value) else { return nil }
    switch s.count {
    case 6:
      return UIColor(
        red: CGFloat((value >> 16) & 0xFF) / 255,
        green: CGFloat((value >> 8) & 0xFF) / 255,
        blue: CGFloat(value & 0xFF) / 255,
        alpha: 1
      )
    case 8:
      return UIColor(
        red: CGFloat((value >> 16) & 0xFF) / 255,
        green: CGFloat((value >> 8) & 0xFF) / 255,
        blue: CGFloat(value & 0xFF) / 255,
        alpha: CGFloat((value >> 24) & 0xFF) / 255
      )
    default:
      return nil
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
