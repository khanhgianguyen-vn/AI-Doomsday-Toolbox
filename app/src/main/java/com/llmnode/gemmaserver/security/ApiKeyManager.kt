package com.llmnode.gemmaserver.security

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.SecureRandom

object ApiKeyManager {
    private const val PREF_FILE = "gemma_secure_prefs"
    private const val KEY_API = "api_key"
    private const val KEY_LENGTH = 32
    private const val CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"

    private fun getPrefs(context: Context) =
        EncryptedSharedPreferences.create(
            context,
            PREF_FILE,
            MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )

    fun getOrCreateApiKey(context: Context): String {
        val prefs = getPrefs(context)
        val existing = prefs.getString(KEY_API, null)
        if (!existing.isNullOrEmpty()) return existing

        val random = SecureRandom()
        val key = buildString {
            append("gsk-")
            repeat(KEY_LENGTH) {
                append(CHARS[random.nextInt(CHARS.length)])
            }
        }
        prefs.edit().putString(KEY_API, key).apply()
        return key
    }

    fun validateKey(context: Context, provided: String): Boolean {
        val stored = getOrCreateApiKey(context)
        return provided == stored
    }
}
