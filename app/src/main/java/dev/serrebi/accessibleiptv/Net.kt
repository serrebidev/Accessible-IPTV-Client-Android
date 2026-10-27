package dev.serrebi.accessibleiptv

import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

object Net {
	const val USER_AGENT = "AccessibleIPTV-Android/0.1"

	private val liveClient = okhttp3.OkHttpClient.Builder()
		.connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
		.readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
		// The shared channel owns retries, and the provider permits only one connection.
		.retryOnConnectionFailure(false).fastFallback(false).build()

	/** Stream hosts worth a warm connection, capped: the first ones seen win. */
	fun warmHosts(urls: List<String>, max: Int = 2): List<okhttp3.HttpUrl> {
		val hosts = LinkedHashMap<String, okhttp3.HttpUrl>()
		for (raw in urls) {
			// A playlist entry can carry its headers behind a pipe; they are not part of the address.
			val u = M3uParser.splitHeaders(raw).first.toHttpUrlOrNull() ?: continue
			val key = "${u.scheme}://${u.host}:${u.port}"
			if (key !in hosts) hosts[key] = okhttp3.HttpUrl.Builder().scheme(u.scheme).host(u.host).port(u.port).build()
			if (hosts.size >= max) break
		}
		return hosts.values.toList()
	}

	/**
	 * Warms one idle connection per stream host on the live client, so picking a channel
	 * does not pay DNS + TCP + TLS before the first byte: a live socket is cancelled when
	 * its channel closes, so the pool is always cold at pick time. The request goes to the
	 * host's root — it opens no channel and no provider stream — and its answer is dropped.
	 */
	fun warmUp(urls: List<String>) {
		for (root in warmHosts(urls)) {
			liveClient.newCall(okhttp3.Request.Builder().url(root).header("User-Agent", USER_AGENT).build())
				.enqueue(object : okhttp3.Callback {
					override fun onFailure(call: okhttp3.Call, e: IOException) = Unit
					override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) = response.close()
				})
		}
	}

	/** A live request whose cancellation also prevents a not-yet-executed call. */
	class Call(private val url: String, private val headers: Map<String, String>) : AutoCloseable {
		private var cancelled = false
		private var call: okhttp3.Call? = null

		fun open(): InputStream {
			val request = okhttp3.Request.Builder().url(url).header("User-Agent", USER_AGENT)
			headers.forEach { (k, v) -> request.header(k, v) }
			val next = liveClient.newCall(request.build())
			synchronized(this) {
				if (cancelled) throw IOException("Stream closed")
				call = next
			}
			val response = next.execute()
			try {
				if (!response.isSuccessful) throw IOException("Server answered ${response.code}")
				return unwrap(response.body.byteStream())
			} catch (e: Exception) {
				response.close()
				throw e
			}
		}

		override fun close() {
			val active = synchronized(this) { cancelled = true; call.also { call = null } }
			active?.cancel()
		}
	}

	private fun unwrap(raw: InputStream): InputStream {
		val input = BufferedInputStream(raw, 64 * 1024)
		input.mark(2)
		val gz = input.read() == 0x1f && input.read() == 0x8b
		input.reset()
		return if (gz) BufferedInputStream(GZIPInputStream(input, 64 * 1024), 64 * 1024) else input
	}

	/** Opens [url] following redirects; gzip bodies are unwrapped by sniffing the magic bytes. */
	fun open(url: String, headers: Map<String, String> = emptyMap(), onResponse: ((HttpURLConnection) -> Unit)? = null): InputStream {
		var current = url
		repeat(5) {
			val c = URL(current).openConnection() as HttpURLConnection
			c.connectTimeout = 30_000
			c.readTimeout = 60_000
			c.instanceFollowRedirects = true
			c.setRequestProperty("User-Agent", USER_AGENT)
			headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
			val code = c.responseCode
			if (code in 300..399) { // http <-> https hops are not followed automatically.
				current = URL(URL(current), c.getHeaderField("Location") ?: throw IOException("Redirect without location")).toString()
				c.disconnect()
				return@repeat
			}
			if (code !in 200..299) { c.disconnect(); throw IOException("Server answered $code") }
			onResponse?.invoke(c)
			return unwrap(c.inputStream)
		}
		throw IOException("Too many redirects")
	}

	/** The whole body as text; a playlist that names its encoding in Content-Type is read in it. */
	fun text(url: String): String {
		var charset = Charsets.UTF_8
		return open(url, onResponse = { c ->
			Regex("charset\\s*=\\s*\"?([^;\"\\s]+)", RegexOption.IGNORE_CASE).find(c.contentType.orEmpty())
				?.groupValues?.get(1)?.let { name -> runCatching { charset = java.nio.charset.Charset.forName(name) } }
		}).use { it.readBytes().toString(charset) }
	}

	/**
	 * Streams a JSON array of objects, handing each object's flat fields to [each] as strings.
	 * Xtream VOD lists run past 100 MB; reading them whole runs a phone out of memory.
	 * Nested values are skipped. A non-array body is an error, not an empty playlist.
	 */
	fun forEachObject(url: String, each: (Map<String, String>) -> Unit) = open(url).use { input ->
		val r = android.util.JsonReader(java.io.InputStreamReader(input, Charsets.UTF_8))
		if (r.peek() != android.util.JsonToken.BEGIN_ARRAY) throw IOException("Provider did not return a channel list")
		r.beginArray()
		val fields = HashMap<String, String>()
		while (r.hasNext()) {
			if (r.peek() != android.util.JsonToken.BEGIN_OBJECT) { r.skipValue(); continue }
			fields.clear()
			r.beginObject()
			while (r.hasNext()) {
				val name = r.nextName()
				when (r.peek()) {
					android.util.JsonToken.STRING, android.util.JsonToken.NUMBER -> fields[name] = r.nextString()
					android.util.JsonToken.BOOLEAN -> fields[name] = r.nextBoolean().toString()
					else -> r.skipValue()
				}
			}
			r.endObject()
			each(fields)
		}
		r.endArray()
	}
}
