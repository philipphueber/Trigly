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
    // by design, and a test that deliberately gives it a runaway pattern
    // leaves that search running in the background for an unmeasured time
    // after the test itself has finished. TextFilterRegexRefusalTest,
    // ExpressionRegexRefusalTest and MatchRangesRegexRefusalTest are each a
    // class with nothing else in it for exactly that reason, so the only
    // thing left to protect is one class's tests from another's leftover
    // search in the same JVM. A fresh JVM per test class is what does that:
    // Gradle tears the old one down, lingering thread and all, before the
    // next class's tests start.
    //
    // This is not a caution taken on paper. It was measured by removing this
    // line: without it, the whole module's tests share one JVM, Gradle's
    // default class order puts each refusal class before the ordinary test
    // of the same name it shares a prefix with (ExpressionRegexRefusalTest
    // before ExpressionTest, and so on, plainly by alphabetical sort), and
    // ExpressionTest and MatchRangesTest then failed outright with a
    // ClassCastException or an AssertionError, not a timeout, not a flaky
    // slowdown, because RegexGuard was still occupied by the previous
    // class's abandoned search when their own ordinary patterns asked to
    // run. That is a wrong answer reaching a real assertion, in a test that
    // has nothing pathological in it, which is worse than the alternative
    // this line costs.
    //
    // A narrower fix was considered and rejected: only a handful of this
    // module's 34 JVM test classes ever touch RegexGuard, so a second Gradle
    // Test task, forked only for those few classes, would isolate the same
    // hazard for less total JVM-startup cost. That was not built, because it
    // would split what "run the unit tests" means into two tasks that both
    // have to be wired into `check` and kept in sync by hand, for a module
    // whose whole run, forked per class, was measured at 53 seconds against
    // 121 seconds and rising (the run had not finished, and was already
    // producing wrong answers) with no forking at all. Fifty-three seconds is
    // not free, but it is cheap enough that the one thing this module's test
    // task means everywhere it is invoked is worth more than the seconds
    // saved by only forking the classes known to need it today.
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
