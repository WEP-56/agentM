package dev.agentm.app

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.nio.file.Files

class WorkingDirectoriesTest {
    @get:Rule val temp = TemporaryFolder()
    private var saved: String? = null
    private var writes = 0
    private fun directories(workspace: File, home: File, fail: Boolean = false) = WorkingDirectories(workspace, home, { saved }, {
        if (fail) throw IOException("fixture write failure")
        saved = it; writes++
    })
    @Test fun selectionPersistsAndMapsGuestDirectoryToTheActualProjectFolder() {
        val workspace = temp.newFolder("workspace").canonicalFile; val home = temp.newFolder("home").canonicalFile
        val name = "项目 'draft' \$(literal);test"
        val project = File(workspace, name).apply { mkdir() }
        val store = directories(workspace, home)
        assertEquals("/workspace", store.current().path)
        store.select("/workspace/$name")
        assertEquals(1, writes)
        val restored = directories(workspace, home).current()
        assertEquals(project, restored.host); assertEquals("/workspace/$name", restored.path)
        val homeProject = File(home, "src").apply { mkdir() }
        store.select("/root/src")
        assertEquals(homeProject, store.current().host)
    }
    @Test fun directoryBrowsingAndCreatingDoNotChangeTheSelectedDirectory() {
        val workspace = temp.newFolder("workspace").canonicalFile; val home = temp.newFolder("home").canonicalFile
        val store = directories(workspace, home)
        File(workspace, "repo").mkdir(); File(workspace, "file.txt").writeText("keep")
        val listing = store.list("/workspace")
        assertEquals(1, listing.getJSONArray("entries").length()); assertEquals("repo", listing.getJSONArray("entries").getString(0))
        store.list("/workspace/repo")
        assertEquals("/root/new repo", store.create("/root", "new repo").getString("path"))
        assertEquals(0, writes); assertEquals("/workspace", store.current().path)
        assertEquals("keep", File(workspace, "file.txt").readText())
    }
    @Test fun missingSelectionIsNotSilentlyReplacedWithWorkspace() {
        val workspace = temp.newFolder("workspace").canonicalFile; val home = temp.newFolder("home").canonicalFile
        val project = File(workspace, "repo").apply { mkdir() }; val store = directories(workspace, home)
        store.select("/workspace/repo"); assertTrue(project.delete())
        assertEquals("/workspace/repo", store.snapshot().getString("path")); assertFalse(store.snapshot().getBoolean("available"))
        assertThrows(IllegalArgumentException::class.java) { store.current() }
        assertFalse(project.exists()); assertEquals("/workspace/repo", saved)
        store.select("/workspace"); assertTrue(store.snapshot().getBoolean("available"))
    }
    @Test fun traversalOtherMountsFilesAndInvalidCreationCannotBeSelected() {
        val workspace = temp.newFolder("workspace").canonicalFile; val home = temp.newFolder("home").canonicalFile
        val store = directories(workspace, home); File(workspace, "file").writeText("keep")
        for (path in listOf("/etc", "/workspace-other", "/root/../workspace", "/workspace/../home", "/workspace//repo", "/workspace/./repo", "/workspace/", "/workspace/a\\b", "/workspace/a\nfile", "/workspace/file"))
            assertThrows(Exception::class.java) { store.select(path) }
        for (name in listOf("", ".", "..", "../escape", "a/b", "a\\b", "bad\u0000", "bad\n", "中".repeat(86), "file"))
            assertThrows(Exception::class.java) { store.create("/workspace", name) }
        assertEquals(0, writes); assertEquals("keep", File(workspace, "file").readText())
    }
    @Test fun linkedDirectoriesCannotBeBrowsedOrSelected() {
        val workspace = temp.newFolder("workspace").canonicalFile; val home = temp.newFolder("home").canonicalFile
        val outside = temp.newFolder("outside").canonicalFile
        try { Files.createSymbolicLink(File(workspace, "linked").toPath(), outside.toPath()) }
        catch (error: Exception) { org.junit.Assume.assumeNoException(error) }
        val store = directories(workspace, home)
        assertEquals(0, store.list("/workspace").getJSONArray("entries").length())
        assertThrows(IllegalArgumentException::class.java) { store.select("/workspace/linked") }
        assertThrows(IllegalArgumentException::class.java) { store.create("/workspace/linked", "nested") }
        assertFalse(File(outside, "nested").exists())
        assertThrows(IllegalArgumentException::class.java) { directories(File(workspace, "linked"), home).select("/workspace") }
    }
    @Test fun listingIsSortedAndPagedAndFailedPersistenceIsReported() {
        val workspace = temp.newFolder("workspace").canonicalFile; val home = temp.newFolder("home").canonicalFile
        for (i in 59 downTo 0) File(workspace, "repo-%02d".format(i)).mkdir()
        val store = directories(workspace, home)
        val first = store.list("/workspace"); val second = store.list("/workspace", 50)
        assertEquals(50, first.getJSONArray("entries").length()); assertEquals("repo-00", first.getJSONArray("entries").getString(0)); assertTrue(first.getBoolean("more"))
        assertEquals(10, second.getJSONArray("entries").length()); assertEquals("repo-50", second.getJSONArray("entries").getString(0)); assertFalse(second.getBoolean("more"))
        assertThrows(IllegalArgumentException::class.java) { store.list("/workspace", -1) }
        assertThrows(IOException::class.java) { directories(workspace, home, true).select("/workspace/repo-00") }
        assertNull(saved)
    }
}
