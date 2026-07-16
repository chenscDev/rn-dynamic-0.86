# packages/

本目录**只放打包相关工具包**，不放 RN 业务页面、不放原生代码。

| 包名 | 路径 | 说明 |
|------|------|------|
| `@rn-dynamic/bundler` | `bundler/` | `rn-pack` CLI：build / upload / publish |
| `@rn-dynamic/schema` | `schema/` | 分包配置与 manifest 的 TypeScript 类型契约 |

业务页面请写在仓库根目录 `src/`，原生 SDK 在 `native/`。
