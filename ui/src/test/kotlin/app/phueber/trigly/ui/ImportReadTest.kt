package app.phueber.trigly.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

/**
 * The size cap and the failure wording for importing a file, both reachable
 * on the JVM: neither needs a `ContentResolver` or a device, only a stream and
 * a sealed value. [MainActivity.readImportFile] is the part that does, and it
 * is thin glue around these two functions on purpose.
 */
class ImportReadTest {

    // --- readAtMost ---------------------------------------------------------

    @Test
    fun `a stream under the limit reads in full`() {
        val bytes = "hello".toByteArray()

        val read = ByteArrayInputStream(bytes).readAtMost(1_000)

        assertNotNull(read)
        assertEquals("hello", read!!.decodeToString())
    }

    /** The boundary itself must not be refused: "at most" includes the limit. */
    @Test
    fun `a stream exactly at the limit reads in full`() {
        val bytes = "12345".toByteArray()

        val read = ByteArrayInputStream(bytes).readAtMost(bytes.size.toLong())

        assertNotNull(read)
        assertEquals(5, read!!.size)
    }

    @Test
    fun `a stream one byte over the limit is refused`() {
        val bytes = "123456".toByteArray()

        val read = ByteArrayInputStream(bytes).readAtMost(bytes.size.toLong() - 1)

        assertNull(read)
    }

    @Test
    fun `an empty stream reads as an empty array, not a refusal`() {
        val read = ByteArrayInputStream(ByteArray(0)).readAtMost(1_000)

        assertNotNull(read)
        assertEquals(0, read!!.size)
    }

    /** A stream larger than any chunk this function reads at once. */
    @Test
    fun `a stream spanning several chunks still reads in full`() {
        val bytes = ByteArray(50_000) { (it % 256).toByte() }

        val read = ByteArrayInputStream(bytes).readAtMost(bytes.size.toLong())

        assertNotNull(read)
        assertTrue(bytes.contentEquals(read))
    }

    // --- readFailureMessage --------------------------------------------------

    @Test
    fun `a successful read has no failure message`() {
        assertNull(ImportRead.Read("{}").readFailureMessage())
    }

    @Test
    fun `a read failure names itself, not the codec`() {
        val message = ImportRead.CouldNotRead.readFailureMessage()

        assertNotNull(message)
        assertTrue(message!!.contains("could not read"))
    }

    @Test
    fun `an oversized file names the limit`() {
        val message = ImportRead.TooLarge.readFailureMessage()

        assertNotNull(message)
        assertTrue(message!!.contains("${MAX_IMPORT_BYTES / (1024 * 1024)} MB"))
    }
}
