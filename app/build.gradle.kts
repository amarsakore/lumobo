import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Release signing comes from keystore.properties (never committed). Without it the release build
// is unsigned, as F-Droid wants (it builds from source and signs with its own key).
//   storeFile=/path/to/lumobo-upload.jks
//   storePassword=…
//   keyAlias=lumobo
//   keyPassword=…
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "app.lumobo"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.lumobo"
        minSdk = 29
        targetSdk = 36
        versionCode = 27
        versionName = "2.0.2"
    }

    signingConfigs {
        if (keystoreProps.getProperty("storeFile") != null) {
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
    // The dependency list Google encrypts into APKs can't be read by anyone else, so F-Droid
    // rejects it; app bundles for Play keep it.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation(project(":film"))
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    // Installs the baseline profiles that ship with Compose: faster start-up and smoother scrolling
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
