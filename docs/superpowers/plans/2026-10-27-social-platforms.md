# Instagram, TikTok and X Links Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let the app download and convert a single video from an Instagram, TikTok or X link exactly as it does from YouTube and Facebook today, and tell the user in Hebrew why a site refused when it does.

**Architecture:** Every link already goes to yt-dlp, which ships extractors for all three sites, so no download or transcoding code changes. The work is in the link parser (which hosts and which link shapes count as one video), the metadata reader (multi-item posts), the failure mapper (each site's refusals) and the Hebrew copy.

**Tech Stack:** Kotlin, Android (minSdk 26), yt-dlp through youtubedl-android, JUnit 4 unit tests. No new dependencies.

**Spec:** none. The user skipped the design conversation and asked for sensible defaults; the defaults are recorded in the Design section below and are the builder's calls, not the user's.

## Design (defaults chosen by the builder)

**Platforms.** `SupportedPlatform` grows `INSTAGRAM("instagram")`, `TIKTOK("tiktok")`, `X("x")`. The value is what `downloads.platform` stores and what the log lines print.

**Hosts.** Case-insensitive, after a trailing dot is trimmed, matched as the exact host or a subdomain of it:

| Platform | Hosts |
|---|---|
| Instagram | `instagram.com`, `instagr.am` |
| TikTok | `tiktok.com`, `tiktokv.com` |
| X | `x.com`, `twitter.com` |

`t.co` is not accepted: it shortens arbitrary links, not only X posts. Third-party mirrors (fxtwitter, vxtwitter) are not accepted; yt-dlp has no extractor for them.

**One post, not a page.** Like the existing YouTube rule, a link is accepted only when it names a single post. Path segments are split on `/` with empties dropped.

- Instagram: `p|reel|reels|tv/<id>`, optionally prefixed by one `<username>` segment; `share/<anything>` with at least two segments (the app's share button hands these out; they redirect to the post). Rejected: `reels/audio/<id>`, `stories/...`, `explore/...`, a bare profile, the root.
- TikTok: `vm.tiktok.com/<code>` and `vt.tiktok.com/<code>` (any non-empty first segment); on other TikTok hosts `t/<code>`, `@<user>/video/<id>` (the user part may be a bare `@`), `share/video/<id>`, `embed/<id>`. Rejected: a profile, `@<user>/photo/<id>`, `@<user>/live`, `tag/...`, `music/...`, the root.
- X: `<user>/status/<id>`, `i/status/<id>`, `i/web/status/<id>`, `statuses/<id>`, each optionally followed by `video/<n>` or `photo/<n>`; the id must be digits. Rejected: a profile, `home`, `search`, `i/spaces/...`, `i/broadcasts/...`, the root.

The accepted link is passed to yt-dlp unchanged apart from the existing http-to-https rewrite, so a `/video/2` index on X reaches the extractor.

**Multi-item posts.** An Instagram carousel or an X post with several videos makes yt-dlp return a playlist. Both the metadata command and the download command gain `--playlist-items 1`, so only the first item is fetched. The metadata reader falls back to the first entry's `title`, `thumbnail` and `duration` when the top-level object is a playlist.

> **Amended after the whole-branch review (commit "fix: fetch the first item of a carousel that actually has a video").** The premise above was wrong for Instagram: a carousel lists photos and videos alike, a photo has no formats, and item 1 is often a photo. What shipped instead: the metadata command describes every item under `--ignore-no-formats-error`; `parseVideoInfo` picks the first entry with a non-empty `formats` array (a post with none is `NO_VIDEO`); the chosen 1-based index travels `VideoInfo.playlistIndex` → `DownloadSpec.playlistIndex` → `downloads.playlistIndex` (schema version 3, `MIGRATION_2_3`) → `ConversionRequest.playlistIndex` → `--playlist-items <n>` on the download command only. A single video carries no index and no selection.

**Refusals.** Two new `ConversionFailure` codes, mapped in `ConversionFailureMapper.fromMessage` in this order (before the existing branches), from yt-dlp's own error texts:

| Code | Markers (lowercase substring) | Hebrew reason |
|---|---|---|
| `SITE_BLOCKED("site_blocked")` | `rate-limit`, `rate limit`, `too many requests`, `http error 429`, `ip address is blocked` | `האתר חוסם כרגע הורדות מהמכשיר, נסו שוב מאוחר יותר` |
| `NO_VIDEO("no_video")` | `no video in this post`, `no video could be found`, `is not a video` | `בקישור הזה אין סרטון` |

`SITE_BLOCKED` is checked before `LOGIN_REQUIRED` because Instagram's rate-limit text also carries yt-dlp's login hint. `LOGIN_REQUIRED` gains the markers `requires authentication`, `not authorized`, `log in` and `permission to view`.

**Metadata failures explain themselves.** The home screen's metadata error, today always "לא ניתן למצוא מידע על הסרטון", shows the same Hebrew reason the failed-download message would show for that failure (login wall, site block, no video, offline, site changed), and the generic sentence only when there is no specific reason.

**Copy.**

- Text field placeholder (`paste_url_hint`): `הדבק קישור לסרטון`
- Home screen subtitle: `הורדה קלה ומהירה מיוטיוב, פייסבוק, אינסטגרם, טיקטוק ו-X`
- Invalid link sentence: `הקישור אינו קישור נתמך מיוטיוב, פייסבוק, אינסטגרם, טיקטוק או X`

## Global Constraints

- All user-facing text is Hebrew. Failed-download sentences start with `ההורדה נכשלה` and contain no Latin letters (`DownloadFailureMessageTest` enforces this). Brand names in other copy may use Latin `X`.
- No new Gradle dependencies; yt-dlp is invoked only through `YtDlpRuntime`, and `--no-playlist` stays on every command.
- `SupportedPlatform.value` is lowercase and is persisted in `downloads.platform`; existing rows keep `youtube` / `facebook`.
- Unit tests live under `app/src/test/java/com/mediaconverter/app/`, JUnit 4, run with `./gradlew :app:testDebugUnitTest --offline -q` after `source /data/tools/media-converter/env.sh`.
- Full pre-merge check: `./gradlew :app:testDebugUnitTest :app:lintDebug :app:compileDebugAndroidTestKotlin :app:assembleDebug --offline -q`.
- No emulator or live network test is possible on this machine; live behaviour (Instagram's anonymous limits, TikTok's challenge pages) is checked on a device or through the CI manual live test.

## Review Focus

1. A TikTok short link shared with a trailing slash and a tracking query, `https://vm.tiktok.com/ZTR45GpSF/?utm_source=copy`, must be accepted as TikTok. (Task 1)
2. An X link to the second video of a multi-video post, `https://x.com/user/status/1813930232880697601/video/2`, must be accepted and passed through with the `/video/2` suffix intact. (Task 1)
3. Share text from the TikTok app puts the link mid-sentence with a trailing period; the link must still be found. (Task 1)
4. Instagram's rate-limit refusal carries the login hint; it must read as a site block, not as a private video. (Task 3)
5. A multi-item post's metadata is a playlist object; the thumbnail and duration must come from its first entry rather than showing nothing. (Task 2)

---

### Task 1: Accept Instagram, TikTok and X links that name one post

**Files:**
- Modify: `app/src/main/java/com/mediaconverter/app/data/MediaContracts.kt:3-6`
- Modify: `app/src/main/java/com/mediaconverter/app/data/MediaUrlParser.kt`
- Modify: `app/src/main/java/com/mediaconverter/app/data/db/DownloadEntity.kt:12` (comment only)
- Test: `app/src/test/java/com/mediaconverter/app/data/MediaUrlParserTest.kt`
- Test: `app/src/test/java/com/mediaconverter/app/data/ShareTextParserTest.kt`

**Interfaces:**
- Consumes: nothing new.
- Produces: `SupportedPlatform.INSTAGRAM`, `SupportedPlatform.TIKTOK`, `SupportedPlatform.X`; `MediaUrlParser.parse(text: String): NormalizedMediaUrl?` unchanged in signature, now returning those platforms.

- [ ] **Step 1: Write the failing tests**

In `MediaUrlParserTest`, add:

```kotlin
@Test
fun supportsInstagramTikTokAndXHostVariants() {
    val cases = mapOf(
        "https://www.instagram.com/reel/Chunk8-jurw/" to SupportedPlatform.INSTAGRAM,
        "https://instagram.com/p/aye83DjauH/?igsh=abc" to SupportedPlatform.INSTAGRAM,
        "https://www.instagram.com/tv/BkfuX9UB-eK/" to SupportedPlatform.INSTAGRAM,
        "https://www.instagram.com/reels/Cop84x6u7CP/" to SupportedPlatform.INSTAGRAM,
        "https://www.instagram.com/marvelskies.fc/reel/CWqAgUZgCku/" to SupportedPlatform.INSTAGRAM,
        "https://www.instagram.com/share/reel/_abc123" to SupportedPlatform.INSTAGRAM,
        "https://instagr.am/p/aye83DjauH/" to SupportedPlatform.INSTAGRAM,
        "https://www.tiktok.com/@hankgreen1/video/7047596209028074758" to SupportedPlatform.TIKTOK,
        "https://www.tiktok.com/@/video/7668090902816017671/" to SupportedPlatform.TIKTOK,
        "https://www.tiktok.com/t/ZTRC5xgJp" to SupportedPlatform.TIKTOK,
        "https://vm.tiktok.com/ZTR45GpSF/?utm_source=copy" to SupportedPlatform.TIKTOK,
        "https://vt.tiktok.com/ZSe4FqkKd" to SupportedPlatform.TIKTOK,
        "https://www.tiktok.com/share/video/7668090902816017671/" to SupportedPlatform.TIKTOK,
        "https://www.tiktokv.com/share/video/7668090902816017671/" to SupportedPlatform.TIKTOK,
        "https://www.tiktok.com/embed/7047596209028074758" to SupportedPlatform.TIKTOK,
        "https://x.com/CBSNews/status/1813930232880697601?s=46&t=abc" to SupportedPlatform.X,
        "https://twitter.com/starwars/status/665052190608723968" to SupportedPlatform.X,
        "https://mobile.twitter.com/i/web/status/910031516746514432" to SupportedPlatform.X,
        "https://x.com/i/status/1813930232880697601" to SupportedPlatform.X,
        "https://x.com/statuses/665052190608723968" to SupportedPlatform.X,
    )

    cases.forEach { (url, platform) ->
        assertEquals(url, platform, MediaUrlParser.parse(url)?.platform)
    }
}

@Test
fun keepsTheVideoIndexOfAMultiVideoPost() {
    val result = MediaUrlParser.parse("https://x.com/user/status/1813930232880697601/video/2")

    assertEquals("https://x.com/user/status/1813930232880697601/video/2", result?.value)
    assertEquals(SupportedPlatform.X, result?.platform)
}

@Test
fun rejectsSocialPagesThatAreNotASinglePost() {
    val cases = listOf(
        "https://www.instagram.com/porsche/",
        "https://www.instagram.com/explore/tags/lolcats/",
        "https://www.instagram.com/stories/fruits_zipper/3570766765028588805/",
        "https://www.instagram.com/reels/audio/123/",
        "https://www.instagram.com/share/",
        "https://www.instagram.com/",
        "https://www.tiktok.com/@hankgreen1",
        "https://www.tiktok.com/@hankgreen1/photo/7047596209028074758",
        "https://www.tiktok.com/@hankgreen1/live",
        "https://www.tiktok.com/tag/funny",
        "https://www.tiktok.com/music/original-sound-123",
        "https://vm.tiktok.com/",
        "https://x.com/CBSNews",
        "https://x.com/home",
        "https://x.com/search?q=cats",
        "https://x.com/i/spaces/1OwxWwQDWvzxQ",
        "https://x.com/i/broadcasts/1abc",
        "https://x.com/CBSNews/status/",
        "https://x.com/CBSNews/status/not-a-number",
        "https://t.co/abc123",
    )

    cases.forEach { value -> assertNull(value, MediaUrlParser.parse(value)) }
}
```

Extend the existing `rejectsLookalikeAndUnsupportedHosts` list with:

```kotlin
"https://tiktok.com.evil.example/@a/video/1",
"https://x.com.evil.example/a/status/1",
"https://instagram.com.evil.example/p/abc",
```

In `ShareTextParserTest`, add:

```kotlin
@Test
fun findsTheLinkInsideTheTikTokShareSentence() {
    val result = ShareTextParser.parse(
        action = "android.intent.action.SEND",
        mimeType = "text/plain",
        text = "Check out this video! https://vm.tiktok.com/ZMabc123/.",
    )

    assertEquals("https://vm.tiktok.com/ZMabc123/", result?.value)
    assertEquals(SupportedPlatform.TIKTOK, result?.platform)
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --offline -q --tests 'com.mediaconverter.app.data.MediaUrlParserTest' --tests 'com.mediaconverter.app.data.ShareTextParserTest'`
Expected: compilation FAILS with unresolved references `INSTAGRAM`, `TIKTOK`, `X`.

- [ ] **Step 3: Add the three enum entries**

In `MediaContracts.kt`, extend `SupportedPlatform` with `INSTAGRAM("instagram")`, `TIKTOK("tiktok")`, `X("x")`. Update the comment on `DownloadEntity.platform` to list the five values.

- [ ] **Step 4: Run the tests again to verify they now fail on behaviour**

Same command. Expected: `supportsInstagramTikTokAndXHostVariants` FAILS with `expected:<INSTAGRAM> but was:<null>` for the first case; `rejectsSocialPagesThatAreNotASinglePost` passes already (unknown hosts are rejected today) and stays as the guard.

- [ ] **Step 5: Implement host matching and the single-post rules in `MediaUrlParser`**

Keep `parse` and `normalize` as they are. Replace the platform `when` with a host-to-platform lookup over the table in the Design section (a private `fun platformOf(host: String): SupportedPlatform?`), and replace the YouTube-only check with `private fun isSinglePost(platform: SupportedPlatform, host: String, uri: URI): Boolean` that dispatches to `isYoutubeVideo` (unchanged), `isInstagramPost(segments)`, `isTikTokPost(host, segments)` and `isXPost(segments)`, each implementing the Design rules. Facebook keeps returning true. Add a KDoc on the rule in the same voice as the YouTube one.

- [ ] **Step 6: Run the parser and share-text tests to verify they pass**

Same command. Expected: all tests in both classes PASS.

- [ ] **Step 7: Run the whole unit suite**

Run: `./gradlew :app:testDebugUnitTest --offline -q`
Expected: PASS (an exhaustive `when` over `SupportedPlatform` elsewhere would surface here; there is none today).

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/com/mediaconverter/app/data/MediaContracts.kt app/src/main/java/com/mediaconverter/app/data/MediaUrlParser.kt app/src/main/java/com/mediaconverter/app/data/db/DownloadEntity.kt app/src/test/java/com/mediaconverter/app/data/MediaUrlParserTest.kt app/src/test/java/com/mediaconverter/app/data/ShareTextParserTest.kt
git commit -m "feat: accept Instagram, TikTok and X links that name one post"
```

### Task 2: Read the first item of a multi-item post

**Files:**
- Modify: `app/src/main/java/com/mediaconverter/app/data/DownloadEngine.kt:20-35`
- Modify: `app/src/main/java/com/mediaconverter/app/data/YtDlpRuntime.kt:93-107`
- Modify: `app/src/main/java/com/mediaconverter/app/data/ConversionEngine.kt` (`YtDlpDownloadCommandFactory.create`, the `common` list)
- Test: `app/src/test/java/com/mediaconverter/app/data/VideoInfoParserTest.kt` (create)
- Test: `app/src/test/java/com/mediaconverter/app/data/YtDlpRuntimeTest.kt:20-31`
- Test: `app/src/test/java/com/mediaconverter/app/data/YtDlpDownloadCommandFactoryTest.kt`

**Interfaces:**
- Consumes: `SupportedPlatform` from Task 1.
- Produces: `internal fun parseVideoInfo(stdout: String, platform: SupportedPlatform): VideoInfo` in `DownloadEngine.kt`, used by `DownloadEngine.getVideoInfo`.

- [ ] **Step 1: Write the failing tests**

Create `VideoInfoParserTest`:

```kotlin
class VideoInfoParserTest {
    @Test
    fun readsTitleThumbnailAndDurationOfASingleVideo() {
        val info = parseVideoInfo(
            """{"title":"Hank","thumbnail":"https://img/1.jpg","duration":12}""",
            SupportedPlatform.TIKTOK,
        )

        assertEquals(VideoInfo("Hank", "https://img/1.jpg", 12L, "tiktok"), info)
    }

    @Test
    fun fallsBackToTheFirstEntryOfAMultiItemPost() {
        val info = parseVideoInfo(
            """{"_type":"playlist","title":"Carousel","entries":[
                 {"title":"First","thumbnail":"https://img/first.jpg","duration":7},
                 {"title":"Second","thumbnail":"https://img/second.jpg","duration":9}]}""",
            SupportedPlatform.INSTAGRAM,
        )

        assertEquals(VideoInfo("Carousel", "https://img/first.jpg", 7L, "instagram"), info)
    }

    @Test
    fun defaultsTheTitleWhenNothingNamesTheVideo() {
        val info = parseVideoInfo("""{"entries":[{"duration":3}]}""", SupportedPlatform.X)

        assertEquals("סרטון", info.title)
        assertEquals("", info.thumbnailUrl)
        assertEquals(3L, info.duration)
    }
}
```

In `YtDlpRuntimeTest.metadataCommandIsBoundedAndTransportSecurityRemainsEnabled`, extend the `containsAll` list with `"--playlist-items", "1"`.

In `YtDlpDownloadCommandFactoryTest`, add:

```kotlin
@Test
fun downloadsOnlyTheFirstItemOfAMultiItemPost() {
    val request = ConversionRequest(
        1, NormalizedMediaUrl("https://www.instagram.com/p/carousel/", SupportedPlatform.INSTAGRAM), OutputFormat.MP4,
    )

    val arguments = YtDlpDownloadCommandFactory.create(request, "/tmp/source.%(ext)s")

    assertEquals("1", arguments.valueAfter("--playlist-items"))
    assertTrue(arguments.contains("--no-playlist"))
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --offline -q --tests 'com.mediaconverter.app.data.VideoInfoParserTest' --tests 'com.mediaconverter.app.data.YtDlpRuntimeTest' --tests 'com.mediaconverter.app.data.YtDlpDownloadCommandFactoryTest'`
Expected: compilation FAILS on `parseVideoInfo`; after a stub, the runtime and factory tests FAIL on the missing `--playlist-items`.

- [ ] **Step 3: Implement `parseVideoInfo` and add `--playlist-items 1`**

`parseVideoInfo(stdout: String, platform: SupportedPlatform): VideoInfo` in `DownloadEngine.kt`: parse with `org.json.JSONObject`; take `entries[0]` as the fallback object; a string field is the top-level value when non-blank, else the entry's; `duration` is the top-level value when positive, else the entry's, else `0L`; the title defaults to `"סרטון"`. `DownloadEngine.getVideoInfo` calls it. Add `"--playlist-items", "1"` after `"--no-playlist"` in `YtDlpRuntime.metadata` and in `YtDlpDownloadCommandFactory.create`'s `common` list, with a one-line comment naming carousels and multi-video posts.

- [ ] **Step 4: Run the three test classes, then the whole suite**

Expected: PASS, and `./gradlew :app:testDebugUnitTest --offline -q` PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/mediaconverter/app/data/DownloadEngine.kt app/src/main/java/com/mediaconverter/app/data/YtDlpRuntime.kt app/src/main/java/com/mediaconverter/app/data/ConversionEngine.kt app/src/test/java/com/mediaconverter/app/data/VideoInfoParserTest.kt app/src/test/java/com/mediaconverter/app/data/YtDlpRuntimeTest.kt app/src/test/java/com/mediaconverter/app/data/YtDlpDownloadCommandFactoryTest.kt
git commit -m "feat: fetch only the first item of a carousel or multi-video post"
```

### Task 3: Classify site blocks and posts without a video

**Files:**
- Modify: `app/src/main/java/com/mediaconverter/app/data/MediaContracts.kt:25-35` and `:59-74`
- Modify: `app/src/main/java/com/mediaconverter/app/viewmodel/HomeViewModel.kt:340-353`
- Test: `app/src/test/java/com/mediaconverter/app/data/ConversionFailureMapperTest.kt`
- Test: `app/src/test/java/com/mediaconverter/app/viewmodel/DownloadFailureMessageTest.kt`

**Interfaces:**
- Produces: `ConversionFailure.SITE_BLOCKED("site_blocked")`, `ConversionFailure.NO_VIDEO("no_video")`; `downloadFailureMessage(errorCode: String): String` unchanged in signature, now covering the two codes.

- [ ] **Step 1: Write the failing tests**

In `ConversionFailureMapperTest`, add (messages are yt-dlp's own, with the login hint it appends):

```kotlin
@Test
fun classifiesSiteBlocksAheadOfTheLoginHintTheyCarry() {
    assertEquals(
        ConversionFailure.SITE_BLOCKED,
        ConversionFailureMapper.fromMessage(
            "ERROR: [Instagram] Cabc: The webpage request was redirected to the login page. " +
                "You have exceeded the rate-limit for accessing posts anonymously. " +
                "Use --cookies-from-browser or --cookies for the authentication",
        ),
    )
    assertEquals(
        ConversionFailure.SITE_BLOCKED,
        ConversionFailureMapper.fromMessage("ERROR: [TikTok] 123: Your IP address is blocked from accessing this post"),
    )
    assertEquals(ConversionFailure.SITE_BLOCKED, ConversionFailureMapper.fromMessage("HTTP Error 429: Too Many Requests"))
}

@Test
fun classifiesPostsWithoutAVideo() {
    assertEquals(ConversionFailure.NO_VIDEO, ConversionFailureMapper.fromMessage("ERROR: [Instagram] Cabc: There is no video in this post"))
    assertEquals(ConversionFailure.NO_VIDEO, ConversionFailureMapper.fromMessage("ERROR: [twitter] 123: No video could be found in this tweet"))
    assertEquals(ConversionFailure.NO_VIDEO, ConversionFailureMapper.fromMessage("ERROR: [twitter] 123: Media #2 is not a video"))
}

@Test
fun classifiesSocialLoginWalls() {
    assertEquals(
        ConversionFailure.LOGIN_REQUIRED,
        ConversionFailureMapper.fromMessage("ERROR: [twitter] 123: NSFW tweet requires authentication. Use --cookies-from-browser or --cookies for the authentication"),
    )
    assertEquals(
        ConversionFailure.LOGIN_REQUIRED,
        ConversionFailureMapper.fromMessage("ERROR: [TikTok] 123: You do not have permission to view this post. Log into an account that has access"),
    )
    assertEquals(
        ConversionFailure.LOGIN_REQUIRED,
        ConversionFailureMapper.fromMessage("ERROR: [twitter] 123: You are not authorized to view this protected tweet"),
    )
}
```

In `DownloadFailureMessageTest.distinguishesTheReasonsAUserCanActOn`, add:

```kotlin
assertTrue(downloadFailureMessage("site_blocked").contains("חוסם"))
assertTrue(downloadFailureMessage("no_video").contains("אין סרטון"))
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --offline -q --tests 'com.mediaconverter.app.data.ConversionFailureMapperTest' --tests 'com.mediaconverter.app.viewmodel.DownloadFailureMessageTest'`
Expected: compilation FAILS on `SITE_BLOCKED` / `NO_VIDEO`.

- [ ] **Step 3: Add the codes, the mapper branches and the Hebrew reasons**

Add the two enum entries. In `fromMessage`, insert the `SITE_BLOCKED` branch first and the `NO_VIDEO` branch second, with the markers from the Design table; extend the `LOGIN_REQUIRED` list with `"requires authentication"`, `"not authorized"`, `"log in"`, `"permission to view"`. In `downloadFailureMessage`, add the two reasons from the Design table (the `when` is exhaustive, so the build forces this).

- [ ] **Step 4: Run the two test classes, then the whole suite**

Expected: PASS; `everyFailureCodeHasAHebrewExplanation` covers the new codes automatically.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/mediaconverter/app/data/MediaContracts.kt app/src/main/java/com/mediaconverter/app/viewmodel/HomeViewModel.kt app/src/test/java/com/mediaconverter/app/data/ConversionFailureMapperTest.kt app/src/test/java/com/mediaconverter/app/viewmodel/DownloadFailureMessageTest.kt
git commit -m "feat: name site blocks and video-less posts in the failure messages"
```

### Task 4: Metadata failures explain the reason

**Files:**
- Modify: `app/src/main/java/com/mediaconverter/app/viewmodel/HomeViewModel.kt:178-199` and `:335-353`
- Test: `app/src/test/java/com/mediaconverter/app/viewmodel/HomeViewModelTest.kt`
- Test: `app/src/test/java/com/mediaconverter/app/viewmodel/DownloadFailureMessageTest.kt`

**Interfaces:**
- Consumes: `ConversionFailureMapper.fromThrowable(failure: Throwable): ConversionFailure`, `ConversionFailure.SITE_BLOCKED` from Task 3.
- Produces: `internal fun failureReason(failure: ConversionFailure?): String?` (the Hebrew reason or null for the generic cases) and `internal fun metadataFailureMessage(failure: Throwable): String`, both in `HomeViewModel.kt`; `downloadFailureMessage` is rewritten on top of `failureReason` with the same output.

- [ ] **Step 1: Write the failing tests**

In `DownloadFailureMessageTest`, add:

```kotlin
@Test
fun metadataFailuresShareTheDownloadReasons() {
    val blocked = metadataFailureMessage(
        YtDlpException("ERROR: [Instagram] Cabc: You have exceeded the rate-limit for accessing posts anonymously. Use --cookies"),
    )
    val private = metadataFailureMessage(YtDlpException("ERROR: [twitter] 1: NSFW tweet requires authentication"))
    val unknown = metadataFailureMessage(IllegalStateException("temporary failure"))

    assertTrue(blocked.contains("חוסם"))
    assertTrue(private.contains("פרטי"))
    assertEquals("לא ניתן למצוא מידע על הסרטון", unknown)
}
```

In `HomeViewModelTest`, add:

```kotlin
@Test
fun metadataFailureExplainsTheReasonInHebrew() = runTest(mainDispatcherRule.dispatcher) {
    val provider = object : MediaInfoProvider {
        override suspend fun getVideoInfo(url: NormalizedMediaUrl): Result<VideoInfo> =
            Result.failure(YtDlpException("ERROR: [Instagram] Cabc: There is no video in this post"))
    }
    val viewModel = HomeViewModel(provider)

    viewModel.onUrlChange("https://www.instagram.com/p/Cabc/")
    advanceTimeBy(600)
    advanceUntilIdle()

    val error = viewModel.uiState.value.linkState as LinkUiState.Error
    assertEquals("בקישור הזה אין סרטון", error.message)
}
```

(`YtDlpException` is in `com.mediaconverter.app.data`; import it in both test files.)

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --offline -q --tests 'com.mediaconverter.app.viewmodel.DownloadFailureMessageTest' --tests 'com.mediaconverter.app.viewmodel.HomeViewModelTest'`
Expected: compilation FAILS on `metadataFailureMessage`; the view-model test then FAILS with the generic sentence.

- [ ] **Step 3: Implement `failureReason`, `metadataFailureMessage`, and use it in `loadMetadata`**

Extract the `when` body of `downloadFailureMessage` into `failureReason(failure: ConversionFailure?): String?`; `downloadFailureMessage` keeps its output. `metadataFailureMessage(failure: Throwable): String` = `failureReason(ConversionFailureMapper.fromThrowable(failure)) ?: "לא ניתן למצוא מידע על הסרטון"`. In `loadMetadata`, the failure branch becomes `LinkUiState.Error(url, metadataFailureMessage(result.exceptionOrNull()!!))` (the `Result` is a failure there, so the exception is present; use `?: IllegalStateException("metadata failed")` rather than `!!` if you prefer).

- [ ] **Step 4: Run the two test classes, then the whole suite**

Expected: PASS, including the existing `failedDownloadExplainsTheReasonInHebrewInsteadOfRawDiagnostics` and `sharingErroredUrlAgainStartsAFreshMetadataRequest`.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/mediaconverter/app/viewmodel/HomeViewModel.kt app/src/test/java/com/mediaconverter/app/viewmodel/HomeViewModelTest.kt app/src/test/java/com/mediaconverter/app/viewmodel/DownloadFailureMessageTest.kt
git commit -m "feat: explain in Hebrew why a site refused the metadata lookup"
```

### Task 5: Name all five sites in the copy

**Files:**
- Modify: `app/src/main/java/com/mediaconverter/app/viewmodel/HomeViewModel.kt:52-57`
- Modify: `app/src/main/res/values/strings.xml:6`
- Modify: `app/src/main/java/com/mediaconverter/app/ui/screens/HomeScreen.kt:81-86`
- Test: `app/src/test/java/com/mediaconverter/app/viewmodel/HomeViewModelTest.kt`

**Interfaces:** none new.

- [ ] **Step 1: Write the failing test**

In `HomeViewModelTest`, add:

```kotlin
@Test
fun invalidLinkMessageNamesEverySupportedSite() = runTest(mainDispatcherRule.dispatcher) {
    val viewModel = HomeViewModel(FakeMediaInfoProvider())

    viewModel.onUrlChange("https://vimeo.com/123")
    advanceUntilIdle()

    val message = viewModel.uiState.value.error.orEmpty()
    listOf("יוטיוב", "פייסבוק", "אינסטגרם", "טיקטוק", "X").forEach { site ->
        assertTrue(site, message.contains(site))
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --offline -q --tests 'com.mediaconverter.app.viewmodel.HomeViewModelTest'`
Expected: FAILS on `יוטיוב` (today's sentence spells the sites in Latin letters and names two).

- [ ] **Step 3: Change the three pieces of copy**

Exactly the strings from the Design section: the `Invalid` branch of `HomeUiState.error`, `paste_url_hint` in `strings.xml`, and the subtitle `Text` in `HomeScreen`. These two resource and Compose strings have no unit test; `lintDebug` and `assembleDebug` in Task 6 check them.

- [ ] **Step 4: Run the test class, then the whole suite**

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/mediaconverter/app/viewmodel/HomeViewModel.kt app/src/main/res/values/strings.xml app/src/main/java/com/mediaconverter/app/ui/screens/HomeScreen.kt app/src/test/java/com/mediaconverter/app/viewmodel/HomeViewModelTest.kt
git commit -m "feat: name Instagram, TikTok and X in the home screen copy"
```

### Task 6: Full verification

**Files:** none.

- [ ] **Step 1: Run the CI-equivalent check**

Run: `source /data/tools/media-converter/env.sh && ./gradlew :app:testDebugUnitTest :app:lintDebug :app:compileDebugAndroidTestKotlin :app:assembleDebug --offline -q`
Expected: exit 0, no output. If the Kotlin compiler reports an unresolved reference for a symbol that plainly exists, delete `app/build/kotlin` and `app/build/tmp/kotlin-classes` and rerun once.

- [ ] **Step 2: Confirm the debug APK exists**

Run: `ls -la app/build/outputs/apk/debug/app-debug.apk`
Expected: the file is listed with today's timestamp.

- [ ] **Step 3: Record what was not verified**

Live behaviour against the three sites (Instagram's anonymous limits, TikTok's challenge page, X's guest token) cannot be checked on this machine. It is checked by installing the debug APK on a phone or by running the Android CI workflow manually with `run_live` and a public Instagram, TikTok or X link as `live_url`.
