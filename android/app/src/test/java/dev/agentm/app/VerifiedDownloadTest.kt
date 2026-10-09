package dev.agentm.app

import dev.agentm.app.packages.VerifiedDownload
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Base64

class VerifiedDownloadTest {
    @get:Rule val temp = TemporaryFolder()
    private val original = "verified native archive".toByteArray()
    private fun recipe() = JSONObject().put("recipe", "native-patch-v1").put("url", "https://registry.npmjs.org/package.tgz")
        .put("integrity", "sha512-" + Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-512").digest(original)))
    private fun response(url: URL, bytes: ByteArray, code: Int = 200) = object : HttpURLConnection(url) {
        override fun connect() {}
        override fun disconnect() {}
        override fun usingProxy() = false
        override fun getResponseCode() = code
        override fun getContentLengthLong() = bytes.size.toLong()
        override fun getInputStream() = bytes.inputStream()
    }
    @Test fun verifiedArchiveGetsLocalSha256ForSlotProvenance() {
        val asset = recipe()
        val file = VerifiedDownload(temp.root) { response(it, original) }.fetch(asset) { _, _ -> }
        assertArrayEquals(original, file.readBytes())
        assertEquals(original.size.toLong(), asset.getLong("bytes"))
        assertEquals(MessageDigest.getInstance("SHA-256").digest(original).joinToString("") { "%02x".format(it) }, asset.getString("sha256"))
    }
    @Test fun corruptionCannotPublishDigestOrDeleteExistingCache() {
        val old = temp.newFile("old.tar.gz").apply { writeText("known working archive") }
        val asset = recipe()
        assertThrows(IllegalStateException::class.java) { VerifiedDownload(temp.root) { response(it, "tampered".toByteArray()) }.fetch(asset) { _, _ -> } }
        assertFalse(asset.has("sha256")); assertEquals("known working archive", old.readText())
        assertEquals(listOf("old.tar.gz"), temp.root.list()!!.toList())
    }
    @Test fun redirectResponseIsRejectedAndPartialFileRemoved() {
        assertThrows(java.io.IOException::class.java) { VerifiedDownload(temp.root) { response(it, original, 302) }.fetch(recipe()) { _, _ -> } }
        assertTrue(temp.root.list()!!.isEmpty())
    }
}
