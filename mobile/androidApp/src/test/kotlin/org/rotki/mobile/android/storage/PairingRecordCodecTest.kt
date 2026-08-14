package org.rotki.mobile.android.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.rotki.mobile.core.ports.PairingRecord
import org.rotki.mobile.core.protocol.DeviceSessionId
import org.rotki.mobile.core.protocol.EngineOrigin
import org.rotki.mobile.core.protocol.EngineOriginParseOutcome
import org.rotki.mobile.core.protocol.ProtocolValueParseOutcome

class PairingRecordCodecTest {
    @Test
    fun `canonical pairing record round trips`() {
        val record = record()
        val outcome = PairingRecordCodec.decode(PairingRecordCodec.encode(record))
        assertTrue(outcome is PairingRecordDecodeOutcome.Accepted)
        val decoded = (outcome as PairingRecordDecodeOutcome.Accepted).record

        assertEquals(record, decoded)
        assertEquals("PairingRecord(redacted)", decoded.toString())
    }

    @Test
    fun `strict decoder rejects every structural mutation`() {
        val valid = PairingRecordCodec.encode(record())
        val mutations =
            listOf(
                valid.copyOf().also { bytes -> bytes[0] = 'X'.code.toByte() },
                valid.copyOf().also { bytes -> bytes[4] = 2 },
                valid.copyOf().also { bytes -> bytes[6] = 0 },
                valid.copyOf().also { bytes -> bytes[7] = 42 },
                valid + 0,
                valid.copyOf(valid.lastIndex),
                valid.copyOf().also { bytes -> bytes[8] = 0.toByte() },
                valid.copyOf().also { bytes -> bytes[valid.lastIndex] = '='.code.toByte() },
            )
        mutations.forEachIndexed { index, encoded ->
            assertTrue(
                "mutation $index was unexpectedly accepted",
                PairingRecordCodec.decode(encoded) is PairingRecordDecodeOutcome.Rejected,
            )
        }
    }

    @Test
    fun `oversized input is rejected before parsing`() {
        assertTrue(
            PairingRecordCodec.decode(ByteArray(PairingRecordCodec.MAX_ENCODED_BYTES + 1)) is
                PairingRecordDecodeOutcome.Rejected,
        )
    }

    private fun record(): PairingRecord {
        val parsedOrigin = EngineOrigin.parse("https://rotki.example:4242")
        check(parsedOrigin is EngineOriginParseOutcome.Accepted)
        val origin = parsedOrigin.origin
        val parsedDeviceSessionId =
            DeviceSessionId.parse("AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8")
        check(parsedDeviceSessionId is ProtocolValueParseOutcome.Accepted<DeviceSessionId>)
        val deviceSessionId = parsedDeviceSessionId.value
        return PairingRecord(origin, deviceSessionId)
    }
}
