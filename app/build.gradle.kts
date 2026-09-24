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
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        create("x08eTest") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".testbed"
            matchingFallbacks += listOf("debug")
        }
        release {
            optimization {
                enable = false
            }
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
