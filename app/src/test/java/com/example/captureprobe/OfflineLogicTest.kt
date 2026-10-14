package com.example.captureprobe

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class OfflineLogicTest {
    private fun resample(rate: Int, hz: Int): FloatArray {
        val values = ArrayList<Float>()
        val resampler = PcmResampler(rate) { values.add(it) }
        repeat(rate) { resampler.accept(sin(2 * PI * hz * it / rate).toFloat()) }
        resampler.finish()
        return values.toFloatArray()
    }
    @Test fun antiAliasAndDuration() {
        listOf(8000, 16000, 44100, 48000).forEach { rate ->
            val low = resample(rate, 440)
            assertEquals(16000, low.size)
            val rms = sqrt(low.drop(100).dropLast(100).map { it.toDouble() * it }.average())
            assertTrue("Speech-band tone amplitude changed: $rate $rms", rms in 0.68..0.74)
        }
        val high = resample(48000, 10000)
        val rms = sqrt(high.drop(100).dropLast(100).map { it.toDouble() * it }.average())
        assertTrue("Out-of-band tone aliased: $rms", rms < 0.02)
    }
    @Test fun textNeverSilentlyRewritesWordsOrNumbers() {
        assertEquals("你好，世界。", TranscriptText.conservative("你好世界", "你好，世界。"))
        assertEquals("北京", TranscriptText.conservative("北京", "上海。"))
        assertEquals("价格15元", TranscriptText.conservative("价格15元", "价格1.5元。"))
        assertEquals("二零二四年", TranscriptText.conservative("二零二四年", "2024年。"))
        assertEquals("00:00:01.500", TranscriptText.stamp(24000))
    }
    @Test fun cerCountsAndNormalization() {
        val deletion = Accuracy.evaluate("甲乙丙", "甲丙")
        assertEquals(1, deletion.deletions); assertEquals(0, deletion.insertions)
        assertEquals(1.0 / 3, deletion.cer, 1e-12)
        val insertion = Accuracy.evaluate("甲丙", "甲乙丙")
        assertEquals(1, insertion.insertions)
        assertEquals(1, Accuracy.evaluate("甲乙", "甲丙").substitutions)
        assertEquals(0.0, Accuracy.evaluate("中文，测试。", "中 文测试").cer, 0.0)
    }
}
