package com.linbox

import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.MotionEvent
import android.view.WindowManager as SysWindowManager
import com.linbox.core.input.TrackpadRouter
import com.linbox.core.theme.LocalBaseDensity
import com.linbox.core.theme.ThemeOverlay
import com.linbox.core.theme.Themes
import com.linbox.core.theme.WinThemeScope
import com.linbox.core.window.WindowFocusHook
import com.linbox.data.prefs.SettingsStore
import com.linbox.shellui.BrowserShell
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Density
import com.linbox.util.ImmersiveMode
import com.linbox.util.StorageAccess

/**
 * LinBrowser 独立浏览器版主界面（从 LinBox MainActivity 精简）。
 *
 * 保留：
 * - 沉浸式全屏（状态栏/导航栏隐藏 + 刘海屏占用）
 * - 存储权限首启申请（下载文件落盘公共 Downloads/）
 * - Win 主题链（基础主题 → 深浅 → 强调色 → 字体色）+ 全局 UI 缩放
 * - View 层触控板手势仲裁入口（dispatchTouchEvent → TrackpadRouter）
 * - 窗口点击置前钩子（WindowFocusHook）
 *
 * 移除：Termux/X11/容器相关全部逻辑（终端 bootstrap、X11 receiver、
 * 录屏、容器快捷方式深链）。
 */
class MainActivity : ComponentActivity() {

