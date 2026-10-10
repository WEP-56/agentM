package dev.agentm.app

import org.junit.Assert.*
import org.junit.Test

class BridgePolicyTest {
    @Test fun onlyThePackagedMainFrameCanInvokeNativeActions() {
        for (method in listOf("checkAppUpdate", "openProjectPage", "openTerminal", "openWeb", "stopWeb", "readClaudeConfig", "applyClaudeConfig", "listClaudeProfiles", "saveClaudeProfile", "deleteClaudeProfile", "previewClaudeProfile", "listWorkingDirectories", "setWorkingDirectory", "createWorkingDirectory")) {
            assertTrue(BridgePolicy.accepts(BridgePolicy.ORIGIN, true, method, "req_1", 80))
            for (origin in listOf("http://127.0.0.1:3080", "https://example.com", "${BridgePolicy.ORIGIN}.evil.test", "null")) {
                assertFalse(BridgePolicy.accepts(origin, true, method, "req_1", 80))
            }
            assertFalse(BridgePolicy.accepts(BridgePolicy.ORIGIN, false, method, "req_1", 80))
        }
    }
    @Test fun unknownCommandsAndOversizedRequestsAreRejected() {
        assertFalse(BridgePolicy.accepts(BridgePolicy.ORIGIN, true, "execShell", "req_1", 80))
        assertFalse(BridgePolicy.accepts(BridgePolicy.ORIGIN, true, "inspect", "", 80))
        assertFalse(BridgePolicy.accepts(BridgePolicy.ORIGIN, true, "inspect", "req_1", 65537))
    }
}
