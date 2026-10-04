# Native Alexa remote feature parity

The original phone-only integration could not display Echo state: MusicViewModel uses the phone MediaController, and LibraryViewModel uses Room. The native remote now uses its own API client, state and Compose screens. Existing phone screens, Spotify, downloads, lyrics, audio effects and Power Tools remain available through Settings or Switch to phone playback.

| Web feature | Native implementation |
| --- | --- |
| Current song, artwork, progress, duration, playback errors | Player and persistent current-song card; serial-specific polling while visible |
| Device selection, transport, seek, volume | Player |
| Shared queue, select/remove/move/shuffle/clear, play-next/add-last | Queue and song/collection menus |
| Mixed Home shelves and filters | Home, with backend entity targets |
| Categorized search, suggestions and pasted links | Search |
| Album, artist and playlist details, full artist songs | Native browse pages with Android back navigation |
| Library, playlist create/rename/delete/add-song, pagination | Library and playlist picker |
| Likes, subscriptions, history | Library tabs and context menus |
| New music, charts, moods and genres | Explore |
| Owner login/TOTP, API key, account status | Accounts |
| Amazon and Google authentication | Native setup controls; restricted account browser only for vendor login/noVNC |
| Jam start/share/copy/QR/stop, guest join/leave | Jam, with isolated guest credentials |

Native remote defaults to the production HTTPS address and also accepts a staging base path. API keys are scoped to the selected server. Phone streams retain the Actions-configured staging URL/key. Production and staging accounts are independent. Users sign in as owner or enter the matching API key; owner sign-in is required for YouTube browser reconnect. Passwords and cookies stay in memory; API key preferences are excluded from backup. No keys appear in URLs or HTTP logs.

The original plan intentionally limited phone Alexa mode to tracks. This native remote expands that scope under the user's subsequent request to rebuild web features. It does not remove that phone mode.

## Validation

Cloud CI runs both existing backend client suites, URL policy tests and mixed-schema parser tests, then builds the APK. No Android builds run on the VPS. Device UAT remains required for audible Echo transport, vendor authentication, playlist writes, Jam invitation scanning and returning to phone playback. Backend-supported actions are respected: the web backend offers playlist add, rename and delete, but no remove-track API; it cannot clear YouTube listening history.
