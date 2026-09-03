package com.mediaconverter.app.data

import org.junit.Assert.assertEquals
import org.junit.Test

class ConversionFailureMapperTest {
    @Test
    fun classifiesProviderAndPermanentFailures() {
        assertEquals(ConversionFailure.LOGIN_REQUIRED, ConversionFailureMapper.fromMessage("Sign in to confirm your age"))
        assertEquals(ConversionFailure.PROVIDER_CHANGED, ConversionFailureMapper.fromMessage("HTTP Error 403: Forbidden"))
        assertEquals(ConversionFailure.NO_COMPATIBLE_FORMAT, ConversionFailureMapper.fromMessage("Requested format is not available"))
    }

    @Test
    fun classifiesNetworkAndStorageFailures() {
        assertEquals(ConversionFailure.OFFLINE, ConversionFailureMapper.fromMessage("Unable to resolve host youtube.com"))
        assertEquals(ConversionFailure.STORAGE_FULL, ConversionFailureMapper.fromMessage("ENOSPC: No space left on device"))
    }
}
