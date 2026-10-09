package dev.agentm.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.content.ContextCompat

class TerminalService : Service() {
    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel("terminal", "开发终端", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, TerminalActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, TerminalService::class.java).setAction("stop"), PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(this, "terminal").setSmallIcon(R.drawable.ic_agentm)
            .setContentTitle("agentM 开发终端").setContentText("会话在后台运行，点击返回终端")
            .setOngoing(true).setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "停止", stop).build()).build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(1, notification)
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val manager = (application as AgentMApplication).terminals
        if (intent?.action == "stop") {
            manager.stop()
            if (manager.session?.isRunning != true) stopSelf()
        } else try {
            if (intent?.action == "restart") manager.restart() else manager.open(intent?.getStringExtra("kind") ?: manager.kind)
        } catch (e: Exception) {
            (application as AgentMApplication).logs.add("terminal", "PTY 创建失败：${e.javaClass.simpleName}", "E")
            stopSelf()
        }
        // A killed terminal must not silently replay a command or create a new shell.
        return START_NOT_STICKY
    }
    override fun onDestroy() { (application as AgentMApplication).terminals.stop(); super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null
    companion object {
        fun restart(context: Context) = ContextCompat.startForegroundService(context, Intent(context, TerminalService::class.java).setAction("restart"))
        fun start(context: Context, kind: String = "deviceShell") = ContextCompat.startForegroundService(context, Intent(context, TerminalService::class.java).putExtra("kind", kind))
    }
}
