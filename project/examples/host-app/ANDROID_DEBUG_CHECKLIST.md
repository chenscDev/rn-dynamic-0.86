# Android 宿主内测包清单（扫码安装 + RN 调试）

面向：已接入 `rn-dynamic-0.86` SDK 的 Android 宿主，打 Debug/内测 APK，扫码安装后连接 `rn-biz-0.86` Metro 做 RN 开发调试。

---

## A. 工程接入（打 APK 前）

- [ ] 宿主 RN 版本锁定 **0.86.0**（与 `rn-biz-0.86` 一致）
- [ ] 已拷贝并编译 SDK：`native/android/rn-bundle-loader/.../com/rndynamic/loader/*`
- [ ] `AndroidManifest.xml` 注册 Activity（建议仅 Debug/内测 flavor 暴露调试页）：

```xml
<activity
  android:name="com.rndynamic.loader.RNDebugEntryActivity"
  android:exported="true"
  android:label="RN调试" />

<activity
  android:name="com.rndynamic.loader.RNBundleHostActivity"
  android:exported="false" />
```

- [ ] 主页或「我的」页能跳到 `RNDebugEntryActivity`（正式包隐藏该入口）
- [ ] 已集成 React Native 0.86 运行时（`ReactInstanceManager` / `ReactRootView` 等宿主侧依赖齐全）
- [ ] `usesCleartextTraffic` 或网络安全配置允许访问 Metro（`http://局域网IP:8081`）
  - Debug 建议：`android:usesCleartextTraffic="true"`
  - 或 `network_security_config` 放行开发网段
- [ ] 如需读本地配置：能读到 config 目录或 `channels/<channel>/bundles.local.json`

---

## B. 打可扫码安装的包

- [ ] 使用 Debug / 内测签名
- [ ] 构建：

```bash
cd platforms/android
./gradlew assembleInternalRelease
# 或本地调试：assembleInternalDebug
# 或一键脚本（仓库根目录）：
#   ./scripts/build-pgyer-apk.sh
```

- [ ] 产物：`app/build/outputs/apk/internal/release/*.apk`
- [ ] 上传蒲公英 / fir / 内网静态站，生成安装二维码
- [ ] 手机扫码安装成功（允许「未知来源」）
- [ ] 能打开 App：**首页 / 我的** 为原生页（无需 Metro）；RN 开发走 **我的 → RN 调试入口**

---

## C. 电脑侧（RN 开发）

- [ ] 电脑与手机同一 Wi‑Fi
- [ ] 查电脑局域网 IP：

```bash
ipconfig getifaddr en0
# 例：192.168.1.23
```

- [ ] 启动业务仓 Metro：

```bash
cd rn-biz-0.86
yarn start
```

- [ ] 本机防火墙放行 **8081**（必要时临时关闭验证）
- [ ] 手机浏览器访问 `http://<电脑IP>:8081/status` 能打开（确认网络通）

---

## D. 手机调试页填写（真机）

| 字段 | 建议值 |
|------|--------|
| Host | 电脑局域网 IP（**不要**填 `10.0.2.2`，那是模拟器专用） |
| Port | `8081` |
| Key | `home` / `demo` / `order` |
| Channel | 本地 Metro 可填 `main`；验 CDN 发测再填分支名 |
| DevServer | **打开** |
| 加载 common | **打开** |

- [ ] 点「打开分包」能出 RN 页面（Metro 直连，支持热更新）
- [ ] 改 `rn-biz-0.86/src/**` 保存后 Fast Refresh / 摇一摇 Reload 生效
- [ ] 摇一摇能出 Dev Menu（Reload、Open Debugger / DevTools）

Intent 正式入口（可选）：

```kotlin
startActivity(
  Intent(this, RNBundleHostActivity::class.java)
    .putExtra(RNBundleHostActivity.EXTRA_KEY, "home")
    .putExtra(RNBundleHostActivity.EXTRA_CHANNEL, "order-main")
    .putExtra(RNBundleHostActivity.EXTRA_CONFIG_PATH, configDir.absolutePath)
)
```

---

## E. 发测 CDN 包（可选，关 DevServer）

- [ ] 业务仓发布：

```bash
cd rn-biz-0.86
yarn pack:publish:common --platform android --channel feature-xxx
yarn pack:publish home --platform android --channel feature-xxx
```

- [ ] CDN / 本地镜像 URL 手机可访问
- [ ] 调试页：**关闭 DevServer**，填 Channel 或 Bundle URL
- [ ] 能按 `dependsOn` 拉到该 channel 的 common + page

CDN 路径约定：`rn/{rnVersion}/{channel}/{key}/{platform}/...`  
配置：`project/config/channels/<channel>/bundles.local.json`

---

## F. 常见问题

| 现象 | 处理 |
|------|------|
| 一直转圈 / Unable to load script | 检查 IP、Wi‑Fi、8081、明文 HTTP |
| 模拟器可以、真机不行 | 真机 Host 必须用局域网 IP |
| 挂载失败 / ClassNotFound | 宿主未正确集成 RN 0.86 |
| 改 JS 无变化 | Metro 是否连上该手机；Dev Menu → Reload |
| 正式用户看到调试页 | 仅 Debug/内测 flavor 注册并暴露入口 |

---

## G. 何时重新扫码装包

| 变更 | 要不要重装 APK |
|------|----------------|
| 只改 RN 业务 / 样式 | **否**，`yarn start` 即可 |
| 改原生 SDK / 权限 / RN 版本 / 包名 | **是**，重打 APK 再扫码 |

---

## 最小闭环

内测 APK 扫码安装 → **首页 / 我的** 原生可离线查看 → **我的 → RN 调试入口** → 电脑 `yarn start` → 真机填 `IP:8081` → 打开 `home` 出页。

相关文档：

- [宿主接入说明](./README.md)
- 业务仓 Channel 约定：`rn-biz-0.86/project/docs/CHANNEL.md`
- 业务仓调试约定：`rn-biz-0.86/project/docs/DEBUG.md`

---

## H. 本仓库一键打 APK

本机需已安装 **Android Studio**（含 SDK）与 JDK。

```bash
cd rn-dynamic-0.86

# 蒲公英内测（internalRelease）
./scripts/build-pgyer-apk.sh
# 产物：project/dist/apk/RnDynamicBase-internal-release.apk

# 本地调试 / 浏览器 DevTools（internalDebug）
./scripts/build-debug-apk.sh
# 产物：project/dist/apk/RnDynamicBase-internal-debug.apk
```

说明：包名均为 `com.rndynamicbase`。原生 Tab（首页/我的）安装即可用；RN 联调从「我的 → RN 调试入口」连电脑 Metro。需要 Metro 按 `j` 开浏览器 DevTools 时请用 **debug** 包。完整业务宿主调试入口仍按上文 A–D 接入你们自己的 App。
