# Accessible IPTV Client for Android - agent notes

Android port of the desktop Accessible IPTV Client (serrebidev/Accessible-IPTV-Client).
One APK for phones, tablets and Android TV, Android 10 (API 29) and up.

## Build

- `gradlew testDebugUnitTest assembleDebug` - JVM tests + debug APK at `app/build/outputs/apk/debug/`.
  Run it before every release; all tests must pass.
- Tests (`app/src/test`): `ParsingTest`/`CoreTest`/`HlsTest` pure JVM; `ProviderTest`, `GuideTest`, `AppTest`, `ScreenTest`,
  `SingleConnectionTest`, `LiveChannelTest` run under Robolectric (SDK 36, `robolectric.properties`) against `FakeServer`
  (JDK `HttpServer` on 127.0.0.1).
  JDK 21 needs `--add-opens=java.base/jdk.internal.access=ALL-UNNAMED` (set in `testOptions`).
  The stream gate is process-wide: a test that builds a player must destroy it (its `@After` does), or the next test is refused.
  A new provider quirk or user-visible bug gets a test here first.
- `lintDebug` passes: Media3 APIs opt in through `androidx.annotation.OptIn`. Any lint error is new.
- AGP 9.4.1 (built-in Kotlin), Kotlin 2.4.20, Gradle 9.8.0, JDK 21, compile/target SDK 37, min SDK 29.
- `local.properties` needs `sdk.dir` with forward slashes (`C\:/...`); backslashes break Gradle with
  "filename, directory name, or volume label syntax is incorrect".
- Release is signed with the owner's debug keystore (same key as Beluga, cert SHA-256 e686d194...10ca) so in-app updates install over each other. A build machine without that key makes APKs that cannot update installed copies.
- Updates: `Updater.kt` reads GitHub Releases of serrebidev/Accessible-IPTV-Client-Android, takes the .apk asset, requires the published sha256 digest (a release without one is refused), installs via PackageInstaller. Tag = `v` + versionName.

## Accessibility rules (non-negotiable)

The user is blind (TalkBack / Backtalk on Android, NVDA on desktop). Follow
cvs-health/android-view-accessibility-techniques.

- Android Views only, no Compose, no custom-drawn widgets.
- Form fields: visible TextView label with `android:labelFor`. A lone search/filter field uses its hint ("Search", "Filter") as its name, no extra label. Errors: `EditText.error` + move accessibility focus to it.
- Wording is blunt: one or two words on buttons, short status lines, no explanations in the UI. Explanations go in Help.
- List rows are ONE focus stop (`item_row.xml`, children `importantForAccessibility="no"`), speech set in code.
  Secondary actions go through `bindRow()` in `Ui.kt`: TalkBack custom actions + long-press/Menu-key popup. Never add per-row buttons.
- Section titles use `style="@style/Heading"` / `.heading()` so heading navigation works.
- Status changes go to a `accessibilityLiveRegion="polite"` status TextView; only milestones, no per-keystroke churn.
- Real buttons, always visible; no auto-hiding player overlay, no icon-only controls.
- D-pad: everything focusable, `row_bg.xml` draws a high-contrast focus ring for TV.

## Layout

- `App.kt` - process state: channel list (cached per source as JSON in filesDir, written temp+rename), guide.
  An empty fetch never overwrites a cache that has channels; a damaged cache is fetched again. When channel
  ids change, favourites, remembered tracks, recent and schedules move by stream URL (`migrateKeys`).
- `Epg.kt` - SQLite guide. Import streams XMLTV (gzip sniffed), keeps only guide channels a playlist channel matches (tvg-id, then `Xmltv.nameKey`), within the catch-up window to `epgDaysAhead`.
- `Xtream.kt` - player_api. Live URLs use `.ts`: Dispatcharr answers `.m3u8` with HTTP 500.
- `Catchup.kt`, `AudioTracks.kt`, `M3uParser.kt`, `Xmltv.kt` - pure Kotlin ports of desktop rules; covered by `ParsingTest`.
- M3U channel id = `tvg-id|name` (+`#n` for repeats), never list position: favourites, remembered tracks and schedules key on it.
  `#EXTVLCOPT`/`http-user-agent` headers ride in the URL's `|Key=Value` tail; `x-tvg-url` fills a blank guide URL.
- `Recorder.kt` - specialUse foreground service writes `.ts` to MediaStore `Movies/AccessibleIPTV`; reconnects drops until the end time.
  Live TS recordings ride the shared `LiveChannel` tap (so recording what you watch opens no second connection);
  `.m3u8` downloads go through `Hls.download` (one request at a time), and a download takes the stream exclusively.
  Not dataSync: Android 15 stops dataSync after 6 h a day. `USE_EXACT_ALARM` (sideloaded, no Play policy) keeps exact alarms on
  Android 14+, where `SCHEDULE_EXACT_ALARM` is denied by default; without an exact alarm the background service start is refused.
  The copy loop holds a partial wake lock (screen off starves it otherwise); a schedule that cannot start stays listed and notifies.
