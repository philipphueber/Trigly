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

tasks.withType<Test>().configureEach {
    // RegexGuard (RegexBudget.kt) is one thread shared by the whole process,
    // by design. Two kinds of test in this module deliberately push it past
    // its normal operation, and both leave state behind that is not scoped
    // to one test class: TextFilterRegexRefusalTest, ExpressionRegexRefusalTest
    // and MatchRangesRegexRefusalTest each abandon a thread on a pattern that
    // never finishes, and RegexGuardTest's own test of MAX_ABANDONED_THREADS
    // deliberately abandons that many more on purpose. RegexGuard remembers
    // every pattern that has ever timed out, and caps how many abandoned
    // threads may exist, for the life of the process, not per test class:
    // that is the fix for the fault the connected gate found, not a leftover
    // bug, and it is deliberately the same lifetime a real device process
    // would give it. A fresh JVM per test class is what keeps one class's
    // deliberate abandonment from being inherited by the next one's ordinary
    // searches.
    //
    // This is not a caution taken on paper. It was measured twice: once
    // before RegexGuard stopped poisoning itself for every pattern after a
    // single timeout, when removing this line made ExpressionTest and
    // MatchRangesTest fail outright with a ClassCastException or an
    // AssertionError; and again after that fix, when removing this line
    // still made MatchRangesTest's five regex tests fail, this time with an
    // empty highlight where a real one was expected, because
    // RegexGuardTest's own MAX_ABANDONED_THREADS test had already spent
    // every abandoned-thread slot by the time MatchRangesTest's turn came.
    // The mechanism changed; the need for isolation between classes did not.
    //
    // A narrower fix was considered and rejected: only a handful of this
    // module's 34 JVM test classes ever touch RegexGuard, so a second Gradle
    // Test task, forked only for those few classes, would isolate the same
    // hazard for less total JVM-startup cost. That was not built, because it
    // would split what "run the unit tests" means into two tasks that both
    // have to be wired into `check` and kept in sync by hand, for a module
    // whose whole run, forked per class, was measured at 48 seconds against
    // 36 seconds and five wrong answers with no forking at all. Forty-eight
    // seconds is not free, but it is cheap enough that the one thing this
    // module's test task means everywhere it is invoked is worth more than
    // the twelve seconds saved by only forking the classes known to need it
    // today.
    forkEvery = 1
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
