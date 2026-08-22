package app.phueber.trigly.core

/**
 * [TriggerEvent.payload] keys that cross a module boundary — written by a
 * trigger in `:triggers` and read by an action in `:actions`.
 *
 * Most payload keys are private business between a trigger and whoever reads its
 * event, and stay as constants on the trigger. These are different: an action
 * depends on the exact string, and the two modules cannot see each other. Naming
 * them here makes the contract greppable instead of a string literal repeated in
 * two places that silently drift apart.
 */
object SharedPayloadKeys {

    /**
     * The `StatusBarNotification` key, emitted by the notification-posted
     * trigger and consumed by the dismiss and button actions. Opaque, generated
     * by the posting app, and the only way to address a specific notification.
     */
    const val NOTIFICATION_KEY = "notificationKey"
}
