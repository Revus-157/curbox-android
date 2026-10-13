package neth.iecal.curbox

import android.content.ComponentName
import android.content.Intent
import android.content.Context
import android.content.ServiceConnection
import android.os.IBinder
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import com.google.gson.Gson
import androidx.core.app.NotificationManagerCompat
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import neth.iecal.curbox.data.models.ManagedGroupPolicy
import neth.iecal.curbox.services.ManagedMediaNotificationListener
import neth.iecal.curbox.utils.DataStoreManager
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Real media-only lease path, isolated emulator package. No website observation or URI. */
class ManagedMediaPackageDeviceTest {
    @Test fun prepareMediaLeaseForProcessRestart() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(android.os.Build.MODEL.contains("sdk") && context.packageName.endsWith(".debug"))
        val policy = Gson().fromJson("""{"id":"restart","kind":"keyword_group","name":"Synthetic restart","members":["debug.example"],"expiresAtMs":${System.currentTimeMillis()+120000},"mediaPackages":["${context.packageName}"]}""", ManagedGroupPolicy::class.java)
        DataStoreManager(context).replaceManagedPoliciesForCaller("synthetic.restart.client", listOf(policy))
    }

    @Test fun retainedLeaseRecoversAfterRealProcessRestartWithoutObservation() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(android.os.Build.MODEL.contains("sdk") && context.packageName.endsWith(".debug"))
        val store = DataStoreManager(context)
        val owner = "synthetic.restart.client"
        val stored = runBlocking { store.settings.first() }
        assertTrue("Run prepareMediaLeaseForProcessRestart in a previous instrumentation process", stored.managedGroupPolicies.any { it.ownerPackage == owner && it.expiresAtMs > System.currentTimeMillis() })
        val session = MediaSession(context, "restart-without-uri")
        try {
            session.setCallback(object : MediaSession.Callback() {
                override fun onPause() { session.setPlaybackState(PlaybackState.Builder().setState(PlaybackState.STATE_PAUSED, 0L, 0f).build()) }
            }, Handler(Looper.getMainLooper()))
            session.isActive = true
            session.setPlaybackState(PlaybackState.Builder().setState(PlaybackState.STATE_PLAYING, 0L, 1f).build())
            val deadline = System.currentTimeMillis()+35000
            while (session.controller.playbackState?.state != PlaybackState.STATE_PAUSED && System.currentTimeMillis() < deadline) Thread.sleep(50)
            assertEquals("Restart must recover explicit media lease without URI or observation", PlaybackState.STATE_PAUSED, session.controller.playbackState?.state)
        } finally {
            runBlocking { store.replaceManagedPoliciesForCaller(owner, emptyList()) }
            session.isActive = false
            session.release()
        }
    }

    @Test fun explicitPackagePausesAllSessionsOnBackgroundLeaseAndReleasesWithoutAutoplay() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(android.os.Build.MODEL.contains("sdk") && context.packageName.endsWith(".debug"))
        assertTrue(NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName))
        val store = DataStoreManager(context)
        val owner = "synthetic.media.client"
        val sessions = listOf(MediaSession(context, "no-uri-first"), MediaSession(context, "no-uri-second"))
        val handler = Handler(Looper.getMainLooper())
        val manager = context.getSystemService(MediaSessionManager::class.java)
        val component = ComponentName(context, ManagedMediaNotificationListener::class.java)
        val connected = CountDownLatch(1)
        val fixtureConnection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) { connected.countDown() }
            override fun onServiceDisconnected(name: ComponentName?) {}
        }
        assertTrue(context.bindService(Intent().setComponent(ComponentName(context.packageName+".test", SyntheticPlayingMediaService::class.java.name)), fixtureConnection, Context.BIND_AUTO_CREATE))
        assertTrue("Synthetic other-package player did not start", connected.await(5, TimeUnit.SECONDS))
        val unrelated = manager.getActiveSessions(component).firstOrNull { it.packageName == context.packageName+".test" }
            ?: throw AssertionError("Synthetic other-package player was not visible")
        val unrelatedState = unrelated.playbackState?.state
        assertEquals("Other package must be actively playing", PlaybackState.STATE_PLAYING, unrelatedState)
        fun state(value: Int) = PlaybackState.Builder().setState(value, 0L, if (value == PlaybackState.STATE_PLAYING) 1f else 0f).build()
        fun await(expected: Int, timeout: Long = 5000): Boolean {
            val deadline = System.currentTimeMillis() + timeout
            while (System.currentTimeMillis() < deadline) {
                if (sessions.all { it.controller.playbackState?.state == expected }) return true
                Thread.sleep(50)
            }
            return sessions.all { it.controller.playbackState?.state == expected }
        }
        fun play() = sessions.forEach { it.setPlaybackState(state(PlaybackState.STATE_PLAYING)) }
        fun apply(expiry: Long) = runBlocking {
            val policy = Gson().fromJson("""{"id":"site","kind":"keyword_group","name":"Synthetic site","members":["debug.example"],"expiresAtMs":$expiry,"mediaPackages":["${context.packageName}"]}""", ManagedGroupPolicy::class.java)
            store.replaceManagedPoliciesForCaller(owner, listOf(policy))
        }
        try {
            runBlocking { store.replaceManagedPoliciesForCaller(owner, emptyList()) }
            sessions.forEach { session ->
                session.setCallback(object : MediaSession.Callback() {
                    override fun onPause() { session.setPlaybackState(state(PlaybackState.STATE_PAUSED)) }
                }, handler)
                session.isActive = true
            }
            play()
            assertTrue(await(PlaybackState.STATE_PLAYING))
            // A null/empty coordinator update must never erase the independently loaded lease.
            context.sendBroadcast(Intent(ManagedMediaNotificationListener.ACTION_UPDATE_TARGETS).setPackage(context.packageName))
            apply(System.currentTimeMillis() + 60000)
            assertTrue("Both URI-less already-playing sessions must pause", await(PlaybackState.STATE_PAUSED, 35000))
            assertEquals(unrelatedState, unrelated.playbackState?.state)
            context.sendBroadcast(Intent(ManagedMediaNotificationListener.ACTION_UPDATE_TARGETS).setPackage(context.packageName))
            play()
            assertTrue("Resume under lease must pause again", await(PlaybackState.STATE_PAUSED))
            runBlocking { store.replaceManagedPoliciesForCaller(owner, emptyList()) }
            Thread.sleep(300)
            assertTrue("Release must never autoplay", await(PlaybackState.STATE_PAUSED))
            play()
            Thread.sleep(300)
            assertTrue("Playback after release remains available", await(PlaybackState.STATE_PLAYING))
            apply(System.currentTimeMillis() + 1500)
            assertTrue(await(PlaybackState.STATE_PAUSED))
            Thread.sleep(2000)
            assertTrue("Expiry must never autoplay", await(PlaybackState.STATE_PAUSED))
            play()
            Thread.sleep(300)
            assertTrue("Playback after offline expiry remains available", await(PlaybackState.STATE_PLAYING))
            assertEquals(unrelatedState, unrelated.playbackState?.state)
        } finally {
            runBlocking { store.replaceManagedPoliciesForCaller(owner, emptyList()) }
            sessions.forEach { it.isActive = false; it.release() }
            context.unbindService(fixtureConnection)
        }
    }
}
