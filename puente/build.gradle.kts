plugins {
    id("com.android.application")
}

android {
    namespace = "com.k4811.puente"
    compileSdk = 36

    defaultConfig {
        minSdk = 19
        targetSdk = 19 // < 23: permisos concedidos al instalar
        versionCode = 20270004
        versionName = "1.3.0"
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
