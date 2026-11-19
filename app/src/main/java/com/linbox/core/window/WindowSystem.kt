package com.linbox.core.window

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.util.UUID

/**
 * LinBrowser 独立版窗口契约（从 LinBox core.window 精简而来）。
 *
 * 浏览器在本项目中以全屏页形式承载（与 LinBox v6.0+ 的 ShellPages
 * 伪窗口壳同一模式）：内容组件只依赖 [WindowContentScope] 的
 * launchArgs / onClose / isTrueFullscreen 契约。原 WindowManager 的
 * 多窗口几何/层级/任务栏管理已随容器桌面移除，这里保留同名 API 的
 * 最小实现，浏览器代码零改动复用：
 *
 * - [WindowManager.get] 恒为单例，windows 列表恒空（无浮动窗口）；
 * - toggleTrueFullscreen 退化为 no-op（浏览器全屏翻转由
 *   WindowState.isTrueFullscreen 直写 + 重组驱动，与 LinBox v6.0
 *   安卓全屏页路径完全一致）。
 */

/** 启动模式（保留枚举兼容 AppDef 契约）。 */
enum class LaunchMode { FULLSCREEN, FLOATING }

/**
 * 应用定义：浏览器自描述（原 LinBox 桌面/任务栏从这里读取图标）。
 * 独立版仅保留浏览器自身注册，字段契约与 LinBox 一致。
 */
data class AppDef(
    val id: String,
    val displayName: String,
    val iconAsset: String,
    val launchMode: LaunchMode,
    val defaultWidth: Dp = 720.dp,
    val defaultHeight: Dp = 520.dp,
    val pinnedToTaskbar: Boolean = false,
    val pinnedToDesktop: Boolean = true,
    val content: @Composable (WindowContentScope) -> Unit
)

/**
 * 窗口状态：浏览器全屏页的伪窗口状态。
 * launchArgs（启动 URL / 类型）、isTrueFullscreen（F11 式真全屏）、
 * onClose（页面退出时销毁标签会话）三个契约为浏览器实际消费的字段。
 */
data class WindowState(
    val id: String = UUID.randomUUID().toString(),
    val appId: String,
    var title: String,
    val launchMode: LaunchMode,
    var isTrueFullscreen: Boolean = false,
    val launchArgs: Map<String, String> = emptyMap(),
    var onClose: (() -> Unit)? = null,
    // 窗口层级（独立版恒 0：无多窗口；保留契约供 WindowFocusHook 编译）
    val zIndex: Int = 0
) {
    val isVisible: Boolean get() = true
}

/** 窗口内容的作用域，提供窗口状态和工具。 */
class WindowContentScope(
    val windowState: WindowState,
    val onClose: () -> Unit,
    val onTitleChange: (String) -> Unit
)

/** 应用注册表：全局单例（独立版仅浏览器自注册）。 */
object AppRegistry {
    private val apps = mutableMapOf<String, AppDef>()

    fun register(app: AppDef) {
        apps[app.id] = app
    }

    fun get(id: String): AppDef? = apps[id]

    fun all(): List<AppDef> = apps.values.toList()
}

/**
 * 窗口管理器：LinBox 多窗口管理器的独立版最小实现。
 * 独立浏览器无浮动窗口 —— windows 恒空，observe 驱动重组计数，
 * toggleTrueFullscreen 走 WindowState 直写路径（浏览器全屏页模式）。
 */
object WindowManager {
    private val listeners = mutableListOf<() -> Unit>()
    val windows: List<WindowState> get() = emptyList()

    fun get(): WindowManager = this

    fun observe(listener: () -> Unit) {
        listeners.add(listener)
    }

    private fun notifyChanged() {
        listeners.forEach { runCatching(it) }
    }

    /** 窗口置前（独立版无多窗口，no-op；保留契约）。 */
    fun focus(id: String) {
    }

    fun toggleTrueFullscreen(id: String) {
        // 独立版：窗口不在列表（恒空）→ BrowserContent 走
        // isTrueFullscreen 直写 + 手动重组分支，与原安卓全屏页一致
        notifyChanged()
    }

    fun close(id: String) {
        notifyChanged()
    }
}
