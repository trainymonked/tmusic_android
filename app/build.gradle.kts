plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

fun String.gradleStringLiteral(): String = "\"" + replace("\\", "\\\\").replace("\"", "\\\"") + "\""

android {
    namespace = "dev.teacode.tmusic"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    buildFeatures {
        resValues = true
    }

    defaultConfig {
        applicationId = "dev.teacode.tmusic"
        minSdk = 28
        targetSdk = 36
        versionCode = 10
        versionName = "1.4.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField(
            "String",
            "API_BASE_URL",
            providers.gradleProperty("API_BASE_URL")
                .orElse("https://tmusic.teacode.dev/api")
                .get()
                .gradleStringLiteral(),
        )
        buildConfigField(
            "String",
            "GOOGLE_SERVER_CLIENT_ID",
            providers.gradleProperty("GOOGLE_SERVER_CLIENT_ID")
                .orElse("...apps.googleusercontent.com")
                .get()
                .gradleStringLiteral(),
        )
        buildConfigField(
            "String",
            "GITHUB_RELEASES_REPOSITORY",
            providers.gradleProperty("GITHUB_RELEASES_REPOSITORY")
                .orElse("trainymonked/tmusic_android")
                .get()
                .gradleStringLiteral(),
        )
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"

            resValue(
                type = "string",
                name = "app_name",
                value = "T-Music Debug"
            )
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        buildConfig = true
        compose = true
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.google.play.services.auth)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
