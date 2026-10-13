package com.notiftune

import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage
import com.notiftune.tweaks.ExpandGestureTweak

/**
 * NotifTune 模块入口（legacy Xposed API，xposedminversion=82）。
 *
 * 作用域：com.android.systemui
 * 功能：恢复被「列表滚动中」挡掉的系统原生通知下拉展开手势（ExpandHelper），
 *       跟手伸缩、上滑缩回、松手提交、关闭重置全部由原生机制完成。
 */
class HookEntry : IXposedHookLoadPackage {

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != TARGET_PKG) return
        runCatching {
            Config.reload()
            XposedBridge.log("[NotifTune] hooked SystemUI, process=${lpparam.processName}")
            ExpandGestureTweak.init(lpparam)
            XposedBridge.log("[NotifTune] init done, gestureEnabled=${Config.gestureEnabled}")
        }.onFailure {
            XposedBridge.log("[NotifTune] init failed: $it")
        }
    }

    companion object {
        const val TARGET_PKG = "com.android.systemui"
    }
}
