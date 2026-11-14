package dev.serrebi.accessibleiptv

import android.content.Intent
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.RadioGroup
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowPopupMenu
import java.io.ByteArrayOutputStream
import java.time.Duration

/** Features ported from the desktop in 0.2.6. */
@RunWith(RobolectricTestRunner::class)
class FeatureTest {
	private val server = FakeServer()
	/** Players built by the tests, destroyed afterwards so the one stream is free again. */
	private val players = mutableListOf<org.robolectric.android.controller.ActivityController<PlayerActivity>>()
	private val app get() = RuntimeEnvironment.getApplication() as App

	@Before fun quiet() { app.prefs.lastUpdateCheck = System.currentTimeMillis() }

	@After fun stop() {
		players.forEach { it.pause().stop().destroy() }
		players.clear()
		server.close()
		Recorder.active.clear()
	}

	private fun idle(ms: Long = 50) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

	private fun playlist(body: String) {
		server.text("/list.m3u", body)
		app.prefs.sources = listOf(Source("src", Source.TYPE_M3U, "List", "${server.url}/list.m3u"))
		runBlocking { app.load(App.LIVE, false) }
	}

	private val numbered = "#EXTM3U\n#EXTINF:-1 tvg-chno=\"101\",BBC One\nhttp://h/1.ts\n#EXTINF:-1,ITV\nhttp://h/2.ts\n#EXTINF:-1 tvg-chno=\"7\",Arena\nhttp://h/3.ts"

	// ---- TS clock (catch-up truncation check) ----

	/** One TS packet starting a PES with [ptsSec] on [pid]. */
	private fun pes(pid: Int, ptsSec: Double): ByteArray {
		val p = ByteArray(188) { 0xFF.toByte() }
		p[0] = 0x47; p[1] = (0x40 or (pid shr 8)).toByte(); p[2] = pid.toByte(); p[3] = 0x10
		val pts = (ptsSec * 90_000).toLong()
		val h = byteArrayOf(0, 0, 1, 0xE0.toByte(), 0, 0, 0x80.toByte(), 0x80.toByte(), 5,
			((0x21 or ((pts shr 29) and 0x0E).toInt())).toByte(), (pts shr 22).toByte(), (((pts shr 14) and 0xFE) or 1).toByte(),
			(pts shr 7).toByte(), (((pts shl 1) and 0xFE) or 1).toByte())
		System.arraycopy(h, 0, p, 4, h.size)
		return p
	}

	@Test fun tsClockMeasuresWhatWasWrittenAcrossOddChunks() {
		val stream = pes(0x100, 10.0) + pes(0x101, 99.0) + pes(0x100, 70.5) + pes(0x100, 40.0)
		val c = TsClock()
		val sink = ByteArrayOutputStream()
		val out = c.watching(sink)
		var i = 0
		for (n in listOf(5, 200, 1, 300, 1000)) { val m = minOf(n, stream.size - i); out.write(stream, i, m); i += m }
		assertEquals(stream.size, sink.size())
		assertEquals("only the first stream counts; the newest time, not the last packet", 60_500L, c.durationMs)
	}

	@Test fun tsClockIgnoresNonTs() {
		val c = TsClock()
		val mp4 = ByteArray(5000) { (it % 251).toByte() }
		c.feed(mp4, 0, mp4.size)
		assertNull(c.durationMs)
	}

	@Test fun catchupEndedEarlyRule() {
		assertTrue(Recorder.endedEarly(1_000_000, 1_800_000)) // 18 of 30 minutes, the teleelevidenie case
		assertFalse(Recorder.endedEarly(1_700_000, 1_800_000)) // inside 10%
		assertFalse(Recorder.endedEarly(null, 1_800_000)) // not TS: no check
		assertFalse(Recorder.endedEarly(10, 0)) // a movie: no expected length
	}

	// ---- Channel numbers ----

