package com.badee.phoneagent.security

import android.content.Context
import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom

class AuthTokenStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun getOrCreate(): String {
        val existing = preferences.getString(KEY_TOKEN, null)
        if (!existing.isNullOrBlank()) return existing
        return rotate()
    }

    fun rotate(): String {
        val bytes = ByteArray(TOKEN_BYTES)
        SecureRandom().nextBytes(bytes)
        val token = Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        preferences.edit().putString(KEY_TOKEN, token).apply()
        return token
    }

    fun matches(candidate: String): Boolean {
        val expectedBytes = getOrCreate().toByteArray(Charsets.UTF_8)
        val candidateBytes = candidate.toByteArray(Charsets.UTF_8)
        return MessageDigest.isEqual(expectedBytes, candidateBytes)
    }

    private companion object {
        const val PREFERENCES_NAME = "agent_security"
        const val KEY_TOKEN = "pairing_token"
        const val TOKEN_BYTES = 32
    }
}
