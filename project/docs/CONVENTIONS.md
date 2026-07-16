# 目录与分包约定

## 职责划分

| 目录 | 内容 |
|------|------|
| `app/` | 基座壳入口、测试 |
| `src/` | RN 业务分包与 `_runtime` |
| `packages/` | bundler / schema（仅打包） |
| `native/` | 宿主 BundleLoader SDK |
| `platforms/` | 本地 RN android/ios 工程 |
| `project/` | config、docs、examples、dist |
| `tooling/` | babel / metro / eslint / tsconfig / Gemfile |

## 分包最小单元

```
src/
  _runtime/
  order/
    index.tsx              # createPackageApp + registerPage
    OrderListScreen.tsx
    OrderDetailScreen.tsx
```

1. 一层文件夹 = 一个分包 key  
2. 入口：`index.tsx|ts|js`  
3. 内部路由：`createPackageApp`  
4. 原生 Mode A：一次打开一个 key  

## 平台代码（iOS / Android）

- **打包期排除**：使用 `*.ios.tsx` / `*.android.tsx` 后缀（见 `project/docs/PLATFORM_CODE.md`）
- **运行时判断**：`isIOS()` / `platformSelect()`（`src/_runtime/platform.ts`）

## 配置

- `project/config/bundles.local.json`
- `project/config/upload.local.json`
