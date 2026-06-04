package dev.teacode.tmusic.data

import dev.teacode.tmusic.BuildConfig

object AppConfig {
    val API_BASE_URL: String = BuildConfig.API_BASE_URL.trimEnd('/')
    const val LOCAL_API_BASE_URL: String = "http://192.168.0.101:6003/api"
    const val GOOGLE_SERVER_CLIENT_ID: String = BuildConfig.GOOGLE_SERVER_CLIENT_ID

    fun apiBaseUrl(useLocalBackend: Boolean): String {
        return if (useLocalBackend) LOCAL_API_BASE_URL else API_BASE_URL
    }
}
