package app.dsh.mobile.engine

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 壳侧注入规则（v1.2.122+）。
 *
 * 目的：把「往页面里补什么样式」从 APK 代码里挪出来，变成可改的文件。
 * 以后调 ds-bot 的布局/主题补丁只需要「改文件 + 重启 App」，不用重新打包安装。
 *
 * 读取顺序（共享优先，私有是出厂默认）：
 *   1. `/storage/emulated/0/DSH/deepbot/inject.json` ← 共享目录，电脑/文件管理可直接改
 *   2. `$DSH_HOME/deepbot/inject.json`               ← App 私有，首次运行写出的默认内容
 * 共享文件存在即生效（没授权读存储/文件损坏时自动退回私有），
 * 这样「改共享文件 + 重启 App」就能调样式，不必重装 APK。
 *
 * 结构：
 * {
 *   "version": 1,
 *   "probe": false,   // true 时额外打一条布局测量日志（tag DshWebView，前缀 [dsh-android]）
 *   "css": ["…"],     // 写进 <style id="dsh-inset-fix">，随「底部安全区适配」开关生效
 *   "js":  ["…"]      // 样式写完后执行一次，可选；会重复执行，必须写成幂等
 * }
 *
 * 文件不存在 → 首次运行时把默认内容写进私有目录（之后永不覆盖，用户改过的版本优先）；
 * 文件损坏/两个数组都为空 → 退回内置默认，不让页面因为一个坏文件变裸奔。
 */
object InjectSpec {

    /** 内置默认规则集的版本号：私有文件版本低于它就自动迁移（v1.2.122） */
    const val DEFAULT_VERSION = 5

    /**
     * 内置默认规则（三条）：
     *  1. 把 ds-bot 底栏的 56px 还给 DSH 的会话列；
     *  2. 键盘弹起时输入框贴键盘上沿（底栏留在键盘下，产品决定）；
     *  3. 收起的左侧抽屉不再往屏内渗阴影/描边；
     * （v5 起不再内置 sidebarCol / centerCol 的容器规则：上游已用客户端插件
     *  `assets/android-plugins/narrow-sidebar/` 解决「对话流被挤压」，走 dsh 官方
     *  插件发现机制，卸载自清理、类名哈希也不会失效。容器宽度一律交给插件。）
     *
     * 第 3 条不是把抽屉 display:none —— `.bt-side.bt-rail` 本身就是手机上的抽屉
     * （sidebar.js:433，打开时靠 `[data-mobile-open]` 滑进来），隐藏了就没法打开。
     * 它关闭时已经 `transform:translateX(-105%)` 移到屏外（styles.js:246），
     * 露出来的那几像素是它自己的 `box-shadow` + `border-right`，所以只清这两样。
     */
    val DEFAULT_RULES: List<String> = listOf(
        "html[data-bt-mobile][data-bt-portrait] [class*=\"centerCol\"]{" +
            "padding-bottom:calc(56px + max(env(safe-area-inset-bottom,0px)," +
            "var(--dsh-android-system-bottom,0px),var(--dsh-android-ime-bottom,0px)))!important}",
        "html[data-bt-mobile][data-bt-portrait][data-bt-keyboard] [class*=\"centerCol\"]{" +
            "padding-bottom:calc(var(--dsh-android-ime-bottom,0px))!important}",
        "html[data-bt-mobile] .bt-side.bt-rail:not([data-mobile-open]){" +
            "box-shadow:none!important;border-right:0!important}",

    )

    /**
     * 历史版本内置过、现在已废弃的规则（v1.2.122）。
     *
     * 迁移私有 inject.json 时把这些**剔除**：只追加不删除的话，旧规则会留在文件里，
     * 而同样特异度下后出现的规则胜出——v2 那条 `display:none`（只隐不撑）就是这么
     * 把会话列压成 56px 的。只按**字面完全相等**匹配，用户自己写的规则不会被误删。
     */
    private val RETIRED_RULES: List<String> = listOf(
        // v2：只隐藏侧栏、没让会话列接回宽度
        "html[data-bt-mobile] [class*=\"sidebarCol\"]{display:none!important}",
        // v3：width:100% 在绝对/栅格布局下无效，已被 v4 那条取代
        "html[data-bt-mobile] [class*=\"centerCol\"]{" +
            "flex:1 1 auto!important;width:100%!important;min-width:0!important}",
        // v4：靠 CSS 硬撑容器；v5 起交给 narrow-sidebar 插件，不再内置
        "html[data-bt-mobile] [class*=\"sidebarCol\"]{" +
            "display:none!important;width:0!important;min-width:0!important}",
        "html[data-bt-mobile] [class*=\"centerCol\"]{" +
            "left:0!important;right:0!important;width:auto!important;margin-left:0!important;" +
            "grid-column:1/-1!important;flex:1 1 auto!important;min-width:0!important}",
    )

    /** 拼成一段 CSS（写进 <style id="dsh-inset-fix">） */
    val DEFAULT_CSS: String = DEFAULT_RULES.joinToString("\n")

