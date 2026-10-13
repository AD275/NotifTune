package com.notiftune.tweaks

import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import com.notiftune.Config
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.lang.ref.WeakReference
import kotlin.math.abs

/**
 * 「下滑展开」手势（v1.13 稳定版）。
 *
 * 设计（v1.0~1.12 十几次真机迭代后收敛）：
 *   1. 展开使用系统官方回调 ExpandHelperCallback.setUserExpandedChild(row, true)
 *      —— 即原生拖开展开松手提交时执行的同一动作（v1.6 验证可展开）；
 *   2. 触发用本模块的手势跟踪（全局 dispatch + 行命中测试）：行上向下拖超过 16dp 即触发；
 *   3. 反抢断：滚动拦截器想在展开触发前抢走向下拖时将其按回 false，避免「滑一半变成
 *      滚动列表」；向上/侧滑不受影响，滚动与删卡照常；
 *   4. 状态体检：每次按下时清理上一手势可能残留的展开状态
 *      （mExpanding/mExpansionStyle/mExpandingNotification），保证滚动永不失效。
 *
 * 已知限制：跟手逐帧缩放需要原生 ExpandHelper 的完整手势管线，经典样式下其入口被
 * 系统自己关闭，无法借用；本模块的展开为官方回调的动画展开。
 */
object ExpandGestureTweak {
    private const val TAG = "NotifTune"
    private const val EXPAND_HELPER_CLASS = "com.android.systemui.ExpandHelper"
    private const val TOUCH_HANDLER_CLASS =
        "com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayoutController\$TouchHandler"
    private const val NSSL_CLASS =
        "com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayout"

    private var activeRowRef: WeakReference<View>? = null
    private var startY = 0f
    private var startX = 0f
    private var expandedFired = false
    private var collapsedFired = false
    private var lastDy = 0f
    private var lastReversalAt = 0L
    private var lastActionAt = 0L
    private var clickSuppressLog = 0
    private var helperRef: WeakReference<Any>? = null
    private var rowClazz: Class<*>? = null
    private var nsslClazz: Class<*>? = null

    fun init(lpparam: XC_LoadPackage.LoadPackageParam) {
        nsslClazz = runCatching {
            XposedHelpers.findClass(NSSL_CLASS, lpparam.classLoader)
        }.getOrNull()
        rowClazz = runCatching {
            XposedHelpers.findClass(
                "com.android.systemui.statusbar.notification.row.ExpandableNotificationRow",
                lpparam.classLoader)
        }.getOrNull()
        hookTracking(lpparam)
        hookExpandHelper(lpparam)
        hookTouchHandler(lpparam)
        hookPanelGuard(lpparam)
        hookClickSuppressor(lpparam)
        if (Config.logNativeGesture) hookDiagnostics(lpparam)
        log("initialized (logNativeGesture=${Config.logNativeGesture})")
    }

    // ---------- 点击抑制：本模块动作（展开/收回）后的短时间窗口内，抑制通知卡片的点击 ----------

