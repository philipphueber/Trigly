package app.phueber.trigly.ui

import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * What reading a chosen file for import produced, before `RuleJson` ever sees
 * it.
 *
 * Kept apart from the codec's own `IllegalArgumentException`, because a file
 * that could not be read, a file that was refused for its size, and a file
 * that decoded to bad JSON are three different problems with three different
 * sentences. Folding every read failure into an empty string used to erase
 * that difference: `RuleJson.decode("")` reports "not valid JSON", which
 * names the wrong cause when the real problem was a revoked permission or a
 * deleted file, and gives nobody anything to act on.
 */
sealed interface ImportRead {

    /** The file's bytes, decoded as UTF-8 text, ready for `RuleJson.decode`. */
    data class Read(val text: String) : ImportRead

    /** The stream could not be opened, or reading it failed partway through. */
    data object CouldNotRead : ImportRead

    /** The file held more than [MAX_IMPORT_BYTES]. */
    data object TooLarge : ImportRead
}

/**
 * The largest file this app reads into memory for one import.
 *
 * A rules document is JSON text. Even a device with thousands of rules, each
 * heavier than any real one, exports to a few megabytes at most. Ten
 * megabytes is already an implausible rule set: the limit exists to stop a
 * mistaken or hostile multi-megabyte file from being read whole into memory
 * before anything has looked at it, not to bound a file any real export would
 * ever produce.
 */
const val MAX_IMPORT_BYTES: Long = 10L * 1024 * 1024

private const val MAX_IMPORT_MEGABYTES: Long = MAX_IMPORT_BYTES / (1024 * 1024)

/**
 * The message to show for this outcome, or null for [ImportRead.Read], which
 * has nothing to report.
 *
 * [ImportRead.TooLarge] names the limit rather than only saying the file was
 * too big, so the refusal is actionable: split the file, or ask whoever sent
 * it for a smaller export.
 */
fun ImportRead.readFailureMessage(): String? = when (this) {
    is ImportRead.Read -> null
    ImportRead.CouldNotRead -> "Trigly could not read that file."
    ImportRead.TooLarge ->
        "That file is bigger than Trigly will import. The limit is $MAX_IMPORT_MEGABYTES MB."
}

/**
 * Reads at most [limit] bytes from this stream, or null once it is clear
 * there are more than that.
 *
 * Stops as soon as it knows the answer is null, so a stream backed by a file
 * larger than [limit] is never read fully into memory just to be refused.
 * Reads in fixed-size chunks rather than calling `readBytes()`, which has no
 * limit of its own and is what let an oversized file reach memory in the
 * first place.
 */
internal fun InputStream.readAtMost(limit: Long): ByteArray? {
    val buffer = ByteArrayOutputStream()
    val chunk = ByteArray(8 * 1024)
    var total = 0L
    while (true) {
        val readCount = read(chunk)
        if (readCount == -1) return buffer.toByteArray()
        total += readCount
        if (total > limit) return null
        buffer.write(chunk, 0, readCount)
    }
}
