package dev.serrebi.accessibleiptv

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.OnBackPressedCallback
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player as M3Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import androidx.media3.cast.CastPlayer
import androidx.media3.common.DeviceInfo
import androidx.mediarouter.app.MediaRouteChooserDialog
import androidx.mediarouter.media.MediaRouteSelector
import com.google.android.gms.cast.framework.CastContext
import java.io.IOException
import java.io.InputStream

/** A live HLS stream cannot be shared: it takes the one stream for itself. */
internal fun isHlsUrl(url: String) = url.substringBefore('?').lowercase().endsWith(".m3u8")

/** The key a live channel's shared tap is filed under; without a channel key the URL stands in. */
internal fun tapKey(channelKey: String, url: String) = channelKey.ifEmpty { url }

object Player {
	/** Plays any list entry: shows open their episode list; Stalker links are fetched first. */
	fun open(a: AppCompatActivity, ch: Channel, status: TextView? = null) {
		if (ch.kind == Channel.KIND_SERIES) return a.startActivity(EpisodesActivity.intent(a, ch))
		// Recently watched / Previous channel: live channels only, as on the desktop.
		if (ch.isLive) App.of(a).prefs.addRecent(ch.key)
		if (!ch.needsResolve) return play(a, ch.url, ch.name, ch.key, status = status)
		status?.status(a.getString(R.string.connecting))
		a.lifecycleScope.launch {
			val url = withContext(Dispatchers.IO) { runCatching { App.of(a).resolveUrl(ch) } }
			url.onSuccess { play(a, it, ch.name, ch.key, status = status) }
				.onFailure { status?.status(a.getString(R.string.play_failed, it.message ?: "")) ?: a.alert(ch.name, it.message ?: "") }
		}
	}

	/**
	 * Opens the player, unless the one provider stream is taken by a different channel.
	 * The refusal is spoken here, on the screen behind, so it is heard before anything opens.
	 */
	fun play(c: Context, url: String, title: String, channelKey: String, catchup: Boolean = false, status: TextView? = null) {
		val plain = M3uParser.splitHeaders(url).first
		if (!freeFor(c, plain, channelKey, catchup)) {
			val busy = c.getString(R.string.stream_busy)
			if (status != null) status.status(busy) else (c as? Activity)?.alert(title, busy)
			return
		}
		c.startActivity(
			Intent(c, PlayerActivity::class.java).putExtra("url", url).putExtra("title", title)
				.putExtra("key", channelKey).putExtra("catchup", catchup),
		)
	}

