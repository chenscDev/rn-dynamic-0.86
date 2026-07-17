import Foundation

#if canImport(React)
import React
#endif

/**
 * RN 根视图挂载辅助。
 * 宿主工程集成 React Native 0.86 后，本文件在 canImport(React) 下提供真实挂载；
 * 未链接 RN 时提供明确错误，避免静默失败。
 */
public enum RNBundleMountError: Error, LocalizedError {
    case reactNotLinked
    case invalidBundleURL(String)
    case mountFailed(String)

    public var errorDescription: String? {
        switch self {
        case .reactNotLinked:
            return "宿主未链接 React Native，无法创建 RCTRootView。请集成 RN 0.86 后再使用挂载 API。"
        case .invalidBundleURL(let url):
            return "非法 bundle URL: \(url)"
        case .mountFailed(let reason):
            return "挂载 RN 失败: \(reason)"
        }
    }
}

public struct RNBundleMountRequest {
    public let moduleName: String
    public let pageBundleURL: URL
    /// 若提供，则先加载 common 再加载 page（双包）
    public let commonBundleURL: URL?
    public let initialProperties: [String: Any]

    public init(
        moduleName: String,
        pageBundleURL: URL,
        commonBundleURL: URL? = nil,
        initialProperties: [String: Any] = [:]
    ) {
        self.moduleName = moduleName
        self.pageBundleURL = pageBundleURL
        self.commonBundleURL = commonBundleURL
        self.initialProperties = initialProperties
    }
}

public enum RNBundleMount {
    /**
     * 在 container 中挂载 RN 根视图。
     * - 仅 page：直接用 pageBundleURL 创建 RCTRootView
     * - common + page：以 common 为 bridge 入口，再执行 page 脚本后展示 moduleName
     */
    @discardableResult
    public static func mount(
        in container: UIView,
        request: RNBundleMountRequest
    ) throws -> UIView {
        #if canImport(React)
        do {
            if let commonURL = request.commonBundleURL {
                return try mountDualBundle(
                    in: container,
                    commonURL: commonURL,
                    pageURL: request.pageBundleURL,
                    moduleName: request.moduleName,
                    initialProperties: request.initialProperties
                )
            }
            return try mountSingleBundle(
                in: container,
                bundleURL: request.pageBundleURL,
                moduleName: request.moduleName,
                initialProperties: request.initialProperties
            )
        } catch {
            throw RNBundleMountError.mountFailed(error.localizedDescription)
        }
        #else
        throw RNBundleMountError.reactNotLinked
        #endif
    }

    #if canImport(React)
    private static func mountSingleBundle(
        in container: UIView,
        bundleURL: URL,
        moduleName: String,
        initialProperties: [String: Any]
    ) throws -> UIView {
        let rootView = RCTRootView(
            bundleURL: bundleURL,
            moduleName: moduleName,
            initialProperties: initialProperties,
            launchOptions: nil
        )
        rootView.frame = container.bounds
        rootView.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        container.addSubview(rootView)
        return rootView
    }

    private static func mountDualBundle(
        in container: UIView,
        commonURL: URL,
        pageURL: URL,
        moduleName: String,
        initialProperties: [String: Any]
    ) throws -> UIView {
        // 以 common 作为 bridge 主包，bridge 就绪后注入 page 脚本
        guard let bridge = RCTBridge(bundleURL: commonURL, moduleProvider: nil, launchOptions: nil) else {
            throw RNBundleMountError.mountFailed("无法创建 RCTBridge(common)")
        }

        // 同步加载 page 源码并执行（正式包为本地文件；调试可为 http）
        let pageData: Data
        if pageURL.isFileURL {
            pageData = try Data(contentsOf: pageURL)
        } else {
            pageData = try Data(contentsOf: pageURL)
        }
        bridge.batchedBridge?.executeSourceCode(pageData, withSourceURL: pageURL)

        let rootView = RCTRootView(
            bridge: bridge,
            moduleName: moduleName,
            initialProperties: initialProperties
        )
        rootView.frame = container.bounds
        rootView.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        container.addSubview(rootView)
        return rootView
    }
    #endif
}
