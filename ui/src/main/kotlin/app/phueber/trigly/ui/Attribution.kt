package app.phueber.trigly.ui

/**
 * One Maven artifact this app's release build ships: its coordinate, split
 * into `groupId` and `artifactId`, the licence it ships under, and the URL
 * its `pom.xml` names as its source repository (`scm.url` in licensee's
 * report), or null if it names none.
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
data class Attribution(val groupId: String, val artifactId: String, val license: String, val scmUrl: String?) {

    /** `groupId:artifactId`, for messages; nothing renders this directly. */
    val coordinate: String get() = "$groupId:$artifactId"
}

/**
 * One project the Attribution screen credits: a project name, the licence it
 * ships under, how many of the artifacts on the real release classpath came
 * from it, and the URL a tap on its row opens, or null if none of its
 * artifacts named one.
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
    val url: String?,
)

/**
 * Maps an artifact's `groupId` to the project name [groupIntoProjects] credits
 * it under. Null means no project claims that groupId, which
 * [groupIntoProjects] turns into a fallback rather than a failure; see there
 * for why.
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
 * The URL a tap on a project's row opens: the most common non-null `scmUrl`
 * among its artifacts, not the first one.
 *
 * "First" is the trap [projectNameForGroup] already warns about: four
 * AndroidX artifacts carry a stale `http://source.android.com` URL, so
 * picking whichever artifact `groupBy` happens to list first would sometimes
 * send AndroidX's row to a page that has not described the project in years.
 * Picking the most common URL instead means one stale outlier cannot win
 * against the 84 AndroidX artifacts that agree on the real one. A tie is
 * broken by the URL's own text, smallest first, so the choice never depends
 * on map or list iteration order.
 */
private fun mostCommonUrl(artifacts: List<Attribution>): String? =
    artifacts.mapNotNull { it.scmUrl }
        .groupingBy { it }
        .eachCount()
        .entries
        .maxWithOrNull(compareBy<Map.Entry<String, Int>> { it.value }.thenByDescending { it.key })
        ?.key

/**
 * Folds the generated per-artifact list into the projects `AttributionScreen`
 * actually shows: one entry per project, carrying how many artifacts of it
 * this build ships and the URL a tap on its row opens, so a reader is not
 * left thinking Trigly ships one file of AndroidX.
 *
 * An artifact whose `groupId` matches no project is shown under its own
 * `groupId` as the project name, rather than being dropped from the page or
 * folded into whichever project happens to be alphabetically first. This is
 * deliberately a soft degrade and not a throw: `AttributionHost` calls this
 * to render the screen, so throwing here would crash the app the moment
 * somebody opened Used Components, the one screen whose whole job is to
 * show them the licence they came for. A row reading a raw group id is
 * ugly but harmless, the licence on it is still correct, and nothing has
 * vanished or been mislabelled as belonging to a project it does not.
 *
 * The strictness lives in the test instead. `GeneratedAttributionTest`'s
 * `nothing is unmapped` calls this over the real generated
 * `shippedDependencies` and asserts every resulting name is one of the five
 * known projects, so a `groupId` this table does not know fails the merge
 * gate, before a release, the same drift-guard shape `ConfigSchemaContractTest`
 * already uses elsewhere in this codebase (see docs/architecture.md, "Config
 * schema"). Fixing it means adding a line to [projectNameForGroup], not
 * editing an existing one.
 */
fun List<Attribution>.groupIntoProjects(): List<AttributionProject> =
    groupBy { artifact -> projectNameForGroup(artifact.groupId) ?: artifact.groupId }
        .map { (name, artifacts) ->
            AttributionProject(
                name = name,
                license = artifacts.map { it.license }.distinct().joinToString(", "),
                artifactCount = artifacts.size,
                url = mostCommonUrl(artifacts),
            )
        }.sortedWith(compareByDescending<AttributionProject> { it.artifactCount }.thenBy { it.name })
