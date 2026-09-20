package app.phueber.trigly.ui

import android.content.Context
import android.content.pm.PackageManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.phueber.trigly.actions.Camera2Torch
import app.phueber.trigly.actions.TorchResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The real torch, on a device, because nothing about `CameraManager` can be
 * asked on the JVM.
 *
 * **What this cannot prove on the emulators this project tests on, stated
 * first.** They report no flash unit, so `FEATURE_CAMERA_FLASH` is absent and
 * every run here takes the second branch. That checks the case a phone without
 * a torch produces, which is worth checking, and it checks nothing about light.
 * Whether `setTorchMode` really lights the flash, whether a brightness below
 * full does anything on a unit with more than one level, and how the light
 * behaves while another app holds the camera, are all questions only a phone
 * with a real flash unit can answer. Run this class on one before trusting any
 * of that.
 *
 * **What the first branch proves, on a phone that has a flash unit, is the one
 * thing that cannot be read out of a stub jar.** Trigly declares no `CAMERA`
 * permission anywhere, and the test asserts that before it switches anything.
 * So an `Ok` from `turnOn` on a real phone is the measurement behind the claim
 * in `FlashlightAction`'s KDoc: the torch needs no permission at all. The
 * SDK's permission database says the same thing, and a database is not a
 * device.
 *
 * It lives in `:ui` for the reason `ConfigSchemaContractTest` does: `:actions`
 * carries no instrumented test dependencies, and `:ui` is the module that
 * already runs on a device.
 */
@RunWith(AndroidJUnit4::class)
class FlashlightOnDeviceTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext

    private val hasFlash: Boolean
        get() = context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_FLASH)

    @Test
    fun the_app_holds_no_camera_permission() {
        // The premise of the test below. Without this line an Ok from the
        // torch would prove nothing about what the torch needs.
        assertNotEquals(
            "Trigly must not ask for the camera to switch a torch",
            PackageManager.PERMISSION_GRANTED,
            context.checkSelfPermission(android.Manifest.permission.CAMERA),
        )
    }

    @Test
    fun the_torch_answers_what_this_device_reports_about_its_flash_unit() {
        val torch = Camera2Torch(context)

        val lit = torch.turnOn()

        if (hasFlash) {
            // The light really comes on here, for as long as this test takes.
            assertEquals(TorchResult.Ok, lit)
            assertEquals(TorchResult.Ok, torch.turnOff())
        } else {
            // A phone with no flash unit must report it rather than throw. The
            // pickers never offer the action on such a device, because the
            // factories declare FEATURE_CAMERA_FLASH, so this is the path a
            // rule imported from a phone that has one would take.
            assertTrue("expected a stated refusal, got $lit", lit is TorchResult.Failed)
        }
    }

    @Test
    fun switching_a_torch_that_is_already_off_is_harmless() {
        // What `BlinkFlashlightAction`'s `finally` does on every path,
        // including the one where the pattern already ended dark.
        val torch = Camera2Torch(context)

        val first = torch.turnOff()
        val second = torch.turnOff()

        assertEquals(first, second)
    }
}
