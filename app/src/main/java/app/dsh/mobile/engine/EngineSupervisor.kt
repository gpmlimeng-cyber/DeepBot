package app.dsh.mobile.engine

import app.dsh.mobile.R

import android.content.Context
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * 引擎监督器：冷启动 → 健康检查 → 运行中 → 崩溃退避重启 的完整状态机。
 *
 * 状态流转：
 *   Idle → Installing → Starting → Healthy(port)
 *        ↘ Backoff(delay, attempt) ↘ Failed(reason) → Stopped
 * 连续健康一次即重置退避计数；达到 MAX_RESTART 后进入 Failed 终态。
 *
 * 自愈层（ProfileGuardian）： Healthy 时快照配置；同签名连续失败触发
 * last-good 回滚；仍失败进入安全模式（归档坏配置空跑）。
 * SafeMode 会作为独立状态暴露给 UI 展示「引擎运行于安全模式」。
 */
class EngineSupervisor(private val ctx: Context) {

    sealed interface State {
        data object Idle : State
        data object Installing : State
        data object Starting : State

        /** 正常健康；tokenUrl = 0.2.0+ WebUI 认证地址（engine.log 的 dsh web: 输出） */
        data class Healthy(val port: Int, val tokenUrl: String? = null) : State

        /** 安全模式：配置被隔离后以空配置拉起，功能受限但可用 */
        data class SafeMode(val port: Int, val tokenUrl: String? = null) : State
        data class Backoff(val delayMs: Long, val attempt: Int) : State
        data class Failed(val reason: String) : State
        data object Stopped : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state

    /** 运行时解压进度 0f..1f；null = 不在解压中（UI 据此切换 indeterminate） */
    private val _installProgress = MutableStateFlow<Float?>(null)
    val installProgress: StateFlow<Float?> = _installProgress

    /** 当前健康端口，UI 层据此加载 WebView */
    val healthyPort: Int get() = when (val s = _state.value) {
        is State.Healthy -> s.port
        is State.SafeMode -> s.port
        else -> EngineConfig.activePort
    }

    /** 从 engine.log 提取**本次启动**的 `dsh web:` 输出（带 token 完整 URL）。
     *  0.2.0 起 WebUI 强制认证，裸 127.0.0.1:3080 会显示认证提示页。
     *  引擎 stdout 由 EngineProcess 泵入 engine.log（环形 2MB，最新在末尾）。
     *  只搜 [logOffsetAtSpawn] 之后的日志段：健康探测可能早于 stdout 送达，
     *  搜全文会拿到上一次启动的旧 token（已失效 → WebView 401 白页，实测）。
     *  stdout 泵有毫秒级延迟，最多重试 ~600ms 等该行出现。 */
    private fun extractTokenUrl(): String? = runCatching {
        repeat(6) {
            val f = logFile()
            if (f.isFile) {
                val text = f.readText()
                val from = logOffsetAtSpawn.coerceIn(0L, text.length.toLong()).toInt()
                val seg = text.substring(from)
                Regex("dsh web: (http://127\\.0\\.0\\.1:\\d+/\\?token=\\S+)")
                    .findAll(seg).lastOrNull()?.groupValues?.get(1)
                    ?.let { hit -> return@runCatching hit }
            }
            Thread.sleep(100)
        }
        null
    }.getOrNull()

    /** WebUI 应加载的地址：优先带 token 的完整 URL，裸地址仅作回退 */
    fun webUrl(): String = when (val s = _state.value) {
        is State.Healthy -> s.tokenUrl ?: "http://127.0.0.1:${s.port}/"
        is State.SafeMode -> s.tokenUrl ?: "http://127.0.0.1:${s.port}/"
        else -> "http://127.0.0.1:${EngineConfig.activePort}/"
    }

    private var process: EngineProcess? = null
    /** 本次引擎启动时 engine.log 的长度：extractTokenUrl 只搜此偏移之后的日志段 */
    @Volatile private var logOffsetAtSpawn = 0L
    private var loopJob: Job? = null
    private var userStop = false
    private var scopeRef: CoroutineScope? = null

