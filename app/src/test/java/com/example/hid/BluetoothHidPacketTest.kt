package com.example.hid

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for Bluetooth HID packet encoding, decoding, bitmasking, and clamping boundaries.
 */
class BluetoothHidPacketTest {

    @Test
    fun testPacketEncoding_idle() {
        val packet = MouseReportPacket(
            leftButton = false,
            rightButton = false,
            middleButton = false,
            deltaX = 0,
            deltaY = 0,
            wheel = 0
        )

        assertTrue(packet.isIdle)
        val encoded = packet.encode()

        assertEquals(4, encoded.size)
        assertArrayEquals(byteArrayOf(0, 0, 0, 0), encoded)
    }

    @Test
    fun testPacketEncoding_buttonsBitmask() {
        // Left click only
        val leftOnly = MouseReportPacket(leftButton = true).encode()
        assertEquals(0x01.toByte(), leftOnly[0])

        // Right click only
        val rightOnly = MouseReportPacket(rightButton = true).encode()
        assertEquals(0x02.toByte(), rightOnly[0])

        // Middle click only
        val middleOnly = MouseReportPacket(middleButton = true).encode()
        assertEquals(0x04.toByte(), middleOnly[0])

        // Left + Right simultaneous click
        val leftRight = MouseReportPacket(leftButton = true, rightButton = true).encode()
        assertEquals(0x03.toByte(), leftRight[0])

        // All three buttons clicked
        val allThree = MouseReportPacket(leftButton = true, rightButton = true, middleButton = true).encode()
        assertEquals(0x07.toByte(), allThree[0])
    }

    @Test
    fun testPacketEncoding_boundaryClamping_positiveOverflow() {
        val packet = MouseReportPacket(
            deltaX = 300,
            deltaY = 128,
            wheel = 999
        )

        val encoded = packet.encode()

        assertEquals(127.toByte(), encoded[1]) // deltaX clamped to 127
        assertEquals(127.toByte(), encoded[2]) // deltaY clamped to 127
        assertEquals(127.toByte(), encoded[3]) // wheel clamped to 127
    }

    @Test
    fun testPacketEncoding_boundaryClamping_negativeOverflow() {
        val packet = MouseReportPacket(
            deltaX = -200,
            deltaY = -128,
            wheel = -500
        )

        val encoded = packet.encode()

        assertEquals((-127).toByte(), encoded[1]) // deltaX clamped to -127
        assertEquals((-127).toByte(), encoded[2]) // deltaY clamped to -127
        assertEquals((-127).toByte(), encoded[3]) // wheel clamped to -127
    }

    @Test
    fun testPacketEncoding_inRangeDisplacement() {
        val packet = MouseReportPacket(
            leftButton = true,
            deltaX = 45,
            deltaY = -60,
            wheel = 3
        )

        val encoded = packet.encode()

        assertEquals(0x01.toByte(), encoded[0])
        assertEquals(45.toByte(), encoded[1])
        assertEquals((-60).toByte(), encoded[2])
        assertEquals(3.toByte(), encoded[3])
    }

    @Test
    fun testPacketRoundtrip_decode() {
        val original = MouseReportPacket(
            leftButton = true,
            rightButton = false,
            middleButton = true,
            deltaX = 100,
            deltaY = -80,
            wheel = -12
        )

        val encoded = original.encode()
        val decoded = MouseReportPacket.decode(encoded)

        assertEquals(original.leftButton, decoded.leftButton)
        assertEquals(original.rightButton, decoded.rightButton)
        assertEquals(original.middleButton, decoded.middleButton)
        assertEquals(original.deltaX, decoded.deltaX)
        assertEquals(original.deltaY, decoded.deltaY)
        assertEquals(original.wheel, decoded.wheel)
        assertFalse(decoded.isIdle)
    }

    @Test
    fun testHidReportDescriptor_structureValidation() {
        val descriptor = HidConstants.MOUSE_REPORT_DESCRIPTOR

        // Check non-empty
        assertTrue(descriptor.size > 20)

        // Starts with USAGE_PAGE(Generic Desktop) 0x05, 0x01, USAGE(Mouse) 0x09, 0x02
        assertEquals(0x05.toByte(), descriptor[0])
        assertEquals(0x01.toByte(), descriptor[1])
        assertEquals(0x09.toByte(), descriptor[2])
        assertEquals(0x02.toByte(), descriptor[3])

        // Ends with double END_COLLECTION 0xC0, 0xC0
        assertEquals(0xC0.toByte(), descriptor[descriptor.size - 2])
        assertEquals(0xC0.toByte(), descriptor[descriptor.size - 1])
    }
}
