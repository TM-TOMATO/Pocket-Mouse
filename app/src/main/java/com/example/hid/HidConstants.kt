package com.example.hid

/**
 * Standard USB/Bluetooth HID Report Descriptor and constants for a 3-button + wheel mouse.
 */
object HidConstants {

    /**
     * Standard USB HID Mouse Report Descriptor (4 bytes payload: Buttons, dX, dY, dWheel)
     * - Byte 0: Bit 0=Left, Bit 1=Right, Bit 2=Middle, Bits 3-7=Padding
     * - Byte 1: X displacement (-127 to 127)
     * - Byte 2: Y displacement (-127 to 127)
     * - Byte 3: Wheel displacement (-127 to 127)
     */
    val MOUSE_REPORT_DESCRIPTOR = byteArrayOf(
        0x05.toByte(), 0x01.toByte(), // USAGE_PAGE (Generic Desktop)
        0x09.toByte(), 0x02.toByte(), // USAGE (Mouse)
        0xA1.toByte(), 0x01.toByte(), // COLLECTION (Application)
        0x09.toByte(), 0x01.toByte(), //   USAGE (Pointer)
        0xA1.toByte(), 0x00.toByte(), //   COLLECTION (Physical)
        0x05.toByte(), 0x09.toByte(), //     USAGE_PAGE (Button)
        0x19.toByte(), 0x01.toByte(), //     USAGE_MINIMUM (Button 1: Left)
        0x29.toByte(), 0x03.toByte(), //     USAGE_MAXIMUM (Button 3: Middle)
        0x15.toByte(), 0x00.toByte(), //     LOGICAL_MINIMUM (0)
        0x25.toByte(), 0x01.toByte(), //     LOGICAL_MAXIMUM (1)
        0x95.toByte(), 0x03.toByte(), //     REPORT_COUNT (3)
        0x75.toByte(), 0x01.toByte(), //     REPORT_SIZE (1)
        0x81.toByte(), 0x02.toByte(), //     INPUT (Data, Var, Abs)
        0x95.toByte(), 0x01.toByte(), //     REPORT_COUNT (1)
        0x75.toByte(), 0x05.toByte(), //     REPORT_SIZE (5 - Padding)
        0x81.toByte(), 0x03.toByte(), //     INPUT (Const, Var, Abs)
        0x05.toByte(), 0x01.toByte(), //     USAGE_PAGE (Generic Desktop)
        0x09.toByte(), 0x30.toByte(), //     USAGE (X)
        0x09.toByte(), 0x31.toByte(), //     USAGE (Y)
        0x09.toByte(), 0x38.toByte(), //     USAGE (Wheel)
        0x15.toByte(), 0x81.toByte(), //     LOGICAL_MINIMUM (-127)
        0x25.toByte(), 0x7F.toByte(), //     LOGICAL_MAXIMUM (127)
        0x75.toByte(), 0x08.toByte(), //     REPORT_SIZE (8)
        0x95.toByte(), 0x03.toByte(), //     REPORT_COUNT (3)
        0x81.toByte(), 0x06.toByte(), //     INPUT (Data, Var, Rel)
        0xC0.toByte(),               //   END_COLLECTION
        0xC0.toByte()                // END_COLLECTION
    )

    const val REPORT_ID_MOUSE = 0
    const val SDP_NAME = "DeskMouse Optical"
    const val SDP_DESCRIPTION = "Visual-Inertial Desk Optical Mouse"
    const val SDP_PROVIDER = "Android VIO"
    const val MOUSE_SUBCLASS = 0x00.toByte() // Pointing device
}
