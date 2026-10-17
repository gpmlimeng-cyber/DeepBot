package app.dsh.mobile.engine

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 壳侧体检文件（v1.3.2）——**长期设施，不是临时诊断代码**。
 *
 * 每次页面加载完成 / inset 变化时，把「壳自己知道的事实」和「页面量到的数」合成一个
 * 可读 JSON，落在与 `inject.json` 同级的共享目录：
 *
 *     /storage/emulated/0/DSH/deepbot/state.json
 *
 * 为什么要有它：`com.deepbot.app` 是独立 uid，App 的 logcat、私有目录、引擎日志
 * 别的进程一律读不到（SELinux），于是「注入读的是哪份文件 / 视口多宽 / 插件部署了没有」
 * 只能靠人眼看界面猜 —— 2026-10-17 一整天卡的就是这件事。这个文件把答案摆到明面上，
 * 谁都能 `cat`，不必再跨 uid 猜、也不必为了取证反复出包。
 *
 * 写入策略：读旧文件 → 覆盖本次字段 → 写 `.tmp` → rename。留旧字段是有意的：
 * 页面侧某次量取失败时，上一次的读数不会被擦成 null。
 */
object ShellState {

    private const val TAG = "DeepBotState"

    /** 共享目录（与 inject.json 同级，壳本来就在读这里） */
    private const val SHARED = "/storage/emulated/0/DSH/deepbot/state.json"

    /**
     * 合并写一次。共享目录不可写（没给「所有文件访问」）时退回 App 私有目录，
     * 并把实际落盘路径写进 `path` 字段——找不到文件时先看这个字段就知道写哪去了。
     */
    fun write(ctx: Context, fields: Map<String, Any?>) {
        runCatching {
            val shared = File(SHARED)
            val parent = shared.parentFile
            val target = if (parent != null && (parent.isDirectory || parent.mkdirs())) shared
            else File(EngineConfig.dshHome(ctx), "deepbot/state.json").also { it.parentFile?.mkdirs() }

            val root = runCatching { JSONObject(target.readText()) }.getOrNull() ?: JSONObject()
            root.put("path", target.absolutePath)
            root.put("updatedAt", SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(Date()))
            fields.forEach { (k, v) -> root.put(k, v ?: JSONObject.NULL) }

            val tmp = File(target.parentFile, target.name + ".tmp")
            tmp.writeText(root.toString(2) + "\n")
            if (!tmp.renameTo(target)) {
                target.writeText(tmp.readText())
                tmp.delete()
            }
        }.onFailure {
            Log.w(TAG, "state write failed: ${it::class.simpleName}: ${it.message}")
        }
    }

    /**
     * 引擎目录下**已部署**的 android-plugins 包名（有 index.js 才算一套完整插件）。
     *
     * ⚠️ 这是壳侧部署清单，不等于「运行期已挂载」——挂没挂上要看页面，壳这边看不到。
     * 字段名下面附了同样的说明，别拿它当加载成功的证据。
     */
    fun deployedPlugins(ctx: Context): JSONArray = JSONArray().apply {
        runCatching {
            val dir = File(EngineConfig.engineRoot(ctx), "android-plugins")
            (dir.listFiles() ?: emptyArray())
                .filter { it.isDirectory && File(it, "index.js").isFile }
                .map { it.name }
                .sorted()
                .forEach { put(it) }
        }
    }
}
