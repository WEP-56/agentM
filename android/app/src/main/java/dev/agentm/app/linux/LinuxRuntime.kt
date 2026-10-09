package dev.agentm.app.linux

import dev.agentm.app.AgentMApplication
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

data class LinuxLaunch(val argv: Array<String>, val environment: Array<String>, val cwd: String)
data class CommandResult(val code: Int, val output: String)

class LinuxRuntime(private val app: AgentMApplication) {
    val base = File(app.filesDir, "linux")
    val rootfs = File(base, "ubuntu")
    val home = File(app.filesDir, "linux-home")
    val workspace = File(app.filesDir, "workspaces")
    val managed = File(app.filesDir, "managed")
    private val native = File(app.applicationInfo.nativeLibraryDir)
    val proot = File(native, "libproot.so")
    private val temp = File(app.cacheDir, "proot")
    private val hardlinks by lazy {
        base.mkdirs()
        val original = File.createTempFile("link-probe-", ".tmp", base)
        val linked = File(base, original.name + ".link")
        try { android.system.Os.link(original.absolutePath, linked.absolutePath); true }
        catch (_: Exception) { false }
        finally { linked.delete(); original.delete() }
    }

    fun launch(root: File = rootfs, command: List<String> = listOf("/bin/bash", "--noprofile", "--norc", "-i")): LinuxLaunch {
        require(proot.isFile) { "APK 缺少 proot 执行引擎" }
        home.mkdirs(); workspace.mkdirs(); temp.mkdirs(); managed.mkdirs()
        val args = mutableListOf(proot.absolutePath, "-L", "--kill-on-exit", "-0",
            "-r", root.absolutePath, "-w", "/workspace", "-b", "/dev", "-b", "/proc", "-b", "/sys",
            "-b", "/proc/self/fd:/dev/fd", "-b", "${home.absolutePath}:/root", "-b", "${workspace.absolutePath}:/workspace",
            "-b", "${managed.absolutePath}:/opt/agentm")
        val l2s = File(root, ".l2s")
        if (!hardlinks) {
            l2s.mkdirs()
            args += listOf("--link2symlink", "-b", "${l2s.absolutePath}:${l2s.absolutePath}")
        }
        // Ubuntu binaries and package tools must not inherit Android linker/npm variables.
        args += listOf("/usr/bin/env", "-i", "HOME=/root", "USER=root", "LOGNAME=root", "TERM=xterm-256color",
            "LANG=C.UTF-8", "PATH=${app.packages.path()}", "TMPDIR=/tmp", "DISABLE_AUTOUPDATER=1", "CODEX_MANAGED_BY_NPM=1",
            "SHELL=/bin/bash", "PS1=agentM:\\w\\$ ")
        args += command
        val environment = mutableListOf("PATH=/system/bin", "HOME=${home.absolutePath}", "TMPDIR=${temp.absolutePath}",
            "PROOT_TMP_DIR=${temp.absolutePath}", "PROOT_LOADER=${File(native, "libprootloader.so").absolutePath}",
            "LD_LIBRARY_PATH=${native.absolutePath}", "PROOT_NO_SECCOMP=1")
        if (!hardlinks) environment += "PROOT_L2S_DIR=${l2s.absolutePath}"
        return LinuxLaunch(args.toTypedArray(), environment.toTypedArray(), workspace.absolutePath)
    }

    fun run(root: File = rootfs, script: String, timeoutSeconds: Long = 30): CommandResult {
        val launch = launch(root, listOf("/bin/bash", "--noprofile", "--norc", "-c", script))
        val builder = ProcessBuilder(*launch.argv).directory(File(launch.cwd)).redirectErrorStream(true)
        builder.environment().clear()
        launch.environment.forEach { builder.environment()[it.substringBefore('=')] = it.substringAfter('=') }
        val process = builder.start()
        process.outputStream.close()
        val output = StringBuilder()
        val reader = Thread {
            runCatching { process.inputStream.bufferedReader().use { input ->
                val buffer = CharArray(4096)
                while (true) { val count = input.read(buffer); if (count < 0) break
                    synchronized(output) { output.append(buffer, 0, count); if (output.length > 65536) output.delete(0, output.length - 65536) }
                }
            } }
        }.apply { isDaemon = true; start() }
        try {
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) throw IOException("Linux 命令执行超时")
            reader.join(2000)
            return CommandResult(process.exitValue(), synchronized(output) { output.toString() })
        } finally {
            // Service cancellation can interrupt waitFor. Still terminate the process we created.
            val interrupted = Thread.interrupted()
            if (process.isAlive) {
                process.destroy()
                if (!process.waitFor(2, TimeUnit.SECONDS)) { process.destroyForcibly(); process.waitFor(2, TimeUnit.SECONDS) }
            }
            runCatching { process.inputStream.close() }
            if (interrupted) Thread.currentThread().interrupt()
        }
    }

    fun probe(root: File = rootfs): CommandResult = run(root,
        "set -e; . /etc/os-release; printf 'AGENTM_OS=%s:%s\\n' \"\$ID\" \"\$VERSION_ID\"; " +
            "test \"\$ID\" = ubuntu; test \"\$VERSION_ID\" = 24.04; " +
            "/bin/bash --version | head -n 1; /usr/bin/apt-get --version | head -n 1; " +
            "/usr/bin/dpkg --print-architecture; printf 'AGENTM_LINUX_OK\\n'", timeoutSeconds = 10)
}
