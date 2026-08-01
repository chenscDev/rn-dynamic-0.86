/**
 * iOS Shell 配置：读取内置 shell.local.json / remote.local.json
 */
import Foundation

struct ShellTabConfig: Codable {
  let id: String
  let title: String
  let type: String
  let bundleKey: String?
  let nativeKey: String?
  let icon: String?
  let visible: Bool?
  let order: Int?

  var isVisible: Bool { visible ?? true }
}

struct ShellConfigFile: Codable {
  let rnVersion: String?
  let channel: String?
  let updatedAt: String?
  let tabs: [ShellTabConfig]

  var visibleTabs: [ShellTabConfig] {
    tabs.filter(\.isVisible).sorted { ($0.order ?? 0) < ($1.order ?? 0) }
  }
}

struct RemoteLocalSettings: Codable {
  let enabled: Bool?
  let baseUrl: String?
  let rnVersion: String?
}

enum ShellConfigLoader {
  static let defaultChannel = "agent-docx"

  /// Bundle 内 rn-config 根目录
  static func configRootURL() -> URL? {
    Bundle.main.resourceURL?
      .appendingPathComponent("rn-config", isDirectory: true)
  }

  static func channelDir(channel: String = defaultChannel) -> URL? {
    configRootURL()?
      .appendingPathComponent("channels", isDirectory: true)
      .appendingPathComponent(channel, isDirectory: true)
  }

  static func loadShell(channel: String = defaultChannel) throws -> ShellConfigFile {
    guard let url = channelDir(channel: channel)?
      .appendingPathComponent("shell.local.json") else {
      throw NSError(
        domain: "ShellConfig",
        code: 1,
        userInfo: [NSLocalizedDescriptionKey: "未找到 rn-config 目录"]
      )
    }
    let data = try Data(contentsOf: url)
    return try JSONDecoder().decode(ShellConfigFile.self, from: data)
  }

  static func loadRemoteSettings(channel: String = defaultChannel) -> RemoteLocalSettings? {
    guard let url = channelDir(channel: channel)?
      .appendingPathComponent("remote.local.json"),
      FileManager.default.fileExists(atPath: url.path),
      let data = try? Data(contentsOf: url)
    else {
      return nil
    }
    return try? JSONDecoder().decode(RemoteLocalSettings.self, from: data)
  }
}
