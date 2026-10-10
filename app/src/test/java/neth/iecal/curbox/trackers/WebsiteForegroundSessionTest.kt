package neth.iecal.curbox.trackers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WebsiteForegroundSessionTest {
    private val brave = "com.brave.browser"
    private val chrome = "com.android.chrome"
    private val youtube = TrackedWebsite(brave, "youtube.com", "youtube.com/watch?v=1")

    @Test
    fun focusedApplicationWinsOverBrowserPictureInPicture() {
        val foreground = selectForegroundApplication(
            windows = listOf(
                WebsiteWindowEvidence(brave, isApplication = true),
                WebsiteWindowEvidence("com.example.reader", isApplication = true, isFocused = true)
            ),
            activeRootPackage = brave,
            activeRootIsApplication = true,
            isSupportedBrowser = { it == brave || it == chrome }
        )
        assertEquals("com.example.reader", foreground)
    }

    @Test
    fun unrelatedEventPackageCannotEndBrowserWithoutApplicationWindowEvidence() {
        val foreground = selectForegroundApplication(
            windows = emptyList(),
            activeRootPackage = brave,
            activeRootIsApplication = false,
            isSupportedBrowser = { it == brave || it == chrome }
        )
        val session = WebsiteForegroundSession().apply { observe(youtube, 0L) }

        session.onForegroundApplication(foreground!!, 6_800L)
        val transition = session.checkpoint(6_800L)

        assertEquals(youtube, session.currentWebsite())
        assertEquals(6_800L, transition.commits.single().durationMs)
    }

    @Test
    fun fullscreenAndSystemOverlayPreserveObservedSite() {
        val foreground = selectForegroundApplication(
            windows = listOf(
                WebsiteWindowEvidence("com.android.systemui", isApplication = false),
                WebsiteWindowEvidence(brave, isApplication = true)
            ),
            activeRootPackage = "com.android.systemui",
            activeRootIsApplication = false,
            isSupportedBrowser = { it == brave || it == chrome }
        )
        val session = WebsiteForegroundSession().apply { observe(youtube, 100L) }

        session.onForegroundApplication(foreground!!, 5_100L)

        assertEquals(youtube, session.currentWebsite())
        assertEquals(5_000L, session.checkpoint(5_100L).commits.single().durationMs)
    }

    @Test
    fun realApplicationLeaveFlushesAndClearsSite() {
        val session = WebsiteForegroundSession().apply { observe(youtube, 1_000L) }

        val transition = session.onForegroundApplication("com.example.reader", 4_000L)

        assertEquals(3_000L, transition.commits.single().durationMs)
        assertEquals(WebsiteObservationChange.Cleared, transition.observationChange)
        assertNull(session.currentWebsite())
    }

    @Test
    fun browserSwitchEndsOldSiteBeforeNewBrowserObservation() {
        val chromeSite = TrackedWebsite(chrome, "example.com", "example.com/article")
        val session = WebsiteForegroundSession().apply { observe(youtube, 0L) }

        val leave = session.onForegroundApplication(chrome, 2_000L)
        val observe = session.observe(chromeSite, 2_100L)

        assertEquals(2_000L, leave.commits.single().durationMs)
        assertEquals(WebsiteObservationChange.Cleared, leave.observationChange)
        assertEquals(WebsiteObservationChange.Observed(chromeSite), observe.observationChange)
        assertEquals(chromeSite, session.currentWebsite())
    }

    @Test
    fun screenOffDoesNotCountUntilSameBrowserIsConfirmedAfterResume() {
        val session = WebsiteForegroundSession().apply { observe(youtube, 1_000L) }

        val screenOff = session.onScreenOff(4_000L)
        session.onScreenOn()
        val beforeEvidence = session.checkpoint(20_000L)
        session.onForegroundApplication(brave, 21_000L)
        val resumed = session.checkpoint(23_000L)

        assertEquals(3_000L, screenOff.commits.single().durationMs)
        assertEquals(emptyList<WebsiteUsageCommit>(), beforeEvidence.commits)
        assertEquals(2_000L, resumed.commits.single().durationMs)
    }

    @Test
    fun repeatedCheckpointsNeverDoubleCount() {
        val session = WebsiteForegroundSession().apply { observe(youtube, 1_000L) }

        val first = session.checkpoint(3_500L)
        val sameInstant = session.checkpoint(3_500L)
        val second = session.checkpoint(5_000L)

        assertEquals(2_500L, first.commits.single().durationMs)
        assertEquals(emptyList<WebsiteUsageCommit>(), sameInstant.commits)
        assertEquals(1_500L, second.commits.single().durationMs)
    }

    @Test
    fun disablingFlushesOnceAndDoesNotResumeUntilEnabled() {
        val session = WebsiteForegroundSession().apply { observe(youtube, 1_000L) }

        val disabled = session.setTrackingEnabled(false, 4_000L)
        val whileDisabled = session.checkpoint(10_000L)
        session.setTrackingEnabled(true, 12_000L)
        val afterEnable = session.checkpoint(13_000L)

        assertEquals(3_000L, disabled.commits.single().durationMs)
        assertEquals(emptyList<WebsiteUsageCommit>(), whileDisabled.commits)
        assertEquals(1_000L, afterEnable.commits.single().durationMs)
    }

    @Test
    fun destroyFlushesOnceAndClearsTheObservation() {
        val session = WebsiteForegroundSession().apply { observe(youtube, 2_000L) }

        val destroyed = session.destroy(7_000L)
        val afterDestroy = session.checkpoint(9_000L)

        assertEquals(5_000L, destroyed.commits.single().durationMs)
        assertEquals(WebsiteObservationChange.Cleared, destroyed.observationChange)
        assertEquals(emptyList<WebsiteUsageCommit>(), afterDestroy.commits)
        assertNull(session.currentWebsite())
    }
}
