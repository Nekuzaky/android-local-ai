package dev.nekuz.localmind

import android.app.ActivityManager
import android.net.Uri
import android.os.StatFs
import android.provider.OpenableColumns
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID

class ModelStore(private val hub: Hub) {
    enum class Phase { DOWNLOADING, VERIFYING, IMPORTING, STOPPING }

    data class Job(val token: CancelToken, val phase: Phase, val done: Long, val total: Long)

    private val dir = File(hub.app.filesDir, DIR)
    private val registryFile = File(dir, "models.json")
    private val jobs = HashMap<String, Job>()
    private var loadingId: String? = null
    private var loadedId: String? = null

    var notice: String? = null
        private set

    var installed: List<InstalledModel> = emptyList()
        private set

    init {
        dir.mkdirs()
        val stored = AtomicFiles.readText(registryFile, 1024 * 1024)
        val present = ModelRegistryCodec.decode(stored).filter { File(dir, it.file).isFile }
        val recovered = Catalogue.models
            .filter { model -> present.none { it.id == model.id } && File(dir, model.localFile).isFile }
            .map { InstalledModel(it.id, it.name, it.localFile, ModelSource.CATALOGUE, it.sha256, it.sizeBytes) }
        installed = present + recovered
        if (ModelRegistryCodec.mayCleanOrphans(registryFile.exists(), stored)) cleanOrphans()
    }

    private fun cleanOrphans() {
        val names = dir.list()?.take(512) ?: return
        val known = installed.map { it.file }.toSet()
        val keptParts = Catalogue.models.map { "${it.localFile}.part" }.toSet()
        for (name in names) {
            val orphan = when {
                name == registryFile.name -> false
                name in known || name in keptParts -> false
                else -> true
            }
            if (orphan) File(dir, name).delete()
        }
    }

    fun job(id: String): Job? = jobs[id]

    fun isLoading(id: String) = loadingId == id

    fun isLoaded(id: String) = loadedId == id && hub.engine.loadedFile != null

    fun loadedModel(): InstalledModel? {
        val file = hub.engine.loadedFile ?: return null
        return installed.firstOrNull { it.file == file }
    }

    fun loadedName(): String? = loadedModel()?.name

    fun partBytes(model: CatalogueModel): Long {
        val part = File(dir, "${model.localFile}.part")
        return if (part.isFile) part.length() else 0
    }

    fun find(id: String): InstalledModel? = installed.firstOrNull { it.id == id }

    fun clearNotice() {
        notice = null
        hub.changed()
    }

    private fun say(text: String?) {
        notice = text
        hub.changed()
    }

    fun download(model: CatalogueModel) {
        if (jobs.containsKey(model.id) || find(model.id) != null) return
        val token = CancelToken()
        jobs[model.id] = Job(token, Phase.DOWNLOADING, partBytes(model), model.sizeBytes)
        say(null)
        val part = File(dir, "${model.localFile}.part")
        val finish = { text: String? ->
            hub.main.post {
                if (jobs[model.id]?.token === token) jobs.remove(model.id)
                say(text)
            }
            Unit
        }
        hub.background({ finish("The download failed.") }) {
            var lastPost = 0L
            val result = ResumableDownload(
                url = model.url,
                partFile = part,
                expectedSize = model.sizeBytes,
                expectedSha256 = model.sha256,
                allowUrl = DownloadUrls::isAllowed,
                freeBytes = { file -> freeBytes(file) },
                cancel = token,
                onProgress = { done, total ->
                    val now = System.currentTimeMillis()
                    if (now - lastPost >= PROGRESS_MS) {
                        lastPost = now
                        hub.main.post {
                            if (jobs[model.id]?.token === token && jobs[model.id]?.phase == Phase.DOWNLOADING) {
                                jobs[model.id] = Job(token, Phase.DOWNLOADING, done, total)
                                hub.changed()
                            }
                        }
                    }
                },
                onVerifying = {
                    hub.main.post {
                        if (jobs[model.id]?.token === token && jobs[model.id]?.phase == Phase.DOWNLOADING) {
                            jobs[model.id] = Job(token, Phase.VERIFYING, model.sizeBytes, model.sizeBytes)
                            hub.changed()
                        }
                    }
                },
            ).run()
            when (result) {
                DownloadResult.Done -> {
                    val target = File(dir, model.localFile)
                    if (part.renameTo(target)) {
                        val entry = InstalledModel(model.id, model.name, model.localFile, ModelSource.CATALOGUE, model.sha256, model.sizeBytes)
                        hub.main.post {
                            addInstalled(entry)
                            if (jobs[model.id]?.token === token) jobs.remove(model.id)
                            say("${model.name} downloaded and verified.")
                        }
                    } else {
                        finish("The file could not be moved into place.")
                    }
                }
                DownloadResult.Cancelled -> finish("Download paused. Tap Resume to continue.")
                DownloadResult.HashMismatch -> finish("The file failed its SHA-256 check and was deleted.")
                DownloadResult.SizeMismatch -> finish("The server sent the wrong size, so the download stopped.")
                DownloadResult.UrlRefused -> finish("The download was sent to a host outside the allow-list, so it stopped.")
                is DownloadResult.NoSpace -> finish("Not enough free space. Needs ${Sizes.human(result.neededBytes)}, free ${Sizes.human(result.freeBytes)}.")
                is DownloadResult.HttpFailure -> finish("The server answered with error ${result.code}.")
                DownloadResult.NetworkFailure -> finish("The connection failed. Tap Resume to continue.")
                DownloadResult.StorageFailure -> finish("Could not write to storage. The phone may be full. Free some space, then tap Resume.")
            }
        }
    }