    /**
     * 用户是否显式退出过且尚未重新启动（通知栏「退出」置位）。
     * 期间 MainActivity.onResume 的自动拉起必须跳过，否则「退出」会被生命周期
     * 立刻撤销（用户实测：通知栏点退出 → 引擎又被拉起来 ✗）。
     * 重新启动（重启按钮 / 权限模式切换等显式动作）会清掉该状态。
     */
    fun isUserStopped(): Boolean = userStop && (loopJob?.isActive != true)
    /** 重启互斥：重复点击重启按钮是 no-op（并发 stop/start 会互相踩踏，实测事故） */
    @Volatile private var restarting = false
    /** 用户重启的起点时间戳（healthy 时打印总耗时，用于定位「重启很久」这类反馈） */
    @Volatile private var restartStartedAt: Long? = null
    private val guardian by lazy { ProfileGuardian(ctx) }

    fun start(scope: CoroutineScope) {
        scopeRef = scope
        if (loopJob?.isActive == true) return
        userStop = false
        loopJob = scope.launch(Dispatchers.Default) { supervisionLoop() }
    }

    fun stop(graceMs: Long = 10_000) {
        userStop = true
        loopJob?.cancel()
        process?.stop(graceMs)
        process = null
        _state.value = State.Stopped
    }

    /**
     * 热重启：完整走一遍 stop → start（TERM→KILL 优雅停止 + 监督循环重进）。
     * 与进程被杀后的自动退避不同，这是用户显式动作：退避计数天然从零开始，
     * guardian 的 Healthy 快照/计数不受影响。
     *
     * ⚠️ v1.2.37 修复（用户实测「点三下才重启」）：旧实现里
     *   a) `process.stop()` 要等引擎优雅退出**最长 10 秒**，且 Stopped 状态在其之后才置位
     *      → 第一次点击界面毫无反馈（用户以为没点到，继续点）；
     *   b) 调用方（MainActivity/SettingsActivity）每次点击各起一条线程，多点几下就是
     *      多条 restart 线程并发 stop/start 互相踩踏 → 「待启动→进程异常→2 秒后自愈重启」。
     * 现在：立即置 Starting（UI 立刻有反馈）、整个重启跑在监督循环同一个 scope 上、
     * [restarting] 互斥保证重复点击是 no-op（幂等）。本方法立即返回、不阻塞调用线程。
     */
    fun restart() {
        val scope = scopeRef ?: return
        if (restarting) {
            Log.i(TAG, "restart already in progress, ignoring duplicate tap")
            return
        }
        Log.i(TAG, "restart: hot restart requested by user")
        restarting = true
        val t0 = System.currentTimeMillis()
        restartStartedAt = t0
        scope.launch(Dispatchers.IO) {
            try {
                _state.value = State.Starting
                // 用户重启：3.5s 宽限（足够引擎落盘收尾；TERM 无响应时尽快 KILL，不再干等 10s）
                stop(graceMs = 3_500)
                Log.i(TAG, "restart: previous engine stopped (${System.currentTimeMillis() - t0}ms)")
                _state.value = State.Starting   // stop() 会置 Stopped，重启路径立即回到启动中
                start(scope)
            } catch (e: Exception) {
                Log.w(TAG, "restart failed: ${e.message}")
            } finally {
                restarting = false
            }
        }
    }

    /** 手动导出引擎日志（用户反馈通道） */
    fun logFile(): File = File(EngineConfig.engineRoot(ctx), "engine.log")

    /**
     * 失败签名：用「引擎日志尾部 + 退出码」的哈希近似。
     * 确定性崩溃（坏配置）每次堆栈一致 → 同签名；
     * 偶发崩溃（OOM/被杀）尾部随机 → 不同签名不累计。
     */
    private fun failureSignature(status: Int?): String {
        // 签名必须稳定：崩溃堆栈里的行号/内存地址/时间戳每次都变，原文哈希会让
        // streak 永远重置 → 永远到不了阶段阈值（自愈失效实测根因）。
        // 数字全部归一为 #，保留错误结构与字面（不同异常仍然不同签名）。
        val tail = runCatching {
            logFile().readText().takeLast(4096)
                .lineSequence()
                .filter { it.contains("Error") || it.contains("at ") }
                .toList()
                .takeLast(12)
                .joinToString("\n") { it.replace(Regex("\\d+"), "#") }
        }.getOrDefault("")
        return "$status:${tail.hashCode()}"
    }

