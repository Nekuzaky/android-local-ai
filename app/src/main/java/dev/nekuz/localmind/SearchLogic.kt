package dev.nekuz.localmind

import org.json.JSONObject
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.util.Locale
import kotlin.math.ln

data class Chunk(val path: String, val line: Int, val text: String)

data class SearchHit(val chunk: Chunk, val score: Double)

object IndexCaps {
    const val MAX_FILES = 4_000
    const val MAX_FILE_BYTES = 2 * 1024 * 1024
    const val MAX_TOTAL_CHARS = 12_000_000
    const val MAX_CHUNKS = 40_000
    const val MAX_DEPTH = 16
    const val MAX_INDEX_FILE_BYTES = 64L * 1024 * 1024
    const val MAX_INDEX_LINE_CHARS = 16_384
}

object TextSniff {
    private const val SAMPLE = 8192
    private val EXTENSIONS = setOf(
        "txt", "md", "markdown", "rst", "adoc", "org", "tex", "csv", "tsv", "log", "ini", "cfg", "conf", "toml", "yaml", "yml",
        "json", "jsonl", "xml", "html", "htm", "css", "scss", "less", "svg", "kt", "kts", "java", "groovy", "gradle", "scala",
        "c", "h", "cc", "cpp", "cxx", "hpp", "hh", "m", "mm", "swift", "rs", "go", "py", "pyi", "rb", "php", "pl", "lua", "r",
        "js", "jsx", "mjs", "cjs", "ts", "tsx", "vue", "svelte", "sh", "bash", "zsh", "fish", "ps1", "bat", "cmd", "sql", "cs",
        "fs", "vb", "dart", "ex", "exs", "erl", "hs", "ml", "clj", "lisp", "el", "vim", "dockerfile", "make", "mk", "cmake",
        "properties", "gitignore", "editorconfig",
    )
    private val NAMES = setOf("makefile", "dockerfile", "readme", "license", "notice", "changelog", "authors", "cmakelists.txt")

    fun hasTextName(name: String): Boolean {
        val lower = name.lowercase(Locale.ROOT)
        if (lower in NAMES) return true
        val dot = lower.lastIndexOf('.')
        if (dot < 0 || dot == lower.length - 1) return false
        return lower.substring(dot + 1) in EXTENSIONS
    }

    fun looksLikeText(bytes: ByteArray): Boolean {
        val limit = minOf(bytes.size, SAMPLE)
        var odd = 0
        for (index in 0 until limit) {
            val value = bytes[index].toInt() and 0xFF
            if (value == 0) return false
            if (value < 0x09 || value in 0x0E..0x1F || value == 0x7F) odd++
        }
        return limit == 0 || odd * 20 <= limit
    }
}

object Chunker {
    private const val MAX_LINES = 30
    private const val MAX_CHARS = 1500

    fun split(path: String, text: String): List<Chunk> {
        val out = ArrayList<Chunk>()
        val buffer = StringBuilder()
        var startLine = 1
        var lineNumber = 0
        var linesInBuffer = 0
        fun flush() {
            if (buffer.isNotBlank()) out.add(Chunk(path, startLine, buffer.toString().trimEnd()))
            buffer.setLength(0)
            linesInBuffer = 0
        }
        for (rawLine in text.lineSequence()) {
            lineNumber++
            var line = rawLine
            if (linesInBuffer == 0) startLine = lineNumber
            while (line.length > MAX_CHARS) {
                flush()
                startLine = lineNumber
                out.add(Chunk(path, lineNumber, line.substring(0, MAX_CHARS)))
                line = line.substring(MAX_CHARS)
            }
            if (buffer.length + line.length + 1 > MAX_CHARS || linesInBuffer >= MAX_LINES) {
                flush()
                startLine = lineNumber
            }
            buffer.append(line).append('\n')
            linesInBuffer++
        }
        flush()
        return out
    }
}

object Tokens {
    private const val MAX_TOKEN = 48
    private const val MAX_QUERY_TERMS = 32

