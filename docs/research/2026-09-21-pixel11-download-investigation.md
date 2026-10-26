> Follow-up: the connected Pixel's migration bug was reproduced and repaired in version 1.1.2. See [the confirmed root cause and physical-device verification](2026-09-21-pixel11-restored-runtime-fix.md). This document preserves the earlier investigation.

# Pixel 11 Pro download failure: research and proposed repair

Investigated on 2026-09-21 against `main` at `67d19579ee113d57b764d51e17dfe28b02f867b7`.

## Implementation update: version 1.1.1

The user authorized implementation after the initial investigation. Changes are on `codex/pixel11-download-reliability`.

- Downloads now use yt-dlp's maintained client defaults, matching metadata extraction.
- MP4 selects separate video/audio streams with a combined-stream fallback. Every video branch respects the selected height limit.
- The app merges separate sources through its existing FFmpegKit runtime before normal conversion and MediaStore publication. No additional native dependency is shipped.
- Controlled retries remove the failed attempt's source files before trying again, including when an extractor update changes the selected format IDs.
- Update state now distinguishes successful checks (24-hour interval) from failures (5-minute retry interval). The old timestamp is intentionally not migrated because it also represented failed checks. Cancellation and fatal errors propagate.
- CI includes API 36 and a manual opt-in live test. The live test accepts `liveUrl` and `liveQuality` instrumentation arguments; its default quality is 720p.

The initially proposed wrapper FFmpeg dependency was rejected after an actual emulator failure: its embedded `libsharpyuv.so` has 4096-byte ELF alignment, and Android with 16384-byte pages refuses to load it. FFmpegKit merges the same fixtures successfully. This supersedes the proposed dependency approach below.

Verification during implementation:

- Eight new unit failures were observed before the initial fixes. A separate-stream pipeline regression and the stale-format-ID retry regression were also observed failing before their respective fixes.
- Final unit suite: **56 tests passed**, zero failures/errors/skips. Includes update throttling across runtime recreation, cancellation during recovery, native-merge cancellation/cleanup/no-publication, and retry file cleanup.
- Build, lint, app APK, and instrumentation APK assembly succeeded. Lint reports zero errors and the existing 72 warnings.
- Full emulator suite passed **15 tests**, including actual YouTube MP4/MP3 downloads, sharing, scheduling configuration, UI/state handling, native FFmpeg, separate-stream merging, and combined fallback. This run preceded the final retry cleanup and additional codec fixture.
- The final application APK passed the live MP4/MP3 test again. The final native fixture suite passed **3 tests**, including VP9/Opus WebM to MP4. The generated VP9 fixture initially had invalid RGB/profile metadata; validating it before download exposed that fixture defect, which was corrected with explicit YUV/BT.709 metadata.
- The device was a temporary ARM64 API 37 emulator with measured 16384-byte pages. All 154 packaged ARM64 ELF files (including archived native payloads) passed the 16 KB segment-alignment inspection.
- Independent code review found one retry-output defect; its regression was added and the defect fixed. Focused re-review reported no remaining actionable findings.

The installable debug APK is `app/build/outputs/apk/debug/app-debug.apk` (versionName `1.1.1`, versionCode `3`). The Pixel 11 Pro itself, the user's failing URL, live Facebook downloads, and the expanded GitHub CI matrix have not been tested in this session. The reported phone-specific failure must still be confirmed with that device/link.

To reproduce the live test on a connected ARM64 device, using the installed Android SDK and JDK:

```sh
./gradlew connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.mediaconverter.app.LiveConversionSmokeTest \
  -Pandroid.testInstrumentationRunnerArguments.runLive=true
```

The remaining sections preserve the original investigation and proposed sequence as historical context.

## Report and success criteria

The user reports that the app finds video details on a Pixel 11 Pro, but downloading fails. The failing URL, exact error, selected output format, Android build, and installed APK version have not yet been supplied. YouTube is a hypothesis; the app also supports Facebook.

Success means the same reported link downloads completely, converts to the selected MP4 or MP3 format, and opens from Downloads on the user's phone. An emulator result is useful evidence but does not establish that the Pixel 11 Pro issue is fixed.

## Findings

### 1. Metadata and downloads use different YouTube client policies

`app/src/main/java/com/mediaconverter/app/data/YtDlpRuntime.kt:47` builds metadata requests with the upstream default clients. `app/src/main/java/com/mediaconverter/app/data/ConversionEngine.kt:64` instead forces `web_embedded`, then `android_vr`, for downloads.

The upstream maintainer reported that `android_vr` version 1.65.10 started receiving HTTP 403 for all formats on August 17, 2026. Upstream removed it from the default client list, and the August 19 release also changed embedded-client fallbacks. Updating yt-dlp does not remove this app's explicit override.

