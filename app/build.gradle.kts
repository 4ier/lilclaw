plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.lilclaw.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.lilclaw.app"
        minSdk = 26
        //noinspection OldTargetApi — targetSdk 28 required for proot execve (W^X exemption)
        targetSdk = 28
        versionCode = 70
        versionName = "0.7.0"

        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true // Extract native libs to filesystem
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
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

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    applicationVariants.all {
        val variant = this
        outputs.all {
            val output = this as com.android.build.gradle.internal.api.BaseVariantOutputImpl
            output.outputFileName = "lilclaw-v${variant.versionName}.apk"
        }
    }

    // Don't compress rootfs tarballs in assets (we extract them ourselves)
    androidResources {
        noCompress += listOf("tar.gz", "bin")
    }
}

dependencies {
    // Compose (minimal)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.runtime)
    debugImplementation(libs.compose.ui.tooling)

    // AndroidX
    implementation(libs.core.ktx)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.runtime)
    implementation(libs.lifecycle.viewmodel)
    implementation(libs.navigation.compose)
    implementation(libs.datastore.preferences)
    implementation(libs.webkit)

    // Koin DI
    implementation(libs.koin.android)
    implementation(libs.koin.compose)

    // Network
    implementation(libs.okhttp)
    implementation(libs.coroutines.android)

    // Embedded HTTP server (for A11y/Device bridges)
    implementation(libs.nanohttpd)

    // Testing
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
