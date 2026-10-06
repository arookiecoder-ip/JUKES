# Music Box

<div align="center">

[![Platform](https://img.shields.io/badge/Platform-Android-green.svg)](https://www.android.com/)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.0.21-purple.svg)](https://kotlinlang.org/)
[![Jetpack Compose](https://img.shields.io/badge/Jetpack%20Compose-UI-blue.svg)](https://developer.android.com/jetpack/compose)
[![Min SDK](https://img.shields.io/badge/Min%20SDK-26-orange.svg)](https://developer.android.com/about/versions/oreo)
[![Version](https://img.shields.io/badge/Version-2.4.0-green.svg)](https://github.com/arookiecoder-ip/MusicBox-APP/releases/tag/v2.4.0)
[![License: AGPL v3](https://img.shields.io/badge/License-AGPL%20v3-blue.svg)](https://www.gnu.org/licenses/agpl-3.0.html)

**A modern Android music player with smart recommendations, offline playback, and shared phone + Alexa output**

[Latest Release](#-latest-release) • [Features](#-features) • [Installation](#-installation) • [Build from Source](#-build-from-source) • [Tech Stack](#-tech-stack)

</div>

---

## 📦 Latest Release

**Music Box v2.4.0** is the current stable release. 🚀

Update-compatible signed build: installs over previous releases without uninstalling, with optimized/shrunk packaging and release-only logging.

[📥 Download v2.4.0](https://github.com/arookiecoder-ip/MusicBox-APP/releases/tag/v2.4.0) | [📝 Full Release Notes](docs/v2.4.0-RELEASE_NOTES.md)

> If you installed a very old build signed with a disposable key, one final reinstall is required — every update after that works normally. Details in the release notes.

## ✨ Features

### 🎵 Playback & Queue

- ExoPlayer (Media3) playback with foreground service, MediaSession, and notification controls
- Smart queue with infinite radio-style recommendations and duplicate prevention
- Queue tools: shuffle upcoming, sort A–Z, clear played, save queue as playlist
- Shuffle, repeat (off / all / one), sleep timer, and tap-to-seek
- Resumable playback position and queue restoration across restarts

### 📥 Downloads & Offline

- Dual download sources with automatic fallback on errors or duration mismatch
- Instant streaming start with background download completion (LRU-cached)
- Full offline playback of downloaded tracks and playlists
- Resumable Spotify playlist imports that survive app close and network loss
- Previews and wrong-length audio are detected, rejected, and re-fetched

### 📝 Lyrics

- Three-stage fallback: LRCLib → YouTube Music lyrics → YouTube captions
- Time-synced lyrics with auto-scroll and adjustable sync offset (±12s)
- Stale/instrumental-line filtering in the mini-player

### 🔍 Search & Discovery

- Debounced autocomplete suggestions while typing, full metadata search on submit
- Track, artist, album, and playlist results with Spotify URL/URI support
- Artist pages with top tracks and discography; album and playlist detail screens

### 📚 Library

- All Tracks, Favorites, playlists, Recently Played, and Most Played views
- Multi-select batch actions (play next, add to queue, add to playlist, delete)
- Playlist management with offline "save playlist" downloads

### 🔊 Audio

- 10-band equalizer with volume/bass boost and stable volume leveling
- Playback speed control and edge-only skip-silence
- Call handling: auto-pause on calls, resume afterwards
- Power Tools diagnostics (hidden developer page)

### 📱 Phone + Alexa Shared Output

- Coordinated playback ownership between the phone app and Alexa devices
- Guarded handoffs with lease deadlines — a silent device can never lock your controls
- Queue position and playback state stay consistent across the handoff

### 🎨 Interface

- Material Design 3 with a liquid-glass player, search, and library
- Phone and tablet layouts, dark theme, animated queue drag-and-drop
- Mini player with progress line and swipe skip gestures

## 📲 Installation

1. Download `Music-Box-2.4.0.apk` from the [releases page](https://github.com/arookiecoder-ip/MusicBox-APP/releases).
2. Install it on any device running **Android 8.0 (API 26) or newer**.
3. Grant storage/notification permissions when prompted.

The in-app updater tracks the stable channel: stable installs ignore prereleases, and beta installs are offered the upgrade to stable.

## 🛠️ Build from Source

Prerequisites: Android Studio (or JDK 17 + Android SDK, compileSdk 36).

```bash
git clone https://github.com/arookiecoder-ip/MusicBox-APP.git
cd MusicBox-APP
```

Create `local.properties` in the project root with your keys:

```properties
POSTHOG_API_KEY=your_key_here
POSTHOG_HOST=https://your-posthog-host
ALEXA_SESSION_BASE_URL=https://your-session-server
ALEXA_BASE_URL=https://your-audio-server
ALEXA_API_KEY=your_key_here
```

Then build and install the debug variant:

```bash
./gradlew assembleDebug
./gradlew installDebug
```

Release (signed, optimized) builds are produced by CI: dispatch the **Android Feature Build** workflow with `stable_release=true`. Release signing requires the preserved key — see `.github/signing/README.md`. Never commit keys or keystores.

## 🧱 Tech Stack

| Layer    | Technology                                                              |
| -------- | ----------------------------------------------------------------------- |
| UI       | Jetpack Compose (Material 3), Coil image loading                        |
| Playback | Media3 ExoPlayer, MediaSession                                         |
| Data     | Room database, Kotlin Coroutines + StateFlow                            |
| Network  | Ktor client, Spotify / YouTube Music / LRCLib APIs                     |
| Language | Kotlin 2.0.21, Java 11 bytecode                                         |

Project layout: `app/src/main/java/com/example/juke/` — `network/`, `services/` (playback, queue, updates), `ui/screens/` + `ui/components/`, `viewmodels/`, `database/`, `models/`, `utils/`.

## 🤝 Contributing

Contributions are welcome! Open a pull request against `feat/alexa-backend` with a clear description and test notes. Do not commit secrets, keys, or binaries.

## 📞 Support

For bugs, questions, or feature requests, please [open an issue](https://github.com/arookiecoder-ip/MusicBox-APP/issues).

## 📄 License

Music Box is licensed under the **GNU Affero General Public License v3.0** — see [LICENSE](LICENSE) for details.

---

<div align="center">

**Made with ❤️ using Kotlin and Jetpack Compose**

[⬆ Back to Top](#music-box)

**MADE WITH ❤️ BY MEEK**

[![GitHub](https://img.shields.io/badge/GitHub-100000?style=for-the-badge&logo=github&logoColor=white)](https://github.com/arookiecoder-ip/MusicBox-APP)
[![Release](https://img.shields.io/badge/Release-v2.4.0-green?style=for-the-badge)](https://github.com/arookiecoder-ip/MusicBox-APP/releases/tag/v2.4.0)

</div>
