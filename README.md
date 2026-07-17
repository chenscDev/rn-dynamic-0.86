# rn-dynamic-0.86

React Native **0.86** 动态分包基座：面向「纯原生 App 嵌 RN」场景，支持按业务目录独立打包、上传与热更新加载。

> RN 版本锁定为 `0.86.0`，不可在本仓库内切换。其他 RN 版本请另建 `rn-dynamic-x.y` 基座线。

---

## 项目能做什么

| 能力 | 说明 |
|------|------|
| 固定 RN 版本基座 | 下载即可用，业务按约定目录扩展 |
| 按目录分包 | `src/<key>/` 为一个最小分包单元 |
| 整页打开（Mode A） | 原生一次打开一个分包全屏页 |
| 分包内路由 | `createPackageApp` 支持 RN 内部页面跳转 |
| 本地发布流 | `build → upload → 更新配置 url/hash` |
| 原生 SDK 骨架 | iOS / Android 配置读取、缓存、正式入口、调试入口 |
| 独立发布 | 业务改动只发对应分包；原生发版走商店流程 |

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
├── project/             # 项目支撑
│   ├── config/          # bundles.local.json、upload.local.json
│   ├── docs/            # 约定与上传说明
│   ├── examples/        # 宿主接入说明
│   └── dist/            # 构建产物与本地 CDN 镜像
├── tooling/             # babel / metro / eslint 等工程配置实体
├── index.js             # Metro 默认入口
├── package.json
└── README.md
```

职责边界：

- **RN 业务**只写在 `src/`
- **打包逻辑**只在 `packages/`
- **宿主原生 SDK**只在 `native/`
- **本地 RN 壳**在 `platforms/`（勿与 `native/` 混淆）

---

## 环境要求

- Node.js：`^22.13.0`（见 `.nvmrc`；RN 0.86 要求）
- Yarn 1.x
- 可选：Xcode / Android Studio（跑本地壳或接宿主时）

```bash
cd rn-dynamic-0.86
nvm use
yarn install
```

首次安装会通过 `prepare` 自动启用 Husky Git hooks。

---

## 快速开始

> **业务开发请使用同级纯 RN 仓 [`rn-biz-0.86`](../rn-biz-0.86)**：日常只需 `yarn start`，由宿主调试入口按平台加载。  
> 本仓继续提供 **原生 SDK**（`native/`）与加载协议参考。

### 1. 启动 Metro（本地调试）

在业务仓：

```bash
cd ../rn-biz-0.86
yarn start
```

原生调试入口填写：`host` + `port(8081)` + `key(home|demo|order)`（platform 由宿主标识）。  
Metro 双包地址示例：

```text
common: http://<host>:8081/packages/common/src/index.bundle?platform=ios&dev=true
page:   http://<host>:8081/src/order/index.bundle?platform=ios&dev=true
```

### 2. 新增业务分包

1. 创建 `src/<key>/index.tsx`
2. 使用 `registerPage` +（可选）`createPackageApp` 注册
3. 发布：

```bash
yarn pack:list
yarn pack:publish <key> --platform ios
# 或
yarn pack:build <key> --platform ios
yarn pack:upload <key> --platform ios
```

产物与配置：

- 构建：`project/dist/bundles/<key>/`
- 本地 CDN：`project/dist/cdn-local/`
- 配置：`project/config/bundles.local.json`（含 `url` + `hash`）

### 3. 分包内路由示例

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

### 4. 区分 iOS / Android 代码（打包期排除）

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
| `yarn start` | 启动 Metro |
| `yarn ios` / `yarn android` | 跑 platforms 本地壳 |
| `yarn lint` | ESLint 检查 RN / 工具代码 |
| `yarn lint:fix` | ESLint 自动修复 |
| `yarn typecheck` | TypeScript 类型检查 |
| `yarn pack:list` | 列出可分包目录 |
| `yarn pack:build <key>` | 仅打包 |
| `yarn pack:upload <key>` | 仅上传（更新配置 url） |
| `yarn pack:publish <key>` | 打包 + 上传 |

上传配置见 `project/config/upload.local.json`，说明见 `project/docs/UPLOAD.md`。

---

## 代码质量与提交校验

本仓库已接入：

- **ESLint**：基于 `@react-native/eslint-config`，覆盖 `app/`、`src/`、`packages/`、`index.js`
- **Husky + lint-staged**：`git commit` 前对暂存的 JS/TS 文件执行 `eslint --fix`

相关脚本：

```bash
yarn lint
yarn lint:fix
yarn typecheck
```

若 hooks 未生效，可执行：

```bash
yarn prepare
```

---

## 原生接入（摘要）

1. 宿主 RN 版本必须为 **0.86.0**
2. 拷贝 `native/ios`、`native/android` SDK 源码
3. 读取 `project/config/bundles.local.json`（后期可换远程接口）
4. 正式入口按 `key` 打开全屏 RN；调试入口支持 Metro / 测试 URL

详细步骤：`project/examples/host-app/README.md`。

---

## 分支约定（解耦协作）

| 分支前缀 | 建议修改范围 |
|----------|----------------|
| `rn/*` | `src/`、`app/`、`packages/`、`project/config` |
| `native/*` | `native/`、`project/examples` |
| `main` | 基座发版、加载协议变更合并点 |

- **RN 业务发布**：只打对应分包并更新配置，无需等商店审核  
- **原生发布**：升 RN、改 SDK / 桥接时再走应用商店  

---

## 文档索引

- 目录约定：`project/docs/CONVENTIONS.md`
- 平台代码：`project/docs/PLATFORM_CODE.md`
- 上传说明：`project/docs/UPLOAD.md`
- 宿主接入：`project/examples/host-app/README.md`
- 打包工具：`packages/README.md`
- 工程配置：`tooling/README.md`

---

## 后续规划（未做）

- 可运行的完整 example 宿主工程
- 真实 CDN SDK（`uploadWithCdn`）
- 远程分包配置后台 / 多工程管理台（P3：灰度、回滚 UI）
- 业务开发迁至同级仓 `rn-biz-0.86`（本仓专注原生 SDK）
