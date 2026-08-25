plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.sierra.voiceapp"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.sierra.voiceapp"
        minSdk = 24
        targetSdk = 34
        // Inyectados por CI (run number + commit corto) para que cada build
        // sea distinguible y Android no ignore una instalacion con el mismo
        // version code que la anterior. En un build local sin esas variables,
        // caen en un default fijo.
        versionCode = System.getenv("SIERRA_VERSION_CODE")?.toIntOrNull() ?: 1
        versionName = System.getenv("SIERRA_VERSION_NAME") ?: "1.0-local"
    }

    signingConfigs {
        getByName("debug") {
            // Keystore de debug fijo y committeado (generado con openssl, formato
            // PKCS12) -- sin esto, cada build de CI firma con una clave distinta
            // (la que Gradle autogenera por defecto en un runner efimero) y
            // Android rechaza instalar la version nueva encima de la vieja
            // ("conflicto con un paquete").
            storeFile = file("debug.keystore")
            storeType = "PKCS12"
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
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
        // Necesario para usar java.time (Instant/Duration) con minSdk 24.
        isCoreLibraryDesugaringEnabled = true
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.activity:activity-ktx:1.9.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.0.4")
}
