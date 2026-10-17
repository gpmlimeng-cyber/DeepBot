package app.dsh.mobile

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.ViewStub
import android.view.ViewTreeObserver
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import app.dsh.mobile.engine.EngineSupervisor
import app.dsh.mobile.engine.InjectSpec
import app.dsh.mobile.engine.Privilege
import app.dsh.mobile.service.EngineService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * WebView 容器。
 *
 * UI 策略：不重写官方 WebUI（上游 developer preview 迭代快，追协议是无底洞），
 * 只做原生外壳 —— 引擎 Healthy 后加载 127.0.0.1 回环页面，状态条显示引擎生命周期。
 *
 * 设置入口：右上角 ⋯ 跳转独立设置页（SettingsActivity，MIUI 风格分组卡片），
 * 不再使用悬浮弹窗菜单。
 */
class MainActivity : Activity() {

    private lateinit var webView: WebView
    private lateinit var statusBar: TextView
    private var urlLoaded = false

    /** 桌面模式：桌面 UA + 固定 1280px 视口 + 手势缩放（手机浏览器「电脑模式」等价物） */

    /** 屏幕朝向偏好（v1.2.120）：portrait 锁竖 / landscape 锁横+桌面布局 / auto 跟随旋转 */
    private var orientationMode: OrientationMode = OrientationMode.PORTRAIT

    /** 页面缩放百分比（竖屏时应用；等价浏览器 Ctrl+/Ctrl-）。横屏桌面模式交给 1280px meta，不叠加 */
    private var pageScale = DEFAULT_PAGE_SCALE

    /** 本页当前生效的语言代码（onResume 比对，变了就重建） */
    private var appliedLocale: String = ""

    /** 本页当前生效的主题（onResume 比对，设置页改过就重建） */
    private var appliedTheme: ThemeMode = ThemeMode.SYSTEM

    // ---- 启动页（v1.2.121）----
    /** 主内容（WebView + 工具栏）是否已由 ViewStub 填进来；首帧之前为 false */
    private var mainReady = false
    /** onResume 里被推迟的引擎/门禁逻辑，等内容填好后补跑 */
    private var pendingResume = false
    /** 启动页是否已经淡出，避免重复动画 */
    private var splashDismissed = false
    /** WebView 首屏页面是否已加载完（与引擎 Healthy 一起决定何时淡出启动页） */
    private var pageReady = false
    /** 最近一次安装进度（0..1，null = 不确定） */
    private var lastProgress: Float? = null
    /** 最近一次引擎状态（启动页与主界面共用） */
    private var lastState: EngineSupervisor.State? = null
    /** Activity 创建时刻 / 进程启动时刻（算「点图标 → 首帧」） */
    private var splashAtMs = 0L
    private var splashProcessStartMs = 0L
    /** 引擎就绪时间戳只记一次（TTFD 对照用） */
    private var readyLogged = false

    // ---- 状态栏 / 安全区（v1.2.122）----
    private var statusBarMode: StatusBarMode = StatusBarMode.TRANSPARENT
    /** 注入规则来源（shared/private/builtin），只用于变化时打一行日志 */
    private var lastInjectSource: String = ""

    /** IME（软键盘）底部 inset，物理 px；edge-to-edge 下窗口不 resize，只能靠它补偿 */
    private var insetImePx = 0

    private var insetTopPx = 0
    private var insetBottomPx = 0

    private val uiScope = CoroutineScope(Dispatchers.Main)

    // ---- 顶部工具栏自动隐藏（v1.2.121）----
    /** 延迟收起的定时器；只在「就绪」那次跃迁上排队一次 */
    private val autoHideHandler = android.os.Handler(android.os.Looper.getMainLooper())
    /** 本轮是否仍允许自动收起（用户手动唤回后置 false，不再自动收） */
    private var autoHideArmed = true
    /** 用户是否手动唤回过工具栏（本轮不再自动收起） */
    private var userShowedToolbar = false
    /** 上一次 render 是否处于「已就绪」，用于只在状态跃迁时动作 */
    private var lastStateReady = false
    /** 延迟到点后执行收起；到点前用户唤回会取消它 */
    private val autoHideRunnable = Runnable {
        if (!userShowedToolbar) hideToolbar()
    }

    /** 待回传的文件选择结果（onShowFileChooser → onActivityResult 之间持有；null 表示无进行中请求） */
    private var pendingFileCallback: android.webkit.ValueCallback<Array<Uri>>? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(ThemeHelper.wrap(LocaleHelper.wrap(newBase)))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 首启保护：若用户尚未完成引导（如直接拉起 MainActivity），先跳 Onboarding
        if (!Privilege.isOnboarded(this)) {
            startActivity(Intent(this, OnboardingActivity::class.java))
            finish()
            return
        }
        // 首帧优先（v1.2.121）：这次 setContentView 只 inflate 启动页，
        // WebView 留在 ViewStub 里，等启动页画出第一帧后再填。
        setContentView(R.layout.activity_main)
        splashAtMs = SystemClock.uptimeMillis()
        splashProcessStartMs =
            if (Build.VERSION.SDK_INT >= 24) android.os.Process.getStartUptimeMillis() else splashAtMs
        Log.i("Splash", "onCreate +${splashAtMs - splashProcessStartMs}ms")

        // 记录本页生效的语言（onResume 里比对，设置页改过就重建本页）
        appliedLocale = LocaleHelper.get(this)
        appliedTheme = ThemePrefs.get(this)

        setupSplash()

        // 状态栏样式与安全区（v1.2.122）：透明/主题色走 edge-to-edge，
        // 高度通过 CSS 变量交给页面自己留白（ds-bot 的底栏/输入框按 --dsh-safe-bottom 排）
        installInsetsListener()
        applyStatusBar()

        // 读回用户保存的页面缩放与屏幕朝向偏好（都只读 SharedPreferences，轻量）
        readUiPrefs()
        applyOrientation(orientationMode)

        // 启动页画出第一帧后再填主内容：WebView 初始化不占首帧
        val splash = findViewById<View>(R.id.splashRoot)
        var drawListener: ViewTreeObserver.OnDrawListener? = null
        var firstFrameHandled = false
        drawListener = object : ViewTreeObserver.OnDrawListener {
            override fun onDraw() {
                if (mainReady || firstFrameHandled) return
                firstFrameHandled = true
                Log.i("Splash", "first frame +${SystemClock.uptimeMillis() - splashProcessStartMs}ms")
                // ⚠️ 硬性规则：onDraw 回调里不能同步 removeOnDrawListener，否则抛
                // IllegalStateException: Cannot call removeOnDrawListener inside of onDraw
                //（v1.2.121 首版就是在这里崩的）。必须 post 到下一帧再移除。
                splash.post {
                    runCatching {
                        drawListener?.let { splash.viewTreeObserver.removeOnDrawListener(it) }
                    }
                }
                // 结构变动同样放到本帧绘制之后
                splash.post { inflateMainContent() }
            }
        }
        splash.viewTreeObserver.addOnDrawListener(drawListener!!)

