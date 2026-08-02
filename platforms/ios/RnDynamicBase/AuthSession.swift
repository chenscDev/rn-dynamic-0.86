/**
 * iOS 登录态（对齐 Android AuthSession：UserDefaults 存 token）
 */
import Foundation

enum AuthSession {
  private static let prefsSuite = "rndynamic_auth"
  private static let keyToken = "token"
  private static let keyUserId = "userId"
  private static let keyNickname = "nickname"

  private static var defaults: UserDefaults {
    UserDefaults(suiteName: prefsSuite) ?? .standard
  }

  static func isLoggedIn() -> Bool {
    !(getToken() ?? "").trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
  }

  static func getToken() -> String? {
    guard let raw = defaults.string(forKey: keyToken) else { return nil }
    let t = raw.trimmingCharacters(in: .whitespacesAndNewlines)
    return t.isEmpty ? nil : t
  }

  static func getUserId() -> String? {
    defaults.string(forKey: keyUserId)
  }

  static func getNickname() -> String? {
    defaults.string(forKey: keyNickname)
  }

  static func saveLogin(token: String, userId: String, nickname: String) {
    defaults.set(token, forKey: keyToken)
    defaults.set(userId, forKey: keyUserId)
    defaults.set(nickname, forKey: keyNickname)
    defaults.synchronize()
  }

  static func clearLogin() {
    defaults.removeObject(forKey: keyToken)
    defaults.removeObject(forKey: keyUserId)
    defaults.removeObject(forKey: keyNickname)
    defaults.synchronize()
  }

  static func authHeaders() -> [String: String] {
    guard let token = getToken() else { return [:] }
    return [
      "Authorization": "Bearer \(token)",
      "X-User-Id": getUserId() ?? "",
    ]
  }
}
