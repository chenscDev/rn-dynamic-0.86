# tooling/

工程配置实体目录。根目录保留同名薄文件转发到此处，避免污染根目录，同时兼容 RN CLI / Metro 默认查找路径。

| 文件 | 作用 |
|------|------|
| `babel.config.js` | Babel |
| `metro.config.js` | Metro（projectRoot=仓库根） |
| `jest.config.js` | Jest |
| `.eslintrc.js` / `.prettierrc.js` | 代码风格 |
| `tsconfig.json` | TS 基座配置（根 tsconfig extends 它） |
| `Gemfile` / `.bundle` | CocoaPods Ruby 依赖 |
