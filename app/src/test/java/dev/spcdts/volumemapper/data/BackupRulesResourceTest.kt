package dev.spcdts.volumemapper.data

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

class BackupRulesResourceTest {
    @Test
    fun `manifest references backup rules for both platform generations`() {
        val application = parse("src/main/AndroidManifest.xml")
            .getElementsByTagName("application")
            .item(0) as Element

        assertEquals("true", application.androidAttribute("allowBackup"))
        assertEquals("@xml/backup_rules", application.androidAttribute("fullBackupContent"))
        assertEquals("@xml/data_extraction_rules", application.androidAttribute("dataExtractionRules"))
    }

    @Test
    fun `legacy backup excludes the settings datastore`() {
        val document = parse("src/main/res/xml/backup_rules.xml")

        assertEquals("full-backup-content", document.documentElement.tagName)
        assertHasDatastoreExclude(document.documentElement)
    }

    @Test
    fun `api 31 rules exclude settings from cloud backup and device transfer`() {
        val root = parse("src/main/res/xml/data_extraction_rules.xml").documentElement

        assertEquals("data-extraction-rules", root.tagName)
        listOf("cloud-backup", "device-transfer").forEach { sectionName ->
            val sections = root.getElementsByTagName(sectionName)
            assertEquals("Expected exactly one $sectionName section", 1, sections.length)
            assertHasDatastoreExclude(sections.item(0) as Element)
        }
    }

    private fun assertHasDatastoreExclude(parent: Element) {
        val matches = parent.getElementsByTagName("exclude")
            .let { nodes ->
                (0 until nodes.length)
                    .map { nodes.item(it) as Element }
                    .filter { element ->
                        element.getAttribute("domain") == "file" &&
                            element.getAttribute("path") == DATASTORE_PATH
                    }
            }

        assertTrue("Missing exclusion for $DATASTORE_PATH", matches.isNotEmpty())
    }

    private fun parse(relativePath: String) =
        DocumentBuilderFactory.newInstance()
            .apply { isNamespaceAware = true }
            .newDocumentBuilder()
            .parse(File(System.getProperty("user.dir"), relativePath))

    private fun Element.androidAttribute(name: String): String =
        getAttributeNS(ANDROID_NAMESPACE, name)

    private companion object {
        const val ANDROID_NAMESPACE = "http://schemas.android.com/apk/res/android"
        const val DATASTORE_PATH = "datastore/volume_mapper.preferences_pb"
    }
}
