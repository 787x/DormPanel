plugins { alias(libs.plugins.android.application) }
android {
    namespace = "com.dormpanel.phone"
    compileSdk = 37
    defaultConfig {
        applicationId = "com.dormpanel.timetable"
        minSdk = 28
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    sourceSets.getByName("androidTest").assets.srcDir("../schedule-core/src/test/resources/schedule")
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
