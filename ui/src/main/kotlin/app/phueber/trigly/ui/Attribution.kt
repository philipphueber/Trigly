package app.phueber.trigly.ui

/**
 * One Maven artifact this app's release build ships: its coordinate, split
 * into `groupId` and `artifactId`, and the licence it ships under.
 *
 * `shippedDependencies`, the list [groupIntoProjects] folds into what
 * `AttributionScreen` shows, is generated rather than declared in this file:
 * `app.cash.licensee` walks `:ui`'s real release runtime classpath, and the
 * `generateAttributionList` task in `ui/build.gradle.kts` turns its report
 * into a `GeneratedAttribution.kt` under `build/generated/`, built from this
 * same class. Neither is checked in: a committed snapshot would recreate
 * exactly the staleness this design exists to prevent. See
 * docs/architecture.md, "Attribution".
 */
data class Attribution(val groupId: String, val artifactId: String, val license: String) {

    /** `groupId:artifactId`, for messages; nothing renders this directly. */
    val coordinate: String get() = "$groupId:$artifactId"
}

/**
 * One project the Attribution screen credits: a project name, the licence it
 * ships under, and how many of the artifacts on the real release classpath
 * came from it.
 *
 * An artifact is a build output, not something a reader recognises.
 * `androidx.compose.ui:ui-graphics-android` means nothing to somebody who has
 * heard of Jetpack Compose; [groupIntoProjects] is what turns 88 such lines
 * into the handful of projects they actually name.
 */
data class AttributionProject(
    val name: String,
    val license: String,
    val artifactCount: Int,
)

/**
 * Maps an artifact's `groupId` to the project name [groupIntoProjects] credits
 * it under. Null means no project claims that groupId.
 *
 * Keyed on `groupId` alone, never on `scm.url`, even though every artifact in
 * licensee's report carries one and it looks like the natural key: four
 * AndroidX artifacts (`androidx.autofill:autofill`,
 * `androidx.concurrent:concurrent-futures`, `androidx.interpolator:interpolator`,
 * `androidx.versionedparcelable:versionedparcelable`) carry a stale
 * `http://source.android.com` URL left over from before they moved out of
 * AOSP into Jetpack. Grouping by `scm.url` would split AndroidX in two and
 * label part of it as AOSP. The next person reaching for `scm.url` here: it
 * lies for these four, `groupId` does not.
 */
private fun projectNameForGroup(groupId: String): String? = when {
    groupId == "androidx" || groupId.startsWith("androidx.") -> "AndroidX"
    groupId == "org.jetbrains.kotlin" -> "Kotlin"
    groupId == "org.jetbrains.kotlinx" -> "Kotlin Coroutines"
    groupId == "com.google.guava" -> "Guava"
    groupId == "org.jetbrains" -> "JetBrains Java Annotations"
    else -> null
}

/**
 * Folds the generated per-artifact list into the projects `AttributionScreen`
 * actually shows: one entry per project, carrying how many artifacts of it
 * this build ships, so a reader is not left thinking Trigly ships one file of
 * AndroidX.
 *
 * An artifact whose `groupId` matches no project throws, rather than either
 * of the two ways this could otherwise fail quietly: vanishing from the page,
 * which is the exact staleness the generated list exists to prevent, or being
 * folded into whichever project happens to be alphabetically first, which
 * would misstate what that project ships. This is a build-time failure in
 * practice rather than a shipped one: `GeneratedAttributionTest` calls this
 * over the real generated `shippedDependencies`, and that test is part of the
 * merge gate, the same drift-guard shape `ConfigSchemaContractTest` already
 * uses elsewhere in this codebase (see docs/architecture.md, "Config
 * schema"). A `groupId` this table does not know is caught there, before a
 * release, not after. Fixing it means adding a line to
 * [projectNameForGroup], not editing an existing one.
 */
fun List<Attribution>.groupIntoProjects(): List<AttributionProject> =
    groupBy { artifact ->
        projectNameForGroup(artifact.groupId)
            ?: error(
                "Attribution: no project claims groupId \"${artifact.groupId}\" " +
                    "(${artifact.coordinate}). Add it to projectNameForGroup in Attribution.kt.",
            )
    }.map { (name, artifacts) ->
        AttributionProject(
            name = name,
            license = artifacts.map { it.license }.distinct().joinToString(", "),
            artifactCount = artifacts.size,
        )
    }.sortedWith(compareByDescending<AttributionProject> { it.artifactCount }.thenBy { it.name })
