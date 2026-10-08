package dev.agentm.app.linux

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

class LinuxInstallService : Service() {
    private val worker = Executors.newSingleThreadExecutor()
    private val manager get() = (application as AgentMApplication).linux
    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("linux-install", "Linux 安装", NotificationManager.IMPORTANCE_LOW))
        if (Build.VERSION.SDK_INT >= 34) startForeground(2, notification("准备安装 Linux"), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(2, notification("准备安装 Linux"))
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        worker.execute {
            try { manager.install { state -> getSystemService(NotificationManager::class.java).notify(2, notification(state.message)) } }
            catch (error: Exception) { manager.serviceFailure(error) }
            finally { stopSelf(startId) }
        }
        return START_NOT_STICKY
    }
    private fun notification(message: String): Notification {
        val open = PendingIntent.getActivity(this, 2, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, "linux-install").setSmallIcon(R.drawable.ic_agentm).setContentTitle("agentM · Ubuntu 安装")
            .setContentText(message).setContentIntent(open).setOngoing(true).build()
    }
    override fun onDestroy() { manager.cancel(); worker.shutdownNow(); super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null
    companion object {
        fun start(context: Context) = ContextCompat.startForegroundService(context, Intent(context, LinuxInstallService::class.java))
    }
}
