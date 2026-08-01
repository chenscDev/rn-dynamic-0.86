# rn-dynamic-0.86

React Native **0.86** 动态分包基座：面向「纯原生 App 嵌 RN」场景，支持 **common + page 双包** 独立打包、CDN 上传与无感热更新加载。

> RN 版本锁定为 `0.86.0`，不可在本仓库内切换。其他 RN 版本请另建 `rn-dynamic-x.y` 基座线。

---

## 项目能做什么

| 能力 | 说明 |
|------|------|
| 固定 RN 版本基座 | 下载即可用，业务按约定目录扩展 |
| common + page 双包 | common 打公共依赖，page 为业务薄包，按 `dependsOn` 加载 |
| 按目录分包 | `src/<key>/` 为一个最小分包单元 |
| 整页打开（Mode A） | 原生一次打开一个分包全屏页（`RNBundleHostActivity`） |
| 分包内路由 | `createPackageApp` 支持 RN 内部页面跳转 |
| 无感发版 | `build → upload → 更新配置 url/hash`，APP 后台下载 + SHA-256 校验 |
| 原生 SDK | iOS / Android 配置读取、缓存、正式入口、调试入口 |
| 混合原生壳 | 原生 Tab（首页 / 我的）+ RN 调试入口，内测离线可用、开发可连 Metro |
| 独立发布 | RN 业务改动只发对应分包；原生发版走商店流程 |

---

## 架构概览

### 双包模型

```text
common.bundle（公共依赖，可预加载）
  ├── react / react-native / navigation ...
  └── 被所有 page 依赖

page.bundle（业务薄包，按 key 独立发版）
  ├── home / demo / order ...
  └── dependsOn: ["common"]
```

配置隔离维度：`rnVersion → channel → key → platform`

### 混合原生壳（Android 内测包）

```text
MainShellActivity（启动页）
├── 首页（NativeHomeFragment）   纯原生文案，安装即可查看，无需 Metro
└── 我的（NativeMineFragment）   用户信息 + 「RN 调试入口」
         └── RNDebugEntryActivity   填 IP:8081，从 Metro 拉指定分包做本地开发

正式业务入口（宿主集成）
└── RNBundleHostActivity         读 CDN 配置 → 下载 common + page → 挂载 RN
```

| 场景 | 入口 | 是否需要 Metro |
|------|------|----------------|
| 内测原生页 | 首页 / 我的 | 否 |
| RN 本地开发 | 我的 → RN 调试入口 | 是（`rn-biz-0.86` → `yarn start`） |
| 正式 / CDN 分包 | `RNBundleHostActivity` | 否（走 CDN + 本地缓存） |

---

## 目录结构

```
rn-dynamic-0.86/
├── app/                 # RN 基座壳入口（App.tsx / app.json / 测试）
├── src/                 # RN 业务分包与运行时
│   ├── _runtime/        # registerPage、createPackageApp、PageShell
│   ├── home/            # 示例分包
│   ├── demo/
│   └── order/           # 业务示例（列表 → 详情路由）
├── packages/            # 仅打包工具
│   ├── bundler/         # rn-pack CLI
│   └── schema/          # 配置 / manifest 类型
├── native/              # 宿主接入 SDK（ios / android）
├── platforms/           # 本地联调 RN 壳（android / ios）
│   └── android/app/     # MainShellActivity、internal flavor
├── project/             # 项目支撑
│   ├── config/          # bundles.local.json、upload.local.json
│   ├── docs/            # 约定与上传说明
│   ├── examples/        # 宿主接入说明
│   └── dist/            # 构建产物、APK、本地 CDN 镜像
├── scripts/             # build-pgyer-apk.sh 等
├── tooling/             # babel / metro / eslint 等工程配置实体
├── index.js             # Metro 默认入口
├── package.json
└── README.md
```

职责边界：

- **RN 业务**日常在同级仓 [`rn-biz-0.86`](../rn-biz-0.86) 开发；本仓 `src/` 为示例与协议参考
- **打包逻辑**只在 `packages/`
- **宿主原生 SDK**只在 `native/`
- **本地 RN 壳**在 `platforms/`（勿与 `native/` 混淆）

---

## 环境要求

- Node.js：`^22.13.0`（见 `.nvmrc`；RN 0.86 要求）
- Yarn 1.x
- Android 构建：Android Studio（含 SDK）+ JDK 17+（推荐用 Android Studio 自带 JBR）
- 可选：Xcode（跑 iOS 壳或接宿主时）

```bash
cd rn-dynamic-0.86
nvm use
yarn install
```

首次安装会通过 `prepare` 自动启用 Husky Git hooks。

---

## 快速开始

### 1. 打测试 / 线上 APK（可并装）

