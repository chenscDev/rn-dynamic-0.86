# 分包上传说明

## CDN 上传（阿里云 OSS）

`provider=cdn` 时使用 `ali-oss` SDK，AK 从环境变量读取：

```bash
export OSS_ACCESS_KEY_ID=...
export OSS_ACCESS_KEY_SECRET=...
export OSS_ENDPOINT=oss-cn-hangzhou.aliyuncs.com
export OSS_BUCKET=rn-bundles
export OSS_REGION=oss-cn-hangzhou
export CDN_BASE_URL=https://cdn.rn.example.com

RN_PACK_CHANNEL=main node packages/bundler/bin/rn-pack.js publish home --platform android --provider cdn
```

路径：`rn/{rnVersion}/{channel}/{key}/{platform}/{fileName}`

`keepHistory` 控制 OSS 同目录保留的历史 bundle 数量（默认 5，支持回滚）。

## 本地占位（默认）

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

`provider=cdn` 已实现阿里云 OSS 上传，见 `project/docs/UPLOAD.md`。
