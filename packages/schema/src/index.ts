/**
 * 分包配置与 manifest 类型定义
 * 原生端与 bundler 共用同一套契约
 *
 * 隔离维度：rnVersion → channel（分支/业务主分支）→ key → platform
 */

/** 分包类型：common 预加载公共包；page 业务薄包 */
export type BundleKind = 'common' | 'page';

/** 单个分包在配置中心（本地或远程）的条目 */
export interface BundleConfigItem {
  /** 分包唯一 key，默认等于 src 下文件夹名；common 固定为 common */
  key: string;
  /** 展示名称（可选） */
  name?: string;
  /** AppRegistry 注册名，默认与 key 相同 */
  componentName?: string;
  /** 分包下载地址（本地 file:// 或 http(s)://） */
  url: string;
  /** 内容 hash，变更时强制重新下载加载 */
  hash: string;
  /** 适用平台 */
  platform: 'ios' | 'android';
  /**
   * 发布通道（通常对应 git 分支 / 业务主分支）
   * 同 RN 版本下不同 channel 的 common/page 互不共用
   */
  channel?: string;
  /** 包类型，默认 page（兼容旧配置） */
  kind?: BundleKind;
  /** 依赖的其他分包 key，page 通常依赖 ['common']（同 channel 内） */
  dependsOn?: string[];
  /** 不可变构建号（回滚时改配置指针，不删对象） */
  version?: string;
  /** assets 包地址（可选） */
  assetsUrl?: string;
  /** 构建产物相对路径（本地调试用） */
  localPath?: string;
  /** 上传后的本地镜像路径（provider=local 时） */
  uploadedPath?: string;
  /** 上传提供方：local | cdn */
  provider?: 'local' | 'cdn';
}

/**
 * 单个 channel 的配置文件根结构
 * 路径约定：project/config/channels/<channel>/bundles.local.json
 */
export interface BundlesConfigFile {
  rnVersion: string;
  baseVersion: string;
  /** 发布通道，与目录名一致 */
  channel: string;
  updatedAt: string;
  bundles: Record<string, BundleConfigItem[]>;
}

/** 单次打包生成的 manifest */
export interface BundleManifest {
  key: string;
  componentName: string;
  rnVersion: string;
  channel: string;
  platform: 'ios' | 'android';
  hash: string;
  entry: string;
  fileName: string;
  createdAt: string;
  minBaseVersion: string;
  kind: BundleKind;
  dependsOn?: string[];
  version: string;
}

/** 原生打开整页 RN 时传入的参数 */
export interface OpenBundleParams {
  key: string;
  /** 发布通道；不传则用宿主默认（如线上业务主分支） */
  channel?: string;
  /** 可覆盖配置中的 url */
  url?: string;
  /** 额外业务参数，透传给 RN 根组件 */
  props?: Record<string, unknown>;
}

/**
 * 调试入口参数：本地 Metro 或测试环境
 * platform / channel 由原生宿主传入
 */
export interface DebugEntryParams {
  host: string;
  port: number;
  /** 分包 key / 文件夹名 */
  key: string;
  /** 目标平台，由宿主标识 */
  platform: 'ios' | 'android';
  /**
   * 发布通道（分支）。正式/发测拉 CDN 时必填；
   * Metro 本地调试可忽略（源码即当前工作区）
   */
  channel?: string;
  /** true = 连 Metro；false = 按 URL 拉包 */
  useDevServer: boolean;
  /** 测试环境包地址（useDevServer=false 时使用） */
  bundleUrl?: string;
  /** 是否同时加载 common（双 URL 调试，默认 true） */
  loadCommon?: boolean;
}

/** Metro 调试 URL 构造结果 */
export interface MetroDebugUrls {
  /** 业务薄包 Metro 地址 */
  page: string;
  /** 公共包 Metro 地址（双包调试） */
  common: string;
}

/**
 * 构造 Metro 调试地址（宿主按 platform 区分 iOS/Android 包）
 * 本地 Metro 使用当前工作区源码，不按 channel 分路径
 */
export function buildMetroDebugUrls(params: {
  host: string;
  port: number;
  key: string;
  platform: 'ios' | 'android';
}): MetroDebugUrls {
  const { host, port, key, platform } = params;
  const qs = `platform=${platform}&dev=true&minify=false`;
  return {
    page: `http://${host}:${port}/src/${key}/index.bundle?${qs}`,
    common: `http://${host}:${port}/packages/common/src/index.bundle?${qs}`,
  };
}

/**
 * 规范化 channel 名（git 分支 → 路径安全）
 * feature/order-pay → feature-order-pay
 */
export function normalizeChannel(raw: string): string {
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

/** 上传配置（config/upload.local.json） */
export interface UploadLocalConfig {
  /** local：拷贝到本地 CDN 模拟目录；cdn：预留真实 CDN */
  provider: 'local' | 'cdn';
  /** 对外访问前缀，如 http://127.0.0.1:8787 */
  baseUrl: string;
  /** 本地上传根目录（相对仓库根或绝对路径） */
  targetDir: string;
  /**
   * 对象路径模板，须含 {channel}
   * 默认：rn/{rnVersion}/{channel}/{key}/{platform}/{fileName}
   */
  pathTemplate: string;
  /** 同目录保留历史版本数量（回滚用，默认 5） */
  keepHistory?: number;
  /** 未传 --channel 时的默认通道 */
  defaultChannel?: string;
  cdn?: {
    endpoint?: string;
    bucket?: string;
    accessKeyEnv?: string;
    secretKeyEnv?: string;
    comment?: string;
  };
}
