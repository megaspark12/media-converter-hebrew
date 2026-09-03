package com.mediaconverter.app.data

object ShareTextParser {
    private const val ACTION_SEND = "android.intent.action.SEND"

    fun parse(action: String?, mimeType: String?, text: String?): NormalizedMediaUrl? {
        if (action != ACTION_SEND || mimeType != "text/plain" || text.isNullOrBlank()) return null
        return MediaUrlParser.parse(text)
    }
}
