package dev.agentm.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.agentm.app.config.ClaudeProfileStore
import dev.agentm.app.config.ProviderManager
import dev.agentm.app.config.ProviderModelDiscovery
import dev.agentm.app.config.OpenCodeDocuments
import dev.agentm.app.config.CodexDocuments
import dev.agentm.app.config.TomlDocument
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/** Uses only a disposable home/state tree and a loopback HTTP fixture. Never touches real provider files or sessions. */
@RunWith(AndroidJUnit4::class)
class ProviderIntegrationTest {
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as AgentMApplication
    private fun rows(value: JSONObject): List<JSONObject> = value.getJSONArray("providers").let { (0 until it.length()).map(it::getJSONObject) }
    private fun args(list: JSONObject, id: String) = JSONObject().put("kind", list.getString("kind")).put("id", id)
        .put("revision", list.getString("revision")).put("nativeRevision", list.getString("nativeRevision"))

    @Test fun migratesCopiesSwitchesAndPreservesNativeDataWithRealKeystore() {
        assertTrue("Ubuntu must be ready; this test does not install it", app.linux.ready)
        val base = File(app.filesDir, "provider-test-${UUID.randomUUID()}").apply { mkdirs() }
        val home = File(base, "home").apply { mkdirs() }; val state = File(base, "state").apply { mkdirs() }
        val claude = File(home, ".claude/settings.json").apply { parentFile!!.mkdirs(); writeText("""{"permissions":{"ask":["Bash"]},"env":{"ANTHROPIC_AUTH_TOKEN":"fixture-current-secret"}}""") }
        val login = File(home, ".claude/login-fixture").apply { writeText("retain-login") }
        try {
            val legacy = ClaudeProfileStore(state)
            legacy.save(JSONObject().put("libraryRevision", "missing").put("name", "Legacy provider").put("baseUrl", "https://legacy.example.test")
                .put("model", "legacy-model").put("authMode", "authToken").put("secretAction", "replace").put("secret", "fixture-legacy-secret"), null)
            val manager = ProviderManager(app, home, state)
            var list = manager.list("claude")
            assertTrue(rows(list).any { it.getString("name") == "Legacy provider" })
            assertFalse(list.toString().contains("fixture-current-secret")); assertFalse(list.toString().contains("fixture-legacy-secret"))
            val old = rows(list).first { it.getString("name") == "Legacy provider" }
            list = manager.copy(args(list, old.getString("id")))
            val copy = list.getString("savedId")
            assertEquals(manager.read("claude", old.getString("id")).getString("source"), manager.read("claude", copy).getString("source"))
            list = manager.switch(args(list, copy))
            assertEquals("fixture-legacy-secret", JSONObject(claude.readText()).getJSONObject("env").getString("ANTHROPIC_AUTH_TOKEN"))
            assertTrue(JSONObject(claude.readText()).has("permissions")); assertEquals("retain-login", login.readText())
            val stale = args(list, ProviderManager.OFFICIAL)
            claude.appendText("\n")
            val edited = claude.readText()
            assertThrows(IllegalStateException::class.java) { manager.switch(stale) }
            assertEquals(edited, claude.readText())
            list = manager.switch(args(manager.list("claude"), ProviderManager.OFFICIAL))
            assertFalse(JSONObject(claude.readText()).getJSONObject("env").has("ANTHROPIC_AUTH_TOKEN"))
            assertThrows(IllegalArgumentException::class.java) { manager.delete(args(list, ProviderManager.OFFICIAL)) }
            assertFalse(File(state, "providers-v1.json").readText().contains("fixture-legacy-secret"))
            assertFalse(File(state, "providers-backup.json").readText().contains("fixture-legacy-secret"))
            assertTrue(rows(ProviderManager(app, home, state).list("claude")).any { it.getString("id") == copy })
            list = manager.switch(args(manager.list("claude"), copy))
            manager.delete(args(list, copy))
            assertFalse(JSONObject(claude.readText()).getJSONObject("env").has("ANTHROPIC_AUTH_TOKEN"))

            val models = File(home, ".pi/agent/models.json").apply { parentFile!!.mkdirs(); writeText("""{"providers":{"external":{"apiKey":"preserve"}}}""") }
            val settings = File(home, ".pi/agent/settings.json").apply { writeText("""{"theme":"dark","defaultProvider":"external","defaultModel":"external-model"}""") }
            val piSource = """{"api":"openai-responses","baseUrl":"https://pi.example.test/v1","apiKey":"fixture-pi-secret","headers":{"X-Title":"agentM"},"compat":{"supportsStore":false},"models":[{"id":"pi-model","name":"Pi model","reasoning":true,"input":["text","image"],"contextWindow":128000,"maxTokens":16384}]}"""
            var pi = manager.list("pi")
            pi = manager.save(JSONObject().put("kind", "pi").put("id", "").put("revision", pi.getString("revision")).put("name", "Pi custom").put("providerKey", "custom-pi").put("source", piSource))
            val piId = pi.getString("savedId")
            pi = manager.switch(args(pi, piId))
            val saved = JSONObject(models.readText()).getJSONObject("providers")
            assertEquals("preserve", saved.getJSONObject("external").getString("apiKey"))
            assertEquals("fixture-pi-secret", saved.getJSONObject("custom-pi").getString("apiKey"))
            assertEquals("custom-pi", JSONObject(settings.readText()).getString("defaultProvider"))
            assertEquals("pi-model", JSONObject(settings.readText()).getString("defaultModel"))
            assertEquals("dark", JSONObject(settings.readText()).getString("theme"))
            pi = manager.copy(args(pi, piId)); val piCopy = pi.getString("savedId")
            assertNotEquals("custom-pi", manager.read("pi", piCopy).getString("providerKey"))
            manager.delete(args(pi, piCopy))
            assertTrue(JSONObject(models.readText()).getJSONObject("providers").has("custom-pi"))
            manager.delete(args(manager.list("pi"), piId))
            assertFalse(JSONObject(models.readText()).getJSONObject("providers").has("custom-pi"))
            assertTrue(JSONObject(models.readText()).getJSONObject("providers").has("external"))
            assertFalse(JSONObject(settings.readText()).has("defaultProvider"))
            assertEquals("dark", JSONObject(settings.readText()).getString("theme"))
            // The library already exists from 0.11: OpenCode import must still run exactly once.
            val ocSource = """{"npm":"@ai-sdk/openai-compatible","options":{"apiKey":"fixture-oc-secret","baseURL":"https://oc.example.test/v1","timeout":false},"models":{"m":{"name":"M","limit":{"context":200000,"output":16000},"variants":{"fast":{"reasoningEffort":"low"}}}}}"""
            val oc = File(home, ".config/opencode/opencode.jsonc").apply {
                parentFile!!.mkdirs()
                writeText("{\n// preserve plugin comment\n\"plugin\":[\"keep\",],\"provider\":{\"native\":$ocSource,\"other\":{}},\"model\":\"native/m\",\n}\n")
            }
            val auth = File(home, ".local/share/opencode/auth.json").apply { parentFile!!.mkdirs(); writeText("fixture-login") }
            val original = oc.readText()
            var openCode = manager.list("opencode")
            val imported = rows(openCode).first { it.getString("providerKey") == "native" }
            assertEquals(original, oc.readText()); assertTrue(imported.getBoolean("active"))
            assertFalse(openCode.toString().contains("fixture-oc-secret"))
            assertEquals(rows(openCode).size, rows(ProviderManager(app, home, state).list("opencode")).size)
            openCode = manager.copy(args(openCode, imported.getString("id")))
            val ocCopy = openCode.getString("savedId")
            val draft = manager.read("opencode", ocCopy)
            val newSource = JSONObject(draft.getString("source")).apply { getJSONObject("models").getJSONObject("m").put("cost", JSONObject().put("input", 1).put("output", 2)) }
            openCode = manager.save(draft.put("source", newSource.toString()).put("name", "OpenCode Copy"))
            assertEquals(original, oc.readText())
            openCode = manager.switch(args(openCode, ocCopy))
            val key = draft.getString("providerKey")
            assertEquals("$key/m", OpenCodeDocuments.parse(oc.readText()).getString("model"))
            assertTrue(rows(openCode).first { it.getString("id") == ocCopy }.getBoolean("active"))
            assertTrue(oc.readText().contains("// preserve plugin comment")); assertEquals("fixture-login", auth.readText())
            val staleOc = args(openCode, ocCopy)
            File(oc.parentFile, "opencode.json").writeText("{\"permission\":{\"read\":\"allow\"}}")
            val applied = oc.readText()
            assertThrows(IllegalStateException::class.java) { manager.switch(staleOc) }
            assertEquals(applied, oc.readText())
            manager.delete(args(manager.list("opencode"), ocCopy))
            val remaining = OpenCodeDocuments.parse(oc.readText())
            assertFalse(remaining.getJSONObject("provider").has(key)); assertTrue(remaining.getJSONObject("provider").has("native"))
            assertTrue(remaining.getJSONObject("provider").has("other")); assertFalse(remaining.has("model"))
            assertFalse(File(state, "providers-v1.json").readText().contains("fixture-oc-secret"))
            assertFalse(File(state, "providers-native-backup.json").readText().contains("fixture-oc-secret"))

            val codexConfig = File(home, ".codex/config.toml").apply { parentFile!!.mkdirs(); writeText("# keep\n[mcp_servers.fixture]\ncommand='keep'\n") }
            val codexAuth = File(home, ".codex/auth.json").apply { writeText("""{"auth_mode":"chatgpt","tokens":{"access_token":"official-test-token","refresh_token":"refresh-keep"}}""") }
            val officialLogin = codexAuth.readText()
            var codex = manager.list("codex")
            assertTrue(rows(codex).any { it.getString("id") == CodexDocuments.OFFICIAL && it.getBoolean("official") })
            assertFalse(manager.read("codex", CodexDocuments.OFFICIAL).toString().contains("official-test-token"))
            assertThrows(IllegalArgumentException::class.java) { manager.delete(args(codex, CodexDocuments.OFFICIAL)) }
            val codexSource = CodexDocuments.blank().put("auth", JSONObject().put("OPENAI_API_KEY", "codex-private-key"))
                .put("config", "model_provider='custom'\nmodel='test-model'\n[model_providers.custom]\nname='Fixture'\nbase_url='https://codex.example.test/v1'\nwire_api='responses'\n")
                .put("modelCatalog", JSONObject().put("models", JSONArray().put(JSONObject().put("model", "test-model"))))
            codex = manager.save(JSONObject().put("kind", "codex").put("id", "").put("name", "Codex Fixture").put("source", codexSource.toString()).put("revision", codex.getString("revision")))
            val codexId = codex.getString("savedId")
            assertFalse(codexConfig.readText().contains("codex-private-key"))
            codex = manager.switch(args(codex, codexId))
            assertEquals("codex-private-key", TomlDocument(codexConfig.readText()).root.getJSONObject("model_providers").getJSONObject("custom").getString("experimental_bearer_token"))
            assertEquals(officialLogin, codexAuth.readText())
            assertTrue(File(home, ".codex/${CodexDocuments.CATALOG}").isFile)
            codex = manager.copy(args(codex, codexId)); val codexCopy = codex.getString("savedId")
            manager.delete(args(codex, codexCopy))
            assertEquals("custom", TomlDocument(codexConfig.readText()).root.getString("model_provider"))
            val staleCodex = args(manager.list("codex"), CodexDocuments.OFFICIAL)
            codexAuth.appendText("\n")
            assertThrows(IllegalStateException::class.java) { manager.switch(staleCodex) }
            codex = manager.list("codex")
            val editing = manager.read("codex", codexId)
            codex = manager.save(editing.put("source", codexSource.put("config", codexSource.getString("config").replace("test-model", "other-model")).put("modelCatalog", JSONObject().put("models", JSONArray())).toString()))
            manager.delete(args(codex, codexId))
            val reset = TomlDocument(codexConfig.readText()).root
            assertFalse(reset.has("model_provider")); assertFalse(reset.has("model_catalog_json")); assertTrue(reset.has("mcp_servers"))
            assertEquals(officialLogin + "\n", codexAuth.readText())
            assertFalse(File(state, "providers-v1.json").readText().contains("codex-private-key"))
        } finally {
            check(base.parentFile!!.canonicalFile == app.filesDir.canonicalFile && base.name.startsWith("provider-test-"))
            java.nio.file.Files.walk(base.toPath()).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { java.nio.file.Files.delete(it) } }
        }
    }

    @Test fun readsModelsThroughAndroidHttpWithCredentialHeaders() {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            server.soTimeout = 10000
            val captured = AtomicReference<String>()
            val worker = Thread {
                server.accept().use { socket ->
                    socket.soTimeout = 10000
                    val reader = socket.getInputStream().bufferedReader()
                    val headers = StringBuilder()
                    while (true) { val line = reader.readLine() ?: break; if (line.isEmpty()) break; headers.append(line).append('\n') }
                    captured.set(headers.toString())
                    val body = """{"data":[{"id":"local-model"}]}""".toByteArray()
                    val output = socket.getOutputStream()
                    output.write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray()); output.write(body); output.flush()
                }
            }.apply { isDaemon = true; start() }
            val source = JSONObject().put("api", "openai-completions").put("baseUrl", "http://127.0.0.1:${server.localPort}/v1").put("apiKey", "local-fixture-key").put("headers", JSONObject().put("X-Title", "agentM"))
            val response = ProviderModelDiscovery().fetch("pi", source.toString())
            worker.join(12000)
            assertEquals("local-model", response.getJSONArray("models").getJSONObject(0).getString("id"))
            assertTrue(captured.get().contains("GET /v1/models")); assertTrue(captured.get().contains("Authorization: Bearer local-fixture-key"))
            assertTrue(captured.get().contains("X-Title: agentM"))
        }
    }
}