    private suspend fun supervisionLoop() {
        var backoffIndex = 0
        while (kotlinx.coroutines.currentCoroutineContext().isActive && !userStop) {
            // 仅当引擎「自行死亡」（fork 失败 / 启动期退出）才值得让 guardian 定罪；
            // 健康超时自杀、安装异常、Healthy 后运行中退出都不算配置崩溃。
            var deterministicFailure: String? = null
            val bootT0 = System.currentTimeMillis()
            var stepT = bootT0
            fun step(name: String) {
                val now = System.currentTimeMillis()
                Log.i(TAG, "boot step $name: ${now - stepT}ms (total ${now - bootT0}ms)")
                stepT = now
            }
            try {
                // 启动前先把被误隔离的引擎内置 patch 恢复（自愈；防 ENOENT fail-loud）
                val healed = withContext(Dispatchers.IO) { guardian.restoreQuarantinedBuiltinOverlays() }
                step("guardian-restore")
                if (healed > 0) Log.w(TAG, "guardian: restored $healed quarantined builtin overlay(s)")

                // Agent 上下文种子（m1.35）：幂等预写 $HOME/AGENTS.md（dsh 原生 user-global
                // 指令），让 Agent 首轮就带 Android 世界观，省掉环境探索 token
                withContext(Dispatchers.IO) { AgentContextSeed.ensure(ctx) }
                step("agent-seed")

                // 孤儿进程回收（v1.2.104）：**必须早于桥 bind 与引擎 fork** ——
                // 历史孤儿会攥着继承来的 3080/3083 监听 socket 不撒手（无 CLOEXEC，
                // 见 OrphanReaper 顶部），不先清掉，桥会静默 bind 失败、引擎会起不来。
                withContext(Dispatchers.IO) { OrphanReaper.reap(ctx) }
                step("orphan-reap")

                // Agent 能力桥（v1.1.0）：notify/scr 的 HTTP 后端，全模式启动
                withContext(Dispatchers.IO) { AgentBridge.start(ctx) }
                step("bridge")

                // Root 提权自愈（m1.27）：非 Root 模式启动前，若 dsh-home 被上次 Root 引擎
                // 污染成 root 属主（EACCES 读不了），chown 回 app uid，否则引擎必崩。
                if (Privilege.getMode(ctx) != PrivMode.ROOT) {
                    val needFix = withContext(Dispatchers.IO) { Privilege.dshHomeNeedsOwnershipFix(ctx) }
                    if (needFix) {
                        Log.w(TAG, "found dsh-home files owned by non-app uid (likely Root-mode residue); fixing ownership")
                        withContext(Dispatchers.IO) { Privilege.fixHomeOwnership(ctx) }
                    }
                }
                step("home-owner-check")

                _state.value = State.Installing
                withContext(Dispatchers.IO) {
                    var lastPct = -1
                    RuntimeInstaller(ctx).ensureInstalled { frac ->
                        // 仅整 1% 变化时发布，避免 2 万次无效状态更新
                        val pct = (frac * 100).toInt()
                        if (pct != lastPct) {
                            lastPct = pct
                            _installProgress.value = frac
                        }
                    }
                }
                _installProgress.value = null
                step("runtime-install")

                // 用户数据外置（v1.2.99）：sessions/workspaces 迁到公开目录（软链），
                // profiles 保持私有（上游强制凭证 0600，共享存储给不了权限位）。
                // 必须在 spawn 前完成 —— 引擎启动即读这些目录。
                withContext(Dispatchers.IO) { EngineConfig.ensureUserDataExternal(ctx) }
                step("user-data")

                // DeepBot 出厂预装（v1.2.122）：把随包的 ds-bot 落到本 App 的 web profile
                // 并默认启用；幂等（版本戳），失败只记日志、不阻断启动。
                withContext(Dispatchers.IO) { DeepBotSeed.ensure(ctx) }
                step("deepbot-seed")

                _state.value = State.Starting
                // 端口可能已被别的壳/旧实例占据（EADDRINUSE → 重启死循环）：启动前先选一个可绑定端口。
                // resolvePort() 幂等，结果写入 EngineConfig.activePort，供下文健康探测/WebView/Shizuku 桥共用。
                EngineConfig.resolvePort()
                val proc = withContext(Dispatchers.IO) { spawnEngine() }
                process = proc
                step("spawn")

                val healthy = pollHealth(EngineConfig.activePort, proc)
                step("health-poll")
                if (healthy) {
                    withContext(Dispatchers.IO) {
                        guardian.resetCrashStreak()
                        guardian.snapshotLastGood()
                    }
                    backoffIndex = 0
                    // 引擎 healthy = 待重启标记解除（激活/停用/卸载的扩展自此刻生效）
                    app.dsh.mobile.engine.ExtensionManager.clearPendingRestart()
                    val safe = guardian.inSafeMode()
                    val tokenUrl = extractTokenUrl()
                    val t1 = System.currentTimeMillis()
                    val restartMs = restartStartedAt?.let { " (total restart took ${t1 - it}ms)" } ?: ""
                    restartStartedAt = null
                    Log.i(TAG, (if (safe) "engine healthy in SAFE MODE on :${EngineConfig.activePort}" else "engine healthy on :${EngineConfig.activePort}") + restartMs)
                    _state.value =
                        if (safe) State.SafeMode(EngineConfig.activePort, tokenUrl)
                        else State.Healthy(EngineConfig.activePort, tokenUrl)
                    // Shizuku 模式：引擎就绪后启动 ADB 级访问桥（shz 包装器回呼用）；其他模式自动关停
                    withContext(Dispatchers.IO) {
                        ShizukuHttpBridge.start(ctx, EngineConfig.activePort)
                    }
                    // 阻塞等待进程退出（被杀/崩溃）。Healthy 后退出视为资源类偶发，
                    // 不计入 guardian（那是普通退避该管的事，与配置无关）。
                    val status = proc.exitFuture.get()
                    if (userStop) break
                    Log.w(TAG, "engine exited, raw status=0x${status.toString(16)}")
                } else if (proc.exitFuture.isDone) {
                    // 启动期内进程自己死了——唯一进入自愈判定的信号
                    deterministicFailure = failureSignature(lastExitStatus)
                } else {
                    // 健康检查超时：引擎没死是我们主动停的——很可能是慢启动，
                    // 绝不计入崩溃计数（m1.6.4 真机误伤教训）
                    proc.stop()
                }
            } catch (e: EngineStartException) {
                if (userStop) break
                Log.e(TAG, "supervision failure", e)
                deterministicFailure = failureSignature(null)
            } catch (e: Exception) {
                if (userStop) break
                Log.e(TAG, "supervision failure", e)
            }

            // ---- 自愈判定：仅对「引擎真死」且签名连续一致时逐步升级 ----
            // 【v1.2.22】Root→普通切换后 root 孤儿 node 霸占 3080 → 普通引擎
            // EADDRINUSE 真死循环（孤儿在服务所以页面/AI 看似正常）。检测到
            // EADDRINUSE 立即用 su 清掉 engine/bin/node 的全部残留后重试。
            if (deterministicFailure?.contains("EADDRINUSE") == true) {
                // 【v1.2.32】孤儿两种来源：Root 残留（需 su）/ 普通模式 App 被强杀后遗留
                // （node 与 App 同 uid，直接 pkill 即可）。此前仅 su 分支 → 普通模式
                // 孤儿永占 3080 → 无限 EADDRINUSE 重启（用户实测 62 次仍在增长）。
                runCatching {
                    ProcessBuilder("/system/bin/sh", "-c",
                        "pkill -9 -f 'files/engine/bin/node' 2>/dev/null; true")
                        .start().waitFor()
                }
                // v1.2.108：走 runSu —— 旧实现硬编码 `su -c`，toybox 风格 su 报
                // `invalid uid/gid '-c'` 后被 runCatching 吞掉，root 孤儿杀不掉 →
                // 端口一直占着 → EADDRINUSE 无限重启（正是上一条注释描述的现场）。
                Privilege.runSu("pkill -9 -f 'files/engine/bin/node' 2>/dev/null; true")
                Log.w(TAG, "EADDRINUSE: killed orphan engine node(s)")
                deterministicFailure = null   // 已处置，不计入 guardian（与配置无关）
                backoffIndex = 0
                delay(1500)                   // 等端口释放再下一轮
            }
            deterministicFailure?.let { sig ->
                when (
                    runCatching { guardian.onFailure(sig) }
                        .getOrElse { ProfileGuardian.Action.NONE }
                ) {
                    ProfileGuardian.Action.ROLLED_BACK -> {
                        Log.w(TAG, "guardian: rolled back profiles to last-good")
                        backoffIndex = 0 // 给恢复后的启动全新的退避额度
                    }
                    ProfileGuardian.Action.SAFE_MODE -> {
                        Log.e(TAG, "guardian: verified persistent crash, entering SAFE MODE")
                        backoffIndex = 0
                    }
                    ProfileGuardian.Action.NONE -> {}
                }
            }

            // 统一走退避重启
            backoffIndex++
            if (backoffIndex > EngineConfig.MAX_RESTART) {
                _state.value = State.Failed(ctx.getString(R.string.error_restart_failed_streak, EngineConfig.MAX_RESTART))
                return
            }
            val delayMs = EngineConfig.BACKOFF_STEPS[
                (backoffIndex - 1).coerceAtMost(EngineConfig.BACKOFF_STEPS.size - 1)
            ]
            _state.value = State.Backoff(delayMs, backoffIndex)
            delay(delayMs)
        }
    }

