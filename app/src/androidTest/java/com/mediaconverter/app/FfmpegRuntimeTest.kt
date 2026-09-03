package com.mediaconverter.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import com.mediaconverter.app.data.AndroidMediaValidator
import com.mediaconverter.app.data.OutputFormat
import com.mediaconverter.app.data.TranscodeCommandFactory
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

@RunWith(AndroidJUnit4::class)
class FfmpegRuntimeTest {
    @Test
    fun nativeRuntimeStartsAndEncodesBundledPcmFixture() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val version = FFmpegKit.executeWithArguments(arrayOf("-version"))
        assertTrue(version.allLogsAsString, ReturnCode.isSuccess(version.returnCode))

        val input = File(context.cacheDir, "fixture.wav").apply { writeBytes(shortWaveFixture()) }
        val output = File(context.cacheDir, "fixture.mp3")
        output.delete()
        val session = FFmpegKit.executeWithArguments(
            TranscodeCommandFactory.mp3(input, output).toTypedArray(),
        )

        assertTrue(session.allLogsAsString, ReturnCode.isSuccess(session.returnCode))
        assertTrue(AndroidMediaValidator().isValid(output, OutputFormat.MP3))
        input.delete()
        output.delete()
    }

    private fun shortWaveFixture(): ByteArray {
        val sampleRate = 8_000
        val sampleCount = sampleRate
        val dataSize = sampleCount * 2
        return ByteBuffer.allocate(44 + dataSize).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray())
            putInt(36 + dataSize)
            put("WAVEfmt ".toByteArray())
            putInt(16)
            putShort(1)
            putShort(1)
            putInt(sampleRate)
            putInt(sampleRate * 2)
            putShort(2)
            putShort(16)
            put("data".toByteArray())
            putInt(dataSize)
            repeat(sampleCount) { index ->
                val sample = (kotlin.math.sin(2.0 * Math.PI * 440.0 * index / sampleRate) * 8_000).toInt()
                putShort(sample.toShort())
            }
        }.array()
    }
}
