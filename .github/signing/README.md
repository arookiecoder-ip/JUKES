# APK update signing

Feature APKs use the same `com.example.juke` package and a monotonically increasing version code. Android updates also require the same signing certificate.

The feature workflow preserves `~/.android/music-box-debug.keystore` using the immutable `music-box-debug-signing-v1` Actions cache. `.github/signing/debug-certificate.sha256` pins its public certificate. If the key is missing or changes, the workflow stops instead of publishing an APK that would require uninstalling the installed app.

For storage independent of Actions cache retention, configure the repository secret `ANDROID_DEBUG_KEYSTORE_BASE64` with the **same** preserved keystore encoded as base64. This debug keystore uses alias `androiddebugkey` and the standard debug store/key password `android`. Do not generate a replacement key, commit a private key, or print it in logs. Repository secret management is unavailable to this session's GitHub integration (HTTP 403).

APKs built before stable signing used disposable runner-generated keys. Their private keys were not preserved, so Android cannot update those installations to this signing identity. One final reinstall is required for such installations; subsequent APKs use the pinned certificate.
