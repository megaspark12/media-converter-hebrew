# Pixel 11 Pro: restored runtime version blocked downloader updates

This supersedes the unconfirmed device diagnosis in the earlier Pixel investigation.

## Confirmed cause

The connected Pixel was running app 1.1.1. Its restored `youtubedl-android.xml`
preferences reported yt-dlp **2026.08.19**, but the actual executable under
`no_backup/youtubedl-android/yt-dlp/yt-dlp` contained **2025.11.12**.
A real download using a previously failed YouTube URL reproduced HTTP 403 on
this phone before the repair.

The pinned Android wrapper installs its executable into `noBackupFilesDir`,
but saves its version in ordinary SharedPreferences. Android transfers those
preferences to a new installation while excluding the executable. The wrapper
then installs the old bundled executable and compares the remote release with
the restored version preference. If those version strings match, it incorrectly
returns `ALREADY_UP_TO_DATE` and never replaces the old executable.

Sources: [wrapper initialization](https://github.com/yausername/youtubedl-android/blob/0.18.1/library/src/main/java/com/yausername/youtubedl_android/YoutubeDL.kt),
[updater comparison](https://github.com/yausername/youtubedl-android/blob/0.18.1/library/src/main/java/com/yausername/youtubedl_android/YoutubeDLUpdater.kt),
[Android backup behavior](https://developer.android.com/identity/data/autobackup).

## Repair in version 1.1.2

- Query the installed executable with `--version` during initialization.
- If that differs from the recorded version, clear the update cooldown and
  record the actual version before the updater runs. This repairs installations
  that have already migrated, without clearing app data.
- Wire the manifest's backup rules and exclude the runtime's version preferences
  and update cooldown from both cloud backup and device transfer. Other user
  data retains the existing backup policy.
- Keep matching installations' successful update cooldown intact.

## Verification

- The migration regression failed before the repair: the installed executable's
  version differed from the restored preference, and initialization left the
  preference untouched. The repaired test passes.
- All 56 unit tests passed. Lint reported zero errors.
- The complete emulator suite passed, including both migration cases and the
  existing sharing and native conversion tests. Network smoke tests remain opt-in.
- Installed version 1.1.2 over the existing app on the actual Pixel, preserving
  its data. The same previously failing YouTube URL then completed real MP4 and
  MP3 downloads, validated readable saved media, and cleaned up its test outputs
  in 144.963 seconds.
- Independently read the installed executable again after upgrading: its actual
  version is now 2026.08.19. The Pixel reports Android API 37 and 4096-byte pages.
- A Facebook link from the phone's history also completed MP4 and MP3 with the
  app's default `best` quality (8.383 seconds). A diagnostic with an explicit
  720p cap reported no matching format; that cap is not the app's default.
- `LiveDownloadFlowInstrumentedTest` additionally covers the real activity,
  Download button, Android scheduler, persisted completion, decoded video frame,
  and Share button. Enable it with `runLiveFlow=true`; the manual CI live-test
  option runs it alongside the conversion smoke test.
- The complete live UI flow passed on the API 37 ARM64 emulator in 50.011 seconds.
  The first physical-phone UI attempt was blocked by its lock screen; the test
  now reports that prerequisite explicitly and keeps its own activity awake.
- After unlocking the Pixel, the same previously failed YouTube URL passed the
  full real Download-button test in **38.487 seconds**, including the Android
  job, saved MediaStore output, decoded video frame, and visible Share button.

The earlier tests used an emulator with an already updated executable. They did
not reproduce the new-phone combination of restored settings and a newly
installed bundled executable. This is why their passing results did not establish
that the Pixel's failure was repaired.
