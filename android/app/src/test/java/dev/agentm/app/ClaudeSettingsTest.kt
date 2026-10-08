package dev.agentm.app

import dev.agentm.app.config.ClaudeSettings
import dev.agentm.app.config.JsonDocument
import org.junit.Assert.*
import org.junit.Test

class ClaudeSettingsTest {
    @Test fun editsOnlyOwnedSpansAndPreservesFormatting() {
        val source = "\uFEFF{\r\n  \"hooks\" : {\"cmd\": [\"echo hi\", 1e+02]},\r\n  \"env\" : { \"OTHER\": \"keep\", \"ANTHROPIC_API_KEY\" : \"old\", \"ANTHROPIC_MODEL\": \"old-model\" },\r\n  \"permissions\": {\"allow\": []}\r\n}\r\n"
        val desired = mapOf(ClaudeSettings.KEY to "new", ClaudeSettings.MODEL to "new-model")
        assertEquals(source.replace("\"old\"", "\"new\"").replace("\"old-model\"", "\"new-model\""), ClaudeSettings.patch(source, desired))
        assertEquals(source, ClaudeSettings.patch(source, ClaudeSettings.values(source)))
    }
    @Test fun escapedNamesAndValuesAreNotNormalizedWhenUnchanged() {
        val source = "{\"env\":{\"ANTHROPIC_\\u004dODEL\":\"model\\u002d1\"},\"other\":-0.00e+00}"
        assertEquals("model-1", ClaudeSettings.values(source)[ClaudeSettings.MODEL])
        assertEquals(source, ClaudeSettings.patch(source, mapOf(ClaudeSettings.MODEL to "model-1")))
    }
    @Test fun switchingAuthRemovesOnlyTheOtherCredential() {
        val source = "{\"env\":{\"ANTHROPIC_API_KEY\":\"old-key\",\"CUSTOM\":\"kept\"},\"theme\":\"dark\"}"
        val input = ClaudeSettings.Input("https://api.example.invalid/anthropic", "model-1", "authToken", "replace", "new-token")
        val after = ClaudeSettings.patch(source, ClaudeSettings.desired(source, input))
        val fields = ClaudeSettings.values(after)
        assertFalse(fields.containsKey(ClaudeSettings.KEY))
        assertEquals("new-token", fields[ClaudeSettings.TOKEN])
        assertTrue(after.contains("\"CUSTOM\":\"kept\""))
        assertTrue(after.contains("\"theme\":\"dark\""))
        val cleared = ClaudeSettings.patch(after, ClaudeSettings.desired(after, input.copy(authMode = "native", model = "")))
        assertEquals(emptyMap<String, String>(), ClaudeSettings.values(cleared))
        assertTrue(cleared.contains("\"CUSTOM\":\"kept\""))
    }
    @Test fun insertionAndDeletionKeepValidJsonInEveryMemberPosition() {
        for (source in listOf("{}", "{\n}", "{\"env\":{}}", "{\"env\":{\"ANTHROPIC_MODEL\":\"m\",\"X\":1}}", "{\"env\":{\"X\":1,\"ANTHROPIC_MODEL\":\"m\"}}")) {
            val next = ClaudeSettings.patch(source, mapOf(ClaudeSettings.BASE to "https://example.invalid", ClaudeSettings.KEY to "k", ClaudeSettings.MODEL to "m"))
            assertEquals("k", ClaudeSettings.values(next)[ClaudeSettings.KEY])
            assertEquals(emptyMap<String, String>(), ClaudeSettings.values(ClaudeSettings.patch(next, emptyMap())))
        }
    }
    @Test fun rejectsAmbiguousOrMalformedJson() {
        val invalid = listOf("{\"env\":{},\"env\":{}}", "{\"env\":{\"X\":1,\"\\u0058\":2}}", "{\"env\":[]}", "{\"env\":{\"ANTHROPIC_MODEL\":1}}", "{\"x\":01}", "{\"x\":true,}", "{\"x\": [1,]}", "{\"x\":\"\\u+123\"}", "{} garbage", "{ // comment\n}")
        for (source in invalid) assertThrows(source, IllegalArgumentException::class.java) { ClaudeSettings.values(source) }
        assertThrows(IllegalArgumentException::class.java) { JsonDocument("[".repeat(80) + "0" + "]".repeat(80)) }
    }
    @Test fun validatesEndpointAndNeverReusesTheOtherAuthModeSecret() {
        val source = "{\"env\":{\"ANTHROPIC_API_KEY\":\"old\"}}"
        val input = ClaudeSettings.Input("https://example.invalid", "model", "apiKey", "keep", "")
        assertEquals("old", ClaudeSettings.desired(source, input)[ClaudeSettings.KEY])
        assertThrows(IllegalArgumentException::class.java) { ClaudeSettings.desired(source, input.copy(authMode = "authToken")) }
        for (url in listOf("file:///etc/passwd", "https://user:key@example.invalid", "https://example.invalid?key=secret", "https://example.invalid#secret", "not-a-url"))
            assertThrows(IllegalArgumentException::class.java) { ClaudeSettings.desired(source, input.copy(baseUrl = url)) }
    }
}
