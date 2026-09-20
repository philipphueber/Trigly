package app.phueber.trigly.actions

import app.phueber.trigly.core.ActionResult
import app.phueber.trigly.core.ComponentRequirement
import app.phueber.trigly.core.ConfigField
import app.phueber.trigly.core.TriggerEvent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Picking the camera whose flash is "the flashlight".
 *
 * All of it is pure, which is the point: the id list comes back in an order
 * nothing promises, so the preference has to be tested rather than observed
 * once on one phone. No emulator this project tests on has a flash unit at all,
 * so a device could not answer any of this.
 */
class PickTorchCameraTest {

    private fun camera(id: String, flash: Boolean = true, facing: CameraFacing = CameraFacing.BACK) =
        TorchCamera(id = id, hasFlash = flash, facing = facing)

    @Test
    fun `a device with no cameras has no flashlight`() {
        assertNull(pickTorchCamera(emptyList()))
    }

    @Test
    fun `a device whose cameras all lack a flash unit has no flashlight`() {
        val cameras = listOf(
            camera("0", flash = false),
            camera("1", flash = false, facing = CameraFacing.FRONT),
        )

        assertNull(pickTorchCamera(cameras))
    }

    @Test
    fun `a camera with no flash is never picked, whatever its position`() {
        val cameras = listOf(
            camera("0", flash = false),
            camera("1", flash = true),
        )

        assertEquals("1", pickTorchCamera(cameras))
    }

    @Test
    fun `the back camera wins even when a front one is listed first`() {
        // The whole reason this is a function rather than getCameraIdList()[0]:
        // the order of that list is not promised, and a front-facing flash is
        // not what anybody means by their torch.
        val cameras = listOf(
            camera("1", facing = CameraFacing.FRONT),
            camera("0", facing = CameraFacing.BACK),
        )

        assertEquals("0", pickTorchCamera(cameras))
    }

    @Test
    fun `the first back camera wins when several report a flash`() {
        // A phone with several rear lenses exposes each as its own id.
        val cameras = listOf(
            camera("0", facing = CameraFacing.BACK),
            camera("3", facing = CameraFacing.BACK),
        )

        assertEquals("0", pickTorchCamera(cameras))
    }

    @Test
    fun `a built-in camera beats an external one`() {
        val cameras = listOf(
            camera("2", facing = CameraFacing.EXTERNAL),
            camera("1", facing = CameraFacing.FRONT),
        )

        assertEquals("1", pickTorchCamera(cameras))
    }

    @Test
    fun `an unknown facing is still preferred over an external camera`() {
        val cameras = listOf(
            camera("2", facing = CameraFacing.EXTERNAL),
            camera("9", facing = CameraFacing.UNKNOWN),
        )

        assertEquals("9", pickTorchCamera(cameras))
    }

    @Test
    fun `an external camera is used when it is the only flash on the device`() {
        val cameras = listOf(camera("2", facing = CameraFacing.EXTERNAL))

        assertEquals("2", pickTorchCamera(cameras))
    }
}

/** The brightness arithmetic, and when it declines to use the newer API at all. */
class TorchStrengthTest {

    @Test
    fun `full brightness never asks for a level`() {
        // The case that decides whether this action needs Android 13. Full
        // brightness must stay on setTorchMode, which every supported phone has.
        assertNull(torchStrengthLevel(percent = 100, maxLevel = 5))
    }

    @Test
    fun `a flash unit with one level never asks for a level`() {
        assertNull(torchStrengthLevel(percent = 50, maxLevel = SINGLE_STRENGTH_LEVEL))
    }

    @Test
    fun `a percentage maps onto this unit's own maximum`() {
        assertEquals(1, torchStrengthLevel(percent = 50, maxLevel = 2))
        assertEquals(50, torchStrengthLevel(percent = 50, maxLevel = 100))
        assertEquals(2, torchStrengthLevel(percent = 40, maxLevel = 5))
    }

    @Test
    fun `a very low percentage still lights the torch`() {
        // Zero is not a brightness, it is off, and the mode field says that.
        assertEquals(1, torchStrengthLevel(percent = 1, maxLevel = 5))
    }

    @Test
    fun `brightness is read from config, defaulted to full`() {
        assertEquals(100, torchStrengthPercent(null))
        assertEquals(100, torchStrengthPercent(""))
        assertEquals(60, torchStrengthPercent("60"))
    }

