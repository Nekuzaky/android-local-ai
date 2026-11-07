package dev.nekuz.localmind

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class Hub private constructor(val app: Context) {
    val main = Handler(Looper.getMainLooper())
    val io: ExecutorService = Executors.newSingleThreadExecutor()

    private val listeners = LinkedHashSet<() -> Unit>()
    private val notifyQueued = AtomicBoolean(false)
    private val settingsFile = File(app.filesDir, "settings.json")

    @Volatile
    var settings: AppSettings = SettingsCodec.decode(AtomicFiles.readText(settingsFile, 64 * 1024))
        private set

    val engine = EngineClient(app) { changed() }
    val models = ModelStore(this)
    val chat = ChatStore(this)
    val pc = PcStore(this)
    val search = SearchStore(this)
    val video = VideoStore(this)

    fun addListener(listener: () -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: () -> Unit) {
        listeners.remove(listener)
    }

    fun changed() {
        if (!notifyQueued.compareAndSet(false, true)) return
        main.post {
            notifyQueued.set(false)
            ArrayList(listeners).forEach { it() }
        }
    }

    fun updateSettings(transform: (AppSettings) -> AppSettings) {
        val next = transform(settings)
        settings = next
        io.execute { AtomicFiles.write(settingsFile, SettingsCodec.encode(next)) }
        changed()
    }

    fun background(onFailure: (() -> Unit)? = null, block: () -> Unit) {
        val thread = Thread {
            try {
                block()
            } catch (e: Exception) {
                onFailure?.let { main.post(it) }
            } catch (e: OutOfMemoryError) {
                onFailure?.let { main.post(it) }
            } catch (e: StackOverflowError) {
                onFailure?.let { main.post(it) }
            }
        }
        thread.isDaemon = true
        thread.start()
    }

    fun whereLine(): String {
        val current = settings
        if (current.usePc) {
            val model = current.ollamaModel.ifEmpty { "no model chosen" }
            val address = current.ollamaAddress.ifEmpty { "no address set" }
            return "Runs on: PC $address · $model"
        }
        val loaded = models.loadedName()
        return if (loaded == null) "Runs on: Phone · no model loaded" else "Runs on: Phone · $loaded"
    }

    companion object {
        @Volatile
        private var instance: Hub? = null

        fun get(context: Context): Hub = instance ?: synchronized(this) {
            instance ?: Hub(context.applicationContext).also { instance = it }
        }
    }
}
