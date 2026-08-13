package org.rotki.mobile.core.ports

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class SecureSnapshotStoreTest {
    @Test
    fun unlockedSnapshotCopiesAndRedactsPlaintext(): Unit {
        val source = byteArrayOf(1, 2, 3)
        val unlocked = SecureSnapshotReadOutcome.Unlocked(source)
        source.fill(9)

        val first = unlocked.documentCopy()
        assertContentEquals(byteArrayOf(1, 2, 3), first)
        first.fill(8)
        assertContentEquals(byteArrayOf(1, 2, 3), unlocked.documentCopy())
        assertEquals("Unlocked(redacted)", unlocked.toString())
    }
}
