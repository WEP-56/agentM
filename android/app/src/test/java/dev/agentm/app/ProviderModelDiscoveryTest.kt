package dev.agentm.app

import dev.agentm.app.config.ProviderModelDiscovery
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL

class ProviderModelDiscoveryTest {
    @Test fun versionedBaseIsNotDuplicatedAndFormatsAreParsedWithoutInventingModels() {
        assertEquals(listOf("https://example.test/v1/models"), ProviderModelDiscovery.urls("https://example.test/v1", "openai-responses"))
        assertEquals(listOf("https://example.test/api/models", "https://example.test/api/v1/models"), ProviderModelDiscovery.urls("https://example.test/api", "anthropic-messages"))
        assertEquals(listOf("https://example.test/anthropic/models", "https://example.test/anthropic/v1/models", "https://example.test/models", "https://example.test/v1/models"), ProviderModelDiscovery.urls("https://example.test/anthropic", "anthropic-messages"))
        val models = ProviderModelDiscovery.parse(JSONObject("""{"models":[{"name":"models/gemini-test","displayName":"Gemini"},{"slug":"test"},{"slug":"test"}]}"""))
        assertEquals(2, models.length()); assertEquals("gemini-test", models.getJSONObject(0).getString("id"))
        assertThrows(IllegalStateException::class.java) { ProviderModelDiscovery.parse(JSONObject("{}")) }
    }
    @Test fun credentialsStayInHeadersAndRedirectsAreNotFollowed() {
        var request: HttpURLConnection? = null
        val discovery = ProviderModelDiscovery { url -> object : HttpURLConnection(url) {
            override fun connect() {}
            override fun disconnect() {}
            override fun usingProxy() = false
            override fun getResponseCode(): Int { assertFalse(instanceFollowRedirects); return 302 }
        }.also { request = it } }
        assertThrows(IllegalStateException::class.java) { discovery.fetch("claude", """{"env":{"ANTHROPIC_BASE_URL":"https://example.test/v1","ANTHROPIC_AUTH_TOKEN":"fixture-secret"}}""") }
        assertEquals("Bearer fixture-secret", request!!.getRequestProperty("Authorization"))
        assertEquals("https://example.test/v1/models", request!!.url.toString())
    }
    @Test fun retriesOnlyMissingEndpointsAndHonorsPiHeaders() {
        val seen = mutableListOf<String>()
        val discovery = ProviderModelDiscovery { url: URL -> object : HttpURLConnection(url) {
            override fun connect() {}
            override fun disconnect() {}
            override fun usingProxy() = false
            override fun getResponseCode(): Int { seen += url.toString(); assertEquals("agentM", getRequestProperty("X-Title")); return if (seen.size == 1) 404 else 200 }
            override fun getInputStream() = """{"data":[{"id":"model-a"}]}""".byteInputStream()
        } }
        val result = discovery.fetch("pi", """{"api":"openai-responses","baseUrl":"https://example.test","apiKey":"fixture","headers":{"X-Title":"agentM"}}""")
        assertEquals(2, seen.size); assertEquals("model-a", result.getJSONArray("models").getJSONObject(0).getString("id"))
    }
    @Test fun openCodeSdkSelectsAuthenticationAndDoesNotExpandSecretsOrQueryUnknownSdks() {
        for ((sdk, auth) in listOf("@ai-sdk/anthropic" to "x-api-key", "@ai-sdk/google" to "x-goog-api-key", "@ai-sdk/openai" to "Authorization", "@ai-sdk/openai-compatible" to "Authorization")) {
            val discovery = ProviderModelDiscovery { url -> object : HttpURLConnection(url) {
                override fun connect() {}
                override fun disconnect() {}
                override fun usingProxy() = false
                override fun getResponseCode(): Int {
                    assertEquals(if (auth == "Authorization") "Bearer fixture" else "fixture", getRequestProperty(auth))
                    assertEquals("agentM", getRequestProperty("X-Title")); assertFalse(instanceFollowRedirects); return 200
                }
                override fun getInputStream() = """{"data":[{"id":"native-model"}]}""".byteInputStream()
            } }
            val source = JSONObject().put("npm", sdk).put("options", JSONObject().put("baseURL", "https://example.test/v1").put("apiKey", "fixture").put("headers", JSONObject().put("X-Title", "agentM")))
            assertEquals("native-model", discovery.fetch("opencode", source.toString()).getJSONArray("models").getJSONObject(0).getString("id"))
        }
        var connected = false
        val offline = ProviderModelDiscovery { connected = true; error("must not connect") }
        for ((sdk, secret) in listOf("@ai-sdk/amazon-bedrock" to "fixture", "custom-sdk" to "fixture", "@ai-sdk/openai" to "{env:TOKEN}", "@ai-sdk/openai" to "{file:/secret}")) {
            val source = JSONObject().put("npm", sdk).put("options", JSONObject().put("baseURL", "https://example.test").put("apiKey", secret))
            assertThrows(Exception::class.java) { offline.fetch("opencode", source.toString()) }
            assertFalse("Unsupported SDKs and secret references must fail before connecting", connected)
        }
    }
    @Test fun codexUsesDirectRowKeyAndNativeHeadersWithoutReadingOfficialLogin() {
        var requests = 0
        val discovery = ProviderModelDiscovery { url -> object : HttpURLConnection(url) {
            override fun connect() {}
            override fun disconnect() {}
            override fun usingProxy() = false
            override fun getResponseCode(): Int { requests++; assertEquals("Bearer codex-fixture", getRequestProperty("Authorization")); assertEquals("agentM", getRequestProperty("User-Agent")); return 200 }
            override fun getInputStream() = """{"data":[{"id":"codex-model"}]}""".byteInputStream()
        } }
        val source = JSONObject().put("auth", JSONObject().put("OPENAI_API_KEY", "codex-fixture")).put("config", "model_provider='relay'\n[model_providers.relay]\nbase_url='https://example.test/v1'\nwire_api='responses'\nhttp_headers={User-Agent='agentM'}\n")
        assertEquals("codex-model", discovery.fetch("codex", source.toString()).getJSONArray("models").getJSONObject(0).getString("id"))
        assertEquals(1, requests)
        source.put("config", source.getString("config") + "env_key='SECRET_ENV'\n")
        assertThrows(IllegalArgumentException::class.java) { discovery.fetch("codex", source.toString()) }
        assertEquals(1, requests)
        assertThrows(IllegalStateException::class.java) { discovery.fetch("codex", """{"auth":{},"config":""}""") }
    }
}
