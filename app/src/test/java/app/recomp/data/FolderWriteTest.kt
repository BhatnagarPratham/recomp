package app.recomp.data

import app.recomp.data.backup.FolderDocuments
import app.recomp.data.backup.FolderWrite
import app.recomp.data.backup.SafBackupStorage
import app.recomp.data.backup.StoredFile
import app.recomp.domain.export.BackupRotation
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** How a provider renames: the address back, null although it renamed (#27 N4), or not at all. */
enum class Rename { RETURNS_URI, RETURNS_NULL, UNSUPPORTED }

/** The process dying at a given step. */
class Died : Error()

/**
 * A folder of documents by id, as a provider shows them. Every change is a numbered step, and the process can die at
 * any of them ([dieAt]); a copy dies halfway, leaving half the bytes. A taken name gets "(n)" on create, and on rename
 * too unless [refuseTaken].
 */
class FakeFolder(private val payload: ByteArray, var rename: Rename = Rename.RETURNS_URI, var refuseTaken: Boolean = false) : FolderDocuments {
    class Doc(var name: String, var bytes: ByteArray)

    val docs = linkedMapOf<String, Doc>()
    private var nextId = 0
    private var step = 0
    var dieAt = -1
    var copies = 0
    val log = mutableListOf<String>()

    /** Stands in for the rename, for a provider that behaves another way. */
    var renameWith: ((uri: String, name: String) -> String?)? = null

    private fun step() {
        if (step++ == dieAt) throw Died()
    }

    fun add(name: String, bytes: ByteArray): String = "doc:${nextId++}".also { docs[it] = Doc(name, bytes) }

    private fun free(name: String): String {
        if (docs.values.none { it.name == name }) return name
        val base = name.removeSuffix(".zip")
        return (1..99).map { "$base ($it).zip" }.first { n -> docs.values.none { it.name == n } }
    }

    override fun list() = docs.map { (id, d) -> StoredFile(d.name, id) }

    override fun create(mimeType: String, name: String): String {
        step()
        log += "create $name"
        return add(free(name), ByteArray(0))
    }

    override fun copyInto(uri: String) {
        step()
        copies++
        val doc = docs.getValue(uri)
        doc.bytes = payload.copyOf(payload.size / 2)
        step()
        doc.bytes = payload.copyOf()
    }

    override fun rename(uri: String, name: String): String? {
        renameWith?.let { return it(uri, name) }
        if (rename == Rename.UNSUPPORTED) throw UnsupportedOperationException("Rename not supported")
        if (refuseTaken && docs.values.any { it.name == name }) throw IllegalStateException("Name taken")
        step()
        log += "rename ${docs.getValue(uri).name}"
        docs.getValue(uri).name = free(name)
        return if (rename == Rename.RETURNS_URI) uri else null
    }

    override fun delete(uri: String) {
        step()
        log += "delete ${docs.getValue(uri).name}"
        docs.remove(uri)
    }

    override fun same(a: String, b: String) = a == b
}

/**
 * The automatic backup's write into a folder (#24's leftover, #27 N3 and N4): a complete backup exists whenever the
 * process dies, a rename the provider didn't report is never written twice, and one document's two spellings match.
 */
@RunWith(RobolectricTestRunner::class)
class FolderWriteTest {
    private val name = BackupRotation.fileName(LocalDate.of(2026, 9, 13))
    private val old = ByteArray(40) { 1 }
    private val new = ByteArray(60) { 2 }

    /** What the next run's start does: every part goes. */
    private fun nextRunCleans(folder: FakeFolder) = folder.docs.entries.removeIf { BackupRotation.isPart(it.value.name) }

    private fun complete(folder: FakeFolder) = folder.docs.values.filter { BackupRotation.dateOf(it.name) != null }
        .any { it.bytes.contentEquals(old) || it.bytes.contentEquals(new) }

    @Test
    fun `wherever the process dies, a complete backup of the day is left once the next run cleans the parts`() {
        for (provider in listOf(Rename.RETURNS_URI, Rename.RETURNS_NULL, Rename.UNSUPPORTED)) for (refuse in listOf(false, true)) {
            var finished = false
            var at = 0
            while (!finished) {
                val folder = FakeFolder(new, provider, refuse)
                folder.add(name, old)
                folder.dieAt = at
                finished = runCatching { FolderWrite.write(folder, name) }.exceptionOrNull() !is Died
                nextRunCleans(folder)
                assertTrue("$provider refuse=$refuse died at step $at", complete(folder))
                if (finished) {
                    // The run finished: the new backup is the day's only file.
                    val day = folder.docs.values.filter { BackupRotation.dateOf(it.name) != null }
                    assertEquals("$provider refuse=$refuse", 1, day.size)
                    assertTrue(day.single().bytes.contentEquals(new))
                }
                at++
            }
        }
    }

    @Test
    fun `the old file is deleted only after the new one has its name`() {
        val folder = FakeFolder(new)
        folder.add(name, old)
        val written = FolderWrite.write(folder, name)
        val part = BackupRotation.partName(name)
        assertEquals(listOf("create $part", "rename $part", "delete $name"), folder.log)
        assertEquals(1, folder.docs.size)
        assertTrue(folder.docs.getValue(written.uri).bytes.contentEquals(new))
    }

    @Test
    fun `a rename that worked but returned no address isn't written twice`() {
        val folder = FakeFolder(new, Rename.RETURNS_NULL)
        folder.add(name, old)
        val written = FolderWrite.write(folder, name)
        assertEquals(1, folder.copies)
        assertEquals(1, folder.docs.size)
        assertTrue(folder.docs.getValue(written.uri).bytes.contentEquals(new))
    }

    @Test
    fun `whether a rename happened is read from the folder as it now is`() {
        val part = BackupRotation.partName(name)
        val same = { a: String, b: String -> a == b }
        val existing = StoredFile(name, "doc:0")
        // The part is still there under its own name: the rename failed.
        assertNull(FolderWrite.renamedAfterAll(listOf(existing, StoredFile(part, "doc:1")), "doc:1", part, name, existing, same))
        // The part's document now has the day's name (the provider kept its id), beside the old file.
        assertEquals(StoredFile(name.replace(".zip", " (1).zip"), "doc:1"),
            FolderWrite.renamedAfterAll(listOf(existing, StoredFile(name.replace(".zip", " (1).zip"), "doc:1")), "doc:1", part, name, existing, same))
        // The id changed and nothing reported it: the file with the day's name that isn't the old one.
        assertEquals(StoredFile(name, "doc:7"), FolderWrite.renamedAfterAll(listOf(StoredFile(name, "doc:7")), "doc:1", part, name, null, same))
        // Only the old file has the name, and the part is gone: not renamed, so it's written another way.
        assertNull(FolderWrite.renamedAfterAll(listOf(existing), "doc:1", part, name, existing, same))
        // The folder couldn't be listed: not known, so not assumed.
        assertNull(FolderWrite.renamedAfterAll(emptyList(), "doc:1", part, name, null, same))
    }

    @Test
    fun `a provider that renames over the old file as the same document doesn't then delete it`() {
        val folder = FakeFolder(new)
        val oldId = folder.add(name, old)
        // Like a file-path provider: the new file takes the old one's place, and so its address.
        folder.renameWith = { uri, name ->
            folder.docs[oldId] = FakeFolder.Doc(name, folder.docs.getValue(uri).bytes)
            folder.docs.remove(uri)
            oldId
        }
        val written = FolderWrite.write(folder, name)
        assertEquals(oldId, written.uri)
        assertEquals(1, folder.docs.size)
        assertTrue(folder.docs.getValue(oldId).bytes.contentEquals(new))
    }

    @Test
    fun `one document's two spellings are the same document, and different documents aren't`() {
        val tree = "content://com.android.externalstorage.documents/tree/primary%3ABackups/document/primary%3ABackups%2F$name"
        val plain = "content://com.android.externalstorage.documents/document/primary%3ABackups%2F$name"
        val lower = "content://com.android.externalstorage.documents/tree/primary%3aBackups/document/primary%3aBackups%2F$name"
        assertTrue(SafBackupStorage.sameDocument(tree, plain))
        assertTrue(SafBackupStorage.sameDocument(tree, lower))
        assertFalse(SafBackupStorage.sameDocument(tree, tree.replace("09-13", "09-06")))
        assertFalse(SafBackupStorage.sameDocument(tree, plain.replace("externalstorage", "other")))
        assertFalse(SafBackupStorage.sameDocument("uri:a", "uri:b"))
        assertTrue(SafBackupStorage.sameDocument("uri:a", "uri:a"))
    }
}
