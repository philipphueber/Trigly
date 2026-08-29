package app.phueber.trigly.ui

/**
 * One open source project this app ships a build of: its name, and the
 * licence it ships under.
 */
data class Attribution(val name: String, val license: String)

/**
 * What actually reaches the release APK, hand-written.
 *
 * **Superseded by stage 2.** `app.cash.licensee` walks the real release
 * runtime classpath of `:ui` and a generated task turns its report into a
 * list like this one, compiled into the module. This hand-written list
 * cannot see a dependency added or bumped anywhere in the project, so it
 * stands only until that generated list replaces it and the one call site
 * that reads this constant is pointed at the generated one instead.
 *
 * Every entry below is verified against `gradle/libs.versions.toml` and the
 * release runtime classpath it produces, including
 * `androidx.compose.material:material-icons-core`, which has no entry of its
 * own in the catalog: it arrives transitively through `material3`. A list
 * built by walking the catalog alone would miss exactly that one.
 */
val shippedDependencies: List<Attribution> = listOf(
    Attribution("kotlinx-coroutines-core", "Apache License 2.0"),
    Attribution("androidx.core:core-ktx", "Apache License 2.0"),
    Attribution("androidx.room:room-runtime", "Apache License 2.0"),
    Attribution("androidx.room:room-ktx", "Apache License 2.0"),
    Attribution("androidx.lifecycle:lifecycle-runtime-ktx", "Apache License 2.0"),
    Attribution("androidx.lifecycle:lifecycle-runtime-compose", "Apache License 2.0"),
    Attribution("androidx.lifecycle:lifecycle-viewmodel-compose", "Apache License 2.0"),
    Attribution("androidx.activity:activity-compose", "Apache License 2.0"),
    Attribution("androidx.compose.ui:ui", "Apache License 2.0"),
    Attribution("androidx.compose.ui:ui-tooling-preview", "Apache License 2.0"),
    Attribution("androidx.compose.material3:material3", "Apache License 2.0"),
    Attribution("androidx.compose.material:material-icons-core", "Apache License 2.0"),
    Attribution("Kotlin Standard Library", "Apache License 2.0"),
)
