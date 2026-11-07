package dev.nekuz.localmind

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.ParcelFileDescriptor
import android.os.RemoteException
import java.io.File
import java.io.IOException

class EngineClient(private val app: Context, private val onChanged: () -> Unit) {
    enum class State { OFF, CONNECTING, IDLE, LOADING, READY, CRASHED }

    private class Request(
        val id: Int,
        val onPiece: (String) -> Unit,
        val onDone: (Int) -> Unit,
    )

    var state = State.OFF
        private set
    var loadedFile: String? = null
        private set
    var crashMessage: String? = null
        private set
    var lastLoadFlags = 0
        private set
    var lastTokens = 0
        private set
    var lastMillis = 0L
        private set

    private val handler = Handler(Looper.getMainLooper()) { message -> onReply(message) }
    private val replies = Messenger(handler)
    private var service: Messenger? = null
    private var bound = false
    private var nextId = 1
    private var active: Request? = null
    private var pendingLoad: ((Int) -> Unit)? = null
    private val waiting = ArrayList<() -> Unit>()

    val busy: Boolean
        get() = active != null || pendingLoad != null || state == State.LOADING

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            if (binder == null) {
                died()
                return
            }
            service = Messenger(binder)
            if (state == State.CONNECTING) state = State.IDLE
            val actions = ArrayList(waiting)
            waiting.clear()
            actions.forEach { it() }
            onChanged()
        }

        override fun onServiceDisconnected(name: ComponentName?) = died()

        override fun onBindingDied(name: ComponentName?) = died()

        override fun onNullBinding(name: ComponentName?) = died()
    }

    private fun bind(): Boolean {
        if (bound) return true
        state = State.CONNECTING
        bound = try {
            app.bindService(Intent(app, EngineService::class.java), connection, Context.BIND_AUTO_CREATE)
        } catch (e: RuntimeException) {
            false
        }
        return bound
    }

    private fun unbind() {
        if (!bound) return
        bound = false
        try {
            app.unbindService(connection)
        } catch (e: IllegalArgumentException) {
            return
        }
    }

    private fun whenConnected(action: () -> Unit, onFailed: () -> Unit) {
        if (service != null) {
            action()
            return
        }
        waiting.add(action)
        if (!bind()) {
            waiting.clear()
            onFailed()
        }
    }

    fun load(file: File, requireIsolated: Boolean, onDone: (Int) -> Unit) {
        if (busy) {
            onDone(EngineCodes.ERR_ARGS)
            return
        }
        crashMessage = null
        state = State.LOADING
        pendingLoad = onDone
        loadedFile = null
        onChanged()
        whenConnected({
            state = State.LOADING
            val descriptor = try {
                ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            } catch (e: IOException) {
                null
            }
            if (descriptor == null) {
                val callback = pendingLoad
                pendingLoad = null
                state = State.IDLE
                callback?.invoke(EngineCodes.ERR_FILE)
                onChanged()
            } else {
                val message = Message.obtain(null, EngineProtocol.MSG_LOAD)
                message.replyTo = replies
                message.data = Bundle().apply {
                    putParcelable(EngineProtocol.KEY_FILE, descriptor)
                    putBoolean(EngineProtocol.KEY_REQUIRE_ISOLATED, requireIsolated)
                }
                pendingFile = file.name
                send(message)
                try {
                    descriptor.close()
                } catch (e: IOException) {
                    state = State.LOADING
                }
            }
        }, {
            val callback = pendingLoad
            pendingLoad = null
            state = State.CRASHED
            crashMessage = DIED
            callback?.invoke(ERR_DIED)
            onChanged()
        })
    }

    private var pendingFile: String? = null

    fun generate(
        messages: List<PromptMessage>,
        temperature: Float,
        maxTokens: Int,
        onPiece: (String) -> Unit,
        onDone: (Int) -> Unit,
    ): Boolean {
        if (state != State.READY || active != null) return false
        val id = nextId++
        active = Request(id, onPiece, onDone)
        val message = Message.obtain(null, EngineProtocol.MSG_GENERATE, id, 0)
        message.replyTo = replies
        message.data = Bundle().apply {
            putStringArray(EngineProtocol.KEY_ROLES, messages.map { it.role }.toTypedArray())
            putStringArray(EngineProtocol.KEY_CONTENTS, messages.map { it.content }.toTypedArray())
            putFloat(EngineProtocol.KEY_TEMPERATURE, temperature)
            putInt(EngineProtocol.KEY_MAX_TOKENS, maxTokens)
        }
        send(message)
        onChanged()
        return true
    }

    fun stop() {
        val request = active ?: return
        send(Message.obtain(null, EngineProtocol.MSG_STOP, request.id, 0))
    }

    fun unload() {
        if (service != null) send(Message.obtain(null, EngineProtocol.MSG_UNLOAD))
        loadedFile = null
        if (state == State.READY) state = State.IDLE
        onChanged()
    }

    fun restart(onConnected: () -> Unit) {
        val running = service
        if (running != null) {
            try {
                running.send(Message.obtain(null, EngineProtocol.MSG_KILL))
            } catch (e: RemoteException) {
                service = null
            }
        }
        unbind()
        service = null
        failPending()
        loadedFile = null
        crashMessage = null
        state = State.CONNECTING
        onChanged()
        handler.postDelayed({
            waiting.add(onConnected)
            if (!bind()) {
                waiting.clear()
                died()
            }
            onChanged()
        }, RESTART_DELAY_MS)
    }

    private fun send(message: Message): Boolean {
        val target = service ?: return false
        return try {
            target.send(message)
            true
        } catch (e: RemoteException) {
            died()
            false
        }
    }

    private fun onReply(message: Message): Boolean {
        try {
            handleReply(message)
        } catch (e: RuntimeException) {
            died()
        }
        return true
    }

    private fun handleReply(message: Message): Boolean {
        when (message.what) {
            EngineProtocol.REPLY_LOADED -> {
                val code = message.arg1
                val callback = pendingLoad ?: return true
                pendingLoad = null
                lastLoadFlags = message.arg2
                if (code == EngineCodes.OK) {
                    loadedFile = pendingFile
                    state = State.READY
                } else {
                    loadedFile = null
                    state = State.IDLE
                }
                pendingFile = null
                callback(code)
                onChanged()
            }
            EngineProtocol.REPLY_PIECE -> {
                val request = active
                if (request != null && request.id == message.arg1) {
                    message.peekData()?.getString(EngineProtocol.KEY_TEXT)?.let { request.onPiece(it) }
                }
            }
            EngineProtocol.REPLY_DONE -> {
                val request = active
                if (request != null && request.id == message.arg1) {
                    active = null
                    val data = message.peekData()
                    lastTokens = data?.getInt(EngineProtocol.KEY_TOKENS) ?: 0
                    lastMillis = data?.getLong(EngineProtocol.KEY_MILLIS) ?: 0L
                    request.onDone(message.arg2)
                    onChanged()
                }
            }
        }
        return true
    }

    private fun failPending() {
        val request = active
        active = null
        request?.onDone(ERR_DIED)
        val callback = pendingLoad
        pendingLoad = null
        callback?.invoke(ERR_DIED)
        pendingFile = null
        waiting.clear()
    }

    private fun died() {
        unbind()
        service = null
        loadedFile = null
        state = State.CRASHED
        crashMessage = DIED
        failPending()
        onChanged()
    }

    companion object {
        const val ERR_DIED = -30
        const val DIED = "The on-phone engine stopped, most likely because it ran out of memory or a model crashed it. The app and your chats are safe. Tap Restart engine to load the model again."
        private const val RESTART_DELAY_MS = 600L
    }
}
