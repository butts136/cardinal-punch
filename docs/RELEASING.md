# Releasing Cardinal Punch

## GitHub Actions workflows

- `Build-Android`: builds the signed-independent GitHub debug APK for quick verification.
- `Release-Android`: builds the signed release assets and creates a GitHub Release from `main`.

## Required GitHub secrets

Add these repository secrets before running `Release-Android`:

- `ANDROID_KEYSTORE_BASE64`: base64 content of the Android release keystore file.
- `ANDROID_KEYSTORE_PASSWORD`: keystore password.
- `ANDROID_KEY_ALIAS`: release key alias.
- `ANDROID_KEY_PASSWORD`: release key password.

## Release workflow behavior

When you launch `Release-Android` from `main`, the workflow:

1. injects `versionName` from the workflow input
2. uses the GitHub Actions run number as `versionCode`
3. builds a signed `githubRelease` APK for direct distribution
4. builds a signed `playRelease` AAB for Play Console upload
5. creates a GitHub Release tagged `v<version>`
6. attaches the APK and AAB to that release

## Distribution channels

- `github` flavor:
  - external GitHub update checks enabled
  - external APK install flow enabled
  - cleartext traffic allowed for the Linux bridge when needed

- `play` flavor:
  - external updater disabled
  - `REQUEST_INSTALL_PACKAGES` removed
  - ultra-fast foreground bridge service removed
  - cleartext traffic disabled
