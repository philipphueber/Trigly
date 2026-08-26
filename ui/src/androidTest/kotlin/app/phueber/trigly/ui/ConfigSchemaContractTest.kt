package app.phueber.trigly.ui

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.phueber.trigly.actions.actionFactories
import app.phueber.trigly.core.ActionFactory
import app.phueber.trigly.core.ComponentFactory
import app.phueber.trigly.core.ComponentRequirement
import app.phueber.trigly.core.ComponentTool
import app.phueber.trigly.core.ConfigField
import app.phueber.trigly.core.companionKeys
import app.phueber.trigly.core.NotificationController
import app.phueber.trigly.core.SpecialAccessKind
import app.phueber.trigly.core.Substitution
import app.phueber.trigly.core.TriggerFactory
import app.phueber.trigly.triggers.AlarmManagerScheduler
import app.phueber.trigly.triggers.triggerFactories
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Holds the config schema and the factories to each other.
 *
 * The realistic failure is drift: someone adds a config key, or renames one, and
 * forgets the schema. The editor then silently cannot set that value, which looks
 * like a broken component rather than a missing declaration. This checks every
 * factory in the app rather than a hand-maintained list, so a new component is
 * covered the moment it is registered.
 *
 * Lives in `:ui` because that is the only module that can see both `:triggers`
 * and `:actions`, and it needs a `Context` to build the factories at all.
 */
