package dev.serrebi.accessibleiptv

import android.content.Context
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject

/** Settings, sources, favourites and remembered audio tracks, kept as plain JSON in prefs. */
class Prefs(context: Context) {
	companion object {
		const val DEFAULT_BUFFER = 25f
		const val DEFAULT_MAX_BUFFER = 30f
	}

	private val sp = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

	var sources: List<Source>
		get() = JSONArray(sp.getString("sources", "[]")).let { a -> List(a.length()) { Source.fromJson(a.getJSONObject(it)) } }
		set(v) = sp.edit { putString("sources", JSONArray(v.map { it.toJson() }).toString()) }

	var favorites: Set<String>
		get() = sp.getStringSet("favorites", emptySet())!!.toSet()
		set(v) = sp.edit { putStringSet("favorites", v) }

	@Synchronized fun toggleFavorite(key: String): Boolean {
		val now = key !in favorites
		favorites = if (now) favorites + key else favorites - key
		return now
	}

	/** Saves a source; one place, so a background fetch cannot lose an edit made meanwhile. */
	@Synchronized fun upsertSource(src: Source) { sources = sources.filter { it.id != src.id } + src }

	@Synchronized fun removeSource(id: String) { sources = sources.filter { it.id != id } }

	@Synchronized fun setSourceEpgUrl(id: String, url: String) { sources = sources.map { if (it.id == id) it.copy(epgUrl = url) else it } }

	/** Moves favourites, remembered tracks and recent keys after the channel ids changed. */
	@Synchronized fun remapChannelKeys(moved: Map<String, String>) {
		if (moved.isEmpty()) return
		favorites = favorites.mapTo(HashSet()) { moved[it] ?: it }
		recent = recent.map { moved[it] ?: it }
		val t = tracks
		if (t.length() > 0) {
			val out = JSONObject()
			for (k in t.keys()) out.put(moved[k] ?: k, t.getString(k))
			sp.edit { putString("channelTracks", out.toString()) }
		}
	}

	/** Drops a deleted playlist's favourites, remembered tracks and recent keys. */
	@Synchronized fun dropChannelKeys(prefix: String) {
		favorites = favorites.filterNot { it.startsWith(prefix) }.toSet()
		recent = recent.filterNot { it.startsWith(prefix) }
		val t = tracks
		if (t.length() > 0) {
			val out = JSONObject()
			for (k in t.keys()) if (!k.startsWith(prefix)) out.put(k, t.getString(k))
			sp.edit { putString("channelTracks", out.toString()) }
		}
	}

	var preferAudioDescription: Boolean
		get() = sp.getBoolean("preferAd", true)
		set(v) = sp.edit { putBoolean("preferAd", v) }

	/** Comma-separated track names or languages, most wanted first. */
	var audioKeywords: String
		get() = sp.getString("audioKeywords", "")!!
		set(v) = sp.edit { putString("audioKeywords", v) }

	var useExternalPlayer: Boolean
		get() = sp.getBoolean("externalPlayer", false)
		set(v) = sp.edit { putBoolean("externalPlayer", v) }

	/** Desktop's "Show Stream URL": adds the URL to channel details. */
	var showStreamUrl: Boolean
		get() = sp.getBoolean("showStreamUrl", false)
		set(v) = sp.edit { putBoolean("showStreamUrl", v) }

	/** Live cushion built during playback; first start needs only half a second. */
	// Historical defaults migrate; other explicit settings are kept for every provider.
	var bufferSeconds: Double
		get() = sp.getFloat("bufferSeconds", DEFAULT_BUFFER).toDouble().let { if (it == 6.0 || it == 10.0) DEFAULT_BUFFER.toDouble() else it }
		set(v) = sp.edit { putFloat("bufferSeconds", v.toFloat()) }

	/** Desktop internal_player_max_buffer_seconds: ceiling for the +1 s per stall. */
	var maxBufferSeconds: Double
		get() = sp.getFloat("maxBufferSeconds", DEFAULT_MAX_BUFFER).toDouble()
			.let { if (it == 18.0) DEFAULT_MAX_BUFFER.toDouble() else it }.coerceAtLeast(bufferSeconds)
		set(v) = sp.edit { putFloat("maxBufferSeconds", v.toFloat()) }

	var epgDaysAhead: Int
		get() = sp.getInt("epgDaysAhead", 2)
		set(v) = sp.edit { putInt("epgDaysAhead", v) }

	/** Which channel set the guide was built for; see [App.guideStale]. */
	var guideFingerprint: Int
		get() = sp.getInt("guideFingerprint", 0)
		set(v) = sp.edit { putInt("guideFingerprint", v) }

	var lastUpdateCheck: Long
		get() = sp.getLong("lastUpdateCheck", 0)
		set(v) = sp.edit { putLong("lastUpdateCheck", v) }

	var lastEpgImport: Long
		get() = sp.getLong("lastEpgImport", 0)
		set(v) = sp.edit { putLong("lastEpgImport", v) }

	var lastGroup: String
		get() = sp.getString("lastGroup", "")!!
		set(v) = sp.edit { putString("lastGroup", v) }

	/** Minutes a scheduled recording starts early and runs late (desktop: 0 and 2). */
	var prePadMinutes: Int
		get() = sp.getInt("prePad", 0)
		set(v) = sp.edit { putInt("prePad", v) }

	var postPadMinutes: Int
		get() = sp.getInt("postPad", 2)
		set(v) = sp.edit { putInt("postPad", v) }

	/** Desktop "Automatic Announcements": 0 none, 1 errors only, 2 important events, 3 detailed status. */
	var announceLevel: Int
		get() = sp.getInt("announceLevel", 2)
		set(v) = sp.edit { putInt("announceLevel", v) }

