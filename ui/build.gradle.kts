import java.io.File
import java.util.Properties
import java.util.concurrent.TimeUnit

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// Release signing is described by a gitignored `keystore.properties` at the repo
// root, so neither the keystore nor its passwords ever enter the repository. A
// missing file is deliberately not an error: it leaves the release build
// unsigned so that debug builds, unit tests and lint still work for a
// contributor who has no signing key. See docs/releasing.md.
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        keystorePropertiesFile.inputStream().use { load(it) }
    }
}

/**
 * The signing password, from the system keyring rather than from a file.
 *
 * `scripts/setup-signing.sh` puts it there, which is what lets the password be
 * typed once by a person and then used by builds nobody is watching — without
 * the secret existing as plaintext anywhere on disk. The file keeps the parts
 * that are not secrets: which keystore, which alias.
 *
 * A `storePassword` in `keystore.properties` still wins if one is present. That
 * is the escape hatch for a machine with no keyring — CI, a container, a
 * headless box — and it is checked first so that a password somebody wrote down
 * deliberately is never silently ignored in favour of a stale keyring entry.
 *
 * Every failure here resolves to null, and null means the release build comes
 * out unsigned. That is the same graceful outcome as a missing
 * `keystore.properties`, and it matters because the keyring is genuinely absent
 * in ordinary situations: no D-Bus in a headless session, a login keyring still
 * locked, libsecret not installed. None of those should turn `./gradlew test`
 * into a configuration error.
 */
fun signingPasswordOrNull(): String? {
    keystoreProperties.getProperty("storePassword")?.takeIf { it.isNotBlank() }?.let { return it }

    val secretTool = listOf("/usr/bin/secret-tool", "/bin/secret-tool")
        .map(::File)
        .firstOrNull { it.canExecute() }
        ?: return null

    // ProcessBuilder rather than providers.exec: exec() treats a non-zero exit
    // as a build failure, and "the keyring has no entry" is an expected answer
    // here, not a failure. Cheap enough to run at configure time — it is one
    // D-Bus round trip, and only for this module.
    return runCatching {
        val process = ProcessBuilder(
            secretTool.path, "lookup", "service", "trigly", "key", "release-keystore",
        ).redirectErrorStream(false).start()

        val output = process.inputStream.bufferedReader().use { it.readText() }
        // A locked keyring can leave secret-tool waiting on a prompt that will
        // never be answered in a non-interactive build, so it does not get to
        // hang the configuration phase.
        if (!process.waitFor(10, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            return null
        }
        if (process.exitValue() != 0) return null
        // secret-tool prints the secret with no trailing newline of its own on
        // some versions and with one on others; only the line ending is trimmed,
        // because a password may legitimately begin or end with a space.
        output.removeSuffix("\n").takeIf { it.isNotEmpty() }
    }.getOrNull()
}

val signingPassword: String? by lazy { signingPasswordOrNull() }

val releaseStoreFile = keystoreProperties.getProperty("storeFile")
    ?.let { rootProject.file(it) }
    ?.takeIf { it.isFile }

/**
 * The version, in one place because two things now read it: the manifest, and the
 * name of the release artifact. `docs/releasing.md` describes what each number is
 * for; the release tag is this string prefixed with `v`.
 *
 * Declared here rather than inside `defaultConfig` so the dist task can name a
 * file after it without reaching into the Android extension at execution time.
 * A second literal in the task would be a version the build could disagree with
 * itself about, and the only symptom would be an APK whose name lies.
 */
val triglyVersionName = "0.0.6"
val triglyVersionCode = 6

android {
    namespace = "app.phueber.trigly.ui"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.phueber.trigly"
        minSdk = 26
        targetSdk = 35
        versionCode = triglyVersionCode
        versionName = triglyVersionName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        // Three things have to be true, and any one of them missing means an
        // unsigned build rather than a failed one: a description of the key, the
        // key file itself, and a password to open it. Checking the file exists
        // here rather than letting the signer discover it keeps a stale path in
        // keystore.properties from failing the build late and obscurely.
        val password = signingPassword
        if (releaseStoreFile != null && password != null) {
            create("release") {
                storeFile = releaseStoreFile
                storePassword = password
                keyAlias = keystoreProperties.getProperty("keyAlias")
                // One password for both, which is what setup-signing.sh creates
                // and what keytool defaults to. A key password that differs from
                // the store password can be given its own keyring entry the day
                // anyone needs one.
                keyPassword = password
            }
        }
    }

    buildTypes {
        release {
            // findByName, not getByName: null when there is no key, which is
            // what makes an unsigned release build possible.
            signingConfig = signingConfigs.findByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    buildFeatures {
        compose = true
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

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

/**
 * Puts the release APK where a person can find it, under a name that says what
 * it is: `<repo>/dist/trigly-<version>.apk`.
 *
 * The build's own output is buried at `ui/build/outputs/apk/release/` under a
 * name that describes the *module* — `ui-release.apk` — which is the wrong name
 * for the thing being handed to someone to install, and a path nobody should
 * have to be told twice. `dist/` at the root is where a release artifact goes.
 *
 * The version is in the filename because these files outlive the directory they
 * were built in: they get downloaded, forwarded, and kept, and a folder holding
 * three files all called `trigly.apk` cannot say which is which. A name carrying
 * `0.0.3` answers "what am I about to install" without a checksum or a `dump
 * badging`.
 *
 * **The filename still carries the signing signal, because that is the only one
 * there is.** An unsigned release build is a success, not an error — a
 * contributor without the key can and should be able to check that R8 does not
 * break the release variant — so the one thing that says whether signing
 * happened is what the file is called. Copying both possible inputs to one
 * output name would throw that away, so unsigned stays visibly unsigned.
 */
val distRelease by tasks.registering(Copy::class) {
    group = "distribution"
    description = "Copies the release APK to <repo>/dist as trigly-<version>.apk."

    dependsOn("assembleRelease")

    // Captured at configuration time: the task action must not read the Android
    // extension, and a literal here could disagree with the manifest.
    val version = triglyVersionName

    from(layout.buildDirectory.dir("outputs/apk/release")) {
        include("*.apk")
        rename { original ->
            if (original.contains("unsigned")) {
                "trigly-$version-unsigned.apk"
            } else {
                "trigly-$version.apk"
            }
        }
    }
    into(rootProject.layout.projectDirectory.dir("dist"))

    doLast {
        // Reports what was actually written, not what was hoped for: naming the
        // signed file here regardless would undo the whole point of keeping an
        // unsigned build visibly unsigned.
        //
        // Filtered to this version, because versioned names mean `dist/` keeps
        // the previous releases too — and listing those as if they had just been
        // built is how the wrong APK gets published.
        val written = rootProject.file("dist")
            .listFiles { file ->
                file.name.startsWith("trigly-$version") && file.extension == "apk"
            }
            .orEmpty()
            .sortedBy { it.name }
        written.forEach { logger.lifecycle("Release artifact: $it") }
    }
}

// The only module that may depend on every other one: it is where the app is
// assembled. Nothing may depend on :ui.
dependencies {
    implementation(project(":core"))
    implementation(project(":triggers"))
    implementation(project(":actions"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    testImplementation(libs.junit)

    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
}