    /** 共享目录里的同级文件（用户可直接编辑，不碰 App 私有目录） */
    private const val SHARED = "/storage/emulated/0/DSH/deepbot/inject.json"

    /**
     * 壳侧常驻注入脚本（v1.3.3）：「我的」页里加一行壳设置入口。
     *
     * 为什么走注入而不是改 ds-bot：用户要求不碰 ds-bot 源码，卸载/升级 ds-bot
     * 都不该影响这个入口。脚本自己幂等（`__deepbotShellEntry` 哨兵 + 行 id 哨兵），
     * 靠 MutationObserver 应对路由切换与重渲染。
     *
     * 行的样式**复用 ds-bot 自己的**（bt-settings-item / bt-settings-shell /
     * bt-settings-name / bt-set-go），图标与箭头从同卡片已有行 clone，
     * 不自造样式，也不受类名哈希变化影响。
     */
    private val SHELL_ENTRY_JS = """
(function(){
  if (window.__deepbotShellEntry) return;
  if (!window.DeepBotShell) return;
  window.__deepbotShellEntry = 1;
  var ROW_ID = 'deepbot-shell-row';
  function buildRow(tpl){
    var btn = document.createElement('button');
    btn.type = 'button';
    btn.id = ROW_ID;
    btn.className = 'bt-settings-item bt-settings-shell';
    btn.title = 'DeepBot shell settings: status bar, injection, zoom, permissions';
    var ic = tpl ? tpl.querySelector('svg') : null;
    if (ic) btn.appendChild(ic.cloneNode(true));
    var name = document.createElement('span');
    name.className = 'bt-settings-name';
    name.textContent = 'DeepBot 设置';
    btn.appendChild(name);
    var go = tpl ? tpl.querySelector('.bt-set-go') : null;
    if (go) btn.appendChild(go.cloneNode(true));
    btn.addEventListener('click', function(ev){
      ev.preventDefault(); ev.stopPropagation();
      try { window.DeepBotShell.openSettings(); } catch (e) {}
    });
    return btn;
  }
  // 回报一次就够：state.json 的 lastBridgeEvent 里能分清「桥没挂」「插入点没找到」
  var missReported = 0;
  function report(msg){ try { window.DeepBotShell.report(msg); } catch (e) {} }
  function tick(){
    if (!document.documentElement.hasAttribute('data-bt-mobile')) return;
    if (!document.querySelector('.bt-settings-layer')) {
      if (!missReported) { missReported = 1; report('entryMiss:noSettingsLayer'); }
      return;
    }
    if (document.getElementById(ROW_ID)) return;
    var cards = document.querySelectorAll('.bt-settings-group-card');
    if (!cards.length) {
      if (!missReported) { missReported = 1; report('entryMiss:noGroupCard'); }
      return;
    }
    var card = cards[cards.length - 1];
    var tpl = card.querySelector('.bt-settings-shell') || card.querySelector('.bt-settings-item');
    card.appendChild(buildRow(tpl));
    report('entryInserted:cards=' + cards.length + ':tpl=' + (tpl ? '1' : '0'));
  }
  function schedule(){ setTimeout(tick, 0); }
  try { new MutationObserver(schedule).observe(document.body, {childList:true, subtree:true}); } catch(e){}
  tick();
})();
    """.trimIndent()

    /** 注入时总是追加执行的脚本（与 inject.json 的 js 合并，不受用户文件影响） */
    val DEFAULT_JS: List<String> = listOf(SHELL_ENTRY_JS)


    /** 一次注入要用的内容（已按文件解析好） */
    /** source：命中的文件来源，shared / private / builtin —— 排查「改了不生效」时第一眼要看它 */
    class Spec(val css: List<String>, val js: List<String>, val probe: Boolean, val source: String)

    /** `$DSH_HOME/deepbot/inject.json`（App 私有，兜底） */
    fun file(ctx: Context): File = File(EngineConfig.dshHome(ctx), "deepbot/inject.json")

    /** 共享目录的同级文件（首选，存在即生效） */
    fun sharedFile(): File = File(SHARED)

    /**
     * 每次调用都重新读文件（v1.2.122）：页面每加载一次读一次，
     * 改完 inject.json 切个会话/刷新页面就生效，不必重启 App。
     * 文件很小（几百字节），读盘代价可以忽略。
     */
    fun load(ctx: Context): Spec {
        val shared = sharedFile()
        val priv = file(ctx)
        val loaded = read(shared, "shared")
            ?: read(priv, "private")
            ?: Spec(listOf(DEFAULT_CSS), emptyList(), true, "builtin")
        // 壳侧常驻脚本总是追加（v1.3.3）：「我的」页的壳设置入口不该被用户文件挤掉，
        // 也不该因为共享文件读不到就消失。脚本自己幂等。
        val spec = if (loaded.js.any { it.contains("__deepbotShellEntry") }) loaded
        else Spec(loaded.css, loaded.js + DEFAULT_JS, loaded.probe, loaded.source)
        // 来源 + 路径打一行（只在来源/路径变化时打，避免每页刷屏）：
        // 「我改了共享文件为什么不生效」第一眼看这行就知道读的是哪份。
        val path = when (spec.source) {
            "shared" -> shared.absolutePath
            "private" -> priv.absolutePath
            else -> "(builtin default)"
        }
        // 共享文件读不到是最常见的「改了不生效」原因（没给存储权限时 isFile 直接 false），
        // 所以把共享路径是否可读一并打出来，省得再猜。
        val sharedReadable = shared.canRead()
        val stamp = spec.source + "|" + path + "|" + sharedReadable
        if (stamp != lastLogged) {
            lastLogged = stamp
            android.util.Log.i(
                "DshWebView",
                "inject source=${spec.source} path=$path " +
                    "css=${spec.css.size} js=${spec.js.size} probe=${spec.probe}" +
                    " sharedPath=${shared.absolutePath} sharedReadable=$sharedReadable"
            )
        }
        return spec
    }

