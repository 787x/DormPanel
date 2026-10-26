plugins { alias(libs.plugins.android.application) }
android {
    namespace = "com.dormpanel.phone"
    compileSdk = 37
    defaultConfig {
        applicationId = "com.dormpanel.timetable"
        minSdk = 28
        targetSdk = 37
        versionCode = 10
        versionName = "0.1.8"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    // Both apps retain the approved identity. Secrets stay on the build machine.
    signingConfigs {
        create("release") {
            val keystorePath = providers.gradleProperty("dormpanel.release.keystore")
                .orElse(providers.environmentVariable("DORMPANEL_RELEASE_KEYSTORE")).orNull
            val alias = providers.gradleProperty("dormpanel.release.keyAlias")
                .orElse(providers.environmentVariable("DORMPANEL_RELEASE_KEY_ALIAS")).orNull
            val storePassword = providers.gradleProperty("dormpanel.release.storePassword")
                .orElse(providers.environmentVariable("DORMPANEL_RELEASE_STORE_PASSWORD")).orNull
            val keyPassword = providers.gradleProperty("dormpanel.release.keyPassword")
                .orElse(providers.environmentVariable("DORMPANEL_RELEASE_KEY_PASSWORD")).orNull
            if (keystorePath != null && alias != null && storePassword != null && keyPassword != null) {
                storeFile = file(keystorePath)
                keyAlias = alias
                this.storePassword = storePassword
                this.keyPassword = keyPassword
            }
        }
    }
    testBuildType = providers.gradleProperty("dormpanel.testBuildType").getOrElse("debug")
    buildTypes {
        create("x08eTest") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".testbed"
            matchingFallbacks += listOf("debug")
        }
        release {
            isDebuggable = false
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = signingConfigs.getByName("release").takeIf { it.storeFile != null }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    sourceSets.getByName("androidTest").assets.srcDir("../schedule-core/src/test/resources/schedule")
    sourceSets.getByName("androidTest").kotlin.srcDir("../schedule-core/src/uiTest/java")
}
dependencies {
    implementation(project(":schedule-core"))
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.ktx)
    testImplementation(libs.junit)
    androidTestImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation("androidx.test.espresso:espresso-intents:3.7.0")
    androidTestImplementation(libs.androidx.espresso.core)
}
