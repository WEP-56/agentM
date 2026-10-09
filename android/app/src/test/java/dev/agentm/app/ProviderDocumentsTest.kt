package dev.agentm.app

import dev.agentm.app.config.ProviderDocuments as Docs
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ProviderDocumentsTest {
    @Test fun claudeSwitchReplacesCredentialsMappingsAndKeepsUnrelatedSource() {
        val current = """{"permissions":{"allow":["Read"]},"env":{"ANTHROPIC_API_KEY":"old","ANTHROPIC_DEFAULT_OPUS_MODEL":"old-model[1m]","KEEP":"untouched"}}"""
        val provider = JSONObject("""{"env":{"ANTHROPIC_AUTH_TOKEN":"new","ANTHROPIC_BASE_URL":"https://example.test","ANTHROPIC_DEFAULT_SONNET_MODEL":"new-model[1m]","ANTHROPIC_DEFAULT_SONNET_MODEL_NAME":"Display"}}""")
        val result = Docs.claude(current, provider)
        assertTrue(result.contains("\"permissions\":{\"allow\":[\"Read\"]}"))
        val env = JSONObject(result).getJSONObject("env")
        assertFalse(env.has("ANTHROPIC_API_KEY")); assertFalse(env.has("ANTHROPIC_DEFAULT_OPUS_MODEL"))
        assertEquals("new", env.getString("ANTHROPIC_AUTH_TOKEN")); assertEquals("untouched", env.getString("KEEP"))
        val official = JSONObject(Docs.claude(result, JSONObject("{\"env\":{}}")))
        assertFalse(official.getJSONObject("env").has("ANTHROPIC_AUTH_TOKEN"))
        assertEquals("untouched", official.getJSONObject("env").getString("KEEP"))
    }
    @Test fun rawSourceRemovalOnlyRemovesPreviouslyOwnedValues() {
        val previous = JSONObject("""{"env":{"CUSTOM":"old"},"model":"old-model"}""")
        val result = JSONObject(Docs.claude("""{"env":{"CUSTOM":"old","KEEP":"safe"},"model":"old-model","permissions":{}}""", JSONObject(), previous))
        assertFalse(result.has("model")); assertFalse(result.getJSONObject("env").has("CUSTOM")); assertTrue(result.has("permissions"))
        val external = JSONObject(Docs.claude("""{"env":{"CUSTOM":"externally-edited"},"model":"external"}""", JSONObject(), previous))
        assertEquals("external", external.getString("model"))
        val common = JSONObject("""{"permissions":{"ask":["Bash"]},"env":{}}""")
        assertTrue(JSONObject(Docs.claude(common.toString(), JSONObject("{\"env\":{}}"), common, removeRootFields = false)).has("permissions"))
    }
    @Test fun piProviderAndDefaultChangesKeepOtherProvidersAndSettings() {
        val desired = JSONObject("""{"api":"openai-responses","baseUrl":"https://example.test/v1","apiKey":"key","headers":{"X-Title":"App"},"compat":{"supportsStore":false},"models":[{"id":"test","name":"Test","reasoning":true,"input":["text","image"],"contextWindow":128000,"maxTokens":16384}]}""")
        Docs.validate("pi", desired.toString(), "my-provider")
        val result = JSONObject(Docs.piModels("""{"other":true,"providers":{"existing":{"apiKey":"keep"}}}""", "my-provider", desired))
        assertEquals("keep", result.getJSONObject("providers").getJSONObject("existing").getString("apiKey"))
        assertTrue(result.getBoolean("other")); assertTrue(Docs.equal(desired, result.getJSONObject("providers").getJSONObject("my-provider")))
        val defaults = JSONObject(Docs.piDefaults("""{"theme":"dark","defaultProvider":"old","defaultModel":"old-model"}""", "my-provider", "test"))
        assertEquals("dark", defaults.getString("theme")); assertEquals("my-provider", defaults.getString("defaultProvider")); assertEquals("test", defaults.getString("defaultModel"))
    }
    @Test fun invalidSourceCannotSilentlyDropDuplicateKeysOrConflictingSecrets() {
        assertThrows(IllegalArgumentException::class.java) { Docs.parse("""{"env":{},"env":{"KEY":"secret"}}""") }
        assertThrows(IllegalArgumentException::class.java) { Docs.validate("claude", """{"env":{"ANTHROPIC_API_KEY":"a","ANTHROPIC_AUTH_TOKEN":"b"}}""") }
        assertThrows(IllegalArgumentException::class.java) { Docs.validate("claude", """{"env":{"ANTHROPIC_BASE_URL":"https://example.test"}}""", official = true) }
        assertThrows(IllegalArgumentException::class.java) { Docs.validate("pi", """{"api":"unsupported","baseUrl":"https://example.test","models":[]}""", "../bad") }
    }
}
