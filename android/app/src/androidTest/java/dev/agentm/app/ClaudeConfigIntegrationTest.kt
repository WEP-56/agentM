package dev.agentm.app

import android.system.Os
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.agentm.app.config.ClaudeConfigManager
import dev.agentm.app.config.ClaudeSettings
import dev.agentm.app.config.ConfigFailure
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Uses an isolated fixture home; never overwrites the user's real Claude settings or login. */
@RunWith(AndroidJUnit4::class)
class ClaudeConfigIntegrationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext as AgentMApplication
    private fun stop() {
        instrumentation.runOnMainSync { app.terminals.stop() }
        val deadline = System.currentTimeMillis() + 10000
        while (app.terminals.session?.isRunning == true && System.currentTimeMillis() < deadline) Thread.sleep(100)
        assertFalse(app.terminals.session?.isRunning == true)
    }
    @Test fun preservesUnknownFieldsEncryptsBackupAndRejectsConflicts() {
        assertTrue("Ubuntu must be installed", app.linux.ready)
        stop()
        val root = File(app.filesDir, "config-test-${UUID.randomUUID()}").apply { mkdirs() }
        val home = File(root, "home").apply { mkdirs() }
        val workspace = File(root, "workspace").apply { mkdirs() }
        val state = File(root, "state")
        val file = File(home, ".claude/settings.json").apply { parentFile!!.mkdirs() }
        val original = "{\r\n  \"env\": {\"ANTHROPIC_API_KEY\":\"fixture-old-secret\",\"UNMANAGED\":\"keep\"},\r\n  \"hooks\": {\"custom\": [1e+02, \"command\"]},\r\n  \"permissions\": {\"deny\": [\"Read(secret)\"]}\r\n}\r\n"
        file.writeText(original)
        val manager = ClaudeConfigManager(app, home, workspace, state)
        fun input(revision: String) = JSONObject().put("revision", revision).put("baseUrl", "https://fixture.example.invalid/anthropic")
            .put("model", "fixture-model").put("authMode", "authToken").put("secretAction", "replace").put("secret", "fixture-new-secret")
        try {
            val current = manager.read()
            assertTrue(current.getBoolean("hasApiKey"))
            assertFalse(current.toString().contains("fixture-old-secret"))
            val plan = manager.preview(input(current.getString("revision")))
            assertFalse(plan.toString().contains("fixture-old-secret"))
            assertFalse(plan.toString().contains("fixture-new-secret"))
            assertEquals(original, file.readText())
            val saved = manager.apply(plan.getString("token"))
            assertEquals(384, Os.stat(file.absolutePath).st_mode and 511)
            val changed = file.readText()
            assertTrue(changed.contains("\"hooks\": {\"custom\": [1e+02, \"command\"]}"))
            assertTrue(changed.contains("\"UNMANAGED\":\"keep\""))
            assertEquals("fixture-new-secret", ClaudeSettings.values(changed)[ClaudeSettings.TOKEN])
            assertFalse(ClaudeSettings.values(changed).containsKey(ClaudeSettings.KEY))
            val backup = File(state, "claude-backup.json").readText()
            assertFalse(backup.contains("fixture-old-secret")); assertFalse(backup.contains("fixture-new-secret"))
            assertTrue(saved.getBoolean("canRestore"))
            val recreated = ClaudeConfigManager(app, home, workspace, state)
            val restore = recreated.previewRestore(saved.getString("revision"))
            recreated.apply(restore.getString("token"))
            assertEquals(original, file.readText())

            val stale = manager.preview(input(manager.read().getString("revision")))
            file.appendText("\n")
            val modified = file.readText()
            assertEquals("CONFIG_CONFLICT", assertThrows(ConfigFailure::class.java) { manager.apply(stale.getString("token")) }.code)
            assertEquals(modified, file.readText())
            assertEquals("CONFIG_CONFLICT", assertThrows(ConfigFailure::class.java) { manager.previewRestore(manager.read().getString("revision")) }.code)

            val pending = manager.preview(input(manager.read().getString("revision")))
            instrumentation.runOnMainSync { app.terminals.open("deviceShell") }
            assertEquals("CONFIG_BUSY", assertThrows(ConfigFailure::class.java) { manager.apply(pending.getString("token")) }.code)
            stop()
            manager.apply(pending.getString("token"))

            file.writeText("{\"env\":{},\"env\":{}}")
            assertEquals("CONFIG_INVALID", assertThrows(ConfigFailure::class.java) { manager.read() }.code)
            val outside = File(root, "outside.json").apply { writeText("{\"untouched\":true}") }
            assertTrue(file.delete())
            Os.symlink(outside.absolutePath, file.absolutePath)
            assertEquals("CONFIG_PATH", assertThrows(ConfigFailure::class.java) { manager.read() }.code)
            assertEquals("{\"untouched\":true}", outside.readText())
            assertTrue(file.delete())

            val blank = manager.read()
            val noChange = manager.preview(JSONObject().put("revision", blank.getString("revision")).put("authMode", "native"))
            assertFalse(noChange.getBoolean("changed"))
            val create = manager.preview(input(blank.getString("revision")))
            val created = manager.apply(create.getString("token"))
            val remove = manager.previewRestore(created.getString("revision"))
            assertTrue(remove.getBoolean("deletesFile"))
            manager.apply(remove.getString("token"))
            assertFalse(file.exists())
            assertFalse(app.logs.snapshot().toString().contains("fixture-new-secret"))
            println("CONFIG_INTEGRATION_OK: isolated fixtures, source preservation, redaction, 0600, Keystore backup, restart restore, conflict, busy gate, symlink rejection")
        } finally {
            stop()
            // The symlink, if any, is unlinked before removing this test-owned tree.
            if (java.nio.file.Files.isSymbolicLink(file.toPath())) file.delete()
            check(root.parentFile!!.canonicalFile == app.filesDir.canonicalFile && root.name.startsWith("config-test-"))
            root.deleteRecursively()
        }
    }
}
