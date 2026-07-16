/**
 * 平台标识与运行时判断
 * 打包时 Metro 会按 --platform 解析 .ios / .android 后缀文件，非本平台文件不会进 bundle
 */
import { Platform } from 'react-native';

export type AppPlatform = 'ios' | 'android';

/** 当前运行平台 */
export const CURRENT_PLATFORM: AppPlatform =
  Platform.OS === 'ios' ? 'ios' : 'android';

export function isIOS(): boolean {
  return CURRENT_PLATFORM === 'ios';
}

export function isAndroid(): boolean {
  return CURRENT_PLATFORM === 'android';
}

/**
 * 按平台选择值（运行时分支；大段互斥逻辑请优先用 .ios.tsx / .android.tsx 文件）
 */
export function platformSelect<T>(options: {
  ios: T;
  android: T;
  default?: T;
}): T {
  if (CURRENT_PLATFORM === 'ios') {
    return options.ios;
  }
  if (CURRENT_PLATFORM === 'android') {
    return options.android;
  }
  if (options.default !== undefined) {
    return options.default;
  }
  return options.android;
}
