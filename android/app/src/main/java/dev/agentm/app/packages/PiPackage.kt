package dev.agentm.app.packages

import android.system.Os
import dev.agentm.app.linux.RootfsExtractor
import org.json.JSONObject
import java.io.File
import java.nio.file.Files

/** The published Node CLI bundle, plus the two external WASM packages it resolves at runtime. */
object PiPackage {
    private val dependencies = mapOf("quickjs-wasi" to "3.6.2", "@silvia-odwyer/photon-node" to "0.3.4")

    fun prepare(stage: File, definition: JSONObject, downloader: VerifiedDownload, progress: (Long, Long) -> Unit) {
        val assets = definition.getJSONArray("dependencies")
        require(assets.length() == dependencies.size)
        val seen = mutableSetOf<String>()
        for (index in 0 until assets.length()) {
            val asset = assets.getJSONObject(index)
            val name = asset.getString("package")
            require(dependencies[name] == asset.getString("version") && seen.add(name)) { "未知 Pi 依赖" }
            val archive = downloader.fetch(asset, progress)
            val extraction = File(stage, "dependency-$index")
            RootfsExtractor().extract(archive, extraction, { Thread.currentThread().isInterrupted }) {}
            val target = File(stage, "package/node_modules/$name")
            require(!target.exists())
            target.parentFile!!.mkdirs()
            Files.move(File(extraction, "package").toPath(), target.toPath())
            check(extraction.delete()) { "Pi 依赖归档含意外根目录" }
        }
        val launcher = File(stage, "bin/pi")
        launcher.parentFile!!.mkdirs()
        // Paths are generated from the closed slot grammar; Node comes from the managed PATH.
        require(ManagedPackagePaths.validSlot(stage.name))
        launcher.writeText("#!/bin/sh\nexport PI_SKIP_VERSION_CHECK=1\nexec node /opt/agentm/slots/${stage.name}/package/dist/bundle/cli.js \"\$@\"\n")
        Os.chmod(launcher.absolutePath, 493)
    }

    fun verifyLayout(stage: File, version: String, definition: JSONObject) {
        fun required(name: String): File = File(stage, name).also {
            require(it.isFile && it.canonicalFile == File(stage.canonicalFile, name)) { "Pi 组件缺失或路径被替换：$name" }
        }
        val metadata = JSONObject(required("package/package.json").readText())
        require(metadata.getString("name") == "@earendil-works/pi-coding-agent" && metadata.getString("version") == version)
        val runtimeFiles = definition.getJSONArray("runtimeFiles")
        for (index in 0 until runtimeFiles.length()) required(runtimeFiles.getString(index))
        for ((name, pinned) in dependencies) {
            val dependency = JSONObject(required("package/node_modules/$name/package.json").readText())
            require(dependency.getString("name") == name && dependency.getString("version") == pinned) { "Pi 依赖版本不匹配" }
        }
        val assets = definition.getJSONArray("dependencies")
        for (index in 0 until assets.length()) {
            val files = assets.getJSONObject(index).getJSONArray("runtimeFiles")
            for (fileIndex in 0 until files.length()) required(files.getString(fileIndex))
        }
    }
}
