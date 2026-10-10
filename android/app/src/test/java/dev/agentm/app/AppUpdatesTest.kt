package dev.agentm.app

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class AppUpdatesTest {
    private fun release(tag: String) = JSONObject().put("tag_name", tag).put("name", "untrusted display title v99.0.0")
        .put("draft", false).put("prerelease", false).put("body", "Release notes").put("html_url", "https://untrusted.example/")

    @Test fun comparesNumericTagVersionsAndUsesTheOfficialReleasePage() {
        val result = AppUpdates { release("v0.15.10") }.check("0.15.9")
        assertTrue(result.getBoolean("updateAvailable"))
        assertEquals("0.15.10", result.getString("latestVersion"))
        assertEquals("https://github.com/WEP-56/agentM/releases/tag/v0.15.10", result.getString("releaseUrl"))
        assertEquals("Release notes", result.getString("notes"))
        assertTrue(AppUpdates { release("v1.0.0") }.check("0.99.99").getBoolean("updateAvailable"))
        assertFalse(AppUpdates { release("v0.15.9") }.check("0.15.10").getBoolean("updateAvailable"))
    }
    @Test fun equalNewerLocalAndPreviewBuildsNeverOfferADowngrade() {
        assertFalse(AppUpdates { release("v0.15.1") }.check("0.15.1").getBoolean("updateAvailable"))
        assertFalse(AppUpdates { release("v0.14.1") }.check("0.15.1").getBoolean("updateAvailable"))
        assertTrue(AppUpdates { release("v0.15.1") }.check("0.15.1-rc.1").getBoolean("updateAvailable"))
        assertFalse(AppUpdates { release("v0.15.1") }.check("0.16.0-beta.2").getBoolean("updateAvailable"))
    }
    @Test fun missingReleasesAndNetworkFailureAreNotReportedAsAlreadyLatest() {
        val missing = AppUpdates { null }.check("0.15.1")
        assertFalse(missing.getBoolean("found")); assertFalse(missing.has("latestVersion"))
        assertThrows(IOException::class.java) { AppUpdates { throw IOException("offline") }.check("0.15.1") }
    }
    @Test fun draftsPreviewsAndMalformedTagsAreRejected() {
        for (payload in listOf(release("v0.16.0").put("draft", true), release("v0.16.0").put("prerelease", true),
            release("v0.16.0-beta.1"), release("0.16.0"), release("v0.016.0"), release("v0.16.0/../../other"),
            release("v999999999999999999999.0.0"))) {
            assertThrows(IllegalArgumentException::class.java) { AppUpdates { payload }.check("0.15.1") }
        }
        assertThrows(IllegalArgumentException::class.java) { AppUpdates { release("v0.16.0") }.check("invalid") }
    }
    @Test fun projectLinksAreLimitedToThisRepositoryAndValidatedTags() {
        assertEquals("https://github.com/WEP-56/agentM", AppUpdates.projectUrl("repository"))
        assertEquals("https://github.com/WEP-56/agentM/releases/latest", AppUpdates.projectUrl("releases"))
        for (tag in listOf("../issues", "https://other.example", "v1.2.3?x=1", "v1.2.3#anchor"))
            assertThrows(IllegalArgumentException::class.java) { AppUpdates.projectUrl("release", tag) }
        assertThrows(IllegalArgumentException::class.java) { AppUpdates.projectUrl("external") }
    }
    @Test fun emptyAndLongReleaseNotesHaveBoundedPlainTextResults() {
        assertEquals("", AppUpdates { release("v0.16.0").put("body", JSONObject.NULL) }.check("0.15.1").getString("notes"))
        assertEquals(12000, AppUpdates { release("v0.16.0").put("body", "x".repeat(20000)) }.check("0.15.1").getString("notes").length)
    }
    @Test fun rateLimitFallbackAcceptsOnlyThisRepositoriesStableReleaseRedirect() {
        for (location in listOf("https://github.com/WEP-56/agentM/releases/tag/v0.16.0", "/WEP-56/agentM/releases/tag/v0.16.0")) {
            val result = AppUpdates { AppUpdates.releaseFromRedirect(location) }.check("0.15.1")
            assertTrue(result.getBoolean("updateAvailable")); assertEquals("v0.16.0", result.getString("tag"))
            assertEquals("", result.getString("notes"))
        }
        for (location in listOf("https://evil.example/releases/tag/v0.16.0", "https://github.com/other/repo/releases/tag/v0.16.0",
            "/WEP-56/agentM/releases/tag/v0.16.0-beta.1", "/WEP-56/agentM/releases/tag/v0.16.0?x=1", "/login")) {
            assertThrows(IllegalArgumentException::class.java) { AppUpdates.releaseFromRedirect(location) }
        }
    }
}
