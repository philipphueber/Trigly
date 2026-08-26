package app.phueber.trigly.ui

import android.content.ComponentName
import android.content.Context
import android.content.pm.ActivityInfo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The manifest facts that decide whether a shortcut fires a rule or opens the
 * app.
 *
 * A tap on a pinned shortcut used to bring Trigly to the front, but only when
 * the app had been used recently enough to still own a task, which is why it
 * read as intermittent rather than as broken. The cause was task affinity: the
 * shortcut activity shared the app's, so a launcher starting it found the
 * existing task, brought the whole thing forward, and left `MainActivity` on
 * screen once this activity finished a millisecond later.
 *
 * **What this test proves and what it cannot.** It proves the declarations that
 * cause that behaviour are still what they should be. It cannot prove the
 * behaviour itself: routing a start into an existing task is a decision the
 * system makes with a real launcher, and no instrumented test has a launcher or
 * a pinned shortcut. That half was checked by hand on API 30 and API 35, by
 * opening the app, pressing home, and starting this activity the way a launcher
 * does:
 *
 * ```
 * adb shell am start -n app.phueber.trigly/app.phueber.trigly.ui.ShortcutTargetActivity \
 *   --es app.phueber.trigly.EXTRA_SHORTCUT_ID <id>
 * adb shell dumpsys window | grep mCurrentFocus
 * ```
 *
 * Before the fix the focus was `MainActivity`; after it, the launcher. Note the
 * fully qualified class name: `applicationId` is `app.phueber.trigly` while the
 * classes live in `app.phueber.trigly.ui`, so the usual `pkg/.Activity`
 * shorthand names a class that does not exist and `am start` fails with "Error
 * type 3" while a careless probe keeps measuring nothing.
 */
@RunWith(AndroidJUnit4::class)
class ShortcutTargetActivityTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    private fun infoFor(name: String): ActivityInfo =
        context.packageManager.getActivityInfo(ComponentName(context.packageName, name), 0)

    private val target get() = infoFor(ShortcutTargetActivity::class.java.name)

    /**
     * The assertion is against `MainActivity`'s affinity rather than against a
     * literal, because *sharing the app's affinity* is the thing that caused
     * the bug. An empty `android:taskAffinity` is reported as null by some
     * platform versions and as an empty string by others, and pinning either
     * spelling would make this a test about a representation instead of about
     * the property that matters.
     */
    @Test
    fun the_shortcut_target_does_not_share_the_app_task() {
        val main = infoFor(MainActivity::class.java.name)

        assertNotEquals(
            "the shortcut activity must not join the app's own task",
            main.taskAffinity,
            target.taskAffinity,
        )
        assertTrue(
            "an empty affinity is what gives it a task of its own, was " +
                "'${target.taskAffinity}'",
            target.taskAffinity.isNullOrEmpty(),
        )
    }

    /**
     * Both were already right before the affinity was fixed, and both stay for
     * their own reasons: a shortcut tap is not somewhere a person returns to
     * with Back, and it is not something they should find in recents.
     */
    @Test
    fun the_shortcut_target_leaves_nothing_behind() {
        assertTrue(
            "noHistory keeps Back from returning to a finished shortcut tap",
            target.flags and ActivityInfo.FLAG_NO_HISTORY != 0,
        )
        assertTrue(
            "a shortcut tap has no business in recents",
            target.flags and ActivityInfo.FLAG_EXCLUDE_FROM_RECENTS != 0,
        )
    }

    /**
     * A launcher is a different app with a different uid, so it can only start
     * this by component name if it is exported. Asserted because the activity's
     * own KDoc leans on it: it treats a missing id as ordinary traffic
     * precisely because anything on the device can send this intent.
     */
    @Test
    fun the_shortcut_target_is_reachable_by_a_launcher() {
        assertTrue("a launcher cannot start an unexported activity", target.exported)
    }
}
