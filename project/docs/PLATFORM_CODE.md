# 平台代码区分（iOS / Android）

本基座使用 **React Native / Metro 原生平台后缀机制**，在打包时自动排除非目标平台文件，无需额外 Babel 插件。

## 推荐方式：文件后缀（打包期排除）

与 RN 官方一致，按平台拆分文件：

```
src/home/
  PlatformTag.tsx          # 可选：公共回退
  PlatformTag.ios.tsx      # 仅 iOS bundle 包含
  PlatformTag.android.tsx  # 仅 Android bundle 包含
```

引用时**不要写后缀**：

```tsx
import { PlatformTag } from './PlatformTag';
```

| 命令 / 场景 | Metro 实际解析 |
|-------------|----------------|
| `rn-pack build home --platform ios` | `PlatformTag.ios.tsx` |
| `rn-pack build home --platform android` | `PlatformTag.android.tsx` |
| `yarn start` + iOS 模拟器 | `.ios.tsx` |
| `yarn start` + Android 模拟器 | `.android.tsx` |

还支持 `.native.tsx`（iOS + Android 共用，Web 排除）。

**规则**：`rn-pack` 已传 `--platform ios|android` 给 Metro，非本平台后缀文件不会进入该次 bundle。

## 辅助方式：运行时判断（小分支）

适合颜色、文案等轻量差异，**不适合**大段互斥逻辑（两边仍会打进包，靠 tree-shaking 不一定能删干净）：

```tsx
import { isIOS, platformSelect } from '../_runtime';

const title = platformSelect({ ios: 'iOS 标题', android: 'Android 标题' });
```

## 不推荐

| 方式 | 原因 |
|------|------|
| 单文件里大量 `if (Platform.OS)` | 两平台代码都会进 bundle |
| 自定义 `@platform` 注释剥离 | 需 Babel 插件，维护成本高 |
| `src/home/ios/` 目录硬隔离 | 需改 Metro resolver，改动大 |

若后续确有「整目录按平台隔离」需求，再单独评估 Metro 自定义 resolver。

## 示例

见 `src/home/PlatformTag.ios.tsx` 与 `PlatformTag.android.tsx`。
