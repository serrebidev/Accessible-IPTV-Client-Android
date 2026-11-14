package dev.serrebi.accessibleiptv

import org.json.JSONObject

/** A playlist source. M3U uses [url]; Xtream uses [url] as the server address. */
data class Source(
	val id: String,
	val type: String, // "m3u", "xtream" or "stalker"
	val name: String,
	val url: String,
	val username: String = "",
	val password: String = "",
	val epgUrl: String = "",
	val mac: String = "",
	val backupServers: String = "",
) {
	val isXtream get() = type == TYPE_XTREAM
	val isStalker get() = type == TYPE_STALKER

	/** The guide to import: the user's own, else the one Xtream servers publish. */
	val effectiveEpgUrl: String
		get() = epgUrl.ifBlank {
			when {
				isXtream -> "${url.trimEnd('/')}/xmltv.php?username=${enc(username)}&password=${enc(password)}"
				isStalker -> Stalker.baseUrl(url) + "/xmltv.php"
				else -> ""
			}
		}

	fun toJson(): JSONObject = JSONObject()
		.put("id", id).put("type", type).put("name", name).put("url", url)
		.put("username", username).put("password", password).put("epgUrl", epgUrl).put("mac", mac).put("backupServers", backupServers)

	companion object {
		const val TYPE_M3U = "m3u"
		const val TYPE_XTREAM = "xtream"
		const val TYPE_STALKER = "stalker"

		fun fromJson(o: JSONObject) = Source(
			o.getString("id"), o.getString("type"), o.optString("name"), o.optString("url"),
			o.optString("username"), o.optString("password"), o.optString("epgUrl"), o.optString("mac"), o.optString("backupServers"),
		)

		fun enc(s: String): String = java.net.URLEncoder.encode(s, "UTF-8")
	}
}

/**
 * One live channel. [attrs] keeps the M3U attributes the catch-up rules need
 * (catchup, catchup-source, catchup-days, timeshift, catchup-offset, catchup-timezone).
 */
data class Channel(
	val sourceId: String,
	val id: String,
	val name: String,
	val group: String,
	val url: String,
	val tvgId: String = "",
	val logo: String = "",
	val attrs: Map<String, String> = emptyMap(),
	val kind: String = KIND_LIVE,
) {
	val key get() = "$sourceId:$id"
	val isLive get() = kind == KIND_LIVE

	/** Stalker links are issued per play; [App.resolveUrl] turns this into a real URL. */
	val needsResolve get() = url.startsWith(STALKER_SCHEME)

	val catchupDays: Int
		get() = (attrs["catchup-days"] ?: attrs["timeshift"])?.toDoubleOrNull()?.toInt() ?: 0

	/** The provider's channel number (M3U tvg-chno, Xtream num, Stalker number), or "". */
	val number: String get() = attrs[CHNO].orEmpty()

	val hasCatchup get() =!attrs["catchup"].isNullOrBlank() || !attrs["catchup-source"].isNullOrBlank() || catchupDays > 0

	/** Dispatcharr marks its XC playlist with this header attribute, and buffers server-side. */
	val isDispatcharr get() = !attrs["catchup-timezone"].isNullOrBlank()

	fun toJson(): JSONObject {
		val o = JSONObject().put("s", sourceId).put("i", id).put("n", name).put("g", group).put("u", url).put("k", kind)
		if (tvgId.isNotEmpty()) o.put("t", tvgId)
		if (logo.isNotEmpty()) o.put("l", logo)
		if (attrs.isNotEmpty()) o.put("a", JSONObject(attrs))
		return o
	}

	companion object {
		fun fromJson(o: JSONObject): Channel {
			val a = o.optJSONObject("a")
			val attrs = buildMap { a?.keys()?.forEach { put(it, a.getString(it)) } }
			return Channel(
				o.getString("s"), o.getString("i"), o.getString("n"), o.optString("g"),
				o.getString("u"), o.optString("t"), o.optString("l"), attrs, o.optString("k", KIND_LIVE),
			)
		}

		const val KIND_LIVE = "live"
		const val KIND_MOVIE = "movie"
		const val KIND_SERIES = "series" // a show; its episodes come from the provider on demand
		const val KIND_EPISODE = "episode"
		const val STALKER_SCHEME = "stalker:"
		const val CHNO = "tvg-chno"

		/** "Go to channel number": the provider's number first, else the [n]th entry, like the desktop. */
		fun byNumber(list: List<Channel>, n: String): Channel? {
			val num = n.trim().toIntOrNull()?.takeIf { it > 0 } ?: return null
			return list.firstOrNull { it.number.toIntOrNull() == num } ?: list.getOrNull(num - 1)
		}
	}
}

/** A guide entry. Times are epoch milliseconds, UTC. */
data class Programme(
	val channelKey: String,
	val start: Long,
	val stop: Long,
	val title: String,
	val description: String = "",
	val channelName: String = "",
)