	/**
	 * One provider stream per device: true when this playback may start. A live TS channel
	 * shares the running tap; a live HLS channel and every archive, movie or file take the
	 * stream on their own.
	 */
	internal fun freeFor(c: Context, plain: String, channelKey: String, catchup: Boolean): Boolean {
		if (plain.startsWith("content://")) return true
		val ch = if (channelKey.isEmpty()) null else App.of(c).channel(channelKey)
		return when {
			ch?.isLive == true && !catchup && !isHlsUrl(plain) ->
				// A live TS channel shares the running tap; both ids it could hold are checked.
				LiveChannels.current(ch.key) != null ||
					(Streams.free(Streams.liveId(ch.key)) && Streams.free(Streams.hlsId(ch.key)))
			// HLS lives cannot share, so both ids a live channel can hold must be free.
			ch?.isLive == true && !catchup -> Streams.free(Streams.hlsId(ch.key)) && Streams.free(Streams.liveId(ch.key))
			else -> Streams.free(Streams.urlId(plain))
		}
	}
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PlayerActivity : AppCompatActivity() {
	private val app by lazy { App.of(this) }
	private lateinit var exo: ExoPlayer

	/** The CastPlayer wrapper when Cast is available: it moves playback to and from the TV by itself. */
	private lateinit var player: M3Player
	private var castContext: CastContext? = null
	private lateinit var status: TextView
	private lateinit var pause: Button
	private var channelKey = ""
	private var autoTrackDone = false
	private lateinit var cushion: Cushion
	private var hasPlayed = false
	private lateinit var castButton: Button
	private lateinit var video: PlayerView
	private var localFile = false
	private var fullscreen = false
	/** The one provider stream this screen holds, freed as soon as playback stops. */
	private var streamId: String? = null
	/** Live TS plays from the channel's shared tap; this is the player's place in it. */
	private var tapSource: TapDataSource? = null
	private var plainUrl = ""
	private var streamHeaders: Map<String, String> = emptyMap()
	/** Live HLS: it cannot share the tap, so it holds a stream of its own. */
	private var hls = false
	/** Where catch-up or a file resumes after the screen was left. */
	private var resumeAt = 0L
	/** The shared socket's reconnect has been announced; the next drop says it again. */
	private var saidRetrying = false
	/** A live channel (not catch-up, a file or VOD): drops are reconnected like the desktop. */
	private var live = false
	private var restoringChannel = false
	/** Catch-up or a file: the Record button does not apply. */
	private var catchupPlayback = false
	private val reconnect = Reconnect(android.os.SystemClock::elapsedRealtime)
	private val retry = android.os.Handler(android.os.Looper.getMainLooper())
	private var retryJob: Runnable? = null
	/** The desktop's playback-health rules, ticked once a second by [watchdog]. */
	private val health = StallTracker(android.os.SystemClock::elapsedRealtime)
	/** Progress sampling for the health tick: the last position seen, and since when it stood still while the player claimed to be playing. */
	private var lastPosition = -1L
	private var frozenSince: Long? = null
	/** A recovery gave up on this channel: the budget was spent, or it never played at all. */
	private var gaveUp = false
	private var resumeTicks = 0

	/**
	 * Announces the shared live socket's reconnect once a second. The watchdog handles
	 * stalled playback, so a timestamp warning cannot start a competing recovery loop.
	 */
	private val pace: Runnable = object : Runnable {
		override fun run() {
			// The shared live socket reconnects for the whole channel: say so while it does.
			val retrying = tapSource?.joined?.reconnecting == true
			if (retrying != saidRetrying) {
				saidRetrying = retrying
				if (retrying) say(getString(R.string.reconnecting), 2)
			}
			status.postDelayed(this, 1_000)
		}
	}

	/** Ticks the desktop's playback-health rules once a second, for every kind of playback. */
	private val watchdog: Runnable = object : Runnable {
		override fun run() {
			tickHealth()
			status.postDelayed(this, 1_000)
		}
	}

	private fun tickHealth() {
		if (restoringChannel || pausedByStop || gaveUp || retryJob != null) return
		if (player.deviceInfo.playbackType == DeviceInfo.PLAYBACK_TYPE_REMOTE) return
		// Progress: the position advancing is the desktop's A/V progress counter.
		val now = android.os.SystemClock.elapsedRealtime()
		val position = exo.currentPosition
		var frozenMs = 0L
		if (exo.playbackState == M3Player.STATE_READY && player.playWhenReady) {
			if (position != lastPosition) { lastPosition = position; frozenSince = null }
			else {
				val since = frozenSince ?: now.also { frozenSince = it }
				frozenMs = now - since
			}
		} else { lastPosition = position; frozenSince = null }
		if (!live && channelKey.isNotEmpty() && ++resumeTicks % 15 == 0) saveResume(exo.currentPosition, exo.duration)
		when (health.tick(exo.playbackState == M3Player.STATE_BUFFERING, frozenMs, exo.totalBufferedDuration)) {
			StallTracker.Verdict.GROW -> cushion.onStall()
			StallTracker.Verdict.RESTART -> if (tapSource?.joined?.reconnecting != true) recover(grow = false)
			StallTracker.Verdict.RESTART_GROWN -> if (tapSource?.joined?.reconnecting != true) recover(grow = true)
			StallTracker.Verdict.GIVE_UP -> giveUp()
			StallTracker.Verdict.NONE -> Unit
		}
	}

	/** Debug builds: buffer depth every 2 s, to tell a slow feed (drains bit by bit) from a delivery gap (drains at once). */
	private val heartbeat: Runnable = object : Runnable {
		override fun run() {
			android.util.Log.i("AiptvPlayer", "beat state=${exo.playbackState} playing=${exo.isPlaying} bufferedMs=${exo.totalBufferedDuration} cushionMs=${cushion.targetUs / 1000} speed=${exo.playbackParameters.speed}")
			retry.postDelayed(this, 2_000)
		}
	}
	private var pausedByStop = false

	// Back in full screen brings the controls back instead of closing the player.
	private val exitFullscreenOnBack = object : OnBackPressedCallback(false) {
		override fun handleOnBackPressed() = setFullscreen(false)
	}

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		setContentView(R.layout.activity_player)
		window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
		val url = intent.getStringExtra("url").orEmpty()
		val name = intent.getStringExtra("title").orEmpty()
		channelKey = intent.getStringExtra("key").orEmpty()
		val catchup = intent.getBooleanExtra("catchup", false)
		title = getString(if (catchup) R.string.catchup_title else R.string.player_title, name)
		status = findViewById(R.id.playerStatus)
		pause = findViewById(R.id.btnPause)

		val ch = app.channel(channelKey)
		restoringChannel = ch == null && channelKey.isNotEmpty()
		live = !catchup && ch?.isLive == true
		channelName = name
		subtitles = findViewById(R.id.subtitles)
		showProgramme(announce = false)

		val (plain, headers) = M3uParser.splitHeaders(url)
		plainUrl = plain
		streamHeaders = headers
		localFile = plain.startsWith("content://")
		hls = isHlsUrl(plain)
		// One provider stream per device. A live channel is read through its shared tap, which
		// takes the stream when the first reader opens it; anything else holds the stream here.
		if (!Player.freeFor(this, plain, channelKey, catchup)) {
			say(getString(R.string.stream_busy), 1)
			finish()
			return
		}
		if (!localFile && !(live && !hls)) {
			val id = if (live) Streams.hlsId(tapKey(channelKey, plain)) else Streams.urlId(plain)
			if (!Streams.acquire(id)) {
				say(getString(R.string.stream_busy), 1)
				finish()
				return
			}
			streamId = id
		}
		val http = DefaultHttpDataSource.Factory()
			.setUserAgent(headers["User-Agent"] ?: Net.USER_AGENT)
			.setDefaultRequestProperties(headers - "User-Agent")
			.setAllowCrossProtocolRedirects(true)
			// Some servers (Dispatcharr) take 20 s to send the first byte of a channel.
			.setConnectTimeoutMs(30_000).setReadTimeoutMs(45_000)
		// DefaultDataSource also opens content:// URIs, so saved recordings play here too.
		// Re-encoded IPTV TS often has no H.264 access-unit delimiters; without DETECT_ACCESS_UNITS
		// ExoPlayer cannot frame the video and plays sound only. NON_IDR_KEYFRAMES starts the
		// picture at the first usable frame instead of waiting for an IDR.
		val extractors = DefaultExtractorsFactory().setTsExtractorFlags(
			DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS or DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES,
		)
		// Same cache as the desktop app: same seconds, same +1 s per stall, same maximum.
		// A Dispatcharr restreamer keeps the saved buffer (see Cushion.onStall).
		cushion = Cushion(app.prefs.bufferSeconds, app.prefs.maxBufferSeconds, dispatcharr = ch?.isDispatcharr == true)
		// A live TS channel is read from its tap, so a recording of it shares this one socket;
		// every other request (catch-up, HLS, a file) goes to the ordinary data source.
		val tap = TapDataSource(tapKey(channelKey, plain), plain, streamHeaders)
		tapSource = tap
		val base = DefaultDataSource.Factory(this, http)
		val sources = DataSource.Factory { LiveSource(plain, { live && !hls }, tap) { base.createDataSource() } }
		exo = ExoPlayer.Builder(this)
			.setMediaSourceFactory(DefaultMediaSourceFactory(sources, extractors))
			.setLoadControl(cushion)
			// Live playback never changes rate to chase the live edge: the desktop holds
			// the edge by dropping late frames instead, where Media3's default control
			// speeds playback up to catch up.
			.setLivePlaybackSpeedControl(
				androidx.media3.exoplayer.DefaultLivePlaybackSpeedControl.Builder()
					.setFallbackMinPlaybackSpeed(1f).setFallbackMaxPlaybackSpeed(1f).build(),
			)
			.build()
		// Pause for calls, alarms and other apps instead of talking over them, and stop when
		// the headphones are pulled out instead of switching to the phone's speaker.
		exo.setAudioAttributes(androidx.media3.common.AudioAttributes.DEFAULT, true)
		exo.setHandleAudioBecomingNoisy(true)
		// Devices without Google Play services (many TVs) have no Cast framework: play locally only.
		castContext = if (localFile) null else runCatching { CastContext.getSharedInstance(this) }.getOrNull()
		player = castContext?.let { runCatching { CastPlayer.Builder(this).setLocalPlayer(exo).build() }.getOrNull() } ?: exo
		video = findViewById(R.id.video)
		video.player = exo
		video.setOnClickListener { if (fullscreen) setFullscreen(false) }
		onBackPressedDispatcher.addCallback(this, exitFullscreenOnBack)
		// Cast receivers need a MIME type; TS is what a typeless IPTV stream almost always is.
		val item = MediaItem.Builder().setUri(plain).setMimeType(mimeOf(plain)).build()
		player.setMediaItem(item)
		player.addListener(listener)
		if (!restoringChannel) player.prepare()
		if (!live && channelKey.isNotEmpty() && !catchup) {
			app.prefs.resume.firstOrNull { it.first == channelKey }?.second?.takeIf { it > 5_000 }?.let { player.seekTo(it) }
		}
		player.playWhenReady = true
		if (BuildConfig.DEBUG) heartbeat.run()
		if (live) status.post(pace)
		status.post(watchdog)
		say(getString(R.string.connecting), 2)
		// Handing a stream to another app cannot be limited to one connection; the old setting
		// is ignored, and said out loud once for anyone who had it on.
		if (app.prefs.useExternalPlayer) {
			app.prefs.useExternalPlayer = false
			alert(getString(R.string.play_external), getString(R.string.stream_external_blocked))
		}

		pause.setOnClickListener {
			if (restoringChannel) return@setOnClickListener
			if (player.isPlaying) player.pause()
			else {
				if (player.playbackState == M3Player.STATE_IDLE || player.playbackState == M3Player.STATE_ENDED) {
					// Retry after a failure: a fresh start — quick start, saved buffer, full budget.
					retry.removeCallbacksAndMessages(null)
					retryJob = null
					reconnect.reset()
					health.reset()
					cushion.onFreshStart()
					gaveUp = false
					if (live) prepareLive() else player.prepare()
				}
				player.play()
			}
		}
		castButton = findViewById(R.id.btnCast)
		castButton.setOnClickListener { cast() }
		findViewById<Button>(R.id.btnAudio).setOnClickListener { chooseAudio() }
		findViewById<Button>(R.id.btnSubs).setOnClickListener { chooseSubtitles() }
		// Our own subtitle line replaces PlayerView's, so the text is shown once and can be spoken.
		video.subtitleView?.visibility = View.GONE
		// The button follows the recording: while one runs, it stops it instead of starting a second.
		catchupPlayback = catchup
		findViewById<Button>(R.id.btnRecord).setOnClickListener {
			val c = app.channel(channelKey)
			when {
				c == null || catchup -> say(getString(R.string.cannot_record_this), 1)
				Recorder.isRecording(c.key) -> {
					Recorder.stop(this, c.key)
					say(getString(R.string.recording_stopped, c.name), 2)
				}
				!Recorder.canStart(c) -> say(getString(R.string.stream_busy), 1)
				else -> {
					Recorder.start(this, c, app.epg.nowNext(c).now?.title.orEmpty())
					say(getString(R.string.recording_channel, c.name), 2)
				}
			}
			updateRecordButton()
		}
		updateRecordButton()
		findViewById<Button>(R.id.btnFull).setOnClickListener { setFullscreen(true) }
		findViewById<Button>(R.id.btnClose).setOnClickListener { finish() }
		// After layout: inside the scrolling button bar an early request is dropped and nothing has focus.
		pause.post { pause.requestFocus() }
		if (restoringChannel) lifecycleScope.launch {
			// Android can restore this activity before the main screen reloads the channel cache.
			val restored = app.findChannel(channelKey, App.LIVE)
			live = !catchup && restored?.isLive == true
			restoringChannel = false
			// Resolve routing before opening a socket; replacing a direct request would tune twice.
			if (live && !localFile && !hls) {
				streamId?.let { Streams.release(it) }
				streamId = null
			}
			if (pausedByStop) return@launch
			if (!reclaimStream()) return@launch say(getString(R.string.stream_busy), 1)
			health.reset()
			status.removeCallbacks(watchdog)
			status.post(watchdog)
			player.prepare()
			if (live) status.post(pace)
			showProgramme(announce = false)
		}
	}