    /** 最近一次子进程退出码；仅在进程已退出后可读，避免阻塞 */
    private val lastExitStatus: Int?
        get() = process?.exitFuture?.takeIf { it.isDone }?.get()

    private fun spawnEngine(): EngineProcess {
        // 【v1.2.32】启动前孤儿清理：App 被强杀/force-stop 后，引擎 node 可能成孤儿
        // 继续占着 3080（普通模式与 App 同 uid；Root 模式需 su）。此时新引擎必然
        // EADDRINUSE 无限重启（用户实测 62 次）。pkill 只匹配我们的引擎路径，
        // 不会误伤其他进程；正常重启（旧引擎已 stop）端口已释放，此处为 no-op。
        cleanupOrphanEngine()
        // 【v1.2.36】记录本次启动的日志起点：token 提取只认这段之后的输出。
        // 否则健康探测先于 stdout 泵送达 "dsh web: ?token=" 行时，会提取到上一次
        // 引擎的旧 token → WebView 加载已失效地址 → "Webpage not available"
        // （模拟器实测：重启后页面 401，engine.log 中新旧 token 并存）。
        logOffsetAtSpawn = runCatching { logFile().length() }.getOrDefault(0L)
        val mode = Privilege.getMode(ctx)
        var suPath: String? = null
        if (mode == PrivMode.ROOT) {
            // Root 保护壳：先备份用户资产 dsh-home，再以 su 整体提权启动引擎。
            // 即便 Root 引擎误改 dsh-home，用户仍可回滚；备份失败不阻断启动，仅告警。
            val backup = Privilege.backupHome(ctx)
            Log.w(TAG, if (backup != null) "ROOT mode: dsh-home backed up to $backup" 
                  else "ROOT mode: WARNING — dsh-home backup failed")
            suPath = Privilege.findSu() ?: throw EngineStartException(
                ctx.getString(R.string.error_root_no_su)
            )
        }
        return EngineProcess.spawn(
            nodeBin = EngineConfig.nodeBin(ctx),
            entryJs = EngineConfig.dshEntry(ctx),
            cwd = EngineConfig.workspaces(ctx),
            env = EngineConfig.buildEnv(ctx, EngineConfig.activePort),
            logFile = logFile(),
            suPath = suPath,
            patchPath = runCatching { EngineConfig.ensureAndroidOverlay(ctx).absolutePath }.getOrNull(),
        )
    }

