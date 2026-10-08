package dev.agentm.app

import dev.agentm.app.linux.ArchivePaths
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class LinuxSafetyTest {
    @Test fun archiveNamesCannotEscapeStaging() {
        val root = Files.createTempDirectory("agentm-tar").toFile()
        try {
            assertEquals(java.io.File(root, "usr/bin/bash"), ArchivePaths.resolve(root, "./usr/bin/bash"))
            for (name in listOf("../outside", "/etc/passwd", "usr/../../escape", "C:/Windows/file", "..\\outside")) {
                assertThrows(java.io.IOException::class.java) { ArchivePaths.resolve(root, name) }
            }
        } finally { root.delete() }
    }
    @Test fun processBirthRejectsPidReuseAndPreservesNamesWithSpaces() {
        fun stat(start: Long) = "123 (shell (worker)) S 90 123 123 " + List(15) { "0" }.joinToString(" ") + " $start 0\n"
        val first = ProcessIdentity.parse(stat(100))!!
        assertEquals(123, first.pid)
        assertEquals(90, first.parent)
        assertEquals(100L, first.startTicks)
        assertNotEquals(first, ProcessIdentity.parse(stat(101)))
        assertNull(ProcessIdentity.parse("corrupt stat"))
    }
}
