package neth.iecal.curbox

import android.media.session.MediaSession
import android.media.session.PlaybackState
import androidx.core.app.NotificationManagerCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import neth.iecal.curbox.services.ManagedMediaEnforcement
import neth.iecal.curbox.services.ManagedMediaNotificationListener
import neth.iecal.curbox.services.ManagedMediaTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real-device contract test. The owner must enable Curbox notification access before running it;
 * this test deliberately fails with an actionable message instead of silently skipping.
 */
@RunWith(AndroidJUnit4::class)
class ManagedMediaSessionDeviceTest {

    @Test
    fun enabledListenerPausesOnlyTargetedPlayingSessionAndNeverResumesIt() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertTrue(
            "Enable Curbox notification access in Android system settings before this test",
            NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)
        )

        val session = MediaSession(context, "managed-media-device-test")
        val controller = android.media.session.MediaController(context, session.sessionToken)
        try {
            session.isActive = true
            session.setPlaybackState(playingState())
            ManagedMediaNotificationListener.sendTarget(
                context,
                ManagedMediaTarget(appPackages = setOf(context.packageName))
            )

            assertTrue("Targeted session did not pause", awaitState(controller, PlaybackState.STATE_PAUSED))

            // Releasing the target never calls play. An explicit user/app resume remains playing.
            ManagedMediaNotificationListener.sendTarget(context, ManagedMediaTarget())
            session.setPlaybackState(playingState())
            assertTrue("Released session was unexpectedly resumed or changed", awaitState(controller, PlaybackState.STATE_PLAYING))

            // The pure policy guard also documents that unrelated packages are not selected.
            assertTrue(
                ManagedMediaEnforcement.playablePackagesToPause(
                    permissionAvailable = true,
                    target = ManagedMediaTarget(appPackages = setOf("com.example.blocked")),
                    sessions = listOf(neth.iecal.curbox.services.MediaSessionSnapshot(context.packageName, true))
                ).isEmpty()
            )
        } finally {
            session.isActive = false
            session.release()
        }
    }

    private fun playingState(): PlaybackState = PlaybackState.Builder()
        .setState(PlaybackState.STATE_PLAYING, 0L, 1f)
        .build()

    private fun awaitState(controller: android.media.session.MediaController, expected: Int): Boolean {
        val deadline = System.currentTimeMillis() + 3_000L
        while (System.currentTimeMillis() < deadline) {
            if (controller.playbackState?.state == expected) return true
            Thread.sleep(50L)
        }
        return controller.playbackState?.state == expected
    }
}
