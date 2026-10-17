package app.dsh.mobile

import android.content.Context
import android.content.SharedPreferences

/**
 * 状态栏样式（v1.2.122）：透明 / 跟随主题色 / 跟随系统，默认透明。
 *
 * 透明与主题色都走 edge-to-edge（窗口铺到状态栏与导航栏下面），
 * 页面通过壳注入的 CSS 变量自行留白：
 *   --dsh-safe-top    状态栏高度（px）
 *   --dsh-safe-bottom 底部手势条/导航栏高度（px）
 * 跟随系统则保持系统默认布局（窗口不延伸），两个变量注入 0。
 */
enum class StatusBarMode(val key: String) {
    TRANSPARENT("transparent"),
    THEME("theme"),
    SYSTEM("system");

    companion object {
        fun fromKey(key: String?): StatusBarMode = when (key) {
            "theme" -> THEME
            "system" -> SYSTEM
            else -> TRANSPARENT
        }
    }
}

object StatusBarPrefs {
    private const val KEY = "status_bar_mode"

    fun get(ctx: Context): StatusBarMode =
        StatusBarMode.fromKey(prefs(ctx).getString(KEY, null))

    fun set(ctx: Context, mode: StatusBarMode) {
        prefs(ctx).edit().putString(KEY, mode.key).apply()
    }

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(DshApp.PREFS_UI, Context.MODE_PRIVATE)
}
