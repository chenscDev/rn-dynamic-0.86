/**
 * 分包注册工具：统一 AppRegistry 注册约定
 * 约定：componentName 默认等于 src 文件夹名（分包 key）
 */
import type { ComponentType } from 'react';
import { AppRegistry } from 'react-native';

export type RegisterPageOptions = {
  /** 分包 key，通常与文件夹名一致 */
  key: string;
  /** 覆盖默认注册名（默认 = key） */
  componentName?: string;
};

/**
 * 注册整页分包根组件，供原生以 moduleName 打开
 */
export function registerPage(
  options: RegisterPageOptions,
  RootComponent: ComponentType<Record<string, unknown>>,
): string {
  const { key } = options;
  if (!key || typeof key !== 'string') {
    throw new Error('[rn-dynamic] registerPage: key 不能为空');
  }

  const componentName = options.componentName || key;

  try {
    AppRegistry.registerComponent(componentName, () => RootComponent);
  } catch (error) {
    const message = error instanceof Error ? error.message : String(error);
    throw new Error(
      `[rn-dynamic] 注册分包失败 key=${key} name=${componentName}: ${message}`,
    );
  }

  return componentName;
}
