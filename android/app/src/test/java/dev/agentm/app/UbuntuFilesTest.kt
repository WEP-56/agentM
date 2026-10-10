package dev.agentm.app

import dev.agentm.app.files.UbuntuFiles
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files

class UbuntuFilesTest {
    @get:Rule val temp = TemporaryFolder()
    private fun appFiles(): File = temp.newFolder("app-files").canonicalFile.also { root ->
        for (path in listOf("linux/ubuntu/opt", "linux-home", "workspaces", "managed")) check(File(root, path).mkdirs())
    }
    private fun link(link: File, target: File) {
        try { Files.createSymbolicLink(link.toPath(), target.toPath()) }
        catch (e: Exception) { org.junit.Assume.assumeNoException(e) }
    }
    @Test fun androidPrivateDirectoryAliasSupportsRootBrowsingPaginationAndFileOperations() {
        val real = appFiles()
        val alias = File(temp.root, "android-data-alias")
        link(alias, real)
        assertNotEquals(alias.toPath(), alias.toPath().toRealPath())
        for (i in 0..100) File(real, "workspaces/item-%03d.txt".format(i)).writeText("item $i")
        val files = UbuntuFiles.fromAppFiles(alias)
        for (root in listOf("/", "/root", "/workspace", "/opt/agentm")) assertEquals(root, files.list(root).getString("path"))
        val first = files.list("/workspace")
        val second = files.list("/workspace", 100)
        assertEquals(100, first.getJSONArray("entries").length()); assertTrue(first.getBoolean("more"))
        assertEquals("item-100.txt", second.getJSONArray("entries").getJSONObject(0).getString("name")); assertFalse(second.getBoolean("more"))
        files.create("/root", "new", true)
        files.importFile("/root/new", "upload.txt", ByteArrayInputStream("copied".toByteArray()))
        assertEquals(1, files.move(listOf("/root/new/upload.txt"), "/workspace", "renamed.txt").getInt("completed"))
        val output = ByteArrayOutputStream(); files.exportFile("/workspace/renamed.txt", output)
        assertEquals("copied", output.toString("UTF-8"))
        assertEquals(2, files.delete(listOf("/root/new", "/workspace/renamed.txt")).getInt("completed"))
        assertEquals("item 100", File(real, "workspaces/item-100.txt").readText())
    }
    @Test fun normalizingTheAppDirectoryNeverTrustsReplacedMountsOrTheirAncestors() {
        val real = appFiles()
        val outside = temp.newFolder("outside").canonicalFile
        File(outside, "keep.txt").writeText("keep")
        check(File(outside, "ubuntu").mkdir())
        val files = UbuntuFiles.fromAppFiles(real)
        val workspace = File(real, "workspaces")
        Files.delete(workspace.toPath()); link(workspace, outside)
        // Reject links already present when constructing a new manager as well as later replacements.
        for (manager in listOf(files, UbuntuFiles.fromAppFiles(real))) {
            assertThrows(IllegalArgumentException::class.java) { manager.list("/workspace") }
            assertThrows(IllegalArgumentException::class.java) { manager.create("/workspace", "escape", false) }
        }
        Files.move(File(real, "linux").toPath(), File(real, "original-linux").toPath())
        link(File(real, "linux"), outside)
        assertThrows(IllegalArgumentException::class.java) { UbuntuFiles.fromAppFiles(real).list("/") }
        assertEquals(setOf("keep.txt", "ubuntu"), outside.list()!!.toSet())
        assertEquals("keep", File(outside, "keep.txt").readText())
    }
    private fun manager(): UbuntuFiles {
        for (name in listOf("rootfs", "home", "workspace", "managed")) File(temp.root, name).mkdir()
        File(temp.root, "rootfs/opt").mkdir()
        return UbuntuFiles(File(temp.root, "rootfs"), File(temp.root, "home"), File(temp.root, "workspace"), File(temp.root, "managed"))
    }
    @Test fun guestTreeUsesTheSameHomeWorkspaceAndManagedMountsAsUbuntu() {
        val files = manager()
        File(temp.root, "home/config").writeText("home")
        File(temp.root, "workspace/project").mkdir()
        File(temp.root, "managed/runtime").mkdir()
        assertEquals(File(temp.root, "home/config").canonicalFile, files.resolve("/root/config").file.canonicalFile)
        val entries = files.list("/").getJSONArray("entries")
        val names = (0 until entries.length()).map { entries.getJSONObject(it).getString("name") }
        assertTrue(names.containsAll(listOf("root", "workspace", "opt")))
        assertEquals("runtime", files.list("/opt/agentm").getJSONArray("entries").getJSONObject(0).getString("name"))
        assertFalse(files.list("/opt/agentm").getBoolean("writable"))
    }
    @Test fun createsRenamesMovesAndDeletesMultipleItemsWithoutChangingOtherFiles() {
        val files = manager()
        files.create("/workspace", "folder", true)
        files.create("/workspace/folder", "项目 'notes' \$(literal).txt", false)
        files.create("/workspace", "keep.txt", false)
        val original = "/workspace/folder/项目 'notes' \$(literal).txt"
        assertEquals(1, files.move(listOf(original), "/workspace/folder", "new.txt").getInt("completed"))
        files.move(listOf("/workspace/folder/new.txt"), "/root")
        assertTrue(File(temp.root, "home/new.txt").isFile)
        val result = files.delete(listOf("/workspace/folder", "/root/new.txt"))
        assertEquals(2, result.getInt("completed")); assertEquals(0, result.getJSONArray("errors").length())
        assertTrue(File(temp.root, "workspace/keep.txt").isFile)
    }
    @Test fun transferPreservesBinaryBytesAndNeverOverwritesAnExistingFile() {
        val files = manager()
        val bytes = ByteArray(300000) { (it % 251).toByte() }
        files.importFile("/workspace", "binary.dat", ByteArrayInputStream(bytes))
        val output = ByteArrayOutputStream(); files.exportFile("/workspace/binary.dat", output)
        assertArrayEquals(bytes, output.toByteArray())
        assertThrows(Exception::class.java) { files.importFile("/workspace", "binary.dat", ByteArrayInputStream(byteArrayOf(0))) }
        assertArrayEquals(bytes, File(temp.root, "workspace/binary.dat").readBytes())
        assertEquals(listOf("binary.dat"), File(temp.root, "workspace").list()!!.toList())
    }
    @Test fun interruptedImportCleansTemporaryFileAndDoesNotPublishPartialContent() {
        val files = manager()
        val broken = object : InputStream() { override fun read(): Int = throw IOException("read failed") }
        assertThrows(IOException::class.java) { files.importFile("/workspace", "broken", broken) }
        assertEquals(0, File(temp.root, "workspace").list()!!.size)
    }
    @Test fun directoryExportProducesPortableZipWithNestedAndEmptyFolders() {
        val files = manager()
        files.create("/workspace", "project", true)
        files.create("/workspace/project", "empty", true)
        files.importFile("/workspace/project", "hello.txt", ByteArrayInputStream("hello".toByteArray()))
        assertEquals("project.zip", files.exportName("/workspace/project"))
        val output = ByteArrayOutputStream(); files.exportFile("/workspace/project", output)
        val content = mutableMapOf<String, String>()
        java.util.zip.ZipInputStream(ByteArrayInputStream(output.toByteArray())).use { zip ->
            while (true) { val entry = zip.nextEntry ?: break; content[entry.name] = zip.readBytes().toString(Charsets.UTF_8) }
        }
        assertEquals(setOf("project/", "project/empty/", "project/hello.txt"), content.keys)
        assertEquals("hello", content["project/hello.txt"])
    }
    @Test fun collisionsAndSelfMovesFailBeforeAnyBatchItemIsMoved() {
        val files = manager()
        files.create("/workspace", "a", false); files.create("/workspace", "b", false)
        files.create("/root", "b", false)
        assertThrows(IllegalArgumentException::class.java) { files.move(listOf("/workspace/a", "/workspace/b"), "/root") }
        assertTrue(File(temp.root, "workspace/a").exists()); assertFalse(File(temp.root, "home/a").exists())
        files.create("/workspace", "folder", true); files.create("/workspace/folder", "nested", true)
        assertThrows(IllegalArgumentException::class.java) { files.move(listOf("/workspace/folder"), "/workspace/folder/nested") }
        assertThrows(Exception::class.java) { files.create("/workspace", "a", true) }
    }
    @Test fun traversalMountDeletionManagedWritesAndVirtualFilesAreRejected() {
        val files = manager()
        for (path in listOf("/workspace/../root", "/workspace//a", "/workspace/./a", "relative", "/workspace/a\\b", "/proc/self", "/dev/null", "/sys", "/.l2s"))
            assertThrows(Exception::class.java) { files.resolve(path) }
        for (path in listOf("/", "/root", "/workspace", "/opt", "/opt/agentm"))
            assertThrows(IllegalArgumentException::class.java) { files.delete(listOf(path)) }
        assertThrows(IllegalArgumentException::class.java) { files.create("/opt/agentm", "bad", false) }
        for (name in listOf("", ".", "..", "bad/name", "bad\\name", "bad\nname", "中".repeat(86)))
            assertThrows(IllegalArgumentException::class.java) { files.create("/workspace", name, false) }
    }
    @Test fun searchIncludesHiddenFilesAndNestedMatchesWithStablePagination() {
        val files = manager()
        files.create("/workspace", "folder", true)
        for (i in 0..100) files.create("/workspace/folder", "item-%03d.txt".format(i), false)
        files.create("/workspace", ".hidden", false)
        val first = files.list("/workspace", 0, "ITEM")
        val second = files.list("/workspace", 100, "ITEM")
        assertEquals(101, first.getInt("total")); assertEquals(100, first.getJSONArray("entries").length()); assertTrue(first.getBoolean("more"))
        assertEquals(1, second.getJSONArray("entries").length()); assertFalse(second.getBoolean("more"))
        assertEquals("/workspace/.hidden", files.list("/workspace", 0, "hidden").getJSONArray("entries").getJSONObject(0).getString("path"))
    }
    @Test fun deletingLinksNeverDeletesTargetsAndSearchDoesNotFollowDirectoryLinks() {
        val files = manager()
        files.create("/workspace", "real", true); files.create("/workspace/real", "keep", false)
        val link = File(temp.root, "workspace/link").toPath()
        try { Files.createSymbolicLink(link, java.nio.file.Paths.get("real")) }
        catch (e: Exception) { org.junit.Assume.assumeNoException(e) }
        assertEquals("/workspace/real", files.directory("/workspace/link").path)
        assertEquals(1, files.list("/workspace", 0, "keep").getInt("total"))
        assertEquals(1, files.delete(listOf("/workspace/link")).getInt("completed"))
        assertTrue(File(temp.root, "workspace/real/keep").exists())
        Files.createSymbolicLink(link, java.nio.file.Paths.get("link"))
        assertThrows(IllegalArgumentException::class.java) { files.resolve("/workspace/link") }
        assertEquals(1, files.delete(listOf("/workspace/link")).getInt("completed"))
    }
}
