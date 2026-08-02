/**
 * iOS 登录桥（对齐 Android RNAuthModule；Mock: admin / 123456）
 */
import Foundation
import UIKit
import React

@objc(RNAuthModule)
final class RNAuthModule: NSObject {
  @objc static func requiresMainQueueSetup() -> Bool { true }

  @objc func getAuthHeaders(
    _ resolve: @escaping RCTPromiseResolveBlock,
    rejecter reject: @escaping RCTPromiseRejectBlock
  ) {
    resolve(AuthSession.authHeaders())
  }

  @objc func getSession(
    _ resolve: @escaping RCTPromiseResolveBlock,
    rejecter reject: @escaping RCTPromiseRejectBlock
  ) {
    resolve(sessionMap())
  }

  @objc func getLoginReason(
    _ resolve: @escaping RCTPromiseResolveBlock,
    rejecter reject: @escaping RCTPromiseRejectBlock
  ) {
    resolve(NSNull())
  }

  @objc func login(
    _ username: String,
    password: String,
    resolver resolve: @escaping RCTPromiseResolveBlock,
    rejecter reject: @escaping RCTPromiseRejectBlock
  ) {
    DispatchQueue.global(qos: .userInitiated).async {
      Thread.sleep(forTimeInterval: 0.35)
      let user = username.trimmingCharacters(in: .whitespacesAndNewlines)
      if user == "admin", password == "123456" {
        let token = "mock-token-\(Int(Date().timeIntervalSince1970 * 1000))"
        AuthSession.saveLogin(token: token, userId: "U10001", nickname: "内测用户")
        DispatchQueue.main.async {
          AuthGate.enterShell(animated: true)
          resolve(self.sessionMap())
        }
      } else {
        reject("UNAUTHORIZED", "用户名或密码错误（Mock: admin / 123456）", nil)
      }
    }
  }

  @objc func changePassword(
    _ oldPassword: String,
    newPassword: String,
    resolver resolve: @escaping RCTPromiseResolveBlock,
    rejecter reject: @escaping RCTPromiseRejectBlock
  ) {
    DispatchQueue.global(qos: .userInitiated).async {
      Thread.sleep(forTimeInterval: 0.25)
      if oldPassword != "123456" {
        reject("API_ERROR", "原密码错误", nil)
        return
      }
      if newPassword.count < 6 {
        reject("API_ERROR", "新密码至少 6 位", nil)
        return
      }
      resolve(NSNull())
    }
  }

  @objc func completeLoginNavigation(
    _ resolve: @escaping RCTPromiseResolveBlock,
    rejecter reject: @escaping RCTPromiseRejectBlock
  ) {
    DispatchQueue.main.async {
      if AuthSession.isLoggedIn() {
        AuthGate.enterShell(animated: true)
      }
      resolve(NSNull())
    }
  }

  @objc func openLogin(_ reason: String?) {
    DispatchQueue.main.async {
      if AuthSession.isLoggedIn() {
        return
      }
      guard let window = UIApplication.shared.connectedScenes
        .compactMap({ $0 as? UIWindowScene })
        .flatMap(\.windows)
        .first(where: { $0.isKeyWindow }),
            let loginRoot = AuthGate.makeLoginNavigation()
      else {
        return
      }
      window.rootViewController = loginRoot
    }
  }

  @objc func mockRequest(
    _ path: String,
    resolver resolve: @escaping RCTPromiseResolveBlock,
    rejecter reject: @escaping RCTPromiseRejectBlock
  ) {
    DispatchQueue.global(qos: .userInitiated).async {
      Thread.sleep(forTimeInterval: 0.2)
      guard AuthSession.isLoggedIn() else {
        reject("UNAUTHORIZED", "未登录", nil)
        return
      }
      resolve([
        "path": path,
        "ok": true,
        "message": "mock ok",
      ] as [String: Any])
    }
  }

  private func sessionMap() -> [String: Any] {
    [
      "isLoggedIn": AuthSession.isLoggedIn(),
      "token": AuthSession.getToken() as Any,
      "userId": AuthSession.getUserId() as Any,
      "nickname": AuthSession.getNickname() as Any,
    ]
  }
}
