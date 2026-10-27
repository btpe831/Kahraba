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
        // The release key. The file is encrypted; its password is only in the SIGNING_PASSWORD
        // secret on GitHub, so only GitHub Actions can sign updates. Every update must use this
        // same key, or phones refuse to install it over the previous version.
        create("release") {
            storeFile = file("release-key.p12")
            storeType = "pkcs12"
            storePassword = System.getenv("SIGNING_PASSWORD")
            keyAlias = "kahraba"
            keyPassword = System.getenv("SIGNING_PASSWORD")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (System.getenv("SIGNING_PASSWORD") != null) signingConfig = signingConfigs.getByName("release")
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
