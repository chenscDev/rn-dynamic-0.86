# 分包上传说明

## 本地占位（当前默认）

`project/config/upload.local.json`：

```json
{
  "provider": "local",
  "baseUrl": "http://127.0.0.1:8787",
  "targetDir": "project/dist/cdn-local",
  "pathTemplate": "rn/{rnVersion}/{key}/{platform}/{fileName}"
}
```

```bash
node packages/bundler/bin/rn-pack.js publish order --platform ios
```

效果：

1. 打包到 `project/dist/bundles/...`
2. 拷贝到 `project/dist/cdn-local/rn/0.86.0/...`
3. 更新 `project/config/bundles.local.json` 的 url / hash

可选静态服务：

```bash
npx serve project/dist/cdn-local -p 8787
```

## CDN 预留

`provider=cdn` 时走 `packages/bundler/src/upload.js` → `uploadWithCdn`（当前未实现真实上传）。
