/**
 * RN 导航桥接
 */
import { NativeModules } from 'react-native';

const NativeNav = NativeModules.RNNavigationModule as
  | { finishContainer: () => void }
  | undefined;

export function finishNativeContainer(): void {
  NativeNav?.finishContainer?.();
}