    /** 上一次打过的来源+路径，避免每次页面加载都刷同一条 */
    private var lastLogged: String? = null

    /**
     * 实际生效文件的 `version` 字段（两个文件都没有 → 内置版本号）。
     *
     * 只给体检文件用：`inject source=shared` 但 `version=1` 这种「读到了旧文件」的情况，
     * 光看来源看不出来，得把版本一起摆出来。v7 起 version 只影响**私有文件迁移**，
     * 不再决定内置规则是否生效（内置规则永远生效）。
     */
    fun fileVersion(ctx: Context): Int {
        val f = if (sharedFile().isFile) sharedFile() else file(ctx)
        if (!f.isFile) return DEFAULT_VERSION
        return runCatching { JSONObject(f.readText()).optInt("version", 0) }.getOrDefault(0)
    }

    /**
     * 保证私有 inject.json 与内置默认规则集同版本（v1.2.122）。
     *
     * 两种情形：
     *  - 文件不存在 → 写一份出厂默认；
     *  - 文件存在但 `version` 低于 [DEFAULT_VERSION] → **迁移**：内置规则在前，
     *    用户自己加过的规则按原顺序追加在后（去重），`js`/`probe` 原样保留。
     *
     * 为什么要有迁移：App 若无「所有文件访问」权限，共享目录那份读不到，会静默回退到
     * 私有文件；而私有文件是**首次安装时**写死的，新增的默认规则永远进不去
     * （实测进过一次：`inject source=private … css=1`，sidebarCol 那条没在）。
     */
    fun ensureDefault(home: File) {
        runCatching {
            val f = File(home, "deepbot/inject.json")
            f.parentFile?.mkdirs()
            if (!f.isFile || f.length() == 0L) {
                f.writeText(freshJson())
                return
            }
            val root = runCatching { JSONObject(f.readText()) }.getOrNull()
            if (root == null) {
                f.writeText(freshJson())   // 坏文件：整份重写，接口约定里写明了
                return
            }
            if (root.optInt("version", 0) >= DEFAULT_VERSION) return

            // 迁移：内置规则优先，用户自定义的规则跟在后面
            val rawUserRules = root.optJSONArray("css").toStringList()
            val userRules = rawUserRules.filterNot { it.trim() in RETIRED_RULES }
            val retired = rawUserRules.size - userRules.size
            val merged = LinkedHashSet<String>(DEFAULT_RULES)
            merged.addAll(userRules)
            val css = JSONArray()
            merged.forEach { css.put(it) }
            root.put("version", DEFAULT_VERSION)
            root.put("css", css)
            if (!root.has("probe")) root.put("probe", true)
            if (!root.has("js")) root.put("js", JSONArray())
            f.writeText(root.toString(2) + "\n")
            android.util.Log.i(
                "DeepBotSeed",
                "inject.json migrated to v$DEFAULT_VERSION (user ${userRules.size} kept, " +
                    "retired ${retired}, total ${merged.size})"
            )
        }
    }

    /** 出厂默认内容 */
    private fun freshJson(): String {
        val css = JSONArray()
        DEFAULT_RULES.forEach { css.put(it) }
        return JSONObject()
            .put("version", DEFAULT_VERSION)
            .put("probe", true)
            .put("css", css)
            .put("js", JSONArray())
            .toString(2) + "\n"
    }

    private fun read(f: File, source: String): Spec? = runCatching {
        if (!f.isFile) return null
        val root = JSONObject(f.readText())
        val css = root.optJSONArray("css").toStringList()
        val js = root.optJSONArray("js").toStringList()
        // probe 缺省为开：本轮装机要拿顶部占位/侧栏的实测数据，用户可在文件里写 false 关掉
        if (css.isEmpty() && js.isEmpty()) null
        else Spec(css, js, root.optBoolean("probe", true), source)
    }.getOrNull()

    private fun JSONArray?.toStringList(): List<String> {
        if (this == null) return emptyList()
        val out = ArrayList<String>(length())
        for (i in 0 until length()) {
            val s = optString(i, "")
            if (s.isNotBlank()) out.add(s)
        }
        return out
    }
}