    private fun hookClickSuppressor(lpparam: XC_LoadPackage.LoadPackageParam) {
        runCatching {
            XposedHelpers.findAndHookMethod(
                android.view.View::class.java.name, lpparam.classLoader,
                "performClick", object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (!Config.gestureEnabled) return
                        if (System.currentTimeMillis() - lastActionAt > 600L) return
                        val v = param.thisObject as? View ?: return
                        val row = rowClazz ?: return
                        if (isInRow(v, row)) {
                            param.result = false
                            if (clickSuppressLog < 5) {
                                clickSuppressLog++
                                logD("notification click suppressed (during gesture)")
                            }
                        }
                    }
                })
            log("hooked View.performClick (click suppressor)")
        }.onFailure { log("hook click suppressor failed: $it") }
    }

    private fun isInRow(v: View, rowClazz: Class<*>): Boolean {
        var p: View? = v
        var depth = 0
        while (p != null && depth < 30) {
            if (rowClazz.isInstance(p)) return true
            p = p.parent as? View
            depth++
        }
        return false
    }

    // ---------- 0. 面板守卫：手势进行中（行已展开）向上拖时，不许通知面板接管（上滑关栏） ----------

    private fun hookPanelGuard(lpparam: XC_LoadPackage.LoadPackageParam) {
        listOf(
            "com.android.systemui.shade.NotificationPanelViewController\$TouchHandler",
            "com.android.systemui.shade.NotificationPanelView",
            "com.android.systemui.shade.NotificationShadeWindowView"
        ).forEach { cls ->
            runCatching {
                val clazz = XposedHelpers.findClass(cls, lpparam.classLoader)
                XposedHelpers.findAndHookMethod(clazz, "onInterceptTouchEvent", MotionEvent::class.java,
                    object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            if (!Config.gestureEnabled) return
                            runCatching { maybeBlockPanelSteal(param) }.onFailure { log("panel guard error: $it") }
                        }
                    })
                // onTouch / onTouchEvent / handleTouch$1：反向回拖期间直接跳过面板处理（关栏总源头）
                listOf("onTouch", "onTouchEvent", "handleTouch\$1").forEach { name ->
                    runCatching {
                        XposedHelpers.findAndHookMethod(clazz, name, MotionEvent::class.java,
                            object : XC_MethodHook() {
                                override fun beforeHookedMethod(param: MethodHookParam) {
                                    if (!Config.gestureEnabled) return
                                    val ev = param.args[0] as? MotionEvent ?: return
                                    if (isReversalContext(ev)) {
                                        param.result = false
                                        if (panelBlockLogCount < 8) {
                                            panelBlockLogCount++
                                            logD("panel $name skipped (reversal)")
                                        }
                                    }
                                }
                            })
                    }
                }
                // handleTouch$1(View, MotionEvent) 变体（若存在）
                runCatching {
                    XposedHelpers.findAndHookMethod(clazz, "onTouch", View::class.java, MotionEvent::class.java,
                        object : XC_MethodHook() {
                            override fun beforeHookedMethod(param: MethodHookParam) {
                                if (!Config.gestureEnabled) return
                                val ev = param.args[1] as? MotionEvent ?: return
                                if (isReversalContext(ev)) {
                                    param.result = false
                                    if (panelBlockLogCount < 8) {
                                        panelBlockLogCount++
                                        logD("panel onTouch(view,ev) skipped (reversal)")
                                    }
                                }
                            }
                        })
                }
                log("hooked $cls (panel guard)")
            }.onFailure { log("hook $cls failed: $it") }
        }

        // 面板收缩入口设岗：反向回拖期间，任何路径的 collapse 一律拦截（防「上滑关栏」）
        runCatching {
            val pvc = XposedHelpers.findClass(
                "com.android.systemui.shade.NotificationPanelViewController", lpparam.classLoader)
            val collapseHook = object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!Config.gestureEnabled) return
                    if (isReversalActive()) {
                        param.result = null    // void 方法：跳过原逻辑
                        if (panelBlockLogCount < 8) {
                            panelBlockLogCount++
                            logD("panel collapse blocked")
                        }
                    }
                }
            }
            runCatching {
                XposedHelpers.findAndHookMethod(pvc, "collapse",
                    java.lang.Float.TYPE, java.lang.Boolean.TYPE, collapseHook)
            }
            runCatching {
                XposedHelpers.findAndHookMethod(pvc, "collapse",
                    java.lang.Float.TYPE, java.lang.Boolean.TYPE, java.lang.Boolean.TYPE, collapseHook)
            }
            runCatching {
                XposedHelpers.findAndHookMethod(pvc, "instantCollapse", collapseHook)
            }
            log("hooked NotificationPanelViewController.collapse (reversal block)")
        }.onFailure { log("hook collapse failed: $it") }
    }

    /** 反向回拖进行中（含刚结束的 600ms 时间窗，覆盖延迟执行的收缩回调）。 */
    private fun isReversalActive(): Boolean =
        System.currentTimeMillis() - lastReversalAt < 600L

    /** 反向回拖上下文：行上向上拖且该行自身处于展开态/本次手势已动作。 */
    private fun isReversalContext(ev: MotionEvent): Boolean {
        if (ev.actionMasked != MotionEvent.ACTION_MOVE) return false
        val row = activeRowRef?.get() ?: return false
        val dy = ev.rawY - startY
        val dx = abs(ev.rawX - startX)
        return dy < -dpToPx(2f) && -dy > dx &&
            (expandedFired || collapsedFired || rowCollapsible(row))
    }

    private fun maybeBlockPanelSteal(param: XC_MethodHook.MethodHookParam) {
        if (param.result as? Boolean != true) return
        val ev = param.args[0] as? MotionEvent ?: return
        if (isReversalContext(ev)) {
            param.result = false
            if (panelBlockLogCount < 8) {
                panelBlockLogCount++
                logD("panel-steal blocked (intercept)")
            }
        }
    }

    // ---------- 1. 手势跟踪 + 触发 ----------

    private fun hookTracking(lpparam: XC_LoadPackage.LoadPackageParam) {
        runCatching {
            val rowClazz = XposedHelpers.findClass(
                "com.android.systemui.statusbar.notification.row.ExpandableNotificationRow",
                lpparam.classLoader)
            XposedHelpers.findAndHookMethod(
                ViewGroup::class.java.name, lpparam.classLoader,
                "dispatchTouchEvent", MotionEvent::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val vg = param.thisObject as? ViewGroup ?: return
                        runCatching { track(param, vg, rowClazz) }.onFailure { log("track error: $it") }
                    }
                })
            log("hooked ViewGroup.dispatchTouchEvent (tracking)")
        }.onFailure { log("hook tracking failed: $it") }
    }

    private fun track(param: XC_MethodHook.MethodHookParam, vg: ViewGroup, rowClazz: Class<*>) {
        if (!Config.gestureEnabled) return
        val event = param.args[0] as? MotionEvent ?: return
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                expandedFired = false
                collapsedFired = false
                val row = findRowUnder(vg, event.rawX, event.rawY, rowClazz)
                if (row != null) {
                    activeRowRef = WeakReference(row)
                    startY = event.rawY
                    startX = event.rawX
                    if (trackLogCount < 20) {
                        trackLogCount++
                        logD("tracking: row pkg=${packageOf(row)} at (${event.rawX}, ${event.rawY})")
                    }
                } else if (activeRowRef != null) {
                    // 未命中的层（DOWN 会经过多个 ViewGroup）：仅在没有任何层命中时清空。
                    // 由于 NSSL 等匹配层比上层容器更深、处理更靠后，这里先清、命中的层随后覆盖即可；
                    // 由此避免用上一次手势的残留行触发（串台）。
                    activeRowRef = null
                }
            }
            MotionEvent.ACTION_MOVE -> {
                val row = activeRowRef?.get() ?: return
                val dy = event.rawY - startY
                val dx = abs(event.rawX - startX)
                lastDy = dy
                if (!expandedFired) {
                    if (dy > dpToPx(16f) && dy > dx) {
                        expandedFired = true
                        logHeights(row)
                        triggerExpansion(row)
                    } else if (dy < -dpToPx(16f) && -dy > dx && rowCollapsible(row)) {
                        collapsedFired = true
                        expandedFired = true
                        triggerCollapse(row)
                    }
                } else if (!collapsedFired && dy < -dpToPx(16f) && -dy > dx && rowCollapsible(row)) {
                    // 本次手势已动作（如下滑展开未松手）→ 反向回拖即收回；每次手势仅收回一次
                    collapsedFired = true
                    if (triggerCollapse(row)) logD("reversed collapse during gesture")
                }
                if (dy < -dpToPx(2f)) lastReversalAt = System.currentTimeMillis()
            }
            MotionEvent.ACTION_UP -> {
                activeRowRef = null
                expandedFired = false
                collapsedFired = false
            }
            MotionEvent.ACTION_CANCEL -> {
                // 不清空跟踪（顶部场景面板接管后事件流仍继续）；跟手会话也继续，
                // 由最终的 ACTION_UP 统一收尾（未收到 UP 时由下次 DOWN 的体检兜底清理）。
            }
        }
    }

    /** 只在通知列表所在的窗口层级打诊断，避免灵动岛等无关窗口刷屏。 */
    private fun isTopLevelTarget(vg: ViewGroup): Boolean =
        nsslClazz?.let { nsslClazz!!.isInstance(vg.parent) } ?: false

    private fun dumpViewChain(vg: ViewGroup, x: Float, y: Float) {
        runCatching {
            // 从 vg 起沿命中点找到最深视图，再向上打 5 层类名
            var v: View = vg
            var depth = 0
            while (v is ViewGroup && depth < 12) {
                var next: View? = null
                val loc = IntArray(2)
                for (i in 0 until v.childCount) {
                    val c = v.getChildAt(i)
                    if (c.visibility != View.VISIBLE) continue
                    c.getLocationOnScreen(loc)
                    if (x >= loc[0] && x <= loc[0] + c.width &&
                        y >= loc[1] && y <= loc[1] + c.height
                    ) { next = c; break }
                }
                if (next == null) break
                v = next
                depth++
            }
            val sb = StringBuilder("  chain: ")
            var p: View? = v
            var up = 0
            while (p != null && up < 5) {
                sb.append(p.javaClass.name).append(" < ")
                p = (p.parent as? View)
                up++
            }
            logD(sb.toString())
        }
    }

    /** 收回只针对手指下「自身处于展开态」的那一条；其余情况交还系统（滚动/退出）。 */
    private fun triggerCollapse(row0: View): Boolean {
        return runCatching {
            if (!rowCollapsible(row0)) return false
            val callback = expandCallback(row0) ?: return false
            XposedHelpers.callMethod(callback, "setUserExpandedChild", row0, false)
            // MIUI 的收回路径缺少高度通知（其展开路径在 setUserExpanded 内有补调），
            // 不补的话视觉上不会收：对非分组行补一次 notifyHeightChanged
            val isSummary = fieldBoolean(row0, "mIsSummaryWithChildren") == true
            if (!isSummary) {
                runCatching { XposedHelpers.callMethod(row0, "notifyHeightChanged", true) }
                    .onFailure { log("notifyHeightChanged failed: $it") }
            }
            lastActionAt = System.currentTimeMillis()
            logDiag("collapse(self): pkg=${packageOf(row0)} child=${callBoolean(row0, "isChildInGroup")}")
            true
        }.getOrDefault(false)
    }

    /** 该行自身是否处于可收回的展开态（视觉高度或标志位）。 */
    private fun rowCollapsible(row: View): Boolean =
        visuallyExpanded(row) || fieldBoolean(row, "mUserExpanded") == true

    /** 视觉上处于展开态：实际高度明显大于收起高度。 */
    private fun visuallyExpanded(row: View): Boolean {
        val actual = callInt(row, "getActualHeight") ?: return false
        val collapsed = callInt(row, "getCollapsedHeight") ?: return false
        return actual > collapsed + 8
    }

    /** 诊断：打印行的关键高度（判断原生跟手是否有增长空间）。 */
    private fun logHeights(row: View) {
        logDiag("heights: actual=${callInt(row, "getActualHeight")} " +
            "max=${callInt(row, "getMaxContentHeight")} " +
            "collapsed=${callInt(row, "getCollapsedHeight")} " +
            "intrinsic=${callInt(row, "getIntrinsicHeight")}")
    }

    private fun callInt(o: Any, method: String): Int? =
        runCatching { XposedHelpers.callMethod(o, method) as? Int }.getOrNull()

    /** 官方展开动作：ExpandHelper 松手提交时调用的同一回调（v1.6 验证可展开）。 */
    private fun triggerExpansion(row0: View) {
        runCatching {
            // 组内子行：分组未展开 → 展开父行（露出子条）；分组已展开 → 展开子条自身（看完整内容）
            var row = row0
            val isChild = callBoolean(row0, "isChildInGroup") == true
            var groupExpanded: Boolean? = null
            if (isChild) {
                val parent = runCatching {
                    XposedHelpers.callMethod(row0, "getNotificationParent")
                }.getOrNull() as? View
                if (parent != null) {
                    groupExpanded = callBoolean(parent, "isGroupExpanded\$1")
                    row = if (groupExpanded == true) row0 else parent
                }
            }
            val expandableBefore = fieldBoolean(row, "mExpandable")
            val userExpandedBefore = fieldBoolean(row, "mUserExpanded")
            val callback = expandCallback(row0)
            if (callback == null) {
                log("expansion trigger: callback unreachable")
                return
            }
            XposedHelpers.callMethod(callback, "setUserExpandedChild", row, true)
            lastActionAt = System.currentTimeMillis()
            val userExpandedAfter = fieldBoolean(row, "mUserExpanded")
            logDiag("expanded: pkg=${packageOf(row)} child=$isChild groupExpanded=$groupExpanded " +
                "expandable=$expandableBefore mUserExpanded $userExpandedBefore -> $userExpandedAfter")
        }.onFailure { log("triggerExpansion failed: $it") }
    }

    /** 行或其分组是否处于展开态。 */
    private fun isExpandedState(row: View): Boolean {
        if (fieldBoolean(row, "mUserExpanded") == true) return true
        if (fieldBoolean(row, "mIsSystemExpanded") == true) return true
        if (callBoolean(row, "isGroupExpanded\$1") == true) return true
        if (callBoolean(row, "isChildInGroup") == true) {
            val parent = runCatching {
                XposedHelpers.callMethod(row, "getNotificationParent")
            }.getOrNull() as? View
            if (parent != null) {
                if (fieldBoolean(parent, "mUserExpanded") == true) return true
                if (callBoolean(parent, "isGroupExpanded\$1") == true) return true
            }
        }
        return false
    }

    private fun expandCallback(row0: View): Any? =
        helperRef?.get()?.let { field(it, "mCallback") }
            ?: nsslClazz?.let { clazz ->
                val nssl = findAncestor(row0, clazz)
                nssl?.let { runCatching { XposedHelpers.callMethod(it, "getExpandHelperCallback") }.getOrNull() }
            }

    private fun callBoolean(o: Any, method: String): Boolean? =
        runCatching { XposedHelpers.callMethod(o, method) as? Boolean }.getOrNull()

    private fun findRowUnder(vg: ViewGroup, x: Float, y: Float, rowClazz: Class<*>): View? {
        val loc = IntArray(2)
        // 快路径：直接子视图 bounds 命中。
        // 从后往前遍历（最上层 z 序优先），与系统自身 dispatch 的命中顺序一致——
        // 列表底部行可能处于堆叠/裁切状态、边界相互覆盖，正向遍历会选错行。
        for (i in vg.childCount - 1 downTo 0) {
            val child = vg.getChildAt(i)
            if (!rowClazz.isInstance(child)) continue
            if (child.visibility != View.VISIBLE) continue
            child.getLocationOnScreen(loc)
            if (x >= loc[0] && x <= loc[0] + child.width &&
                y >= loc[1] && y <= loc[1] + child.height
            ) {
                // 空白区（手指下最深视图是卡片背景）→ 视为通知栏背景手势，不接管
                if (isBlankArea(child, x, y)) continue
                return child
            }
        }
        // 兜底路径：从 vg 沿命中点下钻到最深视图，再向上找最近的行祖先
        runCatching {
            var v: View = vg
            var depth = 0
            while (v is ViewGroup && depth < 12) {
                var next: View? = null
                for (i in v.childCount - 1 downTo 0) {
                    val c = v.getChildAt(i)
                    if (c.visibility != View.VISIBLE) continue
                    c.getLocationOnScreen(loc)
                    if (x >= loc[0] && x <= loc[0] + c.width &&
                        y >= loc[1] && y <= loc[1] + c.height
                    ) { next = c; break }
                }
                if (next == null) break
                v = next
                depth++
            }
            if (v.javaClass.name.contains("NotificationBackgroundView")) return null
            var p: View? = v
            while (p != null) {
                if (rowClazz.isInstance(p)) return p
                p = p.parent as? View
            }
        }
        return null
    }

    /** 手指下的最深视图是否为卡片背景层（即通知卡片的空白区域）。 */
    private fun isBlankArea(row: View, x: Float, y: Float): Boolean {
        return runCatching {
            var v: View = row
            var depth = 0
            val loc = IntArray(2)
            while (v is ViewGroup && depth < 12) {
                var next: View? = null
                for (i in v.childCount - 1 downTo 0) {
                    val c = v.getChildAt(i)
                    if (c.visibility != View.VISIBLE) continue
                    c.getLocationOnScreen(loc)
                    if (x >= loc[0] && x <= loc[0] + c.width &&
                        y >= loc[1] && y <= loc[1] + c.height
                    ) { next = c; break }
                }
                if (next == null) break
                v = next
                depth++
            }
            v.javaClass.name.contains("NotificationBackgroundView")
        }.getOrDefault(false)
    }

    /** 提取行对应的通知包名（排障用）。 */
    private fun packageOf(row: View): String? = runCatching {
        val entry = XposedHelpers.callMethod(row, "getEntry")
        val sbn = XposedHelpers.getObjectField(entry, "mSbn")
        XposedHelpers.callMethod(sbn, "getPackageName") as? String
    }.getOrNull()

    private fun findAncestor(v: View, clazz: Class<*>): View? {
        var p = v.parent
        while (p is ViewGroup) {
            if (clazz.isInstance(p)) return p
            p = p.parent
        }
        return null
    }

    // ---------- 2. ExpandHelper：按下体检（清理残留 + 恢复 pull 监听） ----------

    private fun hookExpandHelper(lpparam: XC_LoadPackage.LoadPackageParam) {
        runCatching {
            val helperCls = XposedHelpers.findClass(EXPAND_HELPER_CLASS, lpparam.classLoader)
            XposedHelpers.findAndHookMethod(helperCls, "onInterceptTouchEvent", MotionEvent::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (!Config.gestureEnabled) return
                        val ev = param.args[0] as? MotionEvent ?: return
                        if (ev.actionMasked != MotionEvent.ACTION_DOWN) return
                        val helper = param.thisObject
                        helperRef = WeakReference(helper)
                        runCatching {
                            clearStaleExpansion(helper)
                            XposedHelpers.setObjectField(helper, "mWatchingForPull", true)
                            if (XposedHelpers.getObjectField(helper, "mResizedView") == null) {
                                val v = XposedHelpers.callMethod(helper, "findView\$1", ev.x, ev.y)
                                if (v != null) XposedHelpers.setObjectField(helper, "mResizedView", v)
                            }
                        }.onFailure { log("down fix failed: $it") }
                    }
                })
            log("hooked ExpandHelper.onInterceptTouchEvent")
        }.onFailure { log("hook ExpandHelper failed: $it") }
    }

    /** 清理上一手势残留的展开状态，防止滚动被 mExpandingNotification 永久压制。 */
    private fun clearStaleExpansion(helper: Any) {
        if (fieldBoolean(helper, "mExpanding") != true) return
        val view = field(helper, "mResizedView")
        val callback = field(helper, "mCallback")
        runCatching { XposedHelpers.callMethod(helper, "finishExpanding", false, 0f) }
        XposedHelpers.setObjectField(helper, "mExpanding", false)
        XposedHelpers.setObjectField(helper, "mExpansionStyle", 0)
        XposedHelpers.setObjectField(helper, "mResizedView", null)
        if (callback != null) {
            runCatching { XposedHelpers.callMethod(callback, "expansionStateChanged", false) }
            if (view != null) {
                runCatching { XposedHelpers.callMethod(callback, "setUserLockedChild", view, false) }
            }
        }
        log("stale expansion state cleared")
    }

    // ---------- 3. 反抢断：向下拖不许被滚动抢走 ----------

    private fun hookTouchHandler(lpparam: XC_LoadPackage.LoadPackageParam) {
        runCatching {
            val thCls = XposedHelpers.findClass(TOUCH_HANDLER_CLASS, lpparam.classLoader)
            XposedHelpers.findAndHookMethod(thCls, "onInterceptTouchEvent", MotionEvent::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (!Config.gestureEnabled) return
                        runCatching { maybeBlockScrollSteal(param) }.onFailure { log("anti-steal error: $it") }
                    }
                })
            log("hooked TouchHandler.onInterceptTouchEvent (anti scroll-steal)")
        }.onFailure { log("hook TouchHandler failed: $it") }
    }

    private fun maybeBlockScrollSteal(param: XC_MethodHook.MethodHookParam) {
        if (param.result as? Boolean != true) return
        val ev = param.args[0] as? MotionEvent ?: return
        if (ev.actionMasked != MotionEvent.ACTION_MOVE) return
        val row = activeRowRef?.get() ?: return
        val helper = helperRef?.get()
        // 原生展开若已接管则放行（防御性保留）
        if (helper != null && fieldBoolean(helper, "mExpanding") == true) return
        val dy = ev.rawY - startY
        val dx = abs(ev.rawX - startX)
        if (dy > dpToPx(2f) && dy > dx) {
            // 向下拖：不许滚动抢走，等触发展开
            param.result = false
            if (blockLogCount < 5) {
                blockLogCount++
                logD("scroll-steal blocked (down dy=$dy)")
            }
            return
        }
        if (dy < -dpToPx(2f) && -dy > dx) {
            // 向上拖且手指下这条自身处于展开态/本次手势已动作（走收回）→ 拦截，避免收回的同时滚动列表。
            // 其余情况（未展开、组内未展开的子条等）放行 → 正常滚动/退出通知中心。
            if (expandedFired || collapsedFired || rowCollapsible(row)) {
                param.result = false
                if (blockLogCount < 10) {
                    blockLogCount++
                    logD("scroll-steal blocked (up dy=$dy, expanding)")
                }
            }
        }
    }

    // ---------- 诊断 ----------

    private var blockLogCount = 0
    private var trackLogCount = 0
    private var panelBlockLogCount = 0

    private fun hookDiagnostics(lpparam: XC_LoadPackage.LoadPackageParam) {
        runCatching {
            val helperCls = XposedHelpers.findClass(EXPAND_HELPER_CLASS, lpparam.classLoader)
            XposedHelpers.findAndHookMethod(helperCls, "onInterceptTouchEvent", MotionEvent::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val ev = param.args[0] as? MotionEvent ?: return
                        logD("helper.intercept a=${ev.actionMasked} -> ${param.result} " +
                            "expanding=${field(param.thisObject, "mExpanding")} " +
                            "style=${field(param.thisObject, "mExpansionStyle")}")
                    }
                })
            log("diagnostics hooked")
        }.onFailure { log("diagnostics failed: $it") }
    }

    // ---------- 工具 ----------

    private fun field(o: Any, name: String): Any? =
        runCatching { XposedHelpers.getObjectField(o, name) }.getOrNull()

    private fun fieldBoolean(o: Any, name: String): Boolean? =
        runCatching { XposedHelpers.getObjectField(o, name) as? Boolean }.getOrNull()

    private fun dpToPx(dp: Float): Float =
        android.content.res.Resources.getSystem().displayMetrics.density * dp

    fun log(msg: String) {
        XposedBridge.log("[$TAG] $msg")
    }

    /** 诊断日志（默认关闭，设置里开启「原生手势日志」后生效）。 */
    private fun logD(msg: String) {
        if (!Config.logNativeGesture) return
        XposedBridge.log("[$TAG][diag] $msg")
    }

    /** 低频动作/状态诊断（同样受诊断开关控制）。 */
    private fun logDiag(msg: String) {
        if (!Config.logNativeGesture) return
        XposedBridge.log("[$TAG][diag] $msg")
    }
}
