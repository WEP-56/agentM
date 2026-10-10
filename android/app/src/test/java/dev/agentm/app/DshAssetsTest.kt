package dev.agentm.app

import dev.agentm.app.packages.DshPackage
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class DshAssetsTest {
    private val assets = File("src/main/assets")
    private val manifest = File(assets, "dsh-overlays/manifest.json")
    private fun overlays() = JSONObject(manifest.readText()).getJSONArray("files")

    @Test fun bundledOverlaysMatchInstallerHashes() {
        val files = overlays()
        assertTrue("DSH overlay manifest must not be empty", files.length() > 0)
        for (index in 0 until files.length()) {
            val patch = files.getJSONObject(index)
            val name = patch.getString("asset")
            // Hash raw bytes, exactly as the installer does; text reads hide CRLF drift.
            assertEquals(name, patch.getString("sha256"), DshPackage.digest(File(assets, name).readBytes()))
        }
    }

    @Test fun overlayAndRecipeBytesUseLfAcrossCheckouts() {
        val files = overlays()
        val paths = (0 until files.length()).map { File(assets, files.getJSONObject(it).getString("asset")) } + manifest
        // The installer also hashes manifest.json itself for the installed recipe stamp.
        for (file in paths) {
            assertFalse("${file.name} contains CR bytes; regenerate with LF line endings", file.readBytes().contains(13.toByte()))
        }
    }
}
