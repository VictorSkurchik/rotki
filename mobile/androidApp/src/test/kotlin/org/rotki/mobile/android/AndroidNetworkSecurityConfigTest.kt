package org.rotki.mobile.android

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

class AndroidNetworkSecurityConfigTest {
    @Test
    fun `manifest uses a cleartext-disabled config trusting system and user anchors`(): Unit {
        val manifest = parseXml(projectFile("src/main/AndroidManifest.xml"))
        val application = manifest.getElementsByTagName("application").item(0) as Element
        assertEquals(
            "@xml/network_security_config",
            application.getAttribute("android:networkSecurityConfig"),
        )
        assertEquals("false", application.getAttribute("android:usesCleartextTraffic"))
        val permissions = manifest.getElementsByTagName("uses-permission")
        val permissionNames = (0 until permissions.length)
            .map { index -> (permissions.item(index) as Element).getAttribute("android:name") }
            .toSet()
        assertTrue("android.permission.INTERNET" in permissionNames)
        assertTrue("android.permission.ACCESS_LOCAL_NETWORK" in permissionNames)

        val config = parseXml(projectFile("src/main/res/xml/network_security_config.xml"))
        val baseConfig = config.getElementsByTagName("base-config").item(0) as Element
        assertEquals("false", baseConfig.getAttribute("cleartextTrafficPermitted"))
        val anchors = config.getElementsByTagName("certificates")
        val sources = (0 until anchors.length)
            .map { index -> (anchors.item(index) as Element).getAttribute("src") }
            .toSet()
        assertEquals(setOf("system", "user"), sources)
    }

    private fun projectFile(relativePath: String): File = File(relativePath).also { file ->
        assertTrue("Missing Android project file: ${file.absolutePath}", file.isFile)
    }

    private fun parseXml(file: File) = DocumentBuilderFactory.newInstance()
        .newDocumentBuilder()
        .parse(file)
}
