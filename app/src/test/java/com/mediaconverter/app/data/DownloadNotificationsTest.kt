package com.mediaconverter.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DownloadNotificationsTest {
    @Test
    fun theResultNotificationDoesNotShareTheSlotWorkManagerCancels() {
        // WorkManager's foreground dispatcher cancels the foreground notification by its bare id
        // as soon as the worker finishes. A completion or failure notice posted under that same
        // bare id disappeared with it on Android 8-13, so results need a slot of their own.
        val progress = DownloadNotifications.progressSlot(42)
        val result = DownloadNotifications.resultSlot(42)

        assertNull(progress.tag)
        assertNotEquals(progress, result)
    }

    @Test
    fun slotsStayDistinctAcrossDownloads() {
        assertNotEquals(DownloadNotifications.progressSlot(1), DownloadNotifications.progressSlot(2))
        assertNotEquals(DownloadNotifications.resultSlot(1), DownloadNotifications.resultSlot(2))
        assertEquals(DownloadNotifications.progressSlot(7).id, DownloadNotifications.notificationId(7))
    }
}
