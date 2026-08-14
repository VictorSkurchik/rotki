package org.rotki.mobile.android.storage

import org.rotki.mobile.core.ports.PairingRecord
import org.rotki.mobile.core.protocol.DeviceSessionId
import org.rotki.mobile.core.protocol.EngineOrigin
import org.rotki.mobile.core.protocol.EngineOriginParseOutcome
import org.rotki.mobile.core.protocol.ProtocolValueParseOutcome
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal sealed interface PairingRecordDecodeOutcome {
    data class Accepted(
        val record: PairingRecord,
    ) : PairingRecordDecodeOutcome

    data object Rejected : PairingRecordDecodeOutcome
}

/** Strict binary codec whose only variable fields are canonical protocol strings. */
internal object PairingRecordCodec {
    const val MAX_ENCODED_BYTES: Int = 2_104

    private const val VERSION: Int = 1
    private const val HEADER_BYTES: Int = 4 + 1 + Short.SIZE_BYTES + 1
    private const val MAX_ORIGIN_BYTES: Int = 2_048
    private const val DEVICE_SESSION_ID_BYTES: Int = 43
    private val magic: ByteArray =
        byteArrayOf(
            'R'.code.toByte(),
            'K'.code.toByte(),
            'P'.code.toByte(),
            'R'.code.toByte(),
        )

    fun encode(record: PairingRecord): ByteArray {
        val origin = record.engineOrigin.canonical.toByteArray(Charsets.US_ASCII)
        val deviceSessionId = record.deviceSessionId.encoded.toByteArray(Charsets.US_ASCII)
        require(origin.size in 1..MAX_ORIGIN_BYTES) { "Engine origin is outside the supported bound" }
        require(deviceSessionId.size == DEVICE_SESSION_ID_BYTES) {
            "Device Session ID has an invalid encoded length"
        }
        return ByteBuffer
            .allocate(HEADER_BYTES + origin.size + deviceSessionId.size)
            .order(ByteOrder.BIG_ENDIAN)
            .put(magic)
            .put(VERSION.toByte())
            .putShort(origin.size.toShort())
            .put(deviceSessionId.size.toByte())
            .put(origin)
            .put(deviceSessionId)
            .array()
    }

    fun decode(encoded: ByteArray): PairingRecordDecodeOutcome {
        if (encoded.size !in minimumEncodedBytes()..MAX_ENCODED_BYTES) {
            return PairingRecordDecodeOutcome.Rejected
        }
        val input = ByteBuffer.wrap(encoded).order(ByteOrder.BIG_ENDIAN)
        val actualMagic = ByteArray(magic.size).also(input::get)
        if (!actualMagic.contentEquals(magic) || input.get().toInt() and 0xff != VERSION) {
            return PairingRecordDecodeOutcome.Rejected
        }
        val originSize = input.short.toInt() and 0xffff
        val deviceSessionIdSize = input.get().toInt() and 0xff
        if (originSize !in 1..MAX_ORIGIN_BYTES ||
            deviceSessionIdSize != DEVICE_SESSION_ID_BYTES ||
            input.remaining() != originSize + deviceSessionIdSize
        ) {
            return PairingRecordDecodeOutcome.Rejected
        }
        val originBytes = ByteArray(originSize).also(input::get)
        val deviceSessionIdBytes = ByteArray(deviceSessionIdSize).also(input::get)
        val originText = originBytes.strictAsciiOrNull() ?: return PairingRecordDecodeOutcome.Rejected
        val deviceSessionIdText =
            deviceSessionIdBytes.strictAsciiOrNull()
                ?: return PairingRecordDecodeOutcome.Rejected
        val origin =
            when (val parsed = EngineOrigin.parse(originText)) {
                is EngineOriginParseOutcome.Accepted -> parsed.origin
                is EngineOriginParseOutcome.Rejected -> return PairingRecordDecodeOutcome.Rejected
            }
        val deviceSessionId =
            when (val parsed = DeviceSessionId.parse(deviceSessionIdText)) {
                is ProtocolValueParseOutcome.Accepted -> parsed.value
                is ProtocolValueParseOutcome.Rejected -> return PairingRecordDecodeOutcome.Rejected
            }
        return PairingRecordDecodeOutcome.Accepted(PairingRecord(origin, deviceSessionId))
    }

    private fun minimumEncodedBytes(): Int = HEADER_BYTES + 1 + DEVICE_SESSION_ID_BYTES

    private fun ByteArray.strictAsciiOrNull(): String? {
        if (any { byte -> byte.toInt() and 0xff !in 0x21..0x7e }) return null
        return toString(Charsets.US_ASCII)
    }
}
