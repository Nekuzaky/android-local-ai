package dev.nekuz.localmind

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

class CancelToken {
    @Volatile
    var cancelled = false
        private set

    @Volatile
    private var hook: (() -> Unit)? = null

    fun cancel() {
        cancelled = true
        val current = hook ?: return
        val runner = Thread {
            try {
                current()
            } catch (e: RuntimeException) {
                hook = null
            }
        }
        runner.isDaemon = true
        runner.start()
    }

    fun onCancel(action: (() -> Unit)?) {
        hook = action
        if (cancelled && action != null) {
            try {
                action()
            } catch (e: RuntimeException) {
                return
            }
        }
    }
}

object AtomicFiles {
    fun write(file: File, text: String): Boolean = writeBytes(file, text.toByteArray(StandardCharsets.UTF_8))

    fun writeBytes(file: File, bytes: ByteArray): Boolean {
        val parent = file.parentFile ?: return false
        if (!parent.isDirectory && !parent.mkdirs() && !parent.isDirectory) return false
        var temp: File? = null
        return try {
            val created = File.createTempFile(file.name.take(20).padEnd(3, '_'), ".tmp", parent)
            temp = created
            FileOutputStream(created).use { out ->
                out.write(bytes)
                out.flush()
                out.fd.sync()
            }
            moveReplacing(created, file)
            temp = null
            true
        } catch (e: IOException) {
            false
        } catch (e: SecurityException) {
            false
        } catch (e: InvalidPathException) {
            false
        } finally {
            temp?.delete()
        }
    }

    fun moveReplacing(from: File, to: File) {
        try {
            Files.move(from.toPath(), to.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: AtomicMoveNotSupportedException) {
            Files.move(from.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    fun readText(file: File, maxBytes: Int): String? = try {
        if (!file.isFile || file.length() > maxBytes) {
            null
        } else {
            file.inputStream().use { input ->
                Bounded.readBytes(input, maxBytes)?.let { String(it, StandardCharsets.UTF_8) }
            }
        }
    } catch (e: IOException) {
        null
    } catch (e: SecurityException) {
        null
    }
}

object Bounded {
    fun readBytes(input: InputStream, maxBytes: Int): ByteArray? {
        val out = ByteArrayOutputStream(minOf(maxBytes, 8192).coerceAtLeast(16))
        val buffer = ByteArray(8192)
        var total = 0
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            if (total > maxBytes || total < 0) return null
            out.write(buffer, 0, count)
        }
        return out.toByteArray()
    }
}

object Sha256 {
    private val HEX = Regex("[0-9a-f]{64}")

    fun isValidHex(value: String?): Boolean = value != null && HEX.matches(value)

    fun ofBytes(bytes: ByteArray): String = hex(MessageDigest.getInstance("SHA-256").digest(bytes))

    fun ofFile(file: File, cancel: CancelToken? = null, onBytes: ((Long) -> Unit)? = null): String? = try {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(256 * 1024)
            var done = 0L
            var cancelled = false
            while (true) {
                if (cancel?.cancelled == true) {
                    cancelled = true
                    break
                }
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
                done += count
                onBytes?.invoke(done)
            }
            if (cancelled) null else hex(digest.digest())
        }
    } catch (e: IOException) {
        null
    } catch (e: SecurityException) {
        null
    }

    private fun hex(bytes: ByteArray): String {
        val out = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val value = b.toInt() and 0xFF
            out.append(Character.forDigit(value ushr 4, 16))
            out.append(Character.forDigit(value and 15, 16))
        }
        return out.toString()
    }
}

class Utf8Stream {
    private var pending = ByteArray(0)

    fun feed(bytes: ByteArray): String {
        val joined = if (pending.isEmpty()) bytes else pending + bytes
        val keep = incompleteTail(joined)
        val usable = joined.size - keep
        pending = if (keep == 0) ByteArray(0) else joined.copyOfRange(usable, joined.size)
        return if (usable == 0) "" else String(joined, 0, usable, StandardCharsets.UTF_8)
    }

    fun finish(): String {
        val rest = if (pending.isEmpty()) "" else String(pending, StandardCharsets.UTF_8)
        pending = ByteArray(0)
        return rest
    }

    private fun incompleteTail(bytes: ByteArray): Int {
        val limit = minOf(3, bytes.size)
        for (back in 1..limit) {
            val value = bytes[bytes.size - back].toInt() and 0xFF
            if (value and 0xC0 == 0x80) continue
            val needed = when {
                value and 0xE0 == 0xC0 -> 2
                value and 0xF0 == 0xE0 -> 3
                value and 0xF8 == 0xF0 -> 4
                else -> 1
            }
            return if (needed > back) back else 0
        }
        return 0
    }
}
