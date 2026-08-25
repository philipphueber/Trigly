package app.phueber.trigly.ui

import app.phueber.trigly.core.ComponentRequirement

/**
 * The marketing version name for each API level this app actually ships
 * against — `minSdk` 26 through `compileSdk`/`targetSdk` 35, per the
 * `build.gradle.kts` files. A phone's own Settings screen shows "Android 14,"
 * never "API 34," so that's the name a person recognises; nobody has ever
 * seen their phone call itself an API level.
 *
 * Deliberately only the range this project targets — extending it to cover
 * hypothetical future levels would mean guessing at names Google hasn't
 * announced.
 */
private val ANDROID_VERSION_NAMES = mapOf(
    26 to "Android 8.0",
    27 to "Android 8.1",
    28 to "Android 9",
    29 to "Android 10",
    30 to "Android 11",
    31 to "Android 12",
    32 to "Android 12L",
    33 to "Android 13",
    34 to "Android 14",
    35 to "Android 15",
)

/**
 * Written out rather than imported from `android.Manifest`, so this file keeps
 * the promise the doc below makes: pure, unit-testable, no Android dependency.
 * The value is a platform constant and does not change.
 */
private const val ACCESS_BACKGROUND_LOCATION = "android.permission.ACCESS_BACKGROUND_LOCATION"

/**
 * Human wording for a requirement.
 *
 * Says what the user must *do*, not what the API is called: "Needs notification
 * access" is actionable, `SpecialAccess(NOTIFICATION_LISTENER)` is not. Pure, so
 * it is unit-testable and holds no Android dependency.
 */
fun ComponentRequirement.describe(): String = when (this) {
    // Background location gets words of its own. The generated wording would be
    // "Needs the access background location permission", which names an API the
    // user never sees and does not say what to do about it. The screen that
    // grants it has one name for the setting, and that is the name to use.
    is ComponentRequirement.RuntimePermission ->
        if (permission == ACCESS_BACKGROUND_LOCATION) {
            "Needs location set to \"Allow all the time\""
        } else {
            "Needs the ${permission.substringAfterLast('.').lowercase().replace('_', ' ')} permission"
        }

    is ComponentRequirement.SpecialAccess ->
        "Needs ${kind.label.lowercase()}, granted in system settings"

    is ComponentRequirement.SystemFeature ->
        "This device has no ${feature.substringAfterLast('.').replace('_', ' ')}"

    is ComponentRequirement.MinApiLevel -> {
        // Lead with the name a person can recognise; keep the number in
        // parentheses because it's the one a bug report actually needs. A
        // level outside the table (nothing in this codebase declares one)
        // falls back to the old, plainly-numeric wording rather than a name
        // that would be a guess.
        val name = ANDROID_VERSION_NAMES[api]
        if (name != null) "Needs $name (API $api) or newer" else "Needs Android API $api or newer"
    }

    is ComponentRequirement.PolicyRestricted -> reason
}

/**
 * Whether tapping through to a settings screen can fix this. A missing sensor
 * or an old Android version cannot be resolved by the user, so offering a button
 * would be a lie.
 */
val ComponentRequirement.isResolvable: Boolean
    get() = this is ComponentRequirement.RuntimePermission ||
        this is ComponentRequirement.SpecialAccess
