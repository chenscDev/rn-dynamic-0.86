/**
 * 分包上传：本地占位实现 + CDN 接口预留
 */
'use strict';

const fs = require('fs');
const path = require('path');

/**
 * @typedef {object} UploadProviderContext
 * @property {string} key
 * @property {'ios'|'android'} platform
 * @property {string} hash
 * @property {string} fileName
 * @property {string} sourceBundlePath 构建产物绝对路径
 * @property {string} sourceManifestPath
 * @property {string} relativeObjectPath CDN/本地对象相对路径
 * @property {string} publicUrl 对外访问 URL
 * @property {object} uploadConfig
 * @property {object} paths
 */

/**
 * 读取上传配置
 */
function readUploadConfig(paths) {
  const configPath = paths.uploadConfigPath;
  if (!fs.existsSync(configPath)) {
    return {
      provider: 'local',
      baseUrl: 'http://127.0.0.1:8787',
      targetDir: 'project/dist/cdn-local',
      pathTemplate: 'rn/{rnVersion}/{key}/{platform}/{fileName}',
      cdn: {},
    };
  }

  try {
    return JSON.parse(fs.readFileSync(configPath, 'utf8'));
  } catch (error) {
    const message = error instanceof Error ? error.message : String(error);
    throw new Error(`读取上传配置失败 ${configPath}: ${message}`);
  }
}

/**
 * 渲染对象路径模板
 */
function renderPathTemplate(template, vars) {
  return template.replace(/\{(\w+)\}/g, (_, name) => {
    if (vars[name] == null || vars[name] === '') {
      throw new Error(`路径模板缺少变量: {${name}}`);
    }
    return String(vars[name]);
  });
}

/**
 * 拼接对外 URL（去掉多余斜杠）
 */
function joinPublicUrl(baseUrl, objectPath) {
  const base = String(baseUrl || '').replace(/\/+$/, '');
  const object = String(objectPath || '').replace(/^\/+/, '');
  if (!base) {
    return object;
  }
  return `${base}/${object}`;
}

/**
 * 解析某个分包某平台的构建产物
 */
function resolveBuiltArtifact(paths, key, platform) {
  const outDir = path.join(paths.distRoot, key, platform);
  const manifestPath = path.join(outDir, 'manifest.json');
  if (!fs.existsSync(manifestPath)) {
    throw new Error(
      `未找到 ${key}@${platform} 的 manifest，请先执行: rn-pack build ${key} --platform ${platform}`,
    );
  }

  let manifest;
  try {
    manifest = JSON.parse(fs.readFileSync(manifestPath, 'utf8'));
  } catch (error) {
    const message = error instanceof Error ? error.message : String(error);
    throw new Error(`读取 manifest 失败: ${message}`);
  }

  const fileName = manifest.fileName;
  const bundlePath = path.join(outDir, fileName);
  if (!fs.existsSync(bundlePath)) {
    throw new Error(`未找到构建产物: ${bundlePath}`);
  }

  return { manifest, bundlePath, manifestPath, outDir };
}

/**
 * 本地上传提供方：拷贝到 dist/cdn-local（模拟 CDN 目录结构）
 * @param {UploadProviderContext} ctx
 */
async function uploadWithLocal(ctx) {
  const targetRoot = path.isAbsolute(ctx.uploadConfig.targetDir)
    ? ctx.uploadConfig.targetDir
    : path.join(ctx.paths.repoRoot, ctx.uploadConfig.targetDir);

  const destBundle = path.join(targetRoot, ctx.relativeObjectPath);
  const destDir = path.dirname(destBundle);
  fs.mkdirSync(destDir, { recursive: true });

  // 同目录只保留当前 hash 的 bundle，模拟 CDN 覆盖发布
  const baseNamePrefix = `${ctx.key}.${ctx.platform}.`;
  if (fs.existsSync(destDir)) {
    fs.readdirSync(destDir)
      .filter(
        name =>
          name.startsWith(baseNamePrefix) &&
          name.endsWith('.bundle') &&
          name !== ctx.fileName,
      )
      .forEach(name => {
        fs.unlinkSync(path.join(destDir, name));
      });
  }

  fs.copyFileSync(ctx.sourceBundlePath, destBundle);
  fs.copyFileSync(
    ctx.sourceManifestPath,
    path.join(destDir, 'manifest.json'),
  );

  return {
    provider: 'local',
    objectPath: ctx.relativeObjectPath,
    publicUrl: ctx.publicUrl,
    localUploadedPath: path.relative(ctx.paths.repoRoot, destBundle),
  };
}

/**
 * CDN 上传提供方占位：接口已预留，便于后期替换实现
 * @param {UploadProviderContext} ctx
 */
