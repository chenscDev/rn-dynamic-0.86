# 蒲公英双环境（测试 Debug + 线上 Release）

手机可**同时安装**两套包（不同 `applicationId`），蒲公英用**两个应用**分别展示扫码下载。

## 包差异

| | 测试（Debug） | 线上（Release） |
|--|---------------|-----------------|
| 命令产物 | `RnDynamicBase-internal-debug.apk` | `RnDynamicBase-internal-release.apk` |
| applicationId | `com.rndynamicbase.debug` | `com.rndynamicbase` |
| 桌面名称 | RnDynamic 测试 | RnDynamic |
| 资源加载 | 右下角悬浮：**远程 CDN / 本地 Metro**（默认远程） | **固定远程 CDN**，无调试 UI |
| 我的 Tab | 保留「RN 调试入口」等 | 无调试入口 |
| 远程主机 | `assets/rn-config/cdn-host.txt`（当前 `47.93.207.70`） | 同左（bundles 内 CDN URL） |

## 一键打两包

在仓库根目录：

```bash
./scripts/build-pgyer-both.sh
```

产物目录：`project/dist/apk/`

也可单独：

```bash
./scripts/build-debug-apk.sh    # 仅测试
./scripts/build-pgyer-apk.sh    # 仅线上
```

## 蒲公英如何展示两个环境

蒲公英按 **应用（包名）** 隔离，不会在同一个应用页里并排两个不同包名。推荐：

1. 在蒲公英新建两个应用（或两个项目）  
   - **RnDynamic 测试** ← 上传 `*-debug.apk`  
   - **RnDynamic**（或「线上」）← 上传 `*-release.apk`
2. 把两个应用的安装页二维码 / 短链放到你们的文档或飞书里，标注「测试」「线上」。
3. 同事按需扫对应码；手机桌面会出现两个图标，互不覆盖。

> 若坚持「一个蒲公英应用里看两个版本」：只能同一 `applicationId` 下上传多版本历史，**无法并装**，且 Debug/Release 会互相覆盖。本仓库刻意拆开包名，优先并装体验。

## 上传方式

蒲公英网页：打开对应应用 → 上传 APK。

或用 API（示例，需自行配置 `_api_key` / `uKey`）：

```bash
# 测试包
curl -F "file=@project/dist/apk/RnDynamicBase-internal-debug.apk" \
  -F "_api_key=$PGYER_API_KEY" \
  -F "buildUpdateDescription=测试包 Debug · 可切远程/本地" \
  https://www.pgyer.com/apiv2/app/upload

# 线上包（建议用另一个应用的 uKey，或同一账号下另一应用）
curl -F "file=@project/dist/apk/RnDynamicBase-internal-release.apk" \
  -F "_api_key=$PGYER_API_KEY" \
  -F "buildUpdateDescription=线上 Release · 仅远程 CDN" \
  https://www.pgyer.com/apiv2/app/upload
```

首次上传 Debug 与 Release 会分别创建两个应用；之后各自更新即可。

## 测试包使用提示

1. 安装「RnDynamic 测试」后，右下角点 **远程 / 本地**。  
2. **远程**：自动用 CDN（`cdn-host.txt`），关本机 Metro 也能进问答。  
3. **本地**：填电脑局域网 IP，电脑 `cd rn-biz-0.86 && yarn start`。  
4. 「我的 → RN 调试入口」仍可细调 channel / key。

## 改远程 IP / 域名

1. 改 `platforms/android/app/src/main/assets/rn-config/cdn-host.txt`  
2. 改 `platforms/android/app/build.gradle` 中 `CDN_BASE_HOST`  
3. 重新 `yarn pack:publish` / 更新 `bundles.local.json` 里的 CDN URL  
4. 重新打 APK
