package dev.agentm.app.packages

import android.content.res.AssetManager
import android.system.Os
import dev.agentm.app.linux.RootfsExtractor
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

/** Reconstruct DSHA's exact npm graph. No package manager or unreviewed install script executes. */
class DshPackage(private val assets: AssetManager, private val abi: String) {
    private val catalog = JSONObject(assets.open("dsh-catalog.json").bufferedReader().use { it.readText() })
    val version = catalog.getString("version")
    val sha256 get() = catalog.getString("lockSha256")
    private val packages get() = catalog.getJSONObject("architectures").getJSONObject(abi).getJSONArray("packages")
    private val overlays get() = JSONObject(assets.open("dsh-overlays/manifest.json").bufferedReader().use { it.readText() }).getJSONArray("files")
    private fun safe(stage: File, name: String): File {
        require(name.matches(Regex("(?:node_modules/)(?:[A-Za-z0-9@._-]+/)*[A-Za-z0-9@._-]+")) && name.split('/').none { it == "." || it == ".." })
        val file = File(stage, name)
        require(file.canonicalFile == File(stage.canonicalFile, name)) { "DSH 目录被符号链接替换" }
        return file
    }
    fun install(stage: File, downloader: VerifiedDownload, progress: (String, Long, Long) -> Unit) {
        check(stage.mkdirs())
        val total = catalog.getJSONObject("architectures").getJSONObject(abi).getLong("bytes")
        var downloaded = 0L
        val definitions = packages
        for (index in 0 until definitions.length()) {
            val definition = definitions.getJSONObject(index)
            val message = "安装 DSH 组件 ${index + 1}/${definitions.length()}"
            val archive = downloader.fetch(definition) { current, _ -> progress(message, downloaded + current, total) }
            downloaded += definition.getLong("bytes")
            progress(message, downloaded, total)
            val temporary = File(stage, "extract-$index")
            RootfsExtractor().extract(archive, temporary, { Thread.currentThread().isInterrupted }) {}
            val source = File(temporary, definition.getString("archiveRoot"))
            require(source.isDirectory && source.canonicalFile.parentFile == temporary.canonicalFile)
            val target = safe(stage, definition.getString("path"))
            require(!target.exists())
            target.parentFile!!.mkdirs()
            Files.move(source.toPath(), target.toPath())
            check(temporary.delete())
        }
        val patchFiles = overlays
        for (index in 0 until patchFiles.length()) {
            val patch = patchFiles.getJSONObject(index)
            val file = safe(stage, patch.getString("path"))
            if (patch.has("beforeSha256")) require(file.isFile && digest(file.readBytes()) == patch.getString("beforeSha256")) { "DSH 补丁源文件不匹配" }
            else require(!file.exists())
            val content = assets.open(patch.getString("asset")).use { it.readBytes() }
            require(digest(content) == patch.getString("sha256")) { "DSH 内置补丁校验失败：${patch.getString("asset")}，请更新或重新安装 agentM" }
            file.parentFile!!.mkdirs(); file.writeBytes(content)
        }
        // Recreate npm's per-level .bin links using the locked bin map and a checked target.
        for (index in 0 until definitions.length()) {
            val definition = definitions.getJSONObject(index)
            val directory = safe(stage, definition.getString("path"))
            val bins = definition.getJSONObject("bin")
            val keys = bins.keys()
            while (keys.hasNext()) {
                val name = keys.next()
                require(name.matches(Regex("[A-Za-z0-9._-]+")))
                val entry = File(directory, bins.getString(name)).canonicalFile
                require(entry.isFile && entry.toPath().startsWith(directory.canonicalFile.toPath()))
                var modules = directory.parentFile!!
                if (modules.name.startsWith('@')) modules = modules.parentFile!!
                require(modules.name == "node_modules")
                val link = File(modules, ".bin/$name")
                link.parentFile!!.mkdirs()
                if (!Files.exists(link.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                    Os.symlink(link.parentFile!!.toPath().relativize(entry.toPath()).toString(), link.absolutePath)
                    Os.chmod(entry.absolutePath, 493)
                }
            }
        }
        val launcher = File(stage, "bin/dsh")
        // Exact equivalent of the pinned dsh-subprocess-local postinstall: executable helper only.
        val cpu = if (abi == "x86_64") "x64" else "arm64"
        val helper = safe(stage, "node_modules/node-pty/prebuilds/linux-$cpu/spawn-helper")
        if (helper.isFile) Os.chmod(helper.absolutePath, 493)
        launcher.parentFile!!.mkdirs()
        require(ManagedPackagePaths.validSlot(stage.name))
        val guest = "/opt/agentm/slots/${stage.name}"
        launcher.writeText("#!/bin/sh\nexport NARB_DISABLE_NATIVE_CACHE=1\nexport AGENTM_DSH_WORKSPACE=/workspace\nexport PATH=$guest/node_modules/.bin:\"\$PATH\"\nexec node $guest/node_modules/@deepseek-ai/dsh/lib/bin.js \"\$@\"\n")
        Os.chmod(launcher.absolutePath, 493)
        File(stage, ".agentm-dsh-recipe").writeText(recipeDigest())
        verify(stage, version)
    }
    fun verify(stage: File, expectedVersion: String) {
        require(expectedVersion == version)
        val main = JSONObject(safe(stage, "node_modules/@deepseek-ai/dsh/package.json").readText())
        require(main.getString("version") == version && main.getString("name") == "@deepseek-ai/dsh")
        for (file in listOf("node_modules/@deepseek-ai/dsh/lib/bin.js", "node_modules/@deepseek-ai/dsh-web/lib/index.js",
            "node_modules/node-pty/lib/index.js", "node_modules/dsha-runtime-fs/index.js")) require(safe(stage, file).isFile)
        val cpu = if (abi == "x86_64") "x64" else "arm64"
        require(safe(stage, "node_modules/node-pty/prebuilds/linux-$cpu/pty.node").isFile) { "DSH 缺少 Linux PTY 组件" }
        val stamp = File(stage, ".agentm-dsh-recipe")
        require(stamp.isFile && stamp.readText() == recipeDigest()) { "DSH 适配记录不匹配，请重新安装程序" }
    }
    fun recipeDigest() = assets.open("dsh-overlays/manifest.json").use { digest(it.readBytes()) }
    companion object { fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) } }
}
