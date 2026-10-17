package app.dsh.mobile

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import kotlin.math.roundToInt

/**
 * 仿系统「验证码已复制」提示条（v1.2.162）。
 *
 * ## 为什么用 Activity 内浮层，而不是系统浮层
 * 用户实测：无障碍通道（TYPE_ACCESSIBILITY_OVERLAY）依赖服务在位——重装 APK 后系统会
 * 停用无障碍，浮层就不出现；退化成 Toast 又被系统限流（日志 "Toast already killed"），
 * 结果**什么都看不到**。用户明确要求："这种东西应该不用依赖无障碍服务吧？就是弄个伪弹窗"。
 *
 * 正解：**直接加在当前 Activity 的窗口里**（`addContentView`）——零权限、零依赖、
 * 必定可见，长得和系统验证码提示条一样。离开设置页时视图随 Activity 一起销毁，无残留。
 */
object CodeToast {

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var current: View? = null

    /**
     * 在 [activity] 顶部显示"验证码已复制"提示条。
     * @param code 6 位验证码
     * @param host 可选的访问地址（显示在验证码下方）
     */
    fun show(activity: Activity, code: String, host: String? = null) {
        // ⚠️ v1.2.162 实测修复（用户反馈"完全没弹窗"）：这里原本 `main.post{}` 包一层，
        // 而 dismiss() 内部**也**是 post —— 于是 show 里那句 dismiss() 被排到
        // "addContentView 之后"才执行，等于**刚加进去就被删掉**（只闪现一帧，肉眼看不到）。
        // 改为：已在主线程则同步执行，避免这类"自己删自己"的时序坑。
        mainThread {
            runCatching {
                dismiss()
                val v = buildView(activity, code, host)
                val lp = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                ).apply {
                    gravity = Gravity.TOP
                    val m = dp(activity, 16).toInt()
                    setMargins(m, dp(activity, 64).toInt(), m, 0)
                }
                // 加在 Activity 内容之上（decorView 的 content 层）——无需任何权限
                activity.addContentView(v, lp)
                current = v
                android.util.Log.i("CodeToast", "shown code=$code host=$host")
                main.postDelayed({ dismiss() }, 9_000L)
            }.onFailure {
                android.util.Log.w("CodeToast", "addContentView failed: ${it.message}")
                // 最后兜底：Toast（至少要有反馈）
                runCatching {
                    Toast.makeText(activity, activity.getString(R.string.code_toast_fallback, code), Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    fun dismiss() {
        mainThread {
            runCatching {
                (current?.parent as? ViewGroup)?.removeView(current)
            }
            current = null
        }
    }

    /** 已在主线程 → 直接执行；否则投递（避免调用方与内部双双 post 造成的时序错乱） */
    private inline fun mainThread(crossinline block: () -> Unit) {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) block()
        else main.post { block() }
    }

    private fun buildView(ctx: Context, code: String, host: String?): View {
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val p = dp(ctx, 16).toInt()
            setPadding(p, dp(ctx, 14).toInt(), p, dp(ctx, 14).toInt())
            background = GradientDrawable().apply {
                cornerRadius = dp(ctx, 18)
                setColor(0xF21F2733.toInt())      // 与悬浮窗/设置页同源深色
                setStroke(dp(ctx, 1).toInt(), 0x33FFFFFF)
            }
            elevation = dp(ctx, 8)
            isClickable = true                     // 吃掉点击，避免穿透到下面的行
            setOnClickListener { dismiss() }
        }

        card.addView(TextView(ctx).apply {
            text = ctx.getString(R.string.code_copied_hint)
            setTextColor(0xFF9AA0A6.toInt())
            textSize = 12f
        })

        card.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(ctx, 6).toInt(), 0, 0)

            addView(TextView(ctx).apply {
                text = code
                setTextColor(Color.WHITE)
                textSize = 28f
                typeface = Typeface.MONOSPACE       // 等宽，像系统验证码那样好读
                letterSpacing = 0.2f
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

            addView(TextView(ctx).apply {
                text = ctx.getString(R.string.code_copy)
                setTextColor(0xFF7DD3FC.toInt())
                textSize = 15f
                val p = dp(ctx, 12).toInt()
                setPadding(p, dp(ctx, 8).toInt(), p, dp(ctx, 8).toInt())
                isClickable = true
                setOnClickListener {
                    runCatching {
                        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("DSH LAN code", code))
                    }
                    Toast.makeText(ctx, ctx.getString(R.string.code_copied_toast, code), Toast.LENGTH_SHORT).show()
                    dismiss()
                }
            })
        })

        if (!host.isNullOrBlank()) {
            card.addView(TextView(ctx).apply {
                text = host
                setTextColor(0xFF8A94A3.toInt())
                textSize = 11f
                setPadding(0, dp(ctx, 6).toInt(), 0, 0)
            })
        }
        return card
    }

    private fun dp(ctx: Context, v: Int): Float =
        v * ctx.resources.displayMetrics.density
}
