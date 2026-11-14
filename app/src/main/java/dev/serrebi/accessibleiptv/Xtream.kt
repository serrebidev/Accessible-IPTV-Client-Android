package dev.serrebi.accessibleiptv

import org.json.JSONArray
import org.json.JSONObject

/** Xtream Codes player_api client: live channels and account info. */
object Xtream {
	/**
	 * Server address as the API wants it. People paste whatever the panel gave them:
	 * "host:8080", ".../player_api.php", or a whole get.php M3U link. Query and endpoint go.
	 */
	fun normalizeServer(s: String): String {
		var t = s.trim().substringBefore('?').substringBefore('#').trimEnd('/')
		if (t.substringAfterLast('/').endsWith(".php", true)) t = t.substringBeforeLast('/')
		return withScheme(t)
	}

	/** Trimmed, no trailing slash, http:// added when no scheme was typed. Stalker keeps its endpoint path. */
	fun withScheme(s: String): String {
		val t = s.trim().trimEnd('/')
		return if (t.startsWith("http://", true) || t.startsWith("https://", true)) t else "http://$t"
	}

	private val streamId = Regex("""\d+(?:\.[A-Za-z0-9]+)?""")
	private val streamPrefixes = setOf("live", "movie", "series", "timeshift")

	/**
	 * (server, username, password) when [url] is an Xtream panel link: get.php / player_api.php
	 * with username and password, or a stream path /live/U/P/123.ts. Port of the desktop's
	 * xtream_credentials_from_url: a false match would show an account that does not exist.
	 */
	fun credentialsFromUrl(url: String): Triple<String, String, String>? {
		val u = runCatching { java.net.URI(url.trim().substringBefore('|')) }.getOrNull() ?: return null
		if (u.scheme?.lowercase() !in setOf("http", "https") || u.rawAuthority.isNullOrEmpty()) return null
		val root = "${u.scheme}://${u.rawAuthority}"
		val segs = u.rawPath.orEmpty().split('/').filter { it.isNotEmpty() }
		fun dec(s: String) = runCatching { java.net.URLDecoder.decode(s, "UTF-8") }.getOrDefault(s).trim()
		if (u.rawQuery != null && segs.lastOrNull()?.endsWith(".php", true) == true) {
			val q = u.rawQuery.split('&').associate { dec(it.substringBefore('=')) to dec(it.substringAfter('=', "")) }
			val user = q["username"].orEmpty()
			val pass = q["password"].orEmpty()
			if (user.isNotEmpty() && pass.isNotEmpty()) return Triple((listOf(root) + segs.dropLast(1)).joinToString("/"), user, pass)
		}
		if (segs.size >= 3 && streamId.matches(segs.last())) {
			val first = segs[0].lowercase()
			val offset = when {
				segs.size == 3 && first !in streamPrefixes -> 0
				segs.size == 4 && first in streamPrefixes -> 1
				segs.size == 6 && first == "timeshift" -> 1
				else -> return null
			}
			val user = dec(segs[offset])
			val pass = dec(segs[offset + 1])
			if (user.isNotEmpty() && pass.isNotEmpty()) return Triple(root, user, pass)
		}
		return null
	}

	/** Every server to try, primary first. Backup lines are one server per line. */
	fun servers(src: Source): List<String> = (listOf(src.url) + src.backupServers.lines().map { it.trim() }.filter { it.isNotEmpty() })
		.map { normalizeServer(it) }.distinct()

	/** Runs [block] against each server until one answers; the last error is thrown. */
	fun <T> withFailover(src: Source, block: (Source) -> T): T {
		var last: Exception? = null
		for (s in servers(src)) {
			try { return block(src.copy(url = s)) } catch (e: Exception) { last = e }
		}
		throw last ?: java.io.IOException("No server address")
	}

	private fun api(src: Source, action: String = "") =
		"${src.url}/player_api.php?username=${Source.enc(src.username)}&password=${Source.enc(src.password)}" +
			if (action.isEmpty()) "" else "&action=$action"

	fun liveChannels(src: Source): List<Channel> = withFailover(src) { liveChannelsOn(it) }

	fun liveChannelsOn(src: Source): List<Channel> {
		val cats = categories(src, "get_live_categories")
		val user = Source.enc(src.username)
		val pass = Source.enc(src.password)
		val out = ArrayList<Channel>()
		Net.forEachObject(api(src, "get_live_streams")) { o ->
			val id = o["stream_id"].orEmpty()
			val archive = o["tv_archive"] == "1"
			out += Channel(
				sourceId = src.id,
				id = id,
				name = o["name"].orEmpty(),
				group = cats[o["category_id"]] ?: "Ungrouped",
				// TS, not m3u8: some servers (Dispatcharr) answer m3u8 with HTTP 500.
				url = "${src.url}/live/$user/$pass/$id.ts",
				tvgId = o["epg_channel_id"]?.takeUnless { it == "null" }.orEmpty(),
				attrs = buildMap {
				if (archive) { put("catchup", "xc"); put("catchup-days", o["tv_archive_duration"].orEmpty()) }
				o["num"]?.takeIf { it.isNotBlank() && it != "null" }?.let { put(Channel.CHNO, it) }
			},
			)
		}
		return out
	}

	/** category_id -> name. The returned strings are shared by every item in the group. */
	private fun categories(src: Source, action: String): Map<String, String> {
		val out = HashMap<String, String>()
		Net.forEachObject(api(src, action)) { out[it["category_id"].orEmpty()] = it["category_name"].orEmpty() }
		return out
	}