本机需已安装 **Android Studio**（含 SDK）。脚本会自动使用 Android Studio JBR 作为 `JAVA_HOME`。

```bash
./scripts/build-pgyer-both.sh
```

产物（蒲公英建议建两个应用分别上传，详见 `project/docs/PGYER_DUAL_ENV.md`）：

- `project/dist/apk/RnDynamicBase-internal-debug.apk` — **测试**（`com.rndynamicbase.debug`）
- `project/dist/apk/RnDynamicBase-internal-release.apk` — **线上**（`com.rndynamicbase`）

安装后：

- **测试包**：右下角悬浮切换远程 CDN / 本地 Metro；「我的」保留 RN 调试入口
- **线上包**：固定远程 CDN，无调试功能
- 两套包可同时装在同一部手机上

手动构建：

```bash
cd platforms/android
./gradlew assembleInternalRelease   # 线上
./gradlew assembleInternalDebug     # 测试
```

> `internal` flavor 已配置 `usesCleartextTraffic=true`，允许 HTTP 访问 Metro（`http://局域网IP:8081`）。

### 2. RN 本地开发（连 Metro）

业务开发在同级仓 **`rn-biz-0.86`**：

```bash
cd ../rn-biz-0.86
yarn start
```

手机与电脑同一 Wi-Fi，App 内操作：

1. 打开 **我的 → RN 调试入口**
2. Host 填电脑局域网 IP（真机不要用 `10.0.2.2`；USB 可先 `adb reverse tcp:8081 tcp:8081` 再用 `localhost`）
3. Port `8081`，Key 如 `home`
4. 开启 **DevServer** + **加载 common** → 打开分包

Metro 双包地址示例：

```text
common: http://<host>:8081/packages/common/src/index.bundle?platform=android&dev=true
page:   http://<host>:8081/src/home/index.bundle?platform=android&dev=true
```

### 3. CDN 分包发布（正式 / 无感发版）

```bash
yarn pack:list
yarn pack:publish common --platform android --channel main
yarn pack:publish home --platform android --channel main
```

产物与配置：

| 阶段 | 路径 |
|------|------|
| 构建产物 | `project/dist/bundles/<key>/<platform>/` |
| CDN 本地镜像 | `project/dist/cdn-local/rn/0.86.0/<key>/<platform>/` |
| 配置文件 | `project/config/channels/<channel>/bundles.local.json` |
| 手机缓存 | `cacheDir/RNDynamicBundles/<key>/` |

CDN URL 模式：

```text
{baseUrl}/rn/{rnVersion}/{key}/{platform}/{key}.{platform}.{hash12}.bundle
```

APP 通过 `RNBundleHostActivity` 读配置 → 下载 common + page → SHA-256 校验 → 挂载。

### 4. 新增业务分包

1. 在业务仓创建 `src/<key>/index.tsx`
2. 使用 `registerPage` +（可选）`createPackageApp` 注册
3. 发布：`yarn pack:publish <key> --platform android`

分包内路由示例：

```tsx
import { createPackageApp, registerPage } from '../_runtime';

const App = createPackageApp({
  initialRouteName: 'OrderList',
  screens: [
    { name: 'OrderList', component: OrderListScreen, options: { title: '订单中心' } },
    { name: 'OrderDetail', component: OrderDetailScreen, options: { title: '订单详情' } },
  ],
});

registerPage({ key: 'order' }, App);
```

原生仍按 Mode A 整页打开 `order`；列表到详情在 RN 内完成。

### 5. 区分 iOS / Android 代码（打包期排除）

使用 RN 官方后缀，**非本平台文件不会进入 bundle**：

```text
src/<key>/Foo.ios.tsx
src/<key>/Foo.android.tsx
import { Foo } from './Foo';   // 不要写后缀
```

`rn-pack build <key> --platform ios` 只会解析 `.ios.tsx`。详见 `project/docs/PLATFORM_CODE.md`。

---

## 常用命令

| 命令 | 说明 |
|------|------|
| `yarn start` | 启动本仓 Metro（壳首页 `RnDynamicBase` 用） |
| `yarn ios` / `yarn android` | 跑 platforms 本地壳（需指定 flavor，见下） |
| `yarn lint` / `yarn lint:fix` | ESLint 检查 / 自动修复 |
| `yarn typecheck` | TypeScript 类型检查 |
| `yarn pack:list` | 列出可分包目录 |
| `yarn pack:build <key>` | 仅打包 |
| `yarn pack:upload <key>` | 仅上传（更新配置 url） |
| `yarn pack:publish <key>` | 打包 + 上传 |
| `./scripts/build-pgyer-both.sh` | 同时打测试 Debug + 线上 Release（可并装，蒲公英双应用） |
| `./scripts/build-pgyer-apk.sh` | 仅线上 Release APK |
| `./scripts/build-debug-apk.sh` | 仅测试 Debug APK（悬浮切换远程/本地） |

