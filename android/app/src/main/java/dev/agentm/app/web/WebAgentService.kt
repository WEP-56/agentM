package dev.agentm.app.web

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

class WebAgentService : Service() {
    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("web-agents", "Agent Web 服务", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 20, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 21, Intent(this, WebAgentService::class.java).setAction("stop"), PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(this, "web-agents").setSmallIcon(R.drawable.ic_agentm).setContentTitle("agentM Web 服务")
            .setContentText("返回工作台可打开或停止 Agent").setContentIntent(open).setOngoing(true)
            .addAction(Notification.Action.Builder(null, "停止 Web 服务", stop).build()).build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(4, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE) else startForeground(4, notification)
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val manager = (application as AgentMApplication).webAgents
        if (intent?.action == "stop") { manager.stopAll(); if (!manager.active) stopSelf() }
        else if (intent != null) manager.launch(intent.getStringExtra("kind") ?: "", intent.getStringExtra("id") ?: "")
        if (!manager.active) stopSelf()
        return START_NOT_STICKY
    }
    override fun onDestroy() { (application as AgentMApplication).webAgents.stopAll(); super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null
    companion object {
        fun start(context: Context, session: WebAgentManager.Session) = ContextCompat.startForegroundService(context,
            Intent(context, WebAgentService::class.java).putExtra("kind", session.kind).putExtra("id", session.id))
    }
}