	private val listener = object : M3Player.Listener {
		override fun onIsPlayingChanged(isPlaying: Boolean) {
			pause.setText(if (isPlaying) R.string.pause else R.string.play)
			if (isPlaying) say(getString(R.string.playing), 3)
		}

		override fun onPlaybackStateChanged(state: Int) {
			// One line per change: lets stalls be measured from logcat (tag AiptvPlayer).
			android.util.Log.i("AiptvPlayer", "state=$state cushionMs=${cushion.targetUs / 1000} bufferedMs=${exo.totalBufferedDuration}")
			if (state == M3Player.STATE_READY) { hasPlayed = true; health.onPlaying() }
			// A live stream that ends was dropped by the server: reconnect, as the desktop does.
			if (state == M3Player.STATE_ENDED && tryReconnect()) return
			if (state == M3Player.STATE_BUFFERING) {
				// Buffering is announced; whether it is a stall is the health tick's call —
				// the cushion grows only when a restart actually happens, like the desktop.
				say(getString(R.string.buffering), 3)
			}
			if (state == M3Player.STATE_ENDED) say(getString(R.string.stream_ended), 1)
		}

		override fun onPlayerError(error: PlaybackException) {
			if (recover(grow = true)) return
			say(getString(R.string.play_failed, error.errorCodeName), 1)
			pause.setText(R.string.retry)
		}

		override fun onDeviceInfoChanged(deviceInfo: DeviceInfo) {
			val remote = deviceInfo.playbackType == DeviceInfo.PLAYBACK_TYPE_REMOTE
			// The TV reads the stream itself, so the phone must let go of its connection.
			if (remote) releaseStream() else reclaimStream()
			castButton.setText(if (remote) R.string.stop_casting else R.string.cast)
			say(
				if (remote) getString(R.string.casting_to, castContext?.sessionManager?.currentCastSession?.castDevice?.friendlyName.orEmpty())
				else getString(R.string.cast_stopped),
				2,
			)
		}

		override fun onCues(cueGroup: androidx.media3.common.text.CueGroup) {
			val text = cueGroup.cues.mapNotNull { it.text?.toString()?.trim() }.filter { it.isNotEmpty() }.joinToString("\n")
			subtitles.accessibilityLiveRegion = if (app.prefs.speakSubtitles) View.ACCESSIBILITY_LIVE_REGION_POLITE else View.ACCESSIBILITY_LIVE_REGION_NONE
			// An empty cue clears the line without a second announcement.
			if (text.isNotEmpty()) subtitles.text = text
			subtitles.visibility = if (text.isEmpty()) View.INVISIBLE else View.VISIBLE
		}

		override fun onTracksChanged(tracks: Tracks) {
			if (autoTrackDone) return
			val audio = audioTracks(tracks)
			if (audio.isEmpty()) return
			autoTrackDone = true
			val idx = AudioTracks.choose(
				audio.map { it.name },
				audio.map { it.describesVideo },
				app.prefs.rememberedTrack(channelKey),
				app.prefs.preferAudioDescription,
				app.prefs.audioKeywords.split(',').map { it.trim() }.filter { it.isNotEmpty() },
			)
			if (idx >= 0 && !audio[idx].selected) select(audio[idx])
		}
	}

