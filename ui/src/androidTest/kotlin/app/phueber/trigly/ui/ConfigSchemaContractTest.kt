package app.phueber.trigly.ui

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.phueber.trigly.actions.actionFactories
import app.phueber.trigly.core.ActionFactory
import app.phueber.trigly.core.ComponentFactory
import app.phueber.trigly.core.ConfigField
import app.phueber.trigly.core.NotificationController
import app.phueber.trigly.core.TriggerFactory
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
        triggerFactories(context) + actionFactories(context, NotificationController.Unavailable)

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
     */
    private fun sampleConfig(fields: List<ConfigField>): Map<String, String> =
        fields.associate { field ->
            field.key to when (field) {
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

                is ConfigField.AppPackage -> context.packageName

                is ConfigField.Text -> "sample"

                // Valid as both a substring and a regex, so the sample exercises
                // the accepting path whichever mode a factory defaults to.
                is ConfigField.TextPattern -> "sample"
            }
        }
}
