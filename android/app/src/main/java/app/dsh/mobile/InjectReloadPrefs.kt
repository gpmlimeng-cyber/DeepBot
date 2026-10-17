package app.dsh.mobile

import android.content.Context
import android.content.SharedPreferences

/**
 * 「重新加载注入」请求标记（v1.2.122）。
 *
 * 注入规则来自 `inject.json`，壳在**每次页面加载**时都会重读它，
 * 所以改完文件只要让页面重新加载一次即可生效，不用重启 App。
 * 设置页点这一行只写一个标记，回到主界面时 onResume 消费它并重载页面——
 * 不在设置页里直接操作主界面的 WebView（那会在后台重载正在看的页面）。
 */
object InjectReloadPrefs {
    private const val KEY = "inject_reload_pending"

    fun request(ctx: Context) {
        prefs(ctx).edit().putBoolean(KEY, true).apply()
    }

    /** 取走标记：返回 true 表示需要重载一次（读到即清，避免每次返回都重载） */
    fun consume(ctx: Context): Boolean {
        val p = prefs(ctx)
        if (!p.getBoolean(KEY, false)) return false
        p.edit().putBoolean(KEY, false).apply()
        return true
    }

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(DshApp.PREFS_UI, Context.MODE_PRIVATE)
}
