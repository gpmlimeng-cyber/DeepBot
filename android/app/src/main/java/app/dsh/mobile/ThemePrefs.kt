package app.dsh.mobile

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration

/**
 * 壳的主题偏好（v1.2.121）：跟随系统 / 浅色 / 深色，默认跟随系统。
 *
 * 与 [OrientationPrefs]、[AutoHidePrefs] 共用 `dsh_ui` SharedPreferences。
 * 只管**壳**（原生外壳、设置页、悬浮窗）；网页自身的主题由 DSH Web 前端控制，
 * 两者是两套（第二阶段的「壳跟随网页」不在本次范围）。
 */
enum class ThemeMode(val key: String) {
    SYSTEM("system"),
    LIGHT("light"),
    DARK("dark");

    companion object {
        fun fromKey(key: String?): ThemeMode = when (key) {
            "light" -> LIGHT
            "dark" -> DARK
            else -> SYSTEM
        }
    }
}

object ThemePrefs {
    private const val KEY = "theme_mode"

    fun get(ctx: Context): ThemeMode = ThemeMode.fromKey(prefs(ctx).getString(KEY, null))

    fun set(ctx: Context, mode: ThemeMode) {
        prefs(ctx).edit().putString(KEY, mode.key).apply()
    }

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(DshApp.PREFS_UI, Context.MODE_PRIVATE)
}

/**
 * 把主题偏好落到资源解析上：通过覆盖 UI_MODE_NIGHT 让 `values-night/colors.xml`
 * 生效（Activity 在 attachBaseContext 里包一层，服务在 inflate 前包一层）。
 *
 * 不用 AppCompat，因此用 createConfigurationContext 这套原生做法；
 * 与 LocaleHelper.wrap 可叠加（先语言、后主题）。
 */
object ThemeHelper {
    fun wrap(base: Context): Context {
        val night = when (ThemePrefs.get(base)) {
            ThemeMode.SYSTEM -> return base   // 跟随系统：不覆盖 uiMode
            ThemeMode.LIGHT -> Configuration.UI_MODE_NIGHT_NO
            ThemeMode.DARK -> Configuration.UI_MODE_NIGHT_YES
        }
        val cfg = Configuration(base.resources.configuration)
        cfg.uiMode = (cfg.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or night
        return base.createConfigurationContext(cfg)
    }

    /** 当前解析配置是否为深色（悬浮窗等按它取色时可读） */
    fun isDark(ctx: Context): Boolean =
        (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
}
