package dev.nekuz.localmind

import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets

class SearchStore(private val hub: Hub) {
    private val indexFile = File(File(hub.app.filesDir, "search"), "index.jsonl")

    var building = false
        private set
    var progress = 0
        private set
    var header: IndexHeader? = null
        private set
    var hits: List<SearchHit> = emptyList()
        private set
    var message: String? = null
        private set
    var chunkCount = 0
        private set

    private var index: Bm25Index? = null
    private var loaded = false
    private var token: CancelToken? = null
    private var generation = 0

    fun ensureLoaded() {
        if (loaded) return
        loaded = true
        val started = generation
        hub.background({
            message = "The saved index is too large to open on this phone. Delete it or rebuild it from a smaller folder."
            hub.changed()
        }) {
            val stored = SearchIndexCodec.read(indexFile)
            if (stored != null) {
                val built = Bm25Index(stored.chunks)
                hub.main.post {
                    if (!building && generation == started) {
                        header = stored.header
                        index = built
                        chunkCount = stored.chunks.size
                        message = when {
                            built.capped -> CAPPED
                            stored.damagedLines > 0 -> "${stored.damagedLines} damaged index lines were skipped."
                            else -> null
                        }
                        hub.changed()
                    }
                }
            }
        }
    }

    fun folderPicked(uri: Uri) {
        if (uri.scheme != "content") return
        try {
            hub.app.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (e: SecurityException) {
            message = "Android did not keep access to this folder, so it can only be read now."
        }
        val previous = hub.settings.searchFolder
        if (previous != uri.toString()) releaseGrant(previous)
        hub.updateSettings { it.copy(searchFolder = uri.toString()) }
        rebuild()
    }

    fun rebuild() {
        val folder = hub.settings.searchFolder
        if (building) return
        if (!folder.startsWith("content://")) {
            message = "Choose a folder first."
            hub.changed()
            return
        }
        building = true
        generation++
        progress = 0
        message = null
        val current = CancelToken()
        token = current
        hub.changed()
        val failed = {
            building = false
            message = "Indexing failed. The folder may no longer be available."
            hub.changed()
        }
        hub.background(failed) {
            val outcome = walk(Uri.parse(folder), current)
            val newHeader = IndexHeader(outcome.name, outcome.files, outcome.skipped, System.currentTimeMillis())
            val wrote = if (current.cancelled) false else SearchIndexCodec.write(indexFile, newHeader, outcome.chunks)
            val built = if (wrote) Bm25Index(outcome.chunks) else null
            hub.main.post {
                building = false
                token = null
                if (built != null) {
                    index = built
                    header = newHeader
                    chunkCount = outcome.chunks.size
                    hits = emptyList()
                    message = if (outcome.capped || built.capped) CAPPED else null
                } else {
                    message = if (current.cancelled) "Indexing cancelled. The old index is unchanged." else "The index could not be saved."
                }
                hub.changed()
            }
        }
    }

    fun cancel() {
        token?.cancel()
    }

    fun deleteIndex() {
        if (building) return
        generation++
        indexFile.delete()
        index = null
        header = null
        hits = emptyList()
        chunkCount = 0
        message = null
        releaseGrant(hub.settings.searchFolder)
        hub.updateSettings { it.copy(searchFolder = "") }
    }

    private fun releaseGrant(folder: String) {
        if (!folder.startsWith("content://")) return
        try {
            hub.app.contentResolver.releasePersistableUriPermission(Uri.parse(folder), Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (e: SecurityException) {
            message = null
        }
    }

    fun search(query: String) {
        val current = index
        if (current == null) {
            message = "Build an index first."
            hub.changed()
            return
        }
        hub.background(null) {
            val found = current.search(query, MAX_HITS)
            hub.main.post {
                hits = found
                message = if (found.isEmpty()) "No matches." else null
                hub.changed()
            }
        }
    }

    fun topSnippets(): List<SearchSnippet> =
        hits.take(TOP_SNIPPETS).map { SearchSnippet(it.chunk.path, it.chunk.line, it.chunk.text) }

    private class WalkOutcome(
        val name: String,
        val chunks: List<Chunk>,
        val files: Int,
        val skipped: Int,
        val capped: Boolean,
    )

    private class Folder(val documentId: String, val path: String, val depth: Int)

    private fun walk(tree: Uri, cancel: CancelToken): WalkOutcome {
        val resolver = hub.app.contentResolver
        val rootId = DocumentsContract.getTreeDocumentId(tree)
        val rootName = resolver.query(
            DocumentsContract.buildDocumentUriUsingTree(tree, rootId),
            arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { cursor -> if (cursor.moveToFirst()) ForeignText.clean(cursor.getString(0), ForeignText.MAX_LABEL) else null } ?: "Folder"
        val stack = ArrayDeque<Folder>()
        val seen = HashSet<String>()
        stack.addLast(Folder(rootId, "", 0))
        seen.add(rootId)
        val chunks = ArrayList<Chunk>()
        var files = 0
        var skipped = 0
        var totalChars = 0L
        var capped = false
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
        )
        walking@ while (stack.isNotEmpty()) {
            val folder = stack.removeLast()
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, folder.documentId)
            val cursor = resolver.query(children, projection, null, null, null) ?: continue
            try {
                while (cursor.moveToNext()) {
                    if (cancel.cancelled) break@walking
                    val documentId = cursor.getString(0) ?: continue
                    val rawName = cursor.getString(1).orEmpty()
                    val mime = cursor.getString(2).orEmpty()
                    val size = if (cursor.isNull(3)) -1L else cursor.getLong(3)
                    val name = ForeignText.clean(rawName, 120)
                    if (name.isEmpty() || name.startsWith(".")) {
                        skipped++
                        continue
                    }
                    val path = if (folder.path.isEmpty()) name else "${folder.path}/$name".takeLast(240)
                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                        if (folder.depth < IndexCaps.MAX_DEPTH && seen.add(documentId)) {
                            stack.addLast(Folder(documentId, path, folder.depth + 1))
                        }
                        continue
                    }
                    if (files >= IndexCaps.MAX_FILES || totalChars >= IndexCaps.MAX_TOTAL_CHARS || chunks.size >= IndexCaps.MAX_CHUNKS) {
                        capped = true
                        break@walking
                    }
                    if (!TextSniff.hasTextName(rawName) || size > IndexCaps.MAX_FILE_BYTES) {
                        skipped++
                        continue
                    }
                    val bytes = try {
                        resolver.openInputStream(DocumentsContract.buildDocumentUriUsingTree(tree, documentId))
                            ?.use { stream -> Bounded.readBytes(stream, IndexCaps.MAX_FILE_BYTES) }
                    } catch (e: IOException) {
                        null
                    } catch (e: SecurityException) {
                        null
                    } catch (e: IllegalArgumentException) {
                        null
                    }
                    if (bytes == null || !TextSniff.looksLikeText(bytes)) {
                        skipped++
                        continue
                    }
                    val text = ForeignText.multiline(String(bytes, StandardCharsets.UTF_8), IndexCaps.MAX_FILE_BYTES)
                    val pieces = Chunker.split(path, text)
                    chunks.addAll(pieces)
                    totalChars += text.length
                    files++
                    if (files % 20 == 0) {
                        val done = files
                        hub.main.post {
                            progress = done
                            hub.changed()
                        }
                    }
                }
            } finally {
                cursor.close()
            }
        }
        val kept = if (chunks.size > IndexCaps.MAX_CHUNKS) chunks.subList(0, IndexCaps.MAX_CHUNKS).toList() else chunks
        return WalkOutcome(rootName, kept, files, skipped, capped)
    }

    private companion object {
        const val MAX_HITS = 50
        const val TOP_SNIPPETS = 5
        const val CAPPED = "Stopped at the index size limit, so some files or words were left out."
    }
}
