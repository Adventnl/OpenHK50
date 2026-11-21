package org.openhk50.sync

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.net.URI
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal class BackupSettings(context: Context) {
    data class State(val endpoint: String?, val successfulAt: Long, val message: String?)
    data class Config(val endpoint: String, val scope: String, val generation: String, val key: String)

    private val prefs = context.getSharedPreferences("backup", Context.MODE_PRIVATE)

    @Synchronized fun state() = State(
        prefs.getString("endpoint", null), prefs.getLong("success", 0), prefs.getString("message", null)
    )

    @Synchronized fun save(address: String, token: String) {
        val uri = runCatching { URI(address.trim()) }.getOrNull()
        require(uri?.scheme == "https" && !uri.host.isNullOrEmpty() && uri.userInfo == null
            && uri.query == null && uri.fragment == null && uri.path.orEmpty() in listOf("", "/")
            && uri.port in -1..65535 && uri.port != 0) { "Enter an HTTPS server address without a path." }
        val endpoint = uri.toString().trimEnd('/')
        val oldEndpoint = prefs.getString("endpoint", null)
        val same = oldEndpoint == endpoint
        val key = token.trim()
        require(key.matches(KEY) || (same && key.isEmpty() && prefs.contains("secret"))) {
            "Enter the 64-character backup key from your server."
        }
        val secret = if (key.isNotEmpty()) encrypt(key) else prefs.getString("secret", null)
        val scope = if (same) prefs.getString("scope", null) ?: UUID.randomUUID().toString()
            else UUID.randomUUID().toString()
        check(prefs.edit().putString("endpoint", endpoint).putString("secret", secret)
            .putString("generation", UUID.randomUUID().toString()).putString("scope", scope)
            .putLong("success", if (same) prefs.getLong("success", 0) else 0)
            .remove("message").commit())
    }

    @Synchronized fun disable() {
        check(prefs.edit().clear().commit())
    }

    @Synchronized fun read(): Config? {
        val endpoint = prefs.getString("endpoint", null) ?: return null
        val scope = checkNotNull(prefs.getString("scope", null))
        val generation = checkNotNull(prefs.getString("generation", null))
        val secret = checkNotNull(prefs.getString("secret", null))
        return Config(endpoint, scope, generation, decrypt(secret))
    }

    @Synchronized fun current(config: Config) = prefs.getString("generation", null) == config.generation

    @Synchronized fun ifCurrent(config: Config, block: () -> Unit): Boolean {
        if (!current(config)) return false
        block()
        return true
    }

    @Synchronized fun status(config: Config, message: String?, success: Boolean) {
        ifCurrent(config) {
            val edit = prefs.edit().putString("message", message)
            if (success) edit.putLong("success", System.currentTimeMillis())
            edit.apply()
        }
    }

    @Synchronized fun unreadable() {
        if (prefs.contains("endpoint")) prefs.edit().putString("message", "Re-enter the backup key.").apply()
    }

    private fun secretKey(create: Boolean): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        check(create)
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build())
            generateKey()
        }
    }

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey(true))
        return Base64.encodeToString(cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
    }

    private fun decrypt(value: String): String {
        val blob = Base64.decode(value, Base64.NO_WRAP)
        require(blob.size >= 28)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secretKey(false), GCMParameterSpec(128, blob, 0, 12))
        return cipher.doFinal(blob, 12, blob.size - 12).toString(Charsets.UTF_8)
    }

    companion object {
        private const val ALIAS = "org.openhk50.backup.v1"
        private val KEY = Regex("[0-9a-f]{64}")
    }
}
