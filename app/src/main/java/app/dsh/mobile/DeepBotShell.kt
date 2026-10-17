package app.dsh.mobile

import android.graphics.Color
import android.os.Build
import android.view.View
import android.webkit.WebView
import app.dsh.mobile.engine.InjectSpec
import app.dsh.mobile.engine.EngineConfig
import app.dsh.mobile.engine.ShellState
import org.json.JSONObject
import org.json.JSONTokener

/**
 * DeepBot 壳层（v1.3.0）：状态栏、安全区、注入。
 *
 * 之所以单独成文件：合并上游时 `MainActivity` 会被重写，我们每次都得手工重放一遍
 * 760 行改动（本轮就是这么丢的）。把壳层收在这里，`MainActivity` 只留几个调用点，
 * 上游再怎么改主界面，这块基本不用动。
 *
 * 职责（全是我们自己的东西，上游没有）：
 *  - edge-to-edge 三档状态栏（透明 / 主题色 / 跟随系统）
 *  - 系统栏 inset → CSS 变量（**物理 px ÷ 密度 = CSS px** 下发）
 *  - inject.json 注入（共享优先、私有兜底、版本迁移在 [InjectSpec] 里）
 *  - 品牌名替换（DS Bot → DeepBot）
 *  - 壳内悬浮层（把手/工具栏/进度条）避让系统栏
 */
