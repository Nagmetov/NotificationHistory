plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.notificationhistory.simulator"
    compileSdk = 36

    defaultConfig {
        // Тот же пакет, что у WhatsApp, — чтобы проверить путь к медиапапке и логику приложения.
        // Ставить только на эмулятор без настоящего WhatsApp.
        applicationId = "com.whatsapp"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "sim"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
}
