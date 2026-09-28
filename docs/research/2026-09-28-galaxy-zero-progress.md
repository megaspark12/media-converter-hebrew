# Galaxy conversion stuck at 0%: investigation and bounded update check

The user reports that any pasted video link remains at 0% after selecting an output format on a Galaxy running the latest available app. The exact model, Android release, displayed status, installed version number, and device logs are unavailable. No physical Android device was connected during this investigation.

## Confirmed hang path

`YtDlpRuntime` serializes initialization, updates, metadata, and downloads with one mutex. The pinned Android wrapper (0.18.1) checks GitHub release metadata through `ObjectMapper.readTree(URL)` without setting connection or read timeouts. This can keep the mutex locked indefinitely when the update endpoint stops responding, including during provider-error recovery before the first downloaded bytes. Queued requests cannot proceed, and the UI can remain at 0%.

A controlled loopback server accepted a connection and sent no response. The regression failed against the original behavior: the request did not return before the test deadline. This establishes a real indefinite-wait bug; it does not establish that the user's Galaxy hit this exact path.

Sources: the locally cached 0.18.1 wrapper's `YoutubeDLUpdater.kt` and [Java URLConnection timeout documentation](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/net/URLConnection.html#setReadTimeout(int)). A zero timeout means an unlimited wait.

## Repair in 1.1.3

Fetch release metadata with a 5-second connection timeout and a 10-second read timeout before invoking the wrapper. Pass the completed temporary JSON file as the wrapper's update channel, preserving its version comparison, executable installation, binary-download timeouts, and rollback. Remove temporary metadata on success and failure. Run the blocking update through `runInterruptible` so coroutine cancellation is preserved, with network waits bounded by their I/O timeouts.

The existing runtime records failed checks with its five-minute backoff and can release its mutex after the timeout. Startup can continue with the installed executable; provider recovery can perform its existing single controlled retry. These are inactivity timeouts, not a guarantee that a server continuously sending bytes must finish within ten seconds.

## Other paths and limits

A pending scheduler request can wait for connectivity or sufficient storage. Download extraction can also precede the first percentage callback. Without Galaxy logs, neither can be ruled out. The pre-fix branch's live Download-button flow completed successfully on the API 37 ARM64 emulator, including a decoded saved video and the Share button.

Physical Galaxy verification remains necessary: install 1.1.3 and retry a previously failing link. If it still stalls, capture the exact on-screen status, `ConversionDiagnostic` logs, and JobScheduler/WorkManager state to distinguish pending scheduling from active extraction. Do not claim the Galaxy-specific incident is resolved based solely on emulator or unit results.

## Verification

- The stalled-server regression failed before the timeout fix, then passed with `SocketTimeoutException`, no installer invocation, and no temporary metadata left behind.
- All 59 unit tests passed, zero failures/errors/skips.
- Debug app and instrumentation APK builds succeeded. Lint passed with zero errors and 70 existing warnings. One combined run hit a lint-generated-stub race (`FixtureServer.java` disappeared during analysis); completing compilation and running lint separately resolved it.
- API 37 ARM64 emulator deterministic suite: 22 passed; two opt-in live tests skipped. Includes real downloader version comparison and executable reinstallation through the local release-metadata channel.
- Independent review found no actionable correctness issue in the fix.
- Final APK live tests passed: real YouTube MP4 and MP3 conversion plus the complete Download-button/scheduler/saved-video/Share flow (86.424 seconds).
- The initial GitHub build failed before compilation because setup-android's default package list requested the removed `tools` package. CI now explicitly installs platform-tools, Android 36, and build-tools 36.0.0.
