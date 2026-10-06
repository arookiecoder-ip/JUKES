# Single-user playback architecture

Music Box remains a single-user app with a custom Alexa skill using AudioPlayer events. It does not require a Music Skill, Redis, another container or additional server workers.

## Implementation checklist

| Recommendation | Implementation / verification |
| --- | --- |
| 1. Shared playback coordination | Application-owned `PlaybackCoordinator` provides the phone controller and one Echo controller. `PlaybackPhase` describes resolving, buffering, playing, paused, switching, failed and idle without changing loading UI behavior. |
| 2. Shared synchronization | UI and notification register consumers with the same controller. Echo polling is 3 seconds during active foreground playback/transitions, 10 seconds for foreground paused/background playing, 30 seconds for background paused, with failure backoff up to 60 seconds. Volume reads slow down while idle. Phone lease renewal stays separate; heartbeat responses replace redundant status reads. |
| 3. Confirmed transfers | Existing bounded transfer/rollback logic and AudioPlayer confirmation remain. Queue IDs now survive Alexa-to-phone transfer. Epoch guards discard status reads crossing a command. |
| 4. Queue identity and revisions | Backend assigns stable occurrence IDs, including duplicate songs. Phone publications carry occurrence IDs, command IDs and expected revisions. Repeated commands apply once. Stale edits fail without mutation; full phone queue publications remain authoritative. Polls omit unchanged queue metadata. |
| 5. Startup latency | Existing stable `wait=1` file/range serving and coalesced backend preparation remain. Downloads load asynchronously; phone playback waits for local manifests before selecting its source. Background preloads use reserved backend prefetch slots. |
| 6. Playback recovery | Existing disconnected-controller recovery, bounded stream retries, 429 Retry-After handling and local-file validation remain. Expired audio credentials are cleared during recovery. Destroyed services cancel ownership/recovery jobs instead of retaining abandoned loops. |
| 7. Restart persistence | SQLite stores shared queue metadata, cursor, position and output preference. Restarts restore paused metadata, with a fresh ownership token and no phone lease. One coalescing worker writes outside request threads. |
| 8. Download persistence | Separate Room tables store manifest records and ordered collection membership. Legacy JSON is imported transactionally before removal. States include queued, downloading, complete, failed and missing; file validation prevents missing downloads being advertised as playable. Writes update only changed rows. Existing music/lyrics database is untouched. |
| 9. Adaptive preloading | Wi-Fi: next five tracks, at most 2 MiB each. Metered networks: disabled by default; optional next one, at most 512 KiB. Buffering, pausing, offline transitions and obsolete queue entries cancel preloads. Network and settings changes re-evaluate policy. Cache capacity is configurable and applies on process restart. |
| 10. Shared HD artwork | One resolver verifies original image dimensions, coalesces requests, shares fallback/backoff and holds a bounded decoded cache. Player surfaces stay blank until HD is verified. Notifications use the same resolver with a smaller bitmap. |
| 11. Custom skill events | Existing `PlaybackStarted`/`PlaybackStopped` confirmation remains authoritative; mirrored phone playback is not evidence of Alexa playback. No Music Skill conversion. |
| 12. Responsibility boundaries | Phone controller, phone ownership synchronization, artwork loading, audio credentials, queue-command handling and backend persistence are separate components. Media service lifecycle and UI state remain where appropriate; no wholesale UI rewrite. |
| 13. Diagnostics | Bounded aggregate timings/counts for server requests, audio connection, first byte, handoff and recovery. No song names, URLs, account identities or secrets in these measurements. Unit/backend integration checks cover protocol, restart, scope/revocation, handoff and policy edges. |
| 14. Scoped credentials | New APKs embed no server-wide audio key. Owner sessions issue audio-only tokens (2 hours playback, 24 hours system downloads), checked against live sessions. Tokens cannot mutate queues/accounts; scoped radio requests cannot publish queue changes. Old skill/APK keys remain accepted server-side during rollout. |
| 15. Optional push | Intentionally retain finite adaptive polling. The current Waitress SSE route sends one snapshot and closes to avoid worker exhaustion. Persistent push would need an independently reviewed streaming-capable deployment; it is not required for this single-user setup. |

## Deployment order

Deploy the matching backend change **before installing the new APK**: new builds use `/api/app/audio-token/` and authenticated queue/output APIs instead of an embedded global key. Keep existing `SECRET_KEY`, `DB_FILE`, audio cache and credentials persistent. No migration deletes audio files or the existing account/music database. Existing custom-skill credentials keep working.

## Offline and validation limits

A disconnected server cannot enforce exclusivity between an offline phone and an Echo. Completed local downloads retain the existing offline playback policy; online streaming stops at lease expiry. Reconnection reconciles ownership before online playback resumes. This distributed limitation cannot be solved by polling alone.

Actions runs focused JVM tests and signs the APK with the existing certificate. Backend tests exercise real Flask middleware/token issuance/range serving against temporary storage, plus queue and ownership handlers with physical Echo calls stubbed. UI/emulator tests are disabled. Physical device/Alexa behavior is not claimed as tested by these checks.
