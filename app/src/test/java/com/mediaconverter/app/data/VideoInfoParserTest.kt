package com.mediaconverter.app.data

import org.junit.Assert.assertEquals
import org.junit.Test

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