- One provider stream per device (`LiveChannel.kt`): `Streams` is the gate every socket takes — `live:<key>` (a live
  channel's tap or a live HLS recording), `hls:<key>` (HLS playback), `url:<url>` (VOD, catch-up), `dl:<url>` (a download).
  `LiveChannel` owns one live TS socket and fans it out through `Tap` (8 MB, drop-oldest; a reconnect clears it so readers
  continue at the live edge); the player and a recording of the same channel are two readers of that one socket.
  A second, different stream is refused with `stream_busy` — check `Player.freeFor`/`Recorder.canStart` before starting.
  HLS lives cannot share, so recording one is refused while it is watched.
- Guide import builds `prog_new`/`gch_new` and swaps on success: a failed download keeps the old guide.
  An import with no guide URL, or none with channels to match, does nothing at all (it used to swap in empty tables and wipe the guide); a
  finished import counts as fresh for a day even when nothing matched, so a huge guide is not re-fetched on
  every load (`Epg.hasGuide` is false then — do not gate staleness on it).
  The guide DB is WAL and `import` locks a private object, never the helper: channel rows read now/next on the
  main thread during an import (0.2.10 ANR'd when Recently watched redrew the list mid-download).
- Help text is `res/raw/help.md`, not a string resource: aapt folds string newlines into spaces.

- The guide screens match the iOS client's guide (`Accessible-IPTV-Client-iOS`, App/ProgrammesView.swift):
  programmes are grouped under a day heading (an accessibility heading, so TalkBack jumps a day at a
  time), a channel's guide opens at the programme on now with the reader there, a programme that has not
  started shows its details instead of playing, an empty guide offers Refresh guide, and guide search says
  what to type and when the list was cut (`Epg.SEARCH_LIMIT`, so message and SQL cannot drift; the string takes the number).
  The picker (`ChannelGuideActivity`) filters off the main thread and announces its channel count when it loads.
  `ChannelGuideActivity` is that app's Guide > Channel guide picker. New strings for it are copied from
  the iOS `tools/android_strings.py`, which is the one place both apps' guide wording lives.

- `Stalker.kt` - MAG handshake; channel url is `stalker:<cmd>`, turned into a link per play by `App.resolveUrl` (never play `ch.url` directly; use `Player.open`).
- Sections live / movies / series (`App.load(section)`); movies and series load only when opened.
  Big lists MUST stream: `Net.forEachObject` + `JsonReader` cache reader. Reading get_vod_streams whole (130 MB) crashed with OOM. `largeHeap` is on.
- Cast: `CastPlayer.Builder(ctx).setLocalPlayer(exo)` wraps the local player and transfers by itself; skipped when Play services are missing.
  On transfer the phone lets go of the stream (Media3 stops the inactive player), so the TV's own connection is the only one. An external
  player handoff is gone: another app's connections cannot be limited.

- Player status lines go through `say(text, priority)`: spoken only up to Settings, Announcements (desktop levels 1-3).
  The status line stays visible in full screen, so its live region keeps announcing there.
- Debug builds log buffer depth every 2 s (tag AiptvPlayer, `beat`): tells a slow feed from a delivery gap.
- Quick start (0.2.10): playback starts on 0.5 s buffered (`Cushion.QUICK_START_SECONDS`) and the cushion loads
  behind it. Filling never slows playback down (0.2.18, and the desktop's rule): the speed stays 1.0, and the
  cushion only grows from what the server hands over ahead of real time. Settings buffer (default 25 s for every
  provider; saved defaults 6/10 read as 25, max 18 reads as 30) is the target; other explicit settings are kept and
  the maximum is at least the base. After a stall it waits for the full cushion. Cold IPTVCanada tunes can take ~12 s
  server-side (provider TTFB). Dispatcharr channels (`Channel.isDispatcharr`, the header's
  `catchup-timezone`) keep the saved buffer: a stall does not raise it (0.2.19).
- Player: live channels reconnect like the desktop (`Reconnect`: 0.4, 2, 4, 8, 8, 8 s, reset after 2 min); catch-up and files do not.
  A reconnect starts with the quick-start buffer and never grows the cushion; ExoPlayer holds audio focus and stops on headphones unplug (0.2.16).
  A hidden player does not hold the socket (`onPause` stops and frees the stream; `onStart` re-prepares — live at the live edge, catch-up where it was);
  the player watches the shared socket's own reconnect and says `reconnecting` from the `pace` tick.
- Player traps: keep `FLAG_DETECT_ACCESS_UNITS` on the TS extractor (re-encoded IPTV H.264 has no AUD NALs -> audio only without it). Live streams arrive at real time: keep the short rebuffer target in `DefaultLoadControl`.
- Edge to edge: `App.keepContentClearOfSystemBars()` pads every activity's content by bars/cutout/IME. Do not remove; Samsung's taskbar covered the player buttons.

## Not yet ported

Stalker VOD, playlist scope filter (owner skipped it, 2026-10-13).

## Translations

13 languages, same as the desktop: `values-<lang>/strings.xml` + `raw-<lang>/help.md`. `TranslationTest` fails on a missing
key, changed placeholder, missing plural form or help section, so a new English string needs all 13 in the same commit.
Hungarian uses the desktop translator's terms (műsorújság, rögzítés, importálás). Apostrophes: typographic ’, never raw '.
Format-only strings are `translatable="false"`. `generateLocaleConfig` feeds Android 13+'s per-app language picker.

## Agent skills

### Issue tracker

Issues are tracked in this repo's GitHub Issues, via the `gh` CLI. See `docs/agents/issue-tracker.md`.

### Triage labels

Default vocabulary: `needs-triage`, `needs-info`, `ready-for-agent`, `ready-for-human`, `wontfix`. See `docs/agents/triage-labels.md`.

### Domain docs

Single-context: one `GLOSSARY.md` and `docs/adr/` at the repo root. See `docs/agents/domain.md`.
