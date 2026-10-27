package dev.serrebi.accessibleiptv

import android.os.Bundle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Pick a channel, then read its guide: the Guide section's way in on iOS, so a channel's
 * guide is two keypresses away instead of a hunt through the groups first.
 */
class ChannelGuideActivity : ListScreen() {
	private lateinit var adapter: RowAdapter<Channel>
	private var all: List<Channel> = emptyList()
	private var searchJob: Job? = null

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		title = getString(R.string.channel_guide)
		adapter = RowAdapter { ch, row, l1, l2 ->
			l1.text = ch.name
			val line = nowLine(ch)
			l2.text = line
			bindRow(row, Format.sentences(listOf(ch.name, line)), { startActivity(ProgrammesActivity.forChannel(this, ch)) }, emptyList())
		}
		list.adapter = adapter
		// Filtering a big playlist is a sort: keep it off the main thread and debounced.
		enableFilter { q ->
			searchJob?.cancel()
			searchJob = lifecycleScope.launch {
				delay(300)
				present(withContext(Dispatchers.Default) { if (q.isEmpty()) all else App.searchRanked(all, q) })
			}
		}
		status.status(getString(R.string.loading))
		lifecycleScope.launch {
			// After Android killed the app, the restored screen has to load the channel list again.
			if (!app.isLoaded(App.LIVE)) app.load(App.LIVE, force = false)
			all = app.channels
			show("")
			// Silence after "Loading" reads like a failure: say how many channels arrived.
			status.status(if (all.isEmpty()) getString(R.string.no_sources) else resources.getQuantityString(R.plurals.channel_count, all.size, all.size))
		}
	}

	override fun onResume() {
		super.onResume()
		Net.warmUp(all.map { it.url })
		// "On now, until when" lines go stale while the screen is away.
		if (this::adapter.isInitialized) adapter.notifyDataSetChanged()
	}

	/** What a channel is showing now, or nothing when it has no guide. */
	private fun nowLine(ch: Channel) =
		app.epg.nowNext(ch).now?.let { getString(R.string.until, it.title, Format.time(it.stop)) }.orEmpty()

	private fun show(q: String) = present(if (q.isEmpty()) all else App.searchRanked(all, q))

	private fun present(shown: List<Channel>) {
		adapter.submit(shown)
		heading.text = resources.getQuantityString(R.plurals.channel_count, shown.size, shown.size)
	}
}
