package com.example.replyassist

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Stores the Claude API key using Android Keystore-backed encryption
 * so it never sits in plaintext SharedPreferences.
 */
object SecurePrefs {

    private const val PREFS_NAME = "reply_assist_secure_prefs"
    private const val KEY_API_KEY = "claude_api_key"

    private fun prefs(context: Context): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        return EncryptedSharedPreferences.create(
            context,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    fun saveApiKey(context: Context, key: String) {
        prefs(context).edit().putString(KEY_API_KEY, key).apply()
    }

    fun getApiKey(context: Context): String? {
        return prefs(context).getString(KEY_API_KEY, null)
    }
}