    fun of(text: String): List<String> {
        val out = ArrayList<String>()
        val current = StringBuilder()
        fun flush() {
            if (current.isEmpty()) return
            addWithParts(current.toString(), out)
            current.setLength(0)
        }
        var index = 0
        while (index < text.length) {
            val codePoint = Character.codePointAt(text, index)
            index += Character.charCount(codePoint)
            if (Character.isLetterOrDigit(codePoint) || codePoint == '_'.code) {
                if (current.length < MAX_TOKEN) current.appendCodePoint(codePoint)
            } else {
                flush()
            }
        }
        flush()
        return out
    }

    fun query(text: String): List<String> = of(text).distinct().take(MAX_QUERY_TERMS)

    private fun addWithParts(word: String, out: MutableList<String>) {
        val lower = word.lowercase(Locale.ROOT)
        if (lower.trim('_').isNotEmpty()) out.add(lower)
        val parts = ArrayList<String>()
        val part = StringBuilder()
        for (i in word.indices) {
            val c = word[i]
            val boundary = c == '_' || (c.isUpperCase() && i > 0 && word[i - 1].isLowerCase())
            if (boundary && part.isNotEmpty()) {
                parts.add(part.toString())
                part.setLength(0)
            }
            if (c != '_') part.append(c)
        }
        if (part.isNotEmpty()) parts.add(part.toString())
        if (parts.size > 1) {
            for (item in parts) {
                val piece = item.lowercase(Locale.ROOT)
                if (piece.length > 1) out.add(piece)
            }
        }
    }
}

class Bm25Index(chunks: List<Chunk>, maxTerms: Int = MAX_TERMS) {
    private class Posting(var docs: IntArray = IntArray(4), var counts: IntArray = IntArray(4), var size: Int = 0) {
        fun add(doc: Int, count: Int) {
            if (size == docs.size) {
                docs = docs.copyOf(size * 2)
                counts = counts.copyOf(size * 2)
            }
            docs[size] = doc
            counts[size] = count
            size++
        }
    }

    private val documents = chunks
    private val lengths = IntArray(chunks.size)
    private val postings = HashMap<String, Posting>()
    private val averageLength: Double

    init {
        var total = 0L
        val counts = HashMap<String, Int>()
        chunks.forEachIndexed { doc, chunk ->
            counts.clear()
            val tokens = Tokens.of(chunk.text)
            lengths[doc] = tokens.size
            total += tokens.size
            for (token in tokens) counts[token] = (counts[token] ?: 0) + 1
            for ((token, count) in counts) {
                var posting = postings[token]
                if (posting == null) {
                    if (postings.size >= maxTerms) {
                        capped = true
                        continue
                    }
                    posting = Posting()
                    postings[token] = posting
                }
                posting.add(doc, count)
            }
        }
        averageLength = if (chunks.isEmpty()) 0.0 else total.toDouble() / chunks.size
    }

    val size: Int
        get() = documents.size

    var capped = false
        private set

    fun search(query: String, limit: Int): List<SearchHit> {
        if (documents.isEmpty() || limit <= 0) return emptyList()
        val scores = HashMap<Int, Double>()
        val count = documents.size.toDouble()
        for (term in Tokens.query(query)) {
            val posting = postings[term] ?: continue
            val df = posting.size.toDouble()
            val idf = ln(1.0 + (count - df + 0.5) / (df + 0.5))
            for (i in 0 until posting.size) {
                val doc = posting.docs[i]
                val tf = posting.counts[i].toDouble()
                val norm = if (averageLength > 0) lengths[doc] / averageLength else 1.0
                val score = idf * (tf * (K1 + 1)) / (tf + K1 * (1 - B + B * norm))
                scores[doc] = (scores[doc] ?: 0.0) + score
            }
        }
        return scores.entries
            .sortedWith(compareByDescending<Map.Entry<Int, Double>> { it.value }.thenBy { it.key })
            .take(limit)
            .map { SearchHit(documents[it.key], it.value) }
    }

