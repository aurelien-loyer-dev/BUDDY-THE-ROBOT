import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.dokka")
}

val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) load(f.inputStream())
}

android {
    namespace = "com.example.buddyug"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.buddyug"
        minSdk = 28
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        buildConfigField(
            "String",
            "GROQ_API_KEY",
            "\"${localProps.getProperty("groq.api.key", "")}\""
        )
    }

    buildFeatures {
        buildConfig = true
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

    // Appels à l'API Groq (Q&A)
    implementation("com.squareup.okhttp3:okhttp:5.4.0")

    // Serveur local embarqué (écran de diffusion en direct pour le salon)
    implementation("org.nanohttpd:nanohttpd-websocket:2.3.1")
}
