package com.snapfetch.app

import com.snapfetch.app.domain.model.SocialPlatform
import com.snapfetch.app.utils.UrlExtractor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class UrlExtractorTest {

    @Test
    fun extractTikTokUrl_fromSharedText_returnsCleanUrl() {
        val sharedText = "¡Mira este increíble video! https://vt.tiktok.com/ZS2xyz123/?utm_source=app #viral"
        val extracted = UrlExtractor.findSocialUrl(sharedText)

        assertNotNull(extracted)
        assertEquals("https://vt.tiktok.com/ZS2xyz123/", extracted)
        assertEquals(SocialPlatform.TIKTOK, SocialPlatform.fromUrl(extracted!!))
    }

    @Test
    fun extractInstagramUrl_fromSharedText_returnsCleanUrl() {
        val sharedText = "Mira este reel en Instagram: https://www.instagram.com/reel/C3x9abc123/?igsh=MWx123=="
        val extracted = UrlExtractor.findSocialUrl(sharedText)

        assertNotNull(extracted)
        assertEquals("https://www.instagram.com/reel/C3x9abc123/", extracted)
        assertEquals(SocialPlatform.INSTAGRAM, SocialPlatform.fromUrl(extracted!!))
    }

    @Test
    fun extractYouTubeShortsUrl_fromSharedText_returnsCleanUrl() {
        val sharedText = "Check this short https://youtube.com/shorts/kJQP7kiw5Fk?si=abcdef123"
        val extracted = UrlExtractor.findSocialUrl(sharedText)

        assertNotNull(extracted)
        assertEquals("https://youtube.com/shorts/kJQP7kiw5Fk", extracted)
        assertEquals(SocialPlatform.YOUTUBE, SocialPlatform.fromUrl(extracted!!))
    }

    @Test
    fun extractTwitterXUrl_fromSharedText_returnsCleanUrl() {
        val sharedText = "Breaking news capture: https://x.com/tech_insider/status/1789012345678901234?s=20"
        val extracted = UrlExtractor.findSocialUrl(sharedText)

        assertNotNull(extracted)
        assertEquals("https://x.com/tech_insider/status/1789012345678901234", extracted)
        assertEquals(SocialPlatform.TWITTER_X, SocialPlatform.fromUrl(extracted!!))
    }
}
