package com.linbox.shellui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import com.linbox.LinBoxApp
import com.linbox.apps.browser.BrowserContent
import com.linbox.apps.browser.DownloadPromptDialog
import com.linbox.core.input.MouseController
import com.linbox.core.input.MouseCursorOverlay
import com.linbox.core.input.TrackpadRouter
import com.linbox.core.input.VirtualKeyboardOverlay
import com.linbox.core.input.gamepad.GamepadController
import com.linbox.core.input.gamepad.GamepadFloatingBall
import com.linbox.core.input.gamepad.GamepadOverlay
import com.linbox.core.input.gamepad.GamepadSettingsWindow
import com.linbox.core.theme.LinBoxMaterialTheme
import com.linbox.core.window.LaunchMode
import com.linbox.core.window.WindowContentScope
import com.linbox.core.window.WindowState
import kotlinx.coroutines.flow.first

/**
 * LinBrowser 独立浏览器壳（从 LinBox LinBoxShell / ShellPages 精简）。
 *
 * 层级（与 LinBox 壳层一致，容器相关层移除）：
 * 1. 浏览器全屏页（BrowserContent 100% 复用，伪窗口壳提供
 *    launchArgs / onClose 契约 —— 与 LinBox v6.0+ 安卓全屏页同一模式）
 * 2. VirtualKeyboardOverlay（虚拟键盘，可拖动）
 * 3. GamepadOverlay + GamepadSettingsWindow + GamepadFloatingBall
 *    （虚拟游戏手柄 + 常驻悬浮球）
 * 4. MouseCursorOverlay（虚拟鼠标指针，最顶层）
 * 5. DownloadPromptDialog（下载重命名确认弹窗，最顶层）
 */
@Composable
fun BrowserShell(sharedText: String? = null) {
    val app = LinBoxApp.get()
    val density = LocalDensity.current

    // ===== 输入设置（触控板模式 / 虚拟鼠标指针 / 右键手势）=====
    val mouseCursorEnabled by app.settingsStore.mouseCursorEnabled.collectAsState(initial = true)
    val mouseControlMode by app.settingsStore.mouseControlMode.collectAsState(initial = "touch")
    val mouseRightClick by app.settingsStore.mouseRightClick.collectAsState(initial = "twofinger")

    // View 层触控板路由器接线：
    // 触控板模式的手势仲裁由 MainActivity.dispatchTouchEvent → TrackpadRouter 完成。
    SideEffect {
        val newEnabled = mouseControlMode == "trackpad"
        if (TrackpadRouter.enabled && !newEnabled) TrackpadRouter.onDisabled()
        TrackpadRouter.enabled = newEnabled
        TrackpadRouter.longPressRightClick = mouseRightClick == "longpress"
        TrackpadRouter.density = density.density
    }

    // 虚拟鼠标指针初始化（触控板模式下指针即鼠标本体，强制初始化）
    SideEffect {
        if (mouseCursorEnabled || mouseControlMode == "trackpad") {
            MouseController.initialize(0f, 0f)
        }
    }

    // ===== 虚拟手柄配置全局一次加载（防覆盖层迟到加载回写旧值）=====
    LaunchedEffect(Unit) {
        val json = app.settingsStore.gamepadConfig.first()
        GamepadController.ensureLoaded(json)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // ===== 1. 浏览器全屏页 =====
        LinBoxMaterialTheme {
            BrowserPage(sharedText)
        }

        // ===== 2. 虚拟键盘层（可拖动全键盘） =====
        VirtualKeyboardOverlay()

        // ===== 3. 虚拟游戏手柄层（悬浮球 + 摇杆/按钮 + 设置窗） =====
        GamepadOverlay()
        GamepadSettingsWindow()
        GamepadFloatingBall()

        // ===== 4. 虚拟鼠标指针层（最顶层） =====
        MouseCursorOverlay()

        // ===== 5. 下载重命名确认弹窗（最顶层） =====
        DownloadPromptDialog()
    }
}

/**
 * 浏览器全屏页：伪窗口壳（launchArgs / onClose 契约）+ BrowserContent。
 * 系统返回 = 收起应用回桌面（标签会话跨进出存活，再进不丢）。
 */
@Composable
private fun BrowserPage(sharedText: String?) {
    val context = LocalContext.current
    val args = remember {
        buildMap {
            if (!sharedText.isNullOrBlank()) put("url", sharedText)
        }
    }
    val host = remember(args) {
        PageHost(args, "browser", "浏览器")
    }

    // 系统返回：网页历史后退 > 视频全屏退出 > 页面级全屏退出 > 本兜底
    //（收起应用回桌面，会话存活 —— 与 LinBox"回主页"语义对齐为最小破坏）
    BackHandler(enabled = true) {
        (context as? android.app.Activity)?.moveTaskToBack(true)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF101418))
    ) {
        BrowserContent(host.scope)
    }
}

/** 深链参数快照 + 伪窗口契约壳（内容组件只依赖 launchArgs 与 onClose）。 */
private class PageHost(
    val args: Map<String, String>,
    val appId: String,
    val title: String
) {
    val state = WindowState(
        appId = appId,
        title = title,
        launchMode = LaunchMode.FLOATING,
        launchArgs = args
    )

    val scope = WindowContentScope(
        windowState = state,
        onClose = { },
        onTitleChange = { }
    )
}
