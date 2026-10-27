package com.mediaconverter.app.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The acceptance rule is shared by the transcoder's per-attempt check and the publisher's final
 * gate, so an output is inspected once per gate instead of twice.
 */
class MediaInspectionTest {
    private val video = MediaInspection(
        sizeBytes = 1_024, durationMillis = 6_000, hasAudio = true, hasVideo = true,
        codecs = SourceCodecs("video/avc", "audio/mp4a-latm"),
    )
    private val audio = MediaInspection(sizeBytes = 1_024, durationMillis = 6_000, hasAudio = true, hasVideo = false)

    @Test
    fun mp4NeedsBothTracksWithDurationAndTheRightExtension() {
        assertTrue(video.isAcceptable("clip.mp4", OutputFormat.MP4))
        assertTrue(video.isAcceptable("CLIP.MP4", OutputFormat.MP4))
        assertFalse(video.copy(hasAudio = false).isAcceptable("clip.mp4", OutputFormat.MP4))
        assertFalse(video.copy(hasVideo = false).isAcceptable("clip.mp4", OutputFormat.MP4))
        assertFalse(video.copy(durationMillis = 0).isAcceptable("clip.mp4", OutputFormat.MP4))
        assertFalse(video.copy(sizeBytes = 0).isAcceptable("clip.mp4", OutputFormat.MP4))
        assertFalse(video.isAcceptable("clip.mkv", OutputFormat.MP4))
    }

    @Test
    fun mp3MustBeAudioOnly() {
        assertTrue(audio.isAcceptable("song.mp3", OutputFormat.MP3))
        assertFalse(video.isAcceptable("song.mp3", OutputFormat.MP3))
        assertFalse(audio.copy(hasAudio = false).isAcceptable("song.mp3", OutputFormat.MP3))
    }
}
