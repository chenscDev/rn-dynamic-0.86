/**
 * 基座运行时导出（不分包上传，随基座发布）
 */
export { registerPage } from './registerPage';
export type { RegisterPageOptions } from './registerPage';
export { PageShell } from './PageShell';
export type { PageShellProps } from './PageShell';
export { createPackageApp } from './createPackageApp';
export type {
  CreatePackageAppOptions,
  PackageScreenConfig,
} from './createPackageApp';
export {
  CURRENT_PLATFORM,
  isAndroid,
  isIOS,
  platformSelect,
} from './platform';
export type { AppPlatform } from './platform';
export { finishNativeContainer } from './navigationBridge';