	/**
	 * A status line, spoken only up to the "Automatic announcements" level in Settings
	 * (desktop: 1 errors, 2 important events, 3 detailed status). The text always updates.
	 * Full screen keeps this line: a GONE live region announces nothing, and the API for
	 * announcing on a hidden view is deprecated on Android 16.
	 */
	private fun say(text: String, priority: Int) {
		val loud = app.prefs.announceLevel >= priority
		status.accessibilityLiveRegion = if (loud) View.ACCESSIBILITY_LIVE_REGION_POLITE else View.ACCESSIBILITY_LIVE_REGION_NONE
		status.text = text
	}

	/** The Record button says what pressing it will do, including for a recording already running. */
	private fun updateRecordButton() {
		findViewById<Button>(R.id.btnRecord).setText(
			if (!catchupPlayback && channelKey.isNotEmpty() && Recorder.isRecording(channelKey)) R.string.stop_recording_channel else R.string.record,
		)
	}

	private var channelName = ""
	private lateinit var subtitles: TextView
	private val programmeTick = Runnable { showProgramme(announce = true) }

	/**
	 * Shows what is on and, when the guide says the programme changed, says "Now: ..." as an
	 * important event. Re-arms itself for the end of the programme. Live channels only.
	 */
	private fun showProgramme(announce: Boolean) {
		val nowPlaying = findViewById<TextView>(R.id.nowPlaying)
		val ch = app.channel(channelKey)?.takeIf { live }
		val nn = ch?.let { app.epg.nowNext(it) }
		val on = nn?.now
		nowPlaying.text = on?.let { Format.sentences(listOf(channelName, getString(R.string.now_programme, Format.programme(it)))) } ?: channelName
		if (announce && on != null) say(getString(R.string.now_programme, Format.programme(on)), 2)
		nowPlaying.removeCallbacks(programmeTick)
		val change = on?.stop ?: nn?.next?.start ?: return
		nowPlaying.postDelayed(programmeTick, maxOf(1_000, change - System.currentTimeMillis() + 2_000))
	}

