package app.dsh.mobile

/**
 * 进程级前台标记（v1.2.122）。
 *
 * 用来回答一个具体问题：引擎的 12 秒健康检查窗口，不该把 App 在后台被系统冻结的
 * 时间算进去。2026-10-17 的日志里出现过 `health-poll: 92254ms`（远超 12 秒上限），
 * 现场就是「启动页还停着，用户切走了，过了 80 多秒回来」——轮询循环本身没有越界，
 * 是等待期间进程被挂起，回来时窗口早已过期，于是把一个可能已经就绪的引擎杀掉。
 *
 * EngineSupervisor 在后台期间不推进健康检查的截止时间，回到前台继续计时。
 */
object AppVisibility {
    @Volatile
    var foreground: Boolean = false
        private set

    fun onResume() {
        foreground = true
    }

    fun onPause() {
        foreground = false
    }
}
