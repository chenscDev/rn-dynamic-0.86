/**
 * 分包打包核心逻辑
 * 约定：RN 页面在仓库根 src/，打包工具在 packages/bundler
 */
'use strict';

const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const { spawnSync } = require('child_process');

const RN_VERSION = '0.86.0';
const BASE_VERSION = '0.1.0';
const RESERVED_DIRS = new Set(['_runtime', '_router']);

/**
 * 规范化 channel（git 分支 → 路径安全）
 */
function normalizeChannel(raw) {
  const trimmed = String(raw || '')
    .trim()
    .toLowerCase()
    .replace(/\\/g, '/')
    .replace(/^refs\/heads\//, '');
  if (!trimmed) {
    return 'main';
  }
  const safe = trimmed
    .replace(/[^a-z0-9._/-]+/g, '-')
    .replace(/\//g, '-')
    .replace(/-+/g, '-')
    .replace(/^-|-$/g, '');
  if (!safe || safe === '.' || safe === '..') {
    throw new Error(`非法 channel: ${raw}`);
  }
  return safe;
}

/**
 * 解析配置文件路径（支持 channel 维度）
 */
function resolveConfigPath(projectDir, channelInput) {
  const channel = normalizeChannel(
    channelInput || process.env.RN_PACK_CHANNEL || 'main',
  );
  const channelPath = path.join(
    projectDir,
    'config',
    'channels',
    channel,
    'bundles.local.json',
  );
  if (fs.existsSync(channelPath)) {
    return channelPath;
  }
  const legacyPath = path.join(projectDir, 'config', 'bundles.local.json');
  if (channel === 'main' && fs.existsSync(legacyPath)) {
    return legacyPath;
  }
  return channelPath;
}

/**
 * 解析 monorepo 关键路径
 * - repoRoot: 仓库根（也是 RN 工程根）
 * - srcDir: RN 业务页面目录
 * - packages: 仅 bundler / schema
 */
function getPaths(fromDir = __dirname) {
  const bundlerRoot = path.resolve(fromDir, '..');
  const packagesRoot = path.resolve(bundlerRoot, '..');
  const repoRoot = path.resolve(packagesRoot, '..');
  const srcDir = path.join(repoRoot, 'src');
  const projectDir = path.join(repoRoot, 'project');
  const distRoot = path.join(projectDir, 'dist', 'bundles');
  const uploadConfigPath = path.join(projectDir, 'config', 'upload.local.json');
  const configPath = resolveConfigPath(projectDir);

  return {
    repoRoot,
    projectDir,
    /** @deprecated 使用 repoRoot；保留字段兼容旧调用 */
    baseRoot: repoRoot,
    /** @deprecated 使用 srcDir */
    baseSrcDir: srcDir,
    srcDir,
    distRoot,
    configPath,
    uploadConfigPath,
  };
}

/**
 * 列出可分包 key（src 下一层目录，排除 _ 开头与保留名）
 */
function listPackageKeys(srcDir) {
  if (!fs.existsSync(srcDir)) {
    return [];
  }

  return fs
    .readdirSync(srcDir, { withFileTypes: true })
    .filter(entry => entry.isDirectory())
    .map(entry => entry.name)
    .filter(name => !name.startsWith('_') && !RESERVED_DIRS.has(name))
    .filter(name => {
      const entryTs = path.join(srcDir, name, 'index.ts');
      const entryTsx = path.join(srcDir, name, 'index.tsx');
      const entryJs = path.join(srcDir, name, 'index.js');
      return (
        fs.existsSync(entryTs) ||
        fs.existsSync(entryTsx) ||
        fs.existsSync(entryJs)
      );
    })
    .sort();
}

/**
 * 解析分包入口文件
 */
function resolveEntryFile(srcDir, key) {
  const candidates = [
    path.join(srcDir, key, 'index.ts'),
    path.join(srcDir, key, 'index.tsx'),
    path.join(srcDir, key, 'index.js'),
  ];

  const found = candidates.find(file => fs.existsSync(file));
  if (!found) {
    throw new Error(
      `分包 ${key} 缺少入口文件，期望: src/${key}/index.ts|tsx|js`,
    );
  }
  return found;
}

/**
 * 计算文件 sha256 短 hash
 */
function hashFile(filePath) {
  const buffer = fs.readFileSync(filePath);
  return crypto.createHash('sha256').update(buffer).digest('hex').slice(0, 12);
}

/**
 * 规范化 platform 列表
 */
function normalizePlatforms(platform) {
  const value = (platform || 'all').toLowerCase();
  if (value === 'all') {
    return ['ios', 'android'];
  }
  if (value === 'ios' || value === 'android') {
    return [value];
  }
  throw new Error(`不支持的 platform: ${platform}，请使用 ios|android|all`);
}

/**
 * 调用 react-native bundle 打出单个平台产物
 */
function runMetroBundle({
  repoRoot,
  entryFile,
  platform,
  outputFile,
  assetsDest,
}) {
  fs.mkdirSync(path.dirname(outputFile), { recursive: true });
  fs.mkdirSync(assetsDest, { recursive: true });

  const cliBin = path.join(repoRoot, 'node_modules', 'react-native', 'cli.js');

  if (!fs.existsSync(cliBin)) {
    throw new Error(
      `未找到 react-native CLI: ${cliBin}，请先在仓库根目录执行 yarn install`,
    );
  }

  const args = [
    cliBin,
    'bundle',
    '--platform',
    platform,
    '--dev',
    'false',
    '--entry-file',
    entryFile,
    '--bundle-output',
    outputFile,
    '--assets-dest',
    assetsDest,
    '--minify',
    'true',
  ];

  const result = spawnSync(process.execPath, args, {
    cwd: repoRoot,
    encoding: 'utf8',
    env: process.env,
  });

  if (result.status !== 0) {
    const detail = [result.stdout, result.stderr].filter(Boolean).join('\n');
    throw new Error(
      `metro bundle 失败 platform=${platform}\n${detail || '无输出'}`,
    );
  }
}

/**
 * 读取 / 初始化本地配置
 */
function readConfig(configPath) {
  if (!fs.existsSync(configPath)) {
    return {
      rnVersion: RN_VERSION,
      baseVersion: BASE_VERSION,
      updatedAt: '',
      bundles: {},
    };
  }

  try {
    const raw = fs.readFileSync(configPath, 'utf8');
    const json = JSON.parse(raw);
    if (!json.bundles || typeof json.bundles !== 'object') {
      json.bundles = {};
    }
    return json;
  } catch (error) {
    const message = error instanceof Error ? error.message : String(error);
    throw new Error(`读取配置失败 ${configPath}: ${message}`);
  }
}

/**
 * 写回本地配置（按 key 合并对应平台条目）
 */
function writeConfig(configPath, config) {
  fs.mkdirSync(path.dirname(configPath), { recursive: true });
  const channel = normalizeChannel(process.env.RN_PACK_CHANNEL || 'main');
  const next = {
    ...config,
    rnVersion: RN_VERSION,
    baseVersion: BASE_VERSION,
    channel,
    updatedAt: new Date().toISOString(),
  };
  fs.writeFileSync(configPath, `${JSON.stringify(next, null, 2)}\n`, 'utf8');
}

/**
 * 更新某个 key 下某一平台的配置项
 */
function upsertBundleItem(config, item) {
  const list = Array.isArray(config.bundles[item.key])
    ? config.bundles[item.key]
    : [];
  const nextList = list.filter(entry => entry.platform !== item.platform);
  nextList.push(item);
  nextList.sort((a, b) => a.platform.localeCompare(b.platform));
  config.bundles[item.key] = nextList;
}

/**
 * 打包单个分包
 */
async function buildPackage({ key, platform = 'all', paths }) {
  if (!key || key.startsWith('_') || RESERVED_DIRS.has(key)) {
    throw new Error(`非法分包 key: ${key}`);
  }

  const srcDir = paths.srcDir || paths.baseSrcDir;
  const repoRoot = paths.repoRoot || paths.baseRoot;
  const entryFile = resolveEntryFile(srcDir, key);
  const platforms = normalizePlatforms(platform);
  const config = readConfig(paths.configPath);
  const createdAt = new Date().toISOString();

  console.log(`[rn-pack] 开始打包 ${key}，平台: ${platforms.join(', ')}`);

  for (const p of platforms) {
    const outDir = path.join(paths.distRoot, key, p);
    fs.mkdirSync(outDir, { recursive: true });

    const tempBundle = path.join(outDir, `${key}.${p}.bundle.tmp`);
    const assetsDest = path.join(outDir, 'assets');

    runMetroBundle({
      repoRoot,
      entryFile,
      platform: p,
      outputFile: tempBundle,
      assetsDest,
    });

    const hash = hashFile(tempBundle);
    const fileName = `${key}.${p}.${hash}.bundle`;
    const finalBundle = path.join(outDir, fileName);

    // 清理同平台旧 bundle（不要删当前 tmp）
    fs.readdirSync(outDir)
      .filter(name => name.endsWith('.bundle') && !name.endsWith('.bundle.tmp'))
      .forEach(name => {
        fs.unlinkSync(path.join(outDir, name));
      });

    fs.renameSync(tempBundle, finalBundle);

    const relativePath = path.relative(repoRoot, finalBundle);
    const localUrl = `file://${finalBundle}`;

    const manifest = {
      key,
      componentName: key,
      rnVersion: RN_VERSION,
      platform: p,
      hash,
      entry: path.relative(repoRoot, entryFile),
      fileName,
      createdAt,
      minBaseVersion: BASE_VERSION,
    };

    fs.writeFileSync(
      path.join(outDir, 'manifest.json'),
      `${JSON.stringify(manifest, null, 2)}\n`,
      'utf8',
    );

    upsertBundleItem(config, {
      key,
      name: key,
      componentName: key,
      url: localUrl,
      hash,
      platform: p,
      localPath: relativePath,
    });

    console.log(`[rn-pack] ✓ ${key}@${p} hash=${hash}`);
    console.log(`         → ${relativePath}`);
    console.log(
      `         平台解析: 已启用 .${p}.tsx|.ts|.js 后缀，非 ${p} 平台文件不会打入此 bundle`,
    );
  }

  writeConfig(paths.configPath, config);
  console.log(
    `[rn-pack] 已更新配置: ${path.relative(repoRoot, paths.configPath)}`,
  );
}

/**
 * 打包全部业务分包
 */
async function buildAllPackages({ platform = 'all', paths }) {
  const srcDir = paths.srcDir || paths.baseSrcDir;
  const keys = listPackageKeys(srcDir);
  if (keys.length === 0) {
    throw new Error('没有可打包的分包目录');
  }

  for (const key of keys) {
    await buildPackage({ key, platform, paths });
  }

  console.log(`[rn-pack] 全部完成，共 ${keys.length} 个分包`);
}

module.exports = {
  getPaths,
  listPackageKeys,
  buildPackage,
  buildAllPackages,
  normalizePlatforms,
  normalizeChannel,
  resolveConfigPath,
  readConfig,
  writeConfig,
  upsertBundleItem,
  RN_VERSION,
  BASE_VERSION,
};
