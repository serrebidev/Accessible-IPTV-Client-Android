package dev.serrebi.accessibleiptv

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.EditText
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import java.util.UUID

/** Playlist Manager: Xtream Codes, Stalker portals, M3U URLs and M3U files, each with an optional guide URL. */
class SourcesActivity : ListScreen() {
	private lateinit var adapter: RowAdapter<Source>

	private val pickFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { addFile(it) } }

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		title = getString(R.string.playlists_and_guides)
		addButton(getString(R.string.add_xtream)) { edit(null, Source.TYPE_XTREAM) }
		addButton(getString(R.string.add_m3u)) { edit(null, Source.TYPE_M3U) }
		addButton(getString(R.string.add_m3u_file)) { pickFile.launch(arrayOf("*/*")) }
		addButton(getString(R.string.add_stalker)) { edit(null, Source.TYPE_STALKER) }
		adapter = RowAdapter { s, row, l1, l2 ->
			l1.text = s.name
			l2.text = when {
				s.isXtream -> getString(R.string.xtream_at, s.url)
				s.isStalker -> getString(R.string.stalker_at, s.url)
				s.url.startsWith("content://") -> getString(R.string.m3u_file)
				else -> getString(R.string.m3u_playlist)
			}
			bindRow(row, "${s.name}, ${l2.text}", { edit(s, s.type) }, listOf(
				RowAction(getString(R.string.edit)) { edit(s, s.type) },
				RowAction(getString(R.string.delete)) { confirmDelete(s) },
			))
		}
		list.adapter = adapter
		refresh()
	}

	private fun refresh() {
		val sources = app.prefs.sources
		adapter.submit(sources)
		heading.text = resources.getQuantityString(R.plurals.source_count, sources.size, sources.size)
		if (sources.isEmpty()) status.status(getString(R.string.no_sources))
	}

	private fun addFile(uri: Uri) {
		// Keep read access across restarts so refresh can re-read the file.
		runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
		val name = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
			if (c.moveToFirst()) c.getString(0) else null
		} ?: getString(R.string.m3u_file)
		save(Source(UUID.randomUUID().toString(), Source.TYPE_M3U, name.substringBeforeLast('.'), uri.toString()))
	}

	private fun save(src: Source) {
		val old = app.source(src.id)
		app.prefs.upsertSource(src)
		app.forgetCache(src)
		// A new address or guide URL needs a fresh import; a rename must not re-download 100 MB.
		if (old == null || old.url != src.url || old.epgUrl != src.epgUrl) app.prefs.lastEpgImport = 0
		changed = true
		refresh()
		status.status(getString(R.string.source_saved, src.name))
	}

	private fun edit(existing: Source?, type: String) {
		val xtream = type == Source.TYPE_XTREAM
		val stalker = type == Source.TYPE_STALKER
		val file = existing?.url?.startsWith("content://") == true
		val v = layoutInflater.inflate(R.layout.dialog_source, null)
		val name = v.findViewById<EditText>(R.id.name)
		val url = v.findViewById<EditText>(R.id.url)
		val mac = v.findViewById<EditText>(R.id.mac)
		val user = v.findViewById<EditText>(R.id.user)
		val pass = v.findViewById<EditText>(R.id.pass)
		val epg = v.findViewById<EditText>(R.id.epg)
		val backups = v.findViewById<EditText>(R.id.backups)
		fun hide(vararg ids: Int) = ids.forEach { v.findViewById<View>(it).visibility = View.GONE }
		when {
			xtream -> { v.findViewById<TextView>(R.id.urlLabel).setText(R.string.field_server); hide(R.id.macLabel, R.id.mac) }
			stalker -> v.findViewById<TextView>(R.id.urlLabel).setText(R.string.field_portal)
			file -> hide(R.id.urlLabel, R.id.url, R.id.macLabel, R.id.mac, R.id.userLabel, R.id.user, R.id.passLabel, R.id.pass)
			else -> hide(R.id.macLabel, R.id.mac, R.id.userLabel, R.id.user, R.id.passLabel, R.id.pass)
		}
		if (!xtream) hide(R.id.backupLabel, R.id.backups)
		existing?.let { name.setText(it.name); url.setText(it.url); mac.setText(it.mac); user.setText(it.username); pass.setText(it.password); epg.setText(it.epgUrl); backups.setText(it.backupServers) }

		val dialog = AlertDialog.Builder(this)
			.setTitle(when { existing != null -> R.string.edit_source; xtream -> R.string.add_xtream; stalker -> R.string.add_stalker; else -> R.string.add_m3u })
			.setView(v)
			.setPositiveButton(R.string.save, null)
			.setNegativeButton(android.R.string.cancel, null)
			.create()
		dialog.setOnShowListener {
			dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
				// A whole panel link pasted as the server carries the login: fill it in.
				if (xtream && user.text.isBlank()) Xtream.credentialsFromUrl(url.text.toString())?.let { (s, u, p) ->
					url.setText(s); user.setText(u); pass.setText(p)
				}
				val macOk = !stalker || Regex("^([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}$").matches(mac.text.trim())
				val missing = listOfNotNull(
					url.takeIf { !file && it.text.isBlank() },
					mac.takeIf { !macOk },
					user.takeIf { xtream && it.text.isBlank() },
					pass.takeIf { xtream && it.text.isBlank() },
				).firstOrNull()
				if (missing != null) {
					// Error text on the field, and the reader is moved there to hear it.
					missing.error = getString(if (missing == mac) R.string.mac_invalid else R.string.field_required)
					missing.requestFocus()
					missing.performAccessibilityAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null)
					return@setOnClickListener
				}
				val typed = url.text.toString().trim()
				val server = when {
					xtream -> Xtream.normalizeServer(typed)
					stalker -> Xtream.withScheme(typed)
					// "example.com/list.m3u" failed with "no protocol".
					!file && !typed.contains("://") -> "http://$typed"
					else -> typed
				}
				save(Source(
					id = existing?.id ?: UUID.randomUUID().toString(),
					type = type,
					name = name.text.toString().trim().ifEmpty { runCatching { Uri.parse(server).host }.getOrNull() ?: server },
					url = server,
					username = user.text.toString().trim(),
					password = pass.text.toString(),
					epgUrl = epg.text.toString().trim(),
					mac = mac.text.toString().trim().uppercase(),
					backupServers = if (xtream) backups.text.toString().trim() else "",
				))
				dialog.dismiss()
			}
		}
		dialog.show()
	}

	private fun confirmDelete(s: Source) {
		AlertDialog.Builder(this).setTitle(R.string.delete).setMessage(getString(R.string.delete_source_q, s.name))
			.setPositiveButton(R.string.delete) { _, _ ->
				app.prefs.removeSource(s.id)
				app.forgetCache(s)
				// A deleted playlist must not leave favourites, track choices or alarms behind.
				app.prefs.dropChannelKeys("${s.id}:")
				Recorder.dropSource(this, s.id)
				changed = true
				refresh()
				status.status(getString(R.string.source_deleted, s.name))
			}
			.setNegativeButton(android.R.string.cancel, null).show()
	}

	companion object {
		/** Set when sources change so the main screen reloads on return. */
		@Volatile var changed = false
	}
}
