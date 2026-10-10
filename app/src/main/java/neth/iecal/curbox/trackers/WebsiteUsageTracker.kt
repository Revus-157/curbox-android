package neth.iecal.curbox.trackers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.app.KeyguardManager
import android.os.PowerManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import neth.iecal.curbox.data.db.AppDatabase
import neth.iecal.curbox.data.db.WebsiteStatsDao
import neth.iecal.curbox.data.db.WebsiteStatsEntity
import neth.iecal.curbox.hardcoded.URL_BAR_ID_LIST
import neth.iecal.curbox.services.BaseBlockingService
import neth.iecal.curbox.utils.AccessibilityHelper
import neth.iecal.curbox.utils.TimeTools
import java.time.Instant
import java.time.ZoneId
import kotlin.text.endsWith
import kotlin.text.substring

class WebsiteUsageTracker {

    companion object {
        // Flush the running session this often so time is recorded even when a
        // browser (e.g. Firefox/GeckoView) does not fire a url change or leave
        // event that would otherwise trigger a commit.
        private const val HEARTBEAT_MS = 15_000L
    }

    private lateinit var service: BaseBlockingService
    private lateinit var websiteStatsDao: WebsiteStatsDao
    private val observerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val persistenceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // All timing state is read and written on the main thread (accessibility
    // events, the heartbeat and rechecks all post here) so it never races.
    private val mainHandler = Handler(Looper.getMainLooper())

    private lateinit var session: WebsiteForegroundSession
    private var onWebsiteObserved: (WebsiteObservation?) -> Unit = {}

    private var recheckJob: Job? = null
    private var screenReceiverRegistered = false
    @Volatile private var destroyed = false

    private val heartbeat = object : Runnable {
        override fun run() {
            if (destroyed) return
            refreshForegroundApplication()
            applyTransition(session.checkpoint(SystemClock.uptimeMillis()))
            mainHandler.postDelayed(this, HEARTBEAT_MS)
        }
    }

