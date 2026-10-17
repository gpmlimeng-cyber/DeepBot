package app.dsh.mobile.engine

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipInputStream

/**
 * DeepBot 出厂预装（v1.2.122）。
 *
 * 目标：装完点开就是 DeepBot 界面，不需要用户自己装插件。
 *
 * 做法（只写 App 自己的 DSH_HOME，绝不碰 /storage 上旧的那套）：
 *  1. 解压 assets/deepbot/ds-bot.zip 到 `$DSH_HOME/deepbot/ds-bot`（按版本戳幂等）；
 *  2. 在 `$DSH_HOME/profiles/web/node_modules/ds-bot` 放一份副本 —— 上游的包解析
 *     是「先安装目录、后 profile 目录」双锚点，有这份副本即使 pnpm 不跑也能解析；
 *  3. 把 `ds-bot` 写进 profile package.json 的 `dsh.profile.bundles`（默认启用），
 *     dependencies 里登记 `file:` 指向解压目录，供 pnpm 跑时投影。
 *
 * 失败只记日志、返回 false，不阻断引擎启动（插件装不上也不能让 App 起不来）。
 */
object DeepBotSeed {
    private const val TAG = "DeepBotSeed"
    private const val VERSION = "0.1.7"
    private const val ASSET = "deepbot/ds-bot.zip"
    private const val STAMP = ".deepbot-version"
    private const val BASE = "@deepseek-ai/dsh-base"
    private const val WEB_APP = "@deepseek-ai/dsh-web-app"
    private const val BUNDLE = "ds-bot"

    fun ensure(ctx: Context): Boolean = try {
        val home = EngineConfig.dshHome(ctx)
        val profile = File(home, "profiles/web").apply { mkdirs() }
        val src = File(home, "deepbot/ds-bot")
        val stamp = File(profile, STAMP)

        val stampOk = stamp.takeIf { it.isFile }?.readText()?.trim() == VERSION
        if (!stampOk || !File(src, "index.js").isFile) {
            val tmp = File(home, "deepbot/.ds-bot.tmp")
            tmp.deleteRecursively()
            unzip(ctx, ASSET, tmp)
            src.deleteRecursively()
            if (!tmp.renameTo(src)) {
                tmp.copyRecursively(src, overwrite = true)
                tmp.deleteRecursively()
            }
            stamp.writeText(VERSION)
            Log.i(TAG, "seeded ds-bot $VERSION -> ${src.absolutePath}")
        }

        // profile 内副本：不依赖 pnpm 也能被解析到
        val nm = File(profile, "node_modules/ds-bot")
        if (!File(nm, "index.js").isFile) {
            nm.deleteRecursively()
            src.copyRecursively(nm, overwrite = true)
            Log.i(TAG, "copied ds-bot into profile node_modules")
        }

        ensureManifest(profile, src)
        // 注入规则文件：不存在就写一份默认内容，之后用户可自行编辑（v1.2.122）
        InjectSpec.ensureDefault(home)
        true
    } catch (t: Throwable) {
        Log.w(TAG, "ds-bot seed failed: ${t.message}")
        false
    }

    /** 把 ds-bot 写进 profile 的 bundles（默认启用），并保证 base/web-app 在最前 */
    private fun ensureManifest(profile: File, src: File) {
        val f = File(profile, "package.json")
        val root = if (f.isFile) {
            runCatching { JSONObject(f.readText()) }.getOrElse { JSONObject() }
        } else JSONObject()

        if (!root.has("name")) root.put("name", "dsh-profile-web")
        if (!root.has("private")) root.put("private", true)

        val deps = root.optJSONObject("dependencies") ?: JSONObject().also { root.put("dependencies", it) }
        deps.put(BUNDLE, "file:" + src.absolutePath)

        val dsh = root.optJSONObject("dsh") ?: JSONObject().also { root.put("dsh", it) }
        val prof = dsh.optJSONObject("profile") ?: JSONObject().also { dsh.put("profile", it) }
        val old = prof.optJSONArray("bundles") ?: JSONArray()
        val names = (0 until old.length()).map { old.optString(it) }.toMutableList()
        if (names.none { it == BASE }) names.add(0, BASE)
        if (names.none { it == WEB_APP }) names.add(1, WEB_APP)
        if (names.none { it == BUNDLE }) names.add(BUNDLE)
        val arr = JSONArray()
        names.forEach { arr.put(it) }
        prof.put("bundles", arr)

        val text = root.toString(2)
        if (!f.isFile || f.readText() != text) f.writeText(text)
    }

    /** 解压 assets 里的 zip（带 zip-slip 防护） */
    private fun unzip(ctx: Context, asset: String, dest: File) {
        dest.mkdirs()
        val root = dest.canonicalFile
        ctx.assets.open(asset).use { input ->
            ZipInputStream(input).use { zip ->
                while (true) {
                    val e = zip.nextEntry ?: break
                    val out = File(root, e.name).canonicalFile
                    if (!out.path.startsWith(root.path + File.separator)) {
                        throw IllegalStateException("zip entry escapes root: ${e.name}")
                    }
                    if (e.isDirectory) {
                        out.mkdirs()
                    } else {
                        out.parentFile?.mkdirs()
                        out.outputStream().use { zip.copyTo(it) }
                    }
                    zip.closeEntry()
                }
            }
        }
    }
}