    @Test
    fun `a brightness that is not a number reads as full`() {
        // It can only come from a hand-edited export, and full is the honest
        // reading of a file that does not say.
        assertEquals(100, torchStrengthPercent("bright"))
    }

    @Test
    fun `brightness is clamped into the slider's own range`() {
        assertEquals(1, torchStrengthPercent("0"))
        assertEquals(1, torchStrengthPercent("-20"))
        assertEquals(100, torchStrengthPercent("400"))
    }
}

/**
 * The order [Camera2Torch] calls the camera in, which is where two known device
 * faults are worked around.
 *
 * Both faults are silent on the devices that have them and invisible on every
 * device that does not, including every emulator here, which report no flash
 * unit at all. So the sequence is asserted rather than trusted, against a fake
 * camera, which is the only place it can be seen.
 */
class TorchCallOrderTest {

    @Test
    fun `full brightness is a single call`() {
        // The path the blink takes, twice per flash. A second binder call here
        // would be paid hundreds of times in one pattern.
        val unit = FakeFlashUnit(maxStrengthLevel = 100)

        assertEquals(TorchResult.Ok, Camera2Torch(unit).turnOn(100))

        assertEquals(listOf("switch(true)"), unit.calls)
    }

    @Test
    fun `a flash unit with one brightness is never asked for a level`() {
        val unit = FakeFlashUnit(maxStrengthLevel = SINGLE_STRENGTH_LEVEL)

        Camera2Torch(unit).turnOn(40)

        assertEquals(listOf("switch(true)"), unit.calls)
    }

    @Test
    fun `a brightness below full switches on first, then sets the level`() {
        // The first device fault: from a dark start several devices take the
        // level and still bring the LED up at the default brightness, so the
        // setting silently does nothing. The plain on first is the workaround.
        val unit = FakeFlashUnit(maxStrengthLevel = 100)

        assertEquals(TorchResult.Ok, Camera2Torch(unit).turnOn(40))

        assertEquals(listOf("switch(true)", "level(40)"), unit.calls)
    }

    @Test
    fun `a device that refuses the level still reports a lit torch`() {
        // The second device fault: phones that report many levels and throw
        // when asked for one. The light is already on by then, so a failure
        // would be false, and no torch is much worse than an undimmed torch.
        val unit = FakeFlashUnit(maxStrengthLevel = 164)
        unit.strengthFails = true

        val result = Camera2Torch(unit).turnOn(25)

        assertEquals(TorchResult.Ok, result)
        assertEquals(listOf("switch(true)", "level(41)"), unit.calls)
        assertTrue("the torch must be on, at full", unit.isOn)
    }

    @Test
    fun `a torch that cannot be switched on at all still fails`() {
        // The fallback must not swallow a real refusal. Nothing is lit here, so
        // reporting success would be the silent failure the workaround is meant
        // to prevent, arriving from the other side.
        val unit = FakeFlashUnit(maxStrengthLevel = 100)
        unit.switchFailsWith = "Another app is using the camera, so the flashlight is not free."

        val result = Camera2Torch(unit).turnOn(40)

        assertEquals(
            TorchResult.Failed("Another app is using the camera, so the flashlight is not free."),
            result,
        )
        assertEquals("the level must not be asked for after a failed on", 1, unit.calls.size)
    }

    @Test
    fun `switching off is one call and never asks about brightness`() {
        val unit = FakeFlashUnit(maxStrengthLevel = 100)

        assertEquals(TorchResult.Ok, Camera2Torch(unit).turnOff())

        assertEquals(listOf("switch(false)"), unit.calls)
    }
}

/** Turning the torch on and off, and what the factory declares for each. */
class FlashlightActionTest {

    private val event = TriggerEvent(triggerType = "interval", firedAtMillis = 1_000)

    @Test
    fun `on switches the torch on, at the configured brightness`() = runTest {
        val torch = FakeTorch()

        val result = FlashlightAction(torch, FlashlightMode.ON, strengthPercent = 40).execute(event)

        assertEquals(ActionResult.Success(), result)
        assertTrue(torch.isOn)
        assertEquals(listOf(40), torch.strengths)
    }

