import java.net.URL
import java.net.URLClassLoader

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.torchain.android"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.torchain.android"
        minSdk = 29
        targetSdk = 34
        versionCode = 2
        versionName = "5.0.1-android"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64") }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true; buildConfig = true }
    composeOptions { kotlinCompilerExtensionVersion = "1.5.14" }
    packaging {
        resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" }
        jniLibs { useLegacyPackaging = true }
    }
    sourceSets {
        getByName("main") {
            jniLibs.srcDirs("src/main/jniLibs")
            assets.srcDirs("src/main/assets")
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.lifecycle:lifecycle-service:2.8.4")
    implementation("androidx.activity:activity-compose:1.9.1")
    implementation("androidx.localbroadcastmanager:localbroadcastmanager:1.1.0")
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.runtime:runtime-livedata")
    implementation("androidx.navigation:navigation-compose:2.7.7")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.json:json:20240303")
    implementation("com.netzarchitekten:IPtProxy:3.8.2")
}

tasks.register("assertNativeLibsExist") {
    doLast {
        val jniLibsDir = file("src/main/jniLibs")
        val abis = listOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
        val requiredLibs = listOf("libtor.so", "libhev-socks5-tunnel.so")
        
        for (abi in abis) {
            val abiDir = File(jniLibsDir, abi)
            for (lib in requiredLibs) {
                val libFile = File(abiDir, lib)
                if (!libFile.exists()) {
                    throw GradleException(
                        "Required native library $lib is missing for ABI $abi in $abiDir. " +
                        "Please run ./scripts/download_tor.sh before building."
                    )
                }
            }
        }
        println("assertNativeLibsExist: All required native libraries are present.")
    }
}

tasks.named("preBuild") {
    dependsOn("assertNativeLibsExist")
}

