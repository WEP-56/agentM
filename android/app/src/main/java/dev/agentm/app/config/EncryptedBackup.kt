package dev.agentm.app.config

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Backups contain entire native settings files, including fields this adapter does not manage. */
class EncryptedBackup(
    private val alias: String = "dev.agentm.claude-config-backup.v1",
    private val associatedData: String = "agentM/claude/settings.json/backup-v1",
) {
    private fun key(): SecretKey = synchronized(KEY_LOCK) {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    fun encrypt(bytes: ByteArray): JSONObject {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()); updateAAD(associatedData.toByteArray()) }
        val encrypted = cipher.doFinal(bytes)
        return JSONObject().put("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .put("ciphertext", Base64.encodeToString(encrypted, Base64.NO_WRAP))
    }
    fun decrypt(json: JSONObject): ByteArray {
        val iv = Base64.decode(json.getString("iv"), Base64.NO_WRAP)
        val ciphertext = Base64.decode(json.getString("ciphertext"), Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv)); updateAAD(associatedData.toByteArray()) }
        return cipher.doFinal(ciphertext)
    }
    companion object {
        private val KEY_LOCK = Any()
    }
}
