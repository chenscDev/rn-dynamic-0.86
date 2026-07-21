# 分包打包管理后台

同一 RN 0.86 工程下，可新增多个**分支项目**分别打包、配置测试/线上环境。

## 启动

```bash
yarn pack:admin
# http://127.0.0.1:8790
```

## 能力

1. **多项目管理**：新增 / 删除 / 选用分支项目（如 `ff-cc`、`master`）
2. **环境配置**：每个项目独立 `test` / `production`（CDN、包名、分包 keys）
3. **构建动作**：打包 / 发布 / 嵌入 assets / 构建 APK
4. **实时日志**：SSE，面板可收起展开

## 配置

- 项目列表：`project/pack-admin/config/projects.json`
- 发布时同步：`project/config/upload.local.json`

## App 调试页说明

- Metro：**必须在 `rn-biz-0.86` 执行 `yarn start`**
- channel：以调试页输入为准，CDN/内置找不到时回退 `master`
- 上次输入的 channel/host/key 会自动记住
