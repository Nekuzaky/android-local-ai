package dev.nekuz.localmind

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer

object MediaSave {
    fun image(context: Context, bytes: ByteArray, kind: ImageKind): Boolean {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "LocalMind_${System.currentTimeMillis()}.${kind.extension}")
            put(MediaStore.Images.Media.MIME_TYPE, kind.mime)
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/LocalMind")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = resolver.insert(collection, values) ?: return false
        return try {
            val output = resolver.openOutputStream(uri) ?: throw IOException()
            output.use { it.write(bytes) }
            resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
            true
        } catch (e: IOException) {
            resolver.delete(uri, null, null)
            false
        } catch (e: IllegalStateException) {
            resolver.delete(uri, null, null)
            false
        }
    }
}

object VideoEditor {
    class ProbeResult(val durationMs: Long, val spec: ClipSpec)

    sealed class Outcome {
        data class Saved(val name: String) : Outcome()
        data class Refused(val reasons: List<String>) : Outcome()
        data object Cancelled : Outcome()
        data class Failed(val message: String) : Outcome()
    }

    private const val MAX_CSD_BYTES = 1024 * 1024
    private const val DEFAULT_SAMPLE_BYTES = 8 * 1024 * 1024
    private const val MAX_SAMPLE_BYTES = 32 * 1024 * 1024

    private class Tracks(val video: Int, val audio: Int)