    private val resumeAfterUnlock = Runnable { refreshForegroundApplication() }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    mainHandler.removeCallbacks(resumeAfterUnlock)
                    applyTransition(session.onScreenOff(SystemClock.uptimeMillis()))
                }
                Intent.ACTION_SCREEN_ON -> {
                    if (isScreenAvailable()) session.onScreenOn()
                }
                Intent.ACTION_USER_PRESENT -> {
                    session.onScreenOn()
                    mainHandler.removeCallbacks(resumeAfterUnlock)
                    mainHandler.postDelayed(resumeAfterUnlock, 300L)
                }
            }
        }
    }

    fun setup(
        service: BaseBlockingService,
        onWebsiteObserved: (WebsiteObservation?) -> Unit = {}
    ) {
        this.service = service
        this.onWebsiteObserved = onWebsiteObserved
        val db = AppDatabase.getInstance(service)
        this.websiteStatsDao = db.websiteStatsDao()
        val powerManager = service.getSystemService(Context.POWER_SERVICE) as PowerManager
        session = WebsiteForegroundSession(screenOn = powerManager.isInteractive && isScreenAvailable())
        registerScreenReceiver()
        startObservingRecheckTime()
        mainHandler.postDelayed(heartbeat, HEARTBEAT_MS)
    }

    private fun startObservingRecheckTime() {
        observerScope.launch {
            service.dataStoreManager.settings.collect { settings ->
                val enabled = settings.isWebsiteUsageTrackingEnabled
                mainHandler.post {
                    applyTransition(session.setTrackingEnabled(enabled, SystemClock.uptimeMillis()))
                }
                val nextRecheck = settings.nextWebsiteRecheckTime
                if (nextRecheck > System.currentTimeMillis()) {
                    scheduleRecheck(nextRecheck)
                }
            }
        }
    }

    private fun scheduleRecheck(recheckTime: Long) {
        recheckJob?.cancel()
        recheckJob = observerScope.launch {
            val delayMs = recheckTime - System.currentTimeMillis()
            if (delayMs > 0) {
                kotlinx.coroutines.delay(delayMs)
                Log.d("WebsiteUsageTracker", "Executing scheduled recheck")
                mainHandler.post {
                    applyTransition(session.checkpoint(SystemClock.uptimeMillis()))
                    emitCurrentObservation()
                }
            }
        }
    }

    private fun filterOutUrlFromPlainText(inputText: String?): String? {
        if (inputText.isNullOrBlank()) return null

        val urlRegex = Regex(
            pattern = """(?:https?://|www\.)?[^\s<>\"']+""",
            option = RegexOption.IGNORE_CASE
        )

        for (match in urlRegex.findAll(inputText)) {
            val cleanUrl = match.value
                .trimStart('(', '[', '{')
                .trimEnd('.', ',', ')', ']', '}', '!', ';', ':')
            val uriText = if (cleanUrl.startsWith("http://", ignoreCase = true) ||
                cleanUrl.startsWith("https://", ignoreCase = true)
            ) cleanUrl else "https://$cleanUrl"

            val uri = runCatching { java.net.URI(uriText) }.getOrNull() ?: continue
            val host = uri.host ?: continue
            if (!host.contains('.')) continue

            val normalizedHost = host.removePrefix("www.")
            val path = uri.rawPath.orEmpty().let { if (it == "/") "" else it }
            val query = uri.rawQuery?.let { "?$it" }.orEmpty()
            val fragment = uri.rawFragment?.let { "#$it" }.orEmpty()
            return "$normalizedHost$path$query$fragment"
        }

        return null
    }
    fun onEvent(event: AccessibilityEvent?) {
        if (event == null || destroyed) return

        val rootNode = service.rootInActiveWindow
        val foregroundPackage = findForegroundApplicationPackage(
            rootNode?.packageName?.toString(),
            rootNode?.window?.type
        )
        if (foregroundPackage != null) {
            mainHandler.post {
                if (destroyed) return@post
                if (!isScreenAvailable()) {
                    applyTransition(session.onScreenOff(SystemClock.uptimeMillis()))
                    return@post
                }
                session.onScreenOn()
                applyTransition(
                    session.onForegroundApplication(foregroundPackage, SystemClock.uptimeMillis())
                )
            }
        }

        val packageName = event.packageName?.toString() ?: return
        if (!URL_BAR_ID_LIST.containsKey(packageName)) return
        // A queued browser event must not resurrect or replace a session after
        // another application has become the foreground window.
        if (foregroundPackage != packageName) return

        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_VIEW_CLICKED &&
            event.eventType != AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED
        ) {
            return
        }

        rootNode ?: return
        val urlBarInfo = URL_BAR_ID_LIST[packageName] ?: return


        try {
            val nodes = AccessibilityHelper.findElementById(
                rootNode,
                urlBarInfo.displayUrlBarId
            ) ?: AccessibilityHelper.findElementById(
                event.source,
                urlBarInfo.displayUrlBarId
            ) ?: return
            val text = (nodes.text ?: nodes.contentDescription).toString()

            if (text.isNotEmpty()) {
                val filteredUrl = filterOutUrlFromPlainText(text)
                val siteInfo = extractSiteInfo(filteredUrl?:text)
                if (siteInfo.domain.isNotEmpty()) {
                    mainHandler.post { observeWebsite(packageName, siteInfo) }
                }
            }
        } catch (e: Exception) {
            Log.e("WebsiteUsageTracker", "Failed to find node", e)
        }
    }

    private data class SiteInfo(val domain: String, val urlIdentifier: String)
    private data class HourSlice(val date: String, val hour: Int, val durationMs: Long)

    private fun applyTransition(transition: WebsiteSessionTransition) {
        if (destroyed) return
        transition.commits.forEach(::saveSession)
        when (val change = transition.observationChange) {
            WebsiteObservationChange.None -> Unit
            WebsiteObservationChange.Cleared -> onWebsiteObserved(null)
            is WebsiteObservationChange.Observed -> {
                val website = change.website
                onWebsiteObserved(
                    WebsiteObservation(website.packageName, website.domain, website.urlIdentifier)
                )
                saveInitialSession(website)
            }
        }
    }

    private fun findForegroundApplicationPackage(
        activeRootPackage: String?,
        activeRootWindowType: Int?
    ): String? {
        val windows = try {
            service.windows
                .map {
                    WebsiteWindowEvidence(
                        packageName = it.root?.packageName?.toString(),
                        isApplication = it.type == AccessibilityWindowInfo.TYPE_APPLICATION,
                        isActive = it.isActive,
                        isFocused = it.isFocused
                    )
                }
        } catch (_: Exception) {
            emptyList()
        }
        return selectForegroundApplication(
            windows = windows,
            activeRootPackage = activeRootPackage,
            activeRootIsApplication = activeRootWindowType == AccessibilityWindowInfo.TYPE_APPLICATION,
            isSupportedBrowser = URL_BAR_ID_LIST::containsKey
        )
    }

    private fun refreshForegroundApplication() {
        if (destroyed) return
        if (!isScreenAvailable()) {
            applyTransition(session.onScreenOff(SystemClock.uptimeMillis()))
            return
        }
        session.onScreenOn()
        val root = try {
            service.rootInActiveWindow
        } catch (_: Exception) {
            null
        }
        val foreground = findForegroundApplicationPackage(
            root?.packageName?.toString(),
            root?.window?.type
        ) ?: return
        applyTransition(session.onForegroundApplication(foreground, SystemClock.uptimeMillis()))
    }

    private fun isScreenAvailable(): Boolean {
        val power = service.getSystemService(Context.POWER_SERVICE) as PowerManager
        val keyguard = service.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        return power.isInteractive && !keyguard.isKeyguardLocked
    }

    private fun registerScreenReceiver() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        service.registerReceiver(screenReceiver, filter)
        screenReceiverRegistered = true
    }

    private fun observeWebsite(packageName: String, siteInfo: SiteInfo) {
        if (destroyed || !isScreenAvailable()) return
        val observed = TrackedWebsite(packageName, siteInfo.domain, siteInfo.urlIdentifier)
        applyTransition(session.observe(observed, SystemClock.uptimeMillis()))
    }

    private fun emitCurrentObservation() {
        session.currentWebsite()?.let {
            onWebsiteObserved(WebsiteObservation(it.packageName, it.domain, it.urlIdentifier))
        }
    }

    private fun splitByLocalHour(startMs: Long, endMs: Long): List<HourSlice> {
        if (endMs <= startMs) return emptyList()
        val zone = ZoneId.systemDefault()
        val slices = ArrayList<HourSlice>(2)
        var cursor = startMs
        while (cursor < endMs) {
            val current = Instant.ofEpochMilli(cursor).atZone(zone)
            val nextHour = current.withMinute(0).withSecond(0).withNano(0)
                .plusHours(1).toInstant().toEpochMilli()
            val sliceEnd = minOf(endMs, nextHour.coerceAtLeast(cursor + 1))
            slices += HourSlice(
                date = TimeTools.dayKey(current.toLocalDate()),
                hour = current.hour,
                durationMs = sliceEnd - cursor
            )
            cursor = sliceEnd
        }
        return slices
    }

    private fun extractSiteInfo(urlText: String): SiteInfo {
        return try {
            var url = urlText
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                url = "https://$url"
            }
            val uri = java.net.URI(url)
            val domain = (uri.host ?: urlText).lowercase().removePrefix("www.")

            // Keep the complete URL identifier because keyword rules can target
            // nested paths, query values, or fragments. Usage limits aggregate
            // every matching identifier later, so URL changes remain combined.
            val path = uri.rawPath.orEmpty().let { if (it == "/") "" else it }
            val query = uri.rawQuery?.let { "?$it" }.orEmpty()
            val fragment = uri.rawFragment?.let { "#$it" }.orEmpty()
            val identifier = "$domain$path$query$fragment"

            SiteInfo(domain, identifier)
        } catch (e: Exception) {
            SiteInfo(urlText, urlText)
        }
    }


    private fun saveInitialSession(website: TrackedWebsite) {
        if (!session.trackingEnabled) return
        with(website) {
            val date = TimeTools.getCurrentDate()
            val wallNow = System.currentTimeMillis()
            persistenceScope.launch {
                try {
                    // Make the row visible immediately without ever touching
                    // totalTime, so an in flight time increment is never clobbered.
                    websiteStatsDao.insertIfAbsent(
                        WebsiteStatsEntity(
                            date = date,
                            packageName = packageName,
                            urlIdentifier = urlIdentifier,
                            domain = domain,
                            totalTime = 0L,
                            lastVisited = wallNow
                        )
                    )
                    websiteStatsDao.touch(date, packageName, urlIdentifier, wallNow)
                } catch (e: Exception) {
                    Log.e("WebsiteUsageTracker", "Failed to save initial website trace", e)
                }
            }
        }
    }

    private fun saveSession(commit: WebsiteUsageCommit) {
        val (website, durationMs) = commit
        val (packageName, domain, identifier) = website
        val wallNow = System.currentTimeMillis()
        val wallStart = (wallNow - durationMs).coerceAtMost(wallNow)
        persistenceScope.launch {
            try {
                splitByLocalHour(wallStart, wallNow).forEach { slice ->
                    val entity = WebsiteStatsEntity(
                        date = slice.date,
                        packageName = packageName,
                        urlIdentifier = identifier,
                        domain = domain,
                        totalTime = 0L,
                        lastVisited = wallNow
                    )
                    websiteStatsDao.insertIfAbsent(entity)
                    websiteStatsDao.addTime(
                        slice.date,
                        packageName,
                        identifier,
                        slice.hour,
                        slice.durationMs,
                        wallNow
                    )
                }
            } catch (e: Exception) {
                Log.e("WebsiteUsageTracker", "Failed to save website trace", e)
            }
        }
    }

    fun onDestroy() {
        mainHandler.removeCallbacks(heartbeat)
        mainHandler.removeCallbacks(resumeAfterUnlock)
        recheckJob?.cancel()
        applyTransition(session.destroy(SystemClock.uptimeMillis()))
        destroyed = true
        if (screenReceiverRegistered) {
            try {
                service.unregisterReceiver(screenReceiver)
            } catch (_: Exception) {
            }
            screenReceiverRegistered = false
        }
        observerScope.cancel()
        // Drain finite database writes off the service main thread. Infinite
        // settings observers belong to the separately cancelled observer scope.
        val pendingWrites = persistenceScope.coroutineContext[Job]?.children?.toList().orEmpty()
        persistenceScope.launch {
            try {
                pendingWrites.joinAll()
            } finally {
                persistenceScope.cancel()
            }
        }
    }
}
