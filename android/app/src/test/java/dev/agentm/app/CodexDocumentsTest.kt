package dev.agentm.app

import dev.agentm.app.config.CodexDocuments as CD
import dev.agentm.app.config.ProviderDocuments as PD
import dev.agentm.app.config.TomlDocument
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class CodexDocumentsTest {
    private fun asset(name: String) = JSONObject(File("src/main/assets/$name").readText())
    private fun docs() = CD(asset("codex-native-responses-template.json"), asset("codex-models-0.161.0.json").getJSONArray("models"), asset("codex-deepseek-catalog-template.json").getJSONArray("models"))
    private fun custom() = CD.blank().put("auth", JSONObject().put("OPENAI_API_KEY", "fixture-secret"))
        .put("config", "model_provider = 'relay'\nmodel = 'model-a'\nmodel_reasoning_effort = 'high'\n[model_providers.relay]\nname = 'Relay'\nbase_url = 'https://relay.example.test/v1'\nwire_api = 'responses'\nrequires_openai_auth = true\n")
        .put("modelCatalog", JSONObject().put("models", JSONArray().put(JSONObject().put("model", "model-a").put("displayName", "Display A").put("contextWindow", 200000).put("reasoningLevels", JSONArray(listOf("low", "high"))).put("defaultReasoningLevel", "low").put("inputModalities", JSONArray(listOf("text"))))))
    @Test fun thirdPartyKeyIsProjectedIntoCustomRouteAndOfficialLoginRemainsByteIdentical() {
        val login = """{"auth_mode":"chatgpt","tokens":{"access_token":"keep-oauth","refresh_token":"refresh"},"last_refresh":"keep"}""".toByteArray()
        val config = "# global\n[mcp_servers.keep]\ncommand = 'keep'\n[projects.'/workspace']\ntrust_level = 'trusted'\n[agents]\nmax_threads = 3\ndefault_subagent_model = 'old'\n"
        val before = mapOf("codexAuth" to login, "codexConfig" to config.toByteArray())
        val changes = docs().apply(before, custom(), false)
        assertFalse(changes.containsKey("codexAuth"))
        val text = CD.text(changes["codexConfig"]); val root = TomlDocument(text).root
        assertTrue(text.startsWith("# global\n")); assertTrue(text.contains("[mcp_servers.keep]\ncommand = 'keep'"))
        assertEquals("custom", root.getString("model_provider"))
        assertFalse(root.getJSONObject("agents").has("default_subagent_model")); assertEquals(3, root.getJSONObject("agents").getInt("max_threads"))
        val provider = root.getJSONObject("model_providers").getJSONObject("custom")
        assertEquals("fixture-secret", provider.getString("experimental_bearer_token")); assertTrue(provider.getBoolean("requires_openai_auth"))
        assertEquals("/root/.codex/${CD.CATALOG}", root.getString("model_catalog_json"))
        val reset = docs().apply(before + changes, CD.blank(true), true, custom())
        val official = TomlDocument(CD.text(reset["codexConfig"])).root
        assertFalse(official.has("model_provider")); assertFalse(official.has("model_catalog_json")); assertFalse(official.has("model"))
        assertFalse(official.getJSONObject("model_providers").getJSONObject("custom").has("experimental_bearer_token"))
        assertFalse(reset.containsKey("codexAuth")); assertArrayEquals(login, (before + changes + reset)["codexAuth"])
    }
    @Test fun directAuthFollowsCcSwitchPrecedenceAndNeverFallsBackToOfficialCredentials() {
        val row = custom()
        val none = docs().apply(emptyMap(), row, false)
        assertFalse(TomlDocument(CD.text(none["codexConfig"])).root.getJSONObject("model_providers").getJSONObject("custom").getBoolean("requires_openai_auth"))
        row.put("auth", JSONObject())
        assertThrows(IllegalArgumentException::class.java) { CD.validate(row, false) }
        row.put("config", row.getString("config").replace("requires_openai_auth = true", "requires_openai_auth = false\nhttp_headers = { Authorization = 'Bearer header-secret' }"))
        val header = docs().apply(mapOf("codexAuth" to """{"OPENAI_API_KEY":"official-key"}""".toByteArray()), row, false)
        assertFalse(TomlDocument(CD.text(header["codexConfig"])).root.getJSONObject("model_providers").getJSONObject("custom").getBoolean("requires_openai_auth"))
        assertThrows(IllegalArgumentException::class.java) { CD.validate(custom(), true) }
        assertThrows(IllegalArgumentException::class.java) { CD.validate(custom().put("config", custom().getString("config").replace("'responses'", "'chat'")), false) }
    }
    @Test fun modelCatalogUsesCcSwitchNativeTemplateAndTypedDeclarations() {
        val catalog = docs().catalog(custom())!!
        val row = catalog.getJSONArray("models").getJSONObject(0)
        assertEquals("Display A", row.getString("display_name")); assertEquals(200000, row.getInt("context_window"))
        assertEquals("low", row.getString("default_reasoning_level")); assertEquals("shell_command", row.getString("shell_type"))
        assertEquals("text", row.getJSONArray("input_modalities").getString(0)); assertEquals(1, row.getJSONArray("input_modalities").length())
        assertTrue(row.getString("base_instructions").isNotBlank()); assertFalse(row.has("apply_patch_tool_type")); assertFalse(row.has("model_messages"))
        assertEquals("none", row.getString("default_reasoning_summary")); assertEquals(1000, row.getInt("priority"))
        val invalid = custom(); invalid.getJSONObject("modelCatalog").getJSONArray("models").getJSONObject(0).put("defaultReasoningLevel", "ultra")
        assertThrows(IllegalArgumentException::class.java) { CD.validate(invalid, false) }
        assertThrows(IllegalArgumentException::class.java) { CD.validate(custom().put("config", custom().getString("config").replace("'model-a'", "'missing'")), false) }
        val ccRow = custom()
        val model = ccRow.getJSONObject("modelCatalog").getJSONArray("models").getJSONObject(0)
        model.put("contextWindow", "262144").put("reasoningLevels", JSONArray()).put("defaultReasoningLevel", "").put("inputModalities", JSONArray())
        CD.validate(ccRow, false)
        assertEquals(262144, docs().catalog(ccRow)!!.getJSONArray("models").getJSONObject(0).getInt("context_window"))
        model.put("contextWindow", "")
        CD.validate(ccRow, false)
        assertEquals(128000, docs().catalog(ccRow)!!.getJSONArray("models").getJSONObject(0).getInt("context_window"))
    }
    @Test fun gptCatalogMirrorsPinnedOfficialDefinitionInsteadOfNeutralInstructions() {
        val official = asset("codex-models-0.161.0.json").getJSONArray("models").getJSONObject(0)
        val slug = official.getString("slug")
        val source = custom().put("config", custom().getString("config").replace("'model-a'", TomlDocument.literal(slug)))
        source.getJSONObject("modelCatalog").put("models", JSONArray().put(JSONObject().put("model", slug).put("contextWindow", 1).put("displayName", "Ignored")))
        val row = docs().catalog(source)!!.getJSONArray("models").getJSONObject(0)
        assertEquals(official.get("context_window"), row.get("context_window")); assertEquals(official.get("display_name"), row.get("display_name"))
        assertTrue(PD.equal(official.opt("model_messages"), row.opt("model_messages")))
        assertFalse(row.getBoolean("use_responses_lite")); assertEquals(0, row.getJSONArray("service_tiers").length())
    }
    @Test fun conflictingProfileCannotApplyAndForeignCatalogIsNeverReadOrOverwritten() {
        val current = "profile = 'work'\n[profiles.work]\nmodel_provider = 'elsewhere'\n"
        assertThrows(IllegalArgumentException::class.java) { docs().apply(mapOf("codexConfig" to current.toByteArray()), custom(), false) }
        val row = custom().put("config", "model_catalog_json = '/unreadable/user-catalog.json'\n" + custom().getString("config"))
        val changes = docs().apply(emptyMap(), row, false)
        assertFalse(changes.containsKey("codexCatalog"))
        assertEquals("/unreadable/user-catalog.json", TomlDocument(CD.text(changes["codexConfig"])).root.getString("model_catalog_json"))
    }
    @Test fun sourceImportDoesNotExposeOfficialTokensAndOldIdsAreNormalized() {
        val source = CD.imported(custom().getString("config"), """{"OPENAI_API_KEY":"legacy-key","tokens":{"refresh_token":"never-export"}}""")
        assertEquals("legacy-key", source.getJSONObject("auth").getString("OPENAI_API_KEY")); assertFalse(source.toString().contains("never-export"))
        assertEquals("custom", CD.project(source, false).selector)
        assertFalse(CD.hasLogin(JSONObject("""{"auth_mode":"bedrockApiKey","OPENAI_API_KEY":"stale","bedrock_api_key":"key"}""")))
        assertTrue(CD.hasLogin(JSONObject("""{"tokens":{"refresh_token":"keep"}}""")))
        assertFalse(CD.hasLogin(JSONObject("""{"last_refresh":"metadata"}""")))
        assertFalse(CD.hasLogin(JSONObject("""{"auth_mode":"apikey","OPENAI_API_KEY":null}""")))
        assertFalse(CD.hasLogin(JSONObject("""{"tokens":{"access_token":null}}""")))
        assertEquals("", CD.key(CD.blank().put("auth", JSONObject().put("OPENAI_API_KEY", JSONObject.NULL)), JSONObject()))
    }
    @Test fun autoDisabledWebSearchAndOwnedLimitsAreRemovedOnOfficialWithoutClobberingExternalChanges() {
        val source = custom().put("config", "model_context_window = 1000000\n" + custom().getString("config").replace("relay.example.test", "api.minimax.io"))
        val first = docs().apply(emptyMap(), source, false)
        assertEquals("disabled", TomlDocument(CD.text(first["codexConfig"])).root.getString("web_search"))
        val last = docs().appliedSource(source)
        val reset = docs().apply(first, CD.blank(true), true, last)
        val root = TomlDocument(CD.text(reset["codexConfig"])).root
        assertFalse(root.has("web_search")); assertFalse(root.has("model_context_window"))
        val external = TomlDocument(CD.text(first["codexConfig"])).set("model_context_window", 123456)
        val retained = docs().apply(first + mapOf("codexConfig" to external.toByteArray()), CD.blank(true), true, last)
        assertEquals(123456, TomlDocument(CD.text(retained["codexConfig"])).root.getInt("model_context_window"))
    }
    @Test fun exactTextOnlyRegistryDoesNotBlockNewVisionVariantsAndLegacyReroutingNeedsAKey() {
        assertFalse(CD.defaultImageInput("vendor/MiniMax-M2.7[1m]"))
        assertFalse(CD.defaultImageInput("glm-5.2")); assertTrue(CD.defaultImageInput("glm-5.2v"))
        assertTrue(CD.defaultImageInput("deepseek-v4-flash")); assertTrue(CD.defaultImageInput("unknown-model"))
        val legacy = CD.blank().put("config", "openai_base_url='https://relay.example.test/v1'\n")
        assertThrows(IllegalArgumentException::class.java) { CD.validate(legacy, false) }
        legacy.put("auth", JSONObject().put("OPENAI_API_KEY", "fixture"))
        CD.validate(legacy, false)
        assertEquals("custom", CD.project(legacy, false).selector)
    }
}
