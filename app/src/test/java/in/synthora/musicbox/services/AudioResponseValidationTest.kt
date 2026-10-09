package `in`.synthora.musicbox.services

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class AudioResponseValidationTest {
    private class Source(val bytes: ByteArray, val mime: String = "application/octet-stream") : DataSource {
        var index = 0; var closed = false
        override fun addTransferListener(listener: TransferListener) {}
        override fun open(spec: DataSpec): Long { index = spec.position.toInt(); closed = false; return (bytes.size - index).toLong() }
        override fun getUri(): Uri = Uri.parse("https://example.test/audio")
        override fun getResponseHeaders(): Map<String, List<String>> = mapOf("Content-Type" to listOf(mime))
        override fun read(target: ByteArray, offset: Int, length: Int): Int {
            if (index == bytes.size) return C.RESULT_END_OF_INPUT
            val count = minOf(length, 3, bytes.size - index) // exercise short socket reads
            bytes.copyInto(target, offset, index, index + count); index += count; return count
        }
        override fun close() { closed = true }
    }
    private val spec get() = DataSpec.Builder().setUri("https://example.test/audio").build()

    @Test fun binaryAudioIsReplayedExactlyAcrossPrefixAndSocketReads() {
        val bytes = "ID3".toByteArray() + ByteArray(200) { it.toByte() }
        val source = Source(bytes); val checked = ValidatingAudioDataSource(source)
        assertEquals(bytes.size.toLong(), checked.open(spec))
        val out = java.io.ByteArrayOutputStream(); val buffer = ByteArray(7)
        while (true) { val n = checked.read(buffer, 0, buffer.size); if (n < 0) break; out.write(buffer, 0, n) }
        assertArrayEquals(bytes, out.toByteArray()); checked.close(); assertTrue(source.closed)
    }
    @Test fun errorDocumentsAndEmptySuccessAreRejectedAndConnectionClosed() {
        for (body in listOf("", "  {\"error\":\"unavailable\"}", "\uFEFF <html>bad</html>", "[{}]")) {
            val source = Source(body.toByteArray()); val checked = ValidatingAudioDataSource(source)
            try { checked.open(spec); fail("Error document accepted") }
            catch (e: InvalidAudioResponseException) { assertTrue(source.closed); assertTrue(hasInvalidAudioResponse(java.io.IOException(e))) }
        }
    }
    @Test fun declaredErrorTypeCannotEnterCacheEvenWithBinaryBody() {
        val source = Source("ID3data".toByteArray(), "application/problem+json; charset=utf-8")
        try { ValidatingAudioDataSource(source).open(spec); fail("JSON accepted") }
        catch (e: InvalidAudioResponseException) { assertTrue(source.closed) }
    }
    @Test fun rangeReadsAreNotMistakenForFileHeaders() {
        val bytes = "ID3[{payload".toByteArray()
        val checked = ValidatingAudioDataSource(Source(bytes))
        assertEquals((bytes.size - 3).toLong(), checked.open(spec.buildUpon().setPosition(3).build()))
        val buffer = ByteArray(3); assertEquals(3, checked.read(buffer, 0, 3)); assertEquals('[', buffer[0].toInt().toChar())
        checked.close()
    }
    @Test fun aSuccessfulDownloadRequiresAllExpectedBytesAndNoErrorPage() {
        val file = java.io.File.createTempFile("audio-check", ".bin")
        try {
            file.writeText("ID3audio")
            assertTrue(validCompletedAudio(file, file.length(), file.length()))
            assertFalse(validCompletedAudio(file, file.length(), file.length() + 10))
            assertFalse(validCompletedAudio(file, file.length() - 1, -1))
            file.writeText("{\"error\":\"bad\"}")
            assertFalse(validCompletedAudio(file, file.length(), file.length()))
        } finally { file.delete() }
    }
    @Test fun onlyTheFailingLocalFileIsInvalidatedAndHealthyDownloadsSurviveNetworkErrors() {
        val file = java.io.File("/tmp/test-audio.m4a")
        assertTrue(sameAudioSource(file.absolutePath, file.toURI().toString()))
        assertFalse(sameAudioSource(file.toURI().toString(), "https://example.test/audio"))
        assertFalse(sameAudioSource(file.toURI().toString(), "/tmp/other-audio.m4a"))
        assertFalse(sameAudioSource(null, null))
    }
    @Test fun recognizedBinaryContainersAndGenericMimeRemainAllowed() {
        for (header in listOf("ID3", "OggS", "fLaC", "RIFF", "\u0000\u0000\u0000\u0018ftyp")) assertFalse(isAudioErrorDocument(header.toByteArray()))
        assertFalse(isAudioErrorContentType("audio/mp4")); assertFalse(isAudioErrorContentType(null))
        assertFalse(isAudioErrorContentType("application/octet-stream")); assertTrue(isAudioErrorContentType("text/html"))
        assertFalse(hasInvalidAudioResponse(java.net.SocketTimeoutException()))
    }
}
