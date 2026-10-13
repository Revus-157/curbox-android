package neth.iecal.curbox.services

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.session.MediaController
import android.media.MediaMetadata
import android.media.session.MediaSession
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
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import neth.iecal.curbox.api.ManagedMediaCapability
import neth.iecal.curbox.data.models.MANAGED_GROUP_PREFIX
import neth.iecal.curbox.data.models.Settings
import neth.iecal.curbox.data.models.effectiveManagedSettings
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
    private var coordinatorAppPackages: Set<String> = emptySet()
    private var managedAppPackages: Set<String>? = null
    private var browserTargetPackages: Set<String> = emptySet()
    private var browserBlockedDomains: Set<String> = emptySet()
    private var retainBrowserSessions = false
    private var browserAttributionSessionTokens: Set<MediaSession.Token> = emptySet()
    private var browserAttributionResolved = false
    private var browserAttributionExpiresAtMs: Long? = null
    private var targetGeneration = 0L
    private var settingsInitialized = false
    private var latestPolicySettings: Settings? = null
    private var settingsJob: kotlinx.coroutines.Job? = null
    private val callbacks = mutableMapOf<MediaController, MediaController.Callback>()

    private val policyExpiryCheck = object : Runnable {
        override fun run() {
            val settings = latestPolicySettings
            if (settings != null) {
                refreshManagedPolicy(settings, System.currentTimeMillis())
            }
        }
    }

    private val sessionsChangedListener =
        MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
            reconcile(controllers.orEmpty())
        }

    private val targetReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != ACTION_UPDATE_TARGETS) return
            targetGeneration += 1
            coordinatorAppPackages =
                intent.getStringArrayListExtra(EXTRA_APP_PACKAGES)?.toSet().orEmpty()
            val browserPackages =
                intent.getStringArrayListExtra(EXTRA_BROWSER_PACKAGES)?.toSet().orEmpty()
            browserBlockedDomains =
                intent.getStringArrayListExtra(EXTRA_BROWSER_DOMAINS)?.toSet().orEmpty()
            val retain = intent.getBooleanExtra(EXTRA_RETAIN_BROWSER_SESSIONS, false)
            if (retain) {
                // A background clear retains only sessions that were already attributed while
                // the blocked site was observed. A new session in the same browser package is
                // intentionally not selected by package name alone.
                browserAttributionSessionTokens = browserAttributionSessionTokens
                    .intersect(callbacks.keys.map { it.sessionToken }.toSet())
                browserAttributionResolved = true
            } else {
                browserAttributionSessionTokens = emptySet()
                browserAttributionResolved = false
            }
            browserTargetPackages = browserPackages
            retainBrowserSessions = retain
            browserAttributionExpiresAtMs = intent.getLongExtra(
                EXTRA_BROWSER_ATTRIBUTION_EXPIRES_AT_MS,
                NO_EXPIRY
            ).takeUnless { it == NO_EXPIRY }
            if (browserAttributionExpired(System.currentTimeMillis())) {
                clearBrowserAttribution()
            }
            reconcileActiveSessions()
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        componentName = ComponentName(this, javaClass)
        sessionManager = getSystemService(MediaSessionManager::class.java)
        startPolicyMonitoring()
        try {
            ContextCompat.registerReceiver(
                this,
                targetReceiver,
                IntentFilter(ACTION_UPDATE_TARGETS),
                null,
                null,
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            receiverRegistered = true
        } catch (error: Exception) {
            Log.e(TAG, "Unable to register managed media target receiver", error)
        }
        val requestGeneration = targetGeneration
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
                val target = ManagedMediaEnforcement.targetFor(
                    settings,
                    System.currentTimeMillis(),
                    null
                )
                handler.post {
                    if (targetGeneration != requestGeneration) return@post
                    coordinatorAppPackages = target.appPackages
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
        stopPolicyMonitoring()
        coordinatorAppPackages = emptySet()
        managedAppPackages = null
        clearBrowserAttribution()
        unregisterTargetReceiver()
        sessionManager = null
        componentName = null
        super.onListenerDisconnected()
    }

    override fun onDestroy() {
        clearSessions()
        stopPolicyMonitoring()
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
                coordinatorAppPackages = emptySet()
                clearBrowserAttribution()
                Log.w(TAG, "Managed media session access is unavailable", error)
            }
    }

    private fun reconcile(controllers: List<MediaController>) {
        val active = controllers.toSet()
        val activeTokens = controllers.map { it.sessionToken }.toSet()
        browserAttributionSessionTokens = browserAttributionSessionTokens.intersect(activeTokens)
        if (!retainBrowserSessions && browserTargetPackages.isNotEmpty() &&
            !browserAttributionResolved
        ) {
            browserAttributionSessionTokens = resolveBrowserAttribution(controllers)
            browserAttributionResolved = true
        }
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

                    override fun onMetadataChanged(metadata: MediaMetadata?) {
                        if (controller.packageName in browserTargetPackages &&
                            browserAttributionSessionTokens.contains(controller.sessionToken) &&
                            mediaUriMatchesBlockedDomain(metadata) == false
                        ) {
                            browserAttributionSessionTokens =
                                browserAttributionSessionTokens - controller.sessionToken
                        }
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
        if (!isTargeted(controller)) return
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
        browserAttributionSessionTokens = emptySet()
        browserAttributionResolved = false
        runCatching {
            sessionManager?.removeOnActiveSessionsChangedListener(sessionsChangedListener)
        }
    }

    private fun unregisterTargetReceiver() {
        if (!receiverRegistered) return
        runCatching { unregisterReceiver(targetReceiver) }
        receiverRegistered = false
    }

    private fun isTargeted(controller: MediaController): Boolean {
        val appPackages = managedAppPackages ?: coordinatorAppPackages
        if (controller.packageName in appPackages) return true
        if (controller.packageName !in browserTargetPackages) return false
        return controller.sessionToken in browserAttributionSessionTokens &&
            mediaUriMatchesBlockedDomain(controller.metadata) != false
    }

    private fun startPolicyMonitoring() {
        stopPolicyMonitoring()
        settingsInitialized = false
        settingsJob = scope.launch {
            DataStoreManager(applicationContext).settings.collectLatest { settings ->
                latestPolicySettings = settings
                handler.post {
                    val now = System.currentTimeMillis()
                    if (settingsInitialized) {
                        // A settings change may revoke or replace the policy that justified
                        // browser attribution. Release retained sessions until the coordinator
                        // supplies a fresh trusted observation/target.
                        clearBrowserAttribution()
                    }
                    settingsInitialized = true
                    refreshManagedPolicy(settings, now)
                }
            }
        }
    }

    private fun stopPolicyMonitoring() {
        settingsJob?.cancel()
        settingsJob = null
        handler.removeCallbacks(policyExpiryCheck)
        latestPolicySettings = null
        settingsInitialized = false
    }

    private fun refreshManagedPolicy(settings: Settings, nowMs: Long) {
        managedAppPackages = effectiveManagedSettings(settings, nowMs)
            .blockedAppGroups
            .asSequence()
            .filter { it.id.startsWith(MANAGED_GROUP_PREFIX) && it.isActive }
            .flatMap { it.selectedPackages.asSequence() }
            .filter(String::isNotBlank)
            .toSet()

        if (browserAttributionExpired(nowMs)) {
            clearBrowserAttribution()
        }
        handler.removeCallbacks(policyExpiryCheck)
        val nextExpiry = settings.managedGroupPolicies
            .asSequence()
            .map { it.expiresAtMs }
            .filter { it > nowMs }
            .minOrNull()
        if (nextExpiry != null) {
            handler.postDelayed(policyExpiryCheck, (nextExpiry - nowMs).coerceAtLeast(1L))
        }
        reconcileActiveSessions()
    }

    private fun browserAttributionExpired(nowMs: Long): Boolean =
        browserAttributionExpiresAtMs?.let { nowMs >= it } == true

    private fun clearBrowserAttribution() {
        browserTargetPackages = emptySet()
        browserBlockedDomains = emptySet()
        retainBrowserSessions = false
        browserAttributionSessionTokens = emptySet()
        browserAttributionResolved = false
        browserAttributionExpiresAtMs = null
    }

    private fun resolveBrowserAttribution(controllers: List<MediaController>): Set<MediaSession.Token> {
        return browserTargetPackages.flatMap { packageName ->
            val candidates = controllers.filter { it.packageName == packageName }
            val uriMatches = candidates.filter { mediaUriMatchesBlockedDomain(it.metadata) == true }
            // Package identity alone does not prove which browser tab owns a session. If the
            // platform omits a media URI, or several sessions remain ambiguous, fail closed.
            uriMatches.map { it.sessionToken }
        }.toSet()
    }

    /** Returns null when the platform does not expose a URI; null keeps the bounded fallback. */
    private fun mediaUriMatchesBlockedDomain(metadata: MediaMetadata?): Boolean? {
        val rawUri = metadata?.getString(MediaMetadata.METADATA_KEY_MEDIA_URI)
            ?.takeIf(String::isNotBlank)
            ?: return null
        val host = runCatching { java.net.URI(rawUri).host }
            .getOrNull()
            ?.lowercase()
            ?.removePrefix("www.")
            ?: return false
        return browserBlockedDomains.any { domain ->
            val normalized = domain.lowercase().removePrefix("www.")
            host == normalized || host.endsWith(".$normalized")
        }
    }

    companion object {
        const val ACTION_UPDATE_TARGETS = "neth.iecal.curbox.managed_media.UPDATE_TARGETS"
        const val ACTION_REQUEST_TARGETS = "neth.iecal.curbox.managed_media.REQUEST_TARGETS"
        private const val EXTRA_APP_PACKAGES = "app_packages"
        private const val EXTRA_BROWSER_PACKAGES = "browser_packages"
        private const val EXTRA_BROWSER_DOMAINS = "browser_domains"
        private const val EXTRA_RETAIN_BROWSER_SESSIONS = "retain_browser_sessions"
        private const val EXTRA_BROWSER_ATTRIBUTION_EXPIRES_AT_MS =
            "browser_attribution_expires_at_ms"
        private const val NO_EXPIRY = Long.MIN_VALUE
        private const val TAG = "ManagedMediaListener"

        /**
         * Reads only whether an active supported-browser session exposes a valid URI host.
         * Missing notification access, no matching session, or an enumeration failure is null.
         */
        fun readBrowserMediaUriPresent(context: Context): Boolean? {
            if (!ManagedMediaCapability.isGranted(context)) return null
            val manager = context.getSystemService(MediaSessionManager::class.java) ?: return null
            val component = ComponentName(context, ManagedMediaNotificationListener::class.java)
            return runCatching {
                ManagedMediaBrowserStatus.fromControllers(manager.getActiveSessions(component))
            }.getOrNull()
        }

        fun sendTarget(context: Context, target: ManagedMediaTarget) {
            context.applicationContext.sendBroadcast(
                Intent(ACTION_UPDATE_TARGETS)
                    .setPackage(context.packageName)
                    .putStringArrayListExtra(EXTRA_APP_PACKAGES, ArrayList(target.appPackages))
                    .putStringArrayListExtra(
                        EXTRA_BROWSER_PACKAGES,
                        ArrayList(target.browserPackages)
                    )
                    .putStringArrayListExtra(
                        EXTRA_BROWSER_DOMAINS,
                        ArrayList(target.browserDomains)
                    )
                    .putExtra(EXTRA_RETAIN_BROWSER_SESSIONS, target.retainBrowserSessions)
                    .putExtra(
                        EXTRA_BROWSER_ATTRIBUTION_EXPIRES_AT_MS,
                        target.browserAttributionExpiresAtMs ?: NO_EXPIRY
                    )
            )
        }
    }
}