    companion object {
        const val MAX_TERMS = 250_000
        private const val K1 = 1.2
        private const val B = 0.75
    }
}

data class IndexHeader(val folder: String, val files: Int, val skipped: Int, val builtAt: Long)

data class LoadedIndex(val header: IndexHeader, val chunks: List<Chunk>, val damagedLines: Int)

object SearchIndexCodec {
    fun write(file: File, header: IndexHeader, chunks: List<Chunk>): Boolean {
        val parent = file.parentFile ?: return false
        if (!parent.isDirectory && !parent.mkdirs() && !parent.isDirectory) return false
        var temp: File? = null
        return try {
            val created = File.createTempFile("index", ".tmp", parent)
            temp = created
            FileOutputStream(created).use { stream ->
                BufferedWriter(OutputStreamWriter(stream, StandardCharsets.UTF_8)).use { writer ->
                    writer.write(headerLine(header))
                    writer.write("\n")
                    for (chunk in chunks) {
                        writer.write(chunkLine(chunk))
                        writer.write("\n")
                    }
                    writer.flush()
                    stream.fd.sync()
                }
            }
            AtomicFiles.moveReplacing(created, file)
            temp = null
            true
        } catch (e: IOException) {
            false
        } catch (e: SecurityException) {
            false
        } finally {
            temp?.delete()
        }
    }

    fun read(file: File): LoadedIndex? = try {
        if (!file.isFile || file.length() > IndexCaps.MAX_INDEX_FILE_BYTES) {
            null
        } else {
            file.bufferedReader(StandardCharsets.UTF_8).useLines { lines -> decode(lines) }
        }
    } catch (e: IOException) {
        null
    } catch (e: SecurityException) {
        null
    }

    fun decode(lines: Sequence<String>): LoadedIndex? {
        var header: IndexHeader? = null
        val chunks = ArrayList<Chunk>()
        var damaged = 0
        var total = 0L
        for (line in lines) {
            if (header == null) {
                header = parseHeader(line) ?: return null
                continue
            }
            if (chunks.size >= IndexCaps.MAX_CHUNKS || total >= IndexCaps.MAX_TOTAL_CHARS) break
            val chunk = parseChunk(line)
            if (chunk == null) {
                damaged++
            } else {
                chunks.add(chunk)
                total += chunk.text.length
            }
        }
        return header?.let { LoadedIndex(it, chunks, damaged) }
    }

    private fun headerLine(header: IndexHeader) = JSONObject()
        .put("v", 1)
        .put("folder", header.folder)
        .put("files", header.files)
        .put("skipped", header.skipped)
        .put("at", header.builtAt)
        .toString()

    private fun chunkLine(chunk: Chunk) = JSONObject()
        .put("p", chunk.path)
        .put("l", chunk.line)
        .put("t", chunk.text)
        .toString()

    private fun parseHeader(line: String): IndexHeader? {
        val root = StrictJson.parseObject(line, IndexCaps.MAX_INDEX_LINE_CHARS) ?: return null
        if (root.long("v") != 1L) return null
        return IndexHeader(
            folder = ForeignText.clean(root.str("folder").orEmpty(), ForeignText.MAX_LABEL),
            files = root.long("files")?.coerceIn(0, Int.MAX_VALUE.toLong())?.toInt() ?: 0,
            skipped = root.long("skipped")?.coerceIn(0, Int.MAX_VALUE.toLong())?.toInt() ?: 0,
            builtAt = root.long("at")?.coerceAtLeast(0) ?: 0,
        )
    }

    private fun parseChunk(line: String): Chunk? {
        val root = StrictJson.parseObject(line, IndexCaps.MAX_INDEX_LINE_CHARS) ?: return null
        val path = root.str("p") ?: return null
        val text = root.str("t") ?: return null
        val at = root.long("l") ?: return null
        if (at < 1 || at > Int.MAX_VALUE) return null
        return Chunk(ForeignText.clean(path, 256), at.toInt(), text)
    }
}
