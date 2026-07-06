val appVersionName = "1.0-beta11"

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

base {
    archivesName = "Q25toolbox-v$appVersionName"
}

android {
    namespace = "com.kgr.q25toolbox"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.kgr.q25toolbox"
        minSdk = 28
        targetSdk = 34
        versionCode = 12
        versionName = appVersionName

        ndk { abiFilters += "arm64-v8a" }
    }

    // To produce a release build signed with your existing keystore, fill these
    // in (or supply them via gradle.properties / environment variables instead
    // of hardcoding the password here) and reference signingConfigs["release"]
    // from the release buildType below.
    //
    // signingConfigs {
    //     create("release") {
    //         storeFile = file("/path/to/kgr_signing.keystore")
    //         storePassword = "kgr_keystore_2024"
    //         keyAlias = "kgr"
    //         keyPassword = "kgr_keystore_2024"
    //     }
    // }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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

    buildFeatures {
        compose = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")

    implementation(platform("androidx.compose:compose-bom:2024.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    // Root shell access (https://github.com/topjohnwu/libsu)
    implementation("com.github.topjohnwu.libsu:core:5.2.2")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
