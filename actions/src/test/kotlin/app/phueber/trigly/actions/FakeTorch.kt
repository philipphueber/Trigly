package app.phueber.trigly.actions

/**
 * [Torch] for a JVM test.
 *
 * The emulators this project tests on report no flash unit, so nothing on a
 * device can watch the light come on either. This fake is where the blink
 * pattern is actually checked: what it switched, in what order, and at what
 * time on the test clock.
 *
 * [now] is the test's own virtual clock, passed in rather than read, so
 * `runTest`'s `currentTime` can drive it. It defaults to a clock that does not
 * move, for a test that only counts edges.
 *
 * [isOn] is the property worth asserting after every path. A torch left on is
 * the failure `BlinkFlashlightAction`'s `finally` exists to prevent, and it is
 * worse than a wake lock left held: the user can see it and it drains the
 * battery fast.
 */
class FakeTorch(
    private val now: () -> Long = { 0L },
) : Torch {

    /** Every switch this fake was asked for, as `(on, at what time)`, in order. */
    val edges = mutableListOf<Pair<Boolean, Long>>()

    /** Every brightness [turnOn] was asked for, in order. */
    val strengths = mutableListOf<Int>()

    var isOn = false
        private set

    /**
     * What every call answers from the [failFromEdge]th edge onwards. Null
     * means every call succeeds. This is how a test stands in for the camera
     * being taken by another app part way through a pattern.
     */
    var failWith: String? = null

    var failFromEdge: Int = 0

    val onCount: Int get() = edges.count { it.first }

    val offCount: Int get() = edges.count { !it.first }

    override fun turnOn(strengthPercent: Int): TorchResult {
        strengths += strengthPercent
        return switch(on = true)
    }

    override fun turnOff(): TorchResult = switch(on = false)

    private fun switch(on: Boolean): TorchResult {
        val failure = failWith
        if (failure != null && edges.size >= failFromEdge) {
            // A refused switch changes nothing, the same way a refused
            // setTorchMode leaves the flash unit where it was.
            return TorchResult.Failed(failure)
        }
        edges += on to now()
        isOn = on
        return TorchResult.Ok
    }
}
