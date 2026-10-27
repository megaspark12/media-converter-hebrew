package com.mediaconverter.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class VideoInfoParserTest {
    @Test
    fun readsTitleThumbnailAndDurationOfASingleVideo() {
        val info = parseVideoInfo(
            """{"title":"Hank","thumbnail":"https://img/1.jpg","duration":12,"formats":[{"format_id":"h264"}]}""",
            SupportedPlatform.TIKTOK,
        )

        assertEquals(VideoInfo("Hank", "https://img/1.jpg", 12L, "tiktok"), info)
        assertNull(info.playlistIndex)
    }

    @Test
    fun picksTheFirstItemOfAMultiItemPostThatHasAVideo() {
        // An Instagram carousel lists photos and videos alike; a photo has no formats.
        val info = parseVideoInfo(
            """{"_type":"playlist","title":"Carousel","entries":[
                 {"title":"Photo","thumbnail":"https://img/photo.jpg","formats":[]},
                 {"title":"Clip","thumbnail":"https://img/clip.jpg","duration":7,"formats":[{"format_id":"dash"}]},
                 {"title":"Later","thumbnail":"https://img/later.jpg","duration":9,"formats":[{"format_id":"dash"}]}]}""",
            SupportedPlatform.INSTAGRAM,
        )

        assertEquals(VideoInfo("Carousel", "https://img/clip.jpg", 7L, "instagram", playlistIndex = 2), info)
    }

    @Test
    fun defaultsTheTitleWhenNothingNamesTheVideo() {
        val info = parseVideoInfo(
            """{"entries":[{"duration":3,"formats":[{"format_id":"1"}]}]}""",
            SupportedPlatform.X,
        )

        assertEquals("סרטון", info.title)
        assertEquals("", info.thumbnailUrl)
        assertEquals(3L, info.duration)
        assertEquals(1, info.playlistIndex)
    }

    @Test
    fun aPostWhoseItemsAreAllPhotosHasNoVideo() {
        val thrown = assertThrows(ConversionException::class.java) {
            parseVideoInfo(
                """{"_type":"playlist","title":"Photos","entries":[{"formats":[]},{"formats":[]}]}""",
                SupportedPlatform.INSTAGRAM,
            )
        }

        assertEquals(ConversionFailure.NO_VIDEO, thrown.failure)
    }

    @Test
    fun aPostWithoutItemsHasNoVideo() {
        val thrown = assertThrows(ConversionException::class.java) {
            parseVideoInfo("""{"_type":"playlist","title":"Empty","entries":[]}""", SupportedPlatform.X)
        }

        assertEquals(ConversionFailure.NO_VIDEO, thrown.failure)
    }

    @Test
    fun aSinglePostWithoutFormatsHasNoVideo() {
        val thrown = assertThrows(ConversionException::class.java) {
            parseVideoInfo("""{"title":"Photo","formats":[]}""", SupportedPlatform.INSTAGRAM)
        }

        assertEquals(ConversionFailure.NO_VIDEO, thrown.failure)
    }
}