    private fun freeBytes(file: File): Long = try {
        StatFs((if (file.isDirectory) file else file.parentFile ?: dir).path).availableBytes
    } catch (e: IllegalArgumentException) {
        0L
    }

    fun cancel(id: String) {
        val job = jobs[id] ?: return
        job.token.cancel()
        jobs[id] = job.copy(phase = Phase.STOPPING)
        hub.changed()
    }

    private fun addInstalled(entry: InstalledModel) {
        installed = installed.filter { it.id != entry.id } + entry
        saveRegistry {
            if (entry.source == ModelSource.IMPORT) {
                installed = installed.filter { it.id != entry.id }
                File(dir, entry.file).delete()
                say("The import could not be recorded because storage is full, so it was removed. Free some space and import again.")
            }
        }
    }

    private fun saveRegistry(onFailed: () -> Unit = {}) {
        val snapshot = installed
        hub.io.execute {
            if (!AtomicFiles.write(registryFile, ModelRegistryCodec.encode(snapshot))) hub.main.post(onFailed)
        }
    }

    fun delete(id: String) {
        val model = find(id)
        if (model != null) {
            if (hub.engine.loadedFile == model.file) {
                hub.engine.unload()
                loadedId = null
            }
            installed = installed.filter { it.id != id }
            saveRegistry()
            File(dir, model.file).delete()
        }
        Catalogue.byId(id)?.let { File(dir, "${it.localFile}.part").delete() }
        say(null)
    }

