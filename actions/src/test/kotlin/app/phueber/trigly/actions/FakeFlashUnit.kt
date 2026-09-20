package app.phueber.trigly.actions

/**
 * [FlashUnit] for a JVM test.
 *
 * One level below [FakeTorch], and the two are not alternatives: [FakeTorch]
 * stands in for the whole torch while an action is tested, and this stands in
 * for the camera while [Camera2Torch]'s own call order is tested. That order is
 * the two device workarounds, so it is the part that most needs a test and the
 * part no device here can provide: the emulators report no flash unit.
 *
 * [calls] records what was asked of the camera, in order, which is the whole
 * point. "A brightness below full switches on first, then sets the level" is a
 * claim about a sequence, and only a recorded sequence can hold it.
 */
class FakeFlashUnit(
    override val maxStrengthLevel: Int = SINGLE_STRENGTH_LEVEL,
) : FlashUnit {

    /** One entry per call: `switch(true)`, `switch(false)` or `level(n)`. */
    val calls = mutableListOf<String>()

    /**
     * What [switch] answers. Non-null stands in for a camera another app holds,
     * which is the only failure that reaches a device with a working flash.
     */
    var switchFailsWith: String? = null

    /**
     * Whether the brightness call is refused, which is the fault worked around
     * in [Camera2Torch.turnOn]: phones that report many levels and throw when
     * asked for one. The real unit turns that throw into a [TorchResult.Failed],
     * so a fake that returns one is standing in for the throw, not softening it.
     */
    var strengthFails: Boolean = false

    var isOn = false
        private set

    override fun switch(on: Boolean): TorchResult {
        calls += "switch($on)"
        switchFailsWith?.let { return TorchResult.Failed(it) }
        isOn = on
        return TorchResult.Ok
    }

    override fun switchOnAt(level: Int): TorchResult {
        calls += "level($level)"
        if (strengthFails) return TorchResult.Failed("This device refused the brightness.")
        isOn = true
        return TorchResult.Ok
    }
}
