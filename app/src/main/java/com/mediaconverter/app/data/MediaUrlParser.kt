package com.mediaconverter.app.data

import java.net.URI

object MediaUrlParser {
    private val urlPattern = Regex(
        """(?<![A-Za-z0-9+.:\-])https?://[^\s<>\[\]{}()]+""",
        RegexOption.IGNORE_CASE,
    )
    private val trailingPunctuation = charArrayOf('.', ',', ';', ':', '!', '?', ')', ']', '}', '\'', '"')

    /** Path segments under which youtube.com serves a single video by id. */
    private val youtubeVideoSegments = setOf("shorts", "live", "embed", "v", "e", "clip")

    fun parse(text: String): NormalizedMediaUrl? {
        return urlPattern.findAll(text.trim())
            .map { it.value.trimEnd(*trailingPunctuation) }
            .mapNotNull(::normalize)
            .firstOrNull()
    }

    private fun normalize(candidate: String): NormalizedMediaUrl? {
        val uri = runCatching { URI(candidate) }.getOrNull() ?: return null
        val host = uri.host?.lowercase()?.trimEnd('.') ?: return null
        val platform = when {
            host == "youtu.be" || host == "youtube.com" || host.endsWith(".youtube.com") ->
                SupportedPlatform.YOUTUBE
            host == "fb.watch" || host == "fb.com" || host.endsWith(".fb.com") ||
                host == "facebook.com" || host.endsWith(".facebook.com") ->
                SupportedPlatform.FACEBOOK
            else -> return null
        }
        if (platform == SupportedPlatform.YOUTUBE && !isYoutubeVideo(host, uri)) return null
        val normalized = if (uri.scheme.equals("http", ignoreCase = true)) {
            URI(
                "https",
                uri.userInfo,
                uri.host,
                uri.port,
                uri.path,
                uri.query,
                uri.fragment,
            ).toASCIIString()
        } else if (uri.scheme.equals("https", ignoreCase = true)) {
            uri.toASCIIString()
        } else {
            return null
        }
        return NormalizedMediaUrl(normalized, platform)
    }

    /**
     * The YouTube hosts also serve playlist, channel, search and feed pages. Those are not videos:
     * yt-dlp would download every entry in them, each overwriting the previous one under the fixed
     * output name, and the metadata lookup would show the playlist title as if it were a video. A
     * video link that also names a playlist is fine; `--no-playlist` picks the video out of it.
     */
    private fun isYoutubeVideo(host: String, uri: URI): Boolean {
        val segments = uri.path.orEmpty().split('/').filter { it.isNotEmpty() }
        if (host == "youtu.be") return segments.isNotEmpty()
        return when (segments.firstOrNull()) {
            "watch", "watch_popup" -> hasQueryParameter(uri, "v")
            in youtubeVideoSegments -> segments.size >= 2
            else -> false
        }
    }

    private fun hasQueryParameter(uri: URI, name: String): Boolean =
        uri.rawQuery.orEmpty().split('&').any { it.length > name.length + 1 && it.startsWith("$name=") }
}
