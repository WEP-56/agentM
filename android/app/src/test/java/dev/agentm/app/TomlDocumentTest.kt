package dev.agentm.app

import dev.agentm.app.config.TomlDocument
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class TomlDocumentTest {
    @Test fun changesSelectedFieldsWithoutTouchingCommentsMcpArraysOrMultilineStrings() {
        val original = """# root comment
model = 'old' # keep inline
created = 2026-10-09T12:30:00Z
instructions = """ + "\"\"\"line1\n# not a comment\nmodel = 'inside'\"\"\"\n" + """
[mcp_servers.example]
command = 'python'
args = [
  '-m', # still an array
  'server',
]
[[skills.config]]
path = '/tmp/skill'
enabled = true
"""
        val changed = TomlDocument(original).set("model", "new-模型😀")
        assertTrue(changed.contains("# keep inline")); assertTrue(changed.contains(original.substring(original.indexOf("created ="))))
        assertEquals("new-模型😀", TomlDocument(changed).root.getString("model"))
        assertEquals("inside", TomlDocument(changed).root.getString("instructions").substringAfter("model = '").substringBefore("'"))
        assertFalse(TomlDocument(TomlDocument(changed).set("model", null)).root.has("model"))
    }
    @Test fun nestedTableReplacementKeepsOtherProvidersAndInlineSiblings() {
        val source = """model_provider = "old"
[model_providers.old]
name = "Old"
[model_providers.old.http_headers]
X = "old"
[model_providers.other]
name = "Keep" # same bytes
[agents]
max_threads = 4
default_subagent_model = "old"
"""
        val desired = JSONObject().put("name", "New").put("http_headers", JSONObject().put("X", "new"))
        val result = TomlDocument(source).set(listOf("model_providers", "old"), desired)
        assertEquals("new", TomlDocument(result).root.getJSONObject("model_providers").getJSONObject("old").getJSONObject("http_headers").getString("X"))
        assertTrue(result.contains("[model_providers.other]\nname = \"Keep\" # same bytes"))
        val cleared = TomlDocument(result).set(listOf("agents", "default_subagent_model"), null)
        assertEquals(4, TomlDocument(cleared).root.getJSONObject("agents").getInt("max_threads"))
        val inline = TomlDocument("agents = { max_threads = 3, default_subagent_model = 'old' }\n").set(listOf("agents", "default_subagent_model"), "new")
        assertEquals(3, TomlDocument(inline).root.getJSONObject("agents").getInt("max_threads"))
        assertEquals("new", TomlDocument(inline).root.getJSONObject("agents").getString("default_subagent_model"))
    }
    @Test fun dottedQuotedKeysMultilineQuoteRunsAndDuplicateRejection() {
        val source = "\uFEFFmodel = \"\"\"old\"\"\"\"\n[model_providers.'my.provider']\nname = 'Name'\n"
        val result = TomlDocument(source).set(listOf("model_providers", "my.provider", "base_url"), "https://example.test")
        assertEquals("old\"", TomlDocument(result).root.getString("model"))
        assertEquals("https://example.test", TomlDocument(result).root.getJSONObject("model_providers").getJSONObject("my.provider").getString("base_url"))
        assertThrows(IllegalArgumentException::class.java) { TomlDocument("model='a'\nmodel='b'\n") }
        assertThrows(IllegalArgumentException::class.java) { TomlDocument("model = [ 'broken'\n") }
    }
}
