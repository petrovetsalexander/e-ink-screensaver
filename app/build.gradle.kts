plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.eink.screensaver"

    // Android 14 (API 34) is the target device platform.
    compileSdk = 34

    defaultConfig {
        applicationId = "com.eink.screensaver"
        minSdk = 28
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        resourceConfigurations += listOf("en", "ru")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
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

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    buildFeatures {
        buildConfig = false
        viewBinding = false
    }

    lint {
        warningsAsErrors = false
        abortOnError = false
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    // Used directly by settings/ModuleSettingsActivity (drag-to-reorder list);
    // declared explicitly rather than relying on Material's transitive copy.
    implementation(libs.androidx.recyclerview)
    // Used only by EinkCompat, to reach the vendor xrz framework without
    // requiring every user to set hidden_api_policy over adb.
    implementation(libs.hiddenapibypass)
}
