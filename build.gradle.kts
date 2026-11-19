// LinBrowser —— 独立浏览器版（从 LinBox 抽取，去除 Wine/Termux/X11 容器栈）
plugins {
    id("com.android.application") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "1.9.25" apply false
    id("com.google.devtools.ksp") version "1.9.25-1.0.20" apply false
}
