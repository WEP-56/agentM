package dev.agentm.app

import dev.agentm.app.packages.ManagedPackagePaths
import org.junit.Assert.*
import org.junit.Test

class ManagedPackagePathsTest {
    @Test fun executableLayoutCannotCrossAgentOrArchitectureNamespaces() {
        assertTrue(ManagedPackagePaths.validEntry("codex", "package/vendor/x86_64-unknown-linux-musl/bin/codex"))
        assertTrue(ManagedPackagePaths.validEntry("codex", "package/vendor/aarch64-unknown-linux-musl/bin/codex"))
        assertFalse(ManagedPackagePaths.validEntry("claude", "package/vendor/x86_64-unknown-linux-musl/bin/codex"))
        for (entry in listOf("package/claude", "package/vendor/../bin/codex", "package/vendor/x86_64-pc-windows-msvc/bin/codex.exe", "/system/bin/sh", "package/vendor/x86_64-unknown-linux-musl/bin/codex;id"))
            assertFalse(ManagedPackagePaths.validEntry("codex", entry))
    }
    @Test fun guestCommandsAndSlotsRejectTraversalAndShellSyntax() {
        val slot = "codex-12345678-1234-1234-1234-123456789abc"
        assertTrue(ManagedPackagePaths.validSlot(slot))
        assertFalse(ManagedPackagePaths.validSlot("../$slot"))
        val guest = "/opt/agentm/slots/$slot/package/vendor/x86_64-unknown-linux-musl/bin/codex"
        assertTrue(ManagedPackagePaths.validGuest(guest))
        for (path in listOf("$guest;id", "$guest --help", "/opt/agentm/slots/$slot/../../bin/sh", "$guest\n"))
            assertFalse(ManagedPackagePaths.validGuest(path))
    }
    @Test fun piActionsAndEntrypointStayWithinTheirOwnSlot() {
        assertEquals("pi", ManagedPackagePaths.installActions["installPi"])
        assertEquals("pi", ManagedPackagePaths.removeActions["removePi"])
        assertNull(ManagedPackagePaths.removeActions["removePi;id"])
        assertTrue(ManagedPackagePaths.validEntry("pi", "bin/pi"))
        assertTrue(ManagedPackagePaths.validSlot("pi-12345678-1234-1234-1234-123456789abc"))
        for (entry in listOf("bin/pi;id", "package/dist/bundle/cli.js", "bin/../bin/pi", "package/claude"))
            assertFalse(ManagedPackagePaths.validEntry("pi", entry))
        for (kind in listOf("node", "claude", "codex")) assertFalse(ManagedPackagePaths.validEntry(kind, "bin/pi"))
    }
}
