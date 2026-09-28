# Reproduced download bugs repaired in 1.1.4

## Foreground worker rejection leaves a permanent pending row

On the WorkManager path (Android 8–13), a foreground-service or notification
setup exception occurs before `DownloadTaskRunner.run`. WorkManager records the
worker failure, but the app's separate Room download row remains pending at 0%.

An instrumented test uses the real DownloadWorker, real Room database, and the
matching AndroidX WorkManager test builder to inject SecurityException at the
foreground-updater boundary. Before the fix, the expected failed status was
still pending. Ordinary worker exceptions now mark the app row failed and remove
the ongoing notification. Coroutine cancellation retains its existing retry
behavior, verified by a separate test.

This is a demonstrated explanation for a stuck download under this condition;
it is not proof that the reported Galaxy encountered it.

## An old cancellation overwrites a newer download's status

A cancellation can finish after a newly shared URL starts another download.
The cancellation failure handler previously wrote its error into the new
session's message. A controlled delayed-failure unit regression demonstrated
the wrong message while the newer download ID was active. Both cancellation UI
writes now require the captured session and download ID to remain current.
Coroutine cancellation is propagated instead of displayed as a user error.

## Missing stream fragments produce falsely successful media

The downloader default skips unavailable HLS/DASH fragments after retries.
A six-second H.264/AAC HLS fixture with a missing middle fragment still downloaded
and remuxed successfully. Timestamps retained a positive/full duration and both
tracks, so the existing file validator accepted incomplete media.

The Android regression failed before the repair because conversion returned
success and reached publication. Download commands now use
`--abort-on-unavailable-fragments`. The regression requires the specific
missing-fragment error, no publication, and removal of request temporary files;
an unrelated decoder/fixup error cannot satisfy it. Synthetic fixture assets
and regeneration instructions are included under androidTest/assets.

## Verification

- All three regressions were observed failing before their respective fixes.
- 60 unit tests passed; debug app/test APK builds and lint passed.
- Complete ARM64 API 37 emulator suite: 25 passed, two opt-in live tests skipped.
- Independent review found no production regression and requested the stricter
  fragment-error assertion described above.
- Physical Galaxy confirmation remains outstanding.
- Final rerun of the stricter missing-fragment regression plus live MP4/MP3
  conversion and the complete Download-button flow passed: three tests in
  74.547 seconds.
