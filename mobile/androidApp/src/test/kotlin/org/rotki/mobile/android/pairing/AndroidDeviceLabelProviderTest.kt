package org.rotki.mobile.android.pairing

import org.junit.Assert.assertEquals
import org.junit.Test

class AndroidDeviceLabelProviderTest {
    @Test
    fun `uses the trimmed device model as the registration label`(): Unit {
        val provider = AndroidDeviceLabelProvider { "  Pixel 10 Pro  " }

        assertEquals("Pixel 10 Pro", provider.label())
    }

    @Test
    fun `allows a valid label containing 64 Unicode scalars`(): Unit {
        val label = "📱".repeat(64)
        val provider = AndroidDeviceLabelProvider { label }

        assertEquals(label, provider.label())
    }

    @Test
    fun `falls back for absent blank or oversized models`(): Unit {
        listOf<String?>(
            null,
            "",
            "   ",
            "x".repeat(65),
            "📱".repeat(65),
            "é".repeat(64) + "a",
        ).forEach { model ->
            assertEquals(
                "model=$model",
                "Android device",
                AndroidDeviceLabelProvider { model }.label(),
            )
        }
    }

    @Test
    fun `falls back for malformed or forbidden Unicode`(): Unit {
        listOf(
            "bad\u0000model",
            "bad\u200Bmodel",
            "bad\u2028model",
            "bad\u2029model",
            "bad\uDB40\uDC01model",
            "bad\uD800model",
            "bad\uDC00model",
        ).forEach { model ->
            assertEquals(
                "model=$model",
                "Android device",
                AndroidDeviceLabelProvider { model }.label(),
            )
        }
    }

    @Test
    fun `supplementary emoji remains valid after code point category validation`(): Unit {
        assertEquals("Pixel 📱", AndroidDeviceLabelProvider { "Pixel 📱" }.label())
    }

    @Test
    fun `reads only the injected model seam once per label`(): Unit {
        var reads = 0
        val provider = AndroidDeviceLabelProvider {
            reads += 1
            "Pixel 8"
        }

        assertEquals("Pixel 8", provider.label())
        assertEquals(1, reads)
    }
}
