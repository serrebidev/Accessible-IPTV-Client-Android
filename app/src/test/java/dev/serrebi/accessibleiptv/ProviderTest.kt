package dev.serrebi.accessibleiptv

import dev.serrebi.accessibleiptv.FakeServer.Companion.gzip
import dev.serrebi.accessibleiptv.FakeServer.Companion.query
import dev.serrebi.accessibleiptv.FakeServer.Companion.redirect
import dev.serrebi.accessibleiptv.FakeServer.Companion.reply
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.IOException
import java.security.MessageDigest

/** HTTP, Xtream, Stalker and the updater against a local fake provider. */
@RunWith(RobolectricTestRunner::class)
class ProviderTest {
	private val server = FakeServer()

	@After fun stop() = server.close()

	// ---- Net ----

	@Test fun redirectsAreFollowedIncludingRelativeOnes() {
		server.on("/a") { it.redirect("/b") }
		server.on("/b") { it.redirect("${server.url}/c", 301) }
		server.text("/c", "done")
		assertEquals("done", Net.text("${server.url}/a"))
	}

	@Test fun gzipBodiesAreUnwrappedWithoutAHeader() {
		server.bytes("/g", gzip("hello"))
		assertEquals("hello", Net.text("${server.url}/g"))
	}

	@Test fun httpErrorsSayTheCode() {
		try {
			Net.text("${server.url}/nope")
			fail()
		} catch (e: IOException) {
			assertEquals("Server answered 404", e.message)
		}
	}

	@Test fun redirectLoopsEnd() {
		server.on("/loop") { it.redirect("/loop") }
		try { Net.text("${server.url}/loop"); fail() } catch (e: IOException) { assertTrue(e.message!!.contains("redirect", true)) }
	}

	@Test fun jsonStreamSkipsNestingAndRejectsErrorObjects() {
		server.text("/list", """[{"a":"1","n":2,"b":true,"x":{"deep":1},"y":[1,2],"z":null},"junk",{"a":"2"}]""")
		val seen = ArrayList<Map<String, String>>()
		Net.forEachObject("${server.url}/list") { seen += HashMap(it) }
		assertEquals(listOf(mapOf("a" to "1", "n" to "2", "b" to "true"), mapOf("a" to "2")), seen)
		server.text("/err", """{"user_info":{"auth":0}}""")
		org.junit.Assert.assertThrows(IOException::class.java) {
			Net.forEachObject("${server.url}/err") { fail("an error object has no items") }
		}
	}

	@Test fun headersFromThePlaylistReachTheServer() {
		server.text("/s", "ok")
		Net.open("${server.url}/s", mapOf("User-Agent" to "Kodi/20", "Referer" to "http://r/")).close()
		assertEquals("Kodi/20", server.headers.last()["user-agent"])
		assertEquals("http://r/", server.headers.last()["referer"])
	}

	@Test fun cancelledLiveCallCannotOpenAnotherProviderConnection() {
		val call = Net.Call("${server.url}/cancelled", emptyMap())
		call.close()
		org.junit.Assert.assertThrows(IOException::class.java) { call.open() }
		assertTrue(server.requests.isEmpty())
	}

	@Test fun cancellingLiveCallReleasesARequestWaitingForItsFirstByte() = cancelWaitingLiveCall(headersFirst = true)

	@Test fun cancellingLiveCallReleasesARequestWaitingForHeaders() = cancelWaitingLiveCall(headersFirst = false)

