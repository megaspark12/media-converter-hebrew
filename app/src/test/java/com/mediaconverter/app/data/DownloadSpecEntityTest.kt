package com.mediaconverter.app.data

import org.junit.Assert.assertEquals
import org.junit.Test

class DownloadSpecEntityTest {
    @Test
    fun persistsTheChosenItemAndZeroForASingleVideo() {
        val spec = DownloadSpec(
            source = NormalizedMediaUrl("https://www.instagram.com/p/carousel/", SupportedPlatform.INSTAGRAM),
            outputFormat = OutputFormat.MP4,
            quality = "best",
            title = "Carousel",
            thumbnailUrl = "https://img/clip.jpg",
            playlistIndex = 2,
        )

        val entity = spec.toEntity(ytDlpVersion = "2026.10.01")

        assertEquals(2, entity.playlistIndex)
        assertEquals("instagram", entity.platform)
        assertEquals("pending", entity.status)
        assertEquals("2026.10.01", entity.ytDlpVersion)
        assertEquals(0, spec.copy(playlistIndex = null).toEntity("").playlistIndex)
    }
}
