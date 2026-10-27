plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// The version people see (1.1, 1.2...) is set in gradle.properties. GitHub Actions gives every
// build a rising number, used as the internal version code so each APK installs over the last.
val buildNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
val appVersion = providers.gradleProperty("appVersion").get()

android {
    namespace = "com.kahraba.power"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.kahraba.power"
        minSdk = 26
        targetSdk = 34
        versionCode = buildNumber
        versionName = appVersion
    }

    signingConfigs {
        // A fixed key kept in the repo so updates install over the previous version.
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.preference:preference-ktx:1.2.1")
    implementation("com.google.android.material:material:1.12.0")
    testImplementation("junit:junit:4.13.2")
}
