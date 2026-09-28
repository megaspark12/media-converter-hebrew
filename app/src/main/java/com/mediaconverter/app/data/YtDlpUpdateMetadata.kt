package com.mediaconverter.app.data

import java.io.File
import java.net.HttpURLConnection
import java.net.URL

internal fun <T> withYtDlpUpdateMetadata(
    cacheDirectory: File,
    releaseUrl: String,
    readTimeoutMillis: Int = 10_000,
    install: (String) -> T,
): T {
    // The wrapper reads its channel URL with no timeout while holding the runtime
    // lock. Fetch it with bounded I/O first, then let the wrapper consume the local
    // copy and retain its version comparison, binary installation, and rollback.
    val metadata = File.createTempFile("yt-dlp-release-", ".json", cacheDirectory)
    try {
        val connection = URL(releaseUrl).openConnection().apply {
            connectTimeout = 5_000
            readTimeout = readTimeoutMillis
        }
        try {
            connection.getInputStream().use { input ->
                metadata.outputStream().use { output -> input.copyTo(output) }
            }
        } finally {
            (connection as? HttpURLConnection)?.disconnect()
        }
        if (Thread.currentThread().isInterrupted) throw InterruptedException("Update cancelled")
        return install(metadata.toURI().toString())
    } finally {
        metadata.delete()
    }
}
