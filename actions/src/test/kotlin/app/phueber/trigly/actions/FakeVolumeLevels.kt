package app.phueber.trigly.actions

/**
 * [VolumeLevels] for a JVM test.
 *
 * This is where the percent is actually checked. An emulator reports whatever
 * step count its own audio policy holds, so a test on a device cannot choose
 * the numbers, and the cases that matter are all about the numbers: a maximum
 * of zero, a minimum above zero, a muted stream below that minimum, and a read
 * that throws.
 *
 * Each stream has its own reading, because "the right stream was read" is a
 * claim about which of four numbers came back, and one shared reading could not
 * tell a correct action from one that reads media every time.
 */
class FakeVolumeLevels(
    private val readings: Map<VolumeStream, VolumeReading> = emptyMap(),
) : VolumeLevels {

    /** Every stream this fake was asked for, in order. */
    val reads = mutableListOf<VolumeStream>()

    /**
     * What every read throws, instead of answering. This stands in for the
     * audio service dying in another process, which arrives as a runtime
     * exception on a call that normally cannot fail.
     */
    var throwsWith: RuntimeException? = null

    override fun read(stream: VolumeStream): VolumeReading {
        reads += stream
        throwsWith?.let { throw it }
        return readings[stream]
            ?: VolumeReading.Failed("This test set no reading for ${stream.configValue}.")
    }
}
