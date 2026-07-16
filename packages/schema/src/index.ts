/**
 * 分包配置与 manifest 类型定义
 * 原生端与 bundler 共用同一套契约
 */

/** 单个分包在配置中心（本地或远程）的条目 */
export interface BundleConfigItem {
  /** 分包唯一 key，默认等于 src 下文件夹名 */
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
  /** 构建产物相对路径（本地调试用） */
  localPath?: string;
  /** 上传后的本地镜像路径（provider=local 时） */
  uploadedPath?: string;
  /** 上传提供方：local | cdn */
  provider?: 'local' | 'cdn';
}

/** 本地 / 远程配置文件根结构 */
export interface BundlesConfigFile {
  rnVersion: string;
  baseVersion: string;
  updatedAt: string;
  bundles: Record<string, BundleConfigItem[]>;
}

/** 单次打包生成的 manifest */
export interface BundleManifest {
  key: string;
  componentName: string;
  rnVersion: string;
  platform: 'ios' | 'android';
  hash: string;
  entry: string;
  fileName: string;
  createdAt: string;
  minBaseVersion: string;
}

/** 原生打开整页 RN 时传入的参数 */
export interface OpenBundleParams {
  key: string;
  /** 可覆盖配置中的 url */
  url?: string;
  /** 额外业务参数，透传给 RN 根组件 */
  props?: Record<string, unknown>;
}

/** 调试入口参数：本地 Metro 或测试环境 */
export interface DebugEntryParams {
  host: string;
  port: number;
  /** 分包 key / 文件夹名 */
  key: string;
  /** true = 连 Metro；false = 按 URL 拉包 */
  useDevServer: boolean;
  /** 测试环境包地址（useDevServer=false 时使用） */
  bundleUrl?: string;
}

/** 上传配置（config/upload.local.json） */
export interface UploadLocalConfig {
  /** local：拷贝到本地 CDN 模拟目录；cdn：预留真实 CDN */
  provider: 'local' | 'cdn';
  /** 对外访问前缀，如 http://127.0.0.1:8787 */
  baseUrl: string;
  /** 本地上传根目录（相对仓库根或绝对路径） */
  targetDir: string;
  /** 对象路径模板 */
  pathTemplate: string;
  cdn?: {
    endpoint?: string;
    bucket?: string;
    accessKeyEnv?: string;
    secretKeyEnv?: string;
    comment?: string;
  };
}
