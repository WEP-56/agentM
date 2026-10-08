package dev.agentm.app.packages

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
import dev.agentm.app.AgentMApplication
import dev.agentm.app.MainActivity
import dev.agentm.app.R
import java.util.concurrent.Executors

class PackageService : Service() {
    private val worker = Executors.newSingleThreadExecutor()
    private val manager get() = (application as AgentMApplication).packages
    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("packages", "开发工具与 Agent", NotificationManager.IMPORTANCE_LOW))
        if (Build.VERSION.SDK_INT >= 34) startForeground(3, notification("准备软件管理任务"), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(3, notification("准备软件管理任务"))
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        worker.execute {
            try { manager.execute { getSystemService(NotificationManager::class.java).notify(3, notification(it)) } }
            catch (error: Exception) { manager.failure(error) }
            finally { stopSelf(startId) }
        }
        return START_NOT_STICKY
    }
    private fun notification(message: String): Notification = Notification.Builder(this, "packages")
        .setSmallIcon(R.drawable.ic_agentm).setContentTitle("agentM · 软件管理").setContentText(message).setOngoing(true)
        .setContentIntent(PendingIntent.getActivity(this, 3, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)).build()
    override fun onDestroy() { worker.shutdownNow(); super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null
    companion object {
        fun start(context: Context) = ContextCompat.startForegroundService(context, Intent(context, PackageService::class.java))
    }
}