    // v1.2.0（用户需求 5）：主页自定义背景选择器（图片/视频）。
    // 活动级注册（onCreate 前段），主页「背景」按钮经 HomeBgManager 调用。
    private val homeBgPicker = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { com.linbox.apps.browser.HomeBgManager.onPicked(this, it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 选择器注入总线（主页按钮 → HomeBgManager.pick）
        com.linbox.apps.browser.HomeBgManager.pickerLauncher = homeBgPicker

        // 全面屏沉浸式：内容绘制到状态栏/导航栏下方，并隐藏系统栏
        ImmersiveMode.applyTo(window)
        // 默认占用刘海屏：内容延伸绘制到刘海/挖孔区域（API 28+）
        applyCutoutMode(true)

        val app = LinBoxApp.get(this)
        setContent {
            // ===== 首次启动存储权限申请（下载文件需要） =====
            var showAllFilesDialog by remember { mutableStateOf(false) }
            val storageLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestMultiplePermissions()
            ) { grants ->
                val ok = grants[android.Manifest.permission.READ_EXTERNAL_STORAGE] == true ||
                    grants[android.Manifest.permission.WRITE_EXTERNAL_STORAGE] == true
                if (!ok && Build.VERSION.SDK_INT >= 30) showAllFilesDialog = true
            }
            LaunchedEffect(Unit) {
                if (!StorageAccess.hasAccess(this@MainActivity)) {
                    val need = buildList {
                        for (p in StorageAccess.legacyPermissions()) {
                            if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) add(p)
                        }
                    }
                    if (need.isNotEmpty()) storageLauncher.launch(need.toTypedArray())
                }
            }
            if (showAllFilesDialog) {
                AlertDialog(
                    onDismissRequest = { showAllFilesDialog = false },
                    title = { Text("需要存储权限") },
                    text = {
                        Text(
                            "浏览器需要存储权限才能把下载的文件保存到公共 Download 目录。" +
                                "请授予「所有文件访问」权限后重试（未授权时文件将保存到应用目录）。"
                        )
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            showAllFilesDialog = false
                            try {
                                startActivity(StorageAccess.manageIntent(this@MainActivity))
                            } catch (_: Exception) {
                                try {
                                    startActivity(StorageAccess.manageFallbackIntent())
                                } catch (_: Exception) {
                                }
                            }
                        }) { Text("去授权") }
                    },
                    dismissButton = {
                        TextButton(onClick = { showAllFilesDialog = false }) { Text("暂不") }
                    }
                )
            }

            val baseTheme by app.themeManager.activeTheme.collectAsState(
                initial = Themes.Win11
            )
            val settingsStore = remember { app.settingsStore }

            // 分享进来的链接/文本（其他应用 ACTION_SEND）作为启动 URL
            val sharedText = remember {
                extractSharedText(intent)
            }

            // 全局 UI 缩放（乘到密度上，同时缩放所有 dp/sp）
            val uiScale by settingsStore.uiScale.collectAsState(initial = 1f)
            // v1.1.0：默认竖屏（与 SettingsStore 缺省一致，避免首帧方向闪变）
            val orientation by settingsStore.displayOrientation.collectAsState(initial = "portrait")
            val useCutout by settingsStore.useCutout.collectAsState(initial = true)

            // ===== 个性化：颜色 / 字体 =====
            val colorMode by settingsStore.appColorMode.collectAsState(initial = "auto")
            val accent by settingsStore.appAccent.collectAsState(initial = "default")
            val fontScale by settingsStore.fontScale.collectAsState(initial = 1f)
            val fontColor by settingsStore.fontColor.collectAsState(initial = "auto")
            val fontStyle by settingsStore.fontStyle.collectAsState(initial = "default")

            // 主题链：基础主题 → 深浅模式覆盖 → 强调色覆盖 → 字体颜色覆盖
            val theme = remember(baseTheme, colorMode, accent, fontColor) {
                baseTheme
                    .let { ThemeOverlay.apply(it, colorMode) }
                    .let { ThemeOverlay.applyAccent(it, accent) }
                    .let { ThemeOverlay.applyFontColor(it, fontColor) }
            }

            // 刘海屏模式切换
            LaunchedEffect(useCutout) { applyCutoutMode(useCutout) }

            // 显示方向设置
            LaunchedEffect(orientation) {
                requestedOrientation = when (orientation) {
                    "portrait" -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                    "landscape" -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                    else -> ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
                }
            }

            // 通过覆盖 LocalDensity 实现全局 UI 缩放 + 字体大小缩放
            val baseDensity = LocalDensity.current
            val baseTextStyle = androidx.compose.material3.LocalTextStyle.current
            CompositionLocalProvider(
                LocalDensity provides Density(
                    density = baseDensity.density * uiScale * SettingsStore.UI_SCALE_BASE,
                    fontScale = baseDensity.fontScale * fontScale
                ),
                // 基础密度下发 —— 悬浮球 / 手柄元素等自带尺寸语义的层
                // 经 KeepBaseDensity 读取，不随全局 uiScale 缩放
                LocalBaseDensity provides baseDensity,
                androidx.compose.material3.LocalTextStyle provides baseTextStyle.copy(
                    fontFamily = when (fontStyle) {
                        "serif" -> FontFamily.Serif
                        "mono" -> FontFamily.Monospace
                        else -> baseTextStyle.fontFamily
                    }
                )
            ) {
                WinThemeScope(theme = theme) {
                    BrowserShell(sharedText = sharedText)
                }
            }
        }
    }

    /**
     * 触控板模式事件仲裁入口（真实手指事件先经 TrackpadRouter：
     * 命中则消费，注入流（id ≥ 99）与虚拟键盘/手柄直通区放行）。
     */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        WindowFocusHook.onMotionEvent(ev)
        if (TrackpadRouter.onMotionEvent(this, ev)) return true
        return super.dispatchTouchEvent(ev)
    }

    /**
     * 沉浸式防失效：MIUI / 部分系统在窗口焦点变化后会重新显示系统栏，
     * 每次重新获得焦点时重新断言隐藏。
     */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            ImmersiveMode.applyTo(window)
        }
    }

    /**
     * 网页文件选择器（input[type=file] 上传）结果回传：
     * TabChromeClient.onShowFileChooser 经 FileChooserBus 登记
     * filePathCallback，这里把用户选择结果回传给页面
     * （修复原版"选完文件页面无响应"的断链 bug）。
     */
    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (com.linbox.apps.browser.FileChooserBus.onActivityResult(
                this, requestCode, resultCode, data
            )
        ) return
        super.onActivityResult(requestCode, resultCode, data)
    }

    /** 刘海屏绘制模式（API 28+）。 */
    private fun applyCutoutMode(useCutout: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val mode = if (useCutout) {
                SysWindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            } else {
                SysWindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT
            }
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = mode
            }
        }
    }

    /** ACTION_SEND 分享进来的文本：提取其中的 URL（无 URL 则当搜索词）。 */
    private fun extractSharedText(intent: Intent?): String? {
        if (intent?.action != Intent.ACTION_SEND) return null
        val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return null
        return text.trim().ifBlank { null }
    }
}
