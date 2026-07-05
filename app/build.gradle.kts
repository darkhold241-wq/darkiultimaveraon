plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.darki.os"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.darki.os"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0-nucleo"
    }

    buildFeatures {
        compose = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
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

    packaging {
        resources.excludes.add("META-INF/*")
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.2")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("com.google.android.material:material:1.12.0")

    // Reconocimiento de voz offline (palabra de activacion + STT).
    // Se resuelve desde Maven Central, trae su propia dependencia de JNA
    // via metadata de Gradle, no hace falta declararla aparte.
    implementation("com.alphacephei:vosk-android:0.3.75")

    // Llamadas HTTP a la API de Anthropic
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.json:json:20240303")

    // Almacenamiento cifrado para la API key
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // Corrutinas para las llamadas asincronas
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Room: memoria persistente de DARKI (sobrevive a reinicios)
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")
}
