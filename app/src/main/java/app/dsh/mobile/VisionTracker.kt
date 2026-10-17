package app.dsh.mobile

import android.graphics.Bitmap
import android.util.Log
import java.io.ByteArrayOutputStream
import kotlin.math.sqrt

/**
 * 视觉追踪层（v1.2.124）—— 无节点树场景的「目标在哪」。
 *
 * ## 为什么需要它
 * 手机游戏 / Canvas / 纯图片界面的**无障碍节点树是空的**，FastPicker 的文本输入
 * 直接没有。这类场景的操作其实很有限：**虚拟按钮（点/长按）+ 摇杆/视角（拖动）**
 * ——手势层全都能做，缺的只是「按钮在哪」这个视觉定位。
 *
 * ## 分工（云端理解 + 本地执行）
 * - **云端**（一次性）：看懂画面 → 圈出目标（按钮/摇杆/图标）→ 给出模板小图
 * - **本地**（每帧）：模板匹配找到目标位置 → 输出坐标（供点击/拖动）→ 毫秒级
 *
 * ## 做法
 * 截图（`screenshotArgb`，跳过 PNG 往返）→ 降采样到处理分辨率（长边 160，与
 * 离线验证一致）→ 灰度 → 与模板做 **NCC（归一化互相关）** 全图搜索 →
 * 最佳位置换算回原图坐标。
 *
 * ## 实测数据（离线验证，见 docs/LOCAL_CLOSED_LOOP.md 第 13 节）
 * - 位置误差 1px（处理分辨率下）、NCC 分数 0.88
 * - PC/OpenCV p50 0.39ms；Kotlin 手写预估 5-30ms（本文件实现后实测）
 * - **局限**：纹理类目标（方块树）缩放后失真，只适合**同尺度**匹配；
 *   距离估计不可行（用「步进 + 帧差」替代，见文档）
 */
object VisionTracker {

    private const val TAG = "VisionTracker"

    /**
     * 处理分辨率长边：320。
     * ⚠️ 实测教训：160 时模板只有 13×13，特征不足 → 全屏搜索会误匹配
     * （彩色匹配下仍匹配到血条等亮块）。320 下模板约 56×56，特征充分；
     * 计算量由**搜索区域限制**（云端给出目标大致位置）控制。
     */
    const val PROC_LONG = 320

    data class Match(
        /** 原图坐标（中心，供点击） */
        val x: Int,
        val y: Int,
        /** NCC 分数 -1..1（>0.7 通常可靠） */
        val score: Double,
        /** 处理分辨率下的左上角（调试） */
        val procX: Int,
        val procY: Int,
        val procW: Int,
        val procH: Int,
        /** 处理分辨率尺寸（换算用） */
        val frameW: Int,
        val frameH: Int,
        val costMs: Long,
    )

    /**
     * 降采样成 **RGB 三通道**（交织存储：out[(y*dw+x)*3 + c]）。
     * ⚠️ 实测教训：灰度降采样会丢失颜色特征——红色圆环按钮和红色血条在 13×13
     * 灰度下几乎一样，导致匹配到错误目标（分数 0.992 位置全错）。彩色匹配用
     * 形状+颜色联合特征，区分度显著提升。
     */
    fun downsampleRgb(argb: IntArray, sw: Int, sh: Int, dw: Int, dh: Int): ByteArray {
        val out = ByteArray(dw * dh * 3)
        for (y2 in 0 until dh) {
            val y1a = y2 * sh / dh
            val y1b = ((y2 + 1) * sh / dh).coerceAtLeast(y1a + 1).coerceAtMost(sh)
            for (x2 in 0 until dw) {
                val x1a = x2 * sw / dw
                val x1b = ((x2 + 1) * sw / dw).coerceAtLeast(x1a + 1).coerceAtMost(sw)
                var sr = 0L; var sg = 0L; var sb = 0L
                var n = 0
                for (y in y1a until y1b) {
                    val row = y * sw
                    for (x in x1a until x1b) {
                        val c = argb[row + x]
                        sr += (c shr 16) and 0xFF
                        sg += (c shr 8) and 0xFF
                        sb += c and 0xFF
                        n++
                    }
                }
                val o = (y2 * dw + x2) * 3
                out[o] = (if (n > 0) sr / n else 0L).toByte()
                out[o + 1] = (if (n > 0) sg / n else 0L).toByte()
                out[o + 2] = (if (n > 0) sb / n else 0L).toByte()
            }
        }
        return out
    }

