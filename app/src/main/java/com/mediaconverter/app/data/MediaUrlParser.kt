package com.mediaconverter.app.data

import java.net.URI

object MediaUrlParser {
    private val urlPattern = Regex(
        """(?<![A-Za-z0-9+.:\-])https?://[^\s<>\[\]{}()]+""",
        RegexOption.IGNORE_CASE,
    )
    private val trailingPunctuation = charArrayOf('.', ',', ';', ':', '!', '?', ')', ']', '}', '\'', '"')

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
}
