# Accessible IPTV Client for Android

A vibe-coded, screen-reader-friendly IPTV player for Android phones, tablets, and Android TV, built to work well with TalkBack and to handle big playlists and guides without choking.

[![Join SerrebiProjects on Telegram](https://img.shields.io/badge/Telegram-SerrebiProjects-2CA5E0?style=for-the-badge&logo=telegram&logoColor=white)](https://t.me/SerrebiProjects)
[![License: MIT](https://img.shields.io/badge/License-MIT-green?style=for-the-badge)](LICENSE)

**Questions, bugs, or release news?** Join the [SerrebiProjects Telegram group](https://t.me/SerrebiProjects), the fastest place to get help.

This is the Android companion to [Accessible IPTV Client](https://github.com/serrebidev/Accessible-IPTV-Client) for Windows and Linux.

## Features

- Xtream Codes logins, Stalker Portals (portal address and MAC), M3U / M3U Plus playlist URLs, and M3U files stored on the device.
- Live TV, Movies, and Series, each with groups plus Favourites and All at the top. A series opens to its episodes, in season order.
- Search at the top of the screen covers every group in the section, with a Voice button that fills it by speech.
- XMLTV guide (`.xml` and `.xml.gz`), including large multi-hundred-megabyte guides. The guide is grouped under a heading for each day, so screen-reader heading navigation jumps a day at a time. Each channel says what is on and until when; **On now** lists everything airing; **Guide search** finds upcoming programmes by title or description; **Guide** picks a channel and reads it.
- Xtream and Stalker logins find their guide by themselves, and the guide re-imports on its own when the playlist changes.
- Catch-up playback for channels with an archive (Xtream timeshift and M3U catch-up templates, the same rules as the desktop app).
- Built-in player with always-visible controls and a full screen mode. It picks an audio description track when a channel has one, in the wording providers actually use, and remembers a track you choose per channel.
- Casts to Chromecast and Google TV, or hands any stream to another player app.
- Record now, schedule recordings from the guide, and download movies, episodes, and catch-up programmes. Scheduled recordings run 2 minutes past the end (both paddings are adjustable) and survive a restart. Recordings in progress are listed first, each with Stop. A live recording rides out network drops, and a catch-up download that ends early is fetched again.
- Previous channel, Recently watched, and Go to channel number; on a remote, the number keys jump straight to a channel.
- A dropped live stream reconnects by itself.
- Live playback starts with half a second buffered, then fills the rest at full speed: playback is never slowed down to build a cushion. Channels from a Dispatcharr restreamer keep the buffer you saved. Settings adjust the cushion and its 30-second default maximum.
- Subtitles shown under the video and spoken by your screen reader. The player says when the programme changes; Settings > Announcements sets how much it says.
- Export and import every setting, playlist, and favourite as one file.
- 14 languages: English, Spanish, Arabic, Portuguese (Brazilian), French, German, Russian, Turkish, Italian, Polish, Hindi, Chinese (Simplified), Japanese, and Hungarian. On Android 13 and later, **Settings > Language** picks one for this app only.
- Account info for Xtream and Stalker: expiry, trial, and connections.
- Built-in updater that pulls new versions from Forgejo Releases and verifies the SHA-256 sidecar before installing.
- An offline Help screen, with headings for quick navigation.

## Accessibility

Built from the start for TalkBack and other Android screen readers, following the [CVS Health Android View accessibility techniques](https://github.com/cvs-health/android-view-accessibility-techniques).

- Every list row is a single stop. Extra choices (another player, favourite, details, guide, record, download) are in the TalkBack Actions menu, or on long-press and the Menu key.
- Real buttons with short, plain names. No icon-only controls, no auto-hiding overlays.
- Headings on every section, labelled form fields, and errors that move you to the field that needs fixing.
- Status changes are announced politely and only when something actually happened.
- On Android TV, every control is reachable with the D-pad and has a thick, high-contrast focus outline.

## Download and install

Grab the latest `AccessibleIPTV-vX.Y.Z.apk` from the [Releases page](https://git.serrebiradio.com/serrebi/Accessible-IPTV-Client-Android/releases).

**Phones and tablets**

1. Download the APK on the device and open it.
2. Allow installs from your browser or file manager when Android asks.

**Android TV and Google TV**

1. Install the Downloader app, or use `adb install`.
2. Open the APK. The app shows up on the TV home screen.

Needs Android 10 or later. After the first install, the app updates itself: it checks once a day, and **More > Check for updates** checks now.

## Getting started

1. Open **Playlists** and add an Xtream login, an M3U URL, an M3U file, or a Stalker portal.
2. Go back. Channels load, then the guide imports in the background.
3. Pick a group, or just search.

## Build

```bash
gradlew testDebugUnitTest assembleRelease
```

Needs JDK 21 and the Android SDK (platform 37).

## Contributing

Pull requests are welcome. If Accessible IPTV Client has been useful to you, open a PR with a fix or feature and I'll review it.
