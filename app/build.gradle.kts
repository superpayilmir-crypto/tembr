plugins { id("com.android.application") }

android {
    namespace = "app.tembr"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.tembr"
        minSdk = 26
        targetSdk = 35
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionName = "1.0." + (System.getenv("GITHUB_RUN_NUMBER") ?: "0")
    }

    signingConfigs {
        create("release") {
            // Секретный ключ приходит из настроек GitHub (Secrets): TEMBR_KEYSTORE и TEMBR_KEY_PASSWORD.
            val ksFile = System.getenv("TEMBR_KEYSTORE_FILE")
            val ksPass = System.getenv("TEMBR_KEY_PASSWORD")
            if (!ksFile.isNullOrBlank() && !ksPass.isNullOrBlank()) {
                storeFile = file(ksFile)
                storeType = "PKCS12"
                storePassword = ksPass
                keyAlias = "tembr"
                keyPassword = ksPass
            } else {
                // Временный запасной ключ, пока секреты не добавлены в GitHub
                storeFile = rootProject.file("tembr.keystore")
                storePassword = "tembr2026"
                keyAlias = "tembr"
                keyPassword = "tembr2026"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("androidx.webkit:webkit:1.12.1")
}
