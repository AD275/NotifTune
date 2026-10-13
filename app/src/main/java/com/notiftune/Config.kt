package com.notiftune

import de.robv.android.xposed.XSharedPreferences
import de.robv.android.xposed.XposedBridge

/**
 * 模块配置。
 *
 * App 侧写入 /data/data/com.notiftune/shared_prefs/com.notiftune_preferences；
 * Hook 侧（SystemUI 进程）通过 XSharedPreferences 旧路径（minversion=82）经 lspd
 * 服务以 root 读取，不受 SELinux 与文件权限限制，且该路径不会被移除。
 */
object Config {
    const val TAG = "NotifTune"
    const val PKG = "com.notiftune"
    const val PREFS_NAME = "com.notiftune_preferences"

    // ---- 可调参数（默认值）----
    var gestureEnabled = true       // 手势总开关
    var logNativeGesture = false    // 诊断：打印手势诊断日志（默认关闭）

    private var prefs: XSharedPreferences? = null
    private var lastReloadAt = 0L

    /** 完整重读配置。 */
    fun reload() {
        runCatching {
            val p = prefs ?: XSharedPreferences(PKG).also { prefs = it }
            p.reload()
            gestureEnabled = p.getBoolean("gesture_enabled", true)
            logNativeGesture = p.getBoolean("log_native_gesture", false)
        }.onFailure {
            XposedBridge.log("[$TAG] config reload failed: $it")
        }
    }

    /** 节流重读：避免每个事件都做文件 stat。 */
    fun reloadThrottled(intervalMs: Long = 1500L) {
        val now = System.currentTimeMillis()
        if (now - lastReloadAt > intervalMs) {
            lastReloadAt = now
            reload()
        }
    }
}
