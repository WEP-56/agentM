package dev.agentm.app

import dev.agentm.app.packages.PackageUpdates
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class PackageUpdatesTest {
    private fun pinned() = JSONObject().put("version", "1.2.3").put("package", "opencode-linux-x64-baseline")
        .put("url", "https://registry.npmjs.org/old.tgz").put("sha256", "a".repeat(64)).put("bytes", 100)
        .put("executable", "package/bin/opencode")
    private fun metadata() = JSONObject().put("name", "opencode-linux-x64-baseline").put("version", "1.2.4")
        .put("dist", JSONObject().put("tarball", "https://registry.npmjs.org/package.tgz").put("integrity", "sha512-" + "A".repeat(86) + "=="))
    @Test fun patchDiscoveryPinsIntegrityWithoutMutatingBundledRecipe() {
        val recipe = pinned()
        val calls = mutableListOf<String>()
        val result = PackageUpdates { path -> calls += path; if (path.endsWith("/latest")) JSONObject().put("version", "1.2.4") else metadata() }.check("opencode", recipe)
        assertEquals(listOf("opencode-ai/latest", "opencode-linux-x64-baseline/1.2.4"), calls)
        assertEquals("1.2.4", result.getString("supportedVersion"))
        assertEquals("native-patch-v1", result.getJSONObject("candidate").getString("recipe"))
        assertFalse(result.getJSONObject("candidate").has("sha256"))
        assertEquals("1.2.3", recipe.getString("version"))
    }
    @Test fun unsupportedReleaseIsVisibleButCannotBecomeAnUpdateCandidate() {
        val result = PackageUpdates { JSONObject().put("version", "2.0.0") }.check("opencode", pinned())
        assertEquals("2.0.0", result.getString("upstreamVersion"))
        assertEquals("1.2.3", result.getString("supportedVersion")); assertFalse(result.has("candidate"))
        for (kind in listOf("pi", "dsh")) {
            val adapted = PackageUpdates { JSONObject().put("version", "1.2.4") }.check(kind, pinned())
            assertFalse(adapted.has("candidate"))
        }
    }
    @Test fun offlineOrMalformedMetadataCannotReturnSuccessfulDiscovery() {
        assertThrows(IOException::class.java) { PackageUpdates { throw IOException("offline") }.check("opencode", pinned()) }
        for (bad in listOf(
            metadata().put("name", "wrong-package"),
            metadata().put("dist", JSONObject().put("tarball", "https://evil.test/package.tgz").put("integrity", "sha512-" + "A".repeat(86) + "==")),
            metadata().put("dist", JSONObject().put("tarball", "https://registry.npmjs.org/package.tgz").put("integrity", "sha1-invalid"))
        )) assertThrows(IllegalArgumentException::class.java) {
            PackageUpdates { path -> if (path.endsWith("/latest")) JSONObject().put("version", "1.2.4") else bad }.check("opencode", pinned())
        }
    }
}
