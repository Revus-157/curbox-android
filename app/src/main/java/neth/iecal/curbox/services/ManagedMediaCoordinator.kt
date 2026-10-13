package neth.iecal.curbox.services

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import neth.iecal.curbox.data.models.Settings
import neth.iecal.curbox.trackers.WebsiteObservation

/** Bridges the accessibility process to the separately managed notification-listener process. */
class ManagedMediaCoordinator(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var settingsJob: Job? = null
    private var receiverRegistered = false
    private var latestSettings: Settings? = null
    private var latestObservation: WebsiteObservation? = null

    private val targetRequestReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ManagedMediaNotificationListener.ACTION_REQUEST_TARGETS) {
                dispatch()
            }
        }
    }

    fun start() {
        if (settingsJob?.isActive == true) return
        ContextCompat.registerReceiver(
            context,
            targetRequestReceiver,
            IntentFilter(ManagedMediaNotificationListener.ACTION_REQUEST_TARGETS),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        receiverRegistered = true
        settingsJob = scope.launch {
            neth.iecal.curbox.utils.DataStoreManager(context.applicationContext).settings
                .collectLatest { settings ->
                    latestSettings = settings
                    dispatch()
                }
        }
    }

    fun onWebsiteObserved(observation: WebsiteObservation?) {
        latestObservation = observation
        dispatch()
    }

    fun stop() {
        settingsJob?.cancel()
        settingsJob = null
        if (receiverRegistered) {
            runCatching { context.unregisterReceiver(targetRequestReceiver) }
            receiverRegistered = false
        }
        scope.coroutineContext.cancel()
        latestSettings = null
        latestObservation = null
        ManagedMediaNotificationListener.sendTarget(context, ManagedMediaTarget())
    }

    private fun dispatch() {
        val settings = latestSettings ?: return
        val target = ManagedMediaEnforcement.targetFor(
            settings,
            System.currentTimeMillis(),
            latestObservation
        )
        ManagedMediaNotificationListener.sendTarget(context, target)
    }
}
