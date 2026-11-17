package dev.serrebi.accessibleiptv

import android.os.Bundle
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import android.widget.RadioGroup
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject

/** Options. Saved when the screen is left: no Save button to hunt for. */
class SettingsActivity : AppCompatActivity() {
	private val prefs by lazy { App.of(this).prefs }
	private lateinit var preferAd: CheckBox
	private lateinit var keywords: EditText
	private lateinit var showUrl: CheckBox
	private lateinit var speakSubs: CheckBox
	private lateinit var announce: RadioGroup
	private lateinit var days: EditText
	private lateinit var buffer: EditText
	private lateinit var maxBuffer: EditText
	private lateinit var prePad: EditText
	private lateinit var postPad: EditText
	private lateinit var status: TextView
	private val levels = listOf(R.id.announce0, R.id.announce1, R.id.announce2, R.id.announce3)

	// Desktop Export/Import Settings: one JSON file with playlists (and their logins), favourites and options.
	private val exportTo = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
		uri ?: return@registerForActivityResult
		status.text = runCatching {
			save()
			contentResolver.openOutputStream(uri, "wt")!!.use { it.write(prefs.export().toString(1).toByteArray()) }
			getString(R.string.settings_exported)
		}.getOrElse { getString(R.string.settings_failed, it.message.orEmpty()) }
	}

	private val importFrom = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
		uri ?: return@registerForActivityResult
		status.text = runCatching {
			val text = contentResolver.openInputStream(uri)!!.use { it.readBytes().toString(Charsets.UTF_8) }
			val oldSources = prefs.sources
			prefs.import(JSONObject(text))
			(oldSources + prefs.sources).distinctBy { it.id }.forEach { App.of(this).forgetCache(it) }
			prefs.lastEpgImport = 0
			SourcesActivity.changed = true
			load()
			getString(R.string.settings_imported)
		}.getOrElse { getString(R.string.settings_failed, it.message ?: it.javaClass.simpleName) }
	}

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		setContentView(R.layout.activity_settings)
		title = getString(R.string.settings)
		listOf(R.id.hAudio, R.id.hPlayback, R.id.hGuide, R.id.hRecordings, R.id.hBackup, R.id.hAbout).forEach { findViewById<View>(it).heading() }
		mapOf(R.id.linkGitHub to "https://github.com/serrebidev/Accessible-IPTV-Client-Android", R.id.linkForgejo to "https://git.serrebiradio.com/serrebi/Accessible-IPTV-Client-Android", R.id.linkProjects to "https://projects.serrebiradio.com", R.id.linkTelegram to "https://t.me/SerrebiProjects", R.id.linkCredits to "https://git.serrebiradio.com/serrebi/Accessible-IPTV-Client-Android/src/branch/main/CREDITS.md").forEach { (id, url) -> findViewById<View>(id).setOnClickListener { runCatching { startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))) } } }
		preferAd = findViewById(R.id.preferAd)
		keywords = findViewById(R.id.keywords)
		days = findViewById(R.id.days)
		showUrl = findViewById(R.id.showUrl)
		speakSubs = findViewById(R.id.speakSubs)
		announce = findViewById(R.id.announce)
		buffer = findViewById(R.id.buffer)
		maxBuffer = findViewById(R.id.maxBuffer)
		prePad = findViewById(R.id.prePad)
		postPad = findViewById(R.id.postPad)
		status = findViewById(R.id.settingsStatus)
		// Android's own per-app language screen (13+); older Android follows the system language.
		findViewById<View>(R.id.btnLanguage).apply {
			visibility = if (android.os.Build.VERSION.SDK_INT >= 33) View.VISIBLE else View.GONE
			setOnClickListener {
				if (android.os.Build.VERSION.SDK_INT >= 33) runCatching { startActivity(android.content.Intent(android.provider.Settings.ACTION_APP_LOCALE_SETTINGS, android.net.Uri.parse("package:$packageName"))) }
			}
		}
		findViewById<View>(R.id.btnExport).setOnClickListener { exportTo.launch("accessible-iptv-settings.json") }
		findViewById<View>(R.id.btnImport).setOnClickListener { importFrom.launch(arrayOf("application/json", "text/plain", "*/*")) }
		load()
	}

	private fun load() {
		showUrl.isChecked = prefs.showStreamUrl
		preferAd.isChecked = prefs.preferAudioDescription
		keywords.setText(prefs.audioKeywords)
		speakSubs.isChecked = prefs.speakSubtitles
		announce.check(levels[prefs.announceLevel.coerceIn(0, 3)])
		days.setText(prefs.epgDaysAhead.toString())
		buffer.setText(plain(prefs.bufferSeconds))
		maxBuffer.setText(plain(prefs.maxBufferSeconds))
		prePad.setText(prefs.prePadMinutes.toString())
		postPad.setText(prefs.postPadMinutes.toString())
	}

	override fun onPause() {
		super.onPause()
		save()
	}

	private fun save() {
		prefs.preferAudioDescription = preferAd.isChecked
		prefs.audioKeywords = keywords.text.toString().trim()
		prefs.showStreamUrl = showUrl.isChecked
		prefs.speakSubtitles = speakSubs.isChecked
		prefs.announceLevel = levels.indexOf(announce.checkedRadioButtonId).takeIf { it >= 0 } ?: 2
		val d = days.text.toString().toIntOrNull()?.coerceIn(1, 14) ?: 2
		if (d != prefs.epgDaysAhead) prefs.lastEpgImport = 0 // re-import with the new window
		prefs.epgDaysAhead = d
		// Desktop _apply_internal_player_bounds: max in (0, 300], base > 0 and never above max.
		val max = (maxBuffer.text.toString().toDoubleOrNull() ?: prefs.maxBufferSeconds).let { if (it <= 0) Prefs.DEFAULT_MAX_BUFFER.toDouble() else minOf(it, 300.0) }
		val base = (buffer.text.toString().toDoubleOrNull() ?: prefs.bufferSeconds).let { if (it <= 0) Prefs.DEFAULT_BUFFER.toDouble() else it }.coerceAtMost(max)
		prefs.maxBufferSeconds = max
		prefs.bufferSeconds = base
		// Desktop schedule padding: 0 to 180 minutes each side.
		prefs.prePadMinutes = prePad.text.toString().toIntOrNull()?.coerceIn(0, 180) ?: 0
		prefs.postPadMinutes = postPad.text.toString().toIntOrNull()?.coerceIn(0, 180) ?: 2
	}

	/** "6" rather than "6.0" in the field. */
	private fun plain(v: Double) = if (v % 1.0 == 0.0) v.toInt().toString() else v.toString()
}
