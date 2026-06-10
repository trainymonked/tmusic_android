@file:Suppress("DEPRECATION")

package dev.teacode.tmusic.auth

import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.CommonStatusCodes

class GoogleSignInTokenProvider(
    context: Context,
    private val serverClientId: String,
) {
    private val client = GoogleSignIn.getClient(
        context.applicationContext,
        GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestIdToken(serverClientId)
            .requestEmail()
            .build(),
    )

    fun signInIntent(): Intent {
        return client.signInIntent
    }

    fun idTokenFromIntent(data: Intent?): Result<String> {
        return runCatching {
            val account = try {
                GoogleSignIn.getSignedInAccountFromIntent(data)
                    .getResult(ApiException::class.java)
            } catch (error: ApiException) {
                throw error.asUserFacingException()
            }
            account.idToken
                ?: throw IllegalStateException("Google Sign-In did not return an idToken.")
        }
    }

    fun signOut() {
        client.signOut()
    }
}

private fun ApiException.asUserFacingException(): IllegalStateException {
    val message = when (statusCode) {
        CommonStatusCodes.DEVELOPER_ERROR -> {
            "Google Sign-In configuration mismatch. Register this app package and signing certificate in Google Cloud."
        }

        CommonStatusCodes.CANCELED -> "Google Sign-In was cancelled."
        CommonStatusCodes.NETWORK_ERROR -> "Google Sign-In could not connect to Google."
        else -> "Google Sign-In failed (${statusCode})."
    }
    return IllegalStateException(message, this)
}
