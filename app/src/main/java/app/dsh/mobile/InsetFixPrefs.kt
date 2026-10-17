package app.dsh.mobile

import android.content.Context
import android.content.SharedPreferences

/**
 * 底部安全区适配开关（v1.2.122）。
 *
 * 壳在页面加载后注入一段 CSS（只注入，不改 ds-bot 源码）：
 *   [data-composer-card] { margin-bottom: var(--dsh-sab, 0px) !important }
 * 让 DSH/ds-bot 的输入框在 edge-to-edge 下不被手势条挡住。
 * 默认开；哪天规则和页面结构对不上，用户可以在设置里一键关掉。
 */
object InsetFixPrefs {
    private const val KEY = "inset_fix"

    fun isEnabled(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY, true)

    fun set(ctx: Context, enabled: Boolean) {
        prefs(ctx).edit().putBoolean(KEY, enabled).apply()
    }

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(DshApp.PREFS_UI, Context.MODE_PRIVATE)
}