	/** Subtitle text is spoken as it appears (a polite live region), like the desktop's subtitle speech. */
	var speakSubtitles: Boolean
		get() = sp.getBoolean("speakSubtitles", true)
		set(v) = sp.edit { putBoolean("speakSubtitles", v) }

	/** Live channels played, newest first; [0] is the one on now, [1] the previous channel. */
	var recent: List<String>
		get() = JSONArray(sp.getString("recent", "[]")).let { a -> List(a.length()) { a.getString(it) } }
		set(v) = sp.edit { putString("recent", JSONArray(v).toString()) }

	fun addRecent(key: String) {
		recent = (listOf(key) + recent.filter { it != key }).take(20)
	}

	/** Every setting, source and favourite as JSON, for Export settings. Typed so Import restores ints as ints. */
	fun export(): JSONObject {
		val out = JSONObject()
		for ((k, v) in sp.all) out.put(k, when (v) {
			is String -> JSONObject().put("s", v)
			is Boolean -> JSONObject().put("b", v)
			is Int -> JSONObject().put("i", v)
			is Long -> JSONObject().put("l", v)
			is Float -> JSONObject().put("f", v.toDouble())
			is Set<*> -> JSONObject().put("set", JSONArray(v.toList()))
			else -> continue
		})
		return JSONObject().put("app", "AccessibleIPTV-Android").put("settings", out)
	}

	/** Replaces every setting with an [export]. Throws on a file that is not one. */
	fun import(o: JSONObject) {
		require(o.optString("app") == "AccessibleIPTV-Android") { "Not a settings file" }
		val all = o.getJSONObject("settings")
		// Validate before committing: wrong types or broken JSON can otherwise prevent every future launch.
		for (k in all.keys()) {
			val v = all.getJSONObject(k)
			val type = when (k) {
				"sources", "recent", "channelTracks", "audioKeywords", "lastGroup", "resumePositions" -> "s"
				"preferAd", "externalPlayer", "showStreamUrl", "speakSubtitles" -> "b"
				"bufferSeconds", "maxBufferSeconds" -> "f"
				"epgDaysAhead", "guideFingerprint", "prePad", "postPad", "announceLevel" -> "i"
				"lastUpdateCheck", "lastEpgImport" -> "l"
				"favorites" -> "set"
				else -> v.keys().asSequence().singleOrNull()
			}
			require(v.length() == 1 && type in setOf("s", "b", "f", "i", "l", "set") && v.has(type!!)) { "Invalid setting: $k" }
			when (k) {
				"sources" -> JSONArray(v.getString("s")).let { a -> repeat(a.length()) { Source.fromJson(a.getJSONObject(it)) } }
				"recent" -> JSONArray(v.getString("s")).let { a -> repeat(a.length()) { a.getString(it) } }
				"channelTracks" -> JSONObject(v.getString("s"))
				"bufferSeconds", "maxBufferSeconds" -> require(v.getDouble("f").isFinite() && v.getDouble("f") > 0 && v.getDouble("f") <= 300) { "Invalid buffer" }
				"epgDaysAhead" -> require(v.getInt("i") in 1..14) { "Invalid guide window" }
				"prePad", "postPad" -> require(v.getInt("i") in 0..180) { "Invalid recording padding" }
				"announceLevel" -> require(v.getInt("i") in 0..3) { "Invalid announcement level" }
			}
		}
		sp.edit {
			clear()
			for (k in all.keys()) {
				val v = all.getJSONObject(k)
				when {
					v.has("s") -> putString(k, v.getString("s"))
					v.has("b") -> putBoolean(k, v.getBoolean("b"))
					v.has("i") -> putInt(k, v.getInt("i"))
					v.has("l") -> putLong(k, v.getLong("l"))
					v.has("f") -> putFloat(k, v.getDouble("f").toFloat())
					v.has("set") -> v.getJSONArray("set").let { a -> putStringSet(k, (0 until a.length()).mapTo(HashSet()) { a.getString(it) }) }
				}
			}
		}
	}

	/** Where a film, episode or catch-up was left; newest first, capped at 50. */
	var resume: List<Pair<String, Long>>
		get() {
			val o = JSONObject(sp.getString("resumePositions", "{}")!!)
			return o.keys().asSequence().mapNotNull { k ->
				val e = o.optJSONObject(k) ?: return@mapNotNull null
				val p = e.optLong("p"); val t = e.optLong("t")
				if (p > 0) k to p else null
			}.sortedByDescending { o.optJSONObject(it.first)?.optLong("t") ?: 0L }.toList()
		}
		set(v) {
			val o = JSONObject(); val now = System.currentTimeMillis()
			v.take(50).forEachIndexed { i, (k, p) -> o.put(k, JSONObject().put("p", p).put("t", now - i)) }
			sp.edit { putString("resumePositions", o.toString()) }
		}

	/** Newest resume entries with their saved order kept; [save] writes one or clears it at the end. */
	fun rememberResume(key: String, positionMs: Long) {
		if (key.isEmpty() || positionMs < 5_000) return
		val rest = resume.filterNot { it.first == key }
		resume = listOf(key to positionMs) + rest
	}

	fun clearResume(key: String) { resume = resume.filterNot { it.first == key } }

	private val tracks get() = JSONObject(sp.getString("channelTracks", "{}")!!)

	/** Track name picked by hand on a channel; comes back ahead of every other rule. */
	fun rememberedTrack(channelKey: String): String? = tracks.optString(channelKey).ifEmpty { null }

	fun rememberTrack(channelKey: String, name: String) =
		sp.edit { putString("channelTracks", tracks.put(channelKey, name).toString()) }
}
