package dev.nekuz.localmind

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.Switch
import android.widget.TextView

class MainActivity : Activity() {
    private enum class Tab { CHAT, MODELS, PC, SEARCH, VIDEO }

    private lateinit var hub: Hub
    private lateinit var where: TextView
    private lateinit var status: TextView
    private lateinit var list: ListView
    private lateinit var empty: TextView
    private lateinit var bar: ProgressBar
    private lateinit var adapter: RowAdapter
    private lateinit var tabs: Map<Tab, Button>
    private lateinit var panels: Map<Tab, View>
    private lateinit var composer: View
    private lateinit var chatInput: EditText
    private lateinit var chatSend: Button
    private lateinit var chatAttach: Button
    private lateinit var chatMode: Button
    private lateinit var ollamaAddress: EditText
    private lateinit var comfyAddress: EditText
    private lateinit var comfyPrompt: EditText
    private lateinit var comfyGenerate: Button
    private lateinit var usePc: Switch
    private lateinit var searchQuery: EditText
    private lateinit var videoCommand: EditText

    private var tab = Tab.CHAT
    private var dialog: AlertDialog? = null
    private val listener: () -> Unit = { render() }

    private var chatCacheKey = ""
    private var shownNotice: String? = null
    private var chatCacheRows: List<Row> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        hub = Hub.get(this)
        setContentView(R.layout.activity_main)
        where = findViewById(R.id.where)
        status = findViewById(R.id.status)
        list = findViewById(R.id.list)
        empty = findViewById(R.id.empty)
        bar = findViewById(R.id.bar)
        composer = findViewById(R.id.composer)
        chatInput = findViewById(R.id.chatInput)
        chatSend = findViewById(R.id.chatSend)
        chatAttach = findViewById(R.id.chatAttach)
        chatMode = findViewById(R.id.chatMode)
        ollamaAddress = findViewById(R.id.ollamaAddress)
        comfyAddress = findViewById(R.id.comfyAddress)
        comfyPrompt = findViewById(R.id.comfyPrompt)
        comfyGenerate = findViewById(R.id.comfyGenerate)
        usePc = findViewById(R.id.usePc)
        searchQuery = findViewById(R.id.searchQuery)
        videoCommand = findViewById(R.id.videoCommand)

        tab = savedInstanceState?.getString(STATE_TAB)?.let { name -> Tab.entries.firstOrNull { it.name == name } } ?: Tab.CHAT
        list.transcriptMode = if (tab == Tab.CHAT) ListView.TRANSCRIPT_MODE_NORMAL else ListView.TRANSCRIPT_MODE_DISABLED
        if (savedInstanceState == null) {
            ollamaAddress.setText(hub.settings.ollamaAddress)
            comfyAddress.setText(hub.settings.comfyAddress)
        }

        adapter = RowAdapter(this)
        list.adapter = adapter
        list.setOnItemLongClickListener { _, _, position, _ ->
            when (val row = adapter.rows.getOrNull(position)) {
                is Row.Message -> Actions.copy(this, row.copyText)
                is Row.Value -> Actions.copy(this, row.value)
                else -> Unit
            }
            true
        }