	private class TextTrack(val group: Tracks.Group, val index: Int, val name: String)

	private fun textTracks(): List<TextTrack> = exo.currentTracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }.flatMap { g ->
		(0 until g.length).map { i ->
			val f = g.getTrackFormat(i)
			val name = listOfNotNull(f.label, f.language?.let { java.util.Locale.forLanguageTag(it).displayLanguage.ifEmpty { it } })
				.distinct().joinToString(", ").ifEmpty { getString(R.string.track_n, i + 1) }
			TextTrack(g, i, name)
		}
	}

	/** Off, or one subtitle track; the chosen text is shown under the video and spoken. */
	private fun chooseSubtitles() {
		val tracks = textTracks()
		if (tracks.isEmpty()) return say(getString(R.string.no_subtitles), 1)
		val off = exo.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT)
		val current = if (off) 0 else tracks.indexOfFirst { it.group.isTrackSelected(it.index) } + 1
		val names = listOf(getString(R.string.subtitles_off)) + tracks.map { it.name }
		AlertDialog.Builder(this).setTitle(R.string.subtitles)
			.setSingleChoiceItems(names.toTypedArray(), current) { d, which ->
				val p = exo.trackSelectionParameters.buildUpon()
				if (which == 0) {
					p.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
					subtitles.visibility = View.GONE
				} else {
					val t = tracks[which - 1]
					p.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false).setOverrideForType(TrackSelectionOverride(t.group.mediaTrackGroup, t.index))
				}
				exo.trackSelectionParameters = p.build()
				say(getString(R.string.subtitles_set, names[which]), 2)
				d.dismiss()
			}.show()
	}

	/** Schedules the next reconnect of a live stream; false when this stream does not get one. */
	private fun tryReconnect(grow: Boolean = false): Boolean = if (live) recover(grow) else false

	/**
	 * Schedules a reopen of the current stream inside the reconnect budget, for every
	 * kind of playback; false when this stream gets none (the screen is left, or the TV
	 * is playing). [grow] is set when the stall earns a bigger cushion, as a desktop
	 * stall does; when the budget is spent the channel is declared lost instead of
	 * retrying forever.
	 */
	private fun recover(grow: Boolean): Boolean {
		if (pausedByStop || player.deviceInfo.playbackType == DeviceInfo.PLAYBACK_TYPE_REMOTE) return false
		if (retryJob != null) return true
		val delay = reconnect.next() ?: run { giveUp(); return true }
		if (grow) cushion.onStall() else cushion.onReopen()
		say(getString(R.string.reconnecting), 2)
		val job = Runnable {
			retryJob = null
			if (isFinishing || isDestroyed || pausedByStop) return@Runnable
			if (live) {
				// Behind the live window after a drop: start again at the live edge.
				prepareLive()
			} else {
				// A film or catch-up reopens where it stopped.
				val item = player.currentMediaItem ?: return@Runnable
				val at = exo.currentPosition
				hasPlayed = false
				health.onPrepared()
				player.stop()
				player.setMediaItem(item, at)
				player.prepare()
			}
			player.play()
		}
		retryJob = job
		retry.postDelayed(job, delay)
		return true
	}

	/**
	 * The budget is spent, or the channel never played at all: the desktop declares it
	 * lost, rather than leaving a silent spinner up.
	 */
	private fun giveUp() {
		if (gaveUp) return
		gaveUp = true
		cancelRetry()
		player.stop()
		tapSource?.release()
		say(getString(R.string.stream_lost), 1)
		pause.setText(R.string.retry)
	}

	/**
	 * Takes the one provider stream this screen needs again: a live TS channel is taken by its
	 * tap when the media is opened, everything else by the gate here. False = another stream
	 * is in use, which the caller says out loud.
	 */
	private fun reclaimStream(): Boolean {
		if (localFile || streamId != null || (live && !hls)) return true
		val id = if (live) Streams.hlsId(tapKey(channelKey, plainUrl)) else Streams.urlId(plainUrl)
		if (!Streams.acquire(id)) return false
		streamId = id
		return true
	}

	/** Frees the one provider stream this screen held: the next stream may start now. */
	private fun releaseStream() {
		streamId?.let { Streams.release(it) }
		streamId = null
		tapSource?.release()
	}

	private fun prepareLive() {
		val item = player.currentMediaItem ?: return
		autoTrackDone = false
		// A recovery reopen keeps the cushion it earned (armed in Cushion by recover):
		// the reopen fills the full saved buffer before sound returns, like the desktop.
		hasPlayed = false
		health.onPrepared()
		// A fresh media period discards stale sample queues and timestamp adjusters after a source reset.
		tapSource?.retainForRecovery()
		player.stop()
		player.setMediaItem(item, true)
		player.prepare()
	}

	private fun mimeOf(url: String): String {
		val path = url.substringBefore('?').lowercase()
		return when {
			path.endsWith(".m3u8") -> MimeTypes.APPLICATION_M3U8
			path.endsWith(".mp4") || path.endsWith(".m4v") -> MimeTypes.VIDEO_MP4
			path.endsWith(".mkv") -> MimeTypes.VIDEO_MATROSKA
			else -> MimeTypes.VIDEO_MP2T
		}
	}

	/**
	 * Full screen hides every control and the system bars. The video becomes one focusable,
	 * labelled item so a screen reader, a remote's OK button or a tap can bring the controls back.
	 */
	private fun setFullscreen(on: Boolean) {
		fullscreen = on
		exitFullscreenOnBack.isEnabled = on
		val shown = if (on) View.GONE else View.VISIBLE
		val bars = WindowCompat.getInsetsController(window, window.decorView)
		if (on) {
			bars.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
			bars.hide(WindowInsetsCompat.Type.systemBars())
		} else bars.show(WindowInsetsCompat.Type.systemBars())
		// Order matters: the new target must hold focus before the old one stops being focusable,
		// or Android hands focus to the first button (Pause) and the next OK pauses the stream.
		if (on) {
			video.isFocusable = true
			video.isClickable = true
			video.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
			video.contentDescription = getString(R.string.video_full_screen)
			video.requestFocus()
			// The status line stays: it is the announcement channel in full screen too.
			for (id in listOf(R.id.controls, R.id.nowPlaying)) findViewById<View>(id).visibility = shown
		} else {
			for (id in listOf(R.id.controls, R.id.nowPlaying)) findViewById<View>(id).visibility = shown
			findViewById<View>(R.id.btnFull).requestFocus()
			video.isFocusable = false
			video.isClickable = false
			video.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
			video.contentDescription = null
		}
		val target = if (on) video else findViewById<View>(R.id.btnFull)
		target.post { target.performAccessibilityAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null) }
	}


	private fun cast() {
		if (localFile) return say(getString(R.string.cast_local_file), 1)
		val ctx = castContext ?: return say(getString(R.string.cast_unavailable), 1)
		if (player.deviceInfo.playbackType == DeviceInfo.PLAYBACK_TYPE_REMOTE) ctx.sessionManager.endCurrentSession(true)
		else MediaRouteChooserDialog(this).apply { routeSelector = ctx.mergedSelector ?: MediaRouteSelector.EMPTY }.show()
	}

	private class AudioTrack(val group: Tracks.Group, val index: Int, val name: String, val describesVideo: Boolean, val selected: Boolean)

	private fun audioTracks(tracks: Tracks = exo.currentTracks): List<AudioTrack> {
		val out = ArrayList<AudioTrack>()
		for (g in tracks.groups) {
			if (g.type != C.TRACK_TYPE_AUDIO) continue
			for (i in 0 until g.length) {
				val f = g.getTrackFormat(i)
				val name = listOfNotNull(f.label, f.language?.let { java.util.Locale.forLanguageTag(it).displayLanguage.ifEmpty { it } })
					.distinct().joinToString(", ").ifEmpty { getString(R.string.track_n, out.size + 1) }
				out += AudioTrack(g, i, name, f.roleFlags and C.ROLE_FLAG_DESCRIBES_VIDEO != 0, g.isTrackSelected(i))
			}
		}
		return out
	}

	private fun select(t: AudioTrack) {
		exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
			.setOverrideForType(TrackSelectionOverride(t.group.mediaTrackGroup, t.index)).build()
	}

	private fun chooseAudio() {
		val audio = audioTracks()
		if (audio.isEmpty()) { say(getString(R.string.no_audio_tracks), 1); return }
		val current = audio.indexOfFirst { it.selected }
		AlertDialog.Builder(this).setTitle(R.string.audio_track)
			.setSingleChoiceItems(audio.map { it.name }.toTypedArray(), current) { d, which ->
				select(audio[which])
				// Remembered per channel, ahead of every other rule, like the desktop app.
				if (channelKey.isNotEmpty()) app.prefs.rememberTrack(channelKey, audio[which].name)
				say(getString(R.string.audio_track_set, audio[which].name), 2)
				d.dismiss()
			}.show()
	}

	override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean = when (keyCode) {
		// Media keys on remotes and keyboards; some send Play and Pause separately.
		KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> { pause.performClick(); true }
		KeyEvent.KEYCODE_MEDIA_PLAY -> { if (!player.isPlaying) pause.performClick(); true }
		KeyEvent.KEYCODE_MEDIA_PAUSE -> { if (player.isPlaying) pause.performClick(); true }
		KeyEvent.KEYCODE_A -> { chooseAudio(); true }
		KeyEvent.KEYCODE_S, KeyEvent.KEYCODE_CAPTIONS -> { chooseSubtitles(); true }
		KeyEvent.KEYCODE_F -> { setFullscreen(!fullscreen); true }
		else -> super.onKeyUp(keyCode, event)
	}

	override fun onStop() {
		super.onStop()
		cancelRetry()
	}

	/**
	 * A hidden player must not hold the provider's socket: the stream has to be free for a
	 * recording or another channel before anything else starts. Playing resumes in [onStart].
	 */
	override fun onPause() {
		super.onPause()
		// Refused before the player existed (another stream is in use): nothing is playing.
		if (!this::player.isInitialized) return
		if (player.deviceInfo.playbackType == DeviceInfo.PLAYBACK_TYPE_REMOTE || pausedByStop) return
		// Catch-up and files resume where they were; a live channel starts at the live edge again.
		if (!live && exo.playbackState != M3Player.STATE_IDLE) { resumeAt = exo.currentPosition; saveResume(exo.currentPosition, exo.duration) }
		pausedByStop = true
		cancelRetry()
		status.removeCallbacks(pace)
		status.removeCallbacks(watchdog)
		player.stop() // closes the data source, which leaves the tap
		releaseStream()
	}

	private fun saveResume(positionMs: Long, durationMs: Long) {
		if (live || channelKey.isEmpty() || positionMs < 5_000) return
		if (durationMs > 0 && positionMs >= durationMs - 30_000) app.prefs.clearResume(channelKey)
		else app.prefs.rememberResume(channelKey, positionMs)
	}

	private fun cancelRetry() {
		retryJob?.let { retry.removeCallbacks(it) }
		retryJob = null
	}

	override fun onStart() {
		super.onStart()
		// The recording may have been stopped from its notification while the app was away.
		updateRecordButton()
		if (!pausedByStop || player.deviceInfo.playbackType == DeviceInfo.PLAYBACK_TYPE_REMOTE) return
		pausedByStop = false
		if (restoringChannel) return
		if (!reclaimStream()) return say(getString(R.string.stream_busy), 1)
		// The player was stopped while away: this open is a fresh start, not a recovery.
		cushion.onFreshStart()
		health.reset()
		gaveUp = false
		status.post(watchdog)
		// Back in the app: live TV picks up at the live edge, not minutes behind where it stopped.
		if (live) {
			prepareLive()
			status.post(pace)
		} else {
			player.seekTo(resumeAt)
			player.prepare()
		}
		player.play()
	}

	override fun onDestroy() {
		retry.removeCallbacksAndMessages(null)
		findViewById<View>(R.id.nowPlaying).removeCallbacks(programmeTick)
		status.removeCallbacks(pace)
		status.removeCallbacks(watchdog)
		if (this::player.isInitialized) {
			releaseStream()
			player.release() // releases the wrapped local player too
		}
		super.onDestroy()
	}
}

