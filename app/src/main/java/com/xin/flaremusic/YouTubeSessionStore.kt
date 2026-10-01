package com.xin.flaremusic

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Stores the YouTube session encrypted with an Android Keystore AES-GCM key. */
object YouTubeSessionStore {
    private const val PREFS = "flare_youtube_session"
    private const val VALUE = "encrypted_cookie"
    private const val KEY_ALIAS = "flare_youtube_session_key"

    fun save(context: android.content.Context, cookieHeader: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val encrypted = cipher.doFinal(cookieHeader.toByteArray(Charsets.UTF_8))
        val packed = cipher.iv + encrypted
        context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
            .edit().putString(VALUE, Base64.encodeToString(packed, Base64.NO_WRAP)).apply()
    }

    fun read(context: android.content.Context): String? = try {
        val encoded = context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE).getString(VALUE, null) ?: return null
        val packed = Base64.decode(encoded, Base64.NO_WRAP)
        val iv = packed.copyOfRange(0, 12)
        val ciphertext = packed.copyOfRange(12, packed.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
        String(cipher.doFinal(ciphertext), Charsets.UTF_8)
    } catch (_: Exception) { null }

    fun clear(context: android.content.Context) {
        context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE).edit().remove(VALUE).apply()
    }

    private fun secretKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)
            .build())
        return generator.generateKey()
    }
}