	private fun cancelWaitingLiveCall(headersFirst: Boolean) {
		val headers = java.util.concurrent.CountDownLatch(1)
		val hold = java.util.concurrent.CountDownLatch(1)
		val ended = java.util.concurrent.CountDownLatch(1)
		val cancelled = java.util.concurrent.CountDownLatch(1)
		server.on("/slow") { ex ->
			if (headersFirst) ex.sendResponseHeaders(200, 0)
			headers.countDown()
			hold.await(5, java.util.concurrent.TimeUnit.SECONDS)
			if (!headersFirst) ex.sendResponseHeaders(200, 0)
		}
		val call = Net.Call("${server.url}/slow", emptyMap())
		val worker = Thread { try { runCatching { call.open().close() } } finally { ended.countDown() } }
		worker.start()
		try {
			assertTrue(headers.await(3, java.util.concurrent.TimeUnit.SECONDS))
			Thread { try { call.close() } finally { cancelled.countDown() } }.start()
			assertTrue("cancellation must not wait for the provider", cancelled.await(2, java.util.concurrent.TimeUnit.SECONDS))
			assertTrue("the old request must end", ended.await(2, java.util.concurrent.TimeUnit.SECONDS))
		} finally { hold.countDown(); worker.join(3_000) }
	}

	@Test fun liveCallsKeepRedirectsHeadersGzipAndHttpErrors() {
		server.on("/live") { it.redirect("/gzip") }
		server.bytes("/gzip", gzip("transport stream"))
		Net.Call("${server.url}/live", mapOf("User-Agent" to "Kodi/20", "Referer" to "http://r/")).use { call ->
			assertEquals("transport stream", call.open().use { it.readBytes().toString(Charsets.UTF_8) })
		}
		assertEquals("Kodi/20", server.headers.last()["user-agent"])
		assertEquals("http://r/", server.headers.last()["referer"])
		Net.Call("${server.url}/missing", emptyMap()).use { call ->
			val error = org.junit.Assert.assertThrows(IOException::class.java) { call.open() }
			assertEquals("Server answered 404", error.message)
		}
	}

	// ---- Xtream ----

	private fun xtreamServer() {
		server.on("/player_api.php") { ex ->
			val q = ex.query()
			if (q["username"] != "me" || q["password"] != "p&w") return@on ex.reply(200, """{"user_info":{"auth":0}}""")
			ex.reply(200, when (q["action"]) {
				"get_live_categories" -> """[{"category_id":"1","category_name":"News"}]"""
				"get_live_streams" -> """[
					{"stream_id":10,"name":"BBC","category_id":"1","epg_channel_id":"bbc.uk","tv_archive":1,"tv_archive_duration":"3"},
					{"stream_id":11,"name":"Lost","category_id":"9","epg_channel_id":null}]"""
				"get_vod_categories" -> """[{"category_id":"5","category_name":"Films"}]"""
				"get_vod_streams" -> """[{"stream_id":7,"name":"Film","category_id":"5","container_extension":"mkv"},{"stream_id":8,"name":"NoExt","category_id":"5"}]"""
				"get_series_categories" -> """[]"""
				"get_series" -> """[{"series_id":3,"name":"Show","plot":"About"}]"""
				"get_series_info" -> """{"episodes":[[{"id":"100","episode_num":1,"title":"Show - S01E01 - Pilot","container_extension":"mp4","info":{"plot":"P"}}],[{"id":"200","title":""}]]}"""
				null -> """{"user_info":{"status":"Active","exp_date":"1","is_trial":"0","active_cons":"0","max_connections":"1"}}"""
				else -> "[]"
			})
		}
	}

	private fun xtream() = Source("x", Source.TYPE_XTREAM, "X", server.url, "me", "p&w")

	@Test fun xtreamLiveChannels() {
		xtreamServer()
		val ch = Xtream.liveChannels(xtream())
		assertEquals(listOf("BBC", "Lost"), ch.map { it.name })
		assertEquals("News", ch[0].group)
		assertEquals("Ungrouped", ch[1].group)
		assertEquals("bbc.uk", ch[0].tvgId)
		assertEquals("", ch[1].tvgId) // JSON null is not the id "null"
		assertEquals(3, ch[0].catchupDays)
		assertFalse(ch[1].hasCatchup)
		// TS, and the password is escaped inside the path.
		assertEquals("${server.url}/live/me/p%26w/10.ts", ch[0].url)
	}

