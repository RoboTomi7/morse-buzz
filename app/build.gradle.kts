plugins {
    id("com.android.application")
}

android {
    namespace = "dev.morsebuzz"
    compileSdk = 34

    defaultConfig {
        applicationId = "dev.morsebuzz"
        minSdk = 26
        targetSdk = 34
        versionCode = 6   // keep in sync with the VERSION file
        versionName = "6"
    }

    // A fixed key that ships with the project, so every build (local or GitHub Actions)
    // is signed the same way and new versions install over old ones without uninstalling.
    // This is a personal sideload app, not a Play Store release, so a public key is fine.
    signingConfigs {
        create("sideload") {
            storeFile = file("sideload.keystore")
            storeType = "pkcs12"
            storePassword = "morsebuzz"
            keyAlias = "morsebuzz"
            keyPassword = "morsebuzz"
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("sideload")
        }
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("sideload")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("org.eclipse.paho:org.eclipse.paho.client.mqttv3:1.2.5")
}
