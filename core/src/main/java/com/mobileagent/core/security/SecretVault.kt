package com.mobileagent.core.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.mobileagent.core.model.JsonCodec
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Stores API keys and MCP bearer tokens encrypted with an AES/GCM key that lives in the
 * Android Keystore. Only ciphertext ever reaches disk; plaintext never goes into the database,
 * logs or checkpoints.
 */
class SecretVault(context: Context) {

    private val storeFile = File(context.filesDir, "secret-vault.json")
    private val lock = Any()
    private val keyStore: KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    init {
        ensureKey()
    }

    fun put(secretId: String, plaintext: String) = synchronized(lock) {
        val entries = readEntries().toMutableMap()
        entries[secretId] = encrypt(plaintext)
        writeEntries(entries)
    }

    fun get(secretId: String): String? = synchronized(lock) {
        val value = readEntries()[secretId] ?: return null
        runCatching { decrypt(value) }.getOrNull()
    }

    fun has(secretId: String): Boolean = get(secretId) != null

    fun delete(secretId: String) = synchronized(lock) {
        val entries = readEntries().toMutableMap()
        entries.remove(secretId)
        writeEntries(entries)
    }

    fun listIds(): Set<String> = synchronized(lock) { readEntries().keys.toSet() }

    private fun ensureKey() {
        if (keyStore.containsAlias(KEY_ALIAS)) return
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        generator.init(spec)
        generator.generateKey()
    }

    private fun secretKey(): SecretKey = (keyStore.getEntry(KEY_ALIAS, null) as KeyStore.SecretKeyEntry).secretKey

    private fun encrypt(plaintext: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val iv = cipher.iv
        val encrypted = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        val combined = ByteArray(iv.size + encrypted.size)
        System.arraycopy(iv, 0, combined, 0, iv.size)
        System.arraycopy(encrypted, 0, combined, iv.size, encrypted.size)
        return Base64.encodeToString(combined, Base64.NO_WRAP)
    }

    private fun decrypt(encoded: String): String {
        val combined = Base64.decode(encoded, Base64.NO_WRAP)
        val iv = combined.copyOfRange(0, IV_SIZE)
        val payload = combined.copyOfRange(IV_SIZE, combined.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(TAG_BITS, iv))
        return String(cipher.doFinal(payload), Charsets.UTF_8)
    }

    private fun readEntries(): Map<String, String> {
        if (!storeFile.exists()) return emptyMap()
        return runCatching {
            JsonCodec.json.parseToJsonElement(storeFile.readText(Charsets.UTF_8)).let { element ->
                element as? JsonObject ?: return emptyMap()
                element.mapValues { (_, value) -> (value as? JsonPrimitive)?.content ?: "" }
            }
        }.getOrElse { emptyMap() }
    }

    private fun writeEntries(entries: Map<String, String>) {
        val json = JsonObject(entries.mapValues { JsonPrimitive(it.value) })
        val tmp = File(storeFile.parentFile, "${storeFile.name}.tmp")
        tmp.writeText(JsonCodec.json.encodeToString(JsonObject.serializer(), json), Charsets.UTF_8)
        if (!tmp.renameTo(storeFile)) {
            storeFile.writeText(JsonCodec.json.encodeToString(JsonObject.serializer(), json), Charsets.UTF_8)
            tmp.delete()
        }
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "mobile_agent_master_key"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_SIZE = 12
        const val TAG_BITS = 128
    }
}
