package dev.agentm.app

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

class StoragePathsTest {
    @get:Rule val temp = TemporaryFolder()
    @Test fun browserRejectsTraversalAndAbsolutePaths() {
        val root = temp.newFolder("workspace").canonicalFile
        assertEquals(File(root, "project/src"), StoragePaths.resolve(root, "project/src"))
        for (path in listOf("../home", "/etc", "a/../../home", "a//b", "a\\b", "a/./b", "a\u0000b"))
            assertThrows(IllegalArgumentException::class.java) { StoragePaths.resolve(root, path) }
    }
    @Test fun browserRejectsDirectorySymlinksWhenPlatformSupportsThem() {
        val root = temp.newFolder("workspace").canonicalFile
        val outside = temp.newFolder("home").canonicalFile
        try { Files.createSymbolicLink(File(root, "link").toPath(), outside.toPath()) }
        catch (error: Exception) { org.junit.Assume.assumeNoException(error) }
        assertThrows(IllegalArgumentException::class.java) { StoragePaths.resolve(root, "link") }
        assertThrows(IllegalArgumentException::class.java) { StoragePaths.resolve(root, "link/secret") }
    }
}
