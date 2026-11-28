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

/** AES-GCM encrypted access/refresh token storage backed by Android Keystore. */
class KeystoreTokenStore(context: Context) : TokenStore {
    private val prefs = context.applicationContext.getSharedPreferences("evolute_vehicle_session", Context.MODE_PRIVATE)
    private val alias = "evolute_telemetry_pro_aes_key"

    private fun key(create: Boolean): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore"); ks.load(null)
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        if (!create) throw IOException("Ключ авторизации отсутствует")
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true).build())
        return gen.generateKey()
    }

    @Synchronized override fun get(): ApiClient.Tokens? {
        val ivs = prefs.getString("iv", null); val ds = prefs.getString("data", null)
        if (ivs == null && ds == null) return null
        if (ivs.isNullOrBlank() || ds.isNullOrBlank()) throw IOException("Хранилище авторизации повреждено")
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(false), GCMParameterSpec(128, Base64.decode(ivs, Base64.NO_WRAP)))
            val j = JSONObject(String(cipher.doFinal(Base64.decode(ds, Base64.NO_WRAP)), Charsets.UTF_8))
            ApiClient.Tokens(j.getString("access"), j.getString("refresh"),
                j.optString("userId").takeIf { it.isNotBlank() },
                j.optString("userToken").takeIf { it.isNotBlank() },
                j.optString("widgetId").takeIf { it.isNotBlank() })
        } catch (e: Exception) { throw IOException("Не удалось прочитать защищённую сессию", e) }
    }

    @Synchronized override fun save(tokens: ApiClient.Tokens) {
        val j = JSONObject().put("access", tokens.access).put("refresh", tokens.refresh)
            .put("userId", tokens.userId).put("userToken", tokens.userToken).put("widgetId", tokens.widgetId)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE, key(true))
        val data = Base64.encodeToString(cipher.doFinal(j.toString().toByteArray()), Base64.NO_WRAP)
        if (!prefs.edit().putString("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
                .putString("data", data).commit()) throw IOException("Не удалось сохранить сессию")
    }

    @Synchronized override fun clear() {
        prefs.edit().clear().commit()
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(alias)
    }
}
