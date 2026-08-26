package app.phueber.trigly.actions

import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
import android.provider.CalendarContract
import androidx.core.net.toUri
import app.phueber.trigly.core.Action
import app.phueber.trigly.core.ActionFactory
import app.phueber.trigly.core.ActionResult
import app.phueber.trigly.core.ConfigField
import app.phueber.trigly.core.Substitution
import app.phueber.trigly.core.TriggerEvent

/**
 * Actions that hand work to another app through an intent.
 *
 * All of them are *compose*, not *send*: they open the user's own mail, SMS or
 * calendar app with the fields filled in, and the user confirms. That is a
 * deliberate design line, not a limitation to route around — sending an SMS
 * silently needs the Play-restricted `SEND_SMS` permission, and an automation
 * app that can send messages without confirmation is a different and much more
 * dangerous product.
 *
 * All of them also depend on the background-activity-start exemption; see
 * [launchForRule].
 */

/** Opens the mail app with recipient, subject and body filled in. */
class ComposeEmailAction(
    private val context: Context,
    private val to: String,
    private val subject: String?,
    private val body: String?,
) : Action {

    override suspend fun execute(event: TriggerEvent): ActionResult {
        val intent = Intent(Intent.ACTION_SENDTO, "mailto:$to".toUri()).apply {
            subject?.let { putExtra(Intent.EXTRA_SUBJECT, it) }
            body?.let { putExtra(Intent.EXTRA_TEXT, it) }
        }
        return context.launchForRule(intent)
    }

    companion object {
        const val TYPE = "compose_email"
        const val CONFIG_TO = "to"
        const val CONFIG_SUBJECT = "subject"
        const val CONFIG_BODY = "body"
    }
}

class ComposeEmailActionFactory(private val context: Context) : ActionFactory {
    override val type = ComposeEmailAction.TYPE

    override val displayName = "Compose an email"
    override val category = ActionCategory.HAND_OFF

    override val configFields = listOf(
        ConfigField.Text(
            key = ComposeEmailAction.CONFIG_TO,
            label = "To",
            required = true,
            help = RECIPIENT_SUBSTITUTION_HELP,
            substitution = Substitution.TEXT,
        ),
        ConfigField.Text(
            key = ComposeEmailAction.CONFIG_SUBJECT,
            label = "Subject",
            substitution = Substitution.TEXT,
        ),
        messageText(ComposeEmailAction.CONFIG_BODY, "Body", required = false),
    )

    override val requirements = ACTIVITY_START_REQUIREMENTS

    override val warning: String =
        "This action opens your mail app with the fields filled in. You still " +
            "press send. $BACKGROUND_START_WARNING"

    override fun create(config: Map<String, String>): Action = ComposeEmailAction(
        context = context,
        to = config[ComposeEmailAction.CONFIG_TO]
            ?: error("$type needs '${ComposeEmailAction.CONFIG_TO}'"),
        subject = config[ComposeEmailAction.CONFIG_SUBJECT],
        body = config[ComposeEmailAction.CONFIG_BODY],
    )
}

/** Opens the SMS app with the number and message filled in. The user presses send. */
class ComposeSmsAction(
    private val context: Context,
    private val to: String,
    private val body: String?,
) : Action {

    override suspend fun execute(event: TriggerEvent): ActionResult {
        val intent = Intent(Intent.ACTION_SENDTO, "smsto:$to".toUri()).apply {
            body?.let { putExtra("sms_body", it) }
        }
        return context.launchForRule(intent)
    }

    companion object {
        const val TYPE = "compose_sms"
        const val CONFIG_TO = "to"
        const val CONFIG_BODY = "body"
    }
}

class ComposeSmsActionFactory(private val context: Context) : ActionFactory {
    override val type = ComposeSmsAction.TYPE

    override val displayName = "Compose a text message"
    override val category = ActionCategory.HAND_OFF

    override val configFields = listOf(
        ConfigField.Text(
            key = ComposeSmsAction.CONFIG_TO,
            label = "To",
            required = true,
            help = RECIPIENT_SUBSTITUTION_HELP,
            substitution = Substitution.TEXT,
        ),
        messageText(ComposeSmsAction.CONFIG_BODY, "Message", required = false),
    )

    override val requirements = ACTIVITY_START_REQUIREMENTS

    override val warning: String =
        "This action opens your messaging app with the message ready. You " +
            "still press send. A silent send needs a permission that Google " +
            "restricts. $BACKGROUND_START_WARNING"

    override fun create(config: Map<String, String>): Action = ComposeSmsAction(
        context = context,
        to = config[ComposeSmsAction.CONFIG_TO]
            ?: error("$type needs '${ComposeSmsAction.CONFIG_TO}'"),
        body = config[ComposeSmsAction.CONFIG_BODY],
    )
}

/**
 * Sets an alarm in the user's clock app.
 *
 * `SKIP_UI` asks the clock app to create it without opening — the one case here
 * where the user is not asked, because setting an alarm is what the rule
 * explicitly said to do and is trivially reversible.
 */