        val app = application as DshApp
        uiScope.launch {
            app.supervisor.state.collectLatest { render(it) }
        }
        uiScope.launch {
            app.supervisor.installProgress.collectLatest { renderProgress(it) }
        }
    }

    /**
     * 主内容延迟填充（v1.2.121）：启动页第一帧画完之后才 inflate WebView 与工具栏，
     * 再把原先 onCreate 里的绑定逻辑补上。这样冷启动首帧只承担启动页的布局成本。
     */
    private fun inflateMainContent() {
        if (mainReady) return
        findViewById<ViewStub>(R.id.mainContentStub)?.inflate()
        mainReady = true

        statusBar = findViewById(R.id.statusBar)
        // 先赋值字段再配置：setupWebView 内部读取的是 this.webView，
        // 若写在 apply{} 里会在赋值完成前执行而触发 UninitializedPropertyAccessException。
        webView = findViewById<WebView>(R.id.webView)
        setupWebView()

        // 热重启：用户显式动作，完整 stop→start 链路；urlLoaded 复位让 Healthy 后重载 3080。
        findViewById<TextView>(R.id.btnRestart).setOnClickListener {
            urlLoaded = false
            (application as DshApp).supervisor.restart()
            Toast.makeText(this, getString(R.string.engine_restarting), Toast.LENGTH_SHORT).show()
        }
        // 隐藏工具栏：一键收起让网页全屏（点顶部小把手唤回）
        findViewById<TextView>(R.id.btnHide).setOnClickListener { toggleToolbar() }
        // 齿轮按钮：跳转独立设置页
        findViewById<View>(R.id.btnMore).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        // 预览模式返回：一键从 AI 起的服务页回引擎主界面
        findViewById<TextView>(R.id.btnBack).setOnClickListener {
            loadLocalUrl((application as DshApp).supervisor.webUrl())
        }
        // 工具栏收起/唤回：点横栏文字空白区收起（网页全屏）
        statusBar.setOnClickListener { toggleToolbar() }
        // 把手：可拖到屏幕边缘任意位置（避让遮挡）；移动距离小于阈值视为点击唤回工具栏
        setupHandleBar()
        // 壳内悬浮层避让系统栏（把手/工具栏/进度条），首帧前 inset 可能还没到
        applyShellInsets()

        // 语音输入（v1.2.65）：这里只注册「把文字送进 WebUI」的能力
        VoiceBridge.registerSender { text -> sendVoiceText(text) }

        // 把当前状态同步到主界面（启动页期间收到的状态不会丢）
        lastState?.let { render(it) }
        lastProgress?.let { renderProgress(it) }

        // onResume 若在内容就绪前已经跑过（冷启动一定是），这里补跑被推迟的部分
        if (pendingResume) {
            pendingResume = false
            onResumeHeavy()
        }
    }

    override fun onResume() {
        super.onResume()
        AppVisibility.onResume()
        // 设置页可能改过状态栏样式：返回时重新应用（不重启 Activity）
        applyStatusBar()
        // 🔴 v1.2.91：恢复 WebView 的 JS 定时器。
        // 主人实测「回到后台时通过语音发送消息，消息发出去了但模型不响应，
        // 切回前台才开始响应」—— 根因就是这里缺 webView.onResume()：
        // Android 在 Activity 进入后台时会**冻结 WebView 的 JS 定时器**
        // （前端靠定时器/轮询驱动流式渲染），所以后台期间 AI 的回复渲染不出来。
        if (::webView.isInitialized) {
            runCatching { webView.onResume() }
            runCatching { webView.resumeTimers() }
        }
        // 首帧优先（v1.2.121）：主内容还没填进来时，门禁检测（会创建 WebView）与
        // 拉起引擎都推迟到启动页画完之后；冷启动一定是这条路径。
        if (!mainReady) {
            pendingResume = true
            return
        }
        onResumeHeavy()
    }

    /** onResume 里的重活（门禁/引擎/看门狗/偏好同步），首帧之后才允许跑 */
    private fun onResumeHeavy() {
        // Web 内核版本门禁（v1.2.100，酷安用户反馈引出的需求）：
        // WebView < Chrome 85 时引擎前端必白屏（??= 等解析期语法 polyfill 补不了），
        // 与其让用户看「进程异常退出/白屏」无限循环，不如先检测、给出明确升级指引，
        // 由用户决定要不要继续。见 checkWebViewGate()。
        if (AppGate.webViewState != AppGate.State.OK &&
            AppGate.webViewState != AppGate.State.OVERRIDDEN && !checkWebViewGate()
        ) {
            return   // 门禁未过：不启动引擎（引擎起了前端也渲染不了，纯粹耗电）
        }
        // 前台进入即拉起前台服务；服务存在则幂等
        EngineService.start(this)
        // 页面卡死看门狗（v1.2.100）：前台时才检测（后台由系统节流，探针会误判）
        startHangWatchdog()
        // 从设置页返回：语言若被改过，原地重建本页（设置页只重建了它自己，
        // 主界面返回时也得跟上，否则会出现「设置页已是新语言、主界面还是旧的」）
        val langNow = LocaleHelper.get(this)
        if (langNow != appliedLocale) {
            appliedLocale = langNow
            recreate()
            return
        }
        // 从设置页返回：主题若被改过，同样原地重建（重走 attachBaseContext → 换色板）
        val themeNow = ThemePrefs.get(this)
        if (themeNow != appliedTheme) {
            appliedTheme = themeNow
            recreate()
            return
        }
        // 从设置页返回：点过「重新加载注入」就重载一次页面（v1.2.122）。
        // 注入规则每次页面加载都会重读 inject.json，所以重载 = 新规则生效。
        if (InjectReloadPrefs.consume(this) && ::webView.isInitialized) {
            webView.reload()
        }
        // 从设置页返回：重新读取朝向/缩放偏好，若被改则同步并重载
        val oldScale = pageScale
        val oldMode = orientationMode
        readUiPrefs()
        if (oldMode != orientationMode) {
            applyOrientation(orientationMode)
        }
        if (oldScale != pageScale || oldMode != orientationMode) {
            applyPageScale()
            webView.reload()
        }
    }

    /**
     * v1.2.91：**不**暂停 WebView。
     *
     * 默认实现（或主动调 `webView.onPause()`）会冻结 JS 定时器 —— 那正是
     * 「后台时 AI 不响应、切回前台才响应」的根因。
     * 本项目的前端需要**在后台继续驱动**（AI 回复的流式渲染、会话状态更新），
     * 而进程本身已由前台服务 + 无障碍服务保活，所以这里保持 WebView 活跃。
     *
     * ⚠️ 代价：后台时 WebView 仍会消耗少量 CPU（前端本身的轮询）。
     * 这是「后台也要能收到 AI 回复」这个需求的必要成本。
     */
    override fun onPause() {
        AppVisibility.onPause()
        // 后台停看门狗：系统会节流后台 WebView，探针超时会造成误判并反复重建页面
        stopHangWatchdog()
        // 刻意不调用 super.onPause() 里的 WebView 暂停逻辑；
        // 同时显式 resumeTimers，确保切后台瞬间定时器仍在跑。
        if (::webView.isInitialized) {
            runCatching { webView.resumeTimers() }
        }
        super.onPause()
    }

    /** 读取用户持久化的屏幕朝向与缩放偏好（设置页与首次引导共用同一组 prefs） */
    private fun readUiPrefs() {
        val p = getSharedPreferences(PREFS_UI, MODE_PRIVATE)
        pageScale = p.getInt(KEY_PAGE_SCALE, DEFAULT_PAGE_SCALE)
        orientationMode = OrientationPrefs.get(this)
    }

    private fun setupWebView() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false       // 关闭文件域，收窄 WebView 攻击面
            allowContentAccess = false
            javaScriptCanOpenWindowsAutomatically = false
            // v1.2.122：视口恒定走手机。useWideViewPort=false 表示**忽略页面的 viewport meta**，
            // 布局宽永远等于 WebView 宽度（本机 = 424 CSS px），980 那条桌面宽度就不会再回来。
            // 代价是 meta 里的 initial-scale 不再生效，所以页面缩放改由 setInitialScale() 下发
            // （见 loadLocalUrl / applyPageScale）。
            useWideViewPort = false
            loadWithOverviewMode = false
            applyZoomControls(false)
        }
        // ── WebView 诊断（v1.2.49）：用户报「用不了」时能拿到确凿信息 ────────────
        // 背景：华为 Mate40 报 `AbortSignal.any is not a function`、另有 Android 16 用户
        // 报失败，但 App 此前既不捕获 WebView 错误也不转发 console，排查只能靠猜。
        // 这里把 JS 控制台消息、资源加载错误、以及 polyfill/WebView 版本自检全部落进
        // engine.log（用户可在设置里导出），日志里带 [webview] 前缀便于定位。
        webView.webChromeClient = object : android.webkit.WebChromeClient() {
            override fun onConsoleMessage(msg: android.webkit.ConsoleMessage?): Boolean {
                msg ?: return false
                val text = msg.message() ?: return false
                // 只记有价值的信息：错误/警告，或我们自己的自检标记
                val important = msg.messageLevel() >= android.webkit.ConsoleMessage.MessageLevel.WARNING ||
                    text.contains("[dsh-android]") || text.startsWith("DSHPROBE2:")
                if (important) {
                    logWebView("console/${msg.messageLevel()} ${msg.sourceId()}:${msg.lineNumber()} $text")
                }
                return false
            }

            /**
             * 网页 <input type="file"> / WebUI 附件按钮 → 系统文件选择器。
             *
             * ⚠️ 2026-10-05（Issue #5）：此前**未实现**此回调，WebView 收到文件选择请求时
             * 无人应答，表现为「上传什么都失败、也弹不出选择窗口」（WebUI 附件功能整体不可用）。
             * Android WebView 不会自行弹选择器，必须由宿主实现本方法并通过
             * filePathCallback.onReceiveValue() 回传结果 —— 不回传则页面永远等待。
             *
             * 权限：用 ACTION_GET_CONTENT（SAF）而非直接读路径，**无需**存储权限，
             * 也不受 allowFileAccess=false 影响（我们刻意关着它收窄攻击面）。
             */
            override fun onShowFileChooser(
                view: WebView?,
                filePathCallback: android.webkit.ValueCallback<Array<Uri>>?,
                params: android.webkit.WebChromeClient.FileChooserParams?,
            ): Boolean {
                filePathCallback ?: return false
                // 上一个请求未结束（用户连点/页面切换）→ 先回传空值释放，否则 WebView 卡死
                pendingFileCallback?.onReceiveValue(null)
                pendingFileCallback = filePathCallback
                val intent = runCatching { params?.createIntent() }.getOrNull()
                    ?: Intent(Intent.ACTION_GET_CONTENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "*/*"
                    }
                return runCatching {
                    @Suppress("DEPRECATION")
                    startActivityForResult(intent, REQ_FILE_CHOOSER)
                    true
                }.getOrElse {
                    logWebView("file chooser failed to launch: ${it.message}")
                    pendingFileCallback = null
                    filePathCallback.onReceiveValue(null)
                    false
                }
            }
        }
        webView.webViewClient = object : WebViewClient() {
            override fun onReceivedError(
                view: WebView?, request: WebResourceRequest?, error: android.webkit.WebResourceError?,
            ) {
                // 主文档加载失败才是致命的；子资源失败常见（favicon 等），降级记录
                val url = request?.url?.toString() ?: "?"
                val desc = error?.description?.toString() ?: "?"
                val fatal = request?.isForMainFrame == true
                logWebView("${if (fatal) "LOAD-FAIL" else "subresource"} $url — $desc")
            }

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val uri = request?.url ?: return false
                // 仅允许回环导航；外部链接交给系统浏览器
                if (uri.host == "127.0.0.1" || uri.host == "localhost") return false
                runCatching { startActivity(Intent(Intent.ACTION_VIEW, uri)) }
                return true
            }

            /**
             * 渲染进程崩溃自愈（v1.2.100，酷安 issue #8）。
             *
             * ## 为什么必须有
             * issue 反馈：「会话内容卡住，计时器还在计数，模型仍在运行，要重启应用」——
             * 典型症状是**前端页面停止更新但后端一切正常**。WebView 的渲染进程
             * （独立沙箱进程）可能因内存压力被系统回收或自身崩溃，此时：
             *  · 不实现本回调 → Android 默认**直接杀掉宿主 App**（整个应用消失），或
             *  · 页面残留但不再刷新（用户看到「卡住」）
             * 两种都不是我们想要的：引擎由前台服务持有，本该继续跑。
             *
             * ## 做法
             * 返回 true 表示「我自己处理」，阻止系统杀 App；随后**重建 WebView 并重载**，
             * 用户视角是页面自动恢复（引擎没重启，会话与进行中的任务都在）。
             */
            override fun onRenderProcessGone(
                view: WebView?,
                detail: android.webkit.RenderProcessGoneDetail?,
            ): Boolean {
                logWebView(
                    "render process gone: crashed=${detail?.didCrash()} " +
                        "priority=${detail?.rendererPriorityAtExit()} — recovering"
                )
                recoverWebView()
                return true   // 已自行处理，别杀 App
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(view, url, favicon)
                // 页面一开始就把安全区变量塞进去（v1.2.122），避免首帧按默认值留白
                injectSafeInsets()
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                if (view == null) return
                // 页面加载完再补一次（部分页面会重建 :root 样式）
                injectSafeInsets()
                // 首屏已出（v1.2.121）：引擎若已就绪，就把启动页淡出交给网页
                pageReady = true
                maybeDismissSplash()
                // 环境自检：把 WebView 版本与关键 API 存在性写进日志，用户报障时一眼定位
                view.evaluateJavascript(WEBVIEW_SELFCHECK_JS, null)

                // v1.2.122：useWideViewPort=false 之后页面 meta 被忽略，缩放改用 setInitialScale
                // 下发（以前弃用它是因为 meta 的 maximum-scale 会把它 clamp 回 100%，现在不存在了）。
                applyPageScale()
            }

            override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                super.doUpdateVisitedHistory(view, url, isReload)
                updatePreviewChrome(url)
            }
        }
    }

    /**
     * 预览 chrome：WebView 导航到非引擎端口的回环页面（用户点击 AI 在对话里给的
     * http://127.0.0.1:PORT 链接）时，状态栏切预览模式并亮出返回按钮；
     * 回到引擎主界面自动恢复。AI 无需任何特殊协议，输出普通链接即可。
     */
    private fun updatePreviewChrome(url: String?) {
        val uri = url?.let { Uri.parse(it) } ?: return
        val loopback = uri.host == "127.0.0.1" || uri.host == "localhost"
        val enginePort = (application as DshApp).supervisor.healthyPort
        val preview = loopback && uri.port != enginePort
        findViewById<View>(R.id.btnBack).visibility = if (preview) View.VISIBLE else View.GONE
        if (preview) statusBar.text = getString(R.string.status_preview, uri.port)
    }

    /** 统一的回环页加载入口：缩放统一由 onPageFinished 的 viewport meta 接管，这里只导航。 */
    private fun loadLocalUrl(url: String) {
        applyPageScale()
        webView.loadUrl(url)
    }

    /** 手势缩放开关：竖屏关闭（锁死固定全屏，禁止双指捏合/拖动移动），桌面模式开启（保留双指缩放）。
     *  displayZoomControls 恒 false，只保留捏合不显示 +/- 浮层按钮。 */
    private fun applyZoomControls(enable: Boolean) {
        webView.settings.apply {
            setSupportZoom(enable)
            builtInZoomControls = enable
            displayZoomControls = false
        }
    }

    /** 工具栏收起/唤回：收起时网页全屏，仅留顶部小把手提示可恢复 */
    private fun toggleToolbar() {
        val row = findViewById<View>(R.id.statusBarRow)
        if (row.visibility == View.VISIBLE) hideToolbar() else showToolbar()
    }

    /** 收起横栏，只留把手（网页全屏）。自动收起与手动点按钮共用这一半 */
    private fun hideToolbar() {
        findViewById<View>(R.id.statusBarRow).visibility = View.GONE
        val handle = findViewById<View>(R.id.handleBar)
        handle.visibility = View.VISIBLE
        // 全屏后 y=0 落在状态栏里（v1.2.122）：把手默认放到状态栏下缘，
        // post 一次等布局完成，否则父容器高度还是 0，clamp 会把它按在顶上。
        handle.post { positionHandleDefault() }
    }

    /**
     * 唤回横栏。
     * @param fromUser 用户手动操作（点横栏空白处/点把手）时为 true：
     *   本轮不再自动收起；系统在「启动中/出错」等状态主动亮出横栏时传 false。
     */
    private fun showToolbar(fromUser: Boolean = true) {
        findViewById<View>(R.id.statusBarRow).visibility = View.VISIBLE
        findViewById<View>(R.id.handleBar).visibility = View.GONE
        if (fromUser) {
            userShowedToolbar = true
            autoHideArmed = false
            autoHideHandler.removeCallbacks(autoHideRunnable)
        }
    }

    /**
     * 把手拖拽：按住可在父容器内自由移动（clamp 保住 60% 宽度在屏内，防止拖丢），
     * 松手时若位移小于 touch slop（约 12px）则视为点击 → 唤回工具栏。
     * 位置仅本次隐藏期间保持（重显工具栏后再收起回到上次位置，rotation/重建回默认）。
     */
    private fun setupHandleBar() {
        val handle = findViewById<View>(R.id.handleBar)
        val slop = 12f
        var rawX0 = 0f; var rawY0 = 0f
        var initTx = 0f; var initTy = 0f
        handle.setOnTouchListener { v, ev ->
            when (ev.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    rawX0 = ev.rawX; rawY0 = ev.rawY
                    initTx = v.translationX; initTy = v.translationY
                    true
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    val parent = v.parent as View
                    v.translationX = (initTx + (ev.rawX - rawX0))
                        .coerceIn(-v.width * 0.6f, parent.width - v.width * 0.4f)
                    // v1.2.122：上下边界按系统栏收住 —— 上不进状态栏、下不进手势条
                    val (minY, maxY) = handleYRange(v)
                    v.translationY = (initTy + (ev.rawY - rawY0)).coerceIn(minY, maxY)
                    true
                }
                android.view.MotionEvent.ACTION_UP -> {
                    val dx = ev.rawX - rawX0; val dy = ev.rawY - rawY0
                    if (dx * dx + dy * dy < slop * slop) toggleToolbar()
                    true
                }
                else -> false
            }
        }
    }

    // ==================== 壳内悬浮层避让系统栏（v1.2.122） ====================

    /** dp → px（本文件没有全局换算工具，就地用一个） */
    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

    /**
     * 把壳自己的悬浮层从状态栏/手势条底下挪出来。全屏（edge-to-edge）之后
     * y=0 就是屏幕物理顶端，把手、工具栏、进度条都按同一个顶部 inset 下移。
     *
     * 只动壳内的原生 View，不碰网页内容（网页那侧由注入的 CSS 变量负责）。
     */
    private fun applyShellInsets() {
        val top = insetTopPx.coerceAtLeast(0)
        val bottom = insetBottomPx.coerceAtLeast(0)

        // 工具栏：自身 40dp 高，整条下移 top，内容区仍是 40dp
        findViewById<View>(R.id.statusBarRow)?.let { row ->
            val lp = row.layoutParams
            val h = dp(40) + top
            if (lp.height != h) {
                lp.height = h
                row.layoutParams = lp
            }
            if (row.paddingTop != top) row.setPadding(0, top, 0, 0)
        }

        // 解压进度条：原来固定 marginTop 40dp，会正好压在状态栏里
        findViewById<View>(R.id.installProgress)?.let { bar ->
            val lp = bar.layoutParams as? android.view.ViewGroup.MarginLayoutParams ?: return@let
            val m = dp(40) + top
            if (lp.topMargin != m) {
                lp.topMargin = m
                bar.layoutParams = lp
            }
        }

        clampHandle()
    }

    /** 把手可停留的纵向区间（translationY 语义）：上界=状态栏下缘，下界=手势条上缘 */
    private fun handleYRange(v: View): Pair<Float, Float> {
        val parent = v.parent as? View ?: return 0f to 0f
        val lp = v.layoutParams as? android.view.ViewGroup.MarginLayoutParams
        val base = (lp?.topMargin ?: 0).toFloat()
        val minY = insetTopPx + dp(2) - base
        val maxY = (parent.height - insetBottomPx.coerceAtLeast(0) - v.height) - base
        return minY to maxY.coerceAtLeast(minY)
    }

    /** 把手回到默认位置（状态栏下缘），每次收起工具栏时调用 */
    private fun positionHandleDefault() {
        val h = findViewById<View>(R.id.handleBar) ?: return
        h.translationY = handleYRange(h).first
    }

    /** inset 变化（旋转/手势条切换）后把把手夹回合法区间，别让它留在状态栏里 */
    private fun clampHandle() {
        val h = findViewById<View>(R.id.handleBar) ?: return
        if (h.parent == null || (h.parent as View).height <= 0) return
        val (minY, maxY) = handleYRange(h)
        h.translationY = h.translationY.coerceIn(minY, maxY)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // 旋转后屏宽变化 → reload 一次让布局重新按 device-width 排。
        // v1.2.122：不再按朝向切桌面 UA（DeepBot 只有一个手机形态），
        // 所以这里只剩重载，没有 UA/视口求值。
        webView.reload()
    }

    /**
     * 有效 CSS 像素密度（v1.2.122）。
     *
     * 正常情况下就是当前 display 的 density；但副屏/投屏可能把 density 报成 1
     * （物理像素直接当 CSS 像素用，实测那块屏上 iw=1168），这时退回系统主屏密度，
     * 否则 inset 会以 126 而不是 46 下发、布局也会按 1168 宽排成桌面。
     */
    private fun effectiveDensity(): Float {
        val cur = (if (::webView.isInitialized) webView.resources else resources)
            .displayMetrics.density
        val sys = android.content.res.Resources.getSystem().displayMetrics.density
        return when {
            cur <= 0f -> sys.takeIf { it > 0f } ?: 1f
            sys > cur * 1.05f -> sys
            else -> cur
        }
    }

    /**
     * 页面缩放（v1.2.122 定稿口径）。
     *
     * - **屏幕适配交给系统**：默认（100%）完全不调用 setInitialScale，
     *   WebView 按设备密度自行排版，布局宽 = 物理宽 / density；
     * - **用户缩放才下发**：只有用户主动选了非 100% 的档位才调用；
     *   调回 100% 用 0 复位。手动缩放会按百分比改布局宽，可能影响排版，
     *   所以设置页把它标成「100% 为推荐值」。
     */
    private fun applyPageScale() {
        if (!::webView.isInitialized) return
        // v1.2.122：默认（100%）**不要**调用 setInitialScale ——
        // useWideViewPort=false 时它会关闭 WebView 的密度换算，
        // 「1 布局像素 = 1 物理像素」，视口宽变成 1168，ds-bot 于是按桌面布局渲染。
        // 只有用户主动改过缩放才下发；回到默认时用 0 复位（0 = 交给 WebView 自己决定）。
        val applied = pageScale != DEFAULT_PAGE_SCALE
        if (applied) runCatching { webView.setInitialScale(pageScale) }
        else runCatching { webView.setInitialScale(0) }
        // 每页加载打一行（v1.2.122 定位「iw=1168」用）：四个密度/缩放/尺寸数分开打，
        // 三者不一致就能直接锁定是「WebView 自己的密度」还是「缩放没落地」。
        logWebView(
            "scale: pageScale=${pageScale}% initialScale=${if (applied) "${pageScale}" else "default"}" +
                " textZoom=${webView.settings.textZoom}" +
                " wvDensity=${webView.resources.displayMetrics.density}" +
                " wvCtxDensity=${webView.context.resources.displayMetrics.density}" +
                " sysDensity=${android.content.res.Resources.getSystem().displayMetrics.density}" +
                " getScale=${webView.scale}" +
                " wvSize=${webView.width}x${webView.height}"
        )
    }

    /** 解压进度：null=不在解压（不确定转圈），有值=真实百分比确定性进度 */
    private fun renderProgress(frac: Float?) {
        // 启动页也要吃真实进度（Installing → 「正在安装运行时 NN%」）
        lastProgress = frac
        updateSplash(lastState)
        if (!mainReady) return
        val bar = findViewById<ProgressBar>(R.id.installProgress)
        if (frac == null) {
            bar.isIndeterminate = true
        } else {
            bar.isIndeterminate = false
            bar.progress = (frac * 10000).toInt()
        }
    }

    /**
     * 屏幕朝向：把用户偏好真正落到 requestedOrientation（v1.2.120 三态）。
     *
     * ⚠️ 竖屏必须显式用 SCREEN_ORIENTATION_PORTRAIT。SCREEN_ORIENTATION_UNSPECIFIED
     * 的语义是「交给系统/传感器决定」，**不是**「锁定竖屏」—— 旧实现给竖屏填的是
     * UNSPECIFIED，所以设置里选「竖屏」后物理旋转设备照样变横屏，看起来就是开关失灵。
     * auto 用 USER（跟随传感器且尊重系统旋转锁），见 OrientationPrefs.toActivityInfo。
     */
    private fun applyOrientation(mode: OrientationMode) {
        requestedOrientation = OrientationPrefs.toActivityInfo(mode)
    }

    /** 启动阶段秒表：引擎启动本身耗时（真机可达十几秒），显示已用秒数避免被误判为卡死 */
    private var startTicker: Runnable? = null

    private fun stopStartTicker() {
        startTicker?.let { statusBar.removeCallbacks(it) }
        startTicker = null
    }

    private fun startStartTicker() {
        if (startTicker != null) return
        val t0 = System.currentTimeMillis()
        val r = object : Runnable {
            override fun run() {
                val sec = (System.currentTimeMillis() - t0) / 1000
                statusBar.text = getString(R.string.status_starting) + " " + sec + "s"
                statusBar.postDelayed(this, 1000)
            }
        }
        startTicker = r
        statusBar.postDelayed(r, 1000)
    }

    // ==================== 状态栏 / 安全区（v1.2.122） ====================

    /**
     * 三档状态栏：
     *  - TRANSPARENT：edge-to-edge + 状态栏全透明，网页透出；
     *  - THEME：edge-to-edge + 状态栏填主题底色（ds_bg_base）；
     *  - SYSTEM：保持系统默认布局（不延伸），状态栏用主题色。
     * 透明与主题色都把状态栏/导航栏高度以 CSS 变量交给页面，壳不再替页面留栏。
     */
    private fun applyStatusBar() {
        statusBarMode = StatusBarPrefs.get(this)
        val edge = statusBarMode != StatusBarMode.SYSTEM
        // 浅色主题用深色图标，深色主题用浅色图标
        val lightIcons = !ThemeHelper.isDark(this)
        val barColor = if (statusBarMode == StatusBarMode.TRANSPARENT)
            android.graphics.Color.TRANSPARENT else getColor(R.color.ds_bg_base)
        val navColor = if (edge) android.graphics.Color.TRANSPARENT else barColor
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(!edge)
            window.statusBarColor = barColor
            window.navigationBarColor = navColor
            window.insetsController?.let { c ->
                val mask = android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                    android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
                c.setSystemBarsAppearance(if (lightIcons) mask else 0, mask)
            }
        } else {
            var vis = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            if (edge) {
                vis = vis or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            }
            if (lightIcons) vis = vis or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
            if (lightIcons) vis = vis or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
            window.decorView.systemUiVisibility = vis
            window.statusBarColor = barColor
            window.navigationBarColor = navColor
        }
        // 布局模式变了 → 重新取一次 inset 并推给页面
        findViewById<View>(android.R.id.content)?.requestApplyInsets()
        injectSafeInsets()
    }

    /** 监听系统栏 inset 变化（旋转/手势条切换/分屏都会变） */
    private fun installInsetsListener() {
        val root = findViewById<View>(android.R.id.content) ?: return
        root.setOnApplyWindowInsetsListener { _, insets ->
            val top: Int
            val bottom: Int
            var ime = 0
            if (Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(android.view.WindowInsets.Type.systemBars())
                top = bars.top
                bottom = bars.bottom
                // v1.2.122：全屏（decorFitsSystemWindows=false）下窗口不会因键盘 resize，
                // 只能把 IME 高度交给页面，让输入框自己抬起来。
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
                injectSafeInsets()
            }
            insets
        }
    }

    /**
     * 把安全区写成 CSS 变量（px）并注入样式。约定（README「壳侧注入」一节）：
     *   --dsh-mobile-top-inset / --dsh-mobile-bottom-inset  ← ds-bot 既有契约名
     *   --dsh-android-system-bottom                          ← ds-bot 底栏间隙读的名字
     *   --dsh-safe-top / --dsh-safe-bottom                   ← 通用名
     *   --dsh-sab                                            ← v1.2.122 起保留的别名
     * 「跟随系统」模式下窗口不延伸，值一律注入 0，页面无需额外留白。
     *
     * 注入内容不再写死在代码里，来自 `$DSH_HOME/deepbot/inject.json`（见 [InjectSpec]），
     * 改文件后重启 App 即可生效，不用重装 APK。设置里的「底部安全区适配」关掉时
     * 只保留变量、不写样式，方便一键回到裸布局对比。
     */
    private fun injectSafeInsets() {
        if (!::webView.isInitialized) return
        // inset 是物理 px（状态栏实测 126px），页面按 CSS px 消费它 ——
        // 必须除以屏幕密度再下发，否则页面按 126 CSS px 留白，等于多出约 2.75 倍（v1.2.122 实测顶部空出一大截）。
        val density = effectiveDensity()
        val top = if (statusBarMode == StatusBarMode.SYSTEM) 0 else Math.round(insetTopPx / density)
        val bottom = if (statusBarMode == StatusBarMode.SYSTEM) 0 else Math.round(insetBottomPx / density)
        val ime = if (statusBarMode == StatusBarMode.SYSTEM) 0 else Math.round(insetImePx / density)
        val fix = InsetFixPrefs.isEnabled(this)
        val spec = InjectSpec.load(this)
        // 注入来源留痕（v1.2.122）：共享文件没读到时会静默退回私有/内置，
        // 打一行就能回答「我改了共享文件为什么不生效」。
        if (spec.source != lastInjectSource) {
            lastInjectSource = spec.source
            logWebView("inject source=${spec.source} css=${spec.css.size} js=${spec.js.size} probe=${spec.probe}")
        }
        val css = if (fix) spec.css.joinToString("\n") else ""
        val extra = if (fix) spec.js.joinToString("\n") else ""
        // 布局测量日志（DSHPROBE2: 前缀，tag DshWebView）：由 inject.json 的 probe 开关控制（缺省开）
        val probe = if (fix && spec.probe) PROBE_JS else ""
        val patch = if (css.isEmpty()) {
            ""
        } else {
            "var s=document.createElement('style');s.id=id;" +
                "s.textContent=${JSONObject.quote(css)};(document.head||d).appendChild(s);"
        }
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
            "if(old)old.remove();" +
            patch + extra + probe + "})();"
        runCatching { webView.evaluateJavascript(js, null) }
        // 品牌名：ds-bot 自己的产品名是「DS Bot」，源码不动，由壳把文案换成 DeepBot（v1.2.122）。
        // 只改**整块文本恰好等于** "DS Bot" 的叶子节点，不碰句子里的同名子串。
        runCatching { webView.evaluateJavascript(BRAND_JS, null) }
    }

    // ==================== 启动页（v1.2.121） ====================

    /** 绑定启动页的重试按钮，并起一个 250ms 的计时器刷新「已等 Ns」文案 */
    private fun setupSplash() {
        findViewById<TextView>(R.id.splashRetry)?.setOnClickListener {
            // 真实重试：走监督器的 stop→start（不是假动画）
            splashDismissed = false
            pageReady = false
            findViewById<View>(R.id.splashErrorGroup).visibility = View.GONE
            findViewById<View>(R.id.splashSpinner).visibility = View.VISIBLE
            findViewById<View>(R.id.splashStatus).visibility = View.VISIBLE
            urlLoaded = false
            (application as DshApp).supervisor.restart()
        }
        uiScope.launch {
            while (!splashDismissed) {
                updateSplash(lastState)
                kotlinx.coroutines.delay(250)
            }
        }
    }

    /**
     * 按真实引擎状态刷新启动页文案（不造假进度）：
     *   Installing → 正在安装运行时 NN%（installProgress 的真实值）
     *   Starting/Backoff → 正在启动本地引擎… Ns（真实等待秒数）
     *   Failed → 错误原因 + 重试按钮
     */
    private fun updateSplash(state: EngineSupervisor.State?) {
        if (splashDismissed) return
        val status = findViewById<TextView>(R.id.splashStatus) ?: return
        val spinner = findViewById<View>(R.id.splashSpinner) ?: return
        val errorGroup = findViewById<View>(R.id.splashErrorGroup) ?: return
        val errorText = findViewById<TextView>(R.id.splashError) ?: return
        val elapsedS = ((SystemClock.uptimeMillis() - splashAtMs) / 1000).coerceAtLeast(0)
        when (state) {
            is EngineSupervisor.State.Failed -> {
                spinner.visibility = View.GONE
                status.visibility = View.GONE
                errorGroup.visibility = View.VISIBLE
                errorText.text = getString(R.string.splash_failed_reason, state.reason)
            }
            is EngineSupervisor.State.Installing -> {
                spinner.visibility = View.VISIBLE
                status.visibility = View.VISIBLE
                errorGroup.visibility = View.GONE
                status.text = lastProgress?.let {
                    getString(R.string.splash_installing_runtime, (it * 100).toInt())
                } ?: getString(R.string.splash_preparing)
            }
            is EngineSupervisor.State.Healthy -> {
                spinner.visibility = View.VISIBLE
                status.visibility = View.VISIBLE
                errorGroup.visibility = View.GONE
                status.text = getString(R.string.splash_ready)
            }
            is EngineSupervisor.State.Backoff -> {
                spinner.visibility = View.VISIBLE
                status.visibility = View.VISIBLE
                errorGroup.visibility = View.GONE
                status.text = getString(R.string.splash_backoff, state.attempt)
            }
            is EngineSupervisor.State.Stopped -> {
                spinner.visibility = View.GONE
                status.visibility = View.VISIBLE
                errorGroup.visibility = View.GONE
                status.text = getString(R.string.status_idle)
            }
            else -> {
                spinner.visibility = View.VISIBLE
                status.visibility = View.VISIBLE
                errorGroup.visibility = View.GONE
                status.text = getString(R.string.splash_starting_engine, elapsedS)
            }
        }
    }

    /** 引擎就绪 + 网页首屏加载完 → 启动页淡出（280ms），交给 WebView */
    private fun maybeDismissSplash() {
        if (splashDismissed) return
        if (lastState !is EngineSupervisor.State.Healthy || !pageReady) return
        splashDismissed = true
        val splash = findViewById<View>(R.id.splashRoot) ?: return
        splash.animate().alpha(0f).setDuration(280).withEndAction {
            splash.visibility = View.GONE
            Log.i("Splash", "dismissed +${SystemClock.uptimeMillis() - splashProcessStartMs}ms")
        }.start()
    }

    private fun render(state: EngineSupervisor.State) {
        // 启动页先吃状态（主内容还没填时也能看到真实阶段）
        lastState = state
        if (state is EngineSupervisor.State.Healthy && !readyLogged) {
            readyLogged = true
            Log.i("Splash", "engine ready +${SystemClock.uptimeMillis() - splashProcessStartMs}ms")
        }
        updateSplash(state)
        if (state is EngineSupervisor.State.Healthy) maybeDismissSplash()
        if (!mainReady) return
        // 显式退出（Stopped）后按钮文案变「启动」，提示用户点这里重新拉起引擎
        findViewById<TextView>(R.id.btnRestart).text =
            if (state is EngineSupervisor.State.Stopped) getString(R.string.btn_start_engine)
            else getString(R.string.btn_restart)
        val bar = findViewById<ProgressBar>(R.id.installProgress)
        bar.visibility =
            if (state is EngineSupervisor.State.Installing || state is EngineSupervisor.State.Starting)
                View.VISIBLE else View.GONE
        if (state !is EngineSupervisor.State.Starting) stopStartTicker()
        statusBar.text = when (state) {
            is EngineSupervisor.State.Idle -> getString(R.string.status_idle)
            is EngineSupervisor.State.Installing -> getString(R.string.status_installing)
            is EngineSupervisor.State.Starting -> getString(R.string.status_starting).also { startStartTicker() }
            is EngineSupervisor.State.Healthy -> {
                if (!urlLoaded) {
                    urlLoaded = true
                    loadLocalUrl((application as DshApp).supervisor.webUrl())
                }
                getString(R.string.status_healthy)
            }
            is EngineSupervisor.State.SafeMode -> {
                if (!urlLoaded) {
                    urlLoaded = true
                    loadLocalUrl((application as DshApp).supervisor.webUrl())
                }
                getString(R.string.status_safe_mode)
            }
            is EngineSupervisor.State.Backoff ->
                getString(R.string.status_backoff, state.delayMs / 1000, state.attempt)
            is EngineSupervisor.State.Failed -> getString(R.string.status_failed, state.reason)
            is EngineSupervisor.State.Stopped -> {
                urlLoaded = false
                getString(R.string.status_idle)
            }
        }
        applyAutoHide(state)
    }

    /**
     * 顶部工具栏自动隐藏（v1.2.121）。
     *
     * 规则（与设置页开关「启动后自动隐藏顶部工具栏」配合，默认开）：
     *  - 只在「非就绪 → 就绪」这一次跃迁上排队收起，状态反复刷新不会重复收；
     *  - 就绪后延迟 [AutoHidePrefs.HIDE_DELAY_MS] 再收，留出看清端口/版本的时间；
     *  - 用户手动唤回过就本轮不再自动收（[userShowedToolbar]）；
     *  - 启动中/安装运行时/退避/失败优先把横栏露出来，等下次就绪再按设置处理。
     */
    private fun applyAutoHide(state: EngineSupervisor.State) {
        val ready = state is EngineSupervisor.State.Healthy
        if (ready == lastStateReady) return
        lastStateReady = ready
        autoHideHandler.removeCallbacks(autoHideRunnable)
        if (!ready) {
            showToolbar(fromUser = false)
            // 用户主动唤回过后，本轮不再自动收起（重启/重建后重新计）
            if (!userShowedToolbar) autoHideArmed = true
            return
        }
        if (!autoHideArmed || userShowedToolbar || !AutoHidePrefs.isEnabled(this)) return
        autoHideArmed = false
        autoHideHandler.postDelayed(autoHideRunnable, AutoHidePrefs.HIDE_DELAY_MS)
    }

    override fun onBackPressed() {
        // WebView 有历史则先回退，保持类原生浏览体验
        if (webView.canGoBack()) webView.goBack() else super.onBackPressed()
    }

    /**
     * 文件选择器结果回传。
     *
     * ⚠️ 必须调用 onReceiveValue（哪怕是 null）：WebView 在回调前会**挂起页面的文件选择
     * 请求**，不调用则页面永久等待（表现为「点了上传没反应」）。用户取消时传 null 即取消。
     */
    @Deprecated("startActivityForResult is the established contract for the WebView file chooser")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == REQ_FILE_CHOOSER) {
            val cb = pendingFileCallback
            pendingFileCallback = null
            if (cb == null) {
                super.onActivityResult(requestCode, resultCode, data)
                return
            }
            val uris: Array<Uri>? = when {
                resultCode != RESULT_OK -> null
                else -> {
                    val fromData = android.webkit.WebChromeClient.FileChooserParams
                        .parseResult(resultCode, data)
                    // 部分 ROM 的选择器把结果放在 clipData（多选/分享式选择器），parseResult 拿不到
                    if (fromData != null && fromData.isNotEmpty()) fromData
                    else data?.clipData?.let { cd -> Array(cd.itemCount) { cd.getItemAt(it).uri } }
                        ?: data?.data?.let { arrayOf(it) }
                }
            }
            runCatching { cb.onReceiveValue(uris) }
                .onFailure { logWebView("file chooser result delivery failed: ${it.message}") }
            return
        }
        super.onActivityResult(requestCode, resultCode, data)
    }

    // ==================== 语音输入（v1.2.58 起，v1.2.65 移交服务托管） ====================
    //
    // 麦克风入口、识别编排、输入面板都在 DshAccessibilityService 侧
    // （见其 startVoiceInput / VoicePanel）—— 因为它们需要 TYPE_ACCESSIBILITY_OVERLAY
    // 窗口，且不应依赖本 Activity 存活。
    // 本 Activity 只负责两件事：
    //   ① 注册「把文字送进 WebUI 输入框」的能力（VoiceBridge.registerSender）
    //   ② 用户从主界面发起语音时申请麦克风权限（onRequestPermissionsResult 回调里
    //      转交服务；requestPermissions 是 Activity 才能做的事）

    /**
     * 发送语音文本：写进 WebUI 输入框并触发发送。
     * 走 DOM 注入（而非引擎 API），因为要复用前端既有的发送流程。
     */
    /**
     * 发送语音文本：写进 WebUI 输入框并触发发送。
     *
     * ⚠️ v1.2.65：按**实际结果**反馈，不再无条件弹「已发送」——
     * 旧版注入失败（如停在首页无会话）也报成功，用户实测「实际无作用」却看到「已发送」。
     *  · ok:*            → 已发送
     *  · entering-session→ 已在进入会话，稍后自动重发一次
     *  · no-input-found  → 明确提示「当前没有可发送的会话」
     *  · err:*           → 报具体错误
     */
    /**
     * 发送去重守卫（v1.2.90）。
     *
     * 🔴 主人实测「语音输入还是一次性复制两段发出去」。
     * 除了面板按钮的 consumed 守卫，这里再按**文本内容 + 时间窗**兜一层：
     * 相同文本在 [DEDUP_WINDOW_MS] 内只允许发送一次。
     *
     * 为什么需要两层：重复可能来自不同入口（面板按钮重复触发、服务回调重入、
     * 重试路径），单堵一处不够。这一层是**最终防线**，且对"用户真的想连说两遍
     * 同样的话"影响很小（1.5 秒内几乎不可能刻意重复）。
     */
    @Volatile private var lastSentText: String = ""
    @Volatile private var lastSentAt: Long = 0L

    private fun sendVoiceText(text: String) {
        val now = System.currentTimeMillis()
        if (text == lastSentText && now - lastSentAt < DEDUP_WINDOW_MS) {
            logWebView("voice send deduped: '$text' within ${now - lastSentAt}ms")
            return
        }
        lastSentText = text
        lastSentAt = now
        sendVoiceTextOnce(text, allowRetry = true)
    }

    private fun sendVoiceTextOnce(text: String, allowRetry: Boolean) {
        val js = buildVoiceInputJs(text)
        webView.evaluateJavascript(js) { raw ->
            val result = jsResult(raw)
            logWebView("voice send: $result")
            when {
                result.startsWith("ok:") -> {
                    // v1.2.95：写入（paste）后先**校验是否真的落了地**再提交。
                    // Lexical 等框架编辑器对合成 paste 的处理是异步的，且对
                    // execCommand 会翻倍 —— 所以不能在 JS 里同步回退，改由
                    // Kotlin 在 FIRE_DELAY_MS 后读框内容分派：
                    //   · 框里 == 发送文本 → paste 成功 → 提交
                    //   · 框里 != 文本     → paste 无效 → repair（execCommand）
                    webView.postDelayed({ verifyWriteThenFire(text) }, FIRE_DELAY_MS)
                }
                result == "entering-session" && allowRetry -> {
                    // 刚从首页点进会话：等 UI 渲染出输入框后重试一次。
                    //
                    // ⚠️ v1.2.94：重写前必须确认输入框里**没有我们那段文字**。
                    // 旧版只判断"框里有没有字"，而"有字"既可能是「上次没写进去」，
                    // 也可能是「上次写进去了、前端正在提交但还没清空」——
                    // 后者会让我们把同一句话再写一遍 → 主人实测的「两段」。
                    //
                    // 现在按**文本内容**比对：只有框里既非空、又不是我们刚发的那段
                    // （= 用户自己打的字），才放弃重写；否则按情况重写或补提交。
                    Toast.makeText(this, getString(R.string.voice_opening_session), Toast.LENGTH_SHORT).show()
                    webView.postDelayed({
                        readSendText { inBox ->
                            when {
                                inBox == text -> {
                                    // 正是我们那段、还躺在框里 → 补一次提交（令牌在写入时已发放）
                                    logWebView("retry: our text still in box, submitting it")
                                    fireSendKey()
                                    webView.postDelayed({ pollSendVal(text, attempt = 0) }, POLL_MS)
                                }
                                inBox.isNotEmpty() -> {
                                    // 是别的文字（用户自己打的）→ 绝不覆盖、绝不提交
                                    logWebView("retry skipped: box has unrelated text, leaving it alone")
                                    reportVoiceTarget("")
                                }
                                else -> {
                                    // 框是空的 → 上次确实没写进去，安全重写
                                    sendVoiceTextOnce(text, allowRetry = false)
                                }
                            }
                        }
                    }, 1_500L)
                }
                result == "entering-session" -> {
                    Toast.makeText(this, getString(R.string.voice_session_timeout), Toast.LENGTH_LONG).show()
                }
                result == "no-input-found" -> {
                    Toast.makeText(this, getString(R.string.voice_no_session), Toast.LENGTH_LONG).show()
                }
                else -> {
                    Toast.makeText(
                        this, getString(R.string.voice_send_failed, result), Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    /**
     * Web 内核版本门禁（v1.2.100）。
     *
     * ## 为什么需要
     * 引擎前端用了 `??=` / 类字段等**解析期**语法（Chrome 85+），polyfill 只能补
     * **API 层**（13 个现代 API 注入），补不了语法 —— WebView 过旧时页面必白屏。
     * 之前的处理是「照常启动引擎 + 日志里记录 SyntaxError」：用户看到的是
     * 白屏/无限重启，不知道原因（酷安用户反馈的正是这种体验）。
     *
     * ## 做法
     * 直接查 WebView Provider 的包版本（不走 JS，无需加载页面）：
     *   · ≥ [MIN_WEBVIEW_CHROME]：放行（本会话内不再重复检查）
     *   · < 门槛：弹对话框，给出当前版本、升级路径（应用商店/Play），并提供
     *     「仍然继续」—— 用户明确坚持才启动引擎（尊重知情选择）；对话框
     *     不会因 AlertDialog 取消而反复弹出，选择「继续」后本会话记入记忆。
     *
     * @return true = 放行（版本达标或用户坚持）
     */
    /**
     * 当前显示的版本门禁对话框（v1.2.100）。
     * ⚠️ 必须持有引用并判重：`onResume` 会被反复触发（切回前台、息屏亮屏、点按钮后
     * 重入），旧实现每次都 `show()` 一个新对话框 → **无限叠堆**（且 setCancelable(false)
     * 无法取消）。独立审查指出后补上这道守卫。
     */
    private var gateDialog: android.app.AlertDialog? = null

    private fun checkWebViewGate(): Boolean {
        val ver = currentWebViewVersion()
        if (ver >= AppGate.MIN_WEBVIEW_CHROME) {
            AppGate.markWebViewOk()
            return true
        }
        AppGate.markWebViewBlocked()
        val appCtx = applicationContext
        // 升级指引：优先指向 Play 商店的 WebView 页（绝大多数设备），失败则给系统设置
        val updateIntent = runCatching {
            Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=com.google.android.webview"))
        }.getOrElse {
            Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(Uri.parse("package:com.google.android.webview"))
        }
        val msg = getString(
            R.string.webview_gate_body,
            if (ver > 0) ver.toString() else "??",
            AppGate.MIN_WEBVIEW_CHROME,
        )
        if (gateDialog?.isShowing == true) {
            logWebView("webview gate: dialog already showing, skip")
            return false
        }
        android.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.webview_gate_title))
            .setMessage(msg)
            .setPositiveButton(R.string.webview_gate_update) { _, _ ->
                runCatching { startActivity(updateIntent) }
                // 升级完回来 onResume 会重新检测（AppGate 仍是 BLOCKED，会再判一次）
            }
            .setNegativeButton(R.string.webview_gate_continue) { _, _ ->
                AppGate.overrideWebViewGate()
                onResume()   // 重新走一遍（这次会启动引擎）
            }
            .setCancelable(false)
            .create()
            .also { gateDialog = it }
            .show()
        logWebView("webview gate: chrome=$ver < ${AppGate.MIN_WEBVIEW_CHROME}, prompting user")
        return false
    }

    /**
     * 当前 WebView 内核的 Chrome 版本号（0 = 读不到）。
     *
     * ## 三级取法（都失败才返回 0）
     *  ① `WebView.getCurrentWebViewPackage()` —— 官方 API，但实测有坑：
     *     **onResume 时序上可能返回 null**（provider 尚未敲定），部分 ROM 还不实现
     *  ② 扫已知 provider 包名取 versionName —— 覆盖 Google WebView / AOSP /
     *     国产 ROM 内置 provider；Android 对 WebView provider 查询有 visibility 豁免
     *  ③ 当前 webView 实例的 UA —— 只在实例已建时可用
     * UA 里的 `Chrome/NN` 永远反映真正干活的内核版本，与 provider 包名无关。
     */
    private fun currentWebViewVersion(): Int {
        // ① 官方 API
        runCatching {
            android.webkit.WebView.getCurrentWebViewPackage()?.versionName?.let { v ->
                Regex("Chrome/(\\d+)").find(v)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
                // provider 版本号首段即 Chrome 大版本（如 133.0.6943.137）
                v.substringBefore('.')?.toIntOrNull()?.let { if (it > 0) return it }
            }
        }
        // ② 扫已知 provider
        runCatching {
            for (pkg in WEBVIEW_PROVIDERS) {
                val info = runCatching { packageManager.getPackageInfo(pkg, 0) }.getOrNull() ?: continue
                val vn = info.versionName ?: continue
                Regex("Chrome/(\\d+)").find(vn)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
                vn.substringBefore('.')?.toIntOrNull()?.let { if (it > 0) return it }
            }
        }
        // ③ 现有实例的 UA
        if (::webView.isInitialized) {
            runCatching {
                Regex("Chrome/(\\d+)").find(webView.settings.userAgentString ?: "")
                    ?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
            }
        }
        return 0
    }

    /** evaluateJavascript 的返回值带 JSON 引号与转义，统一清一遍 */
    private fun jsResult(raw: String?): String =
        raw?.trim('"')?.replace("\\u003d", "=")?.replace("\\", "") ?: ""

    /**
     * 解析 `evaluateJavascript` 返回的**字符串值**（v1.2.94）。
     *
     * ⚠️ 不能复用 [jsResult]：它会把所有反斜杠删掉（那是为状态标记这种
     * 纯 ASCII 短串设计的）。用来比对用户文本时，反斜杠/引号会被破坏，
     * 导致"文字相同却判为不同" → 重写一遍 → 又是「两段」。
     * 这里用 JSON 解析，完整保留原字符。
     */
    private fun jsString(raw: String?): String = runCatching {
        val s = raw?.trim().orEmpty()
        if (s.isEmpty() || s == "null") ""
        else org.json.JSONTokener(s).nextValue() as? String ?: s
    }.getOrDefault(raw?.trim('"').orEmpty())

    /**
     * 提交输入框内容：调用 JS 里的**同步钩子** `__dshFire()`。
     *
     * 钩子本身不排任何定时器 —— 谁调用它、什么时候调用，由 Android 侧决定。
     * JS 端带 1.5 秒节流窗与空框守卫，重复调用不会造成重复发送。
     */
    private fun fireSendKey() {
        webView.evaluateJavascript("(window.__dshFire && window.__dshFire()) || 'no-hook'") { raw ->
            logWebView("voice fire: ${jsResult(raw)}")
        }
    }

    /** 读输入框状态：empty/gone = 已提交；text = 仍在框里；none = 找不到元素 */
    private fun readSendVal(cb: (String) -> Unit) {
        webView.evaluateJavascript("window.__dshReadVal ? window.__dshReadVal() : 'none'") { raw ->
            cb(jsResult(raw))
        }
    }

    /**
     * 校验 paste 写入是否落地，落地才提交；没落地走 repair（v1.2.95）。
     *
     * 为什么必须有这一步：Lexical 对合成 paste 的处理是**异步**的——JS 注入返回时
     * 内容还没进编辑器。若不看结果直接提交，消息可能是旧的；若在 JS 里同步回退
     * （execCommand），Lexical 会把文本翻倍（主人实测「你好你好」）。
     * 所以「写入 → 校验 → 提交/补写」全部由 Android 侧时序驱动。
     */
    private fun verifyWriteThenFire(text: String) {
        readSendText { inBox ->
            when {
                inBox == text -> {
                    // paste 已落地 → 发放提交令牌并点击
                    webView.evaluateJavascript("window.__dshTicket = true") { _ ->
                        fireSendKey()
                        webView.postDelayed({ pollSendVal(text, attempt = 0) }, POLL_MS)
                    }
                }
                inBox.isEmpty() || inBox == "none" -> {
                    // paste 无效（编辑器不认合成 paste）→ execCommand 补写
                    logWebView("voice write: paste had no effect, repairing (execCommand)")
                    webView.evaluateJavascript(buildVoiceRepairJs(text)) { raw ->
                        logWebView("voice repair: ${jsResult(raw)}")
                        readSendText { after ->
                            if (after == text) {
                                webView.evaluateJavascript("window.__dshTicket = true") { _ ->
                                    fireSendKey()
                                    webView.postDelayed({ pollSendVal(text, attempt = 0) }, POLL_MS)
                                }
                            } else {
                                logWebView("voice write failed after repair: '$after'")
                                Toast.makeText(this, getString(R.string.voice_not_sent), Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                }
                else -> {
                    // 框里是别的内容（用户自己打的字）→ 绝不覆盖，放弃本次发送
                    logWebView("voice write: box holds unrelated text '$inBox', aborting")
                    Toast.makeText(this, getString(R.string.voice_not_sent), Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    /**
     * 读输入框里的**实际文字**（v1.2.94）。
     *
     * 与 [readSendVal] 的区别：那个只回答"空不空"，这个给出内容 —— 重试路径
     * 必须靠它区分「我们刚写进去的那段」和「用户自己打的字」，
     * 否则会把用户的输入覆盖掉、或把我们那段提交两遍（主人实测的「两段」）。
     */
    private fun readSendText(cb: (String) -> Unit) {
        webView.evaluateJavascript("window.__dshText ? window.__dshText() : ''") { raw ->
            cb(jsString(raw))
        }
    }

    /**
     * 轮询发送结果（v1.2.94 再修）。
     *
     * 判定源是**同步读值**（`__dshReadVal`），由 Android 侧定时轮询，前台后台一个样。
     * 判定依据：前端提交后会把输入框清空。
     *
     * 🔴 v1.2.93 之前的版本在 4 秒超时后会**再点一次发送**，主人实测
     * 「消息还是会被复制成两段」。那就是重复的来源：
     *  · JS 的 1.5s 节流窗拦不住它（4s > 1.5s）
     *  · Kotlin 的 1.5s 同文本去重也拦不住（重发在 sendVoiceTextOnce 内部，绕过它）
     *  · 前端若因网络慢还没清空输入框，这一下就真的提交了第二遍
     *
     * 现在**绝不重发**：提交动作每段文字只做一次（JS 侧一次性令牌保证），
     * 4 秒后仍没清空就如实报「未发出」，把判断权交回用户 —— 宁可少发，
     * 不可重复发（重复消息会污染对话，用户很难撤销）。
     *
     * @param attempt 已轮询次数（上限 [MAX_POLL] ≈ 4 秒）
     */
    private fun pollSendVal(text: String, attempt: Int) {
        readSendVal { st ->
            when {
                st == "empty" || st == "gone" -> reportVoiceTarget("")
                st == "none" -> {
                    // 输入框不在了（页面被导航走等）：按已提交处理，与旧版语义一致
                    logWebView("voice send: input gone (assume committed)")
                    reportVoiceTarget("")
                }
                attempt < MAX_POLL -> {
                    webView.postDelayed({ pollSendVal(text, attempt + 1) }, POLL_MS)
                }
                else -> {
                    // 超时仍未清空：**不再补发**（那正是「两段」的来源），如实报未发出
                    logWebView("voice send: still in box after ${MAX_POLL * POLL_MS}ms, not retrying")
                    Toast.makeText(this, getString(R.string.voice_not_sent), Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    /**
     * 报告语音文本**真正落到了哪个对话**（v1.2.72）。
     *
     * 主人反复问「我都不知道他发哪个对话、究竟能不能发」—— 光靠 DOM 猜标题不可靠，
     * 改用引擎自己的账本：发送成功后该会话的 `lastPromptAt` 会被刷新为刚刚，
     * 于是「最近 20 秒内活跃过、且非空白」的那个会话就是真正的落点，报它的标题。
     *
     * @param domTitle DOM 里捞到的标题（兜底，通常为空或应用名）
     */
    private fun reportVoiceTarget(domTitle: String) {
        Thread({
            val now = System.currentTimeMillis()
            val hit = runCatching {
                app.dsh.mobile.engine.SessionReader.list(this)
                    .firstOrNull { !it.blank && now - it.lastActiveAt < 20_000L }
            }.getOrNull()
            val name = hit?.title?.takeIf { it.isNotBlank() } ?: domTitle
            val msg = if (name.isBlank()) getString(R.string.voice_sent)
            else getString(R.string.voice_sent_to, name)
            webView.post { runCatching { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() } }
        }, "voice-target").apply { isDaemon = true; start() }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        if (requestCode == REQ_RECORD_AUDIO) {
            val ok = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
            if (ok) {
                // 授权成功 → 交给无障碍服务弹面板并开始识别（面板归服务托管）
                DshAccessibilityService.instance?.startVoiceInput()
            } else {
                Toast.makeText(this, getString(R.string.voice_need_permission), Toast.LENGTH_SHORT).show()
            }
            return
        }
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
    }

    override fun onDestroy() {
        // 注意：引擎由前台服务持有，Activity 销毁不影响后台任务。
        // 首启跳 Onboarding 时本 Activity 立即销毁，webView 尚未初始化——
        // lateinit 直接访问会崩（Android 11 新用户首启闪退实测）。
        uiScope.cancel()
        // 取消尚未到点的自动收起，避免 Activity 销毁后回调仍持有视图
        autoHideHandler.removeCallbacks(autoHideRunnable)
        // 注销语音发送能力：本 Activity 已销毁，服务侧再发会拿到明确的「需打开 App」提示
        // （而不是把文字注入到一个已销毁的 WebView）
        stopHangWatchdog()
        VoiceBridge.registerSender(null)
        // 释放未完成的文件选择请求，否则 WebView 侧回调悬空
        pendingFileCallback?.let { runCatching { it.onReceiveValue(null) } }
        pendingFileCallback = null
        if (::webView.isInitialized) webView.destroy()
        super.onDestroy()
    }

    // ==================== WebView 卡死自愈（v1.2.100，issue #8） ====================
    //
    // ## 问题
    // 用户反馈：「运行到一半会话内容卡住，计时器还在正常计数，模型仍在运行，
    // 需要关闭进程重启应用，偶发」。症状 = **后端正常、前端停止更新**。
    // 两种成因：
    //  ① WebView 渲染进程崩溃/被系统回收 → 页面残留但不再刷新
    //     （未实现 onRenderProcessGone 时 Android 甚至会直接杀掉整个 App）
    //  ② 前端 JS 事件循环被长任务卡住（渲染进程还活着，但不再处理消息）
    //
    // ## 做法：两级看门狗
    //  ① 渲染进程崩溃 → onRenderProcessGone 里 [recoverWebView]（重建 WebView + 重载）
    //  ② 页面活着但不响应 → 定时注入轻量探针，连续失败则同样走 [recoverWebView]
    //
    // ⚠️ 引擎**不重启**（它由前台服务持有，会话与进行中的任务都在），
    // 只重建渲染层，用户视角是「页面自己恢复了」。

    private val hangWatchdog = android.os.Handler(android.os.Looper.getMainLooper())

    /** 连续探针失败次数（成功即清零；达阈值判定卡死） */
    private var probeFails = 0

    /** 页面探针：更新一个时间戳；若 eval 回调迟迟不回来，说明主线程/JS 卡住 */
    private val HANG_PROBE_JS =
        "(function(){window.__dshProbe=Date.now();return String(window.__dshProbe);})()"

    private fun startHangWatchdog() {
        stopHangWatchdog()
        hangWatchdog.postDelayed(hangTick, HANG_CHECK_INTERVAL_MS)
    }

    /** 看门狗的一次心跳：探针 → 判定 → 排下一次 */
    private val hangTick = object : Runnable {
        override fun run() {
            if (!::webView.isInitialized) return
            probePage { alive ->
                if (alive) {
                    probeFails = 0
                } else {
                    probeFails++
                    logWebView("hang probe failed ($probeFails/$HANG_FAIL_LIMIT)")
                    if (probeFails >= HANG_FAIL_LIMIT) {
                        logWebView("page unresponsive — recovering webview (engine untouched)")
                        recoverWebView()
                        probeFails = 0
                        return@probePage   // recoverWebView 内部会重启看门狗
                    }
                }
            }
            hangWatchdog.postDelayed(this, HANG_CHECK_INTERVAL_MS)
        }
    }

    private fun stopHangWatchdog() {
        hangWatchdog.removeCallbacks(hangTick)
        probeFails = 0
    }

    /**
     * 探针：注入一段极短的 JS 并等待回调。
     * @param cb true = 页面有响应；false = 超时或异常
     */
    private fun probePage(cb: (Boolean) -> Unit) {
        var done = false
        val timeout = Runnable {
            if (!done) {
                done = true
                cb(false)
            }
        }
        hangWatchdog.postDelayed(timeout, HANG_PROBE_TIMEOUT_MS)
        runCatching {
            webView.evaluateJavascript(HANG_PROBE_JS) { raw ->
                if (!done) {
                    done = true
                    hangWatchdog.removeCallbacks(timeout)
                    cb(raw != null && raw.contains("1"))   // 时间戳必然含数字
                }
            }
        }.onFailure {
            if (!done) {
                done = true
                hangWatchdog.removeCallbacks(timeout)
                cb(false)
            }
        }
    }

    /**
     * 重建渲染层：销毁旧 WebView、新建并重新加载当前 URL。
     * 引擎与会话完全不受影响（它们不在渲染进程里）。
     */
    private fun recoverWebView() {
        runCatching {
            stopHangWatchdog()
            val parent = webView.parent as? android.view.ViewGroup
            val oldUrl = runCatching { webView.url }.getOrNull()
            parent?.removeView(webView)
            runCatching { webView.destroy() }
            val fresh = WebView(this)
            fresh.id = R.id.webView
            parent?.addView(fresh, android.view.ViewGroup.LayoutParams(-1, -1))
            webView = fresh
            setupWebView()
            // 引擎仍在跑 → 直接回到它给的地址（没有则等 Healthy 回调重载）
            val target = oldUrl ?: runCatching {
                (application as DshApp).supervisor.webUrl()
            }.getOrNull()
            if (!target.isNullOrBlank()) {
                urlLoaded = false   // 交给 Healthy/加载流程统一处理
                webView.loadUrl(target)
            }
            startHangWatchdog()   // 新页面继续受监控
            logWebView("webview recovered (reloaded ${target ?: "engine url pending"})")
        }.onFailure {
            logWebView("webview recovery failed: ${it.message} — recreating activity")
            runCatching { recreate() }
        }
    }

    /** WebView 诊断日志（写 logcat + engine.log，用户可导出） */
    private fun logWebView(message: String) {
        android.util.Log.w("DshWebView", message)
        runCatching {
            val f = app.dsh.mobile.engine.EngineConfig.engineRoot(this).let {
                java.io.File(it, "engine.log")
            }
            f.appendText("[webview] $message\n")
        }
    }

    companion object {

        /**
         * 品牌名替换（v1.2.122）：把页面里**整块文本恰好是 "DS Bot"** 的叶子节点改成 DeepBot，
         * 以及 document.title。ds-bot 源码不动（它的 sidebar.js:822 BrandName 就是写死的 "DS Bot"）。
         *
         * 用 MutationObserver 兜住 React 重渲染，带 150ms 去抖，避免逐个 DOM 变动都全树扫描。
         */
        private val BRAND_JS = """
(function(){
  if(window.__deepbotBrand){return;}
  window.__deepbotBrand=true;
  var NAME='DeepBot';
  function fix(){
    var all=document.querySelectorAll('span,a,p,h1,h2,h3,div,strong,b');
    for(var i=0;i<all.length;i++){var e=all[i];
      if(e.childElementCount){continue;}
      if(String(e.textContent||'').trim()==='DS Bot'){e.textContent=NAME;}
    }
    if(document.title==='DS Bot'){document.title=NAME;}
  }
  var timer=null;
  function schedule(){if(timer){return;}timer=setTimeout(function(){timer=null;fix();},150);}
  fix();
  try{new MutationObserver(schedule).observe(document.documentElement,{childList:true,subtree:true});}catch(e){}
})();
        """.trimIndent()

        /**
         * 布局测量（v1.2.122，由 inject.json 的 probe 开关控制，缺省开）。
         * 打一条就能看清：顶部那片空白是哪个元素、左侧占位侧栏是不是真的存在、
         * 会话列的矩形与视口的关系。前缀 [dsh-android] 会被 console 桥转进 logcat。
         */
        private val PROBE_JS = """
(function(){
  function r(e){if(!e)return null;var b=e.getBoundingClientRect();
    return [Math.round(b.left),Math.round(b.top),Math.round(b.right),Math.round(b.bottom)];}
  function pick(sel,n){var out=[],es=document.querySelectorAll(sel);
    for(var i=0;i<es.length&&out.length<n;i++){var s=getComputedStyle(es[i]);
      if(s.display==='none'&&s.visibility==='hidden')continue;
      out.push([String(es[i].className||'').slice(0,26),r(es[i]),s.display]);}
    return out;}
  function cs(e){if(!e)return null;var s=getComputedStyle(e);
    return {pos:s.position,left:s.left,right:s.right,width:s.width,flex:s.flex,
            disp:s.display,ml:s.marginLeft,gc:s.gridColumn};}
  function topChild(){var cc=document.querySelector('[class*="centerCol"]');
    if(!cc)return null;var k=cc.children;
    for(var i=0;i<k.length;i++){var b=k[i].getBoundingClientRect();
      if(b.height>0)return [String(k[i].className||'').slice(0,26),r(k[i]),
        String(k[i].textContent||'').trim().slice(0,16)];}
    return null;}
  function probe(){
    var d=document.documentElement,b=document.body;
    var cs=getComputedStyle(d),bs=getComputedStyle(b);
    var cc=document.querySelector('[class*="centerCol"]');
    var o={dpr:window.devicePixelRatio,cw:document.documentElement.clientWidth,
      iw:innerWidth,ih:innerHeight,
      htmlPad:[cs.paddingTop,cs.marginTop],bodyPad:[bs.paddingTop,bs.marginTop],
      topInset:cs.getPropertyValue('--dsh-mobile-top-inset').trim(),
      astTop:cs.getPropertyValue('--dsh-android-system-top').trim(),
      astBottom:cs.getPropertyValue('--dsh-android-system-bottom').trim(),
      body:r(b),cc:r(cc),ccPadTop:cc?getComputedStyle(cc).paddingTop:null,
      ccStyle:cs(cc),ccParent:cs(cc?cc.parentElement:null),
      sbStyle:cs(document.querySelector('[class*="sidebarCol"]')),
      top:topChild(),
      rail:pick('[class*="rail"]',3),side:pick('[class*="side"]',3)};
    console.log('DSHPROBE2:'+JSON.stringify(o));
  }
  setTimeout(probe,600);setTimeout(probe,2500);
})();
        """.trimIndent()

        /** WebView 文件选择器的请求码（onShowFileChooser → onActivityResult） */
        private const val REQ_FILE_CHOOSER = 1001

        /** 麦克风权限请求码（语音输入，v1.2.58） */
        private const val REQ_RECORD_AUDIO = 1002

        /** 发送去重窗口（v1.2.90）：同文本在此时间内只发一次，防重复 */
        private const val DEDUP_WINDOW_MS = 1_500L

        /**
         * 页面卡死看门狗的时序（v1.2.100，issue #8）。
         * 探针注入极短 JS 并要求回执；连续 [HANG_FAIL_LIMIT] 次无响应即判定卡死。
         * 间隔取 15s：既不会漏掉「跑了很久才发现」的情况，也不会频繁打扰正常页面。
         */
        private const val HANG_CHECK_INTERVAL_MS = 15_000L
        /** 单次探针等待上限（超过即算无响应） */
        private const val HANG_PROBE_TIMEOUT_MS = 3_000L
        /** 连续失败几次判定卡死（3 次 ≈ 45s 无响应，足够排除偶发抖动） */
        private const val HANG_FAIL_LIMIT = 3


        /** 已知 WebView provider 包名（v1.2.100，三级取版本的 ② 号路径） */
        private val WEBVIEW_PROVIDERS = listOf(
            "com.google.android.webview",
            "com.android.webview",
            "com.google.android.trichromelibrary",
            "com.miui.webview",
            "com.huawei.webview",
            "com.hihonor.webview",
            "com.oplus.webview",
            "com.heytap.webview",
        )

        /**
         * 语音发送的 Android 侧时序（v1.2.92，v1.2.94 调整）。
         *
         * 全部走 Handler，不依赖 JS 定时器或渲染帧 —— 后台 WebView 既不产帧、
         * JS 定时器也可能被节流，只有进程级 Handler 在前后台行为一致。
         */
        /** 写入输入框后、点发送键前的等待：给 React 受控组件落地 state 的时间 */
        private const val FIRE_DELAY_MS = 250L
        /** 结果轮询间隔 */
        private const val POLL_MS = 100L
        /** 轮询上限（× POLL_MS ≈ 4 秒）；超时只报未发出，**绝不重发**（防重复） */
        private const val MAX_POLL = 40
        /** 「输入框是否确定为空」的采样间隔（见 probeInputEmpty） */
        private const val PROBE_INTERVAL_MS = 60L

        /**
         * 环境自检脚本：报告 WebView 版本与引擎所需关键 API 是否存在。
         * 引擎实际需要 Chrome 126（pdf.js 用 URL.parse），polyfill 可补 API 但补不了语法
         * （`??=` 需 Chrome 85，语法错误会导致白屏 —— 那种情况日志里会看到 SyntaxError）。
         */
        private const val WEBVIEW_SELFCHECK_JS = """
(function () {
  try {
    var ua = navigator.userAgent || '';
    var m = ua.match(/Chrome\/(\d+)/);
    var chrome = m ? parseInt(m[1], 10) : 0;
    var need = {
      'Object.hasOwn': 93, 'replaceChildren': 86, 'replaceAll': 85,
      'findLast': 97, 'structuredClone': 98, 'toSorted': 110,
      'AbortSignal.any': 116, 'Promise.withResolvers': 119, 'URL.parse': 126
    };
    var missing = [];
    var probes = {
      'Object.hasOwn': function(){ return typeof Object.hasOwn === 'function'; },
      'replaceChildren': function(){ return typeof Element !== 'undefined' && !!Element.prototype.replaceChildren; },
      'replaceAll': function(){ return !!String.prototype.replaceAll; },
      'findLast': function(){ return !!Array.prototype.findLast; },
      'structuredClone': function(){ return typeof structuredClone === 'function'; },
      'toSorted': function(){ return !!Array.prototype.toSorted; },
      'AbortSignal.any': function(){ return typeof AbortSignal !== 'undefined' && !!AbortSignal.any; },
      'Promise.withResolvers': function(){ return !!Promise.withResolvers; },
      'URL.parse': function(){ return typeof URL !== 'undefined' && !!URL.parse; }
    };
    for (var k in probes) { try { if (!probes[k]()) missing.push(k + '(need ' + need[k] + ')'); } catch (e) { missing.push(k + '(probe err)'); } }
    return '[dsh-android] webview ok | chrome=' + chrome +
      ' | polyfill=' + (!missing.length ? 'complete' : 'MISSING: ' + missing.join(',')) +
      ' | ua=' + ua.slice(0, 120);
  } catch (e) { return '[dsh-android] selfcheck failed: ' + e.message; }
})();
"""

        /** Android 13+ 通知运行时权限请求（Service 启动路径回调到 Activity） */
        fun maybeRequestNotificationPermission(activity: Context) {
            if (Build.VERSION.SDK_INT < 33) return
            val granted = activity.checkSelfPermission(
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted && activity is Activity) {
                activity.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100)
            }
        }

        /**
         * 把识别出的文本写入 WebUI 的输入框并提交（v1.2.58）。
         *
         * 为什么用 JS 注入而不是直接调引擎 API：WebUI 的输入框是前端状态的一部分，
         * 只有走它的 DOM 才能触发正确的发送流程（含附件、@引用等既有逻辑）。
         *
         * 选择器策略（按可靠性排序，逐个尝试）：
         *  ① textarea（当前前端用的是 textarea）
         *  ② [contenteditable]（富文本输入框）
         *  ③ input[type=text]
         * 写入用原生 setter + 派发 input 事件，让 React 的受控组件感知变化
         * （直接改 value 而不派发事件，React 会忽略）。
         */
        fun buildVoiceInputJs(text: String): String {
            val escaped = org.json.JSONObject.quote(text)
            return """
(function(){
  try {
    var t = $escaped;
    // v1.2.65: ensure there is an input box to write into. The user may be sitting
    // on the home screen (no conversation open), where no textarea exists; the old
    // build returned 'no-input-found' and the send silently did nothing.
    // Here we first look for a conversation entry in the DOM, click into it, and
    // let the caller retry once the UI has rendered.
    function findInput() {
      return document.querySelector('textarea')
          || document.querySelector('[contenteditable="true"]')
          || document.querySelector('input[type=text]');
    }
    // Send button: exact selectors first, then a fuzzy sweep over buttons whose
    // aria-label/class mentions send. The old build tried three exact selectors and
    // silently fell back to Enter; if Enter inserts a newline in this frontend,
    // the message never leaves the box (user-reported "it does not send").
    function findSend(el) {
      var sels = ['button[aria-label*="send" i]', 'button[aria-label*="\u53d1\u9001"]',
                  '[data-testid*="send" i]'];
      for (var i = 0; i < sels.length; i++) {
        var b = document.querySelector(sels[i]);
        if (b && b.disabled !== true) return b;
      }
      var cands = document.querySelectorAll('button, [role="button"]');
      for (var k = cands.length - 1; k >= 0; k--) {
        var c = cands[k];
        var label = (c.getAttribute('aria-label') || '') + ' ' + (c.className || '');
        if (/send|\u53d1\u9001/i.test(label) && c.disabled !== true) return c;
      }
      // Fallback: the last enabled button inside the input's own container
      var scope = el.closest('form') || (el.parentElement && el.parentElement.parentElement);
      if (scope) {
        var btns = scope.querySelectorAll('button');
        for (var j = btns.length - 1; j >= 0; j--) {
          if (btns[j].disabled !== true) return btns[j];
        }
      }
      return null;
    }
    var el = findInput();
    if (!el) {
      var cand = document.querySelector('[data-session-id]')
              || document.querySelector('a[href*="session"]')
              || document.querySelector('[role="listitem"]')
              || document.querySelector('[class*="session" i]');
      if (cand instanceof HTMLElement) {
        cand.click();
        return 'entering-session';
      }
      return 'no-input-found';
    }
    el.focus();
    if (el.tagName === 'TEXTAREA' || el.tagName === 'INPUT') {
      var proto = el.tagName === 'TEXTAREA'
        ? window.HTMLTextAreaElement.prototype
        : window.HTMLInputElement.prototype;
      var setter = Object.getOwnPropertyDescriptor(proto, 'value').set;
      setter.call(el, t);
      el.dispatchEvent(new Event('input', { bubbles: true }));
      el.dispatchEvent(new Event('change', { bubbles: true }));
    } else {
      // contenteditable (rich-text editors: Lexical / ProseMirror / Quill ...).
      //
      // v1.2.95 - REWRITE after live reproduce on the real WebUI (emulator + CDP).
      // Facts established by experiment:
      //  1) execCommand('insertText') on this Lexical editor DOUBLED the text: the
      //     framework imports the DOM mutation AND applies the beforeinput that
      //     execCommand fires. One write became two.
      //  2) Dispatching cancelable 'beforeinput' ALSO doubled.
      //  3) A synthetic ClipboardEvent('paste') with a DataTransfer is processed
      //     by Lexical's native paste pipeline EXACTLY ONCE - but ASYNCHRONOUSLY:
      //     the content lands a beat later. A synchronous check right after the
      //     dispatch still sees the old content, which made the old in-JS fallback
      //     kick in and double the text anyway.
      // So this snippet does ONE thing only: fire the paste. Whether the write
      // landed is checked by Kotlin (readSendText after FIRE_DELAY_MS), which
      // falls back to buildVoiceRepairJs (execCommand, safe for framework-less
      // editors) when the paste had no effect. No in-JS fallback here - any
      // synchronous second write races the paste and doubles the text.
      el.focus();
      // Ctrl+A keydown lets the editor select its own content first.
      el.dispatchEvent(new KeyboardEvent('keydown', {
        key: 'a', code: 'KeyA', keyCode: 65, ctrlKey: true, bubbles: true,
      }));
      var dt = new DataTransfer();
      dt.setData('text/plain', t);
      el.dispatchEvent(new ClipboardEvent('paste', {
        bubbles: true, cancelable: true, clipboardData: dt,
      }));
    }
    // Do NOT click send synchronously: a React controlled input updates its state
    // asynchronously, so right after dispatching the input event the send button may
    // still be disabled and the click is a no-op.
    //
    // v1.2.92 - regression fix. v1.2.88 deferred that click with
    // requestAnimationFrame to shave latency, but rAF callbacks ride on frame
    // production and a backgrounded WebView produces no frames: tapping "send"
    // while the app was in the background left the click queued, so the message
    // only went out the instant the user returned to the foreground (user: "it
    // only sends the moment I come back"). The pre-v1.2.88 setTimeout version
    // did work in the background.
    //
    // Now the app side owns both the commit and the verdict: we expose two
    // synchronous hooks and Kotlin calls them on its own Handler schedule, so no
    // JS timer and no frame is involved and background behaves like foreground.
    window.__dshEl = el;
    // One-shot ticket, handed out per write (see __dshFire). Not a time window:
    // v1.2.92 throttled by elapsed time, and Kotlin's "still in the box after 4s,
    // fire again" retry sailed straight through it (4s > 1.5s) - when the
    // frontend was slow to clear the box that second click sent the same text
    // again, which is exactly the "two copies" the user kept seeing.
    window.__dshTicket = false;
    window.__dshFire = function () {
      if (!window.__dshTicket) return 'consumed';   // already committed this text
      var target = (window.__dshEl && document.body.contains(window.__dshEl))
        ? window.__dshEl : findInput();
      if (!target) return 'no-input';
      var v = (target.tagName === 'TEXTAREA' || target.tagName === 'INPUT')
        ? (target.value || '') : (target.textContent || '');
      if (!v.trim()) return 'empty';   // nothing to send - never click on empty
      var btn = findSend(target);
      // Spend the ticket BEFORE clicking: whatever happens next, this text gets
      // exactly one commit attempt. Retrying a submit that already reached the
      // frontend is what produced duplicate messages.
      window.__dshTicket = false;
      if (btn) { btn.click(); return 'clicked'; }
      target.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', code: 'Enter', keyCode: 13, bubbles: true }));
      target.dispatchEvent(new KeyboardEvent('keyup',   { key: 'Enter', code: 'Enter', keyCode: 13, bubbles: true }));
      return 'enter';
    };
    // Verdict source: the frontend clears the input once the message is committed.
    window.__dshReadVal = function () {
      var e = window.__dshEl;
      if (!e) return 'none';
      if (!document.body.contains(e)) return 'gone';
      var v = (e.tagName === 'TEXTAREA' || e.tagName === 'INPUT') ? (e.value || '') : (e.textContent || '');
      return v.trim() ? 'text' : 'empty';
    };
    // Actual text in the box. Kotlin compares it against what it tried to send:
    // only a match means "this is our text, safe to (re)submit"; anything else is
    // the user's own typing and must be left alone.
    window.__dshText = function () {
      var e = window.__dshEl;
      if (!e) {
        var f = findInput();
        if (!f) return '';
        e = f;
      }
      if (!document.body.contains(e)) return '';
      var v = (e.tagName === 'TEXTAREA' || e.tagName === 'INPUT') ? (e.value || '') : (e.textContent || '');
      return v.trim();
    };
    // Hand out exactly one commit ticket for the text just written. Kotlin calls
    // __dshFire() later (250ms, on its own Handler) to spend it.
    // ⚠️ Grant MUST come after the hook block above: that block re-initialises
    // __dshTicket = false, and in v1.2.94 the grant sat before it - the reset
    // clobbered the grant, every fire returned 'consumed' and nothing was ever
    // submitted (the text just sat in the box). This order is load-bearing.
    window.__dshTicket = true;
    return 'ok:written';
  } catch (e) { return 'err:' + e.message; }
})();
""".trimIndent()
        }

        /**
         * 补写（v1.2.95）：paste 通道无效时的回退写入。
         *
         * 专用场景：编辑器**不响应合成 paste 事件**（无框架的普通 contenteditable）。
         * 此时 execCommand 是标准路径且没有框架去双处理 DOM 变更，安全。
         *
         * ⚠️ 绝不能对 Lexical 这类框架编辑器使用（实测翻倍）——调用方必须先
         * 用 readSendText 确认 paste 没生效才允许走这里（见 sendVoiceTextOnce）。
         */
        fun buildVoiceRepairJs(text: String): String {
            val escaped = org.json.JSONObject.quote(text)
            return """
(function(){
  try {
    var t = $escaped;
    var el = window.__dshEl && document.body.contains(window.__dshEl)
      ? window.__dshEl
      : (document.querySelector('textarea')
         || document.querySelector('[contenteditable="true"]')
         || document.querySelector('input[type=text]'));
    if (!el) return 'no-input';
    el.focus();
    function contentOf(node) { return (node.textContent || '').trim(); }
    function selectAllIn(node) {
      try {
        var sel = window.getSelection();
        var range = document.createRange();
        range.selectNodeContents(node);
        sel.removeAllRanges();
        sel.addRange(range);
      } catch (e) {}
    }
    selectAllIn(el);
    try { document.execCommand('insertText', false, t); } catch (e) {}
    if (contentOf(el) !== t.trim() && (el.tagName === 'TEXTAREA' || el.tagName === 'INPUT')) {
      var proto = el.tagName === 'TEXTAREA'
        ? window.HTMLTextAreaElement.prototype : window.HTMLInputElement.prototype;
      Object.getOwnPropertyDescriptor(proto, 'value').set.call(el, t);
      el.dispatchEvent(new Event('input', { bubbles: true }));
    }
    return 'repaired:' + contentOf(el).slice(0, 40);
  } catch (e) { return 'err:' + e.message; }
})();
""".trimIndent()
        }



        /** 页面缩放持久化：SharedPreferences 名 + key（设置页与引导共用）。
         *  屏幕朝向偏好（v1.2.120 起三态 portrait/landscape/auto）集中在
         *  OrientationPrefs，此处不再持有布尔键。 */
        private const val PREFS_UI = "dsh_ui"
        private const val KEY_PAGE_SCALE = "page_scale"

        /**
         * 页面缩放默认值（v1.2.122 收口）：100 = 不调用 setInitialScale，屏幕适配交给系统。
         * 可选档位在设置页（SettingsActivity.PAGE_SCALE_STEPS：80/90/100/110/120）。
         */
        private const val DEFAULT_PAGE_SCALE = 100

    }
}
