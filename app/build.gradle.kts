plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.dormpanel.app"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.dormpanel.app"
        minSdk = 28
        targetSdk = 37
        versionCode = 2
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Release signing secrets are supplied by the developer machine only:
    //   dormpanel.release.keystore / DORMPANEL_RELEASE_KEYSTORE
    //   dormpanel.release.keyAlias  / DORMPANEL_RELEASE_KEY_ALIAS
    //   dormpanel.release.storePassword / DORMPANEL_RELEASE_STORE_PASSWORD
    //   dormpanel.release.keyPassword   / DORMPANEL_RELEASE_KEY_PASSWORD
    // Never commit the keystore or password values.
    signingConfigs {
        create("release") {
            val keystorePath = providers.gradleProperty("dormpanel.release.keystore")
                .orElse(providers.environmentVariable("DORMPANEL_RELEASE_KEYSTORE"))
                .orNull
            val keyAlias = providers.gradleProperty("dormpanel.release.keyAlias")
                .orElse(providers.environmentVariable("DORMPANEL_RELEASE_KEY_ALIAS"))
                .orNull
            val storePassword = providers.gradleProperty("dormpanel.release.storePassword")
                .orElse(providers.environmentVariable("DORMPANEL_RELEASE_STORE_PASSWORD"))
                .orNull
            val keyPassword = providers.gradleProperty("dormpanel.release.keyPassword")
                .orElse(providers.environmentVariable("DORMPANEL_RELEASE_KEY_PASSWORD"))
                .orNull
            if (keystorePath != null && keyAlias != null && storePassword != null && keyPassword != null) {
                storeFile = file(keystorePath)
                this.keyAlias = keyAlias
                this.storePassword = storePassword
                this.keyPassword = keyPassword
            }
        }
    }
    buildTypes {
        create("x08eTest") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".testbed"
            matchingFallbacks += listOf("debug")
        }
        release {
            // Size optimization is intentionally off: X08E stability matters more.
            optimization {
                enable = false
            }
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = signingConfigs.getByName("release").takeIf { it.storeFile != null }
        }
    }
    testBuildType = providers.gradleProperty("dormpanel.testBuildType").getOrElse("debug")
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    sourceSets.getByName("androidTest").assets.srcDir("src/test/resources/schedule")
}

dependencies {
    implementation("net.sf.biweekly:biweekly:0.6.8") {
        // Only the text ICS reader is used; no JSON/XML codecs or timezone downloads.
        exclude(group = "com.fasterxml.jackson.core")
    }
    implementation("androidx.recyclerview:recyclerview:1.2.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.zxing:core:3.5.4")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.json:json:20240303")
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.room.runtime)
    implementation(libs.material)
    ksp(libs.androidx.room.compiler)
    testImplementation(libs.junit)
    androidTestImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.room.testing)
}
