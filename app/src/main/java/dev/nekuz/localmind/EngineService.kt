package dev.nekuz.localmind

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.RemoteException
import java.io.IOException
import java.nio.charset.StandardCharsets

fun interface PieceSink {
    fun onPiece(bytes: ByteArray): Boolean
}

object LlamaNative {
    private val loaded: Boolean by lazy {
        try {
            System.loadLibrary("localmind")
            true
        } catch (e: UnsatisfiedLinkError) {
            false
        } catch (e: SecurityException) {
            false
        }
    }

    fun ready(): Boolean = loaded

    external fun nativeLoad(fd: Int, contextSize: Int, threads: Int): Int
    external fun nativeMapped(): Boolean
    external fun nativeFree()
    external fun nativeFormat(roles: Array<ByteArray>, contents: Array<ByteArray>): ByteArray?
    external fun nativeGenerate(prompt: ByteArray, maxTokens: Int, temperature: Float, sink: PieceSink): Int
    external fun nativeCancel()
    external fun nativeClearCancel()
}

object EngineProtocol {
    const val MSG_LOAD = 1
    const val MSG_GENERATE = 2
    const val MSG_STOP = 3
    const val MSG_UNLOAD = 4
    const val MSG_KILL = 5

    const val REPLY_LOADED = 11
    const val REPLY_PIECE = 12
    const val REPLY_DONE = 13

    const val KEY_FILE = "file"
    const val KEY_REQUIRE_ISOLATED = "requireIsolated"
    const val KEY_ROLES = "roles"
    const val KEY_CONTENTS = "contents"
    const val KEY_TEMPERATURE = "temperature"
    const val KEY_MAX_TOKENS = "maxTokens"
    const val KEY_TEXT = "text"
    const val KEY_TOKENS = "tokens"
    const val KEY_MILLIS = "millis"

    const val MAX_REQUEST_CHARS = 64_000
    const val MAX_OUTPUT_BYTES = 256 * 1024
    const val PIECE_BATCH_MS = 40L
}

class EngineService : Service() {
    private lateinit var workerThread: HandlerThread
    private lateinit var worker: Handler
    private var modelFile: ParcelFileDescriptor? = null

    @Volatile
    private var cancelledUpTo = 0

    private val incoming by lazy {
        Messenger(Handler(Looper.getMainLooper()) { message -> handle(message) })
    }

    override fun onCreate() {
        super.onCreate()
        workerThread = HandlerThread("engine")
        workerThread.start()
        worker = Handler(workerThread.looper)
    }

    override fun onBind(intent: Intent?): IBinder = incoming.binder

    override fun onDestroy() {
        super.onDestroy()
        if (LlamaNative.ready()) LlamaNative.nativeCancel()
        worker.post {
            release()
            workerThread.quit()
        }
    }

    private fun handle(message: Message): Boolean {
        val reply = message.replyTo
        val data = message.peekData()
        data?.classLoader = EngineService::class.java.classLoader
        when (message.what) {
            EngineProtocol.MSG_LOAD -> {
                @Suppress("DEPRECATION")
                val file = data?.getParcelable<ParcelFileDescriptor>(EngineProtocol.KEY_FILE)
                val requireIsolated = data?.getBoolean(EngineProtocol.KEY_REQUIRE_ISOLATED) ?: true
                worker.post { load(file, requireIsolated, reply) }
            }
            EngineProtocol.MSG_GENERATE -> {
                val id = message.arg1
                val roles = data?.getStringArray(EngineProtocol.KEY_ROLES)
                val contents = data?.getStringArray(EngineProtocol.KEY_CONTENTS)
                val temperature = data?.getFloat(EngineProtocol.KEY_TEMPERATURE) ?: PromptBuilder.TEMPERATURE_GENERAL
                val maxTokens = data?.getInt(EngineProtocol.KEY_MAX_TOKENS) ?: 0
                worker.post { generate(id, roles, contents, temperature, maxTokens, reply) }
            }
            EngineProtocol.MSG_STOP -> {
                cancelledUpTo = maxOf(cancelledUpTo, message.arg1)
                if (LlamaNative.ready()) LlamaNative.nativeCancel()
            }
            EngineProtocol.MSG_UNLOAD -> worker.post { release() }
            EngineProtocol.MSG_KILL -> Process.killProcess(Process.myPid())
        }
        return true
    }

    private fun release() {
        if (LlamaNative.ready()) LlamaNative.nativeFree()
        try {
            modelFile?.close()
        } catch (e: IOException) {
            modelFile = null
        }
        modelFile = null
    }

