import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.dokka")
}

val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) load(f.inputStream())
}

android {
    namespace = "com.example.buddyug"
    compileSdk = 33

    defaultConfig {
        applicationId = "com.example.buddyug"
        minSdk = 28
        targetSdk = 33
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

repositories {
    google()
    mavenCentral()
    maven {
        url = uri("https://bluefrogrobotics.jfrog.io/artifactory/bluefrogrobotics-libs-release-local/")
        credentials {
            username = localProps.getProperty("buddy.repo.username")
            password = localProps.getProperty("buddy.repo.password")
        }
    }
}

dependencies {
    implementation("com.bluefrogrobotics.buddy:BuddySDK:2.4+")
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
}
