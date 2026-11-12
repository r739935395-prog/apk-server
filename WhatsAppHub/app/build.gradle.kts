plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.rafat.whatshub"
    compileSdk = 35
    ndkVersion = "27.2.12479018"

    defaultConfig {
        applicationId = "com.rafat.whatshub"
        minSdk = 28
        targetSdk = 35
        versionCode = 2
        versionName = "1.1.0"

        ndk {
            abiFilters += listOf("arm64-v8a")
        }

        resourceConfigurations += listOf("en", "ar")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    val keystorePath = System.getenv("RAFAT_HUB_KEYSTORE_PATH")?.trim().orEmpty()
    val keystorePassword = System.getenv("RAFAT_HUB_KEYSTORE_PASSWORD")?.trim().orEmpty()
    val keyAliasName = System.getenv("RAFAT_HUB_KEY_ALIAS")?.trim().orEmpty()
    val keyPasswordValue = System.getenv("RAFAT_HUB_KEY_PASSWORD")?.trim().orEmpty()
    val hasReleaseKeystore = keystorePath.isNotEmpty() &&
        file(keystorePath).exists() &&
        keystorePassword.isNotEmpty() &&
        keyAliasName.isNotEmpty() &&
        keyPasswordValue.isNotEmpty()

    signingConfigs {
        if (hasReleaseKeystore) {
            create("rafatRelease") {
                storeFile = file(keystorePath)
                storePassword = keystorePassword
                keyAlias = keyAliasName
                keyPassword = keyPasswordValue
                enableV1Signing = false
                enableV2Signing = true
                enableV3Signing = true
                enableV4Signing = false
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            // Never fall back to ephemeral debug keys for release builds!
            signingConfig = if (hasReleaseKeystore) signingConfigs.getByName("rafatRelease") else null
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
        compose = true
        buildConfig = true
    }

    packaging {
        jniLibs {
            // Uncompressed 16 KB page-aligned ELF shared libraries for Android 15 (SM-S918B / Snapdragon 8 Gen 2)
            useLegacyPackaging = false
        }
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "/META-INF/LICENSE*",
                "/META-INF/NOTICE*"
            )
        }
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)
    testImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.core:core-splashscreen:1.0.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-service:2.8.7")
    implementation("androidx.navigation:navigation-compose:2.8.4")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    // SQLCipher 4.6.1 (16 KB page-size aligned native libsqlcipher.so) + AndroidX SQLite
    implementation("net.zetetic:sqlcipher-android:4.6.1@aar")
    implementation("androidx.sqlite:sqlite-ktx:2.4.0")
    implementation("androidx.sqlite:sqlite-framework:2.4.0")

    // Security & Background Reliability
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("androidx.work:work-runtime-ktx:2.10.0")

    // Serialization, Coroutines, QR Rendering, Media Loading
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("com.google.zxing:core:3.5.3")
    implementation("io.coil-kt:coil-compose:2.7.0")

    // Unit & Robolectric Integration Tests
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation("org.robolectric:robolectric:4.13")
    testImplementation("androidx.test:core-ktx:1.6.1")
}
