plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Signature : fournie par la CI (secrets GitHub) ou en local via les mêmes variables d'environnement.
// Sans keystore, l'APK de release est signé avec la clé de debug (dépannage uniquement).
val releaseKeystore: String? = System.getenv("PARANOID_KEYSTORE")

android {
    namespace = "app.paranoid"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.paranoid"
        minSdk = 33
        targetSdk = 35
        versionCode = 4
        versionName = "0.3.0"
        // Relais de développement (LAN). L'empreinte est publique : ce n'est pas un secret.
        buildConfigField("String", "DEFAULT_RELAY_URL", "\"https://192.168.1.252:8443\"")
        buildConfigField(
            "String", "DEFAULT_RELAY_FP",
            "\"FB:2B:01:81:C4:A7:E4:EA:3C:C9:DE:31:EA:A4:96:5A:D4:7E:C0:D6:F6:B7:4B:91:4F:DD:FB:FC:89:C6:B0:EA\"",
        )
    }

    signingConfigs {
        create("release") {
            if (releaseKeystore != null) {
                storeFile = file(releaseKeystore)
                storePassword = System.getenv("PARANOID_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("PARANOID_KEY_ALIAS")
                keyPassword = System.getenv("PARANOID_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName(if (releaseKeystore != null) "release" else "debug")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources {
            excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "META-INF/versions/9/OSGI-INF/MANIFEST.MF")
        }
    }
}

dependencies {
    implementation(project(":core"))

    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // QR : génération (zxing core) et scan (sans Google Play Services).
    implementation("com.google.zxing:core:3.5.3")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")

    // Notifications sans Google : UnifiedPush (distributeur au choix, ntfy par défaut).
    implementation("org.unifiedpush.android:connector:3.0.+")
}
