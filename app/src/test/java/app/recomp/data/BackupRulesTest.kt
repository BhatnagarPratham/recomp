package app.recomp.data

import app.recomp.data.ai.TinkSecretCipher
import app.recomp.di.AiModule
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * The API key never reaches a backup (TRD §6.2, §6.5): the backup rules exclude exactly the files the key store
 * writes, from both cloud backup and device-to-device transfer, and the manifest uses those rules.
 */
class BackupRulesTest {
    private val res = File("src/main/res/xml/data_extraction_rules.xml")

    private fun excludes(section: String): Set<Pair<String, String>> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(res)
        val parent = doc.getElementsByTagName(section).item(0) as Element
        val nodes = parent.getElementsByTagName("exclude")
        return (0 until nodes.length).map { (nodes.item(it) as Element).let { e -> e.getAttribute("domain") to e.getAttribute("path") } }.toSet()
    }

    @Test
    fun `the encrypted key and its keyset are left out of cloud backup and device transfer`() {
        val secrets = "file" to "datastore/${AiModule.SECRETS_FILE}.preferences_pb"
        val keyset = "sharedpref" to "${TinkSecretCipher.KEYSET_PREFS}.xml"
        listOf("cloud-backup", "device-transfer").forEach { section ->
            val excluded = excludes(section)
            assertTrue("$section excludes $secrets", secrets in excluded)
            assertTrue("$section excludes $keyset", keyset in excluded)
        }
        val manifest = File("src/main/AndroidManifest.xml").readText()
        assertTrue(manifest.contains("android:dataExtractionRules=\"@xml/data_extraction_rules\""))
    }
}
