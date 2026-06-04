package dev.teacode.tmusic.data

import android.content.Context

class LastFmAuthTokenStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    fun token(): String? {
        return preferences.getString(KEY_TOKEN, null)?.takeIf { it.isNotBlank() }
    }

    fun saveToken(token: String) {
        preferences.edit()
            .putString(KEY_TOKEN, token)
            .apply()
    }

    fun clear() {
        preferences.edit().clear().apply()
    }

    private companion object {
        const val PREFERENCES_NAME = "tmusic_lastfm_auth_token"
        const val KEY_TOKEN = "token"
    }
}
