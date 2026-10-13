package neth.iecal.curbox.services

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import neth.iecal.curbox.utils.DataStoreManager

/**
 * Selective managed-media adapter. Android starts this only after the owner enables notification
 * access in system settings. It never reads, stores, logs or forwards notification payloads.
 */
class ManagedMediaNotificationListener : NotificationListenerService() {

    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var sessionManager: MediaSessionManager? = null
    private var componentName: ComponentName? = null
    private var receiverRegistered = false
    private var targetPackages: Set<String> = emptySet()
    private val callbacks = mutableMapOf<MediaController, MediaController.Callback>()

    private val sessionsChangedListener =
        MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
            reconcile(controllers.orEmpty())
        }

    private val targetReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != ACTION_UPDATE_TARGETS) return
            targetPackages = intent.getStringArrayListExtra(EXTRA_TARGET_PACKAGES)?.toSet().orEmpty()
            reconcileActiveSessions()
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        componentName = ComponentName(this, javaClass)
        sessionManager = getSystemService(MediaSessionManager::class.java)
        try {
            ContextCompat.registerReceiver(
                this,
                targetReceiver,
                IntentFilter(ACTION_UPDATE_TARGETS),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            receiverRegistered = true
        } catch (error: Exception) {
            Log.e(TAG, "Unable to register managed media target receiver", error)
        }
        applicationContext.sendBroadcast(
            Intent(ACTION_REQUEST_TARGETS).setPackage(packageName)
        )

        runCatching {
            sessionManager?.addOnActiveSessionsChangedListener(
                sessionsChangedListener,
                componentName,
                handler
            )
        }.onFailure { error ->
            Log.e(TAG, "Managed media sessions are unavailable", error)
        }

        // Recover app targets after listener reconnect. Website targets are refreshed by the
        // trusted accessibility observation callback and are intentionally not persisted here.
        scope.launch {
            runCatching {
                val settings = DataStoreManager(applicationContext).settings.first()
                val target = ManagedMediaEnforcement.targetFor(settings, System.currentTimeMillis(), null)
                handler.post {
                    targetPackages = target.appPackages
                    reconcileActiveSessions()
                }
            }.onFailure { error ->
                Log.e(TAG, "Unable to recover managed media targets", error)
            }
        }
        reconcileActiveSessions()
    }

    override fun onListenerDisconnected() {
        clearSessions()
        targetPackages = emptySet()
        unregisterTargetReceiver()
        sessionManager = null
        componentName = null
        super.onListenerDisconnected()
    }

    override fun onDestroy() {
        clearSessions()
        unregisterTargetReceiver()
        scope.cancel()
        super.onDestroy()
    }

    private fun reconcileActiveSessions() {
        val manager = sessionManager ?: return
        val component = componentName ?: return
        runCatching { manager.getActiveSessions(component) }
            .onSuccess(::reconcile)
            .onFailure { error ->
                // Permission revocation can race this callback. Treat it as a safe no-op.
                targetPackages = emptySet()
                Log.w(TAG, "Managed media session access is unavailable", error)
            }
    }

    private fun reconcile(controllers: List<MediaController>) {
        val active = controllers.toSet()
        callbacks.keys.filterNot(active::contains).forEach { controller ->
            callbacks.remove(controller)?.let { callback ->
                runCatching { controller.unregisterCallback(callback) }
            }
        }
        controllers.forEach { controller ->
            if (controller !in callbacks) {
                val callback = object : MediaController.Callback() {
                    override fun onPlaybackStateChanged(state: android.media.session.PlaybackState?) {
                        pauseIfBlocked(controller)
                    }
                }
                callbacks[controller] = callback
                runCatching { controller.registerCallback(callback, handler) }
            }
            pauseIfBlocked(controller)
        }
    }

    private fun pauseIfBlocked(controller: MediaController) {
        if (controller.packageName !in targetPackages) return
        val state = controller.playbackState?.state ?: return
        if (state != android.media.session.PlaybackState.STATE_PLAYING &&
            state != android.media.session.PlaybackState.STATE_BUFFERING
        ) return
        runCatching { controller.transportControls.pause() }
            .onFailure { error -> Log.w(TAG, "Unable to pause managed media session", error) }
    }

    private fun clearSessions() {
        callbacks.forEach { (controller, callback) ->
            runCatching { controller.unregisterCallback(callback) }
        }
        callbacks.clear()
        runCatching {
            sessionManager?.removeOnActiveSessionsChangedListener(sessionsChangedListener)
        }
    }

    private fun unregisterTargetReceiver() {
        if (!receiverRegistered) return
        runCatching { unregisterReceiver(targetReceiver) }
        receiverRegistered = false
    }

    companion object {
        const val ACTION_UPDATE_TARGETS = "neth.iecal.curbox.managed_media.UPDATE_TARGETS"
        const val ACTION_REQUEST_TARGETS = "neth.iecal.curbox.managed_media.REQUEST_TARGETS"
        private const val EXTRA_TARGET_PACKAGES = "target_packages"
        private const val TAG = "ManagedMediaListener"

        fun sendTarget(context: Context, target: ManagedMediaTarget) {
            val packages = ArrayList<String>(target.appPackages + target.browserPackages)
            context.applicationContext.sendBroadcast(
                Intent(ACTION_UPDATE_TARGETS)
                    .setPackage(context.packageName)
                    .putStringArrayListExtra(EXTRA_TARGET_PACKAGES, packages)
            )
        }
    }
}