	@Test fun xtreamMoviesAndSeries() {
		xtreamServer()
		val movies = Xtream.movies(xtream())
		assertEquals("${server.url}/movie/me/p%26w/7.mkv", movies[0].url)
		assertTrue(movies[1].url.endsWith("/8.mp4"))
		assertEquals(Channel.KIND_MOVIE, movies[0].kind)
		val shows = Xtream.series(xtream())
		assertEquals(Channel.KIND_SERIES, shows[0].kind)
		assertEquals("About", shows[0].attrs["plot"])
	}

	@Test fun episodesInListFormGetSeasonNumbers() {
		xtreamServer()
		val show = Xtream.series(xtream())[0]
		val eps = Xtream.episodes(xtream(), show)
		assertEquals(listOf("Season 1, Episode 1: Pilot", "Season 2, Episode 1"), eps.map { it.name })
		assertEquals("${server.url}/series/me/p%26w/100.mp4", eps[0].url)
	}

	@Test fun episodesKeyedBySeasonSortNumerically() {
		val body = """{"episodes":{"10":[{"id":"3","episode_num":"1"}],"2":[{"id":"2","episode_num":"5"}],"1":[{"id":"1","episode_num":"1"},{"title":"no id"}]}}"""
		val eps = Xtream.parseEpisodes(xtream(), Channel("x", "s1", "Show", "", "1", kind = Channel.KIND_SERIES), body)
		assertEquals(listOf("Season 1, Episode 1", "Season 2, Episode 5", "Season 10, Episode 1"), eps.map { it.name })
		assertEquals(emptyList<Channel>(), Xtream.parseEpisodes(xtream(), eps[0], """{"episodes":null}"""))
	}

	@Test fun expiredAccountsSayExpired() {
		xtreamServer()
		val info = Xtream.accountInfo(xtream()).toMap()
		assertEquals("Active", info["Status"])
		assertTrue(info["Expires"]!!.endsWith("expired"))
		assertEquals("0 of 1", info["Connections in use"])
		assertEquals("Never", Xtream.parseAccount("""{"user_info":{"exp_date":null}}""").toMap()["Expires"])
		assertEquals(emptyList<Pair<String, String>>(), Xtream.parseAccount("""{"user_info":"bad"}"""))
	}

	@Test fun xtreamGuideUrlIsEscaped() {
		assertEquals("${server.url}/xmltv.php?username=me&password=p%26w", xtream().effectiveEpgUrl)
	}

	// ---- Stalker ----

	private fun portal() {
		server.on("/portal.php") { ex ->
			val q = ex.query()
			if (q["action"] != "handshake" && ex.requestHeaders.getFirst("Authorization") != "Bearer T1") return@on ex.reply(401, "")
			ex.reply(200, when (q["action"]) {
				"handshake" -> """{"js":{"token":"T1"}}"""
				"get_genres" -> """{"js":[{"id":"4","title":"Sport"}]}"""
				"get_all_channels" -> """{"js":{"data":[
					{"id":"1","name":"Arena","cmd":"ffmpeg http://s/1","tv_genre_id":"4","xmltv_id":"arena","allow_timeshift":"1","tv_archive_duration":"2"},
					{"id":"2","name":"","cmd":"x"},
					{"id":"3","name":"NoCmd","cmd":""}]}}"""
				"create_link" -> if (q["start"] != null) """{"js":{"cmd":"auto http://s/arch?start=${q["start"]}&d=${q["duration"]}"}}""" else """{"js":{"cmd":"ffmpeg http://s/live/${q["cmd"]!!.substringAfterLast('/')}"}}"""
				"get_main_info" -> """{"js":{"tariff_plan":"Gold","end_date":"2030-01-01","phone":"","fname":"null"}}"""
				else -> "{}"
			})
		}
	}

	private fun stalker() = Source("st", Source.TYPE_STALKER, "S", server.url, mac = "00:1A:79:00:00:01")

