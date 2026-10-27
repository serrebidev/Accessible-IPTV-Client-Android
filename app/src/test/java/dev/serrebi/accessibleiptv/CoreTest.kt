package dev.serrebi.accessibleiptv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset

class CoreTest {
	@Test fun m3uParsesAttributesTitleWithCommaAndHeaderCatchup() {
		val text = """
			#EXTM3U catchup="xc" catchup-days="3"
			#EXTINF:-1 tvg-id="bbc1.uk" tvg-name="BBC One" group-title="UK, News",BBC One, London
			http://h/live/u/p/1.ts
			#EXTINF:-1,No Group
			#EXTGRP:Misc
			http://h/live/u/p/2.ts
		""".trimIndent()
		val ch = M3uParser.parse("s", text)
		assertEquals(2, ch.size)
		assertEquals("BBC One, London", ch[0].name)
		assertEquals("UK, News", ch[0].group)
		assertEquals("bbc1.uk", ch[0].tvgId)
		assertEquals(3, ch[0].catchupDays)
		assertEquals("Misc", ch[1].group)
	}

	@Test fun headerTailBecomesHeaders() {
		val (url, h) = M3uParser.splitHeaders("http://a/b.ts|UA=VLC&Referer=http%3A%2F%2Fx")
		assertEquals("http://a/b.ts", url)
		assertEquals("VLC", h["User-Agent"])
		assertEquals("http://x", h["Referer"])
		assertEquals("a lower-case name is the same header", "Zebra", M3uParser.splitHeaders("http://a/b.ts|user-agent=Zebra").second["User-Agent"])
	}

	private fun ch(url: String, attrs: Map<String, String>) = Channel("s", "1", "C", "G", url, attrs = attrs)

	@Test fun xtreamTimeshiftUrl() {
		val start = 1_760_000_400_000L // 2025-10-09 09:00 UTC
		val url = Catchup.url(ch("http://h:80/live/u/p/42.ts", mapOf("catchup" to "xc")), start, start + 30 * 60_000, ZoneOffset.UTC)
		assertEquals("http://h:80/timeshift/u/p/30/2025-10-09:09-00/42.ts", url)
	}

	@Test fun templateCatchupIsAppended() {
		val start = 1_000_000_000_000L
		val url = Catchup.url(ch("http://h/x.m3u8?t=1", mapOf("catchup-source" to "?utc=\${start}&lutc=\${timestamp}")), start, start + 60_000, ZoneOffset.UTC, nowMs = 2_000_000_000_000L)
		assertEquals("http://h/x.m3u8?t=1&utc=1000000000&lutc=2000000000", url)
	}

	@Test fun noCatchupGivesNull() = assertNull(Catchup.url(ch("http://h/1.ts", emptyMap()), 0, 60_000))

	@Test fun audioDescriptionMatching() {
		assertTrue(AudioTracks.matches("eng AD", "ad"))
		assertFalse(AudioTracks.matches("Radio", "ad"))
		assertTrue(AudioTracks.matches("Audiodescription-Ton", "audiodescription"))
		val names = listOf("English", "English (Audio Description)")
		assertEquals(1, AudioTracks.choose(names, listOf(false, false), null, true, emptyList()))
		assertEquals(0, AudioTracks.choose(names, listOf(false, false), "English", true, emptyList()))
		assertEquals(-1, AudioTracks.choose(names, listOf(false, false), null, false, emptyList()))
		// The DVB "describes video" flag wins even when the name says nothing.
		assertEquals(1, AudioTracks.choose(listOf("Polish", "x"), listOf(false, true), null, true, emptyList()))
	}

	@Test fun xmltvTimes() {
		assertEquals(0L, Xmltv.parseTime("19700101000000 +0000"))
		assertEquals(-7_200_000L, Xmltv.parseTime("19700101000000 +0200"))
		assertEquals(0L, Xmltv.parseTime("19700101000000"))
		assertNull(Xmltv.parseTime("bad"))
	}

	@Test fun m3uKindsFromPath() {
		assertEquals(Channel.KIND_MOVIE, M3uParser.kindOf("http://h/movie/u/p/1.mp4", ""))
		assertEquals(Channel.KIND_EPISODE, M3uParser.kindOf("http://h/series/u/p/9.mkv", ""))
		assertEquals(Channel.KIND_LIVE, M3uParser.kindOf("http://h/live/u/p/2.ts", ""))
	}

	@Test fun stalkerBaseUrl() {
		assertEquals("http://portal.tv:8080", Stalker.baseUrl("portal.tv:8080/c/"))
		assertEquals("http://p.tv/stalker_portal", Stalker.baseUrl("http://p.tv/stalker_portal/server/load.php"))
	}

	@Test fun sentencesDoNotDoubleStops() {
		assertEquals("A, 8:00 p.m. Next: B, 9 p.m.", Format.sentences(listOf("A, 8:00 p.m.", "Next: B, 9 p.m.")))
	}

	@Test fun episodeTitlesDropRepetition() {
		assertEquals("", Xtream.episodeTitle("#1 Happy Family USA - S01E01", "#1 Happy Family USA (2025)"))
		assertEquals("The Pilot", Xtream.episodeTitle("Lost - S01E01 - The Pilot", "Lost"))
		assertEquals("The Pilot", Xtream.episodeTitle("The Pilot", "Lost"))
	}

