plugins {
    id("com.android.application")
}

android {
    namespace = "com.k4811.camera.front"
    compileSdk = 36

    defaultConfig {
        minSdk = 19
        targetSdk = 19 // < 23: CAMERA, WRITE_SETTINGS y WRITE_EXTERNAL_STORAGE se conceden al instalar
        versionCode = 20270103
        versionName = "3.1.1"
        ndk {
            abiFilters += listOf("armeabi-v7a")
        }
    }

    buildFeatures {
        aidl = false
        buildConfig = false
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    lint {
        disable += listOf("ExpiredTargetSdkVersion", "OldTargetApi")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
}

dependencies {
    implementation(project(":common"))
}