Android 本地运行（带 `internal` flavor）：

```bash
cd platforms/android
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew installInternalDebug
```

上传配置见 `project/config/upload.local.json`，说明见 `project/docs/UPLOAD.md`。

---

## 代码质量与提交校验

- **ESLint**：`app/`、`src/`、`packages/`、`index.js`
- **Husky + lint-staged**：`git commit` 前对暂存 JS/TS 执行 `eslint --fix`

```bash
yarn lint
yarn lint:fix
yarn typecheck
yarn prepare   # hooks 未生效时
```

`.gitignore` 已忽略 `node_modules/`、`**/build/`、`**/.cxx/`、`project/dist/` 等构建产物，请勿将 Android 编译缓存提交入库。

---

## 原生接入（摘要）

1. 宿主 RN 版本必须为 **0.86.0**
2. 拷贝 `native/android`、`native/ios` SDK 源码到宿主工程
3. 读取 `project/config/channels/<channel>/bundles.local.json`（后期可换远程接口）
4. **正式入口**：`RNBundleHostActivity`，按 `key` + `channel` 打开全屏 RN（CDN 分包）
5. **调试入口**：`RNDebugEntryActivity`，支持 Metro / 测试 Bundle URL（建议仅内测 flavor 暴露）
6. **挂载实现**：`RNBundleMount` 通过反射调用 RN 0.86 API（`ReactInstanceManagerBuilder`），Metro HTTP URL 先下载到本地再加载

详细步骤与内测清单：`project/examples/host-app/README.md`、`project/examples/host-app/ANDROID_DEBUG_CHECKLIST.md`。

### Android 内测 flavor 要点

| 项 | 说明 |
|----|------|
| flavor | `internal`（`assembleInternalRelease` / `assembleInternalDebug`） |
| 明文 HTTP | `src/internal/AndroidManifest.xml` 放行 Metro |
| 启动页 | `MainShellActivity`（原生 Tab，非 RN 页） |
| RN 调试 | `RNDebugEntryActivity`（从「我的」进入） |

---

## 无感发版与安全机制

| 机制 | 说明 |
|------|------|
| SHA-256 校验 | 下载后比对配置 `hash`，不匹配则删除并回退缓存 |
| `dependsOn` | page 声明依赖 `common`，宿主按序加载 |
| channel 隔离 | 不同分支/环境独立配置，避免混包 |
| hash 文件名 | 内容寻址，更新 = 新文件，旧缓存可回滚 |
| `keepHistory` | CDN 保留历史版本，配置指针切回即可秒级回滚 |

---

## 分支约定（解耦协作）

| 分支前缀 | 建议修改范围 |
|----------|----------------|
| `rn/*` | `src/`、`app/`、`packages/`、`project/config` |
| `native/*` | `native/`、`platforms/`、`project/examples` |
| `main` | 基座发版、加载协议变更合并点 |

- **RN 业务发布**：只打对应分包并更新配置，无需等商店审核
- **原生发布**：升 RN、改 SDK / 桥接时再走应用商店

---

## 文档索引

- 目录约定：`project/docs/CONVENTIONS.md`
- 平台代码：`project/docs/PLATFORM_CODE.md`
- 上传说明：`project/docs/UPLOAD.md`
- 宿主接入：`project/examples/host-app/README.md`
- Android 内测清单：`project/examples/host-app/ANDROID_DEBUG_CHECKLIST.md`
- 打包工具：`packages/README.md`
- 工程配置：`tooling/README.md`

---

## 上线程度（当前）

| 模块 | 状态 |
|------|------|
| 拆包工具链 `rn-pack` | ✅ 本地 build / upload / publish |
| 配置协议 + channel | ✅ schema 与示例配置 |
| Android 原生 SDK + 混合壳 | ✅ 内测可用 |
| iOS 原生 SDK + 内测壳 | ⚠️ Tab 壳 / 导航桥已接入，需模拟器或实机回归 |
| 真实 CDN 上传 | ⚠️ `uploadWithCdn` 待实现 |
| 远程配置中心 / 灰度熔断 | ❌ 待建设 |

**结论**：核心链路已通，适合 **内测与小流量灰度**；全量线上需补 CDN、配置中心与监控。

---

## 后续规划

- 真实 CDN SDK（`uploadWithCdn`）
- 远程分包配置后台（灰度、回滚 UI）
- 正式 `prod` flavor 隐藏 RN 调试入口
- `minBaseVersion` 运行时强制校验
- iOS：原生播放器、CDN 仅 iOS 配置刷新、TestFlight 分发文档
