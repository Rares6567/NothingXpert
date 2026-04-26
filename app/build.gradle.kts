import java.util.Properties
import java.io.FileInputStream

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.nothingxpert"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.nothingxpert"
        minSdk = 30
        targetSdk = 34
        versionCode = 16
        versionName = "1.6"
    }

    signingConfigs {
        create("release") {
            val keystorePropertiesFile = rootProject.file("app/keystore.properties")
            if (keystorePropertiesFile.exists()) {
                val keystoreProperties = Properties()
                FileInputStream(keystorePropertiesFile).use { stream ->
                    keystoreProperties.load(stream)
                }

                keyAlias = keystoreProperties["keyAlias"] as String
                keyPassword = keystoreProperties["keyPassword"] as String
                storeFile = file(keystoreProperties["storeFile"] as String)
                storePassword = keystoreProperties["storePassword"] as String
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
        debug {
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

dependencies {
	compileOnly(files("lib/api-82.jar"))
	implementation(files("lib/glyph-matrix-sdk-2.0.aar"))

	implementation("androidx.appcompat:appcompat:1.6.1")
	implementation("androidx.core:core-ktx:1.12.0")
	implementation("androidx.preference:preference-ktx:1.2.1")
	implementation("com.google.android.material:material:1.11.0")
	implementation("com.crossbowffs.remotepreferences:remotepreferences:0.8")
	implementation("com.google.android.gms:play-services-mlkit-subject-segmentation:16.0.0-beta1")
}