    @Test
    fun `off switches the torch off and asks for no brightness`() = runTest {
        val torch = FakeTorch()
        torch.turnOn()

        val result = FlashlightAction(torch, FlashlightMode.OFF, strengthPercent = 40).execute(event)

        assertEquals(ActionResult.Success(), result)
        assertFalse(torch.isOn)
        // The brightness of a torch being switched off is not a question, and
        // asking it would drag the API 33 call into the off case.
        assertEquals(listOf(100), torch.strengths)
    }

    @Test
    fun `a refused switch is reported to the rule with the reason`() = runTest {
        val torch = FakeTorch()
        torch.failWith = "Another app is using the camera, so the flashlight is not free."

        val result = FlashlightAction(torch, FlashlightMode.ON, strengthPercent = 100).execute(event)

        assertEquals(
            ActionResult.Failure("Another app is using the camera, so the flashlight is not free."),
            result,
        )
    }

    @Test
    fun `a mode this build does not know is refused rather than guessed`() {
        assertThrows(IllegalStateException::class.java) { FlashlightMode.parse(null) }
        assertThrows(IllegalStateException::class.java) { FlashlightMode.parse("blink") }
        assertEquals(FlashlightMode.ON, FlashlightMode.parse("ON"))
        assertEquals(FlashlightMode.OFF, FlashlightMode.parse(" off "))
    }
}

/** What the flashlight factory declares, and when. */
class FlashlightFactoryTest {

    private val factory = FlashlightActionFactory(FakeTorch())

    @Test
    fun `a phone with no flash unit is never offered this action`() {
        // A SystemFeature is permanent to RequirementChecker.isPossible, which
        // is what keeps the picker from offering something the device cannot do.
        assertEquals(
            listOf(ComponentRequirement.SystemFeature("android.hardware.camera.flash")),
            factory.requirements,
        )
    }

    @Test
    fun `full brightness needs nothing but the flash unit`() {
        val config = mapOf(
            FlashlightMode.CONFIG_KEY to "on",
            FlashlightAction.CONFIG_STRENGTH_PERCENT to "100",
        )

        assertEquals(factory.requirements, factory.requirementsFor(config))
    }

    @Test
    fun `a brightness that is turned down needs Android 13`() {
        // turnOnTorchWithStrengthLevel arrived in API 33 and this module's
        // minSdk is 26, so the floor has to be declared when it is used and
        // only then. Declared always, it would hide the action from every
        // older phone; declared never, the slider would quietly do nothing.
        val config = mapOf(
            FlashlightMode.CONFIG_KEY to "on",
            FlashlightAction.CONFIG_STRENGTH_PERCENT to "40",
        )

        assertEquals(
            factory.requirements + ComponentRequirement.MinApiLevel(33),
            factory.requirementsFor(config),
        )
    }

    @Test
    fun `switching the torch off never needs Android 13, whatever the slider says`() {
        // A brightness left behind by an earlier edit must not accuse the phone
        // of being too old for an action that only calls setTorchMode(false).
        val config = mapOf(
            FlashlightMode.CONFIG_KEY to "off",
            FlashlightAction.CONFIG_STRENGTH_PERCENT to "40",
        )

        assertEquals(factory.requirements, factory.requirementsFor(config))
    }

    @Test
    fun `a half-filled form asks for nothing extra`() {
        assertEquals(factory.requirements, factory.requirementsFor(emptyMap()))
        assertEquals(
            factory.requirements,
            factory.requirementsFor(mapOf(FlashlightMode.CONFIG_KEY to "nonsense")),
        )
    }

    @Test
    fun `the factory accepts a config built from its own declared schema`() {
        // The JVM half of ConfigSchemaContractTest, which can only run on a
        // device. A factory that needs a key its schema does not declare makes
        // the editor unable to save the action at all.
        val config = factory.configFields.associate { field ->
            field.key to when (field) {
                is ConfigField.Choice -> field.default ?: field.options.first().value
                is ConfigField.Slider -> field.default.toString()
                else -> "sample"
            }
        }

        factory.create(config)
    }

    @Test
    fun `the warning says the light stays on`() {
        val warning = factory.warning.orEmpty()

        assertTrue("a torch left burning is the visible failure", warning.contains("stays on"))
        assertTrue(warning.contains("battery"))
    }
}
