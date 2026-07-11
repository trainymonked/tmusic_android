package dev.teacode.tmusic.ui

import dev.teacode.tmusic.data.TMusicApiException
import java.net.HttpURLConnection
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import kotlinx.coroutines.CancellationException

internal fun AppDestination.isHomeOverview(): Boolean {
    return tab == AppTab.Home && homeRoute == HomeRoute.Overview
}

internal fun Throwable.userMessage(): String {
    if (isServerConnectionFailure()) {
        return "Can't connect to the server. Check your connection and try again."
    }
    return message?.takeIf { it.isNotBlank() } ?: "Unexpected error"
}

internal fun Throwable.causeChainMessage(): String {
    return generateSequence(this as Throwable?) { it.cause }
        .take(6)
        .map { error ->
            val name = error::class.java.simpleName
            val message = error.message?.takeIf { it.isNotBlank() }
                ?: error.localizedMessage?.takeIf { it.isNotBlank() }
            if (message == null) name else "$name: $message"
        }
        .joinToString(" <- ")
}

internal fun Throwable.isServerAvailabilityFailure(): Boolean {
    if (this is CancellationException) {
        return false
    }
    if (isAppUpdateRequiredError()) {
        return false
    }
    return isServerConnectionFailure()
}

private fun Throwable.isServerConnectionFailure(): Boolean {
    return serverConnectionFailure() != null
}

private fun Throwable.serverConnectionFailure(): Throwable? {
    return generateSequence(this as Throwable?) { it.cause }
        .take(6)
        .firstOrNull { error ->
            error is SocketTimeoutException ||
                error is UnknownHostException ||
                error is SocketException ||
                error is SSLException
        }
}

internal fun Throwable.isDeletedAccountError(): Boolean {
    return this is TMusicApiException &&
        statusCode == HttpURLConnection.HTTP_NOT_FOUND &&
        userMessage().contains("user", ignoreCase = true) &&
        userMessage().contains("not found", ignoreCase = true)
}

internal fun Throwable.isUnauthorizedError(): Boolean {
    return this is TMusicApiException &&
        statusCode == HttpURLConnection.HTTP_UNAUTHORIZED
}

internal fun Throwable.unauthorizedSessionMessage(): String {
    val message = userMessage()
    return if (message.isBlank() || message == "Request failed with HTTP ${HttpURLConnection.HTTP_UNAUTHORIZED}.") {
        "Session expired. Sign in again."
    } else {
        "Session expired. $message"
    }
}

internal fun Throwable.isMediaPlaybackDisabledError(): Boolean {
    return this is TMusicApiException &&
        statusCode == HttpURLConnection.HTTP_FORBIDDEN &&
        userMessage().contains("Media playback is disabled", ignoreCase = true)
}

internal fun Throwable.isAppUpdateRequiredError(): Boolean {
    return this is TMusicApiException &&
        (statusCode == 426 || code.equals("APP_UPDATE_REQUIRED", ignoreCase = true))
}