class DeepBotShell(
    private val activity: MainActivity,
    private val web: () -> WebView?,
) {
    var statusBarMode: StatusBarMode = StatusBarMode.TRANSPARENT
        private set

    private var insetTopPx = 0
    private var insetBottomPx = 0
    private var insetImePx = 0
    private var lastSource = ""

    private val res get() = activity.resources

    /** 有效 CSS 密度：副屏把 density 报成 1 时退回系统主屏密度 */
    fun density(): Float {
        val cur = (web()?.resources ?: res).displayMetrics.density
        val sys = android.content.res.Resources.getSystem().displayMetrics.density
        return when {
            cur <= 0f -> sys.takeIf { it > 0f } ?: 1f
            sys > cur * 1.05f -> sys
            else -> cur
        }
    }

    fun install() {
        val root = activity.findViewById<View>(android.R.id.content) ?: return
        root.setOnApplyWindowInsetsListener { _, insets ->
            val top: Int
            val bottom: Int
            var ime = 0
            if (Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(android.view.WindowInsets.Type.systemBars())
                top = bars.top
                bottom = bars.bottom
                ime = insets.getInsets(android.view.WindowInsets.Type.ime()).bottom
            } else {
                @Suppress("DEPRECATION")
                top = insets.systemWindowInsetTop
                @Suppress("DEPRECATION")
                bottom = insets.systemWindowInsetBottom
            }
            if (top != insetTopPx || bottom != insetBottomPx || ime != insetImePx) {
                insetTopPx = top
                insetBottomPx = bottom
                insetImePx = ime
                applyShellInsets()
                inject()
            }
            insets
        }
    }

    /** 三档状态栏：透明 / 主题色走 edge-to-edge；跟随系统则保持默认布局 */
    fun applyStatusBar() {
        statusBarMode = StatusBarPrefs.get(activity)
        val edge = statusBarMode != StatusBarMode.SYSTEM
        val lightIcons = !ThemeHelper.isDark(activity)
        val barColor = if (statusBarMode == StatusBarMode.TRANSPARENT) Color.TRANSPARENT
        else activity.getColor(R.color.ds_bg_base)
        val navColor = if (edge) Color.TRANSPARENT else barColor
        val w = activity.window
        if (Build.VERSION.SDK_INT >= 30) {
            w.setDecorFitsSystemWindows(!edge)
            w.statusBarColor = barColor
            w.navigationBarColor = navColor
            w.insetsController?.let { c ->
                val mask = android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                    android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
                c.setSystemBarsAppearance(if (lightIcons) mask else 0, mask)
            }
        } else {
            var vis = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            if (edge) vis = vis or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            if (lightIcons) vis = vis or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or
                View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
            w.decorView.systemUiVisibility = vis
            w.statusBarColor = barColor
            w.navigationBarColor = navColor
        }
        activity.findViewById<View>(android.R.id.content)?.requestApplyInsets()
        inject()
    }

    /** 下发 CSS 变量 + 注入 inject.json 规则 + 品牌替换 */
    fun inject() {
        val view = web() ?: return
        val d = density()
        val top = if (statusBarMode == StatusBarMode.SYSTEM) 0 else Math.round(insetTopPx / d)
        val bottom = if (statusBarMode == StatusBarMode.SYSTEM) 0 else Math.round(insetBottomPx / d)
        val ime = if (statusBarMode == StatusBarMode.SYSTEM) 0 else Math.round(insetImePx / d)
        val fix = InsetFixPrefs.isEnabled(activity)
        val spec = InjectSpec.load(activity)
        if (spec.source != lastSource) {
            lastSource = spec.source
            activity.logWebView("inject source=${spec.source} css=${spec.css.size} js=${spec.js.size} probe=${spec.probe}")
        }
        val css = if (fix) spec.css.joinToString("\n") else ""
        val extra = if (fix) spec.js.joinToString("\n") else ""
        val probe = if (fix && spec.probe) PROBE_JS else ""
        val patch = if (css.isEmpty()) "" else
            "var s=document.createElement('style');s.id=id;" +
                "s.textContent=${JSONObject.quote(css)};(document.head||d).appendChild(s);"
        val js = "(function(){var d=document.documentElement;if(!d)return;" +
            "d.style.setProperty('--dsh-mobile-top-inset','${top}px');" +
            "d.style.setProperty('--dsh-mobile-bottom-inset','${bottom}px');" +
            "d.style.setProperty('--dsh-android-system-top','${top}px');" +
            "d.style.setProperty('--dsh-android-system-bottom','${bottom}px');" +
            "d.style.setProperty('--dsh-android-ime-bottom','${ime}px');" +
            "d.style.setProperty('--dsh-sab','${bottom}px');" +
            "d.style.setProperty('--dsh-safe-top','${top}px');" +
            "d.style.setProperty('--dsh-safe-bottom','${bottom}px');" +
            "var id='dsh-inset-fix';var old=document.getElementById(id);" +
            "if(old)old.remove();" + patch + extra + probe + "})();"
        runCatching { view.evaluateJavascript(js, null) }
        runCatching { view.evaluateJavascript(BRAND_JS, null) }
        writeState(spec, top, bottom)
    }

    /**
     * 壳侧体检（v1.3.2，永久设施）：把壳知道的事实 + 页面量到的数写进
     * `/storage/emulated/0/DSH/deepbot/state.json`，见 [app.dsh.mobile.engine.ShellState]。
     *
     * 挂在注入之后：此时 CSS 变量与该次规则已经下发，页面量到的就是**生效后**的结果。
     * 量取是异步回调，失败只写壳侧字段，不影响任何界面行为。
     */
    private fun writeState(spec: InjectSpec.Spec, topCss: Int, bottomCss: Int) {
        val view = web() ?: return
        val base = LinkedHashMap<String, Any?>()
        base["enginePort"] = EngineConfig.activePort
        base["injectSource"] = spec.source
        base["injectVersion"] = InjectSpec.fileVersion(activity)
        base["builtinVersion"] = InjectSpec.DEFAULT_VERSION
        base["ruleCount"] = spec.css.size
        base["jsCount"] = spec.js.size
        base["probeOn"] = spec.probe
        base["statusBarMode"] = statusBarMode.name
        base["insetTopCss"] = topCss
        base["insetBottomCss"] = bottomCss
        base["screenWidthDp"] = activity.resources.configuration.screenWidthDp
        base["screenHeightDp"] = activity.resources.configuration.screenHeightDp
        base["densityDpi"] = activity.resources.configuration.densityDpi
        base["plugins"] = ShellState.deployedPlugins(activity)
        base["pluginsNote"] = "壳侧部署清单（引擎目录 android-plugins），不等于运行期已挂载"
        // 先落一版壳侧字段：页面量取是异步回调，量不到时文件里也至少有壳知道的事实
        ShellState.write(activity, base)
        runCatching {
            view.evaluateJavascript(MEASURE_JS) { raw ->
                val measured = runCatching {
                    JSONObject(JSONTokener(raw).nextValue() as String)
                }.getOrNull()
                if (measured != null) {
                    for (k in measured.keys()) base[k] = measured.get(k)
                }
                ShellState.write(activity, base)
            }
        }.onFailure {
            ShellState.write(activity, base)
        }
    }

    /** 壳内悬浮层避让：工具栏高度、进度条 marginTop、把手可拖范围 */
    fun applyShellInsets() {
        val top = insetTopPx.coerceAtLeast(0)
        activity.findViewById<View>(R.id.statusBarRow)?.let { row ->
            val lp = row.layoutParams
            val h = dp(40) + top
            if (lp.height != h) { lp.height = h; row.layoutParams = lp }
            if (row.paddingTop != top) row.setPadding(0, top, 0, 0)
        }
        activity.findViewById<View>(R.id.installProgress)?.let { bar ->
            val lp = bar.layoutParams as? android.view.ViewGroup.MarginLayoutParams ?: return@let
            val m = dp(40) + top
            if (lp.topMargin != m) { lp.topMargin = m; bar.layoutParams = lp }
        }
        clampHandle()
    }

    /** 把手默认停在状态栏下缘 */
    fun positionHandleDefault() {
        val h = activity.findViewById<View>(R.id.handleBar) ?: return
        h.translationY = handleYRange(h).first
    }

    /** 把手可停留区间（translationY 语义）：上不进状态栏，下不进手势条 */
    fun handleYRange(v: View): Pair<Float, Float> {
        val parent = v.parent as? View ?: return 0f to 0f
        val lp = v.layoutParams as? android.view.ViewGroup.MarginLayoutParams
        val base = (lp?.topMargin ?: 0).toFloat()
        val minY = insetTopPx + dp(2) - base
        val maxY = (parent.height - insetBottomPx.coerceAtLeast(0) - v.height) - base
        return minY to maxY.coerceAtLeast(minY)
    }

    private fun clampHandle() {
        val h = activity.findViewById<View>(R.id.handleBar) ?: return
        val parent = h.parent as? View ?: return
        if (parent.height <= 0) return
        val (minY, maxY) = handleYRange(h)
        h.translationY = h.translationY.coerceIn(minY, maxY)
    }

    private fun dp(v: Int): Int = (v * res.displayMetrics.density + 0.5f).toInt()

    companion object {

        /**
         * 页面侧量取（体检文件用）：视口、下发到 DOM 的 inset 变量、会话列实际内边距。
         * 与 PROBE_JS 的区别是它**不在页面里常驻**，只在注入后取一次数，结果直接落
         * state.json，不依赖 logcat。
         */
        private val MEASURE_JS = """
(function(){try{
  var d=document.documentElement;
  var cc=document.querySelector('[class*="centerCol"]');
  var cs=cc?getComputedStyle(cc):null,r=cc?cc.getBoundingClientRect():null;
  return JSON.stringify({
    dpr:window.devicePixelRatio,
    innerWidth:window.innerWidth,
    innerHeight:window.innerHeight,
    clientWidth:d.clientWidth,
    topInset:getComputedStyle(d).getPropertyValue('--dsh-mobile-top-inset').trim(),
    ccPaddingTop:cs?cs.paddingTop:null,
    ccPaddingBottom:cs?cs.paddingBottom:null,
    ccTop:r?Math.round(r.top):null,
    ccW:r?Math.round(r.width):null,
    btMobile:d.getAttribute('data-bt-mobile'),
    btPortrait:d.getAttribute('data-bt-portrait'),
    dshFrame:d.hasAttribute('data-dsh-frame'),
    bridge:typeof window.DeepBotShell !== 'undefined',
    settingsLayer:!!document.querySelector('.bt-settings-layer'),
    entryInserted:!!document.getElementById('deepbot-shell-row'),
    groupCards:document.querySelectorAll('.bt-settings-group-card').length
  });
}catch(e){return JSON.stringify({measureError:String(e)})}})()
        """.trimIndent()
        /**
         * 品牌替换：把**整块文本恰好是 "DS Bot"** 的叶子节点改成 DeepBot，含 document.title。
         * ds-bot 源码不动（sidebar.js:822 BrandName 写死的），用 MutationObserver 兜 React 重渲染。
         */
        private val BRAND_JS = """
(function(){if(window.__deepbotBrand){return;}window.__deepbotBrand=true;
  var NAME='DeepBot';
  function fix(){var all=document.querySelectorAll('span,a,p,h1,h2,h3,div,strong,b');
    for(var i=0;i<all.length;i++){var e=all[i];if(e.childElementCount){continue;}
      if(String(e.textContent||'').trim()==='DS Bot'){e.textContent=NAME;}}
    if(document.title==='DS Bot'){document.title=NAME;}}
  var t=null;function s(){if(t){return;}t=setTimeout(function(){t=null;fix();},150);}
  fix();
  try{new MutationObserver(s).observe(document.documentElement,{childList:true,subtree:true});}catch(e){}
})();
        """.trimIndent()

        /** 布局测量（inject.json 的 probe 开关控制，缺省开） */
        private val PROBE_JS = """
(function(){
  function r(e){if(!e)return null;var b=e.getBoundingClientRect();
    return [Math.round(b.left),Math.round(b.top),Math.round(b.right),Math.round(b.bottom)];}
  function cs(e){if(!e)return null;var s=getComputedStyle(e);
    return {pos:s.position,left:s.left,right:s.right,width:s.width,flex:s.flex,
            disp:s.display,ml:s.marginLeft,gc:s.gridColumn};}
  function probe(){
    var d=document.documentElement,cc=document.querySelector('[class*="centerCol"]');
    var o={dpr:window.devicePixelRatio,cw:d.clientWidth,iw:innerWidth,ih:innerHeight,
      topInset:getComputedStyle(d).getPropertyValue('--dsh-mobile-top-inset').trim(),
      cc:r(cc),ccStyle:cs(cc),ccParent:cs(cc?cc.parentElement:null),
      sbStyle:cs(document.querySelector('[class*="sidebarCol"]'))};
    console.log('DSHPROBE2:'+JSON.stringify(o));
  }
  setTimeout(probe,600);setTimeout(probe,2500);
})();
        """.trimIndent()
    }
}
