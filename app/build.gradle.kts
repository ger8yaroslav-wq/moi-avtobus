plugins {
    id("com.android.application")
}

android {
    namespace = "ru.salarevo.buswidget"
    compileSdk = 36

    defaultConfig {
        applicationId = "ru.salarevo.buswidget"
        minSdk = 26
        targetSdk = 34
        versionCode = 20
        versionName = "4.1"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("androidx.work:work-runtime:2.10.1")
}
