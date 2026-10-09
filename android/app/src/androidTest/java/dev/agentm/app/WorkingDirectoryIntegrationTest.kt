package dev.agentm.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Isolated pwd processes only: does not switch preferences, open terminals or restart user Agents. */
@RunWith(AndroidJUnit4::class)
class WorkingDirectoryIntegrationTest {
    @Test fun prootStartsInsideSelectedProjectWithoutShellQuotingAndDefaultsRemainWorkspace() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as AgentMApplication
        assertTrue("Ubuntu must already be ready", app.linux.ready)
        val root = app.linux.runtime.workspace.canonicalFile
        val project = File(root, "cwd-test-${UUID.randomUUID()} 中文 'draft' \$(literal) #100%?").apply { mkdir() }
        val path = "/workspace/${project.name}"
        try {
            fun output(directory: String, command: List<String> = listOf("/bin/pwd")): String {
                val launch = app.linux.runtime.launch(command = command, workingDirectory = directory)
                assertEquals(directory, launch.argv[launch.argv.indexOf("-w") + 1])
                if (directory == path) assertEquals(project.absolutePath, launch.cwd)
                val builder = ProcessBuilder(*launch.argv).directory(File(launch.cwd)).redirectErrorStream(true)
                builder.environment().clear()
                launch.environment.forEach { builder.environment()[it.substringBefore('=')] = it.substringAfter('=') }
                val process = builder.start()
                try {
                    process.outputStream.close()
                    assertTrue("pwd timed out", process.waitFor(10, TimeUnit.SECONDS))
                    val result = process.inputStream.bufferedReader().readText().trim()
                    assertEquals(result, 0, process.exitValue())
                    return result
                } finally { if (process.isAlive) process.destroyForcibly() }
            }
            assertEquals(path, output(path))
            assertEquals("/workspace", output("/workspace"))
            val osc = output(path, listOf("/bin/bash", "--noprofile", "--norc", "-c", TerminalDirectoryPrompt.COMMAND))
            assertEquals(path, android.net.Uri.parse(osc.removePrefix("\u001b]7;").removeSuffix("\u0007")).path)
            val defaultLaunch = app.linux.runtime.launch(command = listOf("/bin/pwd"))
            assertEquals("/workspace", defaultLaunch.argv[defaultLaunch.argv.indexOf("-w") + 1])
        } finally {
            check(project.parentFile!!.canonicalFile == root && project.name.startsWith("cwd-test-"))
            java.nio.file.Files.deleteIfExists(project.toPath())
        }
    }
}
