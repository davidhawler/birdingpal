package com.openaiexperiments.birdingbuddy.nativeapp.background

import java.util.UUID

/**
 * BLE protocol contract for Bird Buddy remote.
 *
 * Program your microcontroller to expose one notify characteristic under this service UUID.
 * The app subscribes to notifications and expects these packets:
 * - Analyze bird-call trigger: <Buffer 00 43>
 * - Hold-to-talk heartbeat: <Buffer 00 45>
 *
 * For hold-to-talk behavior, send <Buffer 00 45> repeatedly at least every 1 second
 * while the user is pressing/holding your hardware control. If heartbeats stop for >1s,
 * the app closes the microphone and sends audio, mimicking press-and-hold release.
 */
object BirdBuddyBleProtocol {
    // Placeholder UUIDs: replace these when firmware UUIDs are finalized.
    val SERVICE_UUID: UUID = UUID.fromString("0000b001-0000-1000-8000-00805f9b34fb")
    val MESSAGE_CHARACTERISTIC_UUID: UUID =
        UUID.fromString("0000b101-0000-1000-8000-00805f9b34fb")
    val CLIENT_CONFIG_DESCRIPTOR_UUID: UUID =
        UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    val PACKET_ANALYZE: ByteArray = byteArrayOf(0x00, 0x43)
    val PACKET_HOLD_HEARTBEAT: ByteArray = byteArrayOf(0x00, 0x45)

    const val HOLD_HEARTBEAT_TIMEOUT_MS: Long = 1_000L
}

enum class BirdBuddyBleMessage {
    ANALYZE,
    HOLD_HEARTBEAT,
    UNKNOWN
}

fun parseBirdBuddyBleMessage(packet: ByteArray): BirdBuddyBleMessage {
    if (packet.size < 2) {
        return BirdBuddyBleMessage.UNKNOWN
    }

    val header = packet[0]
    val command = packet[1]
    if (header.toInt() != 0x00) {
        return BirdBuddyBleMessage.UNKNOWN
    }

    return when (command.toInt() and 0xFF) {
        0x43 -> BirdBuddyBleMessage.ANALYZE
        0x45 -> BirdBuddyBleMessage.HOLD_HEARTBEAT
        else -> BirdBuddyBleMessage.UNKNOWN
    }
}

fun ByteArray.toHexString(): String =
    joinToString(separator = " ") { byte ->
        "%02X".format(byte.toInt() and 0xFF)
    }
