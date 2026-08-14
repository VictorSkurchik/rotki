package org.rotki.mobile.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class AndroidNavigationManifestTest {
    @Test
    fun `launcher uses the Koin application and exposes no external navigation routes`() {
        val manifest =
            DocumentBuilderFactory
                .newInstance()
                .newDocumentBuilder()
                .parse(projectFile("src/main/AndroidManifest.xml"))
        val application = manifest.getElementsByTagName("application").item(0) as Element
        assertEquals(
            ".RotkiCompanionApplication",
            application.getAttribute("android:name"),
        )

        val activities = manifest.getElementsByTagName("activity")
        val mainActivity =
            (0 until activities.length)
                .map { index -> activities.item(index) as Element }
                .single { activity -> activity.getAttribute("android:name") == ".MainActivity" }
        val actions = mainActivity.getElementsByTagName("action")
        val categories = mainActivity.getElementsByTagName("category")
        val actionNames =
            (0 until actions.length)
                .map { index -> (actions.item(index) as Element).getAttribute("android:name") }
                .toSet()
        val categoryNames =
            (0 until categories.length)
                .map { index -> (categories.item(index) as Element).getAttribute("android:name") }
                .toSet()

        assertTrue("android.intent.action.MAIN" in actionNames)
        assertTrue("android.intent.category.LAUNCHER" in categoryNames)
        assertFalse("android.intent.action.VIEW" in actionNames)
        assertFalse("android.intent.category.BROWSABLE" in categoryNames)
    }

    private fun projectFile(relativePath: String): File =
        File(relativePath).also { file ->
            assertTrue("Missing Android project file: ${file.absolutePath}", file.isFile)
        }
}
