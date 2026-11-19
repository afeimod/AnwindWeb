package com.linbox

import android.app.Application
import android.content.Context
import com.linbox.core.theme.ThemeManager
import com.linbox.data.db.AppDatabase
import com.linbox.data.prefs.SettingsStore

/**
 * LinBrowser 独立浏览器版 Application（从 LinBox 精简）。
 *
 * 只保留浏览器所需的三个单例：
 * - [database]：Room（书签 / 历史记录 / 桌面快捷方式三表，与 LinBox
 *   同 schema，未来导入 LinBox 数据库文件可直接识别）；
 * - [settingsStore]：DataStore 偏好（浏览器 UA/3D 视角/主页、虚拟键盘、
 *   虚拟鼠标、触控板、虚拟手柄、主题等全部键）；
 * - [themeManager]：Win 主题（95/XP/7/10/11）持久化。
 *
 * LinBox 原版中的 Termux bootstrap、X11/DAC receiver、容器前缀等
 * 初始化已随容器栈移除。
 */
class LinBoxApp : Application() {

    lateinit var database: AppDatabase
        private set

    lateinit var settingsStore: SettingsStore
        private set

    lateinit var themeManager: ThemeManager
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this

        database = androidx.room.Room.databaseBuilder(
            this, AppDatabase::class.java, AppDatabase.NAME
        )
            .addMigrations(AppDatabase.MIGRATION_1_2)
            .fallbackToDestructiveMigration()
            .build()
        settingsStore = SettingsStore(this)
        themeManager = ThemeManager(this)
    }

    companion object {
        @Volatile
        private var instance: LinBoxApp? = null

        @JvmStatic
        fun get(): LinBoxApp =
            instance ?: throw IllegalStateException("LinBoxApp not initialized")

        @JvmStatic
        fun get(context: Context): LinBoxApp =
            (context.applicationContext as? LinBoxApp) ?: get()
    }
}
