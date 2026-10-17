package app.dsh.mobile

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.util.Log
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * 图标识别（v1.2.150，用户需求「至少识别常用图标：齿轮=设置、放大镜=搜索」）。
 *
 * ## 思路（零训练）
 * 内置 **Material Design 官方图标**（round 风格，36 个常用图标，随 APK 分发），
 * 对界面上**无标签的图标区域**做 NCC（归一化互相关）匹配——命中即补语义标签。
 * 与 VisionTracker 的区别：它是"在大图里搜模板"（滑窗，慢）；本类是
 * **"给定区域 vs 模板"的分类**（一次比对，毫秒级）。
 *
 * ## 为什么 NCC
 * 归一化互相关对**光照/对比度/尺寸**不敏感（减均值除标准差）——图标在不同
 * App 里颜色深浅不一，NCC 只看形状结构。区域先归一化到 32×32 灰度再比对。
 *
 * ## 覆盖
 * 36 个高频图标：设置齿轮/搜索放大镜/返回/前进/菜单/更多/关闭/分享/星标/
 * 添加/编辑/删除/刷新/主页/用户/勾选/收藏/信息/警告/通知/锁/可见性/下载/
 * 上传/播放/相机/电话/邮件/日历/定位/购物车/账号/退出/筛选/排序。
 *
 * 用法：dump 里无标签图标先经 [identify] 补 `=名称`；未命中的再退回
 * dumpScreenCompact 的 `~最近文本` 空间推断（两级互补）。
 */
object IconMatcher {

    private const val TAG = "IconMatcher"

    /** 归一化边长（匹配用；图标形状在 32×32 下仍可分） */
    private const val SIZE = 32

    /** NCC 阈值：低于此不认（宁可无标签，也不贴错标签） */
    private const val MIN_SCORE = 0.45

    /** 合理图标尺寸范围（像素）：过小是噪点，过大是整块区域 */
    const val MIN_ICON_PX = 20
    const val MAX_ICON_PX = 220

    data class Template(val name: String, val gray: ByteArray)

    data class Hit(val name: String, val score: Double)

    @Volatile private var cache: List<Template>? = null

    /** 加载模板库（assets/icon-templates 下的 PNG → 32×32 灰度；懒加载缓存） */
    fun templates(ctx: Context): List<Template> {
        cache?.let { return it }
        synchronized(this) {
            cache?.let { return it }
            val out = ArrayList<Template>()
            runCatching {
                val names = ctx.assets.list("icon-templates") ?: emptyArray()
                for (fn in names.filter { it.endsWith(".png") }) {
                    val bmp = ctx.assets.open("icon-templates/$fn").use { BitmapFactory.decodeStream(it) }
                        ?: continue
                    val gray = toGray32(bmp)
                    bmp.recycle()
                    out.add(Template(fn.removeSuffix(".png"), gray))
                }
            }.onFailure { Log.w(TAG, "template load failed: ${it.message}") }
            cache = out
            Log.i(TAG, "loaded ${out.size} icon templates")
            return out
        }
    }

    /**
     * 识别截图里某个矩形区域像哪个图标。
     * @return 命中（名称 + NCC 分数）；低于阈值返回 null
     */
    fun identify(
        ctx: Context,
        shot: Triple<IntArray, Int, Int>,
        rect: Rect,
    ): Hit? {
        val (pixels, sw, sh) = shot
        if (rect.width() < MIN_ICON_PX || rect.height() < MIN_ICON_PX) return null
        if (rect.width() > MAX_ICON_PX || rect.height() > MAX_ICON_PX) return null
        val region = cropGray32(pixels, sw, sh, rect) ?: return null
        var best: Hit? = null
        for (t in templates(ctx)) {
            val s = ncc(region, t.gray)
            if (best == null || s > best!!.score) best = Hit(t.name, s)
        }
        return best?.takeIf { it.score >= MIN_SCORE }
    }

