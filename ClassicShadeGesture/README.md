# 经典通知自由展开

面向 REDMI K90 Pro Max / Android 17 / 澎湃 OS 4.0.2.0.XPMCNXM / LSPosed 2.2.1（7912，API 102）的轻量模块。

目标是保留系统“经典样式”的通知布局，并复用系统内置的 Android `ExpandHelper`，实现拖动过程中逐渐展开、同一次触摸内反向拖动逐渐收回。没有重建通知视图或复制三星组件。

## 实现

模块只有两个功能 hook，作用域固定为 `com.android.systemui`：

1. `NotificationStackScrollLayoutControllerInjectorImpl.updateViewExpandingEnabled()`：保留原有状态更新，在经典样式、非 KEYGUARD 状态下允许已有的 `ExpandHelper` 工作。
2. `NotificationPanelViewControllerInjector.handleStretchState()`：在已完全打开的经典通知栏内，将通知栏拉伸接管的起步阈值对齐到本机原生样式已有的 `1.5 × touchSlop`，让通知子视图先识别展开动作。其他情形继续调用原方法。

高度计算、内容过渡、松手动画、速度计算、点击、左右滑动均交给原系统。没有服务、定时器、唤醒锁、网络权限、通知监听器或全局布局扫描。应用的通知权限仅用于用户点击“发送测试通知”后发布本地示例；不授予此权限也不影响 hook。

不支持的 SystemUI 字段或方法会记录错误并撤销已安装的功能 hook。没有修改或替换系统 APK；没有更改通知隐私设置。固件升级后需要重新核对内部接口，不能据此声称兼容所有澎湃版本。

## 使用

1. 安装 APK，在 LSPosed 启用“经典通知自由展开”。
2. 作用域选中“系统界面”（`com.android.systemui`）。
3. 系统通知样式保持“经典样式”。
4. 重启系统界面或手机。
5. 下拉通知栏，手指落在可展开的通知上即向下拖动。无需等待长按菜单；维持同一次触摸往回拖动，可逐渐收回。

不是每条通知都有更多内容：是否能展开仍取决于通知提供的展开内容。分组展开和单条通知展开是系统原有的不同状态。松手后的结果由原系统决定。

恢复原状：在 LSPosed 禁用本模块，再重启系统界面；需要时卸载 APK。

## 编译

依赖 JDK 17、Android SDK Platform 36、Gradle 8.14.5。创建 `local.properties` 指向本机 SDK：

```properties
sdk.dir=/path/to/Android/sdk
```

```sh
./gradlew :app:assembleDebug :app:lintDebug
./gradlew :app:assembleRelease :app:lintRelease
```

`release` 构建默认未签名，应使用自己的 Android 签名证书执行 zipalign/apksigner。源码包不含私人签名密钥、本机 SDK 路径、SystemUI 反编译源码或手机配置备份。当前任务的签名密钥保存在本地工作目录 `.signing/`，后续更新应使用同一证书。

接口依据：[libxposed API 102 Maven 发布](https://central.sonatype.com/artifact/io.github.libxposed/api/102.0.0)、[LSPosed 模块开发说明](https://github.com/LSPosed/LSPosed/wiki/Develop-Xposed-Modules-Using-Modern-Xposed-API)。本机 SystemUI 实现通过 ADB 读取核对。

## 验证状态

本机已通过单条与分组通知的连续下拉、同一次触摸内反向回拖、松手保持展开测试；用户手动确认点击通知可以打开应用。单条通知高度为 `204 → 294 → 424 → 554 → 394 → 254 → 204 px`，分组为 `282 → 372 → 502 → 632 → 472 → 332 → 282 px`。松手保持展开测试为 `625 px`，系统记录 `isUserExpanded=true`。

没有额外实现“松手后另起一次上滑”的折叠手势，该动作仍沿用原系统的滚动和通知交互。截图和完整记录见随附《使用与验证说明》。功耗尚未做长时间 A/B 测试；没有常驻任务不等于实测零额外耗电。


