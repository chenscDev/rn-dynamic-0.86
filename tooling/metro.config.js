const path = require('path');
const { getDefaultConfig, mergeConfig } = require('@react-native/metro-config');

/**
 * Metro 配置（实体在 tooling，根目录 metro.config.js 转发到此）
 * projectRoot 必须是仓库根，这样 src/ 与 index.js 才能被正确解析
 */
const projectRoot = path.resolve(__dirname, '..');

/**
 * @type {import('@react-native/metro-config').MetroConfig}
 */
const config = {
  projectRoot,
  watchFolders: [projectRoot],
};

module.exports = mergeConfig(getDefaultConfig(projectRoot), config);
