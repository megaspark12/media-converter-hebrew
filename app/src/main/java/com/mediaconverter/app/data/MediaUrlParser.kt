package com.mediaconverter.app.data

import java.net.URI

object MediaUrlParser {
    private val urlPattern = Regex(
        """(?<![A-Za-z0-9+.:\-])https?://[^\s<>\[\]{}()]+""",
        RegexOption.IGNORE_CASE,
    )
    private val trailingPunctuation = charArrayOf('.', ',', ';', ':', '!', '?', ')', ']', '}', '\'', '"')

    /** Hosts per platform. A host matches an entry when it is that host or a subdomain of it. */
    private val platformHosts = mapOf(
        SupportedPlatform.YOUTUBE to listOf("youtu.be", "youtube.com"),
        SupportedPlatform.FACEBOOK to listOf("fb.watch", "fb.com", "facebook.com"),
        SupportedPlatform.INSTAGRAM to listOf("instagram.com", "instagr.am"),
        SupportedPlatform.TIKTOK to listOf("tiktok.com", "tiktokv.com"),
        SupportedPlatform.X to listOf("x.com", "twitter.com"),
    )

    /** Path segments under which youtube.com serves a single video by id. */
    private val youtubeVideoSegments = setOf("shorts", "live", "embed", "v", "e", "clip")

    /** Path segments under which instagram.com serves a single post by shortcode. */
    private val instagramPostSegments = setOf("p", "reel", "reels", "tv")

    fun parse(text: String): NormalizedMediaUrl? {
        return urlPattern.findAll(text.trim())
            .map { it.value.trimEnd(*trailingPunctuation) }
            .mapNotNull(::normalize)
            .firstOrNull()
    }

    private fun normalize(candidate: String): NormalizedMediaUrl? {
        val uri = runCatching { URI(candidate) }.getOrNull() ?: return null
        val host = uri.host?.lowercase()?.trimEnd('.') ?: return null
        val platform = platformOf(host) ?: return null
        if (!isSinglePost(platform, host, uri)) return null
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

    private fun platformOf(host: String): SupportedPlatform? =
        platformHosts.entries
            .firstOrNull { (_, hosts) -> hosts.any { host == it || host.endsWith(".$it") } }
            ?.key

    /**
     * Every platform also serves pages that are not one video: playlists, channels, profiles,
     * hashtags, stories. Those are not videos: yt-dlp would download every entry in them, each
     * overwriting the previous one under the fixed output name, and the metadata lookup would
     * show the page title as if it were a video. So a link is accepted only when its path names
     * a single post.
     */
    private fun isSinglePost(platform: SupportedPlatform, host: String, uri: URI): Boolean {
        val segments = uri.path.orEmpty().split('/').filter { it.isNotEmpty() }
        return when (platform) {
            SupportedPlatform.YOUTUBE -> isYoutubeVideo(host, uri, segments)
            SupportedPlatform.FACEBOOK -> true
            SupportedPlatform.INSTAGRAM -> isInstagramPost(segments)
            SupportedPlatform.TIKTOK -> isTikTokPost(host, segments)
            SupportedPlatform.X -> isXPost(segments)
        }
    }

    /** A video link that also names a playlist is fine; `--no-playlist` picks the video out of it. */
    private fun isYoutubeVideo(host: String, uri: URI, segments: List<String>): Boolean {
        if (host == "youtu.be") return segments.isNotEmpty()
        return when (segments.firstOrNull()) {
            "watch", "watch_popup" -> hasQueryParameter(uri, "v")
            in youtubeVideoSegments -> segments.size >= 2
            else -> false
        }
    }

    /**
     * `/p/<code>`, `/reel/<code>`, `/reels/<code>` and `/tv/<code>`, optionally behind the author's
     * username. `/share/...` links are what the app's share button hands out; they redirect to one
     * post. `/reels/audio/<id>`, stories, explore pages and profiles are not posts.
     */
    private fun isInstagramPost(segments: List<String>): Boolean = when {
        segments.size < 2 -> false
        segments[0] == "share" -> true
        segments[0] in instagramPostSegments -> segments[1] != "audio"
        else -> segments.size >= 3 && segments[1] in instagramPostSegments
    }

    /**
     * `vm.tiktok.com/<code>` and `vt.tiktok.com/<code>` are the share-button short links. On the
     * other hosts `/t/<code>`, `/@<user>/video/<id>` (the user part may be a bare `@`),
     * `/share/video/<id>` and `/embed/<id>` name one video. Profiles, photo posts, live rooms,
     * tags and sounds do not.
     */
    private fun isTikTokPost(host: String, segments: List<String>): Boolean {
        if (host == "vm.tiktok.com" || host == "vt.tiktok.com") return segments.isNotEmpty()
        if (segments.size < 2) return false
        return when {
            segments[0] == "t" || segments[0] == "embed" -> true
            segments[0].startsWith("@") || segments[0] == "share" -> segments[1] == "video" && segments.size >= 3
            else -> false
        }
    }

    /**
     * `/<user>/status/<id>`, `/i/status/<id>`, `/i/web/status/<id>` and `/statuses/<id>` name one
     * post; a trailing `/video/<n>` picks one of its videos and is passed through to yt-dlp.
     * Profiles, home, search, Spaces and broadcasts are not posts.
     */
    private fun isXPost(segments: List<String>): Boolean {
        val id = when {
            segments.getOrNull(0) == "statuses" -> segments.getOrNull(1)
            segments.getOrNull(1) == "status" -> segments.getOrNull(2)
            segments.getOrNull(0) == "i" && segments.getOrNull(2) == "status" -> segments.getOrNull(3)
            else -> null
        }
        return !id.isNullOrEmpty() && id.all { it.isDigit() }
    }

    private fun hasQueryParameter(uri: URI, name: String): Boolean =
        uri.rawQuery.orEmpty().split('&').any { it.length > name.length + 1 && it.startsWith("$name=") }
}
