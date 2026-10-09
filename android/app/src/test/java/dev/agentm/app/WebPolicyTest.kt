package dev.agentm.app

import dev.agentm.app.web.WebPolicy
import dev.agentm.app.packages.ManagedPackagePaths
import org.junit.Assert.*
import org.junit.Test

class WebPolicyTest {
    @Test fun addressBarDoesNotBypassOriginIsolation() {
        assertEquals("http://127.0.0.1:4321/project", WebPolicy.navigationUrl("/project", 4321))
        assertEquals("http://127.0.0.1:4321/project", WebPolicy.navigationUrl("127.0.0.1:4321/project", 4321))
        for (value in listOf("//example.com/", "ws://127.0.0.1:4321/", "javascript:alert(1)", "file:///etc/passwd", "http://127.0.0.1:4322/", "http://user:secret@127.0.0.1:4321/", "http://127.0.0.1:4321.example.com/")) {
            assertNull(value, WebPolicy.navigationUrl(value, 4321))
        }
        assertEquals("http://127.0.0.1:4321/project", WebPolicy.displayUrl("http://127.0.0.1:4321/project?token=secret#password", 4321))
    }
    @Test fun desktopUserAgentKeepsActualChromiumVersion() {
        val desktop = WebPolicy.desktopUserAgent("Mozilla/5.0 (Linux; Android 15; device Build/ABC; wv) AppleWebKit/537.36 Version/4.0 Chrome/125.0.1.2 Mobile Safari/537.36")
        assertTrue(desktop.contains("X11; Linux x86_64")); assertTrue(desktop.contains("Chrome/125.0.1.2"))
        assertFalse(desktop.contains("Android")); assertFalse(desktop.contains("Mobile")); assertFalse(desktop.contains("wv"))
    }
    @Test fun originsCannotCrossHostPortOrProtocol() {
        assertTrue(WebPolicy.sameOrigin("http://127.0.0.1:12345/assets/app.js", 12345))
        assertTrue(WebPolicy.sameOrigin("ws://127.0.0.1:12345/events", 12345))
        for (url in listOf("http://localhost:12345/", "http://127.0.0.1:12346/", "http://127.0.0.1.example:12345/", "http://user@127.0.0.1:12345/", "file:///root/.dsh", "https://127.0.0.1:12345/")) assertFalse(WebPolicy.sameOrigin(url, 12345))
    }
    @Test fun dshStartupMustBeWholeUnambiguousOfficialLine() {
        val token = "x".repeat(43)
        val line = "dsh web: http://127.0.0.1:45454/?token=$token"
        assertEquals(45454 to token, WebPolicy.dshStartup("startup\n$line\n"))
        for (text in listOf(line + "x", line + "&next=elsewhere", line.replace("127.0.0.1", "localhost"), line.replace(":45454", ":99999"), "example $line", line + "\n" + line.replace("45454", "45455"))) assertNull(WebPolicy.dshStartup(text))
        assertFalse(WebPolicy.redact(line).contains(token))
    }
    @Test fun cookieRequiresDshSignatureLayout() {
        val pair = "dsh-auth-${"a".repeat(43)}=v1.payload.${"b".repeat(43)}"
        assertEquals(pair, WebPolicy.cookie("$pair; Path=/; HttpOnly"))
        assertNull(WebPolicy.cookie("arbitrary=secret"))
        assertNull(WebPolicy.cookie(pair + "\r\nHeader: injected"))
    }
    @Test fun dshHasNoTerminalRouteAndCannotTakeOverOtherSlots() {
        assertTrue("opencode" in ManagedPackagePaths.terminals)
        assertFalse("dsh" in ManagedPackagePaths.terminals)
        assertTrue(ManagedPackagePaths.validVersion("0.2.0-rc.2"))
        assertFalse(ManagedPackagePaths.validVersion("0.2.0;id"))
        assertTrue(ManagedPackagePaths.validEntry("dsh", "bin/dsh"))
        assertFalse(ManagedPackagePaths.validEntry("dsh", "package/bin/opencode"))
        assertFalse(ManagedPackagePaths.validEntry("opencode", "bin/dsh"))
    }
}
