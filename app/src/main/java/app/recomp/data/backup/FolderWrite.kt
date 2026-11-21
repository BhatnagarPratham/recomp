package app.recomp.data.backup

import app.recomp.domain.export.BackupRotation
import java.io.IOException

/**
 * The document calls one write into the backup folder makes, so the order of its steps can be tested without a
 * provider. Addresses are strings; [same] says whether two of them are one document.
 */
interface FolderDocuments {
    fun list(): List<StoredFile>

    /** A new document; the provider may give it another name when [name] is taken. Null when it won't. */
    fun create(mimeType: String, name: String): String?

    /** Copies the backup into [uri] and checks the size the provider reports; throws when either fails. */
    fun copyInto(uri: String)

    /** The renamed document's address. Some providers return null even when the rename worked (#27 N4). */
    fun rename(uri: String, name: String): String?

    fun delete(uri: String)

    fun same(a: String, b: String): Boolean
}

/**
 * Writes the day's backup into a folder so that a complete backup exists at every step, whatever moment the process
 * dies at (#24's leftover): the new backup goes to `<name>.part` and is checked, then renamed to the day's name, and
 * only then is the old file of that name deleted. A part is therefore never the only complete copy, and the next run
 * can delete any part it finds.
 */
object FolderWrite {
    fun write(docs: FolderDocuments, name: String): StoredFile {
        val partName = BackupRotation.partName(name)
        val existing = docs.list().firstOrNull { it.name == name }
        val part = docs.create("application/octet-stream", partName) ?: throw IOException("The folder didn't take a new file")
        try {
            docs.copyInto(part)
        } catch (e: Exception) {
            runCatching { docs.delete(part) }
            throw e
        }
        // A provider that won't take a name in use may refuse here (it throws or returns null); one that makes a copy
        // gives the new file "(n)", which the rotation handles.
        val renamed = runCatching { docs.rename(part, name) }.getOrNull()?.let { StoredFile(name, it) }
            ?: renamedAfterAll(runCatching { docs.list() }.getOrDefault(emptyList()), part, partName, name, existing, docs::same)
        if (renamed != null) {
            dropOld(docs, existing, renamed)
            return renamed
        }
        // No rename here: a new document is written beside the old one (the provider names it "(n)" if it must),
        // and the part and the old file go once it's complete.
        val fresh = runCatching { docs.create("application/zip", name) }.getOrNull()
        if (fresh != null) {
            try {
                docs.copyInto(fresh)
            } catch (e: Exception) {
                runCatching { docs.delete(fresh) }
                runCatching { docs.delete(part) }
                throw e
            }
            runCatching { docs.delete(part) }
            val written = StoredFile(name, fresh)
            dropOld(docs, existing, written)
            return written
        }
        // Last resort, a provider that neither renames nor creates: the old file is written in place.
        runCatching { docs.delete(part) }
        val old = existing ?: throw IOException("The folder didn't take a new file")
        docs.copyInto(old.uri)
        return old
    }

    /** Deletes the day's old file once the new one is in place, unless the provider replaced it as that same document. */
    private fun dropOld(docs: FolderDocuments, existing: StoredFile?, written: StoredFile) {
        if (existing != null && !docs.same(existing.uri, written.uri)) runCatching { docs.delete(existing.uri) }
    }

    /**
     * After a rename that gave back no address, the folder as it now is decides whether it happened anyway: the part
     * is no longer there under its own name, and the document now holding [name] is the part's (or, by name, isn't the
     * old file). Returns that document, or null when the rename really failed and the backup must be written another
     * way. Never writing it twice is the point (#27 N4).
     */
    fun renamedAfterAll(
        listing: List<StoredFile>,
        part: String,
        partName: String,
        name: String,
        existing: StoredFile?,
        same: (String, String) -> Boolean,
    ): StoredFile? {
        if (listing.isEmpty() || listing.any { it.name == partName }) return null
        listing.firstOrNull { same(it.uri, part) }?.let { return it }
        return listing.firstOrNull { it.name == name && (existing == null || !same(it.uri, existing.uri)) }
    }
}