Sources: [upstream removal](https://github.com/yt-dlp/yt-dlp/pull/17461), [2026.08.19 release](https://github.com/yt-dlp/yt-dlp/releases/tag/2026.08.19).

**Assessment:** strong candidate for YouTube failures, especially when the first embedded-client attempt is unavailable. It is not proof of the user's exact failure and does not explain a Facebook-only failure.

### 2. The MP4 selector cannot download separate video and audio streams

`ConversionEngine.kt:78` selects only a precombined video-and-audio stream. An offline probe using the actual bundled yt-dlp executable and synthetic 720p video plus audio formats produced:

```text
Current selector: best[ext=mp4][vcodec!=none][acodec!=none]/best[vcodec!=none][acodec!=none]/best
Selected formats: []

Probe selector: bestvideo+bestaudio/best
Selected formats: ['video-720+audio']
```

No network request was made by this probe. It confirms a format-selection limitation, not which formats the user's link currently offers. Numeric quality selections also end with an unrestricted `/best`, so that fallback can exceed the requested height.

Source: [yt-dlp format selection](https://github.com/yt-dlp/yt-dlp#format-selection).

### 3. Adaptive downloads require an additional merge executable

The APK contains FFmpegKit shared libraries for the later conversion stage but does not contain `libffmpeg.so`, the executable the yt-dlp wrapper invokes. Adding `bestvideo+bestaudio` alone would therefore be incomplete.

The existing version catalog already declares the compatible wrapper FFmpeg artifact, but `app/build.gradle.kts` does not include it. Its locally cached AAR contains `libffmpeg.so`, `libffprobe.so`, and `libffmpeg.zip.so`. Its initializer is `com.yausername.ffmpeg.FFmpeg.getInstance().init(context)`.

The alternative is to download two files explicitly and pass both to FFmpegKit, but that requires changing the extractor/transcoder interface and cancellation flow. If adaptive download support is needed, using the wrapper's supported executable is the smaller integration change. Check all newly packaged native payloads for 16 KB alignment and verify subprocess cancellation before shipping.

Source: [wrapper documentation](https://github.com/yausername/youtubedl-android).

### 4. The bundled extractor is old; updates can be suppressed after failure

The actual APK's `res/raw/ytdlp` reports `2025.11.12`. The application attempts a stable update at startup. This does not establish the version on the user's phone.

`YtDlpRuntime.kt:95` persists the last-check timestamp in `finally`, including when an update fails. A startup update outage can therefore suppress another update check for 24 hours, including the recovery check after a provider failure. The runtime retries the original command even when no update occurred.

Verify the installed extractor version and update outcome before deciding whether this needs to change for the reported incident. If changed, distinguish successful checks from failed checks, use a bounded failure backoff, preserve cancellation, and prevent repeated requests from hammering the update server.

### 5. QuickJS is already supplied; a page-size failure is not established

The pinned `youtubedl-android` 0.18.1 wrapper injects the QuickJS runtime path. The APK includes `libqjs.so`, and the bundled yt-dlp zip contains EJS entries. Do not prescribe a second JavaScript runtime without a runtime error identifying that problem.

An inspection of 154 ARM64 ELF files, including shared libraries inside the Python payload archive, found no `PT_LOAD` alignment below 16 KB. Compressed native packaging means APK zip alignment alone is insufficient to validate those embedded binaries. Actual execution on a 16 KB system is also required.

Sources: [pinned wrapper implementation](https://raw.githubusercontent.com/yausername/youtubedl-android/0.18.1/library/src/main/java/com/yausername/youtubedl_android/YoutubeDL.kt), [EJS requirements](https://github.com/yt-dlp/yt-dlp/wiki/EJS), [Android page-size guidance](https://developer.android.com/guide/practices/page-sizes).

### 6. Existing green tests do not exercise live downloads by default

`LiveConversionSmokeTest` requires the instrumentation argument `runLive=true`. Normal CI does not supply it. The configured device matrix covers API 26, 29, and 34, despite targeting API 36. Existing unit tests assert the hardcoded client policy, which means they preserve that policy rather than demonstrate provider compatibility.

Android 34+ already uses user-initiated transfer jobs, so replacing the scheduler is not the first proposed fix. Metadata bypasses that scheduler, however, so scheduling remains a separate possible failure stage until the reported error is known.

Source: [Android user-initiated transfer guidance](https://developer.android.com/develop/background-work/background-tasks/uidt).

## Proposed repair sequence

- [x] Trace metadata, scheduling, download, conversion, and publication boundaries.
- [x] Check current upstream client changes and inspect the actual packaged runtime.
- [x] Reproduce the separate-stream selector limitation offline.
- [x] Run fresh unit tests and baseline lint/build checks.
- [ ] Match the user's error to a stage using the failing URL, selected format, APK version, Android build, and actual extractor version. Distinguish scheduling rejection, HTTP 403, missing formats, native loading, conversion failure, and publication failure.
- [x] Run the existing live public fixture through the unchanged conversion engine. Both MP4 and MP3 completed on the emulator with yt-dlp 2026.08.19. This rules out a universal failure of the current pipeline in that environment.
- [ ] Reproduce the reported link and compare metadata and download on the same runtime version. Change one variable at a time: first client defaults, then format selection if necessary. Record both stderr and whether real media bytes finish downloading.
- [ ] If client selection is confirmed, remove app-owned YouTube client pinning so metadata and downloads follow maintained upstream defaults. Keep transport security, single-video behavior, and bounded retries.
- [ ] If separate streams are required, add and initialize the wrapper FFmpeg module, enable adaptive selection plus a precombined fallback, and preserve height constraints on every branch. Continue validating and publishing a single completed source through the existing conversion engine.
- [ ] If the outdated-runtime path is observed, repair update recovery with separate successful/failed-check treatment and bounded backoff. Do not automatically switch channels or retry permanent access failures.
- [ ] Verify actual playback and audio, cancellation, partial-file cleanup, retry limits, and failed-publication cleanup. Test MP4 and MP3, requested quality, a non-embeddable public YouTube video, and a public Facebook video.
- [ ] Test both the oldest supported paths and a current ARM64 Android environment with 16 KB pages. Re-run the user's link on the Pixel 11 Pro before claiming that device's issue is fixed.

## Expected files and regression coverage

| File | Proposed responsibility |
| --- | --- |
| `app/src/main/java/com/mediaconverter/app/data/ConversionEngine.kt` | Client defaults, adaptive format selection, final source-file validation |
| `app/src/test/java/com/mediaconverter/app/data/YtDlpDownloadCommandFactoryTest.kt` | Default client policy, bounded height selectors, MP3/MP4, Facebook |
| `app/build.gradle.kts` | Wrapper FFmpeg dependency, only if adaptive merge is selected |
| `app/src/main/java/com/mediaconverter/app/data/AndroidYtDlpRuntime.kt` | Initialize the executable runtime and report the actual update outcome |
| `app/src/main/java/com/mediaconverter/app/data/YtDlpRuntime.kt` | Bounded update recovery, only if confirmed relevant |
| `app/src/test/java/com/mediaconverter/app/data/YtDlpRuntimeTest.kt` | Failed update recovery, throttling, cancellation, permanent failures |
| `app/src/androidTest/java/com/mediaconverter/app/LiveConversionSmokeTest.kt` | Full real downloads and saved-media validation |
| `app/src/androidTest/java/com/mediaconverter/app/FfmpegRuntimeTest.kt` | Native execution and a deterministic separate-track merge fixture |
| `.github/workflows/android-ci.yml` | Current Android coverage and explicit opt-in live checks |

## Baseline verification

- Fresh `:app:testDebugUnitTest --rerun --offline`: 46 tests, zero failures/errors/skips.
- `testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest --offline`: successful; lint has zero errors and 72 warnings. Compilation and APK assembly were up to date.
- No physical device was connected. A temporary read-only emulator copy was started at `emulator-5580`; its measured API level is 37 and page size is 16384. The image is the locally installed `android-37.2-beta3` ARM64 image, not the user's phone.
- `FfmpegRuntimeTest` and `LiveConversionSmokeTest`, explicitly run with `runLive=true`: `OK (2 tests)` in 44.166 seconds. The latter completed both MP4 and MP3 conversion and MediaStore publication for `https://www.youtube.com/watch?v=YE7VzlLtp-4`. It verified nonzero size and duration and readable saved output, then deleted the generated media. This tests the conversion engine directly, not the UI scheduling path or human playback.
- Emulator preferences identified the active extractor as `yt-dlp 2026.08.19`. This is newer than the bundled 2025.11.12 extractor and confirms why the bundled version must not be mistaken for the installed runtime version.
- The temporary emulator was shut down after testing. Its read-only mode avoided persisting test-session changes to the original AVD.

**Current conclusion:** there are verified client-policy, format-selection, and update-recovery weaknesses, but the user's specific root cause remains unconfirmed. Do not label the Pixel 11 Pro issue fixed or assume a hardware incompatibility. Obtain the failing URL and error, then apply the smallest repair that changes that reproduction from failure to success.

Local checks required the already-installed SDK at `/Users/ronrave/Library/Android/sdk` and the cached JetBrains JDK 21. No dependency upgrades or application-source changes were made during this research.
