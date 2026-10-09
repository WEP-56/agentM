package dev.agentm.app

import android.system.Os
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.FileVisitResult
import java.nio.file.attribute.BasicFileAttributes

/** Archived FAILED exploratory trial, not a production acceptance test.
 * Reproducing it requires adding managed.canonicalPath:managed.canonicalPath as a proot bind.
 * That experimental bind was reverted after mountinfo still lacked the descriptor's mount ID.
 * See codex-compat/result.json. The installed rootfs was not modified by this test.
 */
@RunWith(AndroidJUnit4::class)
class CodexPathTrialTest {
    @Test fun identityMappedTmpPreservesReadOnlyAndSocketIsolation() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as AgentMApplication
        val runtime = app.linux.runtime
        val id = java.util.UUID.randomUUID().toString()
        val view = File(app.filesDir, "codex-view-$id")
        val probe = File(runtime.managed, "codex-trial-$id").apply { mkdirs() }
        val source = runtime.rootfs.toPath()
        fun remove(directory: File) {
            if (!directory.exists()) return
            Files.walkFileTree(directory.toPath(), object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult { Files.delete(file); return FileVisitResult.CONTINUE }
                override fun postVisitDirectory(dir: Path, error: java.io.IOException?): FileVisitResult { if (error != null) throw error; Files.delete(dir); return FileVisitResult.CONTINUE }
            })
        }
        try {
            Files.walkFileTree(source, object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                    File(view, source.relativize(dir).toString()).mkdirs(); return FileVisitResult.CONTINUE
                }
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    val target = File(view, source.relativize(file).toString()).toPath()
                    if (attrs.isSymbolicLink) Files.createSymbolicLink(target, Files.readSymbolicLink(file))
                    else Files.createLink(target, file)
                    return FileVisitResult.CONTINUE
                }
                override fun visitFileFailed(file: Path, error: java.io.IOException): FileVisitResult {
                    // PRoot may leave mode-000 mount placeholders. They contain no backing data;
                    // launch() supplies the same real binds for both views.
                    val relative = source.relativize(file).toString()
                    if (relative !in setOf("opt/agentm", "root", "workspace", "dev", "proc", "sys", runtime.managed.canonicalPath.removePrefix("/"))) throw error
                    File(view, relative).mkdirs()
                    return FileVisitResult.CONTINUE
                }
            })
            remove(File(view, "tmp"))
            val tmp = File(probe, "tmp").apply { mkdirs() }
            Os.symlink(tmp.canonicalPath, File(view, "tmp").absolutePath)
            val home = File(probe, "home").apply { mkdirs() }
            val workspace = File(probe, "workspace").apply { mkdirs() }
            val denied = File(probe, "denied")
            val daemon = File(tmp, "codex-daemon-0").apply { mkdirs(); Os.chmod(absolutePath, 448) }
            File(daemon, "private-marker").writeText("private")
            val exe = app.packages.agentCommand("codex")[0]
            val result = runtime.run(root = view, script = """
                set -eu
                export CODEX_HOME=${home.canonicalPath}
                cd ${workspace.canonicalPath}
                /usr/bin/python3 -c 'import os; d=os.path.realpath("/tmp")+"/codex-daemon-"+str(os.geteuid()); fd=os.open(d,os.O_RDONLY); dev=os.fstat(fd).st_dev; lines=open("/proc/self/fdinfo/"+str(fd)).read().splitlines(); mid=next(x.split(":")[1].strip() for x in lines if x.startswith("mnt_id:")); print("DAEMON",d,"DEVICE",str(os.major(dev))+":"+str(os.minor(dev)),"MOUNT",mid); print("MOUNTINFO",*[x.strip() for x in open("/proc/self/mountinfo") if x.split()[0]==mid]); os.close(fd)'
                $exe -c 'sandbox_mode="read-only"' sandbox linux -- /bin/sh -c '
                  if touch ${denied.canonicalPath} 2>/dev/null; then printf AGENTM_WRITE_ESCAPED; exit 9; fi
                  if test -e ${File(daemon, "private-marker").canonicalPath}; then printf AGENTM_SOCKET_EXPOSED; exit 10; fi
                  printf AGENTM_CODEX_BOUNDARIES_OK
                '
            """.trimIndent(), timeoutSeconds = 30)
            println("CODEX_PATH_TRIAL exit=${result.code} output=${result.output}")
            assertEquals(result.output, 0, result.code)
            assertTrue(result.output, result.output.contains("AGENTM_CODEX_BOUNDARIES_OK"))
            assertFalse("Read-only sandbox wrote a file", denied.exists())
        } finally { remove(view); remove(probe) }
    }
}