/**
 * Routes each request to the shared live tap or to the ordinary data source: a live TS
 * channel plays from the tap (one socket, shared with any recording of it) while every other
 * request — catch-up, HLS, a file — opens normally. Live-ness is read per request, because a
 * restored player learns its channel after the item was prepared.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
private class LiveSource(
	private val plain: String,
	private val live: () -> Boolean,
	private val tap: TapDataSource,
	private val base: () -> DataSource,
) : DataSource {
	private var use: DataSource = tap
	private var uri: Uri = Uri.EMPTY

	override fun open(dataSpec: DataSpec): Long {
		uri = dataSpec.uri
		use = if (live() && dataSpec.uri.toString() == plain) tap else base()
		return use.open(dataSpec)
	}

	override fun read(buffer: ByteArray, offset: Int, length: Int): Int = use.read(buffer, offset, length)

	override fun getUri(): Uri? = if (use === tap) uri else use.uri

	override fun close() = use.close()

	override fun addTransferListener(transferListener: TransferListener) = Unit
}

/**
 * Plays a live channel from its shared tap instead of opening a second provider connection:
 * opening joins the channel (a recording of the same channel joins the same one) and starts
 * at the live edge; decoder recovery retains the socket until the new reader opens.
 * Leaving the screen releases every retained reader and closes an unshared socket.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
private class TapDataSource(private val key: String, private val url: String, private val headers: Map<String, String>) : DataSource {
	private var channel: LiveChannel? = null
	private var retained: LiveChannel? = null
	@Volatile private var input: InputStream? = null
	private var uri: Uri = Uri.EMPTY

	/** The shared channel this reader has, while it has it: watched for reconnecting. */
	val joined: LiveChannel? get() = synchronized(this) { (channel ?: retained)?.takeIf { !it.isClosed } }

	@Synchronized fun retainForRecovery() {
		val active = channel?.takeIf { !it.isClosed } ?: return
		if (retained == null) retained = active.join()
	}

	@Synchronized override fun open(dataSpec: DataSpec): Long {
		uri = dataSpec.uri
		close()
		val held = retained
		retained = null
		if (held?.isClosed == true) LiveChannels.leave(held)
		val joining = held?.takeIf { !it.isClosed } ?: LiveChannels.attachUrl(key, url, headers)
			?: throw IOException("Another stream is in use")
		channel = joining
		input = joining.reader()
		// A live stream has no length, and nothing before the live edge is worth having.
		return C.LENGTH_UNSET.toLong()
	}

	override fun read(buffer: ByteArray, offset: Int, length: Int): Int = input?.read(buffer, offset, length) ?: -1

	override fun getUri(): Uri = uri

	@Synchronized override fun close() {
		runCatching { input?.close() }
		input = null
		channel?.let { LiveChannels.leave(it) }
		channel = null
	}

	@Synchronized fun release() {
		close()
		retained?.let { LiveChannels.leave(it) }
		retained = null
	}

	override fun addTransferListener(transferListener: TransferListener) = Unit
}
