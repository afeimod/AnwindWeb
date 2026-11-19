# LinBrowser

LinBox 浏览器独立版 —— 从 [LinBox](https://github.com/afeimod/linbox)（Windows 容器桌面）抽取的**独立安卓浏览器应用**，浏览器功能 100% 保留，虚拟手柄 / 悬浮球 / 虚拟键盘 / 触控板整套输入系统随行，并修复了下载相关的一批问题。

> **v1.1.0（2025-11 更新）**：① 主页重做为正常手机浏览器主页（Logo + 大搜索栏 + 4 列彩色宫格，12 站快捷）；② 默认竖屏，悬浮球菜单新增「屏幕方向」芯片（竖屏/横屏/自动循环切换并持久化）；③ 修复悬浮球菜单、手柄设置窗、按键映射选择器、.vgp 配置对话框在横屏下显示不完整的问题；④ 「主页」按钮回浏览器主页（首页宫格）而非 bing.com；⑤ 新增 GitHub Actions 手动构建工作流。

## 功能清单（与 LinBox 浏览器对齐）

### 浏览器核心
- **多标签**：每标签独占常驻 WebView，切换不重建；会话跨退出存活
- 地址栏（自动补全 https / 非 URL 转 Bing 搜索）、前进 / 后退 / 刷新 / 主页
- **书签**（Room 持久化，含标题与 favicon）、**历史记录**
- **主页 = 手机浏览器首页**（v1.1.0）：Logo + 大搜索栏 + 4 列彩色快捷宫格（必应/百度/B站/GitHub/知乎/微博/淘宝/京东/抖音/豆瓣/Wikipedia/YouTube），回主页不再跳 bing.com
- **屏幕方向设置**（v1.1.0）：默认竖屏；悬浮球菜单「屏幕方向」芯片可切竖屏/横屏/自动
- `window.open` / `target=_blank` → 真实新标签
- **F11 式真全屏** + HTML5 视频全屏（DecorView 覆盖层）
- **UA 桌面 / 手机模式切换**（即时重载生效）
- **3D 视角旋转**（模拟 Pointer Lock + mousemove 注入，网页游戏转视角，灵敏度可调）
- **双指缩放 30%–100%**（改写 viewport meta，等效桌面 Ctrl+滚轮）
- 左缘悬浮滚动条（悬停 / 拖动 / 轻点跳转）
- **Flash / Ruffle 完整兼容**（.swf 满屏播放器 + 4399 等站点自动加载 + 中文黑体字形 + CDN 链式后备）
- 内网 https→http 自动回退、渲染进程崩溃自愈、WebGL 坏上下文守卫
- 本地 HTML（content:// SAF）打开

### 下载（本次修复 + 增强）
- **下载前重命名确认对话框**：文件名可编辑、显示大小 / 类型 / 来源 / 保存位置，确认才开始下载，取消零残留
- http/https → 系统 DownloadManager（登录态 Cookie / Referer / UA 转发，系统通知可见进度）
- blob: → 页内 XHR 分块桥读取 → 重命名确认 → 落盘
- data: → 直接解码 → 重命名确认 → 落盘
- **落盘三级回退**：公共 Downloads 直写 → MediaStore.Downloads（Android 10+ 免权限）→ 应用专属目录兜底
- 重名自动 `(n)` 后缀；非法文件名字符清洗；MIME 补扩展名
- **修复文件上传断链**（input[type=file] 选完文件页面无响应的问题）：FileChooserBus 统一登记 / 回传

### 输入系统（完整保留）
- **虚拟手柄**：玻璃摇杆 8 向、十字键、径向按钮、编辑布局、位置尺寸调节、按键映射（全键盘 + 鼠标）、ARROWS/WASD 方向模式、经典 / 简单预设、.vgp 配置保存读取
- **悬浮球**：可拖动常驻炫彩球，展开菜单 = 手柄控制 + **浏览器快捷组**（后退 / 前进 / 刷新 / 主页 / 收藏 / 书签 / 历史 / UA / 全屏 / 3D 视角 / 屏幕方向 / 关闭浏览器）；横屏矮屏自动紧凑排版，菜单不出屏（v1.1.0）
- **虚拟键盘**：QWERTY + 功能行 + 小键盘，可拖动 / 捏合缩放 / 多主题 / 剪贴板条 / 触觉反馈
- **触控板模式**：单指移指针、轻点单击、长按拖拽、双指滚动、双指轻点右键
- **虚拟鼠标指针**：速度 / 主题 / 大小可调，涟漪反馈

### 主题
- Win 95 / XP / 7 / 10 / 11 五套主题 + 深浅模式 + 强调色 + 字体颜色 / 样式 / 缩放 + 全局 UI 缩放

## 相对 LinBox 的改动

| 改动 | 说明 |
| --- | --- |
| 移除容器栈 | Wine / Termux / X11 / DAC / 容器管理 / 终端 / 文件管理器全部移除，单模块 `:app`，无 NDK |
| 窗口系统精简 | `core/window` 换成 ~100 行契约实现（AppDef / WindowState / WindowContentScope / 空 WindowManager），浏览器代码零改动复用 |
| 手柄 X11 路由移除 | GamepadController 的 6 处 X11InputHub/SmartTouchBridge 直注重写为浏览器 WebView 单路径 |
| 悬浮球菜单精简 | 移除 X11 快捷组与终端快捷组，保留主功能组 + 浏览器快捷组；「关闭浏览器」改为收起应用（会话存活） |
| .vgp 配置目录 | 公共 Download/linbox/gamepad → 应用专属目录（免「所有文件访问」权限） |
| 下载重做 | 见上「下载」节 |
| applicationId | `com.linbox.browser`（可与 LinBox 共存）；namespace 仍为 `com.linbox`，数据库 / DataStore 同 schema，书签历史结构与 LinBox 一致 |

## 构建

```bash
# 需要 JDK 17+ 与 Android SDK 34
./gradlew assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

`minSdk 24 / targetSdk 28 / compileSdk 34`。targetSdk 保持 28 与 LinBox 一致（legacy 存储行为，公共 Downloads 直写可用）；未授权时下载自动回退 MediaStore，不影响功能。

### GitHub Actions 手动构建（v1.1.0）

仓库内置 `.github/workflows/build.yml`：推到 GitHub 后，在仓库 **Actions → Build LinBrowser APK → Run workflow** 手动触发，可选 debug / release / both，产物以 Artifact 形式上传下载。

## 目录结构

```
app/src/main/java/com/linbox/
├── LinBoxApp.kt              # 精简 Application（Room + DataStore + ThemeManager）
├── MainActivity.kt           # 主界面（沉浸式 / 权限 / 主题链 / 触控板仲裁 / 上传回传）
├── shellui/BrowserShell.kt   # 浏览器壳（页面 + 输入覆盖层 + 下载弹窗）
├── apps/browser/             # 浏览器本体（7 文件 + 下载中心 DownloadCenter.kt）
├── core/input/               # 虚拟键盘 / 鼠标 / 触控板
├── core/input/gamepad/       # 虚拟手柄 / 悬浮球 / 设置窗 / .vgp 配置
├── core/theme/               # Win 主题系统
├── core/window/              # 精简窗口契约 + 点击置前钩子
├── data/                     # Room（书签/历史）+ DataStore 设置
└── util/                     # 沉浸式 / 存储权限引导
```

## 致谢

上游项目：[afeimod/linbox](https://github.com/afeimod/linbox)。浏览器内核基于 Android WebView；Flash 兼容由 [Ruffle](https://ruffle.rs)（assets/ruffle，本地虚拟域供给）提供。
