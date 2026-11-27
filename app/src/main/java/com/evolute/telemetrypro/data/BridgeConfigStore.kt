package com.evolute.telemetrypro.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.io.IOException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Encrypted storage for the local MCP bridge secret. */
class BridgeConfigStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("evolute_mcp_bridge", Context.MODE_PRIVATE)
    private val alias = "evolute_mcp_bridge_aes_key"

    private fun key(create: Boolean): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        if (!create) throw IOException("Ключ MCP bridge отсутствует")
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true).build())
        return generator.generateKey()
    }

    @Synchronized fun get(): String? {
        val iv = prefs.getString("iv", null) ?: return null
        val data = prefs.getString("data", null) ?: throw IOException("Настройки MCP bridge повреждены")
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(false), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
            JSONObject(String(cipher.doFinal(Base64.decode(data, Base64.NO_WRAP)), Charsets.UTF_8))
                .getString("token").takeIf { it.length >= 32 }
                ?: throw IOException("Некорректный ключ MCP bridge")
        } catch (e: Exception) { throw IOException("Не удалось прочитать ключ MCP bridge", e) }
    }

    @Synchronized fun save(token: String) {
        require(token.length >= 32) { "Ключ bridge должен содержать не менее 32 символов" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(true))
        val encrypted = Base64.encodeToString(
            cipher.doFinal(JSONObject().put("token", token).toString().toByteArray(Charsets.UTF_8)),
            Base64.NO_WRAP
        )
        if (!prefs.edit().putString("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
                .putString("data", encrypted).commit()) throw IOException("Не удалось сохранить ключ MCP bridge")
    }

    @Synchronized fun clear() {
        prefs.edit().clear().commit()
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(alias)
    }
}
