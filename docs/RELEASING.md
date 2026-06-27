# Releasing CineX Mobile

End-to-end flow: **version bump → CI builds & signs → GitHub Release → in-app updater pushes it to users.**

## Components

| Piece | Where | Notes |
|---|---|---|
| Version source | `iosApp/Configuration/Version.xcconfig` | `MARKETING_VERSION` (→ versionName / release tag `v<MARKETING_VERSION>`) and `CURRENT_PROJECT_VERSION` (→ versionCode). Single source of truth for Android + iOS. |
| CI pipeline | `.github/workflows/release-mobile.yml` | Builds signed `:androidApp:assembleFullRelease`, verifies the APK has a manifest/resources, publishes a GitHub Release with the APK asset. |
| Update source | `composeApp/.../features/updater/AppUpdater.kt` | `gitHubOwner=amrit-regmi`, `gitHubRepo=NuvioMobile`, `releaseChannelBranch=feat/private-backend`. The fork is **public**, so the Releases API is read unauthenticated (no token in the app). |
| Version readout | Settings → about row | Shows `AppVersionConfig.VERSION_NAME` / `VERSION_CODE`. |

## One-time setup (repo owner)

Add these **Actions secrets** (`Settings → Secrets and variables → Actions`):

- `KEYSTORE_BASE64` — `base64 -w0 release.jks` of the signing keystore
- `NUVIO_RELEASE_STORE_PASSWORD`
- `NUVIO_RELEASE_KEY_ALIAS`
- `NUVIO_RELEASE_KEY_PASSWORD`

The release is published with the auto-provided `GITHUB_TOKEN` — no PAT required.

> Use the **same keystore** as the existing CineX builds, or in-app updates will fail to install
> over the current app (signature mismatch). Keystore lives at `~/keystore-backup/` on the build box.

## Cut a release

1. Bump `MARKETING_VERSION` (and `CURRENT_PROJECT_VERSION`) in `iosApp/Configuration/Version.xcconfig`.
2. Commit to `feat/private-backend` and push — the workflow auto-runs on changes to that file.
   (Or trigger **Actions → Release (CineX Mobile) → Run workflow** manually.)
3. CI builds, signs, verifies, and publishes Release `v<MARKETING_VERSION>` with `target_commitish=feat/private-backend`.
4. Installed apps detect it on next update check (`AppUpdater`) because the tag is a higher
   semver than the running `VERSION_NAME` and the channel (`target_commitish`) matches.

## Build-box vs CI note (important)

`gradle.properties` pins an **aarch64 AAPT2** (`android.aapt2FromMavenOverride=…/arm64-tools/aapt2`)
because the build box is ARM. GitHub runners are x86_64, so the workflow **strips that line**
before building (`sed -i '/aapt2FromMavenOverride/d'`). It also keeps
`android.enableResourceOptimizations=false` — required on the ARM box (AGP 9.2's optimize task
emits no resources there), harmless on x86_64.

## TV

The same pattern can be mirrored to the NuvioTV fork (separate repo, `com.netflix.ninjax.tv`),
but TV release builds are the user's domain — apply workflow + updater repoint only, user builds.