    /** 端口被占则清理孤儿引擎 node（同 uid pkill，su 兜底），轮询到端口释放后返回。 */
    private fun cleanupOrphanEngine() {
        if (!isPortBusy(EngineConfig.activePort, 300)) return
        Log.w(TAG, "port ${EngineConfig.activePort} occupied before start — killing orphan engine node(s)")
        killOrphanNodes()
        killOrphanNodesByUid()
        // v1.2.121：轮询到端口真的空出来（最多 5s）。旧实现只等 1.5s，
        // 残留进程还没退干净就 spawn，新实例第一次健康检查遂失败（实测 6 次冷启动
        // 有 3 次卡满 45s 超时）。1 秒后仍占用 → 按 uid 补杀一次（只匹配带 bin/node 的）。
        val deadline = System.currentTimeMillis() + 5_000
        var secondKill = false
        while (System.currentTimeMillis() < deadline) {
            if (!isPortBusy(EngineConfig.activePort, 200)) return
            if (!secondKill && System.currentTimeMillis() >= deadline - 4_000) {
                secondKill = true
                Log.w(TAG, "port ${EngineConfig.activePort} still busy — second kill by uid")
                killOrphanNodesByUid()
            }
            Thread.sleep(100)
        }
        // v1.2.122：清不掉就别在同一个端口上硬 spawn —— 那必然是 EADDRINUSE，
        // 引擎秒退、进退避重试，用户看到的就是「启动页挂很久」。换一个能绑的端口。
        val stuck = EngineConfig.activePort
        EngineConfig.resetPort()
        val next = EngineConfig.resolvePort()
        if (next == stuck) {
            Log.w(TAG, "port $stuck still busy after 5s and no other port is free — spawning anyway")
        } else {
            Log.w(TAG, "port $stuck still busy after 5s — switched to $next")
        }
    }

