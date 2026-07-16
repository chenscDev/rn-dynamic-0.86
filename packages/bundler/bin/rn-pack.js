#!/usr/bin/env node
/**
 * rn-pack：按 src/<key>/index.tsx 打包、上传分包并更新本地配置
 *
 * 用法：
 *   rn-pack list
 *   rn-pack build <key> [--platform ios|android|all]
 *   rn-pack build:all [--platform ios|android|all]
 *   rn-pack upload <key> [--platform ...] [--provider local|cdn] [--base-url <url>]
 *   rn-pack upload:all [...]
 *   rn-pack publish <key> [...]   # build + upload
 */
'use strict';

const {
  listPackageKeys,
  buildPackage,
  buildAllPackages,
  getPaths,
  normalizePlatforms,
  readConfig,
  writeConfig,
  upsertBundleItem,
  RN_VERSION,
  BASE_VERSION,
} = require('../src/index');
const {
  uploadPackage,
  uploadAllPackages,
  publishPackage,
} = require('../src/upload');

function printHelp() {
  console.log(`
rn-pack — RN 动态分包工具（基座锁定 react-native@0.86）

命令：
  list                                         列出 src 下可分包目录
  build <key> [--platform <p>]                 打包单个分包
  build:all [--platform <p>]                   打包全部业务分包
  upload <key> [--platform <p>] [upload选项]   上传已构建分包并更新配置 url
  upload:all [--platform <p>] [upload选项]     上传全部已构建分包
  publish <key> [--platform <p>] [upload选项]  先 build 再 upload（完整发布）

platform: ios | android | all（默认 all）

upload 选项：
  --provider local|cdn     上传实现（默认读 config/upload.local.json）
  --base-url <url>         对外访问前缀，如 http://127.0.0.1:8787

说明：
  - RN 页面：src/<key>/index.tsx|ts|js
  - 打包工具：packages/bundler
  - 原生 SDK：native/ios、native/android
  - 本地壳：platforms/android、platforms/ios
  - 构建产物：project/dist/bundles/<key>/
  - 本地上传目录：project/dist/cdn-local/
  - 配置写入：project/config/bundles.local.json
  - provider=cdn 为预留接口，当前会明确提示未实现
  - _runtime 为基座运行时，不会作为业务分包打包
`);
}

function parseArgs(argv) {
  const args = argv.slice(2);
  const command = args[0];
  const positional = [];
  let platform = 'all';
  let provider;
  let baseUrl;

  for (let i = 1; i < args.length; i += 1) {
    const token = args[i];
    if (token === '--platform' || token === '-p') {
      platform = args[i + 1];
      i += 1;
      continue;
    }
    if (token === '--provider') {
      provider = args[i + 1];
      i += 1;
      continue;
    }
    if (token === '--base-url') {
      baseUrl = args[i + 1];
      i += 1;
      continue;
    }
    if (token === '--help' || token === '-h') {
      return { command: 'help', positional: [], platform };
    }
    positional.push(token);
  }

  return { command, positional, platform, provider, baseUrl };
}

async function main() {
  const { command, positional, platform, provider, baseUrl } = parseArgs(
    process.argv,
  );

  if (!command || command === 'help') {
    printHelp();
    process.exit(command ? 0 : 1);
  }

  const paths = getPaths();
  const core = {
    listPackageKeys,
    buildPackage,
    buildAllPackages,
    normalizePlatforms,
    readConfig,
    writeConfig,
    upsertBundleItem,
    RN_VERSION,
    BASE_VERSION,
  };

  try {
    if (command === 'list') {
      const keys = listPackageKeys(paths.srcDir || paths.baseSrcDir);
      if (keys.length === 0) {
        console.log('未发现可分包目录（排除 _ 开头的目录）');
        return;
      }
      console.log('可分包目录：');
      keys.forEach(key => {
        console.log(`  - ${key}  →  src/${key}/index.tsx|ts|js`);
      });
      return;
    }

    if (command === 'build') {
      const key = positional[0];
      if (!key) {
        throw new Error('请指定分包 key，例如：rn-pack build home');
      }
      await buildPackage({ key, platform, paths });
      return;
    }

    if (command === 'build:all') {
      await buildAllPackages({ platform, paths });
      return;
    }

    if (command === 'upload') {
      const key = positional[0];
      if (!key) {
        throw new Error('请指定分包 key，例如：rn-pack upload order');
      }
      await uploadPackage({ key, platform, paths, provider, baseUrl, core });
      return;
    }

    if (command === 'upload:all') {
      await uploadAllPackages({ platform, paths, provider, baseUrl, core });
      return;
    }

    if (command === 'publish') {
      const key = positional[0];
      if (!key) {
        throw new Error('请指定分包 key，例如：rn-pack publish order');
      }
      await publishPackage({ key, platform, paths, provider, baseUrl, core });
      return;
    }

    throw new Error(`未知命令: ${command}`);
  } catch (error) {
    const message = error instanceof Error ? error.message : String(error);
    console.error(`[rn-pack] 失败: ${message}`);
    process.exit(1);
  }
}

main();
