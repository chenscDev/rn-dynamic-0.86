module.exports = {
  root: true,
  extends: '@react-native',
  ignorePatterns: [
    'node_modules/',
    'project/dist/',
    'platforms/',
    'coverage/',
    '*.bundle',
    '*.jsbundle',
  ],
  overrides: [
    {
      // 打包 CLI 为 Node CommonJS，关闭 RN 浏览器环境相关规则干扰
      files: ['packages/bundler/**/*.{js,cjs}'],
      env: {
        node: true,
      },
      rules: {
        'no-console': 'off',
      },
    },
  ],
};
