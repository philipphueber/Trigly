plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "app.phueber.trigly.triggers"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // The instrumented tests run against the API level the app itself targets.
    // A library module has no targetSdk of its own, so its test APK used to
    // declare `targetSdkVersion 26`, the minSdk. Several platform rules are
    // gated on that value and not on the device's API level: from targetSdk 34
    // a context-registered receiver must name RECEIVER_EXPORTED or
    // RECEIVER_NOT_EXPORTED, for one. A test APK targeting 26 is excused from
    // every one of those, so it cannot see a fault the shipped app would hit,
    // which is the one job these tests have.
    testOptions {
        targetSdk = 35
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    sourceSets["main"].java.srcDirs("src/main/kotlin")
    sourceSets["test"].java.srcDirs("src/test/kotlin")
    sourceSets["androidTest"].java.srcDirs("src/androidTest/kotlin")
}

// Depends on :core for the interfaces and on nothing else in the project. It
// must never depend on :ui or on :actions.
dependencies {
    api(project(":core"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)

    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.kotlinx.coroutines.test)
}