    private fun tracks(extractor: MediaExtractor): Tracks {
        var video = -1
        var audio = -1
        for (index in 0 until extractor.trackCount) {
            val mime = extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME).orEmpty()
            if (video < 0 && mime.startsWith("video/")) video = index
            if (audio < 0 && mime.startsWith("audio/")) audio = index
        }
        return Tracks(video, audio)
    }

    private fun intOf(format: MediaFormat, key: String): Int =
        if (format.containsKey(key)) format.getInteger(key) else 0

    private fun csdHash(format: MediaFormat): String {
        val out = StringBuilder()
        for (key in listOf("csd-0", "csd-1", "csd-2")) {
            if (!format.containsKey(key)) continue
            val buffer = format.getByteBuffer(key)?.duplicate() ?: continue
            if (buffer.remaining() > MAX_CSD_BYTES) return "oversize"
            val bytes = ByteArray(buffer.remaining())
            buffer.get(bytes)
            out.append(Sha256.ofBytes(bytes))
        }
        return out.toString()
    }

    private fun videoSpec(format: MediaFormat) = TrackSpec(
        mime = format.getString(MediaFormat.KEY_MIME).orEmpty(),
        width = intOf(format, MediaFormat.KEY_WIDTH),
        height = intOf(format, MediaFormat.KEY_HEIGHT),
        rotation = intOf(format, MediaFormat.KEY_ROTATION),
        csdHash = csdHash(format),
    )

    private fun audioSpec(format: MediaFormat) = TrackSpec(
        mime = format.getString(MediaFormat.KEY_MIME).orEmpty(),
        sampleRate = intOf(format, MediaFormat.KEY_SAMPLE_RATE),
        channels = intOf(format, MediaFormat.KEY_CHANNEL_COUNT),
        csdHash = csdHash(format),
    )

    fun probe(context: Context, uri: Uri): ProbeResult? {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(context, uri, null)
            val found = tracks(extractor)
            if (found.video < 0) return null
            val videoFormat = extractor.getTrackFormat(found.video)
            val duration = if (videoFormat.containsKey(MediaFormat.KEY_DURATION)) videoFormat.getLong(MediaFormat.KEY_DURATION) / 1000 else 0L
            ProbeResult(
                duration,
                ClipSpec(videoSpec(videoFormat), if (found.audio >= 0) audioSpec(extractor.getTrackFormat(found.audio)) else null),
            )
        } catch (e: IOException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        } catch (e: IllegalStateException) {
            null
        } catch (e: SecurityException) {
            null
        } finally {
            extractor.release()
        }
    }

    fun run(
        context: Context,
        uris: List<Uri>,
        segments: List<ResolvedSegment>,
        cancel: CancelToken,
        onProgress: (Int) -> Unit,
    ): Outcome {
        val used = segments.map { it.clipIndex }.distinct()
        val probes = used.map { index -> probe(context, uris[index]) ?: return Outcome.Refused(listOf("Clip ${index + 1} could not be read.")) }
        val problems = CodecMatch.check(probes.map { it.spec }, used.map { it + 1 })
        if (problems.isNotEmpty()) return Outcome.Refused(problems)
        val rotation = probes.first().spec.video?.rotation ?: 0
        val name = "LocalMind_${System.currentTimeMillis()}.mp4"
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/LocalMind")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val target = resolver.insert(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
            ?: return Outcome.Failed("The new file could not be created.")
        var finished = false
        try {
            val descriptor = resolver.openFileDescriptor(target, "rw") ?: return Outcome.Failed("The new file could not be opened.")
            descriptor.use { pfd ->
                val muxer = MediaMuxer(pfd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                var started = false
                try {
                    if (rotation == 90 || rotation == 180 || rotation == 270) muxer.setOrientationHint(rotation)
                    val outcome = writeAll(context, uris, segments, muxer, cancel, onProgress) { started = true }
                    if (outcome != null) return outcome
                    muxer.stop()
                    started = false
                } finally {
                    if (started) {
                        try {
                            muxer.stop()
                        } catch (e: RuntimeException) {
                            started = false
                        }
                    }
                    muxer.release()
                }
            }
            resolver.update(target, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
            finished = true
            return Outcome.Saved(name)
        } catch (e: IOException) {
            return Outcome.Failed("Writing the file failed. Check free storage.")
        } catch (e: IllegalArgumentException) {
            return Outcome.Failed("A clip has a sample this phone's muxer rejected.")
        } catch (e: IllegalStateException) {
            return Outcome.Failed("The phone's muxer rejected this video.")
        } catch (e: SecurityException) {
            return Outcome.Failed("Access to a clip was lost. Add it again.")
        } finally {
            if (!finished) {
                try {
                    resolver.delete(target, null, null)
                } catch (e: RuntimeException) {
                }
            }
        }
    }

    private fun writeAll(
        context: Context,
        uris: List<Uri>,
        segments: List<ResolvedSegment>,
        muxer: MediaMuxer,
        cancel: CancelToken,
        onProgress: (Int) -> Unit,
        onStarted: () -> Unit,
    ): Outcome? {
        var videoTrack = -1
        var audioTrack = -1
        var offset = 0L
        var lastAudioOut = -1L
        var started = false
        val totalMs = segments.sumOf { (it.endMs - it.startMs).coerceAtLeast(1) }
        var doneMs = 0L
        var buffer: ByteBuffer? = null
        val info = MediaCodec.BufferInfo()
        for (segment in segments) {
            val uri = uris[segment.clipIndex]
            val video = MediaExtractor()
            val audio = MediaExtractor()
            try {
                video.setDataSource(context, uri, null)
                val found = tracks(video)
                if (found.video < 0) return Outcome.Refused(listOf("Clip ${segment.clipIndex + 1} has no video track."))
                val videoFormat = video.getTrackFormat(found.video)
                video.selectTrack(found.video)
                val hasAudio = found.audio >= 0
                if (hasAudio) {
                    audio.setDataSource(context, uri, null)
                    audio.selectTrack(found.audio)
                }
                if (!started) {
                    videoTrack = muxer.addTrack(videoFormat)
                    if (hasAudio) audioTrack = muxer.addTrack(audio.getTrackFormat(found.audio))
                    muxer.start()
                    started = true
                    onStarted()
                }
                val needed = maxOf(sampleBytes(videoFormat), if (hasAudio) sampleBytes(audio.getTrackFormat(found.audio)) else 0)
                val current = buffer
                val usable = if (current == null || current.capacity() < needed) ByteBuffer.allocateDirect(needed) else current
                buffer = usable

                val startUs = segment.startMs * 1000
                val endUs = segment.endMs * 1000
                video.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                val actualStart = video.sampleTime
                if (actualStart < 0) return Outcome.Failed("A clip has no usable keyframe at that time.")
                if (hasAudio) audio.seekTo(actualStart, MediaExtractor.SEEK_TO_CLOSEST_SYNC)

                var videoDone = false
                var audioDone = !hasAudio
                var maxTime = actualStart
                var frames = 0L
                while (!videoDone || !audioDone) {
                    if (cancel.cancelled) return Outcome.Cancelled
                    val videoTime = if (videoDone) Long.MAX_VALUE else video.sampleTime
                    val audioTime = if (audioDone) Long.MAX_VALUE else audio.sampleTime
                    if (!videoDone && videoTime < 0) {
                        videoDone = true
                        continue
                    }
                    if (!audioDone && audioTime < 0) {
                        audioDone = true
                        continue
                    }
                    if (videoTime <= audioTime) {
                        val sync = video.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0
                        when (SampleFilter.video(videoTime, actualStart, endUs)) {
                            SampleAction.STOP -> videoDone = true
                            SampleAction.SKIP -> video.advance()
                            SampleAction.WRITE -> {
                                val size = video.readSampleData(usable, 0)
                                if (size < 0) {
                                    videoDone = true
                                } else {
                                    info.set(0, size, offset + videoTime - actualStart, if (sync) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                                    muxer.writeSampleData(videoTrack, usable, info)
                                    if (videoTime > maxTime) maxTime = videoTime
                                    frames++
                                    video.advance()
                                }
                            }
                        }
                    } else {
                        when (SampleFilter.audio(audioTime, actualStart, endUs)) {
                            SampleAction.STOP -> audioDone = true
                            SampleAction.SKIP -> audio.advance()
                            SampleAction.WRITE -> {
                                val size = audio.readSampleData(usable, 0)
                                if (size < 0) {
                                    audioDone = true
                                } else {
                                    val out = offset + audioTime - actualStart
                                    if (out > lastAudioOut && audioTrack >= 0) {
                                        info.set(0, size, out, MediaCodec.BUFFER_FLAG_KEY_FRAME)
                                        muxer.writeSampleData(audioTrack, usable, info)
                                        lastAudioOut = out
                                    }
                                    if (audioTime > maxTime) maxTime = audioTime
                                    audio.advance()
                                }
                            }
                        }
                    }
                }
                if (frames == 0L) return Outcome.Failed("A segment has no frames. Try a longer range.")
                val gap = (maxTime - actualStart) / frames.coerceAtLeast(1)
                offset = SampleFilter.nextOffset(offset, actualStart, maxTime, gap)
                doneMs += (segment.endMs - segment.startMs).coerceAtLeast(1)
                onProgress((doneMs * 100 / totalMs).toInt().coerceIn(0, 100))
            } finally {
                video.release()
                audio.release()
            }
        }
        return null
    }

    private fun sampleBytes(format: MediaFormat): Int {
        val declared = if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) else 0
        return declared.coerceIn(DEFAULT_SAMPLE_BYTES, MAX_SAMPLE_BYTES)
    }
}

class VideoStore(private val hub: Hub) {
    private val file = File(hub.app.filesDir, "clips.json")

    var clips: List<StoredClip> = ClipListCodec.decode(AtomicFiles.readText(file, 256 * 1024))
        private set
    var message: String? = null
        private set
    var plan: List<ResolvedSegment>? = null
        private set
    var planProblems: List<String> = emptyList()
        private set
    var asking = false
        private set
    var running = false
        private set
    var progress = 0
        private set

    private var token: CancelToken? = null

    private fun save() {
        val snapshot = clips
        hub.io.execute { AtomicFiles.write(file, ClipListCodec.encode(snapshot)) }
    }

    fun add(uris: List<Uri>) {
        val room = PlanLimits.MAX_CLIPS - clips.size
        if (room <= 0) {
            message = "A plan can use at most ${PlanLimits.MAX_CLIPS} clips."
            hub.changed()
            return
        }
        val accepted = uris.filter { it.scheme == "content" }.take(room)
        hub.background({
            message = "The clips could not be read."
            hub.changed()
        }) {
            val resolver = hub.app.contentResolver
            val added = ArrayList<StoredClip>()
            var failed = 0
            for (uri in accepted) {
                try {
                    resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } catch (e: SecurityException) {
                }
                val probe = VideoEditor.probe(hub.app, uri)
                if (probe == null || probe.durationMs <= 0) {
                    failed++
                    continue
                }
                var name = "Clip"
                try {
                    resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                        if (cursor.moveToFirst() && !cursor.isNull(0)) name = ForeignText.label(cursor.getString(0), name)
                    }
                } catch (e: RuntimeException) {
                    name = "Clip"
                }
                added.add(StoredClip(uri.toString(), name, probe.durationMs))
            }
            val failures = failed
            hub.main.post {
                clips = (clips + added).take(PlanLimits.MAX_CLIPS)
                plan = null
                planProblems = emptyList()
                message = if (failures > 0) "$failures files could not be read as video." else null
                save()
                hub.changed()
            }
        }
    }

    fun remove(index: Int) {
        if (running || index !in clips.indices) return
        val removed = clips[index]
        clips = clips.filterIndexed { i, _ -> i != index }
        releaseGrants(ClipGrants.releasable(listOf(removed), clips))
        plan = null
        planProblems = emptyList()
        save()
        hub.changed()
    }

    fun clear() {
        if (running) return
        releaseGrants(ClipGrants.releasable(clips, emptyList()))
        clips = emptyList()
        plan = null
        planProblems = emptyList()
        message = null
        save()
        hub.changed()
    }

    private fun releaseGrants(uris: List<String>) {
        for (uri in uris) {
            try {
                hub.app.contentResolver.releasePersistableUriPermission(Uri.parse(uri), Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (e: SecurityException) {
                message = null
            }
        }
    }

    fun discardPlan() {
        plan = null
        planProblems = emptyList()
        hub.changed()
    }

    private fun accept(parse: PlanParse) {
        when (parse) {
            is PlanParse.Rejected -> {
                plan = null
                planProblems = listOf(parse.reason)
            }
            is PlanParse.Parsed -> when (val check = PlanValidator.validate(parse.segments, clips.map { it.durationMs })) {
                is PlanCheck.Valid -> {
                    plan = check.segments
                    planProblems = emptyList()
                }
                is PlanCheck.Invalid -> {
                    plan = null
                    planProblems = check.reasons
                }
            }
        }
        message = null
        hub.changed()
    }

    fun readTyped(command: String) {
        accept(TypedPlan.parse(command))
    }

    fun askModel(command: String) {
        if (asking || running) return
        val text = command.trim()
        if (text.isEmpty() || text.length > PlanLimits.MAX_COMMAND) {
            planProblems = listOf("Type a short request first.")
            hub.changed()
            return
        }
        if (clips.isEmpty()) {
            planProblems = listOf("Add at least one clip first.")
            hub.changed()
            return
        }
        asking = true
        plan = null
        planProblems = emptyList()
        hub.changed()
        hub.chat.planWithModel(text, clips.map { it.durationMs }) { output, failure ->
            asking = false
            if (output != null) {
                accept(PlanJson.fromModelOutput(output))
            } else {
                planProblems = listOf(failure ?: "The model did not answer.")
                hub.changed()
            }
        }
    }

    fun confirm() {
        val approved = plan ?: return
        if (running) return
        val uris = clips.map { Uri.parse(it.uri) }
        if (approved.any { it.clipIndex !in uris.indices }) {
            discardPlan()
            return
        }
        val current = CancelToken()
        token = current
        running = true
        progress = 0
        message = null
        hub.changed()
        hub.background({
            running = false
            token = null
            message = "The video could not be saved."
            hub.changed()
        }) {
            val outcome = VideoEditor.run(hub.app, uris, approved, current) { percent ->
                hub.main.post {
                    progress = percent
                    hub.changed()
                }
            }
            hub.main.post {
                running = false
                token = null
                when (outcome) {
                    is VideoEditor.Outcome.Saved -> {
                        plan = null
                        planProblems = emptyList()
                        message = "Saved ${outcome.name} to Movies/LocalMind. The original clips were not changed."
                    }
                    is VideoEditor.Outcome.Refused -> {
                        planProblems = outcome.reasons
                        message = null
                    }
                    VideoEditor.Outcome.Cancelled -> message = "Cancelled. No file was kept."
                    is VideoEditor.Outcome.Failed -> message = outcome.message
                }
                hub.changed()
            }
        }
    }

    fun cancel() {
        token?.cancel()
    }
}
