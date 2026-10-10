package dev.agentm.app.files

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.ExecutorService

/** SAF grants cover import/export; only staged exports receive a temporary sharing grant. */
class FileTransfers(private val activity: ComponentActivity, private val files: UbuntuFiles, private val worker: ExecutorService) {
    private data class Job(val id: String, val kind: String, val path: String)
    private var job: Job? = null
    private var state = JSONObject().put("phase", "idle")
    private val upload = activity.registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        val current = job ?: return@registerForActivityResult
        if (uris.isEmpty()) finish("cancelled", "已取消上传")
        else run {
            var count = 0
            val errors = mutableListOf<String>()
            for (uri in uris.take(100)) {
                try {
                    require(uri.scheme == "content") { "文件来源不受支持" }
                    val name = displayName(uri)
                    activity.contentResolver.openInputStream(uri).use { input ->
                        requireNotNull(input) { "无法读取所选文件" }
                        files.importFile(current.path, name, input)
                    }
                    count++
                } catch (e: Exception) { errors += UbuntuFiles.describeFailure(e) }
            }
            if (uris.size > 100) errors += "每次最多上传 100 个文件"
            finish(if (errors.isEmpty()) "done" else "failed", "已上传 $count 个文件" + if (errors.isEmpty()) "" else "；${errors.joinToString("；")}")
        }
    }
    private val save = activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val current = job ?: return@registerForActivityResult
        val uri = result.data?.data
        if (result.resultCode != android.app.Activity.RESULT_OK || uri == null) finish("cancelled", "已取消另存为")
        else run {
            require(uri.scheme == "content") { "保存位置不受支持" }
            try {
                activity.contentResolver.openOutputStream(uri, "wt").use { output ->
                    requireNotNull(output) { "无法写入所选位置" }
                    files.exportFile(current.path, output)
                }
                finish("done", "文件已保存到所选位置")
            } catch (e: Exception) {
                // Remove the document just created by ACTION_CREATE_DOCUMENT if transfer fails.
                runCatching { android.provider.DocumentsContract.deleteDocument(activity.contentResolver, uri) }
                throw e
            }
        }
    }
    @Synchronized fun snapshot(): JSONObject = JSONObject(state.toString())
    @Synchronized private fun finish(phase: String, message: String) {
        state.put("phase", phase).put("message", message.take(1200))
        if (phase !in setOf("choosing", "running")) job = null
    }
    private fun run(action: () -> Unit) {
        finish("running", "正在传输文件…")
        try { worker.execute {
            try { action() }
            catch (e: Exception) { finish("failed", UbuntuFiles.describeFailure(e)) }
        } } catch (e: Exception) { finish("failed", "文件操作繁忙，请稍后重试") }
    }
    fun start(kind: String, path: String): JSONObject {
        require(kind in setOf("upload", "save", "share")) { "未知文件传输操作" }
        synchronized(this) {
            check(job == null) { "请先完成当前文件传输" }
            val current = Job(UUID.randomUUID().toString(), kind, path)
            job = current
            state = JSONObject().put("id", current.id).put("kind", kind).put("path", path).put("phase", "choosing").put("message", "请在系统界面选择文件或保存位置")
        }
        try {
            when (kind) {
                "upload" -> { require(files.canWrite(files.directory(path).path)) { "目录只读" }; upload.launch(arrayOf("*/*")) }
                "save" -> {
                    val name = files.exportName(path)
                    save.launch(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = mime(name)
                        putExtra(Intent.EXTRA_TITLE, name)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                    })
                }
                "share" -> run { share(path) }
            }
        } catch (e: Exception) { finish("failed", e.message ?: "无法打开系统文件界面") }
        return snapshot()
    }
    private fun displayName(uri: Uri): String = activity.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        require(cursor.moveToFirst()) { "无法读取文件名" }
        cursor.getString(0)
    } ?: error("无法读取文件名")
    private fun share(path: String) {
        val name = files.exportName(path)
        val root = File(activity.cacheDir, "file-shares").apply { mkdirs() }
        // Keep granted files available after the chooser returns; expire only old staged exports.
        root.listFiles()?.filter { it.isDirectory && it.lastModified() < System.currentTimeMillis() - 24 * 60 * 60 * 1000L }?.forEach { old ->
            old.listFiles()?.forEach { it.delete() }; old.delete()
        }
        val directory = File(root, UUID.randomUUID().toString()).apply { check(mkdir()) }
        val staged = File(directory, name)
        try { staged.outputStream().use { files.exportFile(path, it) } }
        catch (e: Exception) { staged.delete(); directory.delete(); throw e }
        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.fileprovider", staged)
        activity.runOnUiThread {
            try {
                check(!activity.isDestroyed)
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = mime(name)
                    putExtra(Intent.EXTRA_STREAM, uri)
                    clipData = ClipData.newUri(activity.contentResolver, name, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                activity.startActivity(Intent.createChooser(intent, "分享文件").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                finish("done", "已打开系统分享面板")
            } catch (e: Exception) { finish("failed", "没有可用的分享应用") }
        }
    }
    private fun mime(name: String) = MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase()) ?: "application/octet-stream"
}
