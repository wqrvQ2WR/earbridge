package com.hayoonjae.earbridge

/**
 * 맥 앱과 맞춘 전송 규칙.
 * 접속하면 헤더 9바이트: "EBR1" + 샘플레이트(uint32 LE) + 채널 수(uint8).
 * 그 뒤로는 16비트 리틀엔디언 PCM이 끝없이 이어진다.
 */
object Protocol {
    const val SAMPLE_RATE = 48000

    fun header(rate: Int, channels: Int): ByteArray = byteArrayOf(
        'E'.code.toByte(), 'B'.code.toByte(), 'R'.code.toByte(), '1'.code.toByte(),
        rate.toByte(), (rate shr 8).toByte(), (rate shr 16).toByte(), (rate shr 24).toByte(),
        channels.toByte(),
    )

    fun toLittleEndian(samples: ShortArray, n: Int): ByteArray {
        val out = ByteArray(n * 2)
        for (i in 0 until n) {
            val v = samples[i].toInt()
            out[i * 2] = v.toByte()
            out[i * 2 + 1] = (v shr 8).toByte()
        }
        return out
    }
}