    fun importFrom(uri: Uri) {
        if (uri.scheme != "content" || !EnginePolicy.IMPORT_ENABLED) return
        if (jobs.containsKey(IMPORT_JOB)) {
            say("An import is already running.")
            return
        }
        val id = "import-" + UUID.randomUUID().toString().take(8)
        val file = "$id.gguf"
        val token = CancelToken()
        jobs[IMPORT_JOB] = Job(token, Phase.IMPORTING, 0, 0)
        say(null)
        val part = File(dir, "$file.part")
        val finish = { text: String ->
            part.delete()
            hub.main.post {
                if (jobs[IMPORT_JOB]?.token === token) jobs.remove(IMPORT_JOB)
                say(text)
            }
            Unit
        }
        hub.background({ finish("The import failed.") }) {
            val resolver = hub.app.contentResolver
            var name = "Imported model"
            var declaredSize = -1L
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    if (!cursor.isNull(0)) name = ForeignText.label(cursor.getString(0), name)
                    if (!cursor.isNull(1)) declaredSize = cursor.getLong(1)
                }
            }
            if (declaredSize > MAX_IMPORT_BYTES) {
                finish("This file is larger than the ${Sizes.human(MAX_IMPORT_BYTES)} import limit.")
                return@background
            }
            if (declaredSize > 0 && freeBytes(dir) < declaredSize + ResumableDownload.SPACE_MARGIN) {
                finish("Not enough free space for this file.")
                return@background
            }
            val input = resolver.openInputStream(uri)
            if (input == null) {
                finish("The file could not be opened.")
                return@background
            }
            var total = 0L
            var bad: String? = null
            input.use { stream ->
                FileOutputStream(part).use { out ->
                    val buffer = ByteArray(256 * 1024)
                    var checked = false
                    var lastPost = 0L
                    while (bad == null) {
                        if (token.cancelled) {
                            bad = "Import cancelled."
                            break
                        }
                        val count = stream.read(buffer)
                        if (count < 0) break
                        if (!checked) {
                            if (count < GgufHeader.BYTES || GgufHeader.check(buffer.copyOf(GgufHeader.BYTES)) != GgufCheck.OK) {
                                bad = "This is not a valid GGUF model file."
                                break
                            }
                            checked = true
                        }
                        total += count
                        if (total > MAX_IMPORT_BYTES) {
                            bad = "This file is larger than the ${Sizes.human(MAX_IMPORT_BYTES)} import limit."
                            break
                        }
                        out.write(buffer, 0, count)
                        val now = System.currentTimeMillis()
                        if (now - lastPost >= PROGRESS_MS) {
                            lastPost = now
                            val done = total
                            val size = declaredSize
                            hub.main.post {
                                if (jobs[IMPORT_JOB]?.token === token && jobs[IMPORT_JOB]?.phase == Phase.IMPORTING) {
                                    jobs[IMPORT_JOB] = Job(token, Phase.IMPORTING, done, size)
                                    hub.changed()
                                }
                            }
                        }
                    }
                    if (!checked && bad == null) bad = "This is not a valid GGUF model file."
                }
            }
            bad?.let {
                finish(it)
                return@background
            }
            val sha = Sha256.ofFile(part, token)
            if (sha == null) {
                finish("Import cancelled.")
                return@background
            }
            val target = File(dir, file)
            if (!part.renameTo(target)) {
                finish("The file could not be moved into place.")
                return@background
            }
            val entry = InstalledModel(id, name, file, ModelSource.IMPORT, sha, total)
            hub.main.post {
                addInstalled(entry)
                if (jobs[IMPORT_JOB]?.token === token) jobs.remove(IMPORT_JOB)
                say("$name imported. It is unverified, so it loads at your own risk.")
            }
        }
    }

    fun load(model: InstalledModel) {
        if (loadingId != null) return
        if (hub.engine.busy || hub.chat.busy) {
            say("Wait for the current answer to finish, or stop it first.")
            return
        }
        if (model.source == ModelSource.IMPORT && !EnginePolicy.IMPORT_ENABLED) {
            say("Imported models are turned off in this version.")
            return
        }
        loadingId = model.id
        say("Checking ${model.name}…")
        val replacedBytes = loadedModel()?.sizeBytes ?: 0L
        val failed = {
            loadingId = null
            say("The model file could not be checked.")
        }
        hub.background(failed) {
            val file = File(dir, model.file)
            val present = file.isFile
            val size = if (present) file.length() else null
            val sha = if (present) Sha256.ofFile(file) else null
            val gate = ModelFiles.gate(model, size, sha)
            val blocked = if (gate == ModelGateResult.OK) deviceBlock(file.length(), replacedBytes) else null
            hub.main.post {
                when (gate) {
                    ModelGateResult.OK -> if (blocked == null) {
                        startEngine(model)
                    } else {
                        loadingId = null
                        say(EngineCodes.message(blocked))
                    }
                    ModelGateResult.MISSING, ModelGateResult.BAD_NAME, ModelGateResult.NOT_IN_CATALOGUE -> {
                        loadingId = null
                        say("The model file is missing or not allowed.")
                    }
                    ModelGateResult.WRONG_SIZE, ModelGateResult.WRONG_HASH -> {
                        loadingId = null
                        say("The file no longer matches its SHA-256, so it will not be loaded. Delete it and download or import it again.")
                    }
                }
            }
        }
    }

    private fun deviceBlock(modelBytes: Long, replacedBytes: Long): Int? {
        val cpuInfo = try {
            File("/proc/cpuinfo").inputStream().use { input ->
                Bounded.readBytes(input, 256 * 1024)?.let { String(it, Charsets.UTF_8) }
            }
        } catch (e: IOException) {
            null
        }
        if (CpuFeatures.missing(cpuInfo).isNotEmpty()) return EngineCodes.ERR_CPU
        val memory = ActivityManager.MemoryInfo()
        hub.app.getSystemService(ActivityManager::class.java).getMemoryInfo(memory)
        val check = MemoryGate.check(modelBytes, MemoryGate.CONTEXT_TOKENS, memory.availMem + replacedBytes)
        return if (check is MemoryCheck.TooBig) EngineCodes.ERR_RAM else null
    }

    private fun startEngine(model: InstalledModel) {
        say("Loading ${model.name}…")
        hub.engine.load(File(dir, model.file), EnginePolicy.requiresIsolation(model.source)) { code ->
            loadingId = null
            if (code == EngineCodes.OK) {
                loadedId = model.id
                hub.updateSettings { it.copy(phoneModelId = model.id, usePc = false) }
                say("${model.name} is ready on this phone (${EnginePolicy.describe(hub.engine.lastLoadFlags)}).")
            } else {
                loadedId = null
                val text = if (code == EngineClient.ERR_DIED) EngineClient.DIED else EngineCodes.message(code)
                say(text)
            }
        }
    }

    fun unload() {
        hub.engine.unload()
        loadedId = null
        say(null)
    }

    fun restartEngine() {
        hub.engine.restart {
            val last = find(hub.settings.phoneModelId)
            if (last != null) load(last)
        }
    }

    companion object {
        const val DIR = "models"
        const val IMPORT_JOB = "import"
        private const val PROGRESS_MS = 250L
        private const val MAX_IMPORT_BYTES = 8L * 1024 * 1024 * 1024
    }
}
