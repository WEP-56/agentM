package dev.agentm.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.agentm.app.config.ClaudeConfigManager
import dev.agentm.app.config.ClaudeSettings
import dev.agentm.app.config.ConfigFailure
import dev.agentm.app.config.EncryptedBackup
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ClaudeProfilesIntegrationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext as AgentMApplication
    @Test fun templatesRemainIndependentAndStalePreviewsCannotApply() {
        assertTrue(app.linux.ready)
        instrumentation.runOnMainSync { app.terminals.stop() }
        val deadline = System.currentTimeMillis() + 10000
        while (app.terminals.session?.isRunning == true && System.currentTimeMillis() < deadline) Thread.sleep(100)
        assertFalse(app.terminals.session?.isRunning == true)
        val root = File(app.filesDir, "profiles-test-${UUID.randomUUID()}").apply { mkdirs() }
        val home = File(root, "home").apply { mkdirs() }
        val workspace = File(root, "workspace").apply { mkdirs() }
        val state = File(root, "state")
        val file = File(home, ".claude/settings.json").apply { parentFile!!.mkdirs() }
        val original = "{\r\n  \"env\":{\"ANTHROPIC_API_KEY\":\"fixture-live-key\",\"CUSTOM\":\"keep\"},\r\n  \"hooks\":{\"fixture\":[1e+02]},\r\n  \"permissions\":{\"deny\":[\"Read(secret)\"]}\r\n}\r\n"
        file.writeText(original)
        var manager = ClaudeConfigManager(app, home, workspace, state)
        fun request(name: String, secret: String = "fixture-alpha-token") = JSONObject().put("libraryRevision", manager.listProfiles().getString("revision"))
            .put("name", name).put("authMode", "authToken").put("baseUrl", "https://profiles.example.invalid/anthropic").put("model", "fixture-model")
            .put("secretAction", if (secret.isEmpty()) "keep" else "replace").put("secret", secret)
        fun profile(id: String): JSONObject {
            val all = manager.listProfiles().getJSONArray("profiles")
            return (0 until all.length()).map { all.getJSONObject(it) }.first { it.getString("id") == id }
        }
        fun preview(id: String) = manager.previewProfile(JSONObject().put("id", id).put("profileRevision", profile(id).getString("revision"))
            .put("revision", manager.read().getString("revision")))
        try {
            assertEquals(0, manager.listProfiles().getJSONArray("profiles").length())
            val first = request("Alpha")
            val alpha = manager.saveProfile(first).getString("savedId")
            assertEquals(original, file.readText())
            assertEquals("PROFILE_CONFLICT", assertThrows(ConfigFailure::class.java) { manager.saveProfile(first) }.code)
            assertEquals("PROFILE_INPUT", assertThrows(ConfigFailure::class.java) { manager.saveProfile(request("Missing credential", "")) }.code)
            assertEquals("PROFILE_INPUT", assertThrows(ConfigFailure::class.java) { manager.saveProfile(request("alpha")) }.code)
            val alphaCopy = manager.saveProfile(request("Different credential", "fixture-other-token")).getString("savedId")
            val live = manager.saveProfile(JSONObject().put("libraryRevision", manager.listProfiles().getString("revision"))
                .put("name", "Captured current").put("captureCurrent", true).put("nativeRevision", manager.read().getString("revision"))).getString("savedId")
            assertTrue(profile(live).getBoolean("matchesCurrent"))
            val onDisk = File(state, "claude-profiles.json")
            for (secret in listOf("fixture-alpha-token", "fixture-other-token", "fixture-live-key")) {
                assertFalse(onDisk.readText().contains(secret)); assertFalse(manager.listProfiles().toString().contains(secret))
            }
            assertThrows(Exception::class.java) { EncryptedBackup().decrypt(JSONObject(onDisk.readText())) }
            manager = ClaudeConfigManager(app, home, workspace, state)
            assertEquals(3, manager.listProfiles().getJSONArray("profiles").length())
            val stale = preview(alpha)
            assertFalse(stale.toString().contains("fixture-alpha-token"))
            manager.saveProfile(request("Alpha edited", "").put("id", alpha).put("model", "fixture-edited"))
            manager.saveProfile(request("Different credential", "").put("id", alphaCopy).put("model", "fixture-edited"))
            assertEquals("PROFILE_CONFLICT", assertThrows(ConfigFailure::class.java) { manager.apply(stale.getString("token")) }.code)
            assertEquals(original, file.readText())
            // Keeping a credential uses the edited template, not the live file or another template.
            assertEquals("PROFILE_INPUT", assertThrows(ConfigFailure::class.java) {
                manager.saveProfile(request("Alpha edited", "").put("id", alpha).put("authMode", "apiKey"))
            }.code)
            manager.apply(preview(alpha).getString("token"))
            assertEquals("fixture-alpha-token", ClaudeSettings.values(file.readText())[ClaudeSettings.TOKEN])
            assertFalse(ClaudeSettings.values(file.readText()).containsKey(ClaudeSettings.KEY))
            assertTrue(file.readText().contains("\"hooks\":{\"fixture\":[1e+02]}"))
            assertTrue(profile(alpha).getBoolean("matchesCurrent"))
            assertEquals(profile(alpha).getString("baseUrl"), profile(alphaCopy).getString("baseUrl"))
            assertEquals(profile(alpha).getString("model"), profile(alphaCopy).getString("model"))
            assertFalse(profile(alphaCopy).getBoolean("matchesCurrent"))
            val applied = file.readText()
            val changedFile = preview(alphaCopy)
            file.appendText("\n")
            assertEquals("CONFIG_CONFLICT", assertThrows(ConfigFailure::class.java) { manager.apply(changedFile.getString("token")) }.code)
            assertEquals(applied + "\n", file.readText())
            file.writeText(applied)
            val deletedPreview = preview(alphaCopy)
            manager.deleteProfile(JSONObject().put("libraryRevision", manager.listProfiles().getString("revision")).put("id", alphaCopy))
            assertEquals("PROFILE_CONFLICT", assertThrows(ConfigFailure::class.java) { manager.apply(deletedPreview.getString("token")) }.code)
            assertEquals(applied, file.readText())
            // An unrelated template update does not invalidate the selected immutable revision.
            val currentPreview = preview(live)
            manager.saveProfile(request("Unrelated"))
            manager.apply(currentPreview.getString("token"))
            assertEquals("fixture-live-key", ClaudeSettings.values(file.readText())[ClaudeSettings.KEY])
            val staleEdit = request("Stale library")
            val second = ClaudeConfigManager(app, home, workspace, state)
            second.saveProfile(request("Other writer"))
            assertEquals("PROFILE_CONFLICT", assertThrows(ConfigFailure::class.java) { manager.saveProfile(staleEdit) }.code)
            // Removing a matching template does not remove the active configuration.
            val configured = file.readText()
            manager.deleteProfile(JSONObject().put("libraryRevision", manager.listProfiles().getString("revision")).put("id", live))
            assertEquals(configured, file.readText())
            val capturedRevision = manager.read().getString("revision")
            file.appendText("\n")
            assertEquals("CONFIG_CONFLICT", assertThrows(ConfigFailure::class.java) {
                manager.saveProfile(JSONObject().put("name", "Stale capture").put("captureCurrent", true)
                    .put("nativeRevision", capturedRevision).put("libraryRevision", manager.listProfiles().getString("revision")))
            }.code)
            onDisk.writeText("corrupted fixture")
            assertEquals("PROFILE_STORAGE", assertThrows(ConfigFailure::class.java) { manager.listProfiles() }.code)
            assertEquals("PROFILE_STORAGE", assertThrows(ConfigFailure::class.java) { manager.saveProfile(first) }.code)
            assertEquals("corrupted fixture", onDisk.readText())
            println("PROFILES_INTEGRATION_OK: encrypted CRUD, restart, redaction, exact matching, capture, credential isolation, revision guards, deletion, corrupt-store protection")
        } finally {
            check(root.parentFile!!.canonicalFile == app.filesDir.canonicalFile && root.name.startsWith("profiles-test-"))
            root.deleteRecursively()
        }
    }
}
