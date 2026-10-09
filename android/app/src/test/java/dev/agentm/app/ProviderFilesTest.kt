package dev.agentm.app

import dev.agentm.app.config.ProviderFiles
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class ProviderFilesTest {
    @get:Rule val temp = TemporaryFolder()
    private fun files(home: File, state: File, fail: (String) -> Unit = {}) = ProviderFiles(home, state, { it.copyOf() }, { it.copyOf() }, fail)
    @Test fun failedSecondPiWriteRollsBackFirstAndCatalog() {
        val home = temp.newFolder("home"); val state = temp.newFolder("state")
        val manager = files(home, state)
        val initial = mapOf("piModels" to "old-models".toByteArray(), "piSettings" to "old-settings".toByteArray(), "library" to "old-library".toByteArray())
        manager.commit(initial.keys.associateWith { "missing" }, initial)
        val failing = files(home, state) { if (it == "piSettings") throw IOException("disk failure") }
        assertThrows(IOException::class.java) {
            failing.commit(initial.mapValues { ProviderFiles.digest(it.value) }, initial.mapValues { "new".toByteArray() })
        }
        for ((key, value) in initial) assertArrayEquals(value, manager.read(key))
        assertFalse(File(state, "providers-transaction.json").exists()); assertTrue(File(state, "providers-backup.json").exists())
    }
    @Test fun staleRevisionAndSymbolicLinksCannotOverwriteFiles() {
        val home = temp.newFolder("home"); val state = temp.newFolder("state"); val manager = files(home, state)
        manager.commit(mapOf("claude" to "missing"), mapOf("claude" to "original".toByteArray()))
        assertThrows(IllegalStateException::class.java) { manager.commit(mapOf("claude" to "missing"), mapOf("claude" to "replacement".toByteArray())) }
        assertEquals("original", manager.text("claude"))
        val outside = temp.newFile("outside").apply { writeText("outside") }
        val pi = File(home, ".pi").apply { mkdirs() }
        try { java.nio.file.Files.createSymbolicLink(File(pi, "agent").toPath(), outside.toPath()) } catch (e: Exception) { org.junit.Assume.assumeNoException(e) }
        assertThrows(IllegalArgumentException::class.java) { manager.read("piModels") }
        assertEquals("outside", outside.readText())
    }
    @Test fun interruptedTransactionCanRecoverAfterRecreation() {
        val home = temp.newFolder("home"); val state = temp.newFolder("state"); val manager = files(home, state)
        manager.commit(mapOf("piModels" to "missing", "piSettings" to "missing"), mapOf("piModels" to "old".toByteArray(), "piSettings" to "old".toByteArray()))
        val crashing = files(home, state) { if (it == "piSettings") throw AssertionError("simulated process death") }
        assertThrows(AssertionError::class.java) { crashing.commit(mapOf("piModels" to ProviderFiles.digest("old".toByteArray()), "piSettings" to ProviderFiles.digest("old".toByteArray())), mapOf("piModels" to "new".toByteArray(), "piSettings" to "new".toByteArray())) }
        files(home, state).recover()
        assertEquals("old", manager.text("piModels")); assertEquals("old", manager.text("piSettings"))
    }
    @Test fun openCodeLayerChangesRollBackTogetherAndDetectNewHigherPriorityFile() {
        val home = temp.newFolder("home"); val state = temp.newFolder("state"); val manager = files(home, state)
        val keys = dev.agentm.app.config.OpenCodeDocuments.fileKeys
        manager.commit(keys.associateWith { "missing" }, mapOf("opencodeJson" to "old".toByteArray()))
        val revisions = keys.associateWith { ProviderFiles.digest(manager.read(it)) }
        val failing = files(home, state) { if (it == "opencodeJsonc") throw IOException("disk full") }
        assertThrows(IOException::class.java) { failing.commit(revisions, mapOf("opencodeJson" to "new".toByteArray(), "opencodeJsonc" to "new".toByteArray())) }
        assertEquals("old", manager.text("opencodeJson")); assertNull(manager.read("opencodeJsonc"))
        manager.commit(mapOf("opencodeJsonc" to "missing"), mapOf("opencodeJsonc" to "external".toByteArray()))
        assertThrows(IllegalStateException::class.java) { manager.commit(revisions, mapOf("opencodeJson" to "replacement".toByteArray())) }
        assertEquals("old", manager.text("opencodeJson")); assertEquals("external", manager.text("opencodeJsonc"))
    }
    @Test fun codexConfigCatalogAndLibraryRollBackWithoutTouchingOfficialAuth() {
        val home = temp.newFolder("home"); val state = temp.newFolder("state"); val manager = files(home, state)
        val originals = mapOf("codexConfig" to "old-config".toByteArray(), "codexCatalog" to "old-catalog".toByteArray(), "codexAuth" to "native-login".toByteArray(), "library" to "old-library".toByteArray())
        manager.commit(originals.keys.associateWith { "missing" }, originals)
        val failing = files(home, state) { if (it == "codexConfig") throw IOException("full") }
        assertThrows(IOException::class.java) { failing.commit(originals.mapValues { ProviderFiles.digest(it.value) }, linkedMapOf("codexCatalog" to "new".toByteArray(), "codexConfig" to "new".toByteArray(), "library" to "new".toByteArray())) }
        for ((key, bytes) in originals) assertArrayEquals(bytes, manager.read(key))
        val stale = originals.mapValues { ProviderFiles.digest(it.value) }
        manager.commit(mapOf("codexAuth" to stale.getValue("codexAuth")), mapOf("codexAuth" to "rotated-login".toByteArray()))
        assertThrows(IllegalStateException::class.java) { manager.commit(stale, mapOf("codexConfig" to "new".toByteArray())) }
        assertEquals("old-config", manager.text("codexConfig")); assertEquals("rotated-login", manager.text("codexAuth"))
    }
}