    /** 端口是否有人监听（connect 成功 = 占用） */
    private fun isPortBusy(port: Int, timeoutMs: Int): Boolean = runCatching {
        java.net.Socket().use { s ->
            s.connect(java.net.InetSocketAddress("127.0.0.1", port), timeoutMs)
            true
        }
    }.getOrDefault(false)

    /** 杀掉本应用的孤儿引擎 node（同 uid 用 pkill，su 兜底） */
    private fun killOrphanNodes() {
        runCatching {
            ProcessBuilder("/system/bin/sh", "-c",
                "pkill -9 -f 'files/engine/bin/node' 2>/dev/null; true")
                .start().waitFor()
        }
        // v1.2.108：走 runSu（同上，toybox 设备上旧写法静默失败）
        Privilege.runSu("pkill -9 -f 'files/engine/bin/node' 2>/dev/null; true")
    }

    /**
     * 按 uid 补杀一次：只匹配命令行里带 `bin/node` 的进程（引擎与扩展的 node），
     * 不碰用户/AI 自己起的裸 `node xxx.js`（命令行里没有 bin/node）。
     * toybox pkill 支持 -U（实测 /system/bin/pkill --help）。
     */
    private fun killOrphanNodesByUid() {
        val cmd = "pkill -9 -U \$(id -u) -f 'bin/node' 2>/dev/null; true"
        runCatching {
            ProcessBuilder("/system/bin/sh", "-c", cmd).start().waitFor()
        }
        Privilege.runSu(cmd)
    }

    /** 稳定窗：windowMs 内进程退出返回 false（启动失败），挺过窗口返回 true */
    private suspend fun awaitStable(proc: EngineProcess, windowMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + windowMs
        while (System.currentTimeMillis() < deadline) {
            if (proc.exitFuture.isDone) return false
            delay(250)
        }
        return !proc.exitFuture.isDone
    }