async function uploadWithCdn(ctx) {
  const cdn = ctx.uploadConfig.cdn || {};
  const missing = [];
  if (!cdn.endpoint) missing.push('cdn.endpoint');
  if (!cdn.bucket) missing.push('cdn.bucket');

  const detail = [
    `provider=cdn 尚未实现真实上传`,
    `object=${ctx.relativeObjectPath}`,
    `publicUrl=${ctx.publicUrl}`,
    `endpoint=${cdn.endpoint || '(空)'}`,
    `bucket=${cdn.bucket || '(空)'}`,
    missing.length ? `缺少配置: ${missing.join(', ')}` : null,
    `请先使用 --provider local，或在 packages/bundler/src/upload.js 的 uploadWithCdn 中接入 SDK`,
  ]
    .filter(Boolean)
    .join('\n  ');

  throw new Error(detail);
}

/**
 * 按 provider 分发上传
 * @param {UploadProviderContext} ctx
 */
async function dispatchUpload(ctx) {
  const provider = (ctx.uploadConfig.provider || 'local').toLowerCase();
  if (provider === 'local') {
    return uploadWithLocal(ctx);
  }
  if (provider === 'cdn') {
    return uploadWithCdn(ctx);
  }
  throw new Error(`未知上传 provider: ${provider}，支持 local|cdn`);
}

/**
 * 上传单个分包（可多平台）
 */
async function uploadPackage({
  key,
  platform = 'all',
  paths,
  provider,
  baseUrl,
  core,
}) {
  if (!key) {
    throw new Error('请指定分包 key，例如：rn-pack upload order');
  }

  const uploadConfig = {
    ...readUploadConfig(paths),
  };
  if (provider) {
    uploadConfig.provider = provider;
  }
  if (baseUrl) {
    uploadConfig.baseUrl = baseUrl;
  }

  const platforms = core.normalizePlatforms(platform);
  const config = core.readConfig(paths.configPath);

  console.log(
    `[rn-pack] 开始上传 ${key}，provider=${uploadConfig.provider}，平台: ${platforms.join(', ')}`,
  );

  for (const p of platforms) {
    const artifact = resolveBuiltArtifact(paths, key, p);
    const relativeObjectPath = renderPathTemplate(
      uploadConfig.pathTemplate ||
        'rn/{rnVersion}/{key}/{platform}/{fileName}',
      {
        rnVersion: artifact.manifest.rnVersion || core.RN_VERSION,
        key,
        platform: p,
        fileName: artifact.manifest.fileName,
        hash: artifact.manifest.hash,
      },
    );
    const publicUrl = joinPublicUrl(uploadConfig.baseUrl, relativeObjectPath);

    /** @type {UploadProviderContext} */
    const ctx = {
      key,
      platform: p,
      hash: artifact.manifest.hash,
      fileName: artifact.manifest.fileName,
      sourceBundlePath: artifact.bundlePath,
      sourceManifestPath: artifact.manifestPath,
      relativeObjectPath,
      publicUrl,
      uploadConfig,
      paths,
    };

    const result = await dispatchUpload(ctx);

    const existing = (config.bundles[key] || []).find(
      item => item.platform === p,
    );

    core.upsertBundleItem(config, {
      key,
      name: existing?.name || key,
      componentName: existing?.componentName || key,
      url: result.publicUrl,
      hash: artifact.manifest.hash,
      platform: p,
      localPath: existing?.localPath || path.relative(paths.repoRoot, artifact.bundlePath),
      uploadedPath: result.localUploadedPath,
      provider: result.provider,
    });

    console.log(`[rn-pack] ✓ 已上传 ${key}@${p}`);
    console.log(`         url  → ${result.publicUrl}`);
    if (result.localUploadedPath) {
      console.log(`         file → ${result.localUploadedPath}`);
    }
  }

  core.writeConfig(paths.configPath, config);
  console.log(
    `[rn-pack] 已更新配置 url: ${path.relative(paths.repoRoot, paths.configPath)}`,
  );
}

/**
 * 上传全部已构建分包
 */
async function uploadAllPackages({
  platform = 'all',
  paths,
  provider,
  baseUrl,
  core,
}) {
  const keys = core.listPackageKeys(paths.srcDir || paths.baseSrcDir);
  if (keys.length === 0) {
    throw new Error('没有可上传的分包目录');
  }

  for (const key of keys) {
    await uploadPackage({ key, platform, paths, provider, baseUrl, core });
  }
  console.log(`[rn-pack] 上传全部完成，共 ${keys.length} 个分包`);
}

/**
 * 先 build 再 upload（完整发布流）
 */
async function publishPackage({
  key,
  platform = 'all',
  paths,
  provider,
  baseUrl,
  core,
}) {
  await core.buildPackage({ key, platform, paths });
  await uploadPackage({ key, platform, paths, provider, baseUrl, core });
}

module.exports = {
  readUploadConfig,
  uploadPackage,
  uploadAllPackages,
  publishPackage,
  uploadWithLocal,
  uploadWithCdn,
};
