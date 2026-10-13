package neth.iecal.curbox

import android.media.session.MediaSession
import android.media.MediaMetadata
import android.media.session.PlaybackState
import android.media.session.MediaSessionManager
import android.content.ComponentName
import android.os.Handler
import android.os.Looper
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
        val allowedSession = MediaSession(context, "managed-media-allowed-same-browser")
        val allowedController =
            android.media.session.MediaController(context, allowedSession.sessionToken)
        val callback = object : MediaSession.Callback() {
            override fun onPause() {
                session.setPlaybackState(pausedState())
            }
        }
        val allowedCallback = object : MediaSession.Callback() {
            override fun onPause() {
                allowedSession.setPlaybackState(pausedState())
            }
        }
        try {
            session.setCallback(callback, Handler(Looper.getMainLooper()))
            allowedSession.setCallback(allowedCallback, Handler(Looper.getMainLooper()))
            session.isActive = true
            allowedSession.isActive = true
            session.setMetadata(metadata("target", "https://debug.example/watch"))
            allowedSession.setMetadata(metadata("allowed", "https://allowed.example/audio"))
            session.setPlaybackState(playingState())
            allowedSession.setPlaybackState(playingState())
            val unrelatedController = awaitUnrelatedController(context)
            val unrelatedState = unrelatedController.playbackState?.state
            val target = ManagedMediaTarget(
                browserPackages = setOf(context.packageName),
                browserDomains = setOf("debug.example")
            )

            assertTrue(
                "Targeted session did not pause",
                awaitTargetPaused(context, controller, target)
            )
            assertEquals(context.packageName, controller.packageName)
            assertEquals("target", controller.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE))
            assertTrue(
                "Allowed same-package session was changed",
                awaitState(allowedController, PlaybackState.STATE_PLAYING)
            )

            // Losing the URI releases the token too; a same-package URI-less session must not
            // be paused merely because the package and old token are still known.
            session.setMetadata(metadataWithoutUri("uncertain-track"))
            Thread.sleep(200L)
            session.setPlaybackState(playingState())
            assertTrue(
                "URI-less same-package track was changed",
                awaitState(controller, PlaybackState.STATE_PLAYING)
            )

            // A same-package track/tab transition to an allowed URI releases the token. A fresh
            // blocked URI may reacquire it only after the trusted target is dispatched again.
            session.setMetadata(metadata("allowed-track", "https://allowed.example/track"))
            Thread.sleep(200L)
            ManagedMediaNotificationListener.sendTarget(context, target)
            Thread.sleep(200L)
            session.setPlaybackState(playingState())
            assertTrue(
                "Allowed same-package track was changed",
                awaitState(controller, PlaybackState.STATE_PLAYING)
            )
            session.setMetadata(metadata("target-again", "https://debug.example/new"))
            Thread.sleep(200L)
            session.setPlaybackState(playingState())
            assertTrue("Fresh blocked track was not paused", awaitTargetPaused(context, controller, target))
            assertTrue(
                "Unrelated package session was changed",
                unrelatedController.playbackState?.state == unrelatedState
            )

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
            allowedSession.isActive = false
            allowedSession.release()
        }
    }

    private fun playingState(): PlaybackState = PlaybackState.Builder()
        .setState(PlaybackState.STATE_PLAYING, 0L, 1f)
        .build()

    private fun pausedState(): PlaybackState = PlaybackState.Builder()
        .setState(PlaybackState.STATE_PAUSED, 0L, 0f)
        .build()

    private fun metadata(title: String, mediaUri: String): MediaMetadata = MediaMetadata.Builder()
        .putString(MediaMetadata.METADATA_KEY_TITLE, title)
        .putString(MediaMetadata.METADATA_KEY_MEDIA_URI, mediaUri)
        .build()

    private fun metadataWithoutUri(title: String): MediaMetadata = MediaMetadata.Builder()
        .putString(MediaMetadata.METADATA_KEY_TITLE, title)
        .build()

    private fun awaitUnrelatedController(context: android.content.Context): android.media.session.MediaController {
        val manager = context.getSystemService(MediaSessionManager::class.java)
        val component = ComponentName(context, ManagedMediaNotificationListener::class.java)
        val deadline = System.currentTimeMillis() + 3_000L
        var packages = emptyList<String>()
        while (System.currentTimeMillis() < deadline) {
            val controllers = runCatching { manager.getActiveSessions(component) }.getOrDefault(emptyList())
            packages = controllers.map { it.packageName }
            controllers.firstOrNull { it.packageName != context.packageName }?.let { return it }
            Thread.sleep(50L)
        }
        throw AssertionError("No unrelated active media session was visible; packages=$packages")
    }

    private fun awaitState(controller: android.media.session.MediaController, expected: Int): Boolean {
        val deadline = System.currentTimeMillis() + 3_000L
        while (System.currentTimeMillis() < deadline) {
            if (controller.playbackState?.state == expected) return true
            Thread.sleep(50L)
        }
        return controller.playbackState?.state == expected
    }

    private fun awaitTargetPaused(
        context: android.content.Context,
        controller: android.media.session.MediaController,
        target: ManagedMediaTarget
    ): Boolean {
        // am instrument force-stops the target package before starting this test. Android may
        // therefore reconnect the separately hosted notification listener after its backoff.
        // The emulator's notification-listener rebind backoff is approximately 22 seconds
        // after that force-stop; keep the test bounded while allowing the real service to return.
        val deadline = System.currentTimeMillis() + 35_000L
        while (System.currentTimeMillis() < deadline) {
            ManagedMediaNotificationListener.sendTarget(context, target)
            if (controller.playbackState?.state == PlaybackState.STATE_PAUSED) return true
            Thread.sleep(50L)
        }
        return controller.playbackState?.state == PlaybackState.STATE_PAUSED
    }
}
