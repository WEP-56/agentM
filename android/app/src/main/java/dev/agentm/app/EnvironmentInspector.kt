package dev.agentm.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.os.StatFs
import android.webkit.WebView
import androidx.core.app.NotificationManagerCompat
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class EnvironmentInspector(private val app: AgentMApplication) {
    fun inspect(): JSONObject {
        val storage = StatFs(app.filesDir.absolutePath)
        val engine = File(app.applicationInfo.nativeLibraryDir, "libproot.so")
        val linux = app.linux.snapshot()
        val probes = JSONArray()
            .put(probe("设备架构", Build.SUPPORTED_ABIS.joinToString(), "passed"))
            .put(probe("应用数据目录", if (app.filesDir.canWrite()) "可写" else "不可写", if (app.filesDir.canWrite()) "passed" else "failed"))
            .put(probe("设备 Shell", "/system/bin/sh", if (File("/system/bin/sh").canExecute()) "passed" else "failed"))
            .put(probe("PTY", "Termux JNI · arm64 / x86_64", "available"))
            .put(probe("Linux 执行引擎", if (engine.exists()) "proot ${linux.getString("engineVersion")}" else "尚未安装", if (app.linux.ready) "passed" else "available"))
            .put(probe("Ubuntu 用户态", linux.getString("message"), if (app.linux.ready) "passed" else "pending"))
        val notifications = NotificationManagerCompat.from(app).areNotificationsEnabled() &&
            (Build.VERSION.SDK_INT < 33 || app.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)
        return JSONObject()
            .put("protocolVersion", 1).put("appVersion", BuildConfig.VERSION_NAME)
            .put("device", JSONObject().put("model", "${Build.MANUFACTURER} ${Build.MODEL}")
                .put("androidVersion", Build.VERSION.RELEASE).put("sdk", Build.VERSION.SDK_INT)
                .put("abis", JSONArray(Build.SUPPORTED_ABIS.toList()))
                .put("webViewVersion", WebView.getCurrentWebViewPackage()?.versionName ?: "unknown")
                .put("availableBytes", storage.availableBytes).put("totalBytes", storage.totalBytes)
                .put("workspace", File(app.filesDir, "workspaces").absolutePath))
            .put("environment", linux.put("status", linux.getString("phase")).put("reason", linux.getString("message")).put("probes", probes))
            .put("terminal", app.terminals.snapshot())
            .put("packages", app.packages.snapshot())
            .put("permissions", JSONObject().put("notifications", notifications).put("storage", true)
                .put("battery", app.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(app.packageName)))
            .put("logs", app.logs.snapshot())
    }

    private fun probe(name: String, detail: String, status: String) =
        JSONObject().put("name", name).put("detail", detail).put("status", status)
}
