# 开发与适配指南

> 本文件记录了模块的调试方法、参考材料的重建步骤，以及面向未来系统版本（如澎湃OS 4）适配的要点。
> 仓库中**不包含**反编译的系统代码等大体积参考材料（版权 + 体积原因），需要时按下文现场重建即可。

## 一、构建环境

| 依赖 | 说明 |
|---|---|
| JDK 17 | 构建必需（如 Amazon Corretto 17） |
| Android SDK | `local.properties` 里配置 `sdk.dir=...`（compileSdk 36，构建时自动下载所需组件） |
| Gradle | 用仓库自带的 wrapper：`./gradlew assembleRelease`（首次会自动下载 Gradle 8.13） |

签名使用仓库内的 `notiftune.keystore`（alias/password 见 `app/build.gradle`），保持与发布版同签名。

## 二、参考材料重建（按需，不随仓库分发）

1. **HyperCeiler 源码**（钩子写法的参考）：
   ```bash
   git clone --depth 1 https://github.com/Sevtinge/HyperCeiler
   ```

2. **反编译本机 SystemUI**（分析小米实现的关键步骤）：
   ```bash
   # 下载 apktool（约 30MB）
   # https://github.com/iBotPeaches/Apktool/releases
   # 从手机拉取当前系统版本的 SystemUI（不同版本路径可能不同）
   adb shell pm path com.android.systemui          # 找到 apk 路径
   adb pull <上一步的路径> MiuiSystemUI.apk
   # 反编译（约 10-30 秒）
   java -jar apktool-cli.jar d MiuiSystemUI.apk -o decoded
   ```
   代码位于 `decoded/smali*_classes*/`，资源在 `decoded/res/`。

## 三、真机调试流程

```bash
# 1. 构建并安装
./gradlew assembleRelease
adb install -r app/build/outputs/apk/release/app-release.apk

# 2. 让模块在 SystemUI 中重新加载（等价于重启系统界面）
adb shell am crash com.android.systemui

# 3. 查看模块日志（含诊断）
adb logcat | grep NotifTune
# 诊断日志默认关闭：模块设置页打开「原生手势日志」后重启系统界面即生效
```

## 四、代码架构（适配时优先读这里）

核心全部在 `app/src/main/java/com/notiftune/tweaks/ExpandGestureTweak.kt`，六个部分：

| 部分 | 作用 | 为什么需要 |
|---|---|---|
| 手势跟踪 | 全局 `dispatchTouchEvent` 钩子 + 行命中测试（最上层 z 序优先、空白区识别、最深视图兜底） | 经典样式下系统不把手势给通知行，需要自己识别"行上滑动" |
| 展开执行 | `NSSL$6.setUserExpandedChild(row, true)` 官方回调 | 复用系统展开动画；分组两级语义（先展组、再展条）由 `isChildInGroup` + `isGroupExpanded$1` 判定 |
| 收回执行 | 官方回调 `(row, false)` + **补偿 `notifyHeightChanged(true)`** | ⚠️ 小米的 `setUserExpanded` 只在展开方向补高度通知，收回方向缺失，不补则"收了不缩"（本项目最重要的兼容发现） |
| 反抢断 | `NotificationStackLayoutController$TouchHandler.onInterceptTouchEvent` 结果按回 false | 否则向下拖会被列表滚动抢走 |
| 面板守卫 | `NotificationPanelViewController$TouchHandler` / `NotificationPanelView` / `NotificationShadeWindowView` 的 touch 方法跳过 + `collapse/instantCollapse` 入口拦截（600ms 时间窗） | 收回手势期间防止事件穿透到面板触发"上滑关通知栏" |
| 点击抑制 | 动作后 600ms 内抑制通知行 `performClick` | 防止展开/收回手势被判定为点击而误开 APP |

另有两个辅助机制：

- **DOWN 体检**（`clearStaleExpansion`）：每次按下时清理残留的 `mExpanding/mExpansionStyle/mWatchingForPull`，保证任何异常状态不跨手势粘连。
- **跟手驱动（未完成）**：`startExpanding + updateExpansion + finishExpanding` 管线已接通但不产生视觉增长
  （`getMaxContentHeight` 有空间，管线未生效），欢迎继续研究——`updateExpansion` 内部通过
  `setFinalActualHeight` 直接改行高，是"逐帧跟手"的正路。

## 五、常见问题速查

| 现象 | 排查方向 |
|---|---|
| 完全无反应 | 模块是否启用、作用域是否含 `com.android.systemui`、系统界面是否重启过 |
| 展开/收回无视觉 | 检查 `notifyHeightChanged` 补偿是否存在、`ExpandHelper` 回调是否可达（开诊断日志看 `setUserExpandedChild`） |
| 手势变成滚动 | 反抢断钩子是否生效（系统版本升级后类名/结构可能变化） |
| 上滑误关通知栏 | 面板守卫的类名/方法名需要跟随系统更新（`NotificationPanelViewController` 等） |
| 误开 APP | 点击抑制的时间窗或条件需要调整 |

## 六、面向新系统版本的适配清单

系统大版本升级后，按顺序核对：

1. `ExpandHelper`、`NotificationStackScrollLayout`、`NSSL$6`、`TouchHandler`、`NotificationPanelViewController` 这几个类是否还在、包名/方法签名是否变化；
2. 反编译新 SystemUI，对照本文件第四节逐一核对钩子目标（IDE 的 smali 搜索：`grep -rn "方法名" decoded/`）；
3. 重点复核 `setUserExpanded` 的高度通知逻辑（第四节标注的兼容发现是否仍成立）；
4. 实机回归：单条展开/收回、分组两级、滚动、删卡、关栏、点按打开。