	@Test fun updateVersionsCompareNumerically() {
		assertTrue(Updater.isNewer("0.10.0", "0.9.3"))
		assertFalse(Updater.isNewer("0.2.0", "0.2.0"))
		assertFalse(Updater.isNewer("0.1.9", "0.2"))
	}

	private fun params(bufferedSec: Double, rebuffering: Boolean = true) = androidx.media3.exoplayer.LoadControl.Parameters(
		androidx.media3.exoplayer.analytics.PlayerId.UNSET, androidx.media3.common.Timeline.EMPTY,
		androidx.media3.exoplayer.source.MediaSource.MediaPeriodId(Any()), 0, (bufferedSec * 1_000_000).toLong(),
		1f, true, rebuffering, androidx.media3.common.C.TIME_UNSET, androidx.media3.common.C.TIME_UNSET,
	)

	@Test fun quickStartThenFullCushionAfterAStall() {
		val c = Cushion(6.0, 8.0)
		assertFalse(c.shouldStartPlayback(params(0.49, rebuffering = false)))
		assertTrue("the first start waits only half a second", c.shouldStartPlayback(params(0.5, rebuffering = false)))
		assertTrue("never more than the cushion itself", Cushion(0.25, 8.0).shouldStartPlayback(params(0.25, rebuffering = false)))
	}

	@Test fun fillingTheCushionNeverSlowsPlayback() {
		// The desktop's rule (2026-10-17): a speed under 1.0 made speech drag, so the
		// cushion fills from what the server hands over ahead of real time instead.
		// Unit tests run with the app module as the working directory.
		val dir = java.io.File("src/main/java/dev/serrebi/accessibleiptv")
		val offenders = dir.listFiles { f -> f.extension == "kt" }!!.filter { file ->
			val text = file.readText()
			text.contains("setPlaybackSpeed") || text.contains("setPlaybackParameters")
		}
		assertTrue("playback speed is set in $offenders", offenders.isEmpty())
	}

	@Test fun dispatcharrStreamKeepsTheSavedBufferThroughAStall() {
		// Dispatcharr restreams from its own buffer: its channels keep the saved setting.
		val c = Cushion(6.0, 30.0, dispatcharr = true)
		repeat(3) { c.onStall() }
		assertEquals(6_000_000L, c.targetUs)
		assertTrue(c.shouldStartPlayback(params(6.0)))
		// The marker rides the playlist header, like the desktop's.
		val plain = Channel("s", "1", "A", "G", "http://h/1")
		assertFalse(plain.isDispatcharr)
		assertTrue(plain.copy(attrs = mapOf("catchup-timezone" to "UTC")).isDispatcharr)
	}

	@Test fun twentyFiveSecondCushionKeepsTheHalfSecondStart() {
		val c = Cushion(Prefs.DEFAULT_BUFFER.toDouble(), 30.0)
		assertFalse(c.shouldStartPlayback(params(0.49, rebuffering = false)))
		assertTrue(c.shouldStartPlayback(params(0.5, rebuffering = false)))
		c.onStall()
		assertFalse(c.shouldStartPlayback(params(25.0)))
		assertTrue(c.shouldStartPlayback(params(26.0)))
		repeat(8) { c.onStall() }
		assertEquals(30_000_000L, c.targetUs)
	}

	@Test fun cushionMatchesDesktopCache() {
		val c = Cushion(6.0, 8.0)
		assertFalse(c.shouldStartPlayback(params(5.9)))
		assertTrue(c.shouldStartPlayback(params(6.0)))
		c.onStall() // 7 s
		assertFalse(c.shouldStartPlayback(params(6.5)))
		assertTrue(c.shouldStartPlayback(params(7.0)))
		c.onStall(); c.onStall() // capped at 8 s
		assertTrue(c.shouldStartPlayback(params(8.0)))
		assertEquals(8_000_000L, c.targetUs)
		// The calls ExoPlayer makes while building the player must not hit LoadControl's
		// throwing defaults (crashed the player on open).
		val id = androidx.media3.exoplayer.analytics.PlayerId.UNSET
		c.onPrepared(id) // ExoPlayer calls this first
		c.getBackBufferDurationUs(id); c.retainBackBufferFromKeyframe(id); c.getAllocator(id)
		c.onStopped(id); c.onReleased(id)
	}

	@Test fun nameKeyIgnoresPrefixAndQuality() {
		assertEquals(Xmltv.nameKey("BBC One"), Xmltv.nameKey("UK: BBC One HD"))
	}

	@Test fun warmHostsDedupesCapsAndSkipsBadUrls() {
		val hosts = Net.warmHosts(listOf(
			"https://tv.example.com/proxy/ts/stream/aaa",
			"https://tv.example.com/proxy/ts/stream/bbb",
			"http://other.example.net:8080/live/1.ts|UA=VLC",
			"not a url",
			"https://third.example.org/x",
		)).map { it.toString() }
		// Distinct scheme/host/port roots, first seen first, capped at two.
		assertEquals(listOf("https://tv.example.com/", "http://other.example.net:8080/"), hosts)
		assertTrue(Net.warmHosts(emptyList()).isEmpty())
	}
}
