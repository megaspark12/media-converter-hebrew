package com.mediaconverter.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MediaUrlParserTest {
    @Test
    fun extractsYoutubeUrlFromSharedText() {
        val result = MediaUrlParser.parse("כדאי לצפות https://youtu.be/abc123?t=5 עכשיו")

        assertEquals("https://youtu.be/abc123?t=5", result?.value)
        assertEquals(SupportedPlatform.YOUTUBE, result?.platform)
    }

    @Test
    fun normalizesRecognizedHttpLinksToHttps() {
        val result = MediaUrlParser.parse("http://m.facebook.com/reel/12345")

        assertEquals("https://m.facebook.com/reel/12345", result?.value)
        assertEquals(SupportedPlatform.FACEBOOK, result?.platform)
    }

    @Test
    fun supportsYoutubeAndFacebookHostVariants() {
        val cases = mapOf(
            "https://youtube.com/watch?v=abc" to SupportedPlatform.YOUTUBE,
            "https://www.youtube.com/shorts/abc" to SupportedPlatform.YOUTUBE,
            "https://music.youtube.com/watch?v=abc" to SupportedPlatform.YOUTUBE,
            "https://www.youtube.com/live/abc" to SupportedPlatform.YOUTUBE,
            "https://www.youtube.com/embed/abc" to SupportedPlatform.YOUTUBE,
            "https://fb.watch/abc/" to SupportedPlatform.FACEBOOK,
            "https://www.facebook.com/watch/?v=123" to SupportedPlatform.FACEBOOK,
            "https://mobile.facebook.com/reel/123" to SupportedPlatform.FACEBOOK,
        )

        cases.forEach { (url, platform) ->
            assertEquals(url, platform, MediaUrlParser.parse(url)?.platform)
        }
    }

    @Test
    fun keepsAVideoLinkThatAlsoNamesAPlaylist() {
        // yt-dlp's --no-playlist picks the video out of such links, so they stay supported.
        val result = MediaUrlParser.parse("https://www.youtube.com/watch?v=abc&list=PL123&index=4")

        assertEquals(SupportedPlatform.YOUTUBE, result?.platform)
    }

    @Test
    fun rejectsYoutubePagesThatAreNotASingleVideo() {
        // A playlist, channel or search page is not a video: yt-dlp would download every entry
        // in it, each overwriting the previous one under the fixed output name, and the metadata
        // lookup would show the playlist title as if it were a video.
        val cases = listOf(
            "https://www.youtube.com/playlist?list=PL123",
            "https://www.youtube.com/channel/UC123",
            "https://www.youtube.com/@someone",
            "https://www.youtube.com/c/SomeChannel",
            "https://www.youtube.com/user/someone",
            "https://www.youtube.com/feed/subscriptions",
            "https://www.youtube.com/results?search_query=cats",
            "https://www.youtube.com/watch?list=PL123",
            "https://www.youtube.com/",
            "https://youtu.be/",
        )

        cases.forEach { value -> assertNull(value, MediaUrlParser.parse(value)) }
    }

    @Test
    fun rejectsLookalikeAndUnsupportedHosts() {
        val cases = listOf(
            "https://youtube.com.evil.example/watch?v=abc",
            "https://evil.example/?next=https://youtube.com/watch?v=abc",
            "javascript:https://youtu.be/abc",
            "https://vimeo.com/123",
            "https://tiktok.com.evil.example/@a/video/1",
            "https://x.com.evil.example/a/status/1",
            "https://instagram.com.evil.example/p/abc",
        )

        cases.forEach { value -> assertNull(value, MediaUrlParser.parse(value)) }
    }

    @Test
    fun stripsPunctuationAroundSharedUrl() {
        val result = MediaUrlParser.parse("Watch this: (https://www.facebook.com/reel/12345).")

        assertEquals("https://www.facebook.com/reel/12345", result?.value)
    }

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
}