	@Test fun stalkerChannelsLinksAndAccount() {
		portal()
		val s = Stalker(stalker())
		val ch = s.channels()
		assertEquals(listOf("Arena"), ch.map { it.name })
		assertEquals("Sport", ch[0].group)
		assertEquals("stalker:ffmpeg http://s/1", ch[0].url)
		assertTrue(ch[0].needsResolve)
		assertEquals(2, ch[0].catchupDays)
		assertEquals("http://s/live/1", s.link("ffmpeg http://s/1"))
		assertEquals("http://s/arch?start=2025-10-09:09-00&d=30", s.link("ffmpeg http://s/1", "2025-10-09:09-00", 30))
		assertEquals(listOf("Plan" to "Gold", "Expires" to "2030-01-01"), s.accountInfo())
		// MAG identity on every call, one handshake reused.
		assertEquals(1, server.requests.count { "action=handshake" in it })
		assertTrue(server.headers.all { "mac=00:1A:79:00:00:01" in it["cookie"].orEmpty() })
	}

	@Test fun stalkerHandshakeWithoutTokenFails() {
		server.text("/portal.php", """{"js":{}}""")
		try { Stalker(stalker()).channels(); fail() } catch (e: IOException) { assertEquals("Portal handshake failed", e.message) }
	}

	// ---- Updater ----

	private val apk = "fake apk bytes".toByteArray()
	private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

	@Test fun updateDownloadIsVerified() {
		server.bytes("/app.apk", apk)
		val ctx = RuntimeEnvironment.getApplication()
		val good = Updater.download(ctx, Updater.Release("9.9.9", "${server.url}/app.apk", sha(apk), ""))
		assertEquals(apk.toList(), good.readBytes().toList())
		try {
			Updater.download(ctx, Updater.Release("9.9.9", "${server.url}/app.apk", sha("other".toByteArray()), ""))
			fail()
		} catch (e: IOException) {
			assertEquals("Checksum mismatch", e.message)
			assertFalse("a bad download is not left for the installer", java.io.File(ctx.cacheDir, "update.apk").exists())
		}
		try {
			Updater.download(ctx, Updater.Release("9.9.9", "${server.url}/app.apk", null, ""))
			fail()
		} catch (e: IOException) {
			assertEquals("a release without a digest is never installed blind", "The release has no checksum", e.message)
		}
	}

	@Test fun updateCheckReadsForgejoRelease() {
		val digest = sha(apk)
		server.text("/releases/latest", """{"tag_name":"v9.9.9","body":"notes here","assets":[
			{"name":"app.apk","browser_download_url":"${server.url}/app.apk"},
			{"name":"app.apk.sha256","browser_download_url":"${server.url}/app.apk.sha256"}]}""")
		server.bytes("/app.apk", apk)
		server.text("/app.apk.sha256", "$digest  app.apk\n")
		val r = Updater.check("9.9.8", server.url)
		assertEquals("9.9.9", r!!.version)
		assertEquals("${server.url}/app.apk", r.apkUrl)
		assertEquals(digest, r.sha256)
		assertEquals("notes here", r.notes)
		assertNull("current builds are not offered their own release", Updater.check("9.9.9", server.url))
	}

	@Test fun updateCheckWithoutSidecarLeavesNothingToVerify() {
		server.text("/releases/latest", """{"tag_name":"v9.9.9","body":"","assets":[
			{"name":"app.apk","browser_download_url":"${server.url}/app.apk"}]}""")
		val r = Updater.check("9.9.8", server.url)
		assertNull("a release without a checksum is refused, not installed blind", r!!.sha256)
	}

	@Test fun stalkerChannelOfflineLinkIsAnError() {
		server.on("/portal.php") { ex -> ex.reply(200, if (ex.query()["action"] == "handshake") """{"js":{"token":"T1"}}""" else """{"js":{"cmd":""}}""") }
		try { Stalker(stalker()).link("x"); fail() } catch (e: IOException) { assertEquals("Portal did not return a stream link", e.message) }
	}
}
