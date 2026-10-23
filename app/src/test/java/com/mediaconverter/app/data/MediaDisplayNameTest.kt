package com.mediaconverter.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaDisplayNameTest {
    @Test
    fun keepsPeriodsThatArePartOfTheTitle() {
        assertEquals("Mr. Bean-42.mp4", sanitizeMediaDisplayName("Mr. Bean-42", OutputFormat.MP4))
        assertEquals("v1.0 teaser-3.mp3", sanitizeMediaDisplayName("v1.0 teaser-3", OutputFormat.MP3))
    }

    @Test
    fun doesNotDoubleAnExistingTargetExtension() {
        assertEquals("clip.mp4", sanitizeMediaDisplayName("clip.MP4", OutputFormat.MP4))
        assertEquals("song.mp3", sanitizeMediaDisplayName("song.mp3", OutputFormat.MP3))
    }

    @Test
    fun replacesCharactersThatBreakFileNames() {
        assertEquals(
            "a_b_c_d_e_f_g_h_i_j.mp4",
            sanitizeMediaDisplayName("a/b\\c:d*e?f\"g<h>i|j", OutputFormat.MP4),
        )
        assertEquals("tab_name.mp3", sanitizeMediaDisplayName("tab\tname", OutputFormat.MP3))
    }

    @Test
    fun blankTitlesFallBackToAGenericName() {
        assertEquals("media.mp4", sanitizeMediaDisplayName("   ", OutputFormat.MP4))
        assertEquals("media.mp3", sanitizeMediaDisplayName(".mp3", OutputFormat.MP3))
    }

    @Test
    fun fullLengthHebrewTitleWithIdSuffixSurvivesIntact() {
        val title = "א".repeat(100) + "-12345"

        assertEquals("$title.mp4", sanitizeMediaDisplayName(title, OutputFormat.MP4))
    }

    @Test
    fun boundsUtf8ByteLengthWithoutSplittingCharacters() {
        val title = "🎬".repeat(100) + "-77"

        val name = sanitizeMediaDisplayName(title, OutputFormat.MP4)

        assertTrue(name.endsWith(".mp4"))
        assertTrue(name.toByteArray(Charsets.UTF_8).size <= MAX_DISPLAY_NAME_BYTES)
        assertEquals(name, String(name.toByteArray(Charsets.UTF_8), Charsets.UTF_8))
        assertTrue(name.startsWith("🎬"))
    }
}
