package app.dsh.mobile.engine

import android.content.Context
import android.util.Log
import java.io.File

/**
 * 凭证探测（v1.2.127）—— 无障碍功能 max+ 的「模型来源」检测。
 *
 * ## 背景
 * 用户可能配置多个 API key（DeepSeek 官方 + 第三方 provider）。max+ 的
 * 云端持续预测需要一个可用凭证：**默认用 DeepSeek 官方（响应最快）**，
 * 但应让用户能自己选（真机反馈：「有多个模型多个 api key 怎么处理？」）。
 *
 * ## 隐私边界（重要）
 * 只读 `$DSH_HOME/.credentials.yaml` 的**记录键名与 kind**，本类**不读取
 * 也不输出 key 值**——行级解析只保留键名。这属于 max+ 开关知情同意的
 * 范围（弹窗已明确告知会读取 API key）。
 *
 * ## 格式（从引擎源码 dsh-credentials-local 确认）
 * ```yaml
 * version: 1
 * records:
 *   <记录键>:
 *     kind: api-key     # ← API key 记录（含 key / env 字段）
 *   client-connection/browser-session:
 *     kind: grant       # ← 会话凭证（不是 API key，需排除）
 * ```
 */
object CredentialProbe {

    private const val TAG = "CredentialProbe"

    /** 一条可用的 API key 记录（ref = 记录键名；label = 友好显示名） */
    data class Cred(val ref: String, val label: String)

    fun credFile(ctx: Context): File = File(ctx.filesDir, "dsh-home/.credentials.yaml")

    /**
     * 列出所有可用凭证的键名。**两个来源都要扫**（v1.2.129 修正）：
     *  · `refs:` 段 —— 简单的 ref → 值映射，**WebUI 的 Models 页写 API key 就在这里**
     *    （如 `DEEPSEEK_API_KEY: sk-xxx`）；此前只扫 records 会漏掉真机的 key。
     *  · `records:` 段 —— 结构化记录，取 `kind: api-key` 的（grant 类会话凭证排除）。
     * 宽松行解析（不引入 YAML 依赖）；未知格式安全降级为空表。
     */
    fun listApiKeys(ctx: Context): List<Cred> {
        val f = credFile(ctx)
        if (!f.isFile) return emptyList()
        return runCatching {
            val out = ArrayList<Cred>()
            var section = ""          // "" | "refs" | "records"
            var curRef: String? = null
            var curKind: String? = null
            fun flush() {
                val ref = curRef ?: return
                if (curKind == "api-key") out.add(Cred(ref, friendly(ref)))
            }
            for (raw in f.readLines()) {
                val line = raw.trimEnd()
                when {
                    // 段头（无缩进 + 结尾冒号）
                    !line.startsWith(" ") && line.endsWith(":") -> {
                        flush(); curRef = null; curKind = null
                        section = line.removeSuffix(":").trim()
                    }
                    section == "refs" -> {
                        // refs 段：2 空格缩进的 `NAME: value`（值不读取，只用键名）
                        if (line.startsWith("  ") && !line.startsWith("    ") && line.contains(":")) {
                            val name = line.trim().substringBefore(":").trim()
                            if (name.isNotEmpty()) out.add(Cred(name, friendly(name)))
                        }
                    }
                    section == "records" -> {
                        when {
                            // 二级记录键（恰好 2 空格缩进 + 结尾冒号）
                            line.startsWith("  ") && !line.startsWith("    ") && line.endsWith(":") -> {
                                flush()
                                curRef = line.trim().removeSuffix(":")
                                curKind = null
                            }
                            // 记录内字段（4+ 空格缩进）
                            curRef != null && line.trimStart().startsWith("kind:") -> {
                                curKind = line.substringAfter("kind:").trim()
                            }
                        }
                    }
                }
            }
            flush()
            out.distinctBy { it.ref }
        }.getOrElse {
            Log.w(TAG, "credentials parse failed: ${it.message}")
            emptyList()
        }
    }

    /** 友好显示名：DEEPSEEK_API_KEY → DeepSeek 官方；识别不了则原样返回 */
    fun friendly(ref: String): String {
        val u = ref.uppercase()
        return when {
            u.contains("DEEPSEEK") -> "DeepSeek 官方"
            u.contains("OPENAI") -> "OpenAI"
            u.contains("ANTHROPIC") || u.contains("CLAUDE") -> "Anthropic"
            u.contains("GEMINI") || u.contains("GOOGLE") -> "Google"
            u.contains("QWEN") || u.contains("DASHSCOPE") -> "通义千问"
            u.contains("GLM") || u.contains("ZHIPU") -> "智谱 GLM"
            u.contains("MOONSHOT") || u.contains("KIMI") -> "Kimi"
            else -> ref
        }
    }

    /** 默认选择：优先含 DEEPSEEK 的（官方响应最快），否则第一个；无则 null */
    fun defaultRef(ctx: Context): String? {
        val list = listApiKeys(ctx)
        return list.firstOrNull { it.ref.uppercase().contains("DEEPSEEK") }?.ref
            ?: list.firstOrNull()?.ref
    }
}
