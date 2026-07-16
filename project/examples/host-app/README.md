# 宿主接入说明（纯原生嵌 RN）

本目录描述如何把 `rn-dynamic-0.86` 接到已有原生 App。第一期提供 SDK 源码与接入步骤，不强制生成完整可编译宿主工程（避免本机未装 CocoaPods / Android SDK 时阻塞基座搭建）。

## 前置条件

1. 宿主集成的 React Native 版本必须与基座一致：**0.86.0**
2. 将以下源码加入宿主工程：
   - iOS: `native/ios/RNBundleLoader/Sources/*`
   - Android: `native/android/rn-bundle-loader/src/main/java/com/rndynamic/loader/*`
3. 将 `project/config/bundles.local.json` 拷贝进 App 可读取位置（或后续改为接口下发）

## 正式入口（配置驱动 / Mode A）

### iOS

```swift
let configURL = Bundle.main.url(forResource: "bundles.local", withExtension: "json")!
let store = RNBundleConfigStore(configURL: configURL)
let cache = try RNBundleCache()
let vc = RNBundleHostViewController(
  request: RNOpenBundleRequest(key: "home", initialProps: ["fromNative": "home-entry"]),
  configStore: store,
  cache: cache
)
navigationController?.pushViewController(vc, animated: true)
```

在 `RNBundleHostViewController` 标注的 TODO 处，使用 `RCTRootView`（或你们 RN 0.86 模板中的 Root API）挂载：

- `bundleURL` = 缓存后的本地 file URL
- `moduleName` = 分包 key（如 `home`）

### Android

```kotlin
startActivity(
  Intent(this, RNBundleHostActivity::class.java)
    .putExtra(RNBundleHostActivity.EXTRA_KEY, "home")
    .putExtra(RNBundleHostActivity.EXTRA_CONFIG_PATH, configFile.absolutePath)
)
```

在 Activity 分包就绪后，用 `ReactRootView` + `JSBundleLoader.createFileLoader(path)` 启动对应 `moduleName`。

## 调试入口

仅内测包 / Debug 包暴露：

- iOS: `RNDebugEntryViewController`
- Android: `RNDebugEntryActivity`

| 场景 | 操作 |
|------|------|
| 本地 | DevServer ON，填 host/port/key，连 Metro |
| 测试 | DevServer OFF，填测试 URL；可清缓存验证 hash 更新 |
| 正式 | 不要展示调试页 |

## 发布关系

- **原生发版**：RN 升级、SDK 变更、新增原生能力时
- **RN 发版**：`rn-pack build <key>` → 更新配置中的 url/hash → 客户端下次打开拉新包（无需等商店审核）
