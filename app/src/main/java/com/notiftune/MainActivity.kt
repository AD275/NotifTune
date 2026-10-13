package com.notiftune

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

/**
 * 模块设置界面。参数写入 com.notiftune_preferences，
 * hook 侧经 XSharedPreferences 读取，新展示的通知即时生效。
 */
class MainActivity : Activity() {

    private lateinit var prefs: SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences(Config.PREFS_NAME, Context.MODE_PRIVATE)
        setContentView(buildUi())
    }

    // ---------- UI 构建 ----------

    private fun buildUi(): ViewGroup {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(24), px(24), px(24), px(24))
        }

        root.addView(TextView(this).apply {
            text = "NotifTune 通知手势"
            textSize = 20f
        })
        root.addView(TextView(this).apply {
            text = "经典通知栏样式下，启用「按住下滑立即展开」手势"
            textSize = 14f
            setPadding(0, px(4), 0, px(16))
        })

        // ---- 手势 ----
        root.addView(sectionTitle("手势"))
        root.addView(switchRow(
            "启用手势",
            prefs.getBoolean("gesture_enabled", true)
        ) { prefs.edit().putBoolean("gesture_enabled", it).apply() })

        // ---- 诊断 ----
        root.addView(sectionTitle("诊断"))
        root.addView(switchRow(
            "原生手势日志",
            prefs.getBoolean("log_native_gesture", false)
        ) { prefs.edit().putBoolean("log_native_gesture", it).apply() })
        root.addView(TextView(this).apply {
            text = "开启后重启系统界面，logcat 过滤 \"NotifTune\" 可查看原生手势（ExpandHelper）的生命周期日志"
            textSize = 12f
            setPadding(0, px(4), 0, px(12))
        })

        root.addView(Button(this).apply {
            text = "重启系统界面"
            setOnClickListener {
                try {
                    Runtime.getRuntime().exec(arrayOf("su", "-c", "killall com.android.systemui"))
                    toast("已尝试通过 root 重启系统界面")
                } catch (e: Exception) {
                    toast("root 重启失败，请在 LSPosed 管理器中手动重启系统界面")
                }
            }
        })

        // ---- 说明 ----
        root.addView(sectionTitle("说明"))
        root.addView(TextView(this).apply {
            text = "1. 请在 LSPosed 中启用本模块，并确认作用域包含 com.android.systemui；\n" +
                "2. 本模块直接调用系统原生的通知展开机制（ExpandHelper）：跟手伸缩、上滑缩回、" +
                "松手按拖动距离决定展开或合并、关闭通知栏自动恢复合并态；\n" +
                "3. 经典样式下列表滚动时原生手势会被系统禁掉，本模块仅恢复该状态，不接管触摸、不改变其他行为。"
            textSize = 13f
            setPadding(0, px(4), 0, 0)
        })

        return ScrollView(this).apply {
            addView(root)
        }
    }

    private fun sectionTitle(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 15f
        setPadding(0, px(16), 0, px(4))
    }

    private fun switchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit): LinearLayout {
        val dp = resources.displayMetrics.density
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, (8 * dp + 0.5f).toInt(), 0, (8 * dp + 0.5f).toInt())
            addView(TextView(this@MainActivity).apply {
                text = label
                textSize = 15f
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(Switch(this@MainActivity).apply {
                isChecked = checked
                setOnCheckedChangeListener { _, value -> onChange(value) }
            })
        }
    }

    private fun px(v: Int) = (v * resources.displayMetrics.density + 0.5f).toInt()

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }
}