    private fun load(file: ParcelFileDescriptor?, requireIsolated: Boolean, reply: Messenger?) {
        release()
        modelFile = file
        val isolated = Process.isIsolated()
        val code = when {
            EnginePolicy.mustRefuse(requireIsolated, isolated) -> EngineCodes.ERR_NOT_ISOLATED
            file == null -> EngineCodes.ERR_FILE
            !LlamaNative.ready() -> EngineCodes.ERR_NO_LIBRARY
            else -> {
                val threads = Runtime.getRuntime().availableProcessors().coerceIn(1, 4)
                LlamaNative.nativeLoad(file.fd, MemoryGate.CONTEXT_TOKENS, threads)
            }
        }
        if (code != EngineCodes.OK) release()
        val mapped = code == EngineCodes.OK && LlamaNative.nativeMapped()
        send(reply, EngineProtocol.REPLY_LOADED, code, EnginePolicy.flags(isolated, mapped))
    }

    private fun generate(
        id: Int,
        roles: Array<String>?,
        contents: Array<String>?,
        temperature: Float,
        maxTokens: Int,
        reply: Messenger?,
    ) {
        if (!LlamaNative.ready()) {
            send(reply, EngineProtocol.REPLY_DONE, id, EngineCodes.ERR_NO_LIBRARY)
            return
        }
        LlamaNative.nativeClearCancel()
        if (id <= cancelledUpTo) {
            send(reply, EngineProtocol.REPLY_DONE, id, EngineCodes.CANCELLED)
            return
        }
        if (roles == null || contents == null || roles.isEmpty() || roles.size != contents.size ||
            contents.sumOf { it.length.toLong() } > EngineProtocol.MAX_REQUEST_CHARS
        ) {
            send(reply, EngineProtocol.REPLY_DONE, id, EngineCodes.ERR_ARGS)
            return
        }
        val prompt = LlamaNative.nativeFormat(
            Array(roles.size) { roles[it].toByteArray(StandardCharsets.UTF_8) },
            Array(contents.size) { contents[it].toByteArray(StandardCharsets.UTF_8) },
        )
        if (prompt == null) {
            send(reply, EngineProtocol.REPLY_DONE, id, EngineCodes.ERR_FORMAT)
            return
        }
        val sink = BatchingSink(id, reply)
        val code = LlamaNative.nativeGenerate(prompt, maxTokens, temperature, sink)
        sink.finish()
        val done = Message.obtain(null, EngineProtocol.REPLY_DONE, id, code)
        done.data = Bundle().apply {
            putInt(EngineProtocol.KEY_TOKENS, sink.tokens)
            putLong(EngineProtocol.KEY_MILLIS, sink.millis())
        }
        try {
            reply?.send(done)
        } catch (e: RemoteException) {
            return
        }
    }

    private inner class BatchingSink(private val id: Int, private val reply: Messenger?) : PieceSink {
        private val decoder = Utf8Stream()
        private val pending = StringBuilder()
        private var lastSent = 0L
        private var total = 0
        private var firstAt = 0L
        private var lastAt = 0L
        var tokens = 0
            private set

        fun millis(): Long = if (tokens > 1) lastAt - firstAt else 0L

        override fun onPiece(bytes: ByteArray): Boolean {
            total += bytes.size
            val at = System.nanoTime() / 1_000_000
            if (tokens == 0) firstAt = at
            lastAt = at
            tokens++
            if (total > EngineProtocol.MAX_OUTPUT_BYTES) return false
            pending.append(decoder.feed(bytes))
            val now = System.currentTimeMillis()
            if (now - lastSent >= EngineProtocol.PIECE_BATCH_MS) return flush(now)
            return true
        }

        fun finish() {
            pending.append(decoder.finish())
            flush(System.currentTimeMillis())
        }

        private fun flush(now: Long): Boolean {
            lastSent = now
            if (pending.isEmpty()) return true
            val text = pending.toString()
            pending.setLength(0)
            val message = Message.obtain(null, EngineProtocol.REPLY_PIECE, id, 0)
            message.data = Bundle().apply { putString(EngineProtocol.KEY_TEXT, text) }
            return try {
                reply?.send(message)
                reply != null
            } catch (e: RemoteException) {
                false
            }
        }
    }

    private fun send(reply: Messenger?, what: Int, arg1: Int, arg2: Int = 0) {
        try {
            reply?.send(Message.obtain(null, what, arg1, arg2))
        } catch (e: RemoteException) {
            return
        }
    }
}
