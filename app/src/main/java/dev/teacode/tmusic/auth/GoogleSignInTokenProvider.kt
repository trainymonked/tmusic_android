@file:Suppress("DEPRECATION")

package dev.teacode.tmusic.auth

import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException

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
            val account = GoogleSignIn.getSignedInAccountFromIntent(data)
                .getResult(ApiException::class.java)
            account.idToken
                ?: throw IllegalStateException("Google Sign-In did not return an idToken.")
        }
    }

    fun signOut() {
        client.signOut()
    }
}
