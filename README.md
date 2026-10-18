# NotifTune

让小米澎湃OS（HyperOS）的**经典通知样式**支持**原生安卓的下拉展开 / 上滑收回手势**的 LSPosed 模块。

> 背景：澎湃OS切换到「原生样式」后可以按住下滑展开通知，但很多人（包括我）更喜欢经典样式的外观；
> 而经典样式下通知列表滚动时，下拉会被系统当作列表滚动，无法展开通知。
> 本仓库只做一件事：把这套原生手势在经典样式下**接回来**。

---

## ⭐ 当前推荐版本：ClassicShadeGesture 1.0.4（2026-10）

针对 **澎湃OS 4 / Android 17** 重写的轻量实现（源码目录 [`ClassicShadeGesture/`](ClassicShadeGesture/)，安装包 [`ClassicShadeGesture-1.0.4.apk`](ClassicShadeGesture-1.0.4.apk)）。

**设计思路**：保留系统「经典样式」的通知布局，**复用系统内置的 Android `ExpandHelper`**——不重建通知视图、不做全局布局扫描。模块只有两个 hook（作用域固定 `com.android.systemui`）：

1. 保留 `NotificationStackScrollLayoutControllerInjectorImpl.updateViewExpandingEnabled()` 原有状态更新，在经典样式、非 KEYGUARD 状态下允许已有的 `ExpandHelper` 工作；
2. 在已完全打开的经典通知栏内，将 `NotificationPanelViewControllerInjector.handleStretchState()` 的拉伸接管起步阈值对齐到原生样式已有的 `1.5 × touchSlop`，让通知子视图先识别展开动作。

高度计算、内容过渡、松手动画、速度、点击、左右滑动全部交给原系统。

**1.0.4 新增经典分组子通知布局适配**：分组拖动时按实际卡片高度累加子通知位置、裁切超出边界的子视图、分组标题/图标/数量/背景随进度变化，修掉了此前"拖动出现大块空白、松手才铺开"的问题；仍复用原生内容变换与 ExpandHelper 松手动画。

| 手势 | 行为 |
|---|---|
| 在可展开通知上**向下拖** | 跟手逐级展开（单条与分组均支持） |
| 同一次触摸内**反向回拖** | 逐级收回 |
| 列表滚动 / 点按 / 侧滑删除 | 系统原有行为，完全不受影响 |

### 安装

1. 安装 APK，在 LSPosed 启用「经典通知自由展开」；
2. 作用域选中「系统界面」（`com.android.systemui`）；
3. 系统通知样式保持「经典样式」；
4. 重启系统界面或手机；
5. 下拉通知栏，手指落在可展开的通知上向下拖动即可（无需长按菜单；同一次触摸往回拖可逐渐收回）。

不是每条通知都有更多内容：能否展开取决于通知本身是否提供展开内容。分组展开与单条展开是系统原有的不同状态。

### 兼容性（ClassicShadeGesture 1.0.4）

- 实测设备：Redmi K90 Pro Max（`myron`），澎湃OS 4.0.4.0.XPMCNXM / Android 17，LSPosed 2.2.1
- 1.0.4 分组实测：用户确认可用；日志记录 2、7、8 条子通知的分组拖动，无适配错误
- 单条通知展开高度实测：`204 → 294 → 424 → 554 → 394 → 254 → 204 px`；分组：`282 → 372 → 502 → 632 → 472 → 332 → 282 px`
- 未在其他机型/系统版本测试；固件升级后需要重新核对内部接口，欢迎反馈

编译方式见 [`ClassicShadeGesture/README.md`](ClassicShadeGesture/README.md)。

---

## 历史版本：NotifTune 2.0（澎湃OS 3 时代）

最初的 Kotlin 实现（源码目录 [`app/`](app/)，安装包 [`NotifTune-2.0.apk`](NotifTune-2.0.apk)），面向澎湃OS 3 / Android 16。

<details>
<summary>展开查看 NotifTune 2.0 详情</summary>

### 功能

| 手势 | 行为 |
|---|---|
| 在通知上**向下滑** | 展开该通知（官方展开动画） |
| 在通知上**向上滑** | 收回该通知（仅当手指下这条自身处于展开态时） |
| 分组通知（如多条应用消息堆叠） | 分组未展开时下滑 → 展开分组；分组已展开时下滑某条 → 展开该条完整内容 |
| 未展开区域的上下滑动 | 完全不受影响（列表滚动、侧滑删除、上滑关闭通知栏照常） |
| 点按通知 | 照常打开应用，不会被手势误伤 |

### 实现思路

全部复用系统原生机制：

1. **展开/收回执行**：调用系统 `ExpandHelper` 回调 `setUserExpandedChild(row, true/false)`；收回方向补偿一次 `notifyHeightChanged` 高度通知；
2. **手势识别**：全局 `dispatchTouchEvent` 钩子 + 坐标命中测试，在通知行上位移超过阈值即触发；
3. **冲突消解**：在列表层与通知面板层做最小化拦截，避免"展开手势变成滚动列表"或"收回手势误关通知栏"。

### 兼容性

- 实测设备：Redmi K90 Pro Max（`myron`），澎湃OS 3 / Android 16，LSPosed 2.2.1 + KernelSU
- 作用域：`com.android.systemui`；通知样式：经典样式
- 澎湃OS 4 / Android 17 的系统接口变化较大，该实现未随版本更新；澎湃OS 4 上请使用上面的 ClassicShadeGesture 1.0.0

</details>

## License

见 [LICENSE](LICENSE)。
