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

/** AES-GCM encrypted session; the AES key never leaves Android Keystore. */
class KeystoreTokenStore(context: Context) : TokenStore {
    private val prefs = context.applicationContext.getSharedPreferences("evolute_vehicle_session", Context.MODE_PRIVATE)
    // Migrate sessions created by the newer Kotlin-only build into the build 273 namespace.
    private val previousPrefs = context.applicationContext.getSharedPreferences("telemetry_pro_session", Context.MODE_PRIVATE)
    private val alias = "evolute_telemetry_pro_aes_key"

    private fun key(create: Boolean): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        if (!create) throw IOException("Ключ сессии отсутствует")
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true).build())
        return generator.generateKey()
    }

    @Synchronized override fun get(): ApiClient.Tokens? {
        var iv = prefs.getString("iv", null)
        var data = prefs.getString("data", null)
        if (iv == null && data == null) {
            // Both versions use the same Keystore alias, so the encrypted payload can be copied unchanged.
            val oldIv = previousPrefs.getString("iv", null)
            val oldData = previousPrefs.getString("data", null)
            if (oldIv != null && oldData != null) {
                if (!prefs.edit().putString("iv", oldIv).putString("data", oldData).commit()) {
                    throw IOException("Не удалось перенести сохранённую сессию")
                }
                iv = oldIv
                data = oldData
            }
        }
        if (iv == null && data == null) return null
        if (iv.isNullOrBlank() || data.isNullOrBlank()) throw IOException("Хранилище сессии повреждено")
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(false), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
            val json = JSONObject(String(cipher.doFinal(Base64.decode(data, Base64.NO_WRAP)), Charsets.UTF_8))
            ApiClient.Tokens(json.getString("access"), json.getString("refresh"),
                json.optString("userId").takeIf { it.isNotBlank() }, json.optString("userToken").takeIf { it.isNotBlank() },
                json.optString("widgetId").takeIf { it.isNotBlank() })
        } catch (e: Exception) { throw IOException("Не удалось прочитать защищённую сессию", e) }
    }

    @Synchronized override fun save(tokens: ApiClient.Tokens) {
        val json = JSONObject().put("access", tokens.access).put("refresh", tokens.refresh)
            .put("userId", tokens.userId).put("userToken", tokens.userToken).put("widgetId", tokens.widgetId)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(true))
        val data = Base64.encodeToString(cipher.doFinal(json.toString().toByteArray()), Base64.NO_WRAP)
        val ok = prefs.edit().putString("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .putString("data", data).commit()
        if (!ok) throw IOException("Не удалось сохранить сессию")
    }

    @Synchronized override fun clear() {
        prefs.edit().clear().commit()
        previousPrefs.edit().clear().commit()
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        ks.deleteEntry(alias)
    }
}
