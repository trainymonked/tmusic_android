package dev.teacode.tmusic.ui

import dev.teacode.tmusic.data.TMusicApiException
import kotlinx.coroutines.CancellationException

internal fun AppDestination.isHomeOverview(): Boolean {
    return tab == AppTab.Home && homeRoute == HomeRoute.Overview
}

internal fun Throwable.userMessage(): String {
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
    return this !is TMusicApiException || statusCode == null || statusCode >= 500
}
