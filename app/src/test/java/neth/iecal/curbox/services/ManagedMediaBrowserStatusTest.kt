package neth.iecal.curbox.services

import org.junit.Assert.assertEquals
import org.junit.Test

class ManagedMediaBrowserStatusTest {
    @Test
    fun noActiveSupportedBrowserIsUnknown() {
        assertEquals(
            null,
            ManagedMediaBrowserStatus.browserMediaUriPresent(
                listOf(BrowserMediaSessionSnapshot("com.example.player", true))
            )
        )
    }

    @Test
    fun supportedBrowserWithoutValidUriHostIsFalse() {
        assertEquals(
            false,
            ManagedMediaBrowserStatus.browserMediaUriPresent(
                listOf(BrowserMediaSessionSnapshot("com.brave.browser", false))
            )
        )
    }

    @Test
    fun anySupportedBrowserWithValidUriHostIsTrue() {
        assertEquals(
            true,
            ManagedMediaBrowserStatus.browserMediaUriPresent(
                listOf(
                    BrowserMediaSessionSnapshot("com.brave.browser", false),
                    BrowserMediaSessionSnapshot("org.mozilla.firefox", true)
                )
            )
        )
    }
}
