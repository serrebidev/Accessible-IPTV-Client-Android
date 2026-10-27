package dev.serrebi.accessibleiptv

import android.content.ActivityNotFoundException
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.result.contract.ActivityResultContracts
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.EditText
import android.widget.PopupMenu
import android.widget.RadioGroup
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {
	private val app by lazy { App.of(this) }
	private lateinit var status: TextView
	private lateinit var details: TextView
	private lateinit var search: EditText
	private lateinit var channelsHeading: TextView
	private lateinit var groupAdapter: RowAdapter<Pair<String, Int>>
	private lateinit var channelAdapter: RowAdapter<Channel>
	private var section = App.LIVE
	private var group = App.ALL
	private var searchJob: Job? = null
	private var channelsJob: Job? = null

	// Back with search text clears the search first, like Escape on the desktop.
	// Voice search: the system recogniser's best guess goes into the search field.
	private val voice = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
		val heard = r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull() ?: return@registerForActivityResult
		search.setText(heard)
		search.setSelection(heard.length)
		// Like the keyboard's Search key: run the search now and move the reader to the result
		// count, otherwise a voice search ends in silence.
		searchJob?.cancel()
		lifecycleScope.launch {
			showChannels().join()
			channelsHeading.performAccessibilityAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null)
		}
	}

	private fun startVoice() {
		val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
			.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_WEB_SEARCH)
			.putExtra(RecognizerIntent.EXTRA_PROMPT, getString(R.string.voice_prompt))
		try { voice.launch(i) } catch (_: ActivityNotFoundException) { status.status(getString(R.string.voice_unavailable)) }
	}

	private val clearSearchOnBack = object : OnBackPressedCallback(false) {
		override fun handleOnBackPressed() = search.setText("")
	}

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		setContentView(R.layout.activity_main)
		status = findViewById(R.id.status)
		details = findViewById(R.id.details)
		search = findViewById(R.id.filter)
		channelsHeading = findViewById(R.id.channelsHeading)
		findViewById<View>(R.id.groupsHeading).heading()
		channelsHeading.heading()

		groupAdapter = RowAdapter { (name, count), row, l1, l2 ->
			l1.text = groupLabel(name)
			l2.text = count.toString()
			val sel = if (name == group) ", " + getString(R.string.selected) else ""
			bindRow(row, "${l1.text}, $count$sel", { selectGroup(name) }, emptyList())
		}
		channelAdapter = RowAdapter { ch, row, l1, l2 -> bindChannel(ch, row, l1, l2) }
		setupList(R.id.groups, groupAdapter)
		setupList(R.id.channels, channelAdapter)

		search.addTextChangedListener(object : TextWatcher {
			override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
			override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
			override fun afterTextChanged(s: Editable?) {
				clearSearchOnBack.isEnabled = !s.isNullOrEmpty()
				searchJob?.cancel()
				// Debounced so typing stays responsive and results are not re-announced per key.
				searchJob = lifecycleScope.launch { delay(300); showChannels() }
			}
		})
		onBackPressedDispatcher.addCallback(this, clearSearchOnBack)
		search.onSearchKey(channelsHeading) { searchJob?.cancel(); showChannels().join() }

		findViewById<RadioGroup>(R.id.sections).setOnCheckedChangeListener { _, id ->
			selectSection(when (id) { R.id.secMovies -> App.MOVIES; R.id.secSeries -> App.SERIES; else -> App.LIVE })
		}
		findViewById<View>(R.id.btnSources).setOnClickListener { startActivity(Intent(this, SourcesActivity::class.java)) }
		findViewById<View>(R.id.btnOnNow).setOnClickListener { startActivity(ProgrammesActivity.onNow(this)) }
		findViewById<View>(R.id.btnGuideSearch).setOnClickListener { startActivity(ProgrammesActivity.search(this)) }
		findViewById<View>(R.id.btnChannelGuide).setOnClickListener { startActivity(Intent(this, ChannelGuideActivity::class.java)) }
		findViewById<View>(R.id.btnRecordings).setOnClickListener { startActivity(Intent(this, RecordingsActivity::class.java)) }
		findViewById<View>(R.id.btnMore).setOnClickListener { showMore(it) }
		findViewById<View>(R.id.btnVoice).setOnClickListener { startVoice() }

		group = app.prefs.lastGroup.ifEmpty { App.ALL }
		load(force = false)
		// Once a day, quietly: only speaks up when there is something to install.
		if (System.currentTimeMillis() - app.prefs.lastUpdateCheck > 86_400_000L) checkUpdates(quiet = true)
	}

	override fun onResume() {
		super.onResume()
		// The connection warmed earlier was spent by the last channel watched.
		Net.warmUp(app.channels.map { it.url })
		if (SourcesActivity.changed) {
			SourcesActivity.changed = false
			load(force = false, all = true)
		} else channelAdapter.notifyDataSetChanged() // favourites or guide may have changed
	}

	private fun setupList(id: Int, adapter: RecyclerView.Adapter<*>) = findViewById<RecyclerView>(id).apply {
		layoutManager = LinearLayoutManager(this@MainActivity)
		this.adapter = adapter
	}

	private fun groupLabel(name: String) = when (name) {
		App.ALL -> getString(R.string.all_in_section)
		App.FAVORITES -> getString(R.string.favorites)
		else -> name
	}

	private fun countText(n: Int) = resources.getQuantityString(
		when (section) { App.MOVIES -> R.plurals.movie_count; App.SERIES -> R.plurals.series_count; else -> R.plurals.channel_count }, n, n,
	)

	private fun sectionName() = getString(when (section) { App.MOVIES -> R.string.movies; App.SERIES -> R.string.series; else -> R.string.channels })

	private fun bindChannel(ch: Channel, row: View, l1: TextView, l2: TextView) {
		val fav = ch.key in app.prefs.favorites
		val nn = if (ch.isLive) app.epg.nowNext(ch).now else null
		l1.text = if (fav) getString(R.string.favorite_name, ch.name) else ch.name
		// Just what is on and until when; next and the description are under Details.
		val now = nn?.let { getString(R.string.until, it.title, Format.time(it.stop)) }
		// While searching, results come from every group: say which when there is no guide.
		l2.text = now ?: if (search.text.isNotEmpty()) ch.group else ""
		val speech = Format.sentences(listOfNotNull(
			if (fav) "${ch.name}, ${getString(R.string.favorite)}" else ch.name,
			l2.text.toString().ifEmpty { null },
		))
		bindRow(row, speech, { Player.open(this, ch, status) }, channelActions(ch, fav))
		row.setOnFocusChangeListener { _, has -> if (has) details.text = detailsText(ch) }
	}

	private fun channelActions(ch: Channel, fav: Boolean): List<RowAction> {
		val out = ArrayList<RowAction>()
		out += RowAction(getString(if (fav) R.string.remove_favorite else R.string.add_favorite)) {
			val now = app.prefs.toggleFavorite(ch.key)
			status.status(getString(if (now) R.string.added_favorite else R.string.removed_favorite, ch.name))
			if (group == App.FAVORITES) showChannels() else channelAdapter.notifyDataSetChanged()
			refreshGroupCounts()
		}
		out += RowAction(getString(R.string.programme_details)) { alert(ch.name, detailsText(ch)) }
		if (ch.isLive) {
			out += RowAction(getString(if (ch.hasCatchup) R.string.guide_and_catchup else R.string.channel_guide)) { startActivity(ProgrammesActivity.forChannel(this, ch)) }
			out += if (Recorder.isRecording(ch.key)) RowAction(getString(R.string.stop_recording_channel)) {
				Recorder.stop(this, ch.key)
				status.status(getString(R.string.recording_stopped, ch.name))
			} else RowAction(getString(R.string.record_now)) {
				// One provider stream per device: say so instead of starting nothing.
				if (!Recorder.canStart(ch)) status.status(getString(R.string.stream_busy))
				else {
					Recorder.start(this, ch, app.epg.nowNext(ch).now?.title.orEmpty())
					status.status(getString(R.string.recording_channel, ch.name))
				}
			}
		} else if (ch.kind == Channel.KIND_MOVIE || ch.kind == Channel.KIND_EPISODE) {
			out += RowAction(getString(R.string.download)) {
				if (!Recorder.canStart(ch)) status.status(getString(R.string.stream_busy))
				else {
					Recorder.download(this, ch.url, ch.name, "")
					status.status(getString(R.string.downloading, ch.name))
				}
			}
		}
		return out
	}

	private fun detailsText(ch: Channel): String {
		val lines = ArrayList<String>()
		if (ch.isLive) {
			val nn = app.epg.nowNext(ch)
			if (nn.isEmpty) lines += getString(R.string.no_guide_for, ch.name)
			nn.now?.let { lines += getString(R.string.now_programme, Format.programme(it)); if (it.description.isNotBlank()) lines += it.description }
			nn.next?.let { lines += getString(R.string.next_programme, Format.programme(it)) }
		} else {
			lines += ch.group
			ch.attrs["plot"]?.takeIf { it.isNotBlank() }?.let { lines += it }
		}
		if (app.prefs.showStreamUrl && ch.kind != Channel.KIND_SERIES) lines += getString(R.string.stream_url_is, ch.url)
		return lines.joinToString("\n")
	}

	private fun load(force: Boolean, all: Boolean = false) {
		if (app.prefs.sources.isEmpty()) {
			status.status(getString(R.string.no_sources))
			groupAdapter.submit(emptyList())
			channelAdapter.submit(emptyList())
			return
		}
		status.status(getString(if (force) R.string.refreshing else R.string.loading))
		lifecycleScope.launch {
			// Live always loads (the guide needs it); the other sections only once they are opened.
			val sections = if (all) App.SECTIONS.filter { it == App.LIVE || it == section || app.isLoaded(it) } else listOf(section)
			val errors = sections.flatMap { app.load(it, force) }
			refreshGroupCounts()
			showChannels()
			// Warm the stream hosts' connections while the user browses: picking a
			// channel then starts at the first byte, not at a TLS handshake.
			Net.warmUp(app.channels.map { it.url })
			status.status(
				if (errors.isEmpty()) countText(app.list(section).size)
				else getString(R.string.load_errors, errors.joinToString("; ")),
			)
			
			// Guide older than a day, or built for a different channel list: fetch it now.
			if (section == App.LIVE && app.guideStale()) importGuide()
		}
	}

	private fun selectSection(s: String) {
		section = s
		group = App.ALL
		// An already loaded section announces its count; otherwise load() announces it.
		if (app.isLoaded(s)) { refreshGroupCounts(); showChannels(); status.status(countText(app.list(s).size)) }
		else load(force = false)
	}

	private fun refreshGroupCounts() {
		val items = app.list(section)
		val counts = items.groupingBy { it.group }.eachCount()
		if (group !in counts && group != App.ALL && group != App.FAVORITES) group = App.ALL
		groupAdapter.submit(app.groups(section).map { g ->
			g to when (g) {
				App.ALL -> items.size
				App.FAVORITES -> app.channelsIn(section, App.FAVORITES).size
				else -> counts[g] ?: 0
			}
		})
	}

	private fun selectGroup(name: String) {
		group = name
		if (section == App.LIVE) app.prefs.lastGroup = name
		groupAdapter.notifyDataSetChanged()
		showChannels()
		// Move the reader to the list heading so the result is heard straight away.
		channelsHeading.post { channelsHeading.performAccessibilityAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null) }
	}

	private fun showChannels(): Job {
		val q = search.text.toString().trim()
		val requestedSection = section
		val requestedGroup = group
		channelsJob?.cancel()
		return lifecycleScope.launch {
			val list = withContext(Dispatchers.Default) {
				// Search covers every group in the section; otherwise show the chosen group.
				if (q.isEmpty()) app.channelsIn(requestedSection, requestedGroup) else App.searchRanked(app.list(requestedSection), q)
			}
			channelAdapter.submit(list)
			findViewById<RecyclerView>(R.id.channels).contentDescription = sectionName()
			channelsHeading.text = if (q.isEmpty()) getString(R.string.list_heading, groupLabel(group), list.size)
			else resources.getQuantityString(R.plurals.result_count, list.size, list.size)
		}.also { channelsJob = it }
	}

	/** Background guide loads are silent; only a failure, or a refresh the user asked for, is spoken. */
	private fun importGuide(manual: Boolean = false) {
		if (manual) status.status(getString(R.string.importing_guide))
		lifecycleScope.launch {
			try {
				val count = app.importGuide { }
				channelAdapter.notifyDataSetChanged()
				// No guide URL yet, or nothing in it matches these channels: an import of nothing.
				if (manual) status.status(getString(if (count > 0) R.string.guide_imported else R.string.no_guide))
			} catch (e: Exception) {
				status.status(getString(R.string.guide_failed, e.message ?: e.javaClass.simpleName))
			}
		}
	}

	private fun showMore(anchor: View) {
		val items = listOf(
			getString(R.string.previous_channel) to { playPrevious() },
			getString(R.string.recently_watched) to { showRecent() },
			getString(R.string.go_to_number) to { askNumber() },
			getString(R.string.refresh_playlists) to { load(force = true, all = true) },
			getString(R.string.import_guide) to { importGuide(manual = true) },
			getString(R.string.account_info) to { showAccountInfo() },
			getString(R.string.settings) to { startActivity(Intent(this, SettingsActivity::class.java)) },
			getString(R.string.check_updates) to { checkUpdates(quiet = false) },
			getString(R.string.help) to { startActivity(Intent(this, HelpActivity::class.java)) },
		)
		PopupMenu(this, anchor).apply {
			items.forEachIndexed { i, (label, _) -> menu.add(0, i, i, label) }
			setOnMenuItemClickListener { items[it.itemId].second(); true }
		}.show()
	}

	/** Recent holds live keys only; app.channel would also scan a loaded 100k-title movie list per key. */
	private fun recentChannels(): List<Channel> {
		val live = app.channels.associateBy { it.key }
		return app.prefs.recent.mapNotNull { live[it] }
	}

	/** Desktop "Previous Channel": the live channel watched before the last one. */
	private fun playPrevious() {
		// Index the saved keys, not the found channels: a dropped newest key would shift "previous" one back.
		val prev = app.prefs.recent.getOrNull(1)?.let { app.channels.firstOrNull { c -> c.key == it } }
			?: return status.status(getString(R.string.no_previous))
		Player.open(this, prev, status)
	}

	private fun showRecent() {
		val recent = recentChannels()
		if (recent.isEmpty()) return status.status(getString(R.string.no_recent))
		AlertDialog.Builder(this).setTitle(R.string.recently_watched)
			.setItems(recent.map { it.name }.toTypedArray()) { _, i -> Player.open(this, recent[i], status) }
			.setNegativeButton(android.R.string.cancel, null).show()
	}

	/** Desktop "Go to Channel Number": the provider's number, else the nth live channel. */
	private fun askNumber() {
		val label = TextView(this).apply { setText(R.string.channel_number); id = View.generateViewId() }
		val field = EditText(this).apply {
			id = View.generateViewId()
			inputType = android.text.InputType.TYPE_CLASS_NUMBER
			setSingleLine()
		}
		label.labelFor = field.id
		val box = android.widget.LinearLayout(this).apply {
			orientation = android.widget.LinearLayout.VERTICAL
			setPadding(48, 16, 48, 0)
			addView(label); addView(field)
		}
		val d = AlertDialog.Builder(this).setTitle(R.string.go_to_number).setView(box)
			.setPositiveButton(R.string.play, null).setNegativeButton(android.R.string.cancel, null).create()
		d.setOnShowListener {
			d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
				val ch = Channel.byNumber(app.channels, field.text.toString())
				if (ch == null) {
					field.error = getString(R.string.number_not_found, field.text.toString().trim())
					field.performAccessibilityAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null)
				} else { d.dismiss(); Player.open(this, ch, status) }
			}
		}
		d.show()
	}

	// Number keys on a remote or keyboard jump to a channel, like a TV: typed digits wait 1.5 s for more.
	private val digits = StringBuilder()
	private val jump = Runnable {
		val n = digits.toString()
		digits.setLength(0)
		Channel.byNumber(app.channels, n)?.let { Player.open(this, it, status) } ?: status.status(getString(R.string.number_not_found, n))
	}

	override fun onDestroy() {
		// The digit timer must not fire (and open the player) after the screen is gone.
		status.removeCallbacks(jump)
		super.onDestroy()
	}

	override fun onKeyUp(keyCode: Int, event: android.view.KeyEvent): Boolean {
		if (currentFocus is EditText) return super.onKeyUp(keyCode, event)
		if (keyCode in android.view.KeyEvent.KEYCODE_0..android.view.KeyEvent.KEYCODE_9 && digits.length < 6) {
			digits.append(keyCode - android.view.KeyEvent.KEYCODE_0)
			status.status(getString(R.string.channel_typed, digits))
			status.removeCallbacks(jump)
			status.postDelayed(jump, 1_500)
			return true
		}
		if (keyCode == android.view.KeyEvent.KEYCODE_LAST_CHANNEL) { playPrevious(); return true }
		return super.onKeyUp(keyCode, event)
	}

	private fun checkUpdates(quiet: Boolean) {
		if (!quiet) status.status(getString(R.string.checking_updates))
		lifecycleScope.launch {
			val result = withContext(Dispatchers.IO) { runCatching { Updater.check(BuildConfig.VERSION_NAME) } }
			val r = result.getOrNull()
			// Only a check that ran counts as today's; a network failure tries again next launch.
			if (result.isSuccess) app.prefs.lastUpdateCheck = System.currentTimeMillis()
			when {
				r != null -> AlertDialog.Builder(this@MainActivity)
					.setTitle(getString(R.string.update_available, r.version))
					.setMessage(r.notes.take(1500))
					.setPositiveButton(R.string.install) { _, _ -> installUpdate(r) }
					.setNegativeButton(R.string.later, null).show()
				quiet -> Unit
				result.isFailure -> status.status(getString(R.string.update_failed, result.exceptionOrNull()?.message.orEmpty()))
				else -> status.status(getString(R.string.up_to_date))
			}
		}
	}

	private fun installUpdate(r: Updater.Release) {
		// First time only: Android sends the user to allow installs from this app.
		if (!Updater.ensureInstallAllowed(this)) { status.status(getString(R.string.allow_installs)); return }
		status.status(getString(R.string.downloading_update))
		lifecycleScope.launch {
			val apk = withContext(Dispatchers.IO) { runCatching { Updater.download(this@MainActivity, r) } }
			apk.onSuccess { Updater.install(this@MainActivity, it); status.status(getString(R.string.installing_update)) }
				.onFailure { status.status(getString(R.string.update_failed, it.message.orEmpty())) }
		}
	}

	private fun showAccountInfo() {
		val sources = app.prefs.sources.filter { it.isXtream || it.isStalker || App.xtreamBehind(it) != null }
		if (sources.isEmpty()) { alert(getString(R.string.account_info), getString(R.string.account_info_none)); return }
		status.status(getString(R.string.loading))
		lifecycleScope.launch {
			val text = withContext(Dispatchers.IO) {
				sources.joinToString("\n\n") { src ->
					val lines = runCatching { app.accountInfo(src) }.fold(
						{ info -> info.joinToString("\n") { "${it.first}: ${it.second}" } },
						{ getString(R.string.account_info_failed, it.message ?: "") },
					)
					"${src.name}\n$lines"
				}
			}
			status.status("")
			AlertDialog.Builder(this@MainActivity).setTitle(R.string.account_info).setMessage(text).setPositiveButton(android.R.string.ok, null).show()
		}
	}
}
