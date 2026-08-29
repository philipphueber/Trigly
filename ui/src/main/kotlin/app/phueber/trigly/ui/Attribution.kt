package app.phueber.trigly.ui

/**
 * One open source project this app ships a build of: its name, and the
 * licence it ships under.
 *
 * `shippedDependencies`, the list `AttributionScreen` reads, is generated
 * rather than declared in this file: `app.cash.licensee` walks `:ui`'s real
 * release runtime classpath, and the `generateAttributionList` task in
 * `ui/build.gradle.kts` turns its report into a `GeneratedAttribution.kt`
 * under `build/generated/`, built from this same class. Neither is checked
 * in: a committed snapshot would recreate exactly the staleness this design
 * exists to prevent. See docs/architecture.md, "Attribution".
 */
data class Attribution(val name: String, val license: String)
