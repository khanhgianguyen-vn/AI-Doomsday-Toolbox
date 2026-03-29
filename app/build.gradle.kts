plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.jetbrains.kotlin.android)
    alias(libs.plugins.ksp)
    alias(libs.plugins.jetbrains.kotlin.serialization)
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

android {
    namespace = "com.example.llamadroid"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.manuxd32.aidoomsdaytoolbox"
        minSdk = 26
        targetSdk = 35
        versionCode = 932
        versionName = "0.932"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
        
        // Limit to arm64 only (CPU features detection uses ARM-specific headers)
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    signingConfigs {
        getByName("debug") {
            // Uses default debug keystore
        }
        create("release") {
            // Keystore path - set via environment or use default location
            storeFile = file(System.getenv("KEYSTORE_PATH") ?: System.getProperty("user.home") + "/.android/aidoomsdaytoolbox-release.jks")
            storePassword = System.getenv("KEYSTORE_PASSWORD") ?: ""
            keyAlias = System.getenv("KEY_ALIAS") ?: "release"
            keyPassword = System.getenv("KEY_PASSWORD") ?: ""
            isV1SigningEnabled = true
            isV2SigningEnabled = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Use release signing for Play Store
            signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.8"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            // Exclude version files that conflict between dynamic feature modules
            excludes += "META-INF/*.version"
            excludes += "META-INF/versions/**"
        }
        jniLibs {
            useLegacyPackaging = true
        }
    }
    
    // NDK for CPU features detection
    ndkVersion = "29.0.14206865"
    
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    
    // Asset Packs for on-demand native binary delivery
    assetPacks += setOf(
        ":asset_upscaler"
    )
    
    // Dynamic Features for native binaries execution (Install-Time/On-Demand)
    dynamicFeatures += setOf(
        ":feature_llm_baseline", ":feature_llm_dotprod", ":feature_llm_armv9",
        ":feature_kiwix_baseline", ":feature_kiwix_dotprod", ":feature_kiwix_armv9",
        ":feature_media_baseline", ":feature_media_dotprod", ":feature_media_armv9",
        ":feature_upscaler"
    )
}

dependencies {

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.fragment)
    
    // Document file support for SAF
    implementation("androidx.documentfile:documentfile:1.0.1")
    
    // Play Asset Delivery for on-demand native binaries
    implementation("com.google.android.play:asset-delivery:2.2.2")
    implementation("com.google.android.play:asset-delivery-ktx:2.2.2")
    
    // Networking
    implementation(libs.retrofit)
    implementation(libs.retrofit.gson)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)

    // DB
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // Image Loading
    implementation(libs.coil.compose)
    
    // Serialization
    implementation(libs.kotlinx.serialization.json)
    
    // Apache Commons Compress for tar extraction (handles hardlinks)
    implementation(libs.commons.compress)
    implementation(libs.xz)
    
    // PDF
    implementation(libs.pdfbox)
    
    // ML Kit Text Recognition for OCR
    implementation("com.google.mlkit:text-recognition:16.0.0")
    
    // Model Sharing - HTTP server
    implementation("org.nanohttpd:nanohttpd:2.3.1")
    
    // QR Code generation
    implementation("com.google.zxing:core:3.5.2")
    
    // SSH client (Termux integration)
    implementation("com.jcraft:jsch:0.1.55")
    
    // Play Feature Delivery for dynamic modules
    implementation("com.google.android.play:feature-delivery:2.1.0")
    implementation("com.google.android.play:feature-delivery-ktx:2.1.0")

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
    
    // Retrofit with kotlinx.serialization
    implementation("com.jakewharton.retrofit:retrofit2-kotlinx-serialization-converter:1.0.0")
}
