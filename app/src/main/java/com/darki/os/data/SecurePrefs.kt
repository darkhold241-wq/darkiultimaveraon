package com.darki.os.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Guarda la llave de la API de Anthropic cifrada en el dispositivo.
 * Nunca se hardcodea la llave en el codigo: cada usuario pone la suya
 * desde la pantalla principal.
 */
object SecurePrefs {
    private const val FILE_NAME = "darki_secure_prefs"
    private const val KEY_API = "anthropic_api_key"
    private const val KEY_VOICEPRINT = "owner_voiceprint"

    private fun prefs(context: Context): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        return EncryptedSharedPreferences.create(
            context,
            FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    fun saveApiKey(context: Context, key: String) {
        prefs(context).edit().putString(KEY_API, key.trim()).apply()
    }

    fun getApiKey(context: Context): String? {
        return prefs(context).getString(KEY_API, null)?.takeIf { it.isNotBlank() }
    }

    /**
     * Guarda la huella de voz del dueno (ver VoiceprintExtractor), cifrada
     * igual que la API key. Se guarda como texto separado por comas
     * porque EncryptedSharedPreferences solo maneja tipos simples.
     */
    fun saveVoiceprint(context: Context, embedding: FloatArray) {
        val serialized = embedding.joinToString(",")
        prefs(context).edit().putString(KEY_VOICEPRINT, serialized).apply()
    }

    fun getVoiceprint(context: Context): FloatArray? {
        val raw = prefs(context).getString(KEY_VOICEPRINT, null) ?: return null
        return try {
            raw.split(",").map { it.toFloat() }.toFloatArray()
        } catch (e: Exception) {
            null
        }
    }

    fun clearVoiceprint(context: Context) {
        prefs(context).edit().remove(KEY_VOICEPRINT).apply()
    }
}
