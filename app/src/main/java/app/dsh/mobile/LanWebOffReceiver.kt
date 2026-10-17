package app.dsh.mobile

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * 处理「局域网 WebUI 已开启」常驻通知里的 **关闭** 按钮（v1.2.188）。
 *
 * ## 为什么需要独立 Receiver
 * 关闭动作必须能在**任意时刻**生效：
 *  · 引擎可能已经停了（代理是独立 node 进程，仍可能在跑）；
 *  · 主界面/设置页可能都没在前台（用户直接在通知栏点关闭）。
 * 所以不能依赖 Activity；用 manifest 注册的 Receiver（exported=false，
 * 只接受本应用 PendingIntent 的广播），在 onReceive 里做三件事：
 *   ① 清掉「局域网访问」偏好（避免下次启动又被恢复）
 *   ② 停掉代理 node 进程（[app.dsh.mobile.engine.LanWebProxy.stop]）
 *   ③ 撤掉常驻通知
 */
class LanWebOffReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != app.dsh.mobile.engine.LanWebProxy.ACTION_LAN_OFF) return
        Log.i("LanWebOff", "LAN WebUI disabled from notification")
        runCatching { DshApp.setLanWebEnabled(context, false) }
        // 停代理会阻塞（等子进程退出），放后台线程
        Thread({
            runCatching { app.dsh.mobile.engine.LanWebProxy.stop() }
        }, "lan-off-receiver").apply { isDaemon = true }.start()
        // stop() 内部也会 hideLanNotice，这里再调一次兜底（stop 失败时通知也得消失）
        runCatching { app.dsh.mobile.engine.LanWebProxy.hideLanNotice(context) }
    }
}