	@Test fun channelNumbersFromEveryProvider() {
		playlist(numbered)
		assertEquals("101", app.channels[0].number)
		assertEquals("Arena", Channel.byNumber(app.channels, "7")?.name)
		assertEquals("ITV", Channel.byNumber(app.channels, "2")?.name) // no provider number: the 2nd entry
		assertNull(Channel.byNumber(app.channels, "0"))
		assertNull(Channel.byNumber(app.channels, "abc"))
		assertNull(Channel.byNumber(app.channels, "500"))
		server.text("/player_api.php", """[{"stream_id":5,"num":42,"name":"X","category_id":"1"}]""")
		assertEquals("42", Xtream.liveChannels(Source("x", Source.TYPE_XTREAM, "X", server.url, "u", "p"))[0].number)
	}

	@Test fun numberKeysJumpToAChannel() {
		playlist(numbered)
		val a = main()
		a.findViewById<View>(R.id.btnMore).requestFocus()
		a.onKeyUp(KeyEvent.KEYCODE_1, KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_1))
		a.onKeyUp(KeyEvent.KEYCODE_0, KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_0))
		a.onKeyUp(KeyEvent.KEYCODE_1, KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_1))
		assertEquals(a.getString(R.string.channel_typed, "101"), a.findViewById<TextView>(R.id.status).text.toString())
		idle(1_600)
		val started = shadowOf(a).nextStartedActivity
		assertEquals(PlayerActivity::class.java.name, started.component?.className)
		assertEquals("BBC One", started.getStringExtra("title"))
	}

	@Test fun goToNumberDialogPutsTheErrorOnTheField() {
		playlist(numbered)
		val a = main()
		more(a, R.string.go_to_number)
		val d = ShadowDialog.getLatestDialog() as AlertDialog
		idle()
		val field = findEdit(d.window!!.decorView)
		field.setText("999")
		d.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
		assertEquals(a.getString(R.string.number_not_found, "999"), field.error.toString())
		field.setText("7")
		d.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
		assertEquals("Arena", shadowOf(a).nextStartedActivity.getStringExtra("title"))
	}

	private fun findEdit(v: View): EditText = when (v) {
		is EditText -> v
		is android.view.ViewGroup -> (0 until v.childCount).firstNotNullOf { runCatching { findEdit(v.getChildAt(it)) }.getOrNull() }
		else -> error("no field")
	}

	private fun main(): MainActivity {
		val a = Robolectric.buildActivity(MainActivity::class.java).setup().get()
		val rv = a.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.channels)
		val end = System.currentTimeMillis() + 5_000
		while ((rv.adapter!!.itemCount == 0 || rv.findViewHolderForAdapterPosition(0) == null) && System.currentTimeMillis() < end) { idle(); Thread.sleep(10) }
		return a
	}

	private fun more(a: MainActivity, label: Int) {
		a.findViewById<View>(R.id.btnMore).performClick()
		val menu = ShadowPopupMenu.getLatestPopupMenu().menu
		val item = (0 until menu.size()).map { menu.getItem(it) }.first { it.title == a.getString(label) }
		menu.performIdentifierAction(item.itemId, 0)
	}

	// ---- Previous channel and recently watched ----

	@Test fun previousChannelIsTheOneBeforeTheLast() {
		playlist(numbered)
		val a = main()
		more(a, R.string.previous_channel)
		assertEquals(a.getString(R.string.no_previous), a.findViewById<TextView>(R.id.status).text.toString())
		Player.open(a, app.channels[0]) // BBC One
		Player.open(a, app.channels[2]) // Arena
		shadowOf(a).clearNextStartedActivities()
		more(a, R.string.previous_channel)
		assertEquals("BBC One", shadowOf(a).nextStartedActivity.getStringExtra("title"))
		assertEquals(listOf("BBC One", "Arena"), app.prefs.recent.map { app.channel(it)!!.name })
	}

	@Test fun previousChannelIsNotSkippedWhenTheLastIsGone() {
		playlist(numbered)
		val a = main()
		Player.open(a, app.channels[0]) // BBC One
		app.prefs.addRecent("gone:from-playlist")
		shadowOf(a).clearNextStartedActivities()
		more(a, R.string.previous_channel)
		assertEquals("BBC One", shadowOf(a).nextStartedActivity.getStringExtra("title"))
	}

	@Test fun recentListIsCappedAndDeduplicated() {
		repeat(25) { app.prefs.addRecent("k$it") }
		app.prefs.addRecent("k20")
		assertEquals(20, app.prefs.recent.size)
		assertEquals("k20", app.prefs.recent[0])
		assertEquals(1, app.prefs.recent.count { it == "k20" })
	}

	// ---- Recordings ----

	@Test fun schedulePaddingWidensTheWindow() {
		app.prefs.prePadMinutes = 5
		app.prefs.postPadMinutes = 10
		val start = System.currentTimeMillis() + 3_600_000
		Recorder.schedule(app, Channel("s", "1", "BBC One", "", "http://h/1.ts"), "News", start, start + 1_800_000)
		val s = Recorder.scheduled(app).single()
		assertEquals(start - 300_000, s.start)
		assertEquals(start + 1_800_000 + 600_000, s.end)
	}

	@Test fun recordingsInProgressAreListedFirstWithStop() {
		Recorder.active["src:1"] = Recorder.Active("src:1", "BBC One - News", "src:1", System.currentTimeMillis(), 0)
		val a = Robolectric.buildActivity(RecordingsActivity::class.java).setup().get()
		@Suppress("UNCHECKED_CAST")
		val rv = a.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.list)
		idle()
		val row = rv.findViewHolderForAdapterPosition(0)!!.itemView
		assertEquals("BBC One - News, ${a.getString(R.string.recording_now)}", row.contentDescription.toString())
		assertEquals(a.getString(R.string.recordings_heading, 1, 0, 0), a.findViewById<TextView>(R.id.listHeading).text.toString())
		row.performClick()
		val sent = shadowOf(a).nextStartedService
		assertEquals(RecordService.ACTION_STOP_ONE, sent.action)
		assertEquals("src:1", sent.getStringExtra("key"))
	}

	@Test fun aChannelThatIsRecordingOffersStop() {
		playlist(numbered)
		Recorder.active[app.channels[0].key] = Recorder.Active(app.channels[0].key, "BBC One", app.channels[0].key, 0, 0)
		val a = main()
		val rv = a.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.channels)
		val end = System.currentTimeMillis() + 5_000
		while (rv.findViewHolderForAdapterPosition(0) == null && System.currentTimeMillis() < end) { idle(); Thread.sleep(10) }
		val labels = rv.findViewHolderForAdapterPosition(0)!!.itemView.createAccessibilityNodeInfo().actionList.mapNotNull { it.label?.toString() }
		assertTrue(a.getString(R.string.stop_recording_channel) in labels)
		assertFalse(a.getString(R.string.record_now) in labels)
	}

	// ---- Settings ----

	@Test fun newSettingsSaveAndClamp() {
		val c = Robolectric.buildActivity(SettingsActivity::class.java).setup()
		val a = c.get()
		assertEquals(R.id.announce2, a.findViewById<RadioGroup>(R.id.announce).checkedRadioButtonId) // desktop default
		a.findViewById<EditText>(R.id.prePad).setText("999")
		a.findViewById<EditText>(R.id.postPad).setText("x")
		a.findViewById<RadioGroup>(R.id.announce).check(R.id.announce0)
		a.findViewById<android.widget.CheckBox>(R.id.speakSubs).isChecked = false
		c.pause()
		assertEquals(180, app.prefs.prePadMinutes)
		assertEquals(2, app.prefs.postPadMinutes)
		assertEquals(0, app.prefs.announceLevel)
		assertFalse(app.prefs.speakSubtitles)
	}

	@Test fun resumeRemembersHalfWatchedAndForgetsNearlyFinished() {
		app.prefs.rememberResume("src:m1", 30_000)
		app.prefs.rememberResume("src:m2", 60_000)
		assertEquals(listOf("src:m2", "src:m1"), app.prefs.resume.map { it.first }) // newest first
		app.prefs.rememberResume("src:m1", 4_000) // too short: kept unchanged
		assertEquals(60_000L, app.prefs.resume.first { it.first == "src:m2" }.second)
		app.prefs.clearResume("src:m2")
		assertEquals(listOf("src:m1"), app.prefs.resume.map { it.first })
	}

	@Test fun historicalBufferDefaultsBecomeTwentyFiveWithoutChangingCustomSettings() {
		assertEquals(25.0, app.prefs.bufferSeconds, 0.0)
		assertEquals(30.0, app.prefs.maxBufferSeconds, 0.0)
		for (old in listOf(6.0, 10.0)) {
			app.prefs.bufferSeconds = old
			app.prefs.maxBufferSeconds = 18.0
			assertEquals(25.0, app.prefs.bufferSeconds, 0.0)
			assertEquals(30.0, app.prefs.maxBufferSeconds, 0.0)
		}
		app.prefs.bufferSeconds = 8.0
		app.prefs.maxBufferSeconds = 12.0
		assertEquals(8.0, app.prefs.bufferSeconds, 0.0)
		assertEquals(12.0, app.prefs.maxBufferSeconds, 0.0)
		app.prefs.bufferSeconds = 10.0
		assertEquals("a custom ceiling cannot undercut the migrated cushion", 25.0, app.prefs.maxBufferSeconds, 0.0)
		val c = Cushion(app.prefs.bufferSeconds, app.prefs.maxBufferSeconds)
		assertEquals(25_000_000L, c.targetUs)
	}

	@Test fun exportImportRoundTripsEverything() {
		app.prefs.sources = listOf(Source("a", Source.TYPE_XTREAM, "X", "http://h", "u", "p"))
		app.prefs.favorites = setOf("a:1", "a:2")
		app.prefs.bufferSeconds = 9.5
		app.prefs.epgDaysAhead = 5
		app.prefs.lastEpgImport = 123L
		val file = app.prefs.export().toString()
		app.prefs.sources = emptyList(); app.prefs.favorites = emptySet(); app.prefs.bufferSeconds = 1.0; app.prefs.epgDaysAhead = 1
		app.prefs.import(org.json.JSONObject(file))
		assertEquals("u", app.prefs.sources.single().username)
		assertEquals(setOf("a:1", "a:2"), app.prefs.favorites)
		assertEquals(9.5, app.prefs.bufferSeconds, 0.0)
		assertEquals(5, app.prefs.epgDaysAhead)
		assertEquals(123L, app.prefs.lastEpgImport)
		try { app.prefs.import(org.json.JSONObject("""{"app":"other"}""")); org.junit.Assert.fail() } catch (_: IllegalArgumentException) {}
		assertEquals("a bad file changes nothing", 5, app.prefs.epgDaysAhead)
	}

	// ---- Player ----

	private fun player() = Robolectric.buildActivity(
		PlayerActivity::class.java,
		Intent(app, PlayerActivity::class.java).putExtra("url", "${server.url}/live.ts").putExtra("title", "BBC One").putExtra("key", ""),
	).also { players += it }.setup().get()

	@Test fun announcementLevelDecidesWhatIsSpoken() {
		app.prefs.announceLevel = 0
		val quiet = player()
		val s = quiet.findViewById<TextView>(R.id.playerStatus)
		quiet.findViewById<Button>(R.id.btnRecord).performClick()
		assertEquals("the text still updates", quiet.getString(R.string.cannot_record_this), s.text.toString())
		assertEquals(View.ACCESSIBILITY_LIVE_REGION_NONE, s.accessibilityLiveRegion)
		app.prefs.announceLevel = 1
		val errors = player()
		errors.findViewById<Button>(R.id.btnRecord).performClick()
		assertEquals(View.ACCESSIBILITY_LIVE_REGION_POLITE, errors.findViewById<TextView>(R.id.playerStatus).accessibilityLiveRegion)
	}

	@Test fun subtitlesButtonSaysWhenThereAreNone() {
		val a = player()
		a.findViewById<Button>(R.id.btnSubs).performClick()
		assertEquals(a.getString(R.string.no_subtitles), a.findViewById<TextView>(R.id.playerStatus).text.toString())
		assertTrue(a.findViewById<Button>(R.id.btnSubs).isFocusable)
	}
}
