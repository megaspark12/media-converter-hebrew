package com.mediaconverter.app.viewmodel

import com.mediaconverter.app.data.ConversionFailure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadFailureMessageTest {
    @Test
    fun everyFailureCodeHasAHebrewExplanation() {
        ConversionFailure.entries
            .filter { it != ConversionFailure.CANCELLED }
            .forEach { failure ->
                val message = downloadFailureMessage(failure.code)
                assertTrue(failure.code, message.startsWith("ההורדה נכשלה"))
                assertFalse(failure.code, message.any { it in 'a'..'z' || it in 'A'..'Z' })
            }
    }

    @Test
    fun distinguishesTheReasonsAUserCanActOn() {
        assertTrue(downloadFailureMessage("offline").contains("אינטרנט"))
        assertTrue(downloadFailureMessage("storage_full").contains("אחסון"))
        assertTrue(downloadFailureMessage("login_required").contains("פרטי"))
        assertTrue(downloadFailureMessage("processing_failed").contains("המרה"))
    }

    @Test
    fun unknownAndLegacyCodesFallBackToAGenericMessage() {
        assertEquals(downloadFailureMessage(""), downloadFailureMessage("something_new"))
        assertEquals(downloadFailureMessage("download_failed"), downloadFailureMessage(""))
    }
}