class SetAlarmAction(
    private val context: Context,
    private val hour: Int,
    private val minute: Int,
    private val label: String?,
) : Action {

    override suspend fun execute(event: TriggerEvent): ActionResult {
        val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, hour)
            putExtra(AlarmClock.EXTRA_MINUTES, minute)
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            label?.let { putExtra(AlarmClock.EXTRA_MESSAGE, it) }
        }
        return context.launchForRule(intent)
    }

    companion object {
        const val TYPE = "set_alarm"
        const val CONFIG_HOUR = "hour"
        const val CONFIG_MINUTE = "minute"
        const val CONFIG_LABEL = "label"
    }
}

class SetAlarmActionFactory(private val context: Context) : ActionFactory {
    override val type = SetAlarmAction.TYPE

    override val displayName = "Set an alarm"
    override val category = ActionCategory.HAND_OFF

    override val configFields = listOf(
        ConfigField.TimeOfDay(
            key = SetAlarmAction.CONFIG_HOUR,
            label = "At",
            required = true,
            minuteKey = SetAlarmAction.CONFIG_MINUTE,
        ),
        ConfigField.Text(
            key = SetAlarmAction.CONFIG_LABEL,
            label = "Label",
            substitution = Substitution.TEXT,
        ),
    )

    override val requirements = ACTIVITY_START_REQUIREMENTS

    override val warning: String = BACKGROUND_START_WARNING

    override fun create(config: Map<String, String>): Action {
        val hour = config[SetAlarmAction.CONFIG_HOUR]?.toIntOrNull()
            ?: error("$type needs '${SetAlarmAction.CONFIG_HOUR}'")
        val minute = config[SetAlarmAction.CONFIG_MINUTE]?.toIntOrNull() ?: 0

        require(hour in 0..23) { "hour must be 0-23, was $hour" }
        require(minute in 0..59) { "minute must be 0-59, was $minute" }

        return SetAlarmAction(context, hour, minute, config[SetAlarmAction.CONFIG_LABEL])
    }
}

/**
 * Opens the calendar app's new-event screen, pre-filled.
 *
 * Writing the event directly would need `WRITE_CALENDAR`; the insert intent
 * needs no permission at all, and the user sees what is being added.
 */
class AddCalendarEventAction(
    private val context: Context,
    private val title: String,
    private val beginMillis: Long?,
    private val endMillis: Long?,
) : Action {

    override suspend fun execute(event: TriggerEvent): ActionResult {
        val intent = Intent(Intent.ACTION_INSERT)
            .setData(CalendarContract.Events.CONTENT_URI)
            .putExtra(CalendarContract.Events.TITLE, title)
            .apply {
                beginMillis?.let {
                    putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, it)
                }
                endMillis?.let {
                    putExtra(CalendarContract.EXTRA_EVENT_END_TIME, it)
                }
            }
        return context.launchForRule(intent)
    }

    companion object {
        const val TYPE = "add_calendar_event"
        const val CONFIG_TITLE = "title"
        const val CONFIG_BEGIN_MILLIS = "beginMillis"
        const val CONFIG_END_MILLIS = "endMillis"
    }
}

class AddCalendarEventActionFactory(private val context: Context) : ActionFactory {
    override val type = AddCalendarEventAction.TYPE

    override val displayName = "Add a calendar event"
    override val category = ActionCategory.HAND_OFF

    override val configFields = listOf(
        ConfigField.Text(
            key = AddCalendarEventAction.CONFIG_TITLE,
            label = "Title",
            required = true,
            substitution = Substitution.TEXT,
        ),
        ConfigField.Timestamp(
            key = AddCalendarEventAction.CONFIG_BEGIN_MILLIS,
            label = "Starts at",
            blankMeaning = "The calendar app chooses",
            help = "This is a fixed date and time. A rule that fires more than " +
                "once keeps proposing this same moment. After the first run, " +
                "that moment is in the past. For a repeating rule, leave this " +
                "field blank.",
        ),
        ConfigField.Timestamp(
            key = AddCalendarEventAction.CONFIG_END_MILLIS,
            label = "Ends at",
            blankMeaning = "The calendar app chooses",
        ),
    )

    override val requirements = ACTIVITY_START_REQUIREMENTS

    override val warning: String =
        "This action opens the calendar's new-event screen so you can confirm. " +
            "$BACKGROUND_START_WARNING"

    override fun create(config: Map<String, String>): Action = AddCalendarEventAction(
        context = context,
        title = config[AddCalendarEventAction.CONFIG_TITLE]
            ?: error("$type needs '${AddCalendarEventAction.CONFIG_TITLE}'"),
        beginMillis = config[AddCalendarEventAction.CONFIG_BEGIN_MILLIS]?.toLongOrNull(),
        endMillis = config[AddCalendarEventAction.CONFIG_END_MILLIS]?.toLongOrNull(),
    )
}