    /**
     * 三通道 NCC 匹配（各通道分别算，取平均）。
     * 返回处理分辨率下左上角 + 平均分数。
     */
    fun matchNccRgb(
        frame: ByteArray, fw: Int, fh: Int,
        tmpl: ByteArray, tw: Int, th: Int,
    ): Triple<Int, Int, Double>? {
        if (tw > fw || th > fh || tw < 2 || th < 2) return null

        // 模板各通道去均值 + 范数
        val tZero = DoubleArray(tw * th * 3)
        val tNorm = DoubleArray(3)
        for (c in 0 until 3) {
            var sum = 0.0
            for (i in 0 until tw * th) sum += tmpl[i * 3 + c]
            val mean = sum / (tw * th)
            var n2 = 0.0
            for (i in 0 until tw * th) {
                val d = tmpl[i * 3 + c] - mean
                tZero[i * 3 + c] = d
                n2 += d * d
            }
            tNorm[c] = sqrt(n2)
        }
        if (tNorm.all { it < 1e-6 }) return null

        var bestScore = -2.0
        var bestX = 0
        var bestY = 0
        val maxY = fh - th
        val maxX = fw - tw
        val tArea = tw * th

        for (y in 0..maxY) {
            for (x in 0..maxX) {
                var scoreSum = 0.0
                var validCh = 0
                for (c in 0 until 3) {
                    if (tNorm[c] < 1e-6) continue
                    var wSum = 0.0
                    for (ty in 0 until th) {
                        val row = ((y + ty) * fw + x) * 3 + c
                        var o = row
                        for (tx in 0 until tw) { wSum += frame[o]; o += 3 }
                    }
                    val wMean = wSum / tArea
                    var dot = 0.0
                    var wNorm2 = 0.0
                    var k = 0
                    for (ty in 0 until th) {
                        var o = ((y + ty) * fw + x) * 3 + c
                        for (tx in 0 until tw) {
                            val d = frame[o] - wMean
                            dot += d * tZero[k * 3 + c]
                            wNorm2 += d * d
                            k++
                            o += 3
                        }
                    }
                    val denom = sqrt(wNorm2) * tNorm[c]
                    if (denom < 1e-6) continue
                    scoreSum += dot / denom
                    validCh++
                }
                if (validCh == 0) continue
                val s = scoreSum / validCh
                if (s > bestScore) {
                    bestScore = s
                    bestX = x
                    bestY = y
                }
            }
        }
        return Triple(bestX, bestY, bestScore)
    }

    /**
     * 降采样成灰度（区域平均 + luma 权重，抗锯齿优于逐点抽样）。
     * 与 Framer.downsampleGray 同思路，但尺寸可配（Framer 固定 64）。
     */
    fun downsampleGray(argb: IntArray, sw: Int, sh: Int, dw: Int, dh: Int): ByteArray {
        val out = ByteArray(dw * dh)
        for (y2 in 0 until dh) {
            val y1a = y2 * sh / dh
            val y1b = ((y2 + 1) * sh / dh).coerceAtLeast(y1a + 1).coerceAtMost(sh)
            for (x2 in 0 until dw) {
                val x1a = x2 * sw / dw
                val x1b = ((x2 + 1) * sw / dw).coerceAtLeast(x1a + 1).coerceAtMost(sw)
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
                out[y2 * dw + x2] = (if (n > 0) sum / n else 0L).toByte()
            }
        }
        return out
    }

    /** 计算处理分辨率（长边 PROC_LONG，保持比例） */
    fun procSize(w: Int, h: Int): Pair<Int, Int> {
        val long = maxOf(w, h)
        if (long <= PROC_LONG) return w to h
        val scale = PROC_LONG.toDouble() / long
        return maxOf(1, (w * scale).toInt()) to maxOf(1, (h * scale).toInt())
    }