	fun movies(src: Source): List<Channel> = withFailover(src) { moviesOn(it) }

	fun moviesOn(src: Source): List<Channel> {
		val cats = categories(src, "get_vod_categories")
		val user = Source.enc(src.username)
		val pass = Source.enc(src.password)
		val out = ArrayList<Channel>()
		Net.forEachObject(api(src, "get_vod_streams")) { o ->
			val id = o["stream_id"].orEmpty()
			val ext = o["container_extension"].orEmpty().ifEmpty { "mp4" }
			out += Channel(src.id, "m$id", o["name"].orEmpty(), cats[o["category_id"]] ?: "Ungrouped",
				"${src.url}/movie/$user/$pass/$id.$ext", kind = Channel.KIND_MOVIE)
		}
		return out
	}

	fun series(src: Source): List<Channel> = withFailover(src) { seriesOn(it) }

	fun seriesOn(src: Source): List<Channel> {
		val cats = categories(src, "get_series_categories")
		val out = ArrayList<Channel>()
		Net.forEachObject(api(src, "get_series")) { o ->
			// url holds the series id; episodes are fetched when the show is opened.
			val plot = o["plot"].orEmpty()
			out += Channel(src.id, "s${o["series_id"]}", o["name"].orEmpty(), cats[o["category_id"]] ?: "Ungrouped",
				o["series_id"].orEmpty(), attrs = if (plot.isBlank()) emptyMap() else mapOf("plot" to plot), kind = Channel.KIND_SERIES)
		}
		return out
	}

	/**
	 * Providers often title episodes "Show Name - S01E02"; after the season and episode
	 * are spoken, that is pure repetition. Keeps only a real episode title, else "".
	 */
	// Compiled once: each Regex holds native ICU memory until a GC finalizes it.
	private val yearSuffix = Regex("\\s*\\(\\d{4}\\)\\s*$")
	private val seasonEpisode = Regex("(?i)\\bS\\d{1,3}\\s*E\\d{1,4}\\b")

	fun episodeTitle(raw: String, show: String): String {
		var t = raw.trim()
		val showBase = show.replace(yearSuffix, "")
		for (prefix in listOf(show, showBase)) if (prefix.isNotEmpty() && t.startsWith(prefix, true)) t = t.substring(prefix.length)
		t = t.replace(seasonEpisode, "")
		return t.trim(' ', '-', ':', '.', '|', '_')
	}

	/** Episodes of a show, in season order, named "Season 1, Episode 2: Title". */
	fun episodes(src: Source, show: Channel): List<Channel> = withFailover(src) { parseEpisodes(it, show, Net.text(api(it, "get_series_info") + "&series_id=${show.url}")) }

	/** get_series_info body -> episodes. "episodes" is keyed by season, or (some panels) a list of seasons. */
	fun parseEpisodes(src: Source, show: Channel, body: String): List<Channel> {
		val seasons: List<Pair<String, JSONArray>> = when (val raw = JSONObject(body).opt("episodes")) {
			is JSONObject -> raw.keys().asSequence().sortedBy { it.toIntOrNull() ?: 0 }.mapNotNull { k -> raw.optJSONArray(k)?.let { k to it } }.toList()
			is JSONArray -> (0 until raw.length()).mapNotNull { i -> raw.optJSONArray(i)?.let { (i + 1).toString() to it } }
			else -> emptyList()
		}
		val user = Source.enc(src.username)
		val pass = Source.enc(src.password)
		val out = ArrayList<Channel>()
		for ((season, list) in seasons) {
			for (i in 0 until list.length()) {
				val e = list.optJSONObject(i) ?: continue
				if (e.optString("id").isEmpty()) continue
				val num = e.optString("episode_num").ifEmpty { (i + 1).toString() }
				val title = episodeTitle(e.optString("title"), show.name)
				val ext = e.optString("container_extension").ifEmpty { "mp4" }
				val label = if (title.isEmpty()) "Season $season, Episode $num" else "Season $season, Episode $num: $title"
				out += Channel(src.id, "e${e.optString("id")}", label, show.name,
					"${src.url}/series/$user/$pass/${e.optString("id")}.$ext",
					attrs = mapOf("plot" to (e.optJSONObject("info")?.optString("plot").orEmpty())), kind = Channel.KIND_EPISODE)
			}
		}
		return out
	}

	/** user_info from the server, as label/value lines ready to read out. */
	fun accountInfo(src: Source): List<Pair<String, String>> = withFailover(src) { parseAccount(Net.text(api(it))) }

	fun parseAccount(body: String): List<Pair<String, String>> {
		val u = JSONObject(body).optJSONObject("user_info") ?: return emptyList()
		val out = ArrayList<Pair<String, String>>()
		out += "Status" to u.optString("status")
		val exp = u.optString("exp_date").toLongOrNull()
		if (exp != null) {
			val days = Math.floorDiv(exp * 1000 - System.currentTimeMillis(), 86_400_000L)
			out += "Expires" to if (days < 0) "${Format.date(exp * 1000)}, expired" else "${Format.date(exp * 1000)}, $days days left"
		} else out += "Expires" to "Never"
		out += "Trial" to if (u.optString("is_trial") == "1") "Yes" else "No"
		out += "Connections in use" to "${u.optString("active_cons")} of ${u.optString("max_connections")}"
		u.optString("created_at").toLongOrNull()?.let { out += "Created" to Format.date(it * 1000) }
		return out
	}
}