    /** 轮询 http://127.0.0.1:port 直到响应/超时/子进程提前死亡 */
    private suspend fun pollHealth(port: Int, proc: EngineProcess): Boolean = withContext(Dispatchers.IO) {
        // v1.2.122：改用单调时钟。之前用 System.currentTimeMillis()，用户时钟/时区调整
        // 或对齐校正都可能让 deadline 提前/滞后，日志里出现过 92s 的健康轮询（远超 12s 上限）。
        val started = SystemClock.elapsedRealtime()
        val deadline = started + EngineConfig.HEALTH_TIMEOUT_MS
        var attempts = 0
        var pausedMs = 0L
        val url = "http://127.0.0.1:$port/"
        var last = SystemClock.elapsedRealtime()
        // 兜底上限：后台不推进窗口，但也不能无限等（5 分钟后一律判定失败去自愈）
        val hardDeadline = started + 5 * 60_000L
        while (SystemClock.elapsedRealtime() < deadline + pausedMs &&
            SystemClock.elapsedRealtime() < hardDeadline &&
            kotlinx.coroutines.currentCoroutineContext().isActive
        ) {
            // 后台冻结不计入窗口：App 切走时系统会挂起进程，回来时窗口若已过期，
            // 会把一个可能已就绪的引擎杀掉（实测过一次 92254ms 的 health-poll）。
            val now = SystemClock.elapsedRealtime()
            if (!app.dsh.mobile.AppVisibility.foreground) pausedMs += (now - last)
            last = now
            attempts++
            // 子进程已死就别干等超时——立即返回走快速退避重试
            if (proc.exitFuture.isDone) return@withContext false
            if (probeOnce(port)) return@withContext true
            delay(EngineConfig.HEALTH_INTERVAL_MS)
        }
        // 超时判定前再探一次端口：慢启动的引擎可能在最后一个窗口边缘刚好起来
        if (!proc.exitFuture.isDone && probeOnce(port)) return@withContext true
        // 超时留痕 + 现场取证（v1.2.122）：端口、进程存活、各候选端口是否应答、引擎 stdout 尾巴。
        // 只有这几样一起打，才能分清「引擎没起来」/「起在别的端口」/「端口被残留占着」。
        val elapsed = SystemClock.elapsedRealtime() - started
        Log.w(TAG, "health poll timeout on :$port after $attempts attempt(s), " +
            "${elapsed}ms elapsed, ${pausedMs}ms backgrounded, procAlive=${!proc.exitFuture.isDone}")
        val alive = scanPorts(port)
        if (alive.isNotEmpty()) {
            Log.w(TAG, "health: engine answers on ${alive.joinToString(",")} (expected :$port)")
        }
        dumpEngineLogTail(8)
        // 引擎起在别的端口上（换端口逻辑生效、或上一轮残留换过端口）：直接认它，别杀
        val other = alive.firstOrNull { it != port }
        if (other != null) {
            EngineConfig.adoptPort(other)
            Log.w(TAG, "health: adopting :$other (expected :$port)")
            return@withContext true
        }
        false
    }

    /** 扫一遍候选端口，返回当前能应答的那些（v1.2.122 定位「起在别处」） */
    private fun scanPorts(expected: Int): List<Int> {
        val out = ArrayList<Int>()
        for (p in expected..(expected + EngineConfig.PORT_SCAN_WINDOW)) {
            if (probeOnce(p)) out.add(p)
        }
        return out
    }

    /** 把引擎 stdout 日志的最后 n 行写进 logcat（超时现场的唯一直接证据） */
    private fun dumpEngineLogTail(n: Int) {
        runCatching {
            val f = logFile()
            if (!f.isFile) return
            val lines = f.readLines()
            val tail = lines.takeLast(n)
            tail.forEachIndexed { i, line ->
                Log.w(TAG, "engine[${lines.size - tail.size + i + 1}] ${line.take(200)}")
            }
        }
    }

    /** 单次健康探测：HTTP 200..499 即算起来（Web UI 有响应） */
    private fun probeOnce(port: Int): Boolean = runCatching {
        val conn = URL("http://127.0.0.1:$port/").openConnection() as HttpURLConnection
        conn.connectTimeout = 2_000
        conn.readTimeout = 2_000
        val code = conn.responseCode
        conn.disconnect()
        code in 200..499
    }.getOrDefault(false)

    companion object {
        private const val TAG = "EngineSupervisor"
    }
}
