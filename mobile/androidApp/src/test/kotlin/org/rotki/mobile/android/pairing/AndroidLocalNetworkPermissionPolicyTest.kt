package org.rotki.mobile.android.pairing

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidLocalNetworkPermissionPolicyTest {
    @Test
    fun `API 28 and 36 retain implicit local network access`(): Unit {
        assertFalse(AndroidLocalNetworkPermissionPolicy.requiresRuntimePermission(28, false))
        assertFalse(AndroidLocalNetworkPermissionPolicy.requiresRuntimePermission(36, false))
    }

    @Test
    fun `API 37 requires the runtime permission only while it is denied`(): Unit {
        assertTrue(AndroidLocalNetworkPermissionPolicy.requiresRuntimePermission(37, false))
        assertFalse(AndroidLocalNetworkPermissionPolicy.requiresRuntimePermission(37, true))
    }
}