    /** 批量识别（一次截图多个区域——避免重复截屏） */
    fun identifyAll(
        ctx: Context,
        shot: Triple<IntArray, Int, Int>,
        rects: List<Rect>,
    ): List<Hit?> = rects.map { identify(ctx, shot, it) }

    // ---------------- 内部 ----------------

    /** Bitmap → 32×32 灰度（区域平均降采样） */
    private fun toGray32(bmp: android.graphics.Bitmap): ByteArray {
        val out = ByteArray(SIZE * SIZE)
        val sw = bmp.width
        val sh = bmp.height
        for (y in 0 until SIZE) {
            val y0 = y * sh / SIZE
            val y1 = ((y + 1) * sh / SIZE).coerceAtLeast(y0 + 1).coerceAtMost(sh)
            for (x in 0 until SIZE) {
                val x0 = x * sw / SIZE
                val x1 = ((x + 1) * sw / SIZE).coerceAtLeast(x0 + 1).coerceAtMost(sw)
                var sum = 0L
                var n = 0
                for (yy in y0 until y1) for (xx in x0 until x1) {
                    val c = bmp.getPixel(xx, yy)
                    sum += (((c shr 16) and 0xFF) * 299 + ((c shr 8) and 0xFF) * 587 + (c and 0xFF) * 114) / 1000
                    n++
                }
                out[y * SIZE + x] = (if (n > 0) sum / n else 0L).toByte()
            }
        }
        return out
    }

    /** 截图区域裁剪 → 32×32 灰度（与模板同一归一化口径） */
    private fun cropGray32(pixels: IntArray, sw: Int, sh: Int, rect: Rect): ByteArray? {
        val l = rect.left.coerceIn(0, sw - 1)
        val t = rect.top.coerceIn(0, sh - 1)
        val r = rect.right.coerceIn(l + 1, sw)
        val b = rect.bottom.coerceIn(t + 1, sh)
        val out = ByteArray(SIZE * SIZE)
        for (y in 0 until SIZE) {
            val y0 = t + y * (b - t) / SIZE
            val y1 = (t + (y + 1) * (b - t) / SIZE).coerceAtLeast(y0 + 1).coerceAtMost(b)
            for (x in 0 until SIZE) {
                val x0 = l + x * (r - l) / SIZE
                val x1 = (l + (x + 1) * (r - l) / SIZE).coerceAtLeast(x0 + 1).coerceAtMost(r)
                var sum = 0L
                var n = 0
                for (yy in y0 until y1) for (xx in x0 until x1) {
                    val c = pixels[yy * sw + xx]
                    sum += (((c shr 16) and 0xFF) * 299 + ((c shr 8) and 0xFF) * 587 + (c and 0xFF) * 114) / 1000
                    n++
                }
                out[y * SIZE + x] = (if (n > 0) sum / n else 0L).toByte()
            }
        }
        return out
    }

    /** 归一化互相关（减均值除标准差——对亮度/对比度不敏感） */
    private fun ncc(a: ByteArray, b: ByteArray): Double {
        if (a.size != b.size) return 0.0
        // ⚠️ v1.2.152 审查修复（P1）：Byte 有符号回绕（灰度 128+ 变负）会让 127/128
        // 断崖两侧的像素差被撕裂出 255 落差 → 相关系数失真。全部按无符号读取。
        fun u(v: Byte) = v.toInt() and 0xFF
        var sa = 0.0
        var sb = 0.0
        for (i in a.indices) { sa += u(a[i]); sb += u(b[i]) }
        val ma = sa / a.size
        val mb = sb / b.size
        var num = 0.0
        var da = 0.0
        var db = 0.0
        for (i in a.indices) {
            val xa = u(a[i]) - ma
            val xb = u(b[i]) - mb
            num += xa * xb
            da += xa * xa
            db += xb * xb
        }
        val den = sqrt(da * db)
        return if (den < 1e-6) 0.0 else num / den
    }
}
