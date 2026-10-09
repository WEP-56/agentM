package dev.agentm.app

import dev.agentm.app.packages.UpdatePolicy
import dev.agentm.app.packages.ManagedPackagePaths
import org.junit.Assert.*
import org.junit.Test

class UpdatePolicyTest {
    @Test fun onlyNativePatchRecipesAcceptDynamicVersions() {
        for (kind in listOf("claude", "codex", "opencode")) {
            assertTrue(UpdatePolicy.accepts(kind, "1.2.3", "1.2.10"))
            for (version in listOf("1.2.2", "1.3.0", "2.0.0", "1.2.4-rc.1", "1.2.4;id", "9999999999.2.4"))
                assertFalse(version, UpdatePolicy.accepts(kind, "1.2.3", version))
        }
        for (kind in listOf("pi", "dsh", "node", "unknown")) assertFalse(UpdatePolicy.accepts(kind, "1.2.3", "1.2.4"))
    }
    @Test fun updatesNeverDowngradeOrCompareLexicographically() {
        assertTrue(UpdatePolicy.newer("1.2.10", "1.2.9"))
        assertFalse(UpdatePolicy.newer("1.2.9", "1.2.10"))
        assertFalse(UpdatePolicy.newer("1.2.3", "1.2.3"))
        assertFalse(UpdatePolicy.newer("latest", "1.2.3"))
        assertTrue(UpdatePolicy.newer("0.2.0-rc.10", "0.2.0-rc.2"))
        assertTrue(UpdatePolicy.newer("0.2.0", "0.2.0-rc.2"))
        assertFalse(UpdatePolicy.newer("0.2.0-rc.2", "0.2.0"))
    }
    @Test fun checksAreSeparatedAtTheNativeActionBoundary() {
        assertEquals("node", ManagedPackagePaths.checkActions["checkTools"])
        assertEquals(setOf("claude", "codex", "opencode", "pi", "dsh"), ManagedPackagePaths.updateCheckActions.values.toSet())
        assertNull(ManagedPackagePaths.checkActions["checkPackages"])
        assertEquals("codex", ManagedPackagePaths.updateActions["updateCodex"])
        assertNull(ManagedPackagePaths.updateActions["updateCodex;id"])
    }
}
