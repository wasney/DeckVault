plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.deckvault.offline"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.deckvault.offline"
        minSdk = 26
        targetSdk = 35
        versionCode = 12
        versionName = "1.1.1"
    }

    signingConfigs {
        getByName("debug") {
            storeFile = file("deckvault.jks")
            storePassword = "deckvault123"
            keyAlias = "deckvault"
            keyPassword = "deckvault123"
        }
        create("release") {
            storeFile = file("deckvault.jks")
            storePassword = "deckvault123"
            keyAlias = "deckvault"
            keyPassword = "deckvault123"
            enableV1Signing = true
            enableV2Signing = true
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
}

extensions.configure<org.gradle.api.plugins.BasePluginExtension> {
    archivesName.set("DeckVault-v${android.defaultConfig.versionName}")
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jsoup:jsoup:1.18.3")

    // Hardware-accelerated offline video compression
    implementation("androidx.media3:media3-transformer:1.5.1")
    implementation("androidx.media3:media3-effect:1.5.1")
    implementation("androidx.media3:media3-common:1.5.1")
}
