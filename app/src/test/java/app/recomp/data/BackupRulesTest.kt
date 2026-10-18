package app.recomp.data

import app.recomp.data.ai.TinkSecretCipher
import app.recomp.di.AiModule
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * The API key never reaches a backup (TRD §6.2, §6.5): neither file the key store writes is in cloud backup or
 * device-to-device transfer, and the manifest uses those rules. The rules list what's included (the database and
 * the settings), so the key files are left out by omission; lint rejects an `<exclude>` outside an included path.
 */
class BackupRulesTest {
    private val res = File("src/main/res/xml/data_extraction_rules.xml")

    private fun rules(section: String, tag: String): Set<Pair<String, String>> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(res)
        val parent = doc.getElementsByTagName(section).item(0) as Element
        val nodes = parent.getElementsByTagName(tag)
        return (0 until nodes.length).map { (nodes.item(it) as Element).let { e -> e.getAttribute("domain") to e.getAttribute("path") } }.toSet()
    }

    /** Left out when excluded by name, or when the section lists includes and none covers it. */
    private fun leftOut(section: String, file: Pair<String, String>): Boolean {
        if (file in rules(section, "exclude")) return true
        val includes = rules(section, "include")
        return includes.isNotEmpty() && includes.none { (domain, path) -> domain == file.first && (path == file.second || file.second.startsWith("$path/")) }
    }

    @Test
    fun `the encrypted key and its keyset are left out of cloud backup and device transfer`() {
        val secrets = "file" to "datastore/${AiModule.SECRETS_FILE}.preferences_pb"
        val keyset = "sharedpref" to "${TinkSecretCipher.KEYSET_PREFS}.xml"
        listOf("cloud-backup", "device-transfer").forEach { section ->
            assertTrue("$section leaves out $secrets", leftOut(section, secrets))
            assertTrue("$section leaves out $keyset", leftOut(section, keyset))
            // What a restore needs is still in.
            assertTrue("$section keeps the database", ("database" to "recomp.db") in rules(section, "include"))
            assertTrue("$section keeps the settings", ("file" to "datastore/settings.preferences_pb") in rules(section, "include"))
        }
        val manifest = File("src/main/AndroidManifest.xml").readText()
        assertTrue(manifest.contains("android:dataExtractionRules=\"@xml/data_extraction_rules\""))
    }
}
