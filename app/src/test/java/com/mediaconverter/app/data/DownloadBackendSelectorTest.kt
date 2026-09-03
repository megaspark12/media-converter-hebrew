package com.mediaconverter.app.data

import org.junit.Assert.assertEquals
import org.junit.Test

class DownloadBackendSelectorTest {
    @Test
    fun usesUserInitiatedJobsOnApi34AndNewer() {
        assertEquals(DownloadBackend.USER_INITIATED_JOB, DownloadBackendSelector.forApi(34))
        assertEquals(DownloadBackend.USER_INITIATED_JOB, DownloadBackendSelector.forApi(37))
    }

    @Test
    fun usesForegroundWorkerOnApi26Through33() {
        assertEquals(DownloadBackend.FOREGROUND_WORKER, DownloadBackendSelector.forApi(26))
        assertEquals(DownloadBackend.FOREGROUND_WORKER, DownloadBackendSelector.forApi(33))
    }
}
