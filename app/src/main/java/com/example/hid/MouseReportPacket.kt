package com.example.hid

/**
 * Immutable data representation of a standard 4-byte USB/Bluetooth mouse input report.
 *
 * Byte 0: Buttons
 *   Bit 0: Left button (1=pressed, 0=released)
 *   Bit 1: Right button (1=pressed, 0=released)
 *   Bit 2: Middle button (1=pressed, 0=released)
 *   Bit 3-7: Padding / Reserved (0)
 * Byte 1: X displacement (-127 to 127)
 * Byte 2: Y displacement (-127 to 127)
 * Byte 3: Wheel displacement (-127 to 127)
 */
data class MouseReportPacket(
    val leftButton: Boolean = false,
    val rightButton: Boolean = false,
    val middleButton: Boolean = false,
    val deltaX: Int = 0,
    val deltaY: Int = 0,
    val wheel: Int = 0
) {
    /**
     * Serializes this mouse report into the 4-byte payload expected by the HID descriptor.
     */
    fun encode(): ByteArray {
        var buttons = 0
        if (leftButton) buttons = buttons or BUTTON_LEFT_MASK
        if (rightButton) buttons = buttons or BUTTON_RIGHT_MASK
        if (middleButton) buttons = buttons or BUTTON_MIDDLE_MASK

        val x = clamp(deltaX)
        val y = clamp(deltaY)
        val w = clamp(wheel)

        return byteArrayOf(
            buttons.toByte(),
            x,
            y,
            w
        )
    }

    val isIdle: Boolean
        get() = !leftButton && !rightButton && !middleButton && deltaX == 0 && deltaY == 0 && wheel == 0

    companion object {
        const val BUTTON_LEFT_MASK = 0x01
        const val BUTTON_RIGHT_MASK = 0x02
        const val BUTTON_MIDDLE_MASK = 0x04

        const val MIN_DISPLACEMENT = -127
        const val MAX_DISPLACEMENT = 127

        fun clamp(value: Int): Byte {
            return when {
                value < MIN_DISPLACEMENT -> MIN_DISPLACEMENT.toByte()
                value > MAX_DISPLACEMENT -> MAX_DISPLACEMENT.toByte()
                else -> value.toByte()
            }
        }

        fun decode(bytes: ByteArray): MouseReportPacket {
            require(bytes.size >= 4) { "Payload must have at least 4 bytes" }
            val b0 = bytes[0].toInt()
            val left = (b0 and BUTTON_LEFT_MASK) != 0
            val right = (b0 and BUTTON_RIGHT_MASK) != 0
            val middle = (b0 and BUTTON_MIDDLE_MASK) != 0
            val dx = bytes[1].toInt()
            val dy = bytes[2].toInt()
            val dw = bytes[3].toInt()
            return MouseReportPacket(
                leftButton = left,
                rightButton = right,
                middleButton = middle,
                deltaX = dx,
                deltaY = dy,
                wheel = dw
            )
        }
    }
}