@RunWith(AndroidJUnit4::class)
class ConfigSchemaContractTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private val factories: List<ComponentFactory> =
        triggerFactories(context, AlarmManagerScheduler(context)) + actionFactories(context, NotificationController.Unavailable)

    @Test
    fun every_component_is_registered_and_reachable() {
        // Guards against the list itself going empty through a bad merge.
        assertTrue("expected a substantial component set", factories.size > 40)
    }

    @Test
    fun every_component_has_a_human_name_and_a_category() {
        val unnamed = factories.filter { it.displayName == it.type }
        assertTrue(
            "these components still show their raw type string in the picker: " +
                unnamed.map { it.type },
            unnamed.isEmpty(),
        )

        val uncategorised = factories.filter { it.category == "Other" }
        assertTrue(
            "these components would be grouped under \"Other\": " +
                uncategorised.map { it.type },
            uncategorised.isEmpty(),
        )
    }

    @Test
    fun every_component_matched_against_a_notification_offers_the_inspector() {
        // The contract, not a list of four names: a component whose configuration
        // is *matched against a notification* is configured against fields nobody
        // can see from outside — the posting package, what the platform calls the
        // title versus the text, what a button is named under its icon — and a
        // wrong guess there is a rule that silently never fires. So the way to
        // look at them belongs on that component's own block.
        //
        // Needing notification access is not the key, and `dnd_mode` is why: it
        // holds that access to read the interruption filter, reads no
        // notification, and has nothing the inspector could help anyone fill in.
        // The key is a matcher field held by a component that can see
        // notifications at all — a text pattern on an SMS trigger is not one of
        // these, because it never had the access.
        val missing = factories.filter { factory ->
            val needsListener = factory.requirements.any {
                it is ComponentRequirement.SpecialAccess &&
                    it.kind == SpecialAccessKind.NOTIFICATION_LISTENER
            }
            val matchesContent = factory.configFields.any {
                it is ConfigField.TextPattern ||
                    it is ConfigField.AppPackage ||
                    it is ConfigField.NotificationButton
            }
            needsListener && matchesContent &&
                ComponentTool.InspectNotifications !in factory.toolsFor(emptyMap())
        }
        assertTrue(
            "these are matched against notifications but offer no way to see one: " +
                missing.map { it.type },
            missing.isEmpty(),
        )
    }

    @Test
    fun no_trigger_offers_to_be_run() {
        // `Test` runs a component on demand, which only an action can do — the
        // editor draws it only where it has something to run, so a trigger that
        // declared it would produce nothing at all. Caught here rather than left
        // to render as an absence, since a tool that silently does not appear is
        // the same class of bug as a rule that silently never fires.
        val offenders = factories
            .filterIsInstance<TriggerFactory>()
            .filter { ComponentTool.Test in it.toolsFor(emptyMap()) }
        assertTrue(
            "a trigger cannot be run on demand: " + offenders.map { it.type },
            offenders.isEmpty(),
        )
    }

    @Test
    fun no_component_declares_the_same_config_key_twice() {
        val offenders = factories.mapNotNull { factory ->
            val duplicates = factory.configFields
                .groupingBy { it.key }
                .eachCount()
                .filterValues { it > 1 }
                .keys
            if (duplicates.isEmpty()) null else "${factory.type}: $duplicates"
        }

        assertTrue("duplicate config keys: $offenders", offenders.isEmpty())
    }

    /**
     * Guards the picker's own contract, per `docs/variables.md` section 4: a
     * blank label or sample is not caught by any factory's own `create()`, since
     * a [app.phueber.trigly.core.VariableSpec] never reaches one. It is read
     * only by the editor, so this test is the only place that would ever notice.
     */
    @Test
    fun every_variable_spec_has_a_key_a_label_and_a_sample() {
        val offenders = mutableListOf<String>()
        factories.forEach { factory ->
            factory.variables.forEach { spec ->
                val prefix = "${factory.type}.${spec.key}"
                if (spec.key.isBlank()) offenders += "${factory.type} declares a blank variable key"
                if (spec.label.isBlank()) offenders += "$prefix has a blank label"
                if (spec.sample.isBlank()) offenders += "$prefix has a blank sample"
            }
        }
        assertTrue(offenders.joinToString("; "), offenders.isEmpty())
    }

    @Test
    fun no_factory_declares_the_same_variable_key_twice() {
        val offenders = factories.mapNotNull { factory ->
            val duplicates = factory.variables
                .groupingBy { it.key }
                .eachCount()
                .filterValues { it > 1 }
                .keys
            if (duplicates.isEmpty()) null else "${factory.type}: $duplicates"
        }

        assertTrue("duplicate variable keys: $offenders", offenders.isEmpty())
    }

    /**
     * The editor draws a variable picker only on [ConfigField.Text]. See
     * `ConfigFieldEditor`'s `SubstitutableTextField`. A number, a duration, a
     * choice or any other kind declaring a [Substitution] would be a capability
     * the engine can act on and the editor can never reach, which is exactly
     * the silent gap this contract test exists to catch. Binding a typed field
     * to a variable is a different mechanism: `docs/variables.md` section
     * 6, P7. It is not built yet.
     */
    @Test
    fun a_substitutable_field_is_always_a_text_field() {
        val offenders = factories.flatMap { factory ->
            factory.configFields
                .filter { it.substitution != Substitution.NONE && it !is ConfigField.Text }
                .map { "${factory.type}.${it.key}" }
        }
        assertTrue(
            "these declare a substitution but are not a text field: $offenders",
            offenders.isEmpty(),
        )
    }

    @Test
    fun every_choice_offers_options_and_a_valid_default() {
        val offenders = mutableListOf<String>()

        factories.forEach { factory ->
            factory.configFields.filterIsInstance<ConfigField.Choice>().forEach { choice ->
                if (choice.options.isEmpty()) {
                    offenders += "${factory.type}.${choice.key} has no options"
                }
                val default = choice.default
                if (default != null && choice.options.none { it.value == default }) {
                    offenders += "${factory.type}.${choice.key} defaults to '$default', " +
                        "which is not one of its options"
                }
            }
        }

        assertTrue(offenders.joinToString("; "), offenders.isEmpty())
    }

    /**
     * The important one: a component built from nothing but its own declared
     * schema must be accepted by its own factory.
     *
     * If a factory requires a key the schema does not declare, the editor cannot
     * produce a saveable rule for it — and this fails here rather than at the
     * moment a user tries.
     */
    @Test
    fun a_component_built_from_its_declared_schema_is_accepted_by_its_factory() {
        val failures = mutableListOf<String>()

        factories.forEach { factory ->
            val config = sampleConfig(factory.configFields)
            val outcome = runCatching {
                when (factory) {
                    is TriggerFactory -> factory.create(config)
                    is ActionFactory -> factory.create(config)
                    else -> error("unknown factory kind for ${factory.type}")
                }
            }
            outcome.exceptionOrNull()?.let {
                failures += "${factory.type} rejected its own schema " +
                    "($config): ${it.message}"
            }
        }

        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    /**
     * A plausible value for each declared field. Fields whose blankness is
     * meaningful are still filled here — the point is to exercise the accepting
     * path, and an optional field being present is always valid.
     *
     * Built as a list of pairs rather than one entry per field, because three
     * kinds own a **second** config key and a factory that requires both would
     * otherwise be handed half an answer and fail for the wrong reason.
     */
    private fun sampleConfig(fields: List<ConfigField>): Map<String, String> =
        fields.flatMap { field -> extraKeysFor(field) + (field.key to when (field) {
                is ConfigField.Choice ->
                    field.default ?: field.options.first().value

                is ConfigField.Flag -> field.default.toString()

                is ConfigField.Number ->
                    (field.default ?: field.min ?: 1L).toString()

                // A slider always has all three, and its default is by definition
                // inside the range — the data class checks that at construction.
                is ConfigField.Slider -> field.default.toString()

                is ConfigField.Decimal ->
                    (field.default ?: field.min ?: 1.0).toString()

                // Any opaque value: the editor mints a real one, and a factory
                // must accept whatever it finds rather than validating a shape
                // it did not choose.
                is ConfigField.GeneratedId -> "generated-sample-id"

                is ConfigField.AppPackage -> context.packageName

                // Any id: this exercises the accepting path, and the action's
                // "no such rule" case is a *runtime* outcome, not a config the
                // factory should refuse. A factory that rejected an id for not
                // existing yet would make an exported rule unimportable.
                is ConfigField.RuleRef -> "rule-sample-id"

                // A real content: URI shape, because `play_alert` refuses a sound
                // URI that is not local — a bare "sample" would fail its factory
                // for the right reason and make this test look like a schema bug.
                is ConfigField.SoundUri -> "content://media/internal/audio/media/1"

                // The shape the picker produces and the trigger stores.
                is ConfigField.BluetoothAddress -> "00:11:22:33:44:55"

                // One entry off the curated grid, exercising the same accepting
                // path a picked value would.
                is ConfigField.Emoji -> "🔔"

                // Stored in milliseconds whatever unit the editor showed.
                is ConfigField.Duration ->
                    (field.defaultMillis ?: 1_000L).toString()

                // A real instant, and a real hour: the calendar and alarm
                // factories parse these, so a token value would fail for the
                // right reason and read as a schema bug.
                is ConfigField.Timestamp -> "1787900400000"
                is ConfigField.TimeOfDay -> "8"
                is ConfigField.Coordinates -> "52.520008"

                // A captured button records what it said.
                is ConfigField.NotificationButton -> "Snooze"

                is ConfigField.Text -> "sample"

                // Valid as both a substring and a regex, so the sample exercises
                // the accepting path whichever mode a factory defaults to.
                is ConfigField.TextPattern -> "sample"
            })
        }.toMap()

    /**
     * The companion keys a kind owns, from the one declaration in `:core` rather
     * than a copy here — a second list is how a two-key field ends up
     * half-populated and its factory blamed for refusing it.
     *
     * A `TextPattern`'s mode is skipped deliberately: absent reads as `contains`,
     * and leaving it out is the case every older rule exercises.
     */
    private fun extraKeysFor(field: ConfigField): List<Pair<String, String>> =
        field.companionKeys()
            .filterNot { field is ConfigField.TextPattern && it == field.modeKey }
            .map { key -> key to sampleCompanion(field, key) }

    private fun sampleCompanion(field: ConfigField, key: String): String = when {
        field is ConfigField.TimeOfDay && key == field.minuteKey -> "30"
        field is ConfigField.Coordinates && key == field.longitudeKey -> "13.404954"
        field is ConfigField.NotificationButton && key == field.semanticKey -> "0"
        field is ConfigField.NotificationButton && key == field.packageKey -> context.packageName
        else -> "sample"
    }
}
