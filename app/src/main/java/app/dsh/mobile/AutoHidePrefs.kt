package app.dsh.mobile

import android.content.Context
import android.content.SharedPreferences

/**
 * 顶部工具栏自动隐藏偏好（v1.2.121）。
 *
 * 引擎进入「已就绪」后延迟 [HIDE_DELAY_MS] 再收起顶部横栏，只留把手；
 * 与 [OrientationPrefs] 共用同一份 `dsh_ui` SharedPreferences。
 *
 * 默认开启（用户反馈：手动点收起是多余动作，默认收起体验最好）。
 * 关掉后行为回到旧版：横栏常驻，只由用户点按钮/把手切换。
 */
object AutoHidePrefs {
    private const val KEY = "auto_hide_toolbar"

    /** 就绪后延迟收起的时间：留出看清端口/版本与手动干预的窗口 */
    const val HIDE_DELAY_MS = 2_000L

    fun isEnabled(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY, true)

    fun set(ctx: Context, enabled: Boolean) {
        prefs(ctx).edit().putBoolean(KEY, enabled).apply()
    }

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(DshApp.PREFS_UI, Context.MODE_PRIVATE)
}
