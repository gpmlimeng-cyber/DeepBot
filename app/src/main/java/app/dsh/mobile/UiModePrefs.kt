package app.dsh.mobile

import android.content.Context
import android.content.SharedPreferences

/**
 * 界面模式（v1.3.0 起，本轮新增）：手机 / 平板 / 电脑。
 *
 * 取代原来的「按横竖屏自动切桌面」——自动切是错的（转个屏就换一整套布局），
 * 但桌面模式本身有用，所以改成**用户显式选择**，默认手机。
 *
 *   手机：手机 UA，`device-width`，useWideViewPort=false
 *   折叠：手机 UA，注入 `width=425`（实测 dumpsys display：外屏 1168×1712 @2.75）
 *   平板：平板 UA，注入 `width=768` 的 viewport
 *   电脑：桌面 UA，注入上游现成的 `width=1280` 视口（DESKTOP_VIEWPORT_JS）
 *
 * 折叠只做**折叠态（内屏）**；展开态要等接 Jetpack WindowManager 的 hinge/FoldingFeature
 * 再判断，现在不猜。
 */
enum class UiMode(val key: String) {
    PHONE("phone"),
    FOLD("fold"),
    TABLET("tablet"),
    DESKTOP("desktop");

    companion object {
        fun fromKey(raw: String?): UiMode = entries.firstOrNull { it.key == raw } ?: PHONE
    }
}

object UiModePrefs {
    private const val FILE = "dsh_ui"
    private const val KEY = "ui_mode"

    fun get(ctx: Context): UiMode =
        UiMode.fromKey(prefs(ctx).getString(KEY, UiMode.PHONE.key))

    fun set(ctx: Context, mode: UiMode) {
        prefs(ctx).edit().putString(KEY, mode.key).apply()
    }

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
}
