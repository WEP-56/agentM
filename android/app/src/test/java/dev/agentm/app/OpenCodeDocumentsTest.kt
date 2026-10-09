package dev.agentm.app

import dev.agentm.app.config.OpenCodeDocuments as OC
import dev.agentm.app.config.ProviderDocuments as Docs
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class OpenCodeDocumentsTest {
    private val source = """{"npm":"@ai-sdk/openai-compatible","options":{"baseURL":"https://example.test/v1","apiKey":"fixture-key","timeout":false,"setCacheKey":true,"headers":{"X-App":"agentM"}},"models":{"model-a":{"name":"Model A","reasoning":true,"limit":{"context":128000,"output":8192},"modalities":{"input":["text","image","pdf"],"output":["text"]},"options":{"reasoningEffort":"high"},"cost":{"input":1,"output":2},"variants":{"fast":{"disabled":false,"reasoningEffort":"low"}}}}}"""
    @Test fun sdkOptionsAndModelPropertiesStayTypedInNativeProvider() {
        val provider = Docs.validate("opencode", source, "custom")
        val changes = OC.apply(emptyMap(), "custom", provider, "model-a")
        assertEquals(setOf("opencodeJson"), changes.keys)
        val root = OC.merged(changes)
        assertEquals("custom/model-a", root.getString("model"))
        assertTrue(Docs.equal(provider, root.getJSONObject("provider").getJSONObject("custom")))
        assertFalse(root.has("providers")); assertFalse(root.has("defaultProvider"))
    }
    @Test fun layeredJsoncEditsKeepOtherProvidersPluginsAndCommentsWithoutResurrectingRemovedFields() {
        val before = mapOf(
            "opencodeLegacy" to """{"provider":{"custom":{"options":{"old":true}}},"small_model":"custom/old"}""".toByteArray(),
            "opencodeJson" to """{"provider":{"custom":{"options":{"stale":true}},"other":{"npm":"keep"}},"permission":{"read":"allow"}}""".toByteArray(),
            "opencodeJsonc" to """{
              // user plugins
              "plugin": ["keep-plugin",],
              "provider": {"custom": {"options":{"top":true},},},
              "model": "custom/old",
            }
            """.toByteArray(),
        )
        val next = before + OC.apply(before, "custom", JSONObject(source), "model-a")
        val root = OC.merged(next)
        assertTrue(Docs.equal(JSONObject(source), root.getJSONObject("provider").getJSONObject("custom")))
        assertEquals("keep", root.getJSONObject("provider").getJSONObject("other").getString("npm"))
        assertEquals("allow", root.getJSONObject("permission").getString("read"))
        assertTrue(next.getValue("opencodeJsonc")!!.toString(Charsets.UTF_8).contains("// user plugins\n              \"plugin\": [\"keep-plugin\",],"))
        val removed = next + OC.remove(next, "custom")
        val result = OC.merged(removed)
        assertFalse(result.getJSONObject("provider").has("custom")); assertTrue(result.getJSONObject("provider").has("other"))
        assertFalse(result.has("model")); assertFalse(result.has("small_model"))
        assertEquals("keep-plugin", result.getJSONArray("plugin").getString(0))
    }
    @Test fun jsoncPreservesStringsAndRejectsDuplicateKeysMalformedCommentsAndCommaOnlyObjects() {
        assertEquals("https://example.test/*path*/,}", OC.parse("""{/* comment */"url":"https://example.test/*path*/,}", // line
        "nested":[{"x":1,},],}""").getString("url"))
        for (source in listOf("{,}", "[ , ]", "{\"x\":1,,}", "{\"x\":1,\"x\":2}", "{\"x\":1/*", "{\"x\":'bad'}"))
            assertThrows(IllegalArgumentException::class.java) { OC.parse(source) }
        assertThrows(IllegalArgumentException::class.java) { Docs.parse("{/*comment*/}") }
        assertThrows(java.nio.charset.CharacterCodingException::class.java) { OC.merged(mapOf("opencodeJson" to byteArrayOf(0x7b, 0x22, 0xff.toByte(), 0x22, 0x3a, 0x31, 0x7d))) }
        // Removing the only provider/default must also remove its trailing comma.
        val original = mapOf("opencodeJsonc" to """{"provider":{"custom":{},},"model":"custom/a",}""".toByteArray())
        assertEquals(0, OC.merged(original + OC.remove(original, "custom")).getJSONObject("provider").length())
    }
    @Test fun invalidKnownSdkAndModelFieldsAreRejectedWhileBuiltinDefaultsAreAllowed() {
        Docs.validate("opencode", """{"models":{"native-model":{}}}""", "anthropic")
        val invalid = listOf(
            JSONObject(source).apply { getJSONObject("options").put("timeout", "false") },
            JSONObject(source).apply { getJSONObject("options").put("headers", JSONObject().put("X-App", "bad\nheader")) },
            JSONObject(source).apply { getJSONObject("models").getJSONObject("model-a").put("variants", JSONObject().put("bad", true)) },
            JSONObject(source).apply { getJSONObject("models").getJSONObject("model-a").put("reasoning", "true") },
            JSONObject(source).apply { getJSONObject("models").getJSONObject("model-a").put("cost", JSONObject().put("input", 1)) },
            JSONObject(source).apply { put("models", org.json.JSONArray()) },
        )
        for (json in invalid) assertThrows(IllegalArgumentException::class.java) { Docs.validate("opencode", json.toString(), "custom") }
        assertThrows(IllegalArgumentException::class.java) { Docs.validate("opencode", source, "../invalid") }
    }
    @Test fun disabledProvidersCannotBeReportedAsSuccessfullySwitched() {
        for (json in listOf("""{"disabled_providers":["custom"]}""", """{"enabled_providers":["other"]}""")) {
            assertFalse(OC.enabled(JSONObject(json), "custom"))
            assertThrows(IllegalArgumentException::class.java) { OC.apply(mapOf("opencodeJson" to json.toByteArray()), "custom", JSONObject(source), "model-a") }
        }
    }
}
