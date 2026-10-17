package app.dsh.mobile

import android.util.Log
import kotlin.math.abs

/**
 * 帧差验证层（v1.2.122）—— 动态视觉场景的「变了没」判据。
 *
 * ## 为什么需要它
 * 文本场景有无障碍事件流（`scr changed`）告知屏幕变化；但**动态视觉场景**
 * （Canvas 游戏、视频、动画、WebGL）**没有节点树、也没有可用事件** —— 唯一可靠
 * 信号是**画面本身**。这是整条视觉路径的地基：没有「变了没」的判定，
 * 「点一下有没有生效」「加载完了吗」「是不是卡住了」都无从谈起。
 *
 * ## 做法（快，是关键）
 * `screenshotArgb()` 直接取 ARGB 像素（跳过 PNG/base64 往返）→ **降采样到 64×64
 * 灰度**（区域平均，抗锯齿）→ 与上一帧逐像素比。64×64=4096 像素，一次比对
 * **< 5ms**；直接比 1080p（230 万像素）要上百毫秒，且大部分像素对
 * 「变没变」这个问题没有贡献。
 *
 * ## 输出语义
 * - [DiffResult.changedRatio] 变化像素占比（整体强度）
 * - [DiffResult.hotRegions] 变化集中在哪（4×4 网格 → 16 个区块，按强度降序）
 * - [DiffResult.centroid] 变化重心（相对坐标 0..1，供「朝变化方向滑」这类决策）
 */
object Framer {

    private const val TAG = "Framer"

    /** 降采样边长：64 在「灵敏度 vs 速度」上最均衡 */
    const val SIDE = 64

    /** 网格划分数（SIDE / 4 = 16 格） */
    private const val GRID = 4
    private const val CELL = SIDE / GRID

    /** 判定「该像素变了」的灰度差阈值（0-255；抗压缩噪点与屏幕微闪） */
    private const val PIXEL_THRESHOLD = 12

    /** 判定「帧有变化」的变化像素占比（4% —— 40fps 视频单帧通常远超，静态页接近 0） */
    private const val CHANGED_RATIO = 0.004f

    /** 某区块算「热点」的变化像素占比 */
    private const val HOT_RATIO = 0.06f

    private val lock = Any()
    private var prev: ByteArray? = null

    data class Region(
        /** 以 SIDE 为单位的归一化中心坐标（0..1） */
        val nx: Float,
        val ny: Float,
        val ratio: Float,
    )

    data class DiffResult(
        /** 变化像素占全屏比例 */
        val changedRatio: Float,
        /** 是否判定为「帧有变化」 */
        val changed: Boolean,
        /** 变化热点（归一化坐标），按强度降序，最多 8 个 */
        val hotRegions: List<Region>,
        /** 变化重心（归一化 0..1）；无变化为 -1 */
        val cx: Float,
        val cy: Float,
        /** 采集 + 比对总耗时（ms） */
        val costMs: Long,
        /** 首帧（无历史可比） */
        val isFirst: Boolean,
        /** 截图不可用（API<30 / 未授权 / 服务异常）时为 true，调用方据此走事件流兜底 */
        val unavailable: Boolean,
    )

    /**
     * 采一帧并与上一帧比对（请在 IO 线程调用：截图是 IPC，100-1500ms 不等）。
     * 比对本身 <5ms，高频调用没问题。
     */
    fun captureAndDiff(svc: android.accessibilityservice.AccessibilityService): DiffResult {
        val t0 = System.currentTimeMillis()
        val shot = runCatching { (svc as? DshAccessibilityService)?.screenshotArgb() }.getOrNull()
        if (shot == null) {
            return DiffResult(0f, false, emptyList(), -1f, -1f,
                System.currentTimeMillis() - t0, false, unavailable = true)
        }
        val (pixels, w, h) = shot
        val cur = downsampleGray(pixels, w, h)
        synchronized(lock) {
            val p = prev
            val r = if (p == null) {
                DiffResult(0f, false, emptyList(), -1f, -1f,
                    System.currentTimeMillis() - t0, isFirst = true, unavailable = false)
            } else {
                computeDiff(p, cur, System.currentTimeMillis() - t0)
            }
            prev = cur
            return r
        }
    }

