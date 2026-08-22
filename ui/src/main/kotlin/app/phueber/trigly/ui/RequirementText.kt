package app.phueber.trigly.ui

import app.phueber.trigly.core.ComponentRequirement

/**
 * Human wording for a requirement.
 *
 * Says what the user must *do*, not what the API is called: "Needs notification
 * access" is actionable, `SpecialAccess(NOTIFICATION_LISTENER)` is not. Pure, so
 * it is unit-testable and holds no Android dependency.
 */
fun ComponentRequirement.describe(): String = when (this) {
    is ComponentRequirement.RuntimePermission ->
        "Needs the ${permission.substringAfterLast('.').lowercase().replace('_', ' ')} permission"

    is ComponentRequirement.SpecialAccess ->
        "Needs ${kind.label.lowercase()}, granted in system settings"

    is ComponentRequirement.SystemFeature ->
        "This device has no ${feature.substringAfterLast('.').replace('_', ' ')}"

    is ComponentRequirement.MinApiLevel ->
        "Needs Android API $api or newer"

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
