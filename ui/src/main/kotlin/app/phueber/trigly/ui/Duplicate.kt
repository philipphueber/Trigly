package app.phueber.trigly.ui

import app.phueber.trigly.core.Registry
import app.phueber.trigly.core.Rule
import app.phueber.trigly.core.RuleJson
import app.phueber.trigly.core.withFreshGeneratedIds

/**
 * A copy of this rule, ready to be edited into the one the person actually
 * wants.
 *
 * Two decisions are worth stating, because a copy is not a memcpy of a rule.
 *
 * **A generated id is minted again, not copied.** Some config values identify
 * the rule to something outside it. A home screen shortcut carries the
 * `shortcutId` its trigger listens for, and that trigger fires on any tap whose
 * id matches. Copy the value and one shortcut starts two rules, which is a
 * fault nobody would look for in a duplicate. See [withFreshGeneratedIds] for
 * the walk, which lives in `:core` because a second caller needs it: importing
 * a rule from a file mints fresh ids for exactly the same reason, and for a
 * stronger one. A file publishes the id to whoever holds a copy of the file,
 * not only to this device.
 *
 * **The copy arrives switched off.** Duplicating an enabled rule that acts on
 * the world means two rules doing the same thing from the moment of the tap,
 * before anyone has changed the part they meant to change. Off is the state a
 * person can correct with one tap, in the direction they choose. On is a state
 * the app chose for them and may already have acted on.
 *
 * **The name says it is a copy.** Two rules with one name in a list is the
 * problem the list exists to solve. Import does not do this. See
 * [RulesViewModel.import]: a name somebody else chose is not this app's to
 * edit. The folder is kept, because a copy belongs where the original does
 * until told otherwise.
 */
internal fun Rule.duplicated(registry: Registry): Rule = withFreshGeneratedIds(registry).copy(
    id = RuleJson.newId(),
    name = "$name copy",
    enabled = false,
)
