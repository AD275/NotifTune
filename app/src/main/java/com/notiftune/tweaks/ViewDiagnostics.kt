package com.notiftune.tweaks

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import de.robv.android.xposed.XposedBridge

/**
 * 诊断工具：把通知行的视图树打印到 logcat（tag: NotifTune）。
 * 用于设备连接后确认通知卡片内部结构（TextView/ImageView/padding/背景），
 * 为后续精确调节提供依据。在模块设置里开启「转储通知视图树」后重启系统界面生效。
 */
object ViewDiagnostics {

    fun dump(root: View) {
        val sb = StringBuilder()
        sb.append("==== NotifTune view tree dump start ====\n")
        walk(root, 0, sb)
        sb.append("==== NotifTune view tree dump end ====")
        XposedBridge.log(sb.toString())
    }

    private fun walk(v: View, depth: Int, sb: StringBuilder) {
        runCatching {
            val indent = "  ".repeat(depth)
            sb.append(indent).append(v.javaClass.simpleName)
            runCatching { sb.append(" id=").append(v.resources.getResourceName(v.id)) }
            if (v is TextView) {
                sb.append(" text=\"").append(v.text?.toString()?.take(24)).append("\"")
                sb.append(" textSize=").append(v.textSize)
            }
            sb.append(" size=").append(v.width).append("x").append(v.height)
            sb.append(" pad=").append(v.paddingLeft).append(",").append(v.paddingTop)
                .append(",").append(v.paddingRight).append(",").append(v.paddingBottom)
            sb.append(" bg=").append(v.background?.javaClass?.simpleName)
            sb.append("\n")
            if (v is ViewGroup) {
                for (i in 0 until v.childCount) walk(v.getChildAt(i), depth + 1, sb)
            }
        }
    }
}
