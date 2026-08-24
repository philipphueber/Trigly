plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
}

android {
    namespace = "app.phueber.trigly.core"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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

    // The committed schema JSON, packaged into the test APK so
    // `MigrationTestHelper` can read it — it looks the schemas up as assets at
    // runtime, and without this a migration test fails complaining the schema is
    // missing rather than that the migration is wrong.
    //
    // Pointed at the same directory KSP writes, deliberately: copying the files
    // under src/androidTest/assets would work today and go stale the first time a
    // schema is added, which is exactly when a migration test matters most.
    sourceSets["androidTest"].assets.srcDir("$projectDir/schemas")
}

ksp {
    // Committed schema JSON is what makes a future migration writable rather than
    // guesswork — rules are user data and must survive an app update.
    arg("room.schemaLocation", "$projectDir/schemas")
}

// :core is the one module that must stay UI-free. It may not depend on :ui,
// on Compose, or on any sibling module — see docs/architecture.md.
dependencies {
    implementation(libs.kotlinx.coroutines.core)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    // Real org.json, because android.jar's is a stub that throws.
    testImplementation(libs.org.json)

    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.room.testing)
}
