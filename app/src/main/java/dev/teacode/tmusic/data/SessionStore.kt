package dev.teacode.tmusic.data

import android.content.Context
import dev.teacode.tmusic.domain.Account
import dev.teacode.tmusic.domain.AccountRole

data class SessionTokens(
    val accessToken: String,
    val refreshToken: String,
)

data class AuthSession(
    val accessToken: String,
    val refreshToken: String,
    val user: Account,
)

class SessionStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        SESSION_PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    fun tokens(): SessionTokens? {
        val accessToken = preferences.getString(KEY_ACCESS_TOKEN, null)
        val refreshToken = preferences.getString(KEY_REFRESH_TOKEN, null)

        return if (accessToken.isNullOrBlank() || refreshToken.isNullOrBlank()) {
            null
        } else {
            SessionTokens(
                accessToken = accessToken,
                refreshToken = refreshToken,
            )
        }
    }

    fun account(): Account? {
        val id = preferences.getString(KEY_USER_ID, null)
        val displayName = preferences.getString(KEY_DISPLAY_NAME, null)
        val email = preferences.getString(KEY_EMAIL, null).orEmpty()
        val avatarUrl = preferences.getString(KEY_AVATAR_URL, null)?.takeIf { it.isNotBlank() }
        val canPlayMedia = preferences.getBoolean(KEY_CAN_PLAY_MEDIA, true)
        val role = preferences.getString(KEY_USER_ROLE, null)
            ?.let { storedRole -> runCatching { AccountRole.valueOf(storedRole) }.getOrNull() }
            ?: AccountRole.USER

        return if (id.isNullOrBlank() || displayName.isNullOrBlank()) {
            null
        } else {
            Account(
                id = id,
                displayName = displayName,
                email = email,
                avatarUrl = avatarUrl,
                canPlayMedia = canPlayMedia,
                role = role,
            )
        }
    }

    fun saveSession(session: AuthSession) {
        preferences.edit()
            .putString(KEY_ACCESS_TOKEN, session.accessToken)
            .putString(KEY_REFRESH_TOKEN, session.refreshToken)
            .putString(KEY_USER_ID, session.user.id)
            .putString(KEY_DISPLAY_NAME, session.user.displayName)
            .putString(KEY_EMAIL, session.user.email)
            .putString(KEY_AVATAR_URL, session.user.avatarUrl)
            .putBoolean(KEY_CAN_PLAY_MEDIA, session.user.canPlayMedia)
            .putString(KEY_USER_ROLE, session.user.role.name)
            .apply()
    }

    fun saveTokens(accessToken: String, refreshToken: String) {
        preferences.edit()
            .putString(KEY_ACCESS_TOKEN, accessToken)
            .putString(KEY_REFRESH_TOKEN, refreshToken)
            .apply()
    }

    fun saveAccount(account: Account) {
        preferences.edit()
            .putString(KEY_USER_ID, account.id)
            .putString(KEY_DISPLAY_NAME, account.displayName)
            .putString(KEY_EMAIL, account.email)
            .putString(KEY_AVATAR_URL, account.avatarUrl)
            .putBoolean(KEY_CAN_PLAY_MEDIA, account.canPlayMedia)
            .putString(KEY_USER_ROLE, account.role.name)
            .apply()
    }

    fun clear() {
        preferences.edit().clear().apply()
    }

    private companion object {
        const val SESSION_PREFERENCES_NAME = "tmusic_session"
        const val KEY_ACCESS_TOKEN = "access_token"
        const val KEY_REFRESH_TOKEN = "refresh_token"
        const val KEY_USER_ID = "user_id"
        const val KEY_DISPLAY_NAME = "display_name"
        const val KEY_EMAIL = "email"
        const val KEY_AVATAR_URL = "avatar_url"
        const val KEY_CAN_PLAY_MEDIA = "can_play_media"
        const val KEY_USER_ROLE = "user_role"
    }
}
