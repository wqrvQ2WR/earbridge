package com.hayoonjae.earbridge

import org.junit.Assert.assertArrayEquals
import org.junit.Test

class ProtocolTest {
    @Test
    fun header() {
        val h = Protocol.header(48000, 1)
        assertArrayEquals(byteArrayOf(0x45, 0x42, 0x52, 0x31, 0x80.toByte(), 0xBB.toByte(), 0, 0, 1), h)
    }

    @Test
    fun littleEndian() {
        val b = Protocol.toLittleEndian(shortArrayOf(1, -2, 0x1234, 99), 3)
        assertArrayEquals(byteArrayOf(1, 0, 0xFE.toByte(), 0xFF.toByte(), 0x34, 0x12), b)
    }
}