    /**
     * NCC 模板匹配（在场景灰度图里找模板的最佳位置）。
     * 返回处理分辨率下的左上角坐标 + 分数。
     */
    fun matchNcc(
        frame: ByteArray, fw: Int, fh: Int,
        tmpl: ByteArray, tw: Int, th: Int,
    ): Triple<Int, Int, Double>? {
        if (tw > fw || th > fh || tw < 2 || th < 2) return null

        // 模板去均值 + 范数（预计算一次）
        var tSum = 0.0
        for (v in tmpl) tSum += v
        val tMean = tSum / tmpl.size
        val tZero = DoubleArray(tmpl.size)
        var tNorm2 = 0.0
        for (i in tmpl.indices) {
            val d = tmpl[i] - tMean
            tZero[i] = d
            tNorm2 += d * d
        }
        val tNorm = sqrt(tNorm2)
        if (tNorm < 1e-6) return null   // 纯色模板无区分度

        var bestScore = -2.0
        var bestX = 0
        var bestY = 0
        val maxY = fh - th
        val maxX = fw - tw
        val tArea = tw * th

        for (y in 0..maxY) {
            for (x in 0..maxX) {
                // 窗口均值
                var wSum = 0.0
                for (ty in 0 until th) {
                    val row = (y + ty) * fw + x
                    for (tx in 0 until tw) wSum += frame[row + tx]
                }
                val wMean = wSum / tArea
                // 去均值互相关 + 窗口范数
                var dot = 0.0
                var wNorm2 = 0.0
                var k = 0
                for (ty in 0 until th) {
                    val row = (y + ty) * fw + x
                    for (tx in 0 until tw) {
                        val d = frame[row + tx] - wMean
                        dot += d * tZero[k]
                        wNorm2 += d * d
                        k++
                    }
                }
                val denom = sqrt(wNorm2) * tNorm
                if (denom < 1e-6) continue
                val s = dot / denom
                if (s > bestScore) {
                    bestScore = s
                    bestX = x
                    bestY = y
                }
            }
        }
        return Triple(bestX, bestY, bestScore)
    }

