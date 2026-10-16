package app.instantrecorder.recording

import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** 16 kHz, mono, 16-bit PCM WAV — exactly what whisper wants, so no resampling is ever needed. */
object WavFile {
    const val SAMPLE_RATE = 16_000
    private const val HEADER_SIZE = 44

    class Writer(file: File) : Closeable {
        private val raf = RandomAccessFile(file, "rw")
        private var dataBytes = 0L
        private var scratch = ByteBuffer.allocate(0).order(ByteOrder.LITTLE_ENDIAN)

        init {
            raf.setLength(0)
            raf.write(header(0))
        }

        fun write(samples: ShortArray, count: Int) {
            if (scratch.capacity() < count * 2) {
                scratch = ByteBuffer.allocate(count * 2).order(ByteOrder.LITTLE_ENDIAN)
            }
            scratch.clear()
            for (i in 0 until count) scratch.putShort(samples[i])
            raf.write(scratch.array(), 0, count * 2)
            dataBytes += count * 2
        }

        override fun close() {
            raf.seek(0)
            raf.write(header(dataBytes))
            raf.close()
        }
    }

    fun readAsFloat(file: File): FloatArray {
        val bytes = file.readBytes()
        if (bytes.size < HEADER_SIZE) throw IOException("Recording file is empty")
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        // Walk the RIFF chunks to find "data"; our own files always put it at byte 36.
        var pos = 12
        var dataStart = -1
        var dataLen = 0
        while (pos + 8 <= bytes.size) {
            val id = String(bytes, pos, 4, Charsets.US_ASCII)
            val len = buf.getInt(pos + 4)
            if (id == "data") {
                dataStart = pos + 8
                // A recording interrupted by process death never got its sizes patched in.
                dataLen = if (len <= 0 || dataStart + len > bytes.size) bytes.size - dataStart else len
                break
            }
            pos += 8 + len
        }
        if (dataStart < 0) throw IOException("Not a WAV file")
        val n = dataLen / 2
        return FloatArray(n) { i -> buf.getShort(dataStart + i * 2) / 32768f }
    }

    fun durationMs(sampleCount: Long): Long = sampleCount * 1000 / SAMPLE_RATE

    private fun header(dataBytes: Long): ByteArray {
        val b = ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()).putInt((36 + dataBytes).toInt()).put("WAVE".toByteArray())
        b.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1)
            .putInt(SAMPLE_RATE).putInt(SAMPLE_RATE * 2).putShort(2).putShort(16)
        b.put("data".toByteArray()).putInt(dataBytes.toInt())
        return b.array()
    }
}
