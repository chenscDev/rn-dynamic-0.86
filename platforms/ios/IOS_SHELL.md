# iOS 内测壳（MainShell）

对齐 Android `MainShellActivity`：底部 Tab（首页 / 问答 / 我的）+ `RNNavigationModule` + 调试入口。

## 本地跑通

1. 业务仓先打出 iOS 分包（至少 `common` / `home` / `docs-agent`）：

```bash
cd /path/to/rn-biz-0.86
yarn pack:publish common --platform ios --channel agent-docx
yarn pack:publish home --platform ios --channel agent-docx
yarn pack:publish docs-agent --platform ios --channel agent-docx
```

2. 更新宿主内置配置（可选）：把 `project/config/channels/agent-docx/bundles.local.json` 中 **platform=ios** 条目同步到  
   `platforms/ios/RnDynamicBase/rn-config/channels/agent-docx/bundles.local.json`  
   可先复制同目录 `bundles.local.example.json` 为 `bundles.local.json`（已被 gitignore）。
   （模拟器可改成 `file://` 本地 dist；真机请用 CDN `http://…/cdn/rn/.../ios/...`）。

3. 打开工程并安装 Pods：

```bash
cd platforms/ios
pod install
open RnDynamicBase.xcworkspace
```

4. Xcode 选模拟器或真机 Run。启动后应看到底部三个 Tab。

## 能力对照

| 能力 | 状态 |
|------|------|
| 原生 Tab 壳 | ✅ `MainShellViewController` |
| `switchTab` / `openBundle` / `finishContainer` | ✅ `RNNavigationModule` |
| 我的 → RN 调试入口 | ✅ `RNDebugEntryViewController` |
| 相机/相册/麦克风隐私文案 | ✅ `Info.plist` |
| HTTP CDN ATS 例外 | ✅ `47.93.207.70` |
| 原生 `RNMediaPlayer` | ❌ 暂用 WebView 降级 |
| 远程配置仅含 Android 时 | ✅ 自动回退本地 `bundles.local.json` |

## 一键出片

问答分包调用 `switchNativeTab('home')`；iOS 壳收到后切到首页 Tab，并消费服务端 handoff。