    /**
     * 一站式：全屏截图 + 模板（原图尺寸）→ 匹配 → 原图坐标。
     * @param shot 全屏 ARGB（screenshotArgb 的输出）
     * @param tmplArgb 模板 ARGB（原图尺度的小图，云端给的）
     * @param region 可选搜索区域（原图归一化：nx,ny,nw,nh）。**强烈建议提供**——
     *   云端看懂画面时本就知道目标大致在哪，限制区域既提速又防误匹配。
     */
    fun locate(
        shot: Triple<IntArray, Int, Int>,
        tmplArgb: IntArray, tmplW: Int, tmplH: Int,
        region: FloatArray? = null,
    ): Match? {
        val t0 = System.currentTimeMillis()
        val (pixels, sw, sh) = shot
        val (pw, ph) = procSize(sw, sh)
        val frameRgb = downsampleRgb(pixels, sw, sh, pw, ph)

        // ⚠️ 模板必须按**帧的同一比例**降采样（实测坑：按自身长边降到 PROC_LONG 会
        // 得到比处理帧还大的模板 → matchNcc 直接返回 null）
        val scale = pw.toDouble() / sw
        val ptw = maxOf(2, Math.round(tmplW * scale).toInt())
        val pth = maxOf(2, Math.round(tmplH * scale).toInt())
        if (ptw < 2 || pth < 2) {
            Log.w(TAG, "template too small after scale: ${tmplW}x$tmplH -> ${ptw}x$pth")
            return null
        }
        val tmplRgb = downsampleRgb(tmplArgb, tmplW, tmplH, ptw, pth)

        // 搜索区域（处理分辨率坐标）
        var rx = 0; var ry = 0; var rw = pw; var rh = ph
        if (region != null && region.size >= 4) {
            rx = (region[0] * pw).toInt().coerceIn(0, pw - 1)
            ry = (region[1] * ph).toInt().coerceIn(0, ph - 1)
            rw = (region[2] * pw).toInt().coerceIn(2, pw - rx)
            rh = (region[3] * ph).toInt().coerceIn(2, ph - ry)
            // ⚠️ 实测坑：区域比模板还小时 matchNcc 直接返回 null（静默失败）。
            // 增量追踪传"目标附近 ±10%"时区域可能只有 20×38 而模板 31×31 →
            // 必须自动扩展到"装得下模板 + 搜索余量"，语义仍是"在该中心附近找"。
            val minW = ptw + 6
            val minH = pth + 6
            if (rw < minW) {
                val cx = rx + rw / 2
                rw = minOf(pw, minW)
                rx = (cx - rw / 2).coerceIn(0, pw - rw)
            }
            if (rh < minH) {
                val cy = ry + rh / 2
                rh = minOf(ph, minH)
                ry = (cy - rh / 2).coerceIn(0, ph - rh)
            }
        }
        val sub = cropRgb(frameRgb, pw, ph, rx, ry, rw, rh) ?: return null
        val (subPx, subW, subH) = sub

        val r = matchNccRgb(subPx, subW, subH, tmplRgb, ptw, pth) ?: return null
        val costMs = System.currentTimeMillis() - t0
        // 子图坐标 → 处理坐标 → 原图坐标（匹配框中心）
        val cxProc = rx + r.first + ptw / 2.0
        val cyProc = ry + r.second + pth / 2.0
        val scaleX = sw.toDouble() / pw
        val scaleY = sh.toDouble() / ph
        return Match(
            x = (cxProc * scaleX).toInt().coerceIn(0, sw - 1),
            y = (cyProc * scaleY).toInt().coerceIn(0, sh - 1),
            score = r.third,
            procX = rx + r.first, procY = ry + r.second, procW = ptw, procH = pth,
            frameW = pw, frameH = ph,
            costMs = costMs,
        )
    }

    /** 从 RGB 交织缓冲区裁一块（返回新的 RGB 缓冲 + 尺寸） */
    private fun cropRgb(rgb: ByteArray, w: Int, h: Int, x: Int, y: Int, cw: Int, ch: Int): Triple<ByteArray, Int, Int>? {
        if (x < 0 || y < 0 || cw <= 0 || ch <= 0 || x + cw > w || y + ch > h) return null
        val out = ByteArray(cw * ch * 3)
        for (row in 0 until ch) {
            System.arraycopy(rgb, ((y + row) * w + x) * 3, out, row * cw * 3, cw * 3)
        }
        return Triple(out, cw, ch)
    }

    /** PNG bytes → ARGB IntArray（模板解码用） */
    fun decodePng(bytes: ByteArray): Triple<IntArray, Int, Int>? = runCatching {
        val bmp = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        val w = bmp.width
        val h = bmp.height
        val arr = IntArray(w * h)
        bmp.getPixels(arr, 0, w, 0, 0, w, h)
        bmp.recycle()
        Triple(arr, w, h)
    }.getOrNull()

    /** 裁剪截图的一块（云端从整屏截图里圈目标用） */
    fun crop(shot: Triple<IntArray, Int, Int>, x: Int, y: Int, w: Int, h: Int): Triple<IntArray, Int, Int>? {
        val (px, sw, sh) = shot
        if (x < 0 || y < 0 || w <= 0 || h <= 0 || x + w > sw || y + h > sh) return null
        val out = IntArray(w * h)
        for (row in 0 until h) {
            System.arraycopy(px, (y + row) * sw + x, out, row * w, w)
        }
        return Triple(out, w, h)
    }

    /** ARGB IntArray → PNG bytes（存模板用） */
    fun encodePng(argb: IntArray, w: Int, h: Int): ByteArray? = runCatching {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bmp.setPixels(argb, 0, w, 0, 0, w, h)
        val bos = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, bos)
        bmp.recycle()
        bos.toByteArray()
    }.getOrNull()
}
