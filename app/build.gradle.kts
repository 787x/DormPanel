plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.ksp)
}

val airPlaySource = layout.projectDirectory.dir("src/main/airplay")
val airPlayAbis = listOf("armeabi-v7a", "arm64-v8a", "x86_64")
val airPlayOutput = layout.buildDirectory.dir("generated/airplay")
val airPlayBuilds = airPlayAbis.map { abi ->
    tasks.register<Exec>("buildAirPlayNative" + abi.replace(Regex("[^a-zA-Z0-9]"), "_")) {
        val output = airPlayOutput.map { it.dir(abi) }
        workingDir(rootProject.projectDir)
        inputs.dir(airPlaySource)
        inputs.property("abi", abi)
        outputs.dir(output)
        val sourcePath = rootProject.relativePath(airPlaySource.file("build.sh").asFile).replace('\\', '/')
        val outputPath = rootProject.relativePath(output.get().asFile).replace('\\', '/')
        val shell = if (System.getProperty("os.name").startsWith("Windows")) listOf("wsl", "--exec", "bash") else listOf("bash")
        commandLine(shell + listOf(sourcePath, outputPath, abi))
    }
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
        versionCode = 19
        versionName = "0.1.14"

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
            applicationIdSuffix = providers.gradleProperty("dormpanel.testSuffix").getOrElse(".testbed")
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
    packaging.jniLibs.keepDebugSymbols += "**/libairplay_native.so" // Stripped by the pinned NDK in build.sh.
    sourceSets.getByName("test").resources.srcDir("../schedule-core/src/test/resources")
    sourceSets.getByName("main").apply {
        jniLibs.srcDirs(airPlayAbis.map { airPlayOutput.get().dir("$it/jni").asFile })
        assets.srcDir(airPlayOutput.get().dir("armeabi-v7a/assets").asFile)
    }
    sourceSets.getByName("androidTest").assets.srcDir("../schedule-core/src/test/resources/schedule")
    sourceSets.getByName("androidTest").kotlin.srcDir("../schedule-core/src/uiTest/java")
}

tasks.configureEach {
    if (name.startsWith("merge") && name.endsWith("JniLibFolders")) dependsOn(airPlayBuilds)
    if (name.startsWith("merge") && name.endsWith("Assets")) dependsOn(airPlayBuilds.first())
}

dependencies {
    implementation(project(":schedule-core"))
    implementation("androidx.recyclerview:recyclerview:1.2.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("net.sf.biweekly:biweekly:0.6.8") { exclude(group = "com.fasterxml.jackson.core") }
    implementation("net.i2p.crypto:eddsa:0.3.0")
    implementation("com.google.zxing:core:3.5.4")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("com.squareup.okhttp3:okhttp-tls:4.12.0")
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
    androidTestImplementation("com.squareup.okhttp3:okhttp-tls:4.12.0")
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.room.testing)
}
