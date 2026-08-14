package org.rotki.mobile.core.ports

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SecureSnapshotStoreTest {
    @Test
    fun unlockedSnapshotCopiesRedactsAndRevokesPlaintext() {
        val source = byteArrayOf(1, 2, 3)
        val unlocked = SecureSnapshotReadOutcome.Unlocked(source)
        source.fill(9)

        val first = checkNotNull(unlocked.documentCopy())
        assertContentEquals(byteArrayOf(1, 2, 3), first)
        first.fill(8)
        assertContentEquals(byteArrayOf(1, 2, 3), unlocked.documentCopy())
        assertEquals("Unlocked(redacted)", unlocked.toString())

        unlocked.discard()
        unlocked.discard()

        assertTrue(unlocked.isDiscarded)
        assertNull(unlocked.documentCopy())
    }
}