        tabs = mapOf(
            Tab.CHAT to findViewById(R.id.tabChat),
            Tab.MODELS to findViewById(R.id.tabModels),
            Tab.PC to findViewById(R.id.tabPc),
            Tab.SEARCH to findViewById(R.id.tabSearch),
            Tab.VIDEO to findViewById(R.id.tabVideo),
        )
        panels = mapOf(
            Tab.CHAT to findViewById(R.id.panelChat),
            Tab.MODELS to findViewById(R.id.panelModels),
            Tab.PC to findViewById(R.id.panelPc),
            Tab.SEARCH to findViewById(R.id.panelSearch),
            Tab.VIDEO to findViewById(R.id.panelVideo),
        )
        tabs.forEach { (target, button) ->
            button.setOnClickListener {
                tab = target
                if (target != Tab.CHAT) list.setSelection(0)
                hideKeyboard()
                render()
            }
        }
        findViewById<ImageButton>(R.id.infoButton).setOnClickListener {
            showDialog(
                AlertDialog.Builder(this)
                    .setTitle(R.string.info)
                    .setMessage(R.string.info_message)
                    .setPositiveButton(R.string.close, null)
                    .create(),
            )
        }
        bindChat()
        bindModels()
        bindPc()
        bindSearch()
        bindVideo()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_TAB, tab.name)
    }

    override fun onStart() {
        super.onStart()
        hub.addListener(listener)
        hub.chat.ensureLoaded()
        hub.search.ensureLoaded()
        render()
    }

    override fun onStop() {
        super.onStop()
        hub.removeListener(listener)
    }

    override fun onDestroy() {
        super.onDestroy()
        dialog?.dismiss()
        dialog = null
    }

    private fun showDialog(next: AlertDialog) {
        dialog?.dismiss()
        dialog = Actions.show(next)
    }

    private fun hideKeyboard() {
        getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(window.decorView.windowToken, 0)
    }

    private fun bindChat() {
        findViewById<Button>(R.id.chatList).setOnClickListener { showChats() }
        findViewById<Button>(R.id.chatNew).setOnClickListener { hub.chat.newConversation() }
        chatMode.setOnClickListener {
            hub.chat.setMode(if (hub.chat.mode == ChatMode.CODE) ChatMode.GENERAL else ChatMode.CODE)
        }
        chatSend.setOnClickListener {
            if (hub.chat.busy) {
                hub.chat.stop()
            } else if (hub.chat.send(chatInput.text.toString())) {
                chatInput.setText("")
                list.post { list.setSelection(adapter.count - 1) }
            }
        }
        chatAttach.setOnClickListener {
            if (!hub.settings.usePc) {
                Actions.toast(this, R.string.image_pc_only)
            } else {
                open(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*"), REQUEST_IMAGE)
            }
        }
    }

    private fun showChats() {
        val summaries = hub.chat.summaries
        if (summaries.isEmpty()) {
            Actions.toast(this, R.string.chat_none)
            return
        }
        val builder = AlertDialog.Builder(this)
            .setTitle(R.string.chat_pick)
            .setItems(summaries.map { it.title }.toTypedArray()) { _, which ->
                summaries.getOrNull(which)?.let { hub.chat.open(it.id) }
            }
            .setPositiveButton(R.string.close, null)
        val current = hub.chat.current
        if (current != null) {
            builder.setNeutralButton(R.string.chat_delete) { _, _ -> confirmDeleteChat(current.id) }
        }
        showDialog(builder.create())
    }

    private fun confirmDeleteChat(id: String) {
        showDialog(
            AlertDialog.Builder(this)
                .setTitle(R.string.chat_delete_title)
                .setMessage(R.string.chat_delete_message)
                .setPositiveButton(R.string.models_delete) { _, _ -> hub.chat.delete(id) }
                .setNegativeButton(R.string.cancel, null)
                .create(),
        )
    }

    private fun bindModels() {
        findViewById<Button>(R.id.modelsImport).visibility = if (EnginePolicy.IMPORT_ENABLED) View.VISIBLE else View.GONE
        findViewById<Button>(R.id.modelsImport).setOnClickListener {
            showDialog(
                AlertDialog.Builder(this)
                    .setTitle(R.string.models_import_title)
                    .setMessage(R.string.models_import_message)
                    .setPositiveButton(R.string.models_import_yes) { _, _ ->
                        open(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), REQUEST_MODEL)
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .create(),
            )
        }
    }

    private fun bindPc() {
        findViewById<Button>(R.id.ollamaConnect).setOnClickListener {
            hideKeyboard()
            hub.pc.connectOllama(ollamaAddress.text.toString())
        }
        findViewById<Button>(R.id.comfyConnect).setOnClickListener {
            hideKeyboard()
            hub.pc.connectComfy(comfyAddress.text.toString())
        }
        usePc.isChecked = hub.settings.usePc
        usePc.setOnCheckedChangeListener { _, checked ->
            if (hub.settings.usePc != checked) hub.updateSettings { it.copy(usePc = checked) }
        }
        findViewById<Button>(R.id.ollamaChoose).setOnClickListener {
            choose(hub.pc.ollamaModels) { name -> hub.updateSettings { it.copy(ollamaModel = name) } }
        }
        findViewById<Button>(R.id.comfyChoose).setOnClickListener {
            choose(hub.pc.checkpoints) { name -> hub.updateSettings { it.copy(comfyCheckpoint = name) } }
        }
        comfyGenerate.setOnClickListener {
            if (hub.pc.generating) {
                hub.pc.cancel()
            } else {
                hideKeyboard()
                hub.pc.generate(comfyPrompt.text.toString())
            }
        }
    }

    private fun choose(options: List<String>, onPick: (String) -> Unit) {
        if (options.isEmpty()) {
            Actions.toast(this, R.string.pc_no_models)
            return
        }
        showDialog(
            AlertDialog.Builder(this)
                .setItems(options.map { ForeignText.clean(it, ForeignText.MAX_LABEL) }.toTypedArray()) { _, which ->
                    options.getOrNull(which)?.let(onPick)
                }
                .setNegativeButton(R.string.cancel, null)
                .create(),
        )
    }

    private fun bindSearch() {
        findViewById<Button>(R.id.searchPick).setOnClickListener {
            open(
                Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION),
                REQUEST_FOLDER,
            )
        }
        findViewById<Button>(R.id.searchRebuild).setOnClickListener {
            if (hub.search.building) hub.search.cancel() else hub.search.rebuild()
        }
        findViewById<Button>(R.id.searchDelete).setOnClickListener { hub.search.deleteIndex() }
        findViewById<Button>(R.id.searchRun).setOnClickListener {
            hideKeyboard()
            hub.search.search(searchQuery.text.toString())
        }
        searchQuery.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_SEARCH) {
                hideKeyboard()
                hub.search.search(searchQuery.text.toString())
                true
            } else {
                false
            }
        }
        findViewById<Button>(R.id.searchAsk).setOnClickListener { askAboutResults() }
    }

    private fun askAboutResults() {
        val snippets = hub.search.topSnippets()
        val question = searchQuery.text.toString().trim()
        if (snippets.isEmpty() || question.isEmpty()) {
            Actions.toast(this, R.string.search_ask_none)
            return
        }
        val prompt = PromptBuilder.askAboutResults(question, snippets)
        tab = Tab.CHAT
        hub.chat.send(getString(R.string.search_ask_label, question), prompt)
        hideKeyboard()
        render()
    }

    private fun bindVideo() {
        findViewById<Button>(R.id.videoAdd).setOnClickListener {
            open(
                Intent(Intent.ACTION_OPEN_DOCUMENT)
                    .addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("video/*")
                    .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION),
                REQUEST_CLIPS,
            )
        }
        findViewById<Button>(R.id.videoClear).setOnClickListener { hub.video.clear() }
        findViewById<Button>(R.id.videoRead).setOnClickListener {
            hideKeyboard()
            hub.video.readTyped(videoCommand.text.toString())
        }
        findViewById<Button>(R.id.videoAsk).setOnClickListener {
            hideKeyboard()
            hub.video.askModel(videoCommand.text.toString())
        }
    }

    private fun open(intent: Intent, request: Int) {
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(intent, request)
        } catch (e: RuntimeException) {
            Actions.toast(this, R.string.image_failed)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data == null) return
        when (requestCode) {
            REQUEST_MODEL -> data.data?.let { hub.models.importFrom(it) }
            REQUEST_FOLDER -> data.data?.let { hub.search.folderPicked(it) }
            REQUEST_CLIPS -> hub.video.add(clipUris(data))
            REQUEST_IMAGE -> data.data?.let { attachImage(it) }
        }
    }

    private fun clipUris(data: Intent): List<Uri> {
        val out = ArrayList<Uri>()
        val clip = data.clipData
        if (clip != null) {
            for (index in 0 until minOf(clip.itemCount, PlanLimits.MAX_CLIPS)) {
                clip.getItemAt(index)?.uri?.let { out.add(it) }
            }
        } else {
            data.data?.let { out.add(it) }
        }
        return out
    }

    private fun attachImage(uri: Uri) {
        if (uri.scheme != "content") return
        val app = hub
        app.background({ app.chat.say(getString(R.string.image_failed)) }) {
            val image = Images.forVision(app, uri)
            app.main.post {
                if (image == null) app.chat.say(getString(R.string.image_failed)) else app.chat.setImage(image)
            }
        }
    }

    private fun render() {
        where.text = hub.whereLine()
        tabs.forEach { (target, button) ->
            val selected = target == tab
            button.setBackgroundResource(if (selected) R.drawable.bg_button_primary else R.drawable.bg_button_outline)
            button.setTextColor(getColor(if (selected) R.color.on_brand else R.color.text_primary))
        }
        panels.forEach { (target, panel) -> panel.visibility = if (target == tab) View.VISIBLE else View.GONE }
        composer.visibility = if (tab == Tab.CHAT) View.VISIBLE else View.GONE
        list.transcriptMode = if (tab == Tab.CHAT) ListView.TRANSCRIPT_MODE_NORMAL else ListView.TRANSCRIPT_MODE_DISABLED
        bar.visibility = View.GONE
        empty.visibility = View.GONE
        val rows = when (tab) {
            Tab.CHAT -> chatRows()
            Tab.MODELS -> modelRows()
            Tab.PC -> pcRows()
            Tab.SEARCH -> searchRows()
            Tab.VIDEO -> videoRows()
        }
        adapter.rows = rows
        val notice = when (tab) {
            Tab.MODELS -> hub.models.notice
            Tab.SEARCH -> hub.search.message
            Tab.VIDEO -> hub.video.message
            Tab.PC -> hub.pc.comfyMessage
            Tab.CHAT -> null
        }
        if (notice != null && notice != shownNotice && tab != Tab.PC) list.setSelection(0)
        shownNotice = notice
        val keepOn = hub.chat.busy || hub.video.running || hub.models.job(ModelStore.IMPORT_JOB) != null ||
            Catalogue.models.any { hub.models.job(it.id) != null }
        if (keepOn) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun showEmpty(text: Int) {
        empty.visibility = View.VISIBLE
        empty.setText(text)
    }

    private fun enable(button: Button, enabled: Boolean) {
        button.isEnabled = enabled
        button.alpha = if (enabled) 1f else 0.4f
    }

    private fun chatRows(): List<Row> {
        val chat = hub.chat
        val engine = hub.engine
        chatMode.setText(if (chat.mode == ChatMode.CODE) R.string.mode_code else R.string.mode_general)
        chatSend.setText(if (chat.busy) R.string.chat_stop else R.string.chat_send)
        chatAttach.visibility = if (hub.settings.usePc) View.VISIBLE else View.GONE
        status.setText(if (chat.busy) R.string.chat_generating else R.string.chat_idle)
        if (chat.busy) {
            bar.visibility = View.VISIBLE
            bar.isIndeterminate = true
        }
        val rows = ArrayList<Row>()
        val current = chat.current
        if (current != null) rows.addAll(conversationRows(current, chat.busy))
        chat.pendingImage?.let { rows.add(Row.Note(getString(R.string.chat_attached, it.label))) }
        chat.notice?.let { rows.add(Row.Note(it)) }
        if (engine.state == EngineClient.State.CRASHED) {
            rows.add(
                Row.Action(
                    title = getString(R.string.engine_label),
                    detail = engine.crashMessage ?: EngineClient.DIED,
                    highlighted = true,
                    buttons = listOf(RowButton(getString(R.string.engine_restart)) { hub.models.restartEngine() }),
                ),
            )
        }
        if (rows.isEmpty()) showEmpty(R.string.chat_empty)
        return rows
    }

    private fun conversationRows(conversation: Conversation, streaming: Boolean): List<Row> {
        val messages = conversation.messages
        val settledCount = if (streaming) (messages.size - 1).coerceAtLeast(0) else messages.size
        val key = "${conversation.id}:$settledCount:${if (streaming) "s" else conversation.updatedAt.toString()}"
        if (key != chatCacheKey) {
            chatCacheKey = key
            chatCacheRows = messages.take(settledCount).flatMap { messageRows(it) }
        }
        return if (settledCount < messages.size) chatCacheRows + messageRows(messages.last()) else chatCacheRows
    }

    private fun messageRows(message: ChatMessage): List<Row> {
        if (message.role == ChatRole.USER) {
            return listOf(Row.Message(getString(R.string.role_you), message.text, true, message.text))
        }
        val rows = ArrayList<Row>()
        var labelled = false
        for (part in CodeBlocks.split(message.text)) {
            when (part) {
                is ChatPart.Prose -> {
                    val label = if (labelled) "" else getString(R.string.role_model)
                    labelled = true
                    rows.add(Row.Message(label, part.text, false, part.text))
                }
                is ChatPart.Code -> {
                    if (!labelled) rows.add(Row.Note(getString(R.string.role_model)))
                    labelled = true
                    rows.add(Row.Code(part.language, part.code))
                }
            }
        }
        if (rows.isEmpty()) rows.add(Row.Message(getString(R.string.role_model), "…", false, ""))
        message.note?.let { rows.add(Row.Note(it)) }
        return rows
    }

    private fun modelRows(): List<Row> {
        val models = hub.models
        status.setText(R.string.models_status)
        val rows = ArrayList<Row>()
        models.notice?.let { rows.add(Row.Note(it)) }
        models.job(ModelStore.IMPORT_JOB)?.let { job ->
            val percent = if (job.total > 0) (job.done * 100 / job.total).toInt().coerceIn(0, 100) else null
            rows.add(
                Row.Action(
                    title = getString(R.string.models_importing),
                    detail = Sizes.human(job.done),
                    progress = percent,
                    indeterminate = percent == null,
                    buttons = if (job.phase == ModelStore.Phase.STOPPING) emptyList() else listOf(RowButton(getString(R.string.models_cancel)) { models.cancel(ModelStore.IMPORT_JOB) }),
                ),
            )
        }
        if (hub.engine.state == EngineClient.State.CRASHED) {
            rows.add(
                Row.Action(
                    title = getString(R.string.engine_label),
                    detail = hub.engine.crashMessage ?: EngineClient.DIED,
                    highlighted = true,
                    buttons = listOf(RowButton(getString(R.string.engine_restart)) { models.restartEngine() }),
                ),
            )
        }
        rows.add(Row.Header(getString(R.string.models_header_catalogue)))
        rows.add(Row.Note(getString(R.string.models_note)))
        for (model in Catalogue.models) rows.add(catalogueRow(model))
        val imported = models.installed.filter { it.source == ModelSource.IMPORT }
        if (imported.isNotEmpty()) {
            rows.add(Row.Header(getString(R.string.models_header_imported)))
            for (model in imported) rows.add(importedRow(model))
        }
        return rows
    }

    private fun catalogueRow(model: CatalogueModel): Row {
        val models = hub.models
        val detail = getString(R.string.models_detail, model.purpose, model.licence, Sizes.human(model.sizeBytes))
        val job = models.job(model.id)
        if (job != null) {
            val verifying = job.phase == ModelStore.Phase.VERIFYING
            val stopping = job.phase == ModelStore.Phase.STOPPING
            val percent = if (job.total > 0) (job.done * 100 / job.total).toInt().coerceIn(0, 100) else 0
            return Row.Action(
                title = model.name,
                detail = when {
                    stopping -> getString(R.string.models_stopping)
                    verifying -> "Checking SHA-256…"
                    else -> "${Sizes.human(job.done)} of ${Sizes.human(job.total)}"
                },
                progress = percent,
                indeterminate = verifying || stopping,
                buttons = if (stopping) emptyList() else listOf(RowButton(getString(R.string.models_cancel)) { models.cancel(model.id) }),
            )
        }
        val installed = models.find(model.id)
        if (installed != null) return installedRow(installed, detail, getString(R.string.tag_verified), R.color.level_ok)
        val part = models.partBytes(model)
        val buttons = ArrayList<RowButton>()
        buttons.add(RowButton(getString(if (part > 0) R.string.models_resume else R.string.models_download)) { models.download(model) })
        if (part > 0) buttons.add(RowButton(getString(R.string.models_delete)) { models.delete(model.id) })
        val extra = if (part > 0) " · ${Sizes.human(part)} downloaded" else ""
        return Row.Action(title = model.name, detail = detail + extra, buttons = buttons)
    }

    private fun importedRow(model: InstalledModel): Row =
        installedRow(model, Sizes.human(model.sizeBytes), getString(R.string.tag_unverified), R.color.level_review)

    private fun installedRow(model: InstalledModel, detail: String, sourceTag: String, sourceColor: Int): Row {
        val models = hub.models
        val loaded = models.isLoaded(model.id) && hub.engine.loadedFile == model.file
        val loading = models.isLoading(model.id)
        val buttons = ArrayList<RowButton>()
        if (loaded) {
            buttons.add(RowButton(getString(R.string.models_unload), !hub.chat.busy) { models.unload() })
        } else {
            buttons.add(RowButton(getString(R.string.models_load), !loading) { models.load(model) })
        }
        buttons.add(RowButton(getString(R.string.models_delete), !loading && !hub.chat.busy) { confirmDeleteModel(model) })
        return Row.Action(
            title = model.name,
            detail = detail,
            tag = when {
                loaded -> getString(R.string.tag_ready)
                loading -> getString(R.string.tag_loading)
                else -> sourceTag
            },
            tagColor = if (loaded) R.color.level_ok else sourceColor,
            indeterminate = loading,
            buttons = buttons,
        )
    }

    private fun confirmDeleteModel(model: InstalledModel) {
        showDialog(
            AlertDialog.Builder(this)
                .setTitle(R.string.models_delete_title)
                .setMessage(R.string.models_delete_message)
                .setPositiveButton(R.string.models_delete) { _, _ -> hub.models.delete(model.id) }
                .setNegativeButton(R.string.cancel, null)
                .create(),
        )
    }

    private fun pcRows(): List<Row> {
        val pc = hub.pc
        val settings = hub.settings
        status.setText(R.string.pc_status)
        comfyGenerate.setText(if (pc.generating) R.string.pc_stop else R.string.pc_generate)
        if (usePc.isChecked != settings.usePc) usePc.isChecked = settings.usePc
        if (pc.generating) {
            bar.visibility = View.VISIBLE
            bar.isIndeterminate = true
        }
        val rows = ArrayList<Row>()
        rows.add(Row.Note(getString(R.string.pc_warning)))
        rows.add(Row.Value(getString(R.string.pc_ollama_label), pc.ollamaMessage ?: settings.ollamaAddress.ifEmpty { getString(R.string.pc_ready) }))
        rows.add(Row.Value(getString(R.string.pc_model_label), ForeignText.clean(settings.ollamaModel).ifEmpty { getString(R.string.pc_ready) }))
        rows.add(Row.Header(getString(R.string.pc_image_title)))
        rows.add(Row.Note(getString(R.string.pc_image_note)))
        rows.add(Row.Value(getString(R.string.pc_comfy_label), pc.comfyMessage ?: settings.comfyAddress.ifEmpty { getString(R.string.pc_ready) }))
        rows.add(Row.Value(getString(R.string.pc_checkpoint_label), ForeignText.clean(settings.comfyCheckpoint).ifEmpty { getString(R.string.pc_ready) }))
        pc.result?.let { result ->
            rows.add(
                Row.Picture(
                    result.preview,
                    RowButton(getString(if (pc.saved) R.string.pc_saved else R.string.pc_save), !pc.saved) { pc.save() },
                ),
            )
        }
        return rows
    }

    private fun searchRows(): List<Row> {
        val search = hub.search
        val rebuild = findViewById<Button>(R.id.searchRebuild)
        rebuild.setText(if (search.building) R.string.search_cancel else R.string.search_rebuild)
        val header = search.header
        status.text = when {
            search.building -> getString(R.string.search_building, search.progress)
            header != null -> getString(R.string.search_index, header.folder, header.files, header.skipped, search.chunkCount)
            else -> getString(R.string.search_status)
        }
        if (search.building) {
            bar.visibility = View.VISIBLE
            bar.isIndeterminate = true
        }
        enable(findViewById(R.id.searchAsk), search.hits.isNotEmpty() && !hub.chat.busy)
        val rows = ArrayList<Row>()
        search.message?.let { rows.add(Row.Note(it)) }
        for (hit in search.hits) {
            rows.add(
                Row.Value(
                    label = getString(R.string.search_hit_label, ForeignText.clean(hit.chunk.path, 200), hit.chunk.line),
                    value = ForeignText.multiline(hit.chunk.text, 800),
                ),
            )
        }
        rows.add(Row.Note(getString(R.string.search_note)))
        return rows
    }

    private fun videoRows(): List<Row> {
        val video = hub.video
        status.text = when {
            video.running -> getString(R.string.video_running, video.progress)
            video.asking -> getString(R.string.video_asking)
            else -> getString(R.string.video_status)
        }
        if (video.running) {
            bar.visibility = View.VISIBLE
            bar.isIndeterminate = false
            bar.max = 100
            bar.progress = video.progress
        } else if (video.asking) {
            bar.visibility = View.VISIBLE
            bar.isIndeterminate = true
        }
        val idle = !video.running && !video.asking
        enable(findViewById(R.id.videoRead), idle)
        enable(findViewById(R.id.videoAsk), idle && !hub.chat.busy)
        enable(findViewById(R.id.videoAdd), idle)
        enable(findViewById(R.id.videoClear), idle)
        val rows = ArrayList<Row>()
        video.message?.let { rows.add(Row.Note(it)) }
        if (video.running) {
            rows.add(
                Row.Action(
                    title = getString(R.string.video_running, video.progress),
                    detail = "",
                    buttons = listOf(RowButton(getString(R.string.video_cancel)) { video.cancel() }),
                ),
            )
        }
        video.plan?.let { plan ->
            rows.add(Row.Header(getString(R.string.video_plan)))
            rows.add(Row.Note(getString(R.string.video_plan_note)))
            plan.forEach { segment ->
                rows.add(
                    Row.Value(
                        "",
                        getString(R.string.video_line, segment.clipIndex + 1, Durations.format(segment.startMs), Durations.format(segment.endMs)),
                    ),
                )
            }
            rows.add(
                Row.Action(
                    title = getString(R.string.video_confirm),
                    detail = "",
                    buttons = listOf(
                        RowButton(getString(R.string.video_confirm), idle) { video.confirm() },
                        RowButton(getString(R.string.video_discard), idle) { video.discardPlan() },
                    ),
                ),
            )
        }
        if (video.planProblems.isNotEmpty()) {
            rows.add(Row.Header(getString(R.string.video_problems)))
            video.planProblems.forEach { rows.add(Row.Value("", it, highlighted = true)) }
        }
        rows.add(Row.Header(getString(R.string.video_clips)))
        if (video.clips.isEmpty()) rows.add(Row.Note(getString(R.string.video_empty)))
        video.clips.forEachIndexed { index, clip ->
            rows.add(
                Row.Action(
                    title = getString(R.string.clip_label, index + 1),
                    detail = "${clip.name} · ${Durations.format(clip.durationMs)}",
                    buttons = listOf(RowButton(getString(R.string.video_remove), idle) { video.remove(index) }),
                ),
            )
        }
        rows.add(Row.Note(getString(R.string.video_note)))
        return rows
    }

    private companion object {
        const val STATE_TAB = "tab"
        const val REQUEST_MODEL = 1
        const val REQUEST_FOLDER = 2
        const val REQUEST_CLIPS = 3
        const val REQUEST_IMAGE = 4
    }
}
