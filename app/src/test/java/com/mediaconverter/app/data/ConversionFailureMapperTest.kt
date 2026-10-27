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

    @Test
    fun classifiesSiteBlocksAheadOfTheLoginHintTheyCarry() {
        assertEquals(
            ConversionFailure.SITE_BLOCKED,
            ConversionFailureMapper.fromMessage(
                "ERROR: [Instagram] Cabc: The webpage request was redirected to the login page. " +
                    "You have exceeded the rate-limit for accessing posts anonymously. " +
                    "Use --cookies-from-browser or --cookies for the authentication",
            ),
        )
        assertEquals(
            ConversionFailure.SITE_BLOCKED,
            ConversionFailureMapper.fromMessage("ERROR: [TikTok] 123: Your IP address is blocked from accessing this post"),
        )
        assertEquals(ConversionFailure.SITE_BLOCKED, ConversionFailureMapper.fromMessage("HTTP Error 429: Too Many Requests"))
    }

    @Test
    fun classifiesPostsWithoutAVideo() {
        assertEquals(ConversionFailure.NO_VIDEO, ConversionFailureMapper.fromMessage("ERROR: [Instagram] Cabc: There is no video in this post"))
        assertEquals(ConversionFailure.NO_VIDEO, ConversionFailureMapper.fromMessage("ERROR: [twitter] 123: No video could be found in this tweet"))
        assertEquals(ConversionFailure.NO_VIDEO, ConversionFailureMapper.fromMessage("ERROR: [twitter] 123: Media #2 is not a video"))
    }

    @Test
    fun classifiesSocialLoginWalls() {
        assertEquals(
            ConversionFailure.LOGIN_REQUIRED,
            ConversionFailureMapper.fromMessage(
                "ERROR: [twitter] 123: NSFW tweet requires authentication. Use --cookies-from-browser or --cookies for the authentication",
            ),
        )
        assertEquals(
            ConversionFailure.LOGIN_REQUIRED,
            ConversionFailureMapper.fromMessage(
                "ERROR: [TikTok] 123: You do not have permission to view this post. Log into an account that has access",
            ),
        )
        assertEquals(
            ConversionFailure.LOGIN_REQUIRED,
            ConversionFailureMapper.fromMessage("ERROR: [twitter] 123: You are not authorized to view this protected tweet"),
        )
    }
}