    /** 重置历史（场景切换时调用，否则「换场景」会被算成一次巨大变化） */
    fun reset() {
        synchronized(lock) { prev = null }
    }

    fun hasBaseline(): Boolean = synchronized(lock) { prev != null }

    // ---------------- 内部实现 ----------------

    /** 区域平均降采样 + 转灰度（luma 600/1000 权重，抗锯齿优于逐点抽样） */
    private fun downsampleGray(argb: IntArray, sw: Int, sh: Int): ByteArray {
        val out = ByteArray(SIDE * SIDE)
        for (y2 in 0 until SIDE) {
            val y1a = y2 * sh / SIDE
            val y1b = ((y2 + 1) * sh / SIDE).coerceAtLeast(y1a + 1).coerceAtMost(sh)
            for (x2 in 0 until SIDE) {
                val x1a = x2 * sw / SIDE
                val x1b = ((x2 + 1) * sw / SIDE).coerceAtLeast(x1a + 1).coerceAtMost(sw)
                var sum = 0L
                var n = 0
                for (y in y1a until y1b) {
                    val row = y * sw
                    for (x in x1a until x1b) {
                        val c = argb[row + x]
                        sum += (((c shr 16) and 0xFF) * 299 +
                                ((c shr 8) and 0xFF) * 587 +
                                (c and 0xFF) * 114) / 1000
                        n++
                    }
                }
                out[y2 * SIDE + x2] = (if (n > 0) sum / n else 0L).toByte()
            }
        }
        return out
    }

    /** 逐像素比对 + GRID×GRID 热点统计 */
    private fun computeDiff(prevFrame: ByteArray, curFrame: ByteArray, costMs: Long): DiffResult {
        var changedPixels = 0
        var wx = 0L
        var wy = 0L
        val cells = IntArray(GRID * GRID)
        val total = curFrame.size
        for (i in 0 until total) {
            // ⚠️ v1.2.152 审查修复（P1）：Byte 有符号回绕——灰度 130(存-126) 与 126
            // 实际差 4，符号扩展后算成 254 → 静止像素被误判剧变。按无符号比较。
            val d = abs((prevFrame[i].toInt() and 0xFF) - (curFrame[i].toInt() and 0xFF))
            if (d >= PIXEL_THRESHOLD) {
                val x = i % SIDE
                val y = i / SIDE
                changedPixels++
                wx += x.toLong()
                wy += y.toLong()
                cells[(y / CELL) * GRID + (x / CELL)]++
            }
        }
        val ratio = changedPixels.toFloat() / total
        val regions = ArrayList<Region>(8)
        for (i in 0 until GRID * GRID) {
            val cellTotal = CELL * CELL
            val r = cells[i] / cellTotal.toFloat()
            if (r >= HOT_RATIO) {
                regions.add(Region(
                    nx = ((i % GRID) * CELL + CELL / 2f) / SIDE,
                    ny = ((i / GRID) * CELL + CELL / 2f) / SIDE,
                    ratio = r,
                ))
            }
        }
        regions.sortByDescending { it.ratio }
        if (regions.size > 8) regions.subList(8, regions.size).clear()
        val cx = if (changedPixels > 0) wx.toFloat() / changedPixels / SIDE else -1f
        val cy = if (changedPixels > 0) wy.toFloat() / changedPixels / SIDE else -1f
        Log.v(TAG, "diff: ratio=${"%.4f".format(ratio)} changed=$changedPixels cost=${costMs}ms")
        return DiffResult(ratio, ratio >= CHANGED_RATIO, regions, cx, cy, costMs, false, false)
    }
}
